@file:OptIn(org.jetbrains.kotlin.ir.symbols.UnsafeDuringIrConstructionAPI::class)

package jasper.compiler

import jasper.translator.*
import org.jetbrains.kotlin.descriptors.ClassKind
import org.jetbrains.kotlin.ir.*
import org.jetbrains.kotlin.ir.declarations.*
import org.jetbrains.kotlin.ir.expressions.*
import org.jetbrains.kotlin.ir.expressions.impl.*
import org.jetbrains.kotlin.ir.types.*
import org.jetbrains.kotlin.ir.symbols.*
import org.jetbrains.org.objectweb.asm.*
import java.io.File
import java.nio.file.Path

class JvmIrBackend(private val typeMapper: TypeMapper) {
    private val graalvmWarnings = mutableListOf<String>()

    fun getGraalvmWarnings(): List<String> = graalvmWarnings.toList()

    private fun checkGraalvmHostilePatterns(translationResult: TranslationResult) {
        for (file in translationResult.irFiles) {
            for (decl in file.declarations) {
                checkIrDeclarationForGraalvm(decl)
            }
        }
    }

    private fun checkIrDeclarationForGraalvm(decl: IrDeclaration) {
        when (decl) {
            is IrSimpleFunction -> {
                val body = decl.body
                if (body is IrBlockBody) {
                    for (stmt in body.statements) {
                        checkIrStatementForGraalvm(stmt)
                    }
                }
            }
            is IrClass -> {
                for (classDecl in decl.declarations) {
                    checkIrDeclarationForGraalvm(classDecl)
                }
            }
            else -> {}
        }
    }

    private fun checkIrStatementForGraalvm(stmt: IrStatement) {
        when (stmt) {
            is IrBlock -> stmt.statements.forEach { checkIrStatementForGraalvm(it) }
            is IrExpression -> checkIrExpressionForGraalvm(stmt)
            else -> {}
        }
    }

    private fun checkIrExpressionForGraalvm(expr: IrExpression) {
        when (expr) {
            is IrCall -> {
                val name = callNames[expr.symbol] ?: functionSymbolToName[expr.symbol]
                if (name == "invokedynamic") {
                    graalvmWarnings.add("invokedynamic detected in IR call — GraalVM native-image may require additional configuration")
                }
            }
            is IrBlock -> expr.statements.forEach { checkIrStatementForGraalvm(it) }
            is IrWhen -> {
                for (branch in expr.branches) {
                    checkIrExpressionForGraalvm(branch.condition)
                    when (val r = branch.result) {
                        is IrBlock -> r.statements.forEach { checkIrStatementForGraalvm(it) }
                        else -> checkIrExpressionForGraalvm(r)
                    }
                }
            }
            is IrFunctionExpression -> {
                val func = expr.function
                val body = func.body
                if (body is IrBlockBody) {
                    for (stmt in body.statements) {
                        checkIrStatementForGraalvm(stmt)
                    }
                }
            }
            else -> {}
        }
    }

    data class LocalInfo(val name: String, val irType: IrType?, val slot: Int)
    data class MethodContext(
        val mv: MethodVisitor,
        val locals: MutableList<LocalInfo>,
        val variables: MutableMap<String, Int>,
        val symbolToName: MutableMap<IrValueSymbol, String>,
        var nextSlot: Int,
        val returnType: IrType?,
        val isStatic: Boolean,
        val loopStack: MutableList<Pair<Label, Label>> = mutableListOf(),
        val deferredBodies: MutableList<IrBlock> = mutableListOf()
    )

    private var currentPkg: String = ""
    private val functionSymbolToName = mutableMapOf<IrSimpleFunctionSymbol, String>()
    private val functionSymbolToOwner = mutableMapOf<IrSimpleFunctionSymbol, String>()
    private val fieldSymbolToName = mutableMapOf<IrFieldSymbol, String>()
    private val fieldOwnerMap = mutableMapOf<IrFieldSymbol, String>()
    private var classSuperclassNames: Map<String, String> = emptyMap()
    private var classInterfaceNames: Map<String, List<String>> = emptyMap()
    private var interfaceDeclNames: Set<String> = emptySet()
    private var callNames: Map<IrSimpleFunctionSymbol, String> = emptyMap()
    private var constructorTypes: Map<IrConstructorSymbol, String> = emptyMap()

    private fun resolveFieldOwnerFromSymbol(symbol: IrFieldSymbol): String {
        return fieldOwnerMap[symbol] ?: currentPkg.replace('.', '/')
    }

    private fun resolveFieldOwner(symbol: IrFieldSymbol): String {
        return resolveFieldOwnerFromSymbol(symbol)
    }

    private fun resolveFieldOwner(receiver: IrExpression): String {
        // If receiver is a GetValue for a local variable, try to determine its type
        if (receiver is IrGetValue) {
            val recvType = receiver.type
            val typeName = typeMapper.jvmInternalNameFromIrType(recvType)
            if (typeName.isNotEmpty() && typeName != "java/lang/Object") {
                return typeName
            }
        }
        return currentPkg
    }

    private fun isArrayType(type: IrType): Boolean =
        typeMapper.jvmDescriptorFromIrType(type).startsWith("[")

    fun generate(translationResult: TranslationResult, outputDir: Path) {
        functionSymbolToName.clear()
        functionSymbolToOwner.clear()
        fieldSymbolToName.clear()
        fieldOwnerMap.clear()
        graalvmWarnings.clear()
        callNames = translationResult.callNames
        constructorTypes = translationResult.constructorTypes
        classSuperclassNames = translationResult.classSuperclassNames
        classInterfaceNames = translationResult.classInterfaceNames
        interfaceDeclNames = translationResult.interfaceDeclNames
        checkGraalvmHostilePatterns(translationResult)
        for (file in translationResult.irFiles) {
            for (decl in file.declarations) {
                when (decl) {
                    is IrSimpleFunction -> {
                        functionSymbolToName[decl.symbol] = decl.name.toString()
                        val ownerName = "${file.packageFqName?.toString()?.replace('.', '/') ?: ""}/${decl.name.toString()}"
                        functionSymbolToOwner[decl.symbol] = ownerName
                    }
                    is IrClass -> {
                        val internalName = "${file.packageFqName?.toString()?.replace('.', '/') ?: ""}/${decl.name.toString()}"
                        for (classDecl in decl.declarations) {
                            when (classDecl) {
                                is IrSimpleFunction -> {
                                    functionSymbolToName[classDecl.symbol] = classDecl.name.toString()
                                    functionSymbolToOwner[classDecl.symbol] = internalName
                                }
                                is IrField -> {
                                    fieldSymbolToName[classDecl.symbol] = classDecl.name.toString()
                                    fieldOwnerMap[classDecl.symbol] = internalName
                                }
                                else -> {}
                            }
                        }
                    }
                    else -> {}
                }
            }
        }
        for (file in translationResult.irFiles) {
            currentPkg = file.packageFqName?.toString()?.replace('.', '/') ?: ""
            val dir = outputDir.resolve(currentPkg).toFile()
            dir.mkdirs()
            for (decl in file.declarations) {
                generateTopLevelDeclaration(decl, dir)
            }
        }
    }

    private fun generateTopLevelDeclaration(decl: IrDeclaration, dir: File) {
        when (decl) {
            is IrSimpleFunction -> generateTopLevelFunction(decl, dir)
            is IrClass -> generateIrClass(decl, dir)
            else -> {}
        }
    }

    private fun generateTopLevelFunction(func: IrSimpleFunction, dir: File) {
        val name = func.name.toString()
        val internalName = "$currentPkg/$name"
         val cw = ClassWriter(ClassWriter.COMPUTE_FRAMES)
        cw.visit(Opcodes.V1_8, Opcodes.ACC_PUBLIC or Opcodes.ACC_SUPER, internalName, null, "java/lang/Object", emptyArray())
        generateIrFunction(cw, func, internalName, true)
        if (name == "main" && func.valueParameters.isEmpty()) {
            val mv = cw.visitMethod(Opcodes.ACC_PUBLIC or Opcodes.ACC_STATIC, "main", "([Ljava/lang/String;)V", null, null)
            mv.visitCode()
            mv.visitMethodInsn(Opcodes.INVOKESTATIC, internalName, "main", irMethodDescriptor(func), false)
            mv.visitInsn(Opcodes.RETURN)
            mv.visitMaxs(0, 0)
            mv.visitEnd()
        }
        cw.visitEnd()
        val bytes = cw.toByteArray()
        dir.resolve("$name.class").writeBytes(bytes)
    }

    private fun generateIrClass(cls: IrClass, dir: File) {
        val name = cls.name.toString()
        val internalName = "$currentPkg/$name"
        val cw = ClassWriter(ClassWriter.COMPUTE_FRAMES)
        val isInterface = cls.kind == ClassKind.INTERFACE
        val isEnum = cls.kind == ClassKind.ENUM_CLASS
        val isAnnotation = cls.kind == ClassKind.ANNOTATION_CLASS
        val classFlags = when {
            isInterface -> Opcodes.ACC_PUBLIC or Opcodes.ACC_INTERFACE or Opcodes.ACC_ABSTRACT
            isEnum -> Opcodes.ACC_PUBLIC or Opcodes.ACC_SUPER or Opcodes.ACC_ENUM
            isAnnotation -> Opcodes.ACC_PUBLIC or Opcodes.ACC_ANNOTATION or Opcodes.ACC_INTERFACE or Opcodes.ACC_ABSTRACT
            else -> Opcodes.ACC_PUBLIC or Opcodes.ACC_SUPER
        }
        val superName = when {
            isEnum -> "java/lang/Enum"
            classSuperclassNames.containsKey(cls.name.toString()) -> classSuperclassNames[cls.name.toString()]!!
            else -> "java/lang/Object"
        }
        val interfaceNames = when {
            isAnnotation -> arrayOf("java/lang/annotation/Annotation")
            classInterfaceNames.containsKey(cls.name.toString()) -> classInterfaceNames[cls.name.toString()]!!.toTypedArray()
            else -> emptyArray()
        }
        val classSig = typeMapper.buildClassSignature(cls, superName)
        cw.visit(Opcodes.V1_8, classFlags, internalName, classSig, superName, interfaceNames)

        for (decl in cls.declarations) {
            when (decl) {
                is IrSimpleFunction -> {
                    val methodFlags = if (isInterface) Opcodes.ACC_PUBLIC or Opcodes.ACC_ABSTRACT else Opcodes.ACC_PUBLIC
                    generateIrFunction(cw, decl, internalName, false, methodFlags)
                }
                is IrConstructor -> {
                    // Handle enum constructors: they need synthetic (String, int) prefix
                    if (isEnum) {
                        generateEnumConstructor(cw, decl, internalName)
                    } else {
                        generateIrConstructor(cw, decl, internalName)
                    }
                }
                is IrField -> {
                    val fieldFlags = if (isEnum) Opcodes.ACC_PUBLIC or Opcodes.ACC_STATIC or Opcodes.ACC_FINAL or Opcodes.ACC_ENUM
                        else Opcodes.ACC_PUBLIC
                    val fieldDesc = typeMapper.jvmDescriptorFromIrType(decl.type)
                    val fieldSig = typeMapper.buildFieldSignature(decl)
                    cw.visitField(fieldFlags, decl.name.toString(), fieldDesc, fieldSig, null)
                }
                is IrProperty -> {
                    // Property access is handled directly via IrGetField/IrSetField at the IR level.
                    // Accessor functions (getter/setter) are for custom accessor bodies only.
                    // Skip if body is null to avoid generating illegal JVM names like <getter-foo>.
                    val getter = decl.getter
                    if (getter != null && getter.body != null) {
                        generateIrFunction(cw, getter, internalName, false)
                    }
                    val setter = decl.setter
                    if (setter != null && setter.body != null) {
                        generateIrFunction(cw, setter, internalName, false)
                    }
                }
                else -> {}
            }
        }
        if (isEnum) {
            generateEnumInfrastructure(cw, cls, internalName)
        }
        cw.visitEnd()
        val bytes = cw.toByteArray()
        dir.resolve("$name.class").writeBytes(bytes)
    }

    private fun generateEnumInfrastructure(cw: ClassWriter, cls: IrClass, internalName: String) {
        val enumConstNames = mutableListOf<String>()
        for (decl in cls.declarations) {
            if (decl is IrField && decl.name.toString().let { it[0].isUpperCase() }) {
                enumConstNames.add(decl.name.toString())
            }
        }
        if (enumConstNames.isEmpty()) return
        val n = enumConstNames.size
        val valuesDesc = "[L$internalName;"
        cw.visitField(Opcodes.ACC_PRIVATE or Opcodes.ACC_STATIC or Opcodes.ACC_FINAL or Opcodes.ACC_SYNTHETIC,
            "__jasper_values", valuesDesc, null, null)
        val clMv = cw.visitMethod(Opcodes.ACC_STATIC, "<clinit>", "()V", null, null)
        clMv.visitCode()
        for ((i, constName) in enumConstNames.withIndex()) {
            clMv.visitTypeInsn(Opcodes.NEW, internalName)
            clMv.visitInsn(Opcodes.DUP)
            clMv.visitLdcInsn(constName)
            clMv.visitIntInsn(Opcodes.SIPUSH, i)
            clMv.visitMethodInsn(Opcodes.INVOKESPECIAL, internalName, "<init>", "(Ljava/lang/String;I)V", false)
            clMv.visitFieldInsn(Opcodes.PUTSTATIC, internalName, constName, "L$internalName;")
        }
        clMv.visitIntInsn(Opcodes.BIPUSH, n)
        clMv.visitTypeInsn(Opcodes.ANEWARRAY, internalName)
        for ((i, constName) in enumConstNames.withIndex()) {
            clMv.visitInsn(Opcodes.DUP)
            clMv.visitIntInsn(Opcodes.BIPUSH, i)
            clMv.visitFieldInsn(Opcodes.GETSTATIC, internalName, constName, "L$internalName;")
            clMv.visitInsn(Opcodes.AASTORE)
        }
        clMv.visitFieldInsn(Opcodes.PUTSTATIC, internalName, "__jasper_values", valuesDesc)
        clMv.visitInsn(Opcodes.RETURN)
        clMv.visitMaxs(0, 0)
        clMv.visitEnd()
        val vMv = cw.visitMethod(Opcodes.ACC_PUBLIC or Opcodes.ACC_STATIC, "values", "()[L$internalName;", null, null)
        vMv.visitCode()
        vMv.visitFieldInsn(Opcodes.GETSTATIC, internalName, "__jasper_values", valuesDesc)
        vMv.visitMethodInsn(Opcodes.INVOKEVIRTUAL, "[L$internalName;", "clone", "()Ljava/lang/Object;", false)
        vMv.visitTypeInsn(Opcodes.CHECKCAST, valuesDesc.removePrefix("["))
        vMv.visitInsn(Opcodes.ARETURN)
        vMv.visitMaxs(0, 0)
        vMv.visitEnd()
        val vfMv = cw.visitMethod(Opcodes.ACC_PUBLIC or Opcodes.ACC_STATIC, "valueOf", "(Ljava/lang/String;)L$internalName;", null, null)
        vfMv.visitCode()
        vfMv.visitLdcInsn(org.jetbrains.org.objectweb.asm.Type.getType("L$internalName;"))
        vfMv.visitVarInsn(Opcodes.ALOAD, 0)
        vfMv.visitMethodInsn(Opcodes.INVOKESTATIC, "java/lang/Enum", "valueOf", "(Ljava/lang/Class;Ljava/lang/String;)Ljava/lang/Enum;", false)
        vfMv.visitTypeInsn(Opcodes.CHECKCAST, internalName)
        vfMv.visitInsn(Opcodes.ARETURN)
        vfMv.visitMaxs(0, 0)
        vfMv.visitEnd()
    }

    private fun generateEnumConstructor(cw: ClassWriter, ctor: IrConstructor, owner: String) {
        // Enum constructors need synthetic (String name, int ordinal) prefix before user params
        val paramDescs = ctor.valueParameters.joinToString("") {
            typeMapper.jvmDescriptorFromIrType(it.type)
        }
        val desc = "(Ljava/lang/String;I$paramDescs)V"
        val mv = cw.visitMethod(Opcodes.ACC_PRIVATE, "<init>", desc, null, null)
        mv.visitCode()
        mv.visitVarInsn(Opcodes.ALOAD, 0)
        mv.visitVarInsn(Opcodes.ALOAD, 1)
        mv.visitVarInsn(Opcodes.ILOAD, 2)
        mv.visitMethodInsn(Opcodes.INVOKESPECIAL, "java/lang/Enum", "<init>", "(Ljava/lang/String;I)V", false)
        // Translate constructor body
        val body = ctor.body
        if (body is IrBlockBody) {
            val locals = mutableListOf<LocalInfo>()
            val variables = mutableMapOf<String, Int>()
            val symbolToName = mutableMapOf<IrValueSymbol, String>()
            locals.add(LocalInfo("this", null, 0))
            var slot = 3 // this(0), name(1), ordinal(2) are synthetic
            for (p in ctor.valueParameters) {
                val pName = p.name.toString()
                locals.add(LocalInfo(pName, p.type, slot))
                variables[pName] = slot
                symbolToName[p.symbol] = pName
                slot += irTypeSlots(p.type)
            }
            val ctx = MethodContext(mv, locals, variables, symbolToName, slot, null, false)
            for (stmt in body.statements) {
                generateIrStatement(mv, stmt, ctx, owner)
            }
        }
        mv.visitInsn(Opcodes.RETURN)
        mv.visitMaxs(0, 0)
        mv.visitEnd()
    }

    private fun generateIrFunction(cw: ClassWriter, func: IrSimpleFunction, owner: String, isStatic: Boolean, methodFlags: Int = -1) {
        val desc = irMethodDescriptor(func)
        val access = if (methodFlags >= 0) methodFlags else (Opcodes.ACC_PUBLIC or (if (isStatic) Opcodes.ACC_STATIC else 0))
        val methodSig = if (func.typeParameters.isNotEmpty() || typeMapper.hasGenericParams(func)) {
            typeMapper.buildMethodSignature(func)
        } else null
        val mv = cw.visitMethod(access, func.name.toString(), desc, methodSig, null)
        // For abstract methods (e.g., interface methods), don't emit a Code attribute
        if ((access and Opcodes.ACC_ABSTRACT) != 0) {
            mv.visitEnd()
            return
        }
        mv.visitCode()
        val body = func.body
        if (body is IrBlockBody) {
            val locals = mutableListOf<LocalInfo>()
            val variables = mutableMapOf<String, Int>()
            val symbolToName = mutableMapOf<IrValueSymbol, String>()
            var slot = if (isStatic) 0 else 1
            for (p in func.valueParameters) {
                val pName = p.name.toString()
                locals.add(LocalInfo(pName, p.type, slot))
                variables[pName] = slot
                symbolToName[p.symbol] = pName
                slot += irTypeSlots(p.type)
            }
            val ctx = MethodContext(mv, locals, variables, symbolToName, slot, func.returnType, isStatic)
            generateIrBlockBody(mv, body, ctx, owner)
            if (!hasIrReturn(body)) {
                generateIrReturn(mv, func.returnType)
            }
        } else {
            generateIrReturn(mv, func.returnType)
        }
        mv.visitMaxs(0, 0)
        mv.visitEnd()
    }

    private fun generateIrConstructor(cw: ClassWriter, ctor: IrConstructor, owner: String) {
        val desc = irConstructorDescriptor(ctor)
        val mv = cw.visitMethod(Opcodes.ACC_PUBLIC, "<init>", desc, null, null)
        mv.visitCode()
        val body = ctor.body
        // Check if constructor has delegation (first statement is a ctor_delegate call)
        val hasDelegate = body is IrBlockBody && body.statements.isNotEmpty() &&
            body.statements[0] is IrCall &&
            callNames[(body.statements[0] as IrCall).symbol]?.startsWith("ctor_delegate:") == true
        if (!hasDelegate) {
            mv.visitVarInsn(Opcodes.ALOAD, 0)
            val simpleName = owner.substringAfterLast('/')
            val superName = classSuperclassNames[simpleName] ?: "java/lang/Object"
            mv.visitMethodInsn(Opcodes.INVOKESPECIAL, superName, "<init>", "()V", false)
        }
        if (body is IrBlockBody) {
            val locals = mutableListOf<LocalInfo>()
            val variables = mutableMapOf<String, Int>()
            val symbolToName = mutableMapOf<IrValueSymbol, String>()
            locals.add(LocalInfo("this", null, 0))
            var slot = 1
            for (p in ctor.valueParameters) {
                val pName = p.name.toString()
                locals.add(LocalInfo(pName, p.type, slot))
                variables[pName] = slot
                symbolToName[p.symbol] = pName
                slot += irTypeSlots(p.type)
            }
            val ctx = MethodContext(mv, locals, variables, symbolToName, slot, null, false)
            generateIrBlockBody(mv, body, ctx, owner)
        }
        mv.visitInsn(Opcodes.RETURN)
        mv.visitMaxs(0, 0)
        mv.visitEnd()
    }

    private fun generateIrBlockBody(mv: MethodVisitor, body: IrBlockBody, ctx: MethodContext, owner: String) {
        for (stmt in body.statements) {
            generateIrStatement(mv, stmt, ctx, owner)
        }
    }

    private fun generateIrStatement(mv: MethodVisitor, stmt: IrStatement, ctx: MethodContext, owner: String) {
        when (stmt) {
            is IrVariable -> {
                val name = stmt.name.toString()
                val desc = typeMapper.jvmDescriptorFromIrType(stmt.type)
                val slot = ctx.nextSlot
                ctx.nextSlot += if (desc == "J" || desc == "D") 2 else 1
                ctx.variables[name] = slot
                ctx.locals.add(LocalInfo(name, stmt.type, slot))
                ctx.symbolToName[stmt.symbol] = name
                val init = stmt.initializer
                if (init != null) {
                    generateIrExpression(mv, init, ctx, owner)
                    val initDesc = typeMapper.jvmDescriptorFromIrType(init.type)
                    widenIfNeeded(mv, initDesc, desc)
                    emitBoxingIfNeeded(mv, initDesc, desc)
                    storeLocal(mv, desc, slot)
                }
            }
            is IrReturn -> {
                val value = stmt.value
                if (!isIrVoidExpr(value)) {
                    generateIrExpression(mv, value, ctx, owner)
                    val valueDesc = typeMapper.jvmDescriptorFromIrType(value.type)
                    val retType = ctx.returnType
                    if (retType != null) {
                        val returnDesc = typeMapper.jvmReturnDescriptorFromIrType(retType)
                        widenIfNeeded(mv, valueDesc, returnDesc)
                        emitBoxingIfNeeded(mv, valueDesc, returnDesc)
                    }
                }
                generateIrReturn(mv, ctx.returnType)
            }
            is IrBlock -> {
                for (s in stmt.statements) {
                    generateIrStatement(mv, s, ctx, owner)
                }
            }
            is IrWhen -> generateIrWhen(mv, stmt, ctx, owner)
            is IrWhileLoop -> {
                val loopLabel = Label()
                val endLabel = Label()
                ctx.loopStack.add(Pair(loopLabel, endLabel))
                mv.visitLabel(loopLabel)
                val cond = stmt.condition
                generateIrExpression(mv, cond, ctx, owner)
                mv.visitJumpInsn(Opcodes.IFEQ, endLabel)
                val loopBody = stmt.body
                if (loopBody is IrBlock) {
                    for (s in loopBody.statements) {
                        generateIrStatement(mv, s, ctx, owner)
                    }
                }
                mv.visitJumpInsn(Opcodes.GOTO, loopLabel)
                mv.visitLabel(endLabel)
                ctx.loopStack.removeLast()
            }
            is IrDoWhileLoop -> {
                val loopLabel = Label()
                val endLabel = Label()
                ctx.loopStack.add(Pair(loopLabel, endLabel))
                mv.visitLabel(loopLabel)
                val loopBody = stmt.body
                if (loopBody is IrBlock) {
                    for (s in loopBody.statements) {
                        generateIrStatement(mv, s, ctx, owner)
                    }
                }
                val cond = stmt.condition
                generateIrExpression(mv, cond, ctx, owner)
                mv.visitJumpInsn(Opcodes.IFNE, loopLabel)
                mv.visitLabel(endLabel)
                ctx.loopStack.removeLast()
            }
            is IrTry -> {
                if (stmt.catches.isEmpty()) {
                    val tryBody = stmt.tryResult
                    if (tryBody is IrBlock) {
                        for (s in tryBody.statements) {
                            generateIrStatement(mv, s, ctx, owner)
                        }
                    }
                    val finallyExpr = stmt.finallyExpression
                    if (finallyExpr is IrBlock) {
                        for (s in finallyExpr.statements) {
                            generateIrStatement(mv, s, ctx, owner)
                        }
                    } else if (finallyExpr != null) {
                        generateIrExpression(mv, finallyExpr, ctx, owner)
                        if (typeMapper.jvmDescriptorFromIrType(finallyExpr.type) != "V") {
                            mv.visitInsn(Opcodes.POP)
                        }
                    }
                    return
                }
                val tryStart = Label()
                val tryEnd = Label()
                val catchStart = Label()
                val endLabel = Label()
                val catchTypeInternal = if (stmt.catches.isNotEmpty()) {
                    val param = stmt.catches.first().catchParameter
                    typeMapper.jvmInternalNameFromIrType(param.type)
                } else "java/lang/Throwable"
                mv.visitTryCatchBlock(tryStart, tryEnd, catchStart, catchTypeInternal)
                mv.visitLabel(tryStart)
                val tryBody = stmt.tryResult
                if (tryBody is IrBlock) {
                    for (s in tryBody.statements) {
                        generateIrStatement(mv, s, ctx, owner)
                    }
                }
                mv.visitLabel(tryEnd)
                mv.visitJumpInsn(Opcodes.GOTO, endLabel)
                mv.visitLabel(catchStart)
                for (catchClause in stmt.catches) {
                    val param = catchClause.catchParameter
                    val pName = param.name.toString()
                    val paramDesc = typeMapper.jvmDescriptorFromIrType(param.type)
                    val paramInternal = typeMapper.jvmInternalNameFromIrType(param.type)
                    val catchSlot = ctx.nextSlot
                    ctx.nextSlot += 1
                    storeLocal(mv, "Ljava/lang/Throwable;", catchSlot)
                    if (paramInternal != "java/lang/Throwable" && paramInternal != "throwable") {
                        loadLocal(mv, "Ljava/lang/Throwable;", catchSlot)
                        mv.visitTypeInsn(Opcodes.CHECKCAST, paramInternal)
                        storeLocal(mv, paramDesc, catchSlot)
                    }
                    ctx.variables[pName] = catchSlot
                    ctx.locals.add(LocalInfo(pName, param.type, catchSlot))
                    ctx.symbolToName[param.symbol] = pName
                    val catchBody = catchClause.result
                    if (catchBody is IrBlock) {
                        for (s in catchBody.statements) {
                            generateIrStatement(mv, s, ctx, owner)
                        }
                    } else {
                        generateIrStatement(mv, catchBody as IrStatement, ctx, owner)
                    }
                }
                mv.visitLabel(endLabel)
            }
            is IrThrow -> {
                generateIrExpression(mv, stmt.value, ctx, owner)
                mv.visitInsn(Opcodes.ATHROW)
            }
            is IrBreak -> {
                ctx.loopStack.lastOrNull()?.second?.let { mv.visitJumpInsn(Opcodes.GOTO, it) }
            }
            is IrContinue -> {
                ctx.loopStack.lastOrNull()?.first?.let { mv.visitJumpInsn(Opcodes.GOTO, it) }
            }
            is IrSetValue -> {
                val name = ctx.symbolToName[stmt.symbol] ?: ""
                val slot = ctx.variables[name]
                if (slot != null) {
                    val local = ctx.locals.find { it.name == name }
                    val desc = typeMapper.jvmDescriptorFromIrType(local?.irType ?: stmt.type)
                    generateIrExpression(mv, stmt.value, ctx, owner)
                    storeLocal(mv, desc, slot)
                }
            }
            is IrSetField -> {
                generateIrExpression(mv, stmt as IrExpression, ctx, owner)
            }
            else -> {
                if (stmt is IrExpression) {
                    generateIrExpression(mv, stmt as IrExpression, ctx, owner)
                    mv.visitInsn(Opcodes.POP)
                }
            }
        }
    }

    private fun generateIrWhen(mv: MethodVisitor, irWhen: IrWhen, ctx: MethodContext, owner: String) {
        val endLabel = Label()
        val nonElseBranches = irWhen.branches.filter { !isAlwaysTrue(it.condition) }
        val elseBranch = irWhen.branches.firstOrNull { isAlwaysTrue(it.condition) }
        for (branch in nonElseBranches) {
            val nextLabel = Label()
            generateIrExpression(mv, branch.condition, ctx, owner)
            mv.visitJumpInsn(Opcodes.IFEQ, nextLabel)
            val result = branch.result
            if (result is IrBlock) {
                for (s in result.statements) {
                    generateIrStatement(mv, s, ctx, owner)
                }
            } else {
                generateIrExpression(mv, result, ctx, owner)
                mv.visitInsn(Opcodes.POP)
            }
            mv.visitJumpInsn(Opcodes.GOTO, endLabel)
            mv.visitLabel(nextLabel)
        }
        if (elseBranch != null) {
            val result = elseBranch.result
            if (result is IrBlock) {
                for (s in result.statements) {
                    generateIrStatement(mv, s, ctx, owner)
                }
            } else {
                generateIrExpression(mv, result, ctx, owner)
                mv.visitInsn(Opcodes.POP)
            }
        }
        mv.visitLabel(endLabel)
    }

    private fun isAlwaysTrue(cond: IrExpression?): Boolean {
        return cond is IrConstImpl && cond.kind == IrConstKind.Boolean && cond.value == true
    }

    private fun generateIrExpression(mv: MethodVisitor, expr: IrExpression, ctx: MethodContext, owner: String) {
        when (expr) {
            is IrConstImpl -> {
                when (expr.kind) {
                    IrConstKind.Int -> pushInt(mv, (expr.value as Number).toInt())
                    IrConstKind.Long -> mv.visitLdcInsn(expr.value as Long)
                    IrConstKind.Float -> mv.visitLdcInsn(expr.value as Float)
                    IrConstKind.Double -> mv.visitLdcInsn(expr.value as Double)
                    IrConstKind.String -> mv.visitLdcInsn(expr.value as String)
                    IrConstKind.Boolean -> mv.visitInsn(if (expr.value as Boolean) Opcodes.ICONST_1 else Opcodes.ICONST_0)
                    IrConstKind.Null -> mv.visitInsn(Opcodes.ACONST_NULL)
                    else -> mv.visitInsn(Opcodes.ACONST_NULL)
                }
            }
            is IrGetValue -> {
                val name = ctx.symbolToName[expr.symbol] ?: ""
                val slot = ctx.variables[name]
                if (slot != null) {
                    val local = ctx.locals.find { it.name == name }
                    val desc = typeMapper.jvmDescriptorFromIrType(local?.irType ?: expr.type)
                    loadLocal(mv, desc, slot)
                } else if (!ctx.isStatic) {
                    mv.visitVarInsn(Opcodes.ALOAD, 0)
                } else {
                    mv.visitInsn(Opcodes.ACONST_NULL)
                }
            }
            is IrSetValue -> {
                val name = ctx.symbolToName[expr.symbol] ?: ""
                val slot = ctx.variables[name]
                if (slot != null) {
                    val local = ctx.locals.find { it.name == name }
                    val desc = typeMapper.jvmDescriptorFromIrType(local?.irType ?: expr.type)
                    generateIrExpression(mv, expr.value, ctx, owner)
                    val valueDesc = typeMapper.jvmDescriptorFromIrType(expr.value.type)
                    widenIfNeeded(mv, valueDesc, desc)
                    storeLocal(mv, desc, slot)
                    loadLocal(mv, desc, slot)
                } else if (!ctx.isStatic) {
                    mv.visitVarInsn(Opcodes.ALOAD, 0)
                    generateIrExpression(mv, expr.value, ctx, owner)
                    // This case handles this.x = v via IrSetField, not IrSetValue
                    // For IrSetValue with this, just evaluate the value
                } else {
                    generateIrExpression(mv, expr.value, ctx, owner)
                }
            }
            is IrCall -> {
                val irName = callNames[expr.symbol] ?: functionSymbolToName[expr.symbol] ?: "invoke"
                when (irName) {
                    "invoke_lambda" -> {
                        val receiver = expr.dispatchReceiver
                        if (receiver != null) {
                            generateIrExpression(mv, receiver, ctx, owner)
                            val rawReceiverInternal = typeMapper.jvmInternalNameFromIrType(receiver.type)
                            val receiverInternal = if (rawReceiverInternal.contains("/")) rawReceiverInternal else "$currentPkg/$rawReceiverInternal"
                            val paramSb = StringBuilder()
                            for (i in 0 until expr.valueArgumentsCount) {
                                val arg = expr.getValueArgument(i) ?: continue
                                generateIrExpression(mv, arg, ctx, owner)
                                paramSb.append(typeMapper.jvmDescriptorFromIrType(arg.type))
                            }
                            val returnDesc = typeMapper.jvmReturnDescriptorFromIrType(expr.type)
                            mv.visitMethodInsn(Opcodes.INVOKEVIRTUAL, receiverInternal, "invoke", "($paramSb)$returnDesc", false)
                        }
                    }
                    "println", "print" -> {
                        mv.visitFieldInsn(Opcodes.GETSTATIC, "java/lang/System", "out", "Ljava/io/PrintStream;")
                        val arg = expr.getValueArgument(0)
                        if (arg != null) {
                            val desc = typeMapper.jvmDescriptorFromIrType(arg.type)
                            generateIrExpression(mv, arg, ctx, owner)
                            when (desc) {
                                "I", "Z", "B", "S", "C" ->
                                    mv.visitMethodInsn(Opcodes.INVOKESTATIC, "java/lang/String", "valueOf", "($desc)Ljava/lang/String;", false)
                                "J" ->
                                    mv.visitMethodInsn(Opcodes.INVOKESTATIC, "java/lang/String", "valueOf", "(J)Ljava/lang/String;", false)
                                "F" ->
                                    mv.visitMethodInsn(Opcodes.INVOKESTATIC, "java/lang/String", "valueOf", "(F)Ljava/lang/String;", false)
                                "D" ->
                                    mv.visitMethodInsn(Opcodes.INVOKESTATIC, "java/lang/String", "valueOf", "(D)Ljava/lang/String;", false)
                            }
                        }
                        mv.visitMethodInsn(Opcodes.INVOKEVIRTUAL, "java/io/PrintStream", irName, "(Ljava/lang/String;)V", false)
                        mv.visitInsn(Opcodes.ACONST_NULL)
                    }
                    "toString" -> {
                        emitToString(mv, expr, ctx, owner)
                    }
                    "__jasper_pow__" -> {
                        generateIrExpression(mv, expr.getValueArgument(0)!!, ctx, owner)
                        mv.visitInsn(Opcodes.I2D)
                        generateIrExpression(mv, expr.getValueArgument(1)!!, ctx, owner)
                        mv.visitInsn(Opcodes.I2D)
                        mv.visitMethodInsn(Opcodes.INVOKESTATIC, "java/lang/Math", "pow", "(DD)D", false)
                        mv.visitInsn(Opcodes.D2I)
                    }
                    "negate" -> {
                        generateIrExpression(mv, expr.getValueArgument(0)!!, ctx, owner)
                        mv.visitInsn(Opcodes.INEG)
                    }
                    "not" -> {
                        val trueLabel = Label()
                        val endLabel = Label()
                        generateIrExpression(mv, expr.getValueArgument(0)!!, ctx, owner)
                        mv.visitJumpInsn(Opcodes.IFEQ, trueLabel)
                        mv.visitInsn(Opcodes.ICONST_0)
                        mv.visitJumpInsn(Opcodes.GOTO, endLabel)
                        mv.visitLabel(trueLabel)
                        mv.visitInsn(Opcodes.ICONST_1)
                        mv.visitLabel(endLabel)
                    }
                    "size" -> {
                        generateIrExpression(mv, expr.getValueArgument(0)!!, ctx, owner)
                        mv.visitInsn(Opcodes.ARRAYLENGTH)
                    }
                    "get" -> {
                        val arr = expr.getValueArgument(0)!!
                        val idx = expr.getValueArgument(1)!!
                        generateIrExpression(mv, arr, ctx, owner)
                        generateIrExpression(mv, idx, ctx, owner)
                        val arrDesc = typeMapper.jvmDescriptorFromIrType(arr.type)
                        val elemDesc = if (arrDesc.startsWith("[")) arrDesc.substring(1) else "Ljava/lang/Object;"
                        mv.visitInsn(arrayLoadOpcode(elemDesc))
                    }
                    "arraySet" -> {
                        val arr = expr.getValueArgument(0)!!
                        val idx = expr.getValueArgument(1)!!
                        val rhs = expr.getValueArgument(2)!!
                        val rhsDesc = typeMapper.jvmDescriptorFromIrType(rhs.type)
                        val rhsSlot = ctx.nextSlot
                        ctx.nextSlot += if (rhsDesc == "J" || rhsDesc == "D") 2 else 1
                        // Save rhs value so we can push it back after array store
                        generateIrExpression(mv, rhs, ctx, owner)
                        storeLocal(mv, rhsDesc, rhsSlot)
                        generateIrExpression(mv, arr, ctx, owner)
                        generateIrExpression(mv, idx, ctx, owner)
                        loadLocal(mv, rhsDesc, rhsSlot)
                        mv.visitInsn(arrayStoreOpcode(rhsDesc))
                        loadLocal(mv, rhsDesc, rhsSlot)
                    }
                    "put" -> {
                        val receiver = expr.getValueArgument(0)!!
                        val key = expr.getValueArgument(1)!!
                        val rhs = expr.getValueArgument(2)!!
                        generateIrExpression(mv, receiver, ctx, owner)
                        generateIrExpression(mv, key, ctx, owner)
                        boxTopOfStack(mv, typeMapper.jvmDescriptorFromIrType(key.type))
                        generateIrExpression(mv, rhs, ctx, owner)
                        boxTopOfStack(mv, typeMapper.jvmDescriptorFromIrType(rhs.type))
                        mv.visitMethodInsn(Opcodes.INVOKEVIRTUAL, "java/util/HashMap", "put", "(Ljava/lang/Object;Ljava/lang/Object;)Ljava/lang/Object;", false)
                    }
                    "inc" -> {
                        generateIrExpression(mv, expr.getValueArgument(0)!!, ctx, owner)
                        val arg0 = expr.getValueArgument(0)!!
                        val argDesc0 = typeMapper.jvmDescriptorFromIrType(arg0.type)
                        if (argDesc0 == "J") {
                            mv.visitInsn(Opcodes.LCONST_1)
                            mv.visitInsn(Opcodes.LADD)
                        } else {
                            pushInt(mv, 1)
                            mv.visitInsn(Opcodes.IADD)
                        }
                    }
                    "dec" -> {
                        generateIrExpression(mv, expr.getValueArgument(0)!!, ctx, owner)
                        val arg0 = expr.getValueArgument(0)!!
                        val argDesc0 = typeMapper.jvmDescriptorFromIrType(arg0.type)
                        if (argDesc0 == "J") {
                            mv.visitInsn(Opcodes.LCONST_1)
                            mv.visitInsn(Opcodes.LSUB)
                        } else {
                            pushInt(mv, 1)
                            mv.visitInsn(Opcodes.ISUB)
                        }
                    }
                    "inv" -> {
                        generateIrExpression(mv, expr.getValueArgument(0)!!, ctx, owner)
                        val arg0 = expr.getValueArgument(0)!!
                        val argDesc0 = typeMapper.jvmDescriptorFromIrType(arg0.type)
                        if (argDesc0 == "J") {
                            mv.visitLdcInsn(-1L)
                            mv.visitInsn(Opcodes.LXOR)
                        } else {
                            mv.visitInsn(Opcodes.ICONST_M1)
                            mv.visitInsn(Opcodes.IXOR)
                        }
                    }
                    "unaryPlus" -> {
                        generateIrExpression(mv, expr.getValueArgument(0)!!, ctx, owner)
                        // No-op: unary plus just returns the value
                    }
                    "string_plus" -> {
                        emitStringConcatCall(mv, expr, ctx, owner)
                    }
                    "+", "-", "*", "/", "%", "&", "|", "^", "<<", ">>", ">>>" -> {
                        val arg0 = expr.getValueArgument(0)!!
                        val arg1 = expr.getValueArgument(1)!!
                        val desc0 = typeMapper.jvmDescriptorFromIrType(arg0.type)
                        val desc1 = typeMapper.jvmDescriptorFromIrType(arg1.type)
                        if (desc0 == "Ljava/lang/String;" || desc0 == "Ljava/lang/String") {
                            emitStringConcatCall(mv, expr, ctx, owner)
                        } else {
                            val wider = binaryOpWiderDesc(desc0, desc1)
                            generateIrExpression(mv, arg0, ctx, owner)
                            widenIfNeeded(mv, desc0, wider)
                            generateIrExpression(mv, arg1, ctx, owner)
                            widenIfNeeded(mv, desc1, wider)
                            emitBinaryArithmeticOp(mv, irName, wider)
                        }
                    }
                    "==", "!=", "<", ">", "<=", ">=" -> {
                        val arg0 = expr.getValueArgument(0)!!
                        val arg1 = expr.getValueArgument(1)!!
                        val desc0 = typeMapper.jvmDescriptorFromIrType(arg0.type)
                        val desc1 = typeMapper.jvmDescriptorFromIrType(arg1.type)
                        val wider = binaryOpWiderDesc(desc0, desc1)
                        generateIrExpression(mv, arg0, ctx, owner)
                        widenIfNeeded(mv, desc0, wider)
                        generateIrExpression(mv, arg1, ctx, owner)
                        widenIfNeeded(mv, desc1, wider)
                        emitCompare(mv, irName, wider)
                    }
                    "&&" -> {
                        val falseLabel = Label()
                        val endLabel = Label()
                        generateIrExpression(mv, expr.getValueArgument(0)!!, ctx, owner)
                        mv.visitJumpInsn(Opcodes.IFEQ, falseLabel)
                        generateIrExpression(mv, expr.getValueArgument(1)!!, ctx, owner)
                        mv.visitJumpInsn(Opcodes.IFEQ, falseLabel)
                        mv.visitInsn(Opcodes.ICONST_1)
                        mv.visitJumpInsn(Opcodes.GOTO, endLabel)
                        mv.visitLabel(falseLabel)
                        mv.visitInsn(Opcodes.ICONST_0)
                        mv.visitLabel(endLabel)
                    }
                    "||" -> {
                        val trueLabel = Label()
                        val endLabel = Label()
                        generateIrExpression(mv, expr.getValueArgument(0)!!, ctx, owner)
                        mv.visitJumpInsn(Opcodes.IFNE, trueLabel)
                        generateIrExpression(mv, expr.getValueArgument(1)!!, ctx, owner)
                        mv.visitJumpInsn(Opcodes.IFNE, trueLabel)
                        mv.visitInsn(Opcodes.ICONST_0)
                        mv.visitJumpInsn(Opcodes.GOTO, endLabel)
                        mv.visitLabel(trueLabel)
                        mv.visitInsn(Opcodes.ICONST_1)
                        mv.visitLabel(endLabel)
                    }
                    else -> {
                        if (irName.startsWith("ctor_delegate:")) {
                            val remainder = irName.substring("ctor_delegate:".length)
                            val targetInternal = if (remainder.startsWith("super:")) {
                                remainder.substring("super:".length)
                            } else {
                                owner
                            }
                            // Emit ALOAD 0 (this) first
                            mv.visitVarInsn(Opcodes.ALOAD, 0)
                            // Build descriptor from arg types (skip first arg which is `this`)
                            val paramSb = StringBuilder()
                            for (i in 1 until expr.valueArgumentsCount) {
                                val arg = expr.getValueArgument(i) ?: continue
                                generateIrExpression(mv, arg, ctx, owner)
                                paramSb.append(typeMapper.jvmDescriptorFromIrType(arg.type))
                            }
                            mv.visitMethodInsn(Opcodes.INVOKESPECIAL, targetInternal, "<init>", "($paramSb)V", false)
                            // Leave a dummy null on stack so POP (from IrExpression statement handler) works
                            mv.visitInsn(Opcodes.ACONST_NULL)
                        } else if (irName.startsWith("v:")) {
                            val realName = irName.substring(2)
                            val receiver = expr.getValueArgument(0)!!
                            val rawReceiverInternal = typeMapper.jvmInternalNameFromIrType(receiver.type)
                            val receiverInternal = if (rawReceiverInternal.contains("/")) rawReceiverInternal else "$currentPkg/$rawReceiverInternal"
                            val receiverDesc = typeMapper.jvmDescriptorFromIrType(receiver.type)
                            // Determine if receiver is an interface
                            val isInterfaceTarget = interfaceDeclNames.contains(rawReceiverInternal) ||
                                interfaceDeclNames.contains(rawReceiverInternal.substringAfterLast('/'))
                            // Generate receiver
                            generateIrExpression(mv, receiver, ctx, owner)
                            // Generate remaining args and build descriptor
                            val paramSb = StringBuilder()
                            for (i in 1 until expr.valueArgumentsCount) {
                                val arg = expr.getValueArgument(i) ?: continue
                                generateIrExpression(mv, arg, ctx, owner)
                                paramSb.append(typeMapper.jvmDescriptorFromIrType(arg.type))
                            }
                            val returnDesc = if (realName == "toString") "Ljava/lang/String;"
                                else if (realName.startsWith("append") || realName == "set" || realName == "get" || realName == "remove") receiverDesc
                                else typeMapper.jvmReturnDescriptorFromIrType(expr.type)
                            if (isInterfaceTarget) {
                                mv.visitMethodInsn(Opcodes.INVOKEINTERFACE, receiverInternal, realName, "($paramSb)$returnDesc", true)
                            } else {
                                mv.visitMethodInsn(Opcodes.INVOKEVIRTUAL, receiverInternal, realName, "($paramSb)$returnDesc", false)
                            }
                        } else {
                            val func = try { expr.symbol.owner } catch (e: Exception) { null }
                            // Generate arguments with boxing
                            for (i in 0 until expr.valueArgumentsCount) {
                                val arg = expr.getValueArgument(i) ?: continue
                                generateIrExpression(mv, arg, ctx, owner)
                                if (func != null && i < func.valueParameters.size) {
                                    val argDesc = typeMapper.jvmDescriptorFromIrType(arg.type)
                                    val paramDesc = typeMapper.jvmDescriptorFromIrType(func.valueParameters[i].type)
                                    emitBoxingIfNeeded(mv, argDesc, paramDesc)
                                }
                            }
                            val desc = irCallDescriptor(expr)
                            val targetOwner = functionSymbolToOwner[expr.symbol] ?: owner
                            mv.visitMethodInsn(Opcodes.INVOKESTATIC, targetOwner, irName, desc, false)
                            // Handle return type mismatch between erasure and call-site IR type.
                            val callReturnDesc = typeMapper.jvmDescriptorFromIrType(expr.type)
                            val funcReturnDesc = typeMapper.jvmReturnDescriptorFromIrType(
                                try { func?.returnType } catch (e: Exception) { null } ?: expr.type
                            )
                            if (callReturnDesc != funcReturnDesc) {
                                if (callReturnDesc.startsWith("L") && funcReturnDesc.startsWith("L")) {
                                    val internalName = callReturnDesc.substring(1, callReturnDesc.length - 1)
                                    mv.visitTypeInsn(Opcodes.CHECKCAST, internalName)
                                } else {
                                    emitUnboxingIfNeeded(mv, funcReturnDesc, callReturnDesc)
                                    emitBoxingIfNeeded(mv, funcReturnDesc, callReturnDesc)
                                }
                            }
                        }
                    }
                }
            }
            is IrConstructorCall -> {
                val ctorType = constructorTypes[expr.symbol]
                if (ctorType != null && ctorType.startsWith("array:")) {
                    val elemDesc = ctorType.substring("array:".length)
                    if (expr.valueArgumentsCount == 1) {
                        generateIrExpression(mv, expr.getValueArgument(0)!!, ctx, owner)
                        emitNewArray(mv, elemDesc)
                    } else {
                        for (i in 0 until expr.valueArgumentsCount) {
                            val arg = expr.getValueArgument(i) ?: continue
                            generateIrExpression(mv, arg, ctx, owner)
                        }
                        mv.visitMultiANewArrayInsn("[".repeat(expr.valueArgumentsCount) + elemDesc, expr.valueArgumentsCount)
                    }
                } else {
                    val rawName = typeMapper.jvmInternalNameFromIrType(expr.type)
                    val internalName = if (rawName.contains("/")) rawName else "$currentPkg/$rawName"
                    val desc = irNewDescriptor(expr)
                    mv.visitTypeInsn(Opcodes.NEW, internalName)
                    mv.visitInsn(Opcodes.DUP)
                    for (i in 0 until expr.valueArgumentsCount) {
                        val arg = expr.getValueArgument(i) ?: continue
                        generateIrExpression(mv, arg, ctx, owner)
                    }
                    mv.visitMethodInsn(Opcodes.INVOKESPECIAL, internalName, "<init>", desc, false)
                }
            }
            is IrGetField -> {
                val receiver = expr.receiver
                if (receiver != null) generateIrExpression(mv, receiver, ctx, owner)
                val fieldName = fieldSymbolToName[expr.symbol] ?: "field"
                // Handle array.length: emit ARRAYLENGTH instead of GETFIELD
                if (receiver != null && isArrayType(receiver.type)) {
                    mv.visitInsn(Opcodes.ARRAYLENGTH)
                    return
                }
                val fieldDesc = typeMapper.jvmDescriptorFromIrType(expr.type)
                val fieldOwner = resolveFieldOwner(expr.symbol)
                mv.visitFieldInsn(Opcodes.GETFIELD, fieldOwner, fieldName, fieldDesc)
            }
            is IrSetField -> {
                val receiver = expr.receiver
                if (receiver != null) generateIrExpression(mv, receiver, ctx, owner)
                val fieldName = fieldSymbolToName[expr.symbol] ?: "field"
                val fieldDesc = typeMapper.jvmDescriptorFromIrType(expr.value.type)
                generateIrExpression(mv, expr.value, ctx, owner)
                val fieldOwner = resolveFieldOwner(expr.symbol)
                mv.visitFieldInsn(Opcodes.PUTFIELD, fieldOwner, fieldName, fieldDesc)
            }
            is IrBlock -> {
                val stmts = expr.statements
                for (i in 0 until stmts.size - 1) {
                    generateIrStatement(mv, stmts[i], ctx, owner)
                }
                if (stmts.isNotEmpty()) {
                    val last = stmts.last()
                    if (last is IrExpression) {
                        generateIrExpression(mv, last, ctx, owner)
                    } else {
                        generateIrStatement(mv, last, ctx, owner)
                    }
                }
            }
            is IrWhen -> {
                val endLabel = Label()
                val nonElseBranches = expr.branches.filter { !isAlwaysTrue(it.condition) }
                val elseBranch = expr.branches.firstOrNull { isAlwaysTrue(it.condition) }
                for (branch in nonElseBranches) {
                    val branchEnd = Label()
                    generateIrExpression(mv, branch.condition, ctx, owner)
                    mv.visitJumpInsn(Opcodes.IFEQ, branchEnd)
                    val result = branch.result
                    if (result is IrBlock) {
                        val stmts = result.statements
                        for (i in 0 until stmts.size - 1) {
                            generateIrStatement(mv, stmts[i], ctx, owner)
                        }
                        if (stmts.isNotEmpty()) {
                            val last = stmts.last()
                            if (last is IrExpression) {
                                generateIrExpression(mv, last, ctx, owner)
                            } else {
                                generateIrStatement(mv, last, ctx, owner)
                            }
                        }
                    } else {
                        generateIrExpression(mv, result, ctx, owner)
                    }
                    mv.visitJumpInsn(Opcodes.GOTO, endLabel)
                    mv.visitLabel(branchEnd)
                }
                if (elseBranch != null) {
                    val result = elseBranch.result
                    if (result is IrBlock) {
                        val stmts = result.statements
                        for (i in 0 until stmts.size - 1) {
                            generateIrStatement(mv, stmts[i], ctx, owner)
                        }
                        if (stmts.isNotEmpty()) {
                            val last = stmts.last()
                            if (last is IrExpression) {
                                generateIrExpression(mv, last, ctx, owner)
                            } else {
                                generateIrStatement(mv, last, ctx, owner)
                            }
                        }
                    } else {
                        generateIrExpression(mv, result, ctx, owner)
                    }
                }
                mv.visitLabel(endLabel)
            }
            is IrFunctionExpression -> {
                emitLambda(mv, expr, ctx, owner)
            }
            is IrTypeOperatorCall -> {
                when (expr.operator) {
                    IrTypeOperator.IMPLICIT_NOTNULL -> {
                        val trueLabel = Label()
                        val endLabel = Label()
                        generateIrExpression(mv, expr.argument, ctx, owner)
                        mv.visitJumpInsn(Opcodes.IFNONNULL, trueLabel)
                        mv.visitInsn(Opcodes.ICONST_0)
                        mv.visitJumpInsn(Opcodes.GOTO, endLabel)
                        mv.visitLabel(trueLabel)
                        mv.visitInsn(Opcodes.ICONST_1)
                        mv.visitLabel(endLabel)
                    }
                    IrTypeOperator.INSTANCEOF -> {
                        generateIrExpression(mv, expr.argument, ctx, owner)
                        val internalName = typeMapper.jvmInternalNameFromIrType(expr.typeOperand)
                        mv.visitTypeInsn(Opcodes.INSTANCEOF, internalName)
                    }
                    IrTypeOperator.CAST -> {
                        generateIrExpression(mv, expr.argument, ctx, owner)
                        val desc = typeMapper.jvmDescriptorFromIrType(expr.typeOperand)
                        if (desc.startsWith("L") || desc.startsWith("[")) {
                            val internalName = typeMapper.jvmInternalNameFromIrType(expr.typeOperand)
                            mv.visitTypeInsn(Opcodes.CHECKCAST, internalName)
                        }
                    }
                    else -> generateIrExpression(mv, expr.argument, ctx, owner)
                }
            }
            else -> mv.visitInsn(Opcodes.ACONST_NULL)
        }
    }

    private fun isIrVoidExpr(expr: IrExpression): Boolean {
        return expr is IrConstImpl && expr.kind == IrConstKind.Null
    }

    private fun hasIrReturn(body: IrBlockBody): Boolean {
        return body.statements.any { it is IrReturn }
    }

    private fun generateIrReturn(mv: MethodVisitor, returnType: IrType?) {
        val d = if (returnType != null) typeMapper.jvmReturnDescriptorFromIrType(returnType) else "V"
        when {
            d == "V" -> mv.visitInsn(Opcodes.RETURN)
            d == "I" || d == "Z" || d == "B" || d == "S" || d == "C" -> mv.visitInsn(Opcodes.IRETURN)
            d == "J" -> mv.visitInsn(Opcodes.LRETURN)
            d == "F" -> mv.visitInsn(Opcodes.FRETURN)
            d == "D" -> mv.visitInsn(Opcodes.DRETURN)
            else -> mv.visitInsn(Opcodes.ARETURN)
        }
    }

    private fun irMethodDescriptor(func: IrSimpleFunction): String {
        val paramDescs = func.valueParameters.joinToString("") {
            typeMapper.jvmDescriptorFromIrType(it.type)
        }
        val rd = typeMapper.jvmReturnDescriptorFromIrType(func.returnType)
        return "($paramDescs)$rd"
    }

    private fun irConstructorDescriptor(ctor: IrConstructor): String {
        val paramDescs = ctor.valueParameters.joinToString("") {
            typeMapper.jvmDescriptorFromIrType(it.type)
        }
        return "($paramDescs)V"
    }

    private fun irCallDescriptor(call: IrCall): String {
        // Use the function's declared parameter types for the descriptor (erasure),
        // falling back to argument types if the target function is not found.
        val func = try { call.symbol.owner } catch (e: Exception) { null }
        if (func != null) {
            return irMethodDescriptor(func)
        }
        val paramDescs = (0 until call.valueArgumentsCount).joinToString("") {
            val arg = call.getValueArgument(it)
            if (arg != null) typeMapper.jvmDescriptorFromIrType(arg.type) else "Ljava/lang/Object;"
        }
        val returnDesc = typeMapper.jvmReturnDescriptorFromIrType(call.type)
        return "($paramDescs)$returnDesc"
    }

    private fun irNewDescriptor(ctor: IrConstructorCall): String {
        val paramDescs = (0 until ctor.valueArgumentsCount).joinToString("") {
            val arg = ctor.getValueArgument(it)
            if (arg != null) typeMapper.jvmDescriptorFromIrType(arg.type) else "Ljava/lang/Object;"
        }
        return "($paramDescs)V"
    }

    private fun irTypeSlots(type: IrType): Int {
        val desc = typeMapper.jvmDescriptorFromIrType(type)
        return if (desc == "J" || desc == "D") 2 else 1
    }

    private fun loadLocal(mv: MethodVisitor, desc: String, slot: Int) {
        when (desc) {
            "I", "Z", "B", "S", "C" -> mv.visitVarInsn(Opcodes.ILOAD, slot)
            "J" -> mv.visitVarInsn(Opcodes.LLOAD, slot)
            "F" -> mv.visitVarInsn(Opcodes.FLOAD, slot)
            "D" -> mv.visitVarInsn(Opcodes.DLOAD, slot)
            else -> mv.visitVarInsn(Opcodes.ALOAD, slot)
        }
    }

    private fun storeLocal(mv: MethodVisitor, desc: String, slot: Int) {
        when (desc) {
            "I", "Z", "B", "S", "C" -> mv.visitVarInsn(Opcodes.ISTORE, slot)
            "J" -> mv.visitVarInsn(Opcodes.LSTORE, slot)
            "F" -> mv.visitVarInsn(Opcodes.FSTORE, slot)
            "D" -> mv.visitVarInsn(Opcodes.DSTORE, slot)
            else -> mv.visitVarInsn(Opcodes.ASTORE, slot)
        }
    }

    private fun pushInt(mv: MethodVisitor, value: Int) {
        when (value) {
            -1 -> mv.visitInsn(Opcodes.ICONST_M1)
            0 -> mv.visitInsn(Opcodes.ICONST_0)
            1 -> mv.visitInsn(Opcodes.ICONST_1)
            2 -> mv.visitInsn(Opcodes.ICONST_2)
            3 -> mv.visitInsn(Opcodes.ICONST_3)
            4 -> mv.visitInsn(Opcodes.ICONST_4)
            5 -> mv.visitInsn(Opcodes.ICONST_5)
            in Byte.MIN_VALUE..Byte.MAX_VALUE -> mv.visitIntInsn(Opcodes.BIPUSH, value)
            in Short.MIN_VALUE..Short.MAX_VALUE -> mv.visitIntInsn(Opcodes.SIPUSH, value)
            else -> mv.visitLdcInsn(value)
        }
    }

    private fun widenIfNeeded(mv: MethodVisitor, sourceDesc: String, targetDesc: String) {
        if (sourceDesc == targetDesc) return
        when (sourceDesc) {
            "I" -> when (targetDesc) {
                "J" -> mv.visitInsn(Opcodes.I2L)
                "F" -> mv.visitInsn(Opcodes.I2F)
                "D" -> mv.visitInsn(Opcodes.I2D)
            }
            "J" -> when (targetDesc) {
                "F" -> mv.visitInsn(Opcodes.L2F)
                "D" -> mv.visitInsn(Opcodes.L2D)
            }
            "F" -> when (targetDesc) {
                "D" -> mv.visitInsn(Opcodes.F2D)
            }
        }
    }

    private fun emitBoxingIfNeeded(mv: MethodVisitor, fromDesc: String, toDesc: String) {
        if (toDesc == "Ljava/lang/Object;" || toDesc == "Ljava/io/Serializable;") {
            when (fromDesc) {
                "I" -> mv.visitMethodInsn(Opcodes.INVOKESTATIC, "java/lang/Integer", "valueOf", "(I)Ljava/lang/Integer;", false)
                "J" -> mv.visitMethodInsn(Opcodes.INVOKESTATIC, "java/lang/Long", "valueOf", "(J)Ljava/lang/Long;", false)
                "F" -> mv.visitMethodInsn(Opcodes.INVOKESTATIC, "java/lang/Float", "valueOf", "(F)Ljava/lang/Float;", false)
                "D" -> mv.visitMethodInsn(Opcodes.INVOKESTATIC, "java/lang/Double", "valueOf", "(D)Ljava/lang/Double;", false)
                "Z" -> mv.visitMethodInsn(Opcodes.INVOKESTATIC, "java/lang/Boolean", "valueOf", "(Z)Ljava/lang/Boolean;", false)
            }
        }
    }

    private fun emitUnboxingIfNeeded(mv: MethodVisitor, fromDesc: String, toDesc: String) {
        if (fromDesc == "Ljava/lang/Object;" || fromDesc == "Ljava/io/Serializable;") {
            when (toDesc) {
                "I" -> {
                    mv.visitTypeInsn(Opcodes.CHECKCAST, "java/lang/Integer")
                    mv.visitMethodInsn(Opcodes.INVOKEVIRTUAL, "java/lang/Integer", "intValue", "()I", false)
                }
                "J" -> {
                    mv.visitTypeInsn(Opcodes.CHECKCAST, "java/lang/Long")
                    mv.visitMethodInsn(Opcodes.INVOKEVIRTUAL, "java/lang/Long", "longValue", "()J", false)
                }
                "F" -> {
                    mv.visitTypeInsn(Opcodes.CHECKCAST, "java/lang/Float")
                    mv.visitMethodInsn(Opcodes.INVOKEVIRTUAL, "java/lang/Float", "floatValue", "()F", false)
                }
                "D" -> {
                    mv.visitTypeInsn(Opcodes.CHECKCAST, "java/lang/Double")
                    mv.visitMethodInsn(Opcodes.INVOKEVIRTUAL, "java/lang/Double", "doubleValue", "()D", false)
                }
                "Z" -> {
                    mv.visitTypeInsn(Opcodes.CHECKCAST, "java/lang/Boolean")
                    mv.visitMethodInsn(Opcodes.INVOKEVIRTUAL, "java/lang/Boolean", "booleanValue", "()Z", false)
                }
            }
        }
    }

    private fun binaryOpWiderDesc(desc1: String, desc2: String): String {
        if (desc1 == desc2) return desc1
        val priority = mapOf("I" to 0, "F" to 1, "J" to 1, "D" to 2)
        val p1 = priority[desc1] ?: return desc1
        val p2 = priority[desc2] ?: return desc2
        return if (p1 >= p2) desc1 else desc2
    }

    private fun emitBinaryArithmeticOp(mv: MethodVisitor, op: String, desc: String) {
        val isLong = desc == "J"
        val isDouble = desc == "D"
        val isFloat = desc == "F"
        when (op) {
            "+" -> mv.visitInsn(if (isLong) Opcodes.LADD else if (isDouble) Opcodes.DADD else if (isFloat) Opcodes.FADD else Opcodes.IADD)
            "-" -> mv.visitInsn(if (isLong) Opcodes.LSUB else if (isDouble) Opcodes.DSUB else if (isFloat) Opcodes.FSUB else Opcodes.ISUB)
            "*" -> mv.visitInsn(if (isLong) Opcodes.LMUL else if (isDouble) Opcodes.DMUL else if (isFloat) Opcodes.FMUL else Opcodes.IMUL)
            "/" -> mv.visitInsn(if (isLong) Opcodes.LDIV else if (isDouble) Opcodes.DDIV else if (isFloat) Opcodes.FDIV else Opcodes.IDIV)
            "%" -> mv.visitInsn(if (isLong) Opcodes.LREM else if (isDouble) Opcodes.DREM else if (isFloat) Opcodes.FREM else Opcodes.IREM)
            "&" -> mv.visitInsn(if (isLong) Opcodes.LAND else Opcodes.IAND)
            "|" -> mv.visitInsn(if (isLong) Opcodes.LOR else Opcodes.IOR)
            "^" -> mv.visitInsn(if (isLong) Opcodes.LXOR else Opcodes.IXOR)
            "<<" -> mv.visitInsn(if (isLong) Opcodes.LSHL else Opcodes.ISHL)
            ">>" -> mv.visitInsn(if (isLong) Opcodes.LSHR else Opcodes.ISHR)
            ">>>" -> mv.visitInsn(if (isLong) Opcodes.LUSHR else Opcodes.IUSHR)
        }
    }

    private fun emitCompare(mv: MethodVisitor, op: String, desc: String) {
        if (desc.startsWith("L") || desc.startsWith("[")) {
            mv.visitMethodInsn(Opcodes.INVOKESTATIC, "java/util/Objects", "equals", "(Ljava/lang/Object;Ljava/lang/Object;)Z", false)
            if (op == "==") {
                return
            } else if (op == "!=") {
                mv.visitInsn(Opcodes.ICONST_1)
                mv.visitInsn(Opcodes.IXOR)
                return
            } else {
                mv.visitInsn(Opcodes.ICONST_0)
                return
            }
        }
        val trueLabel = Label()
        val endLabel = Label()
        val isLong = desc == "J"
        val isDouble = desc == "D"
        val isFloat = desc == "F"
        if (isLong) {
            mv.visitInsn(Opcodes.LCMP)
        } else if (isDouble || isFloat) {
            mv.visitInsn(if (isDouble) Opcodes.DCMPG else Opcodes.FCMPG)
        }
        if (isLong || isDouble || isFloat) {
            val ifOp = when (op) {
                "==" -> Opcodes.IFEQ; "!=" -> Opcodes.IFNE
                "<" -> Opcodes.IFLT; ">" -> Opcodes.IFGT
                "<=" -> Opcodes.IFLE; ">=" -> Opcodes.IFGE
                else -> Opcodes.IFEQ
            }
            mv.visitJumpInsn(ifOp, trueLabel)
        } else {
            val ifOp = when (op) {
                "==" -> Opcodes.IF_ICMPEQ; "!=" -> Opcodes.IF_ICMPNE
                "<" -> Opcodes.IF_ICMPLT; ">" -> Opcodes.IF_ICMPGT
                "<=" -> Opcodes.IF_ICMPLE; ">=" -> Opcodes.IF_ICMPGE
                else -> Opcodes.IF_ICMPEQ
            }
            mv.visitJumpInsn(ifOp, trueLabel)
        }
        mv.visitInsn(Opcodes.ICONST_0)
        mv.visitJumpInsn(Opcodes.GOTO, endLabel)
        mv.visitLabel(trueLabel)
        mv.visitInsn(Opcodes.ICONST_1)
        mv.visitLabel(endLabel)
    }

    private fun emitStringConcatCall(mv: MethodVisitor, expr: IrCall, ctx: MethodContext, owner: String) {
        mv.visitTypeInsn(Opcodes.NEW, "java/lang/StringBuilder")
        mv.visitInsn(Opcodes.DUP)
        mv.visitMethodInsn(Opcodes.INVOKESPECIAL, "java/lang/StringBuilder", "<init>", "()V", false)
        for (i in 0 until expr.valueArgumentsCount) {
            val arg = expr.getValueArgument(i) ?: continue
            generateIrExpression(mv, arg, ctx, owner)
            val argDesc = typeMapper.jvmDescriptorFromIrType(arg.type)
            when (argDesc) {
                "I", "Z", "B", "S", "C" -> mv.visitMethodInsn(Opcodes.INVOKESTATIC, "java/lang/String", "valueOf", "($argDesc)Ljava/lang/String;", false)
                "J" -> mv.visitMethodInsn(Opcodes.INVOKESTATIC, "java/lang/String", "valueOf", "(J)Ljava/lang/String;", false)
                "F" -> mv.visitMethodInsn(Opcodes.INVOKESTATIC, "java/lang/String", "valueOf", "(F)Ljava/lang/String;", false)
                "D" -> mv.visitMethodInsn(Opcodes.INVOKESTATIC, "java/lang/String", "valueOf", "(D)Ljava/lang/String;", false)
            }
            mv.visitMethodInsn(Opcodes.INVOKEVIRTUAL, "java/lang/StringBuilder", "append", "(Ljava/lang/String;)Ljava/lang/StringBuilder;", false)
        }
        mv.visitMethodInsn(Opcodes.INVOKEVIRTUAL, "java/lang/StringBuilder", "toString", "()Ljava/lang/String;", false)
    }

    private fun emitToString(mv: MethodVisitor, expr: IrCall, ctx: MethodContext, owner: String) {
        val arg = expr.getValueArgument(0)!!
        val desc = typeMapper.jvmDescriptorFromIrType(arg.type)
        val valueOfMethod = when (desc) {
            "I", "Z", "B", "S", "C" -> "($desc)Ljava/lang/String;"
            "J" -> "(J)Ljava/lang/String;"
            "F" -> "(F)Ljava/lang/String;"
            "D" -> "(D)Ljava/lang/String;"
            else -> "(Ljava/lang/Object;)Ljava/lang/String;"
        }
        generateIrExpression(mv, arg, ctx, owner)
        mv.visitMethodInsn(Opcodes.INVOKESTATIC, "java/lang/String", "valueOf", valueOfMethod, false)
    }

    private fun boxTopOfStack(mv: MethodVisitor, desc: String) {
        when (desc) {
            "I" -> mv.visitMethodInsn(Opcodes.INVOKESTATIC, "java/lang/Integer", "valueOf", "(I)Ljava/lang/Integer;", false)
            "J" -> mv.visitMethodInsn(Opcodes.INVOKESTATIC, "java/lang/Long", "valueOf", "(J)Ljava/lang/Long;", false)
            "F" -> mv.visitMethodInsn(Opcodes.INVOKESTATIC, "java/lang/Float", "valueOf", "(F)Ljava/lang/Float;", false)
            "D" -> mv.visitMethodInsn(Opcodes.INVOKESTATIC, "java/lang/Double", "valueOf", "(D)Ljava/lang/Double;", false)
            "Z" -> mv.visitMethodInsn(Opcodes.INVOKESTATIC, "java/lang/Boolean", "valueOf", "(Z)Ljava/lang/Boolean;", false)
            "B" -> mv.visitMethodInsn(Opcodes.INVOKESTATIC, "java/lang/Byte", "valueOf", "(B)Ljava/lang/Byte;", false)
            "S" -> mv.visitMethodInsn(Opcodes.INVOKESTATIC, "java/lang/Short", "valueOf", "(S)Ljava/lang/Short;", false)
            "C" -> mv.visitMethodInsn(Opcodes.INVOKESTATIC, "java/lang/Character", "valueOf", "(C)Ljava/lang/Character;", false)
        }
    }

    private fun arrayElementDesc(arrExpr: IrExpression): String {
        val desc = typeMapper.jvmDescriptorFromIrType(arrExpr.type)
        return if (desc.startsWith("[")) desc.substring(1) else "Ljava/lang/Object;"
    }

    private fun arrayLoadOpcode(desc: String): Int {
        return when (desc) {
            "I" -> Opcodes.IALOAD; "J" -> Opcodes.LALOAD; "F" -> Opcodes.FALOAD
            "D" -> Opcodes.DALOAD; "Z", "B" -> Opcodes.BALOAD; "C" -> Opcodes.CALOAD
            "S" -> Opcodes.SALOAD; else -> Opcodes.AALOAD
        }
    }

    private fun arrayStoreOpcode(desc: String): Int {
        return when (desc) {
            "I" -> Opcodes.IASTORE; "J" -> Opcodes.LASTORE; "F" -> Opcodes.FASTORE
            "D" -> Opcodes.DASTORE; "Z", "B" -> Opcodes.BASTORE; "C" -> Opcodes.CASTORE
            "S" -> Opcodes.SASTORE; else -> Opcodes.AASTORE
        }
    }

    private fun emitNewArray(mv: MethodVisitor, elemDesc: String) {
        when (elemDesc) {
            "I" -> mv.visitIntInsn(Opcodes.NEWARRAY, Opcodes.T_INT)
            "J" -> mv.visitIntInsn(Opcodes.NEWARRAY, Opcodes.T_LONG)
            "F" -> mv.visitIntInsn(Opcodes.NEWARRAY, Opcodes.T_FLOAT)
            "D" -> mv.visitIntInsn(Opcodes.NEWARRAY, Opcodes.T_DOUBLE)
            "Z" -> mv.visitIntInsn(Opcodes.NEWARRAY, Opcodes.T_BOOLEAN)
            "B" -> mv.visitIntInsn(Opcodes.NEWARRAY, Opcodes.T_BYTE)
            "S" -> mv.visitIntInsn(Opcodes.NEWARRAY, Opcodes.T_SHORT)
            "C" -> mv.visitIntInsn(Opcodes.NEWARRAY, Opcodes.T_CHAR)
            else -> {
                val internalName = elemDesc.removePrefix("L").removeSuffix(";").replace('.', '/')
                mv.visitTypeInsn(Opcodes.ANEWARRAY, internalName)
            }
        }
    }

    private fun emitLambda(mv: MethodVisitor, expr: IrFunctionExpression, ctx: MethodContext, owner: String) {
        // Lambdas are now translated as anonymous IrClass declarations in the translator.
        // IrFunctionExpression is deprecated; use the anonymous class path instead.
        // Fallback: push null for any remaining IrFunctionExpression nodes
        mv.visitInsn(Opcodes.ACONST_NULL)
    }

    private fun returnOpcode(desc: String): Int {
        return when {
            desc == "I" || desc == "Z" || desc == "B" || desc == "S" || desc == "C" -> Opcodes.IRETURN
            desc == "J" -> Opcodes.LRETURN
            desc == "F" -> Opcodes.FRETURN
            desc == "D" -> Opcodes.DRETURN
            else -> Opcodes.ARETURN
        }
    }

    private fun loadOpcode(desc: String): Int {
        return when {
            desc == "I" || desc == "Z" || desc == "B" || desc == "S" || desc == "C" -> Opcodes.ILOAD
            desc == "J" -> Opcodes.LLOAD
            desc == "F" -> Opcodes.FLOAD
            desc == "D" -> Opcodes.DLOAD
            else -> Opcodes.ALOAD
        }
    }
}
