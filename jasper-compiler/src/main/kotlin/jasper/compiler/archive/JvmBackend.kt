package jasper.compiler

import jasper.ast.*
import jasper.translator.TypeMapper
import org.jetbrains.org.objectweb.asm.*
import java.io.File
import java.nio.file.Path

class JvmBackend(private val typeMapper: TypeMapper = TypeMapper()) {

    data class LocalInfo(val name: String, val type: JasType?, val slot: Int)

    private data class PendingLambda(
        val name: String,
        val parameters: List<JasParameter>,
        val body: JasBlock,
        val returnType: JasType?,
        val captured: List<String>
    )

    private var currentClassWriter: ClassWriter? = null
    private var currentInternalName: String? = null
    private var lambdaCounter = 0
    private val pendingLambdaMethods = mutableListOf<PendingLambda>()
    private var currentPkg: String = ""

    private val functionRegistry = mutableMapOf<String, JasFunction>()
    private val methodRegistry = mutableMapOf<String, MutableMap<String, JasFunction>>()
    private val fieldDescriptorMap = mutableMapOf<String, String>()

    fun generate(
        packageName: String?,
        declarations: List<JasDeclaration>,
        outputDir: Path
    ) {
        currentPkg = (packageName ?: "").replace('.', '/')
        currentClassWriter = null
        currentInternalName = null
        lambdaCounter = 0
        pendingLambdaMethods.clear()
        functionRegistry.clear()
        methodRegistry.clear()
        fieldDescriptorMap.clear()
        val dir = outputDir.resolve(currentPkg).toFile()
        dir.mkdirs()

        for (decl in declarations) {
            if (decl is JasFunction) {
                functionRegistry[decl.name] = decl
            }
        }

        for (decl in declarations) {
            when (decl) {
                is JasClass -> generateClass(decl, currentPkg, dir)
                is JasInterface -> generateInterface(decl, currentPkg, dir)
                is JasEnum -> generateEnum(decl, currentPkg, dir)
                is JasAnnotationType -> generateAnnotationType(decl, currentPkg, dir)
                is JasFunction -> generateMainClass(decl, currentPkg, dir)
                else -> {}
            }
        }
    }

    private fun generateClass(cls: JasClass, pkg: String, dir: File) {
        val internalName = "$pkg/${cls.name}"
        val superName = typeMapper.jvmInternalNameFromType(cls.superclass)
            .let { if (it == "void") "java/lang/Object" else it }
        val interfaces = cls.interfaces.map {
            typeMapper.jvmInternalNameFromType(it)
        }.toTypedArray()

        val cw = ClassWriter(ClassWriter.COMPUTE_FRAMES)
        currentClassWriter = cw
        currentInternalName = internalName
        lambdaCounter = 0
        pendingLambdaMethods.clear()
        fieldDescriptorMap.clear()
        methodRegistry[internalName] = mutableMapOf()

        val classSignature = if (cls.typeParameters.isNotEmpty()) {
            jvmClassSignature(cls.typeParameters, cls.superclass)
        } else null

        cw.visit(
            Opcodes.V1_8,
            Opcodes.ACC_PUBLIC or Opcodes.ACC_SUPER,
            internalName,
            classSignature,
            superName,
            interfaces
        )

        for (member in cls.members) {
            when (member) {
                is JasProperty -> {
                    generateField(cw, member)
                    generateSyntheticAccessors(cw, member, internalName)
                }
                is JasFunction -> {
                    methodRegistry[internalName]?.put(member.name, member)
                    generateMethod(cw, member, internalName, false)
                }
                else -> {}
            }
        }
        for (ctor in cls.constructors) {
            generateConstructor(cw, ctor, internalName)
        }

        var index = 0
        while (index < pendingLambdaMethods.size) {
            generateLambdaSyntheticMethod(cw, pendingLambdaMethods[index], internalName)
            index++
        }

        cw.visitEnd()
        currentClassWriter = null
        currentInternalName = null
        dir.resolve("${cls.name}.class").writeBytes(cw.toByteArray())
    }

    private fun generateInterface(iface: JasInterface, pkg: String, dir: File) {
        val internalName = "$pkg/${iface.name}"
        val cw = ClassWriter(ClassWriter.COMPUTE_FRAMES)
        val interfaceSignature = if (iface.typeParameters.isNotEmpty()) {
            jvmClassSignature(iface.typeParameters, null)
        } else null
        cw.visit(
            Opcodes.V1_8,
            Opcodes.ACC_PUBLIC or Opcodes.ACC_INTERFACE or Opcodes.ACC_ABSTRACT,
            internalName,
            interfaceSignature,
            "java/lang/Object",
            iface.extends.map { typeMapper.jvmInternalNameFromType(it) }.toTypedArray()
        )

        for (member in iface.members) {
            when (member) {
                is JasFunction -> {
                    val desc = methodDescriptor(member)
                    val methodSignature = if (member.typeParameters.isNotEmpty()) {
                        jvmMethodSignature(member)
                    } else null
                    val mv = cw.visitMethod(
                        Opcodes.ACC_PUBLIC or Opcodes.ACC_ABSTRACT,
                        member.name,
                        desc,
                        methodSignature, null
                    )
                    mv.visitEnd()
                }
                else -> {}
            }
        }

        cw.visitEnd()
        dir.resolve("${iface.name}.class").writeBytes(cw.toByteArray())
    }

    private fun generateEnum(enum: JasEnum, pkg: String, dir: File) {
        val internalName = "$pkg/${enum.name}"
        val cw = ClassWriter(ClassWriter.COMPUTE_FRAMES)
        currentClassWriter = cw
        currentInternalName = internalName
        lambdaCounter = 0
        pendingLambdaMethods.clear()

        cw.visit(
            Opcodes.V1_8,
            Opcodes.ACC_PUBLIC or Opcodes.ACC_SUPER or Opcodes.ACC_ENUM,
            internalName,
            null,
            "java/lang/Enum",
            emptyArray()
        )

        // Static final enum constant fields
        for (constant in enum.constants) {
            cw.visitField(
                Opcodes.ACC_PUBLIC or Opcodes.ACC_STATIC or Opcodes.ACC_FINAL or Opcodes.ACC_ENUM,
                constant.name,
                "L$internalName;",
                null, null
            ).visitEnd()
        }

        // Determine constructor args from enum constants
        val hasArgs = enum.constants.any { it.args.isNotEmpty() }
        val argDescs = if (hasArgs && enum.constants.isNotEmpty()) {
            val firstWithArgs = enum.constants.firstOrNull { it.args.isNotEmpty() }
            firstWithArgs?.args?.map { inferEnumArgDesc(it) } ?: emptyList()
        } else emptyList()

        // Generate private constructor: (String, int, argTypes...)V
        val ctorDesc = "(Ljava/lang/String;I${argDescs.joinToString("")})V"
        val ctorMethod = cw.visitMethod(Opcodes.ACC_PRIVATE, "<init>", ctorDesc, null, null)
        ctorMethod.visitCode()
        ctorMethod.visitVarInsn(Opcodes.ALOAD, 0)
        ctorMethod.visitVarInsn(Opcodes.ALOAD, 1)
        ctorMethod.visitVarInsn(Opcodes.ILOAD, 2)
        ctorMethod.visitMethodInsn(Opcodes.INVOKESPECIAL, "java/lang/Enum", "<init>", "(Ljava/lang/String;I)V", false)
        var ctorSlot = 3
        for ((i, desc) in argDescs.withIndex()) {
            val fieldName = "\$param$i"
            cw.visitField(Opcodes.ACC_PRIVATE or Opcodes.ACC_FINAL, fieldName, desc, null, null).visitEnd()
            ctorMethod.visitVarInsn(Opcodes.ALOAD, 0)
            ctorMethod.visitVarInsn(loadOpcode(desc), ctorSlot)
            ctorMethod.visitFieldInsn(Opcodes.PUTFIELD, internalName, fieldName, desc)
            ctorSlot += if (desc == "J" || desc == "D") 2 else 1
        }
        ctorMethod.visitInsn(Opcodes.RETURN)
        ctorMethod.visitMaxs(0, 0)
        ctorMethod.visitEnd()

        // $VALUES field
        cw.visitField(
            Opcodes.ACC_PRIVATE or Opcodes.ACC_STATIC or Opcodes.ACC_FINAL or Opcodes.ACC_SYNTHETIC,
            "\$VALUES", "[L$internalName;", null, null
        ).visitEnd()

        // <clinit>
        val clinit = cw.visitMethod(Opcodes.ACC_STATIC, "<clinit>", "()V", null, null)
        clinit.visitCode()
        for ((i, constant) in enum.constants.withIndex()) {
            clinit.visitTypeInsn(Opcodes.NEW, internalName)
            clinit.visitInsn(Opcodes.DUP)
            clinit.visitLdcInsn(constant.name)
            pushInt(clinit, i)
            for (arg in constant.args) {
                when (arg) {
                    is JasIntLiteral -> {
                        if (arg.value >= Int.MIN_VALUE && arg.value <= Int.MAX_VALUE)
                            pushInt(clinit, arg.value.toInt())
                        else clinit.visitLdcInsn(arg.value)
                    }
                    is JasStringLiteral -> clinit.visitLdcInsn(arg.text)
                    is JasBoolLiteral -> clinit.visitInsn(if (arg.value) Opcodes.ICONST_1 else Opcodes.ICONST_0)
                    is JasFloatLiteral -> clinit.visitLdcInsn(arg.value)
                    else -> clinit.visitInsn(Opcodes.ACONST_NULL)
                }
            }
            clinit.visitMethodInsn(Opcodes.INVOKESPECIAL, internalName, "<init>", ctorDesc, false)
            clinit.visitFieldInsn(Opcodes.PUTSTATIC, internalName, constant.name, "L$internalName;")
        }
        pushInt(clinit, enum.constants.size)
        clinit.visitTypeInsn(Opcodes.ANEWARRAY, internalName)
        for ((i, constant) in enum.constants.withIndex()) {
            clinit.visitInsn(Opcodes.DUP)
            pushInt(clinit, i)
            clinit.visitFieldInsn(Opcodes.GETSTATIC, internalName, constant.name, "L$internalName;")
            clinit.visitInsn(Opcodes.AASTORE)
        }
        clinit.visitFieldInsn(Opcodes.PUTSTATIC, internalName, "\$VALUES", "[L$internalName;")
        clinit.visitInsn(Opcodes.RETURN)
        clinit.visitMaxs(0, 0)
        clinit.visitEnd()

        // values()
        val valuesMethod = cw.visitMethod(
            Opcodes.ACC_PUBLIC or Opcodes.ACC_STATIC,
            "values", "()[L$internalName;", null, null
        )
        valuesMethod.visitCode()
        valuesMethod.visitFieldInsn(Opcodes.GETSTATIC, internalName, "\$VALUES", "[L$internalName;")
        valuesMethod.visitMethodInsn(Opcodes.INVOKEVIRTUAL, "[L$internalName;", "clone", "()Ljava/lang/Object;", false)
        valuesMethod.visitTypeInsn(Opcodes.CHECKCAST, "[L$internalName;")
        valuesMethod.visitInsn(Opcodes.ARETURN)
        valuesMethod.visitMaxs(0, 0)
        valuesMethod.visitEnd()

        // valueOf()
        val valueOfMethod = cw.visitMethod(
            Opcodes.ACC_PUBLIC or Opcodes.ACC_STATIC,
            "valueOf", "(Ljava/lang/String;)L$internalName;", null, null
        )
        valueOfMethod.visitCode()
        valueOfMethod.visitLdcInsn(Type.getObjectType(internalName))
        valueOfMethod.visitVarInsn(Opcodes.ALOAD, 0)
        valueOfMethod.visitMethodInsn(Opcodes.INVOKESTATIC, "java/lang/Enum", "valueOf", "(Ljava/lang/Class;Ljava/lang/String;)Ljava/lang/Enum;", false)
        valueOfMethod.visitTypeInsn(Opcodes.CHECKCAST, internalName)
        valueOfMethod.visitInsn(Opcodes.ARETURN)
        valueOfMethod.visitMaxs(0, 0)
        valueOfMethod.visitEnd()

        // Generate members
        for (member in enum.members) {
            when (member) {
                is JasFunction -> generateMethod(cw, member, internalName, false)
                is JasProperty -> {
                    generateField(cw, member)
                    generateSyntheticAccessors(cw, member, internalName)
                }
                else -> {}
            }
        }

        var index = 0
        while (index < pendingLambdaMethods.size) {
            generateLambdaSyntheticMethod(cw, pendingLambdaMethods[index], internalName)
            index++
        }

        cw.visitEnd()
        currentClassWriter = null
        currentInternalName = null
        dir.resolve("${enum.name}.class").writeBytes(cw.toByteArray())
    }

    private fun generateMainClass(func: JasFunction, pkg: String, dir: File) {
        val internalName = "$pkg/${func.name}"
        val cw = ClassWriter(ClassWriter.COMPUTE_FRAMES)
        currentClassWriter = cw
        currentInternalName = internalName
        lambdaCounter = 0
        pendingLambdaMethods.clear()
        fieldDescriptorMap.clear()

        cw.visit(
            Opcodes.V1_8,
            Opcodes.ACC_PUBLIC or Opcodes.ACC_SUPER,
            internalName,
            null,
            "java/lang/Object",
            emptyArray()
        )

        generateMethod(cw, func, internalName, true)

        var index = 0
        while (index < pendingLambdaMethods.size) {
            generateLambdaSyntheticMethod(cw, pendingLambdaMethods[index], internalName)
            index++
        }

        if (func.name == "main" && func.parameters.isEmpty()) {
            val mv = cw.visitMethod(
                Opcodes.ACC_PUBLIC or Opcodes.ACC_STATIC,
                "main", "([Ljava/lang/String;)V", null, null
            )
            mv.visitCode()
            val mainDesc = methodDescriptor(func)
            mv.visitMethodInsn(
                Opcodes.INVOKESTATIC, internalName,
                "main", mainDesc, false
            )
            val returnDesc = typeMapper.jvmReturnDescriptor(func.returnType)
            if (returnDesc != "V") {
                if (returnDesc == "J" || returnDesc == "D") {
                    mv.visitInsn(Opcodes.POP2)
                } else {
                    mv.visitInsn(Opcodes.POP)
                }
            }
            mv.visitInsn(Opcodes.RETURN)
            mv.visitMaxs(1, 1)
            mv.visitEnd()
        }

        cw.visitEnd()
        currentClassWriter = null
        currentInternalName = null
        dir.resolve("${func.name}.class").writeBytes(cw.toByteArray())
    }

    private fun generateAnnotationType(ann: JasAnnotationType, pkg: String, dir: File) {
        val internalName = "$pkg/${ann.name}"
        val cw = ClassWriter(ClassWriter.COMPUTE_FRAMES)
        currentClassWriter = cw
        currentInternalName = internalName
        lambdaCounter = 0
        pendingLambdaMethods.clear()

        val access = Opcodes.ACC_PUBLIC or Opcodes.ACC_INTERFACE or Opcodes.ACC_ABSTRACT or Opcodes.ACC_ANNOTATION
        cw.visit(
            Opcodes.V1_8,
            access,
            internalName,
            null,
            "java/lang/Object",
            arrayOf("java/lang/annotation/Annotation")
        )

        val retentionVis = cw.visitAnnotation("Ljava/lang/annotation/Retention;", true)
        retentionVis.visitEnum("value", "Ljava/lang/annotation/RetentionPolicy;", "RUNTIME")
        retentionVis.visitEnd()

        for (member in ann.members) {
            val desc = typeMapper.jvmReturnDescriptor(member.type)
            val mv = cw.visitMethod(
                Opcodes.ACC_PUBLIC or Opcodes.ACC_ABSTRACT,
                member.name,
                "()$desc",
                null, null
            )
            if (member.defaultValue != null) {
                val defaultVal = generateAnnotationDefaultValue(member.defaultValue, desc)
                if (defaultVal != null) {
                    val av = mv.visitAnnotationDefault()
                    av.visit(null, defaultVal)
                    av.visitEnd()
                }
            }
            mv.visitEnd()
        }

        cw.visitEnd()
        currentClassWriter = null
        currentInternalName = null
        dir.resolve("${ann.name}.class").writeBytes(cw.toByteArray())
    }

    private fun generateAnnotationDefaultValue(expr: JasExpression, desc: String): Any? {
        return when (expr) {
            is JasIntLiteral -> {
                when (desc) {
                    "J" -> expr.value
                    else -> expr.value.toInt()
                }
            }
            is JasFloatLiteral -> expr.value
            is JasStringLiteral -> expr.text
            is JasBoolLiteral -> expr.value
            else -> null
        }
    }

    private fun generateField(cw: ClassWriter, field: JasProperty) {
        val access = if (field.modifiers.contains("static"))
            Opcodes.ACC_STATIC else Opcodes.ACC_PRIVATE
        val desc = typeMapper.jvmDescriptorFromType(field.type)
        fieldDescriptorMap[field.name] = desc
        cw.visitField(access, field.name, desc, null, null).visitEnd()
    }

    private fun generateSyntheticAccessors(cw: ClassWriter, prop: JasProperty, owner: String) {
        val fieldDesc = typeMapper.jvmDescriptorFromType(prop.type)

        val prefix = if (fieldDesc == "Z") "is" else "get"
        val getterName = prefix + prop.name.replaceFirstChar { it.uppercase() }
        val getterMv = cw.visitMethod(Opcodes.ACC_PUBLIC, getterName, "()$fieldDesc", null, null)
        getterMv.visitCode()
        if (prop.getter?.body != null) {
            val statements = prop.getter.body.statements
            val locals = mutableListOf<LocalInfo>()
            val variables = mutableMapOf<String, Int>()
            locals.add(LocalInfo("this", null, 0))
            val ctx = CodegenContext(getterMv, locals, prop.type, 1, variables, false)
            for (i in 0 until statements.size - 1) {
                generateStatement(getterMv, statements[i], ctx, owner)
            }
            if (statements.isNotEmpty()) {
                val last = statements.last()
                if (last is JasExpressionStatement) {
                    generateExpression(getterMv, last.expression, ctx, owner)
                } else {
                    generateStatement(getterMv, last, ctx, owner)
                }
            }
        } else {
            getterMv.visitVarInsn(Opcodes.ALOAD, 0)
            getterMv.visitFieldInsn(Opcodes.GETFIELD, owner, prop.name, fieldDesc)
        }
        getterMv.visitInsn(returnOpcode(fieldDesc))
        getterMv.visitMaxs(0, 0)
        getterMv.visitEnd()

        val setterName = "set" + prop.name.replaceFirstChar { it.uppercase() }
        val setterMv = cw.visitMethod(Opcodes.ACC_PUBLIC, setterName, "($fieldDesc)V", null, null)
        setterMv.visitCode()
        if (prop.setter?.body != null) {
            val locals = mutableListOf<LocalInfo>()
            val variables = mutableMapOf<String, Int>()
            locals.add(LocalInfo("this", null, 0))
            val paramName = prop.setter.parameterName ?: "value"
            val paramSlot = 1
            locals.add(LocalInfo(paramName, prop.type, paramSlot))
            variables[paramName] = paramSlot
            val nextSlot = paramSlot + if (fieldDesc == "J" || fieldDesc == "D") 2 else 1
            val ctx = CodegenContext(setterMv, locals, null, nextSlot, variables, false)
            generateBlock(setterMv, prop.setter.body, ctx, owner)
        } else {
            setterMv.visitVarInsn(Opcodes.ALOAD, 0)
            setterMv.visitVarInsn(loadOpcode(fieldDesc), 1)
            setterMv.visitFieldInsn(Opcodes.PUTFIELD, owner, prop.name, fieldDesc)
        }
        setterMv.visitInsn(Opcodes.RETURN)
        setterMv.visitMaxs(0, 0)
        setterMv.visitEnd()
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

    private fun generateMethod(
        cw: ClassWriter,
        func: JasFunction,
        owner: String,
        isStatic: Boolean
    ) {
        val desc = methodDescriptor(func)
        val methodSignature = if (func.typeParameters.isNotEmpty()) {
            jvmMethodSignature(func)
        } else null
        val access = Opcodes.ACC_PUBLIC or (if (isStatic) Opcodes.ACC_STATIC else 0)
        val mv = cw.visitMethod(access, func.name, desc, methodSignature, null)
        mv.visitCode()

        if (func.body != null) {
            val locals = mutableListOf<LocalInfo>()
            val variables = mutableMapOf<String, Int>()
            var slot = if (isStatic) 0 else 1
            for (p in func.parameters) {
                locals.add(LocalInfo(p.name, p.type, slot))
                variables[p.name] = slot
                slot += typeSlots(p.type)
            }

            val ctx = CodegenContext(mv, locals, func.returnType, slot, variables, isStatic)
            val hasDeferred = hasDeferBlock(func.body)
            if (hasDeferred) {
                generateBlock(mv, func.body, ctx, owner)
            } else {
                generateBlock(mv, func.body, ctx, owner)
            }
            if (!func.body.statements.any { it is JasReturn }) {
                generateReturn(mv, func.returnType)
            }
        } else {
            generateReturn(mv, func.returnType)
        }

        mv.visitMaxs(0, 0)
        mv.visitEnd()
    }

    private fun generateConstructor(
        cw: ClassWriter,
        ctor: JasConstructor,
        owner: String
    ) {
        val desc = constructorDescriptor(ctor)
        val mv = cw.visitMethod(Opcodes.ACC_PUBLIC, "<init>", desc, null, null)
        mv.visitCode()

        mv.visitVarInsn(Opcodes.ALOAD, 0)
        mv.visitMethodInsn(Opcodes.INVOKESPECIAL, "java/lang/Object", "<init>", "()V", false)

        val locals = mutableListOf<LocalInfo>()
        val variables = mutableMapOf<String, Int>()
        locals.add(LocalInfo("this", null, 0))
        var slot = 1
        for (p in ctor.parameters) {
            locals.add(LocalInfo(p.name, p.type, slot))
            variables[p.name] = slot
            slot += typeSlots(p.type)
        }

        if (ctor.delegateCall != null) {
            val ctx = CodegenContext(mv, locals, null, slot, variables, false)
            mv.visitVarInsn(Opcodes.ALOAD, 0)
            for (arg in ctor.delegateCall!!.args) {
                generateExpression(mv, arg, ctx, owner)
            }
            val target = if (ctor.delegateCall!!.isSuper) "java/lang/Object" else owner
            val paramDescs = ctor.delegateCall!!.args.joinToString("") { "Ljava/lang/Object;" }
            mv.visitMethodInsn(Opcodes.INVOKESPECIAL, target, "<init>", "($paramDescs)V", false)
        }

        if (ctor.body != null) {
            val ctx = CodegenContext(mv, locals, null, slot, variables, false)
            generateBlock(mv, ctor.body, ctx, owner)
        }

        mv.visitInsn(Opcodes.RETURN)
        mv.visitMaxs(0, 0)
        mv.visitEnd()
    }

    private fun methodDescriptor(func: JasFunction): String {
        val paramDescs = func.parameters.joinToString("") {
            typeMapper.jvmDescriptorFromType(it.type)
        }
        val returnDesc = typeMapper.jvmReturnDescriptor(func.returnType)
        return "($paramDescs)$returnDesc"
    }

    private fun constructorDescriptor(ctor: JasConstructor): String {
        val paramDescs = ctor.parameters.joinToString("") {
            typeMapper.jvmDescriptorFromType(it.type)
        }
        return "($paramDescs)V"
    }

    private fun typeSlots(type: JasType?): Int {
        val desc = typeMapper.jvmDescriptorFromType(type)
        return if (desc == "J" || desc == "D") 2 else 1
    }

    private class CodegenContext(
        val mv: MethodVisitor,
        val locals: MutableList<LocalInfo>,
        val returnType: JasType?,
        var nextSlot: Int,
        val variables: MutableMap<String, Int>,
        val isStatic: Boolean
    ) {
        val loopStack = mutableListOf<Pair<Label, Label>>()
        val deferredBodies = mutableListOf<JasBlock>()
        val lockSlots = mutableListOf<Int>()
        val inferredTypes = mutableMapOf<String, JasType>()
    }

    private fun generateBlock(mv: MethodVisitor, block: JasBlock, ctx: CodegenContext, owner: String) {
        for (stmt in block.statements) {
            generateStatement(mv, stmt, ctx, owner)
        }
    }

    private fun generateStatement(mv: MethodVisitor, stmt: JasStatement, ctx: CodegenContext, owner: String) {
        when (stmt) {
            is JasExpressionStatement -> {
                generateExpression(mv, stmt.expression, ctx, owner)
                mv.visitInsn(Opcodes.POP)
            }
            is JasVariableStatement -> {
                val inferredType = stmt.type ?: when (val init = stmt.initializer) {
                    is JasNew -> init.type
                    else -> null
                }
                if (inferredType != null && inferredType != stmt.type) {
                    ctx.inferredTypes[stmt.name] = inferredType
                }
                val desc = typeMapper.jvmDescriptorFromType(stmt.type)
                val slot = ctx.nextSlot
                ctx.nextSlot += if (desc == "J" || desc == "D") 2 else 1
                ctx.variables[stmt.name] = slot
                ctx.locals.add(LocalInfo(stmt.name, stmt.type, slot))
                if (stmt.initializer != null) {
                    generateExpression(mv, stmt.initializer, ctx, owner)
                    storeLocal(mv, desc, slot)
                }
            }
            is JasBlock -> generateBlock(mv, stmt, ctx, owner)
            is JasReturn -> {
                val hasDeferred = ctx.deferredBodies.isNotEmpty()
                val hasLocks = ctx.lockSlots.isNotEmpty()
                if (hasLocks) {
                    val retSlot: Int?
                    val retDesc: String
                    if (stmt.value != null) {
                        generateExpression(mv, stmt.value, ctx, owner)
                        retDesc = typeMapper.jvmDescriptorFromType(ctx.returnType)
                        retSlot = ctx.nextSlot
                        ctx.nextSlot += 1
                        storeLocal(mv, retDesc, retSlot)
                    } else {
                        retSlot = null
                        retDesc = "V"
                    }
                    for (ls in ctx.lockSlots.reversed()) {
                        loadLocal(mv, "Ljava/lang/Object;", ls)
                        mv.visitInsn(Opcodes.MONITOREXIT)
                    }
                    if (hasDeferred) {
                        emitDeferredActions(mv, ctx, owner)
                    }
                    if (retSlot != null) {
                        loadLocal(mv, retDesc, retSlot)
                    }
                    generateReturn(mv, ctx.returnType)
                } else if (hasDeferred) {
                    emitDeferredActions(mv, ctx, owner)
                    if (stmt.value != null) {
                        generateExpression(mv, stmt.value, ctx, owner)
                    }
                    generateReturn(mv, ctx.returnType)
                } else {
                    if (stmt.value != null) {
                        generateExpression(mv, stmt.value, ctx, owner)
                    }
                    generateReturn(mv, ctx.returnType)
                }
            }
            is JasIf -> {
                generateExpression(mv, stmt.condition, ctx, owner)
                val elseLabel = Label()
                val endLabel = Label()
                mv.visitJumpInsn(Opcodes.IFEQ, elseLabel)
                generateBlock(mv, stmt.thenBody, ctx, owner)
                mv.visitJumpInsn(Opcodes.GOTO, endLabel)
                mv.visitLabel(elseLabel)
                if (stmt.elseBody != null) {
                    generateBlock(mv, stmt.elseBody, ctx, owner)
                }
                mv.visitLabel(endLabel)
            }
            is JasWhile -> {
                val loopLabel = Label()
                val endLabel = Label()
                ctx.loopStack.add(Pair(loopLabel, endLabel))
                mv.visitLabel(loopLabel)
                generateExpression(mv, stmt.condition, ctx, owner)
                mv.visitJumpInsn(Opcodes.IFEQ, endLabel)
                generateBlock(mv, stmt.body, ctx, owner)
                mv.visitJumpInsn(Opcodes.GOTO, loopLabel)
                mv.visitLabel(endLabel)
                ctx.loopStack.removeLast()
            }
            is JasDoWhile -> {
                val loopLabel = Label()
                val endLabel = Label()
                ctx.loopStack.add(Pair(loopLabel, endLabel))
                mv.visitLabel(loopLabel)
                generateBlock(mv, stmt.body, ctx, owner)
                generateExpression(mv, stmt.condition, ctx, owner)
                mv.visitJumpInsn(Opcodes.IFNE, loopLabel)
                mv.visitLabel(endLabel)
                ctx.loopStack.removeLast()
            }
            is JasForStatement -> {
                if (stmt.init != null) {
                    generateStatement(mv, stmt.init, ctx, owner)
                }
                val loopLabel = Label()
                val exitLabel = Label()
                val endLabel = Label()
                ctx.loopStack.add(Pair(loopLabel, endLabel))
                mv.visitLabel(loopLabel)
                if (stmt.condition != null) {
                    generateExpression(mv, stmt.condition, ctx, owner)
                    mv.visitJumpInsn(Opcodes.IFEQ, exitLabel)
                }
                generateBlock(mv, stmt.body, ctx, owner)
                if (stmt.update != null) {
                    generateExpression(mv, stmt.update, ctx, owner)
                    mv.visitInsn(Opcodes.POP)
                }
                mv.visitJumpInsn(Opcodes.GOTO, loopLabel)
                mv.visitLabel(exitLabel)
                mv.visitLabel(endLabel)
                ctx.loopStack.removeLast()
            }
            is JasForInStatement -> {
                val loopEnteredSlot = if (stmt.elseBody != null) {
                    val slot = ctx.nextSlot
                    ctx.nextSlot += 1
                    pushInt(mv, 0)
                    storeLocal(mv, "I", slot)
                    slot
                } else -1
                val arraySlot = ctx.nextSlot
                ctx.nextSlot += 1
                val indexSlot = ctx.nextSlot
                ctx.nextSlot += 1
                val resolvedElemDesc = tryResolveArrayElementDesc(stmt.iterable, ctx) ?: "Ljava/lang/Object;"
                val isPrimitiveElem = resolvedElemDesc in listOf("I", "J", "F", "D", "Z", "B", "S", "C")
                val elemDesc = if (isPrimitiveElem) resolvedElemDesc else "Ljava/lang/Object;"
                val arrayDesc = if (elemDesc == "Ljava/lang/Object;") "Ljava/lang/Object;" else "[$elemDesc"
                generateExpression(mv, stmt.iterable, ctx, owner)
                storeLocal(mv, arrayDesc, arraySlot)
                pushInt(mv, 0)
                storeLocal(mv, "I", indexSlot)
                val loopLabel = Label()
                val breakLabel = Label()
                val thenLabel = Label()
                ctx.loopStack.add(Pair(loopLabel, breakLabel))
                mv.visitLabel(loopLabel)
                loadLocal(mv, "I", indexSlot)
                loadLocal(mv, arrayDesc, arraySlot)
                mv.visitInsn(Opcodes.ARRAYLENGTH)
                mv.visitJumpInsn(Opcodes.IF_ICMPGE, thenLabel)
                if (stmt.elseBody != null) {
                    pushInt(mv, 1)
                    storeLocal(mv, "I", loopEnteredSlot)
                }
                loadLocal(mv, arrayDesc, arraySlot)
                loadLocal(mv, "I", indexSlot)
                mv.visitInsn(arrayLoadOpcode(elemDesc))
                if (!isPrimitiveElem && resolvedElemDesc != "Ljava/lang/Object;") {
                    val internalName = resolvedElemDesc.removePrefix("L").removeSuffix(";").replace('.', '/')
                    mv.visitTypeInsn(Opcodes.CHECKCAST, internalName)
                }
                val varSlot = ctx.nextSlot
                ctx.nextSlot += 1
                val elementType = when (resolvedElemDesc) {
                    "I" -> JasPrimitiveType("int32")
                    "J" -> JasPrimitiveType("int64")
                    "F" -> JasPrimitiveType("float32")
                    "D" -> JasPrimitiveType("float64")
                    "Z" -> JasPrimitiveType("bool")
                    "Ljava/lang/String;" -> JasPrimitiveType("string")
                    else -> null
                }
                storeLocal(mv, elemDesc, varSlot)
                ctx.variables[stmt.varName] = varSlot
                ctx.locals.add(LocalInfo(stmt.varName, elementType, varSlot))
                generateBlock(mv, stmt.body, ctx, owner)
                loadLocal(mv, "I", indexSlot)
                pushInt(mv, 1)
                mv.visitInsn(Opcodes.IADD)
                storeLocal(mv, "I", indexSlot)
                mv.visitJumpInsn(Opcodes.GOTO, loopLabel)
                mv.visitLabel(thenLabel)
                ctx.loopStack.removeAt(ctx.loopStack.size - 1)
                if (stmt.elseBody != null) {
                    val elseBodyLabel = Label()
                    loadLocal(mv, "I", loopEnteredSlot)
                    mv.visitJumpInsn(Opcodes.IFEQ, elseBodyLabel)
                    if (stmt.thenBody != null) {
                        generateBlock(mv, stmt.thenBody, ctx, owner)
                    }
                    mv.visitJumpInsn(Opcodes.GOTO, breakLabel)
                    mv.visitLabel(elseBodyLabel)
                    generateBlock(mv, stmt.elseBody, ctx, owner)
                } else {
                    if (stmt.thenBody != null) {
                        generateBlock(mv, stmt.thenBody, ctx, owner)
                    }
                }
                mv.visitLabel(breakLabel)
            }
            is JasSwitch -> {
                generateExpression(mv, stmt.expression, ctx, owner)
                val endLabel = Label()
                val caseLabels = stmt.cases.map { Label() }
                val table = mutableListOf<Int>()
                val labels = mutableListOf<Label>()
                var defaultLabel = endLabel
                for ((i, case) in stmt.cases.withIndex()) {
                    if (case.values.isEmpty()) {
                        defaultLabel = caseLabels[i]
                    } else {
                        for (v in case.values) {
                            if (v is JasIntLiteral) {
                                table.add(v.value.toInt())
                                labels.add(caseLabels[i])
                            }
                        }
                    }
                }
                if (table.isNotEmpty()) {
                    val min = table.min()
                    val max = table.max()
                    if (max - min > 10) {
                        mv.visitLookupSwitchInsn(defaultLabel, table.toIntArray(), labels.toTypedArray())
                    } else {
                        val denseLabels = Array(max - min + 1) { defaultLabel }
                        for (i in table.indices) {
                            val idx = table[i] - min
                            denseLabels[idx] = labels[i]
                        }
                        mv.visitTableSwitchInsn(min, max, defaultLabel, *denseLabels)
                    }
                } else {
                    mv.visitJumpInsn(Opcodes.GOTO, defaultLabel)
                }
                for ((i, case) in stmt.cases.withIndex()) {
                    mv.visitLabel(caseLabels[i])
                    generateBlock(mv, case.body, ctx, owner)
                    mv.visitJumpInsn(Opcodes.GOTO, endLabel)
                }
                mv.visitLabel(endLabel)
            }
            is JasTry -> {
                val tryStart = Label()
                val tryEnd = Label()
                val catchStart = Label()
                val endLabel = Label()
                mv.visitTryCatchBlock(tryStart, tryEnd, catchStart, "java/lang/Throwable")
                mv.visitLabel(tryStart)
                generateBlock(mv, stmt.body, ctx, owner)
                mv.visitLabel(tryEnd)
                mv.visitJumpInsn(Opcodes.GOTO, endLabel)
                mv.visitLabel(catchStart)
                for (catchClause in stmt.catches) {
                    val catchDesc = typeMapper.jvmDescriptorFromType(catchClause.parameter.type)
                    val catchSlot = ctx.nextSlot
                    ctx.nextSlot += 1
                    storeLocal(mv, "Ljava/lang/Throwable;", catchSlot)
                    val catchInternalName = typeMapper.jvmInternalNameFromType(catchClause.parameter.type)
                    if (catchInternalName != "java/lang/Throwable") {
                        loadLocal(mv, "Ljava/lang/Throwable;", catchSlot)
                        mv.visitTypeInsn(Opcodes.CHECKCAST, catchInternalName)
                        storeLocal(mv, catchDesc, catchSlot)
                    }
                    ctx.variables[catchClause.parameter.name] = catchSlot
                    generateBlock(mv, catchClause.body, ctx, owner)
                }
                if (stmt.finallyBody != null) {
                    generateBlock(mv, stmt.finallyBody, ctx, owner)
                }
                mv.visitLabel(endLabel)
            }
            is JasThrow -> {
                generateExpression(mv, stmt.expression, ctx, owner)
                mv.visitInsn(Opcodes.ATHROW)
            }
            is JasLabeledStatement -> generateStatement(mv, stmt.statement, ctx, owner)
            is JasBreakStatement -> {
                ctx.loopStack.lastOrNull()?.second?.let { mv.visitJumpInsn(Opcodes.GOTO, it) }
            }
            is JasContinueStatement -> {
                ctx.loopStack.lastOrNull()?.first?.let { mv.visitJumpInsn(Opcodes.GOTO, it) }
            }
            is JasDefer -> {
                ctx.deferredBodies.add(stmt.body)
            }
            is JasAssert -> {
                generateExpression(mv, stmt.condition, ctx, owner)
                val assertOk = Label()
                mv.visitJumpInsn(Opcodes.IFNE, assertOk)
                mv.visitTypeInsn(Opcodes.NEW, "java/lang/AssertionError")
                mv.visitInsn(Opcodes.DUP)
                if (stmt.message != null) {
                    generateExpression(mv, stmt.message, ctx, owner)
                    mv.visitMethodInsn(Opcodes.INVOKESPECIAL, "java/lang/AssertionError", "<init>", "(Ljava/lang/Object;)V", false)
                } else {
                    mv.visitMethodInsn(Opcodes.INVOKESPECIAL, "java/lang/AssertionError", "<init>", "()V", false)
                }
                mv.visitInsn(Opcodes.ATHROW)
                mv.visitLabel(assertOk)
            }
            is JasLock -> {
                generateExpression(mv, stmt.expression, ctx, owner)
                mv.visitInsn(Opcodes.DUP)
                val lockSlot = ctx.nextSlot
                ctx.nextSlot += 1
                storeLocal(mv, "Ljava/lang/Object;", lockSlot)
                mv.visitInsn(Opcodes.MONITORENTER)
                val tryStart = Label()
                val tryEnd = Label()
                val catchLabel = Label()
                val endLabel = Label()
                ctx.lockSlots.add(lockSlot)
                mv.visitTryCatchBlock(tryStart, tryEnd, catchLabel, "java/lang/Throwable")
                mv.visitLabel(tryStart)
                generateBlock(mv, stmt.body, ctx, owner)
                mv.visitLabel(tryEnd)
                ctx.lockSlots.removeLast()
                loadLocal(mv, "Ljava/lang/Object;", lockSlot)
                mv.visitInsn(Opcodes.MONITOREXIT)
                mv.visitJumpInsn(Opcodes.GOTO, endLabel)
                mv.visitLabel(catchLabel)
                val exSlot = ctx.nextSlot
                ctx.nextSlot += 1
                storeLocal(mv, "Ljava/lang/Throwable;", exSlot)
                loadLocal(mv, "Ljava/lang/Object;", lockSlot)
                mv.visitInsn(Opcodes.MONITOREXIT)
                loadLocal(mv, "Ljava/lang/Throwable;", exSlot)
                mv.visitInsn(Opcodes.ATHROW)
                mv.visitLabel(endLabel)
            }
            is JasYield -> {
                generateExpression(mv, stmt.expression, ctx, owner)
                val yieldSlot = ctx.nextSlot
                ctx.nextSlot += 1
                val desc = inferExprDesc(stmt.expression, ctx)
                storeLocal(mv, desc, yieldSlot)
                emitDeferredActions(mv, ctx, owner)
                loadLocal(mv, desc, yieldSlot)
                generateReturn(mv, ctx.returnType)
            }
            is JasDestructuringDeclaration -> {
                generateExpression(mv, stmt.initializer, ctx, owner)
                val arrSlot = ctx.nextSlot
                ctx.nextSlot += 1
                storeLocal(mv, "Ljava/lang/Object;", arrSlot)
                for ((i, binding) in stmt.bindings.withIndex()) {
                    loadLocal(mv, "Ljava/lang/Object;", arrSlot)
                    pushInt(mv, i)
                    val elemDesc = typeMapper.jvmDescriptorFromType(binding.type)
                    mv.visitInsn(arrayLoadOpcode(elemDesc))
                    val varSlot = ctx.nextSlot
                    ctx.nextSlot += if (elemDesc == "J" || elemDesc == "D") 2 else 1
                    ctx.variables[binding.name] = varSlot
                    ctx.locals.add(LocalInfo(binding.name, binding.type, varSlot))
                    storeLocal(mv, elemDesc, varSlot)
                }
            }
            is JasMatch -> {
                val subjectSlot = ctx.nextSlot
                ctx.nextSlot += 1
                generateExpression(mv, stmt.expression, ctx, owner)
                val subjectDesc = inferExprDesc(stmt.expression, ctx)
                if (subjectDesc in listOf("I", "J", "F", "D", "Z", "B", "S", "C")) {
                    boxTopOfStack(mv, subjectDesc)
                }
                storeLocal(mv, "Ljava/lang/Object;", subjectSlot)
                val endLabel = Label()
                val nextCaseLabels = mutableListOf<Label>()
                for (i in stmt.cases.indices) {
                    nextCaseLabels.add(if (i < stmt.cases.size - 1) Label() else endLabel)
                }
                for ((i, mc) in stmt.cases.withIndex()) {
                    val nextLabel = nextCaseLabels[i]
                    val p = mc.pattern
                    var patternMatchLabel: Label? = null
                    when (p) {
                        is JasWildcardPattern -> {}
                        is JasIntLiteral -> {
                            loadLocal(mv, "Ljava/lang/Object;", subjectSlot)
                            mv.visitTypeInsn(Opcodes.CHECKCAST, "java/lang/Number")
                            mv.visitMethodInsn(Opcodes.INVOKEVIRTUAL, "java/lang/Number", "intValue", "()I", false)
                            pushInt(mv, p.value.toInt())
                            mv.visitJumpInsn(Opcodes.IF_ICMPNE, nextLabel)
                        }
                        is JasStringLiteral -> {
                            loadLocal(mv, "Ljava/lang/Object;", subjectSlot)
                            mv.visitLdcInsn(p.text)
                            mv.visitMethodInsn(Opcodes.INVOKEVIRTUAL, "java/lang/Object", "equals", "(Ljava/lang/Object;)Z", false)
                            mv.visitJumpInsn(Opcodes.IFEQ, nextLabel)
                        }
                        is JasBoolLiteral -> {
                            loadLocal(mv, "Ljava/lang/Object;", subjectSlot)
                            mv.visitTypeInsn(Opcodes.CHECKCAST, "java/lang/Boolean")
                            mv.visitMethodInsn(Opcodes.INVOKEVIRTUAL, "java/lang/Boolean", "booleanValue", "()Z", false)
                            pushInt(mv, if (p.value) 1 else 0)
                            mv.visitJumpInsn(Opcodes.IF_ICMPNE, nextLabel)
                        }
                        is JasIdentifier -> {
                            val varSlot = ctx.nextSlot
                            ctx.nextSlot += 1
                            ctx.variables[p.name] = varSlot
                            ctx.locals.add(LocalInfo(p.name, null, varSlot))
                            loadLocal(mv, "Ljava/lang/Object;", subjectSlot)
                            storeLocal(mv, "Ljava/lang/Object;", varSlot)
                        }
                        is JasBinaryOp -> {
                            if (p.op == "or") {
                                patternMatchLabel = Label()
                                checkOrPatternBranch(mv, p.left, subjectSlot, patternMatchLabel!!, nextLabel, ctx, owner)
                                checkOrPatternBranch(mv, p.right, subjectSlot, patternMatchLabel!!, nextLabel, ctx, owner)
                                mv.visitJumpInsn(Opcodes.GOTO, nextLabel)
                                mv.visitLabel(patternMatchLabel!!)
                            } else {
                                mv.visitJumpInsn(Opcodes.GOTO, nextLabel)
                            }
                        }
                        null -> {
                            mv.visitJumpInsn(Opcodes.GOTO, nextLabel)
                        }
                        else -> {
                            mv.visitJumpInsn(Opcodes.GOTO, nextLabel)
                        }
                    }
                    if (mc.guard != null) {
                        generateExpression(mv, mc.guard, ctx, owner)
                        mv.visitJumpInsn(Opcodes.IFEQ, nextLabel)
                    }
                    generateBlock(mv, mc.body, ctx, owner)
                    mv.visitJumpInsn(Opcodes.GOTO, endLabel)
                    if (nextLabel !== endLabel) {
                        mv.visitLabel(nextLabel)
                    }
                }
                mv.visitLabel(endLabel)
            }
            else -> {}
        }
    }

    private fun generateExpression(mv: MethodVisitor, expr: JasExpression, ctx: CodegenContext, owner: String) {
        when (expr) {
            is JasIntLiteral -> {
                val v = expr.value
                if (v >= Int.MIN_VALUE && v <= Int.MAX_VALUE) {
                    pushInt(mv, v.toInt())
                } else {
                    mv.visitLdcInsn(v)
                }
            }
            is JasFloatLiteral -> mv.visitLdcInsn(expr.value)
            is JasStringLiteral -> mv.visitLdcInsn(expr.text)
            is JasBoolLiteral -> mv.visitInsn(if (expr.value) Opcodes.ICONST_1 else Opcodes.ICONST_0)
            is JasNullLiteral -> mv.visitInsn(Opcodes.ACONST_NULL)
            is JasIdentifier -> {
                if (expr.name == "this" || expr.name == "super") {
                    mv.visitVarInsn(Opcodes.ALOAD, 0)
                } else {
                    val slot = ctx.variables[expr.name]
                    if (slot != null) {
                        val local = ctx.locals.find { it.name == expr.name }
                        val desc = typeMapper.jvmDescriptorFromType(local?.type)
                        loadLocal(mv, desc, slot)
                    } else {
                        mv.visitInsn(Opcodes.ACONST_NULL)
                    }
                }
            }
            is JasBinaryOp -> {
                when (expr.op) {
                    "&&" -> {
                        generateExpression(mv, expr.left, ctx, owner)
                        val falseLabel = Label()
                        val endLabel = Label()
                        mv.visitJumpInsn(Opcodes.IFEQ, falseLabel)
                        generateExpression(mv, expr.right, ctx, owner)
                        mv.visitJumpInsn(Opcodes.IFEQ, falseLabel)
                        mv.visitInsn(Opcodes.ICONST_1)
                        mv.visitJumpInsn(Opcodes.GOTO, endLabel)
                        mv.visitLabel(falseLabel)
                        mv.visitFrame(Opcodes.F_SAME, 0, null, 0, null)
                        mv.visitInsn(Opcodes.ICONST_0)
                        mv.visitLabel(endLabel)
                        mv.visitFrame(Opcodes.F_SAME1, 0, null, 1, arrayOf(Opcodes.INTEGER))
                    }
                    "||" -> {
                        generateExpression(mv, expr.left, ctx, owner)
                        val trueLabel = Label()
                        val endLabel = Label()
                        mv.visitJumpInsn(Opcodes.IFNE, trueLabel)
                        generateExpression(mv, expr.right, ctx, owner)
                        mv.visitJumpInsn(Opcodes.IFNE, trueLabel)
                        mv.visitInsn(Opcodes.ICONST_0)
                        mv.visitJumpInsn(Opcodes.GOTO, endLabel)
                        mv.visitLabel(trueLabel)
                        mv.visitFrame(Opcodes.F_SAME, 0, null, 0, null)
                        mv.visitInsn(Opcodes.ICONST_1)
                        mv.visitLabel(endLabel)
                        mv.visitFrame(Opcodes.F_SAME1, 0, null, 1, arrayOf(Opcodes.INTEGER))
                    }
                    else -> {
                        generateExpression(mv, expr.left, ctx, owner)
                        generateExpression(mv, expr.right, ctx, owner)
                        val desc = inferExprDesc(expr.left, ctx)
                        emitBinaryArithmeticOp(mv, expr.op, desc)
                    }
                }
            }
            is JasUnaryOp -> {
                when (expr.op) {
                    "await", "go" -> generateExpression(mv, expr.operand, ctx, owner)
                    else -> {
                        generateExpression(mv, expr.operand, ctx, owner)
                        when (expr.op) {
                            "-" -> mv.visitInsn(Opcodes.INEG)
                            "!" -> {
                                val trueLabel = Label()
                                val endLabel = Label()
                                mv.visitJumpInsn(Opcodes.IFEQ, trueLabel)
                                mv.visitInsn(Opcodes.ICONST_0)
                                mv.visitJumpInsn(Opcodes.GOTO, endLabel)
                                mv.visitLabel(trueLabel)
                                mv.visitInsn(Opcodes.ICONST_1)
                                mv.visitLabel(endLabel)
                            }
                            "~" -> {
                                mv.visitInsn(Opcodes.ICONST_M1)
                                mv.visitInsn(Opcodes.IXOR)
                            }
                        }
                    }
                }
            }
            is JasCall -> {
                val name = callMethodName(expr)
                if (name == "println" && expr.args.size == 1) {
                    mv.visitFieldInsn(Opcodes.GETSTATIC, "java/lang/System", "out", "Ljava/io/PrintStream;")
                    generateExpression(mv, expr.args[0], ctx, owner)
                    mv.visitMethodInsn(Opcodes.INVOKEVIRTUAL, "java/io/PrintStream", "println", "(Ljava/lang/Object;)V", false)
                    mv.visitInsn(Opcodes.ACONST_NULL)
                } else if (name == "__jasper_pow__" && expr.args.size == 2) {
                    generateExpression(mv, expr.args[0], ctx, owner)
                    mv.visitInsn(Opcodes.I2D)
                    generateExpression(mv, expr.args[1], ctx, owner)
                    mv.visitInsn(Opcodes.I2D)
                    mv.visitMethodInsn(
                        Opcodes.INVOKESTATIC,
                        "java/lang/Math",
                        "pow",
                        "(DD)D",
                        false
                    )
                    mv.visitInsn(Opcodes.D2I)
                } else if (expr.target is JasIdentifier && ctx.variables.containsKey(name)) {
                    val slot = ctx.variables[name]!!
                    loadLocal(mv, "Ljava/lang/Object;", slot)
                    for (arg in expr.args) {
                        generateExpression(mv, arg, ctx, owner)
                        val argDesc = inferExprDesc(arg, ctx)
                        if (argDesc in listOf("I", "J", "F", "D", "Z", "B", "S", "C")) {
                            boxTopOfStack(mv, argDesc)
                        }
                    }
                    val numArgs = expr.args.size
                    val iface: String
                    val samName: String
                    val samDesc: String
                    when (numArgs) {
                        0 -> { iface = "java/util/function/Supplier"; samName = "get"; samDesc = "()Ljava/lang/Object;" }
                        1 -> { iface = "java/util/function/Function"; samName = "apply"; samDesc = "(Ljava/lang/Object;)Ljava/lang/Object;" }
                        2 -> { iface = "java/util/function/BiFunction"; samName = "apply"; samDesc = "(Ljava/lang/Object;Ljava/lang/Object;)Ljava/lang/Object;" }
                        else -> { iface = "java/util/function/Function"; samName = "apply"; samDesc = "(Ljava/lang/Object;)Ljava/lang/Object;" }
                    }
                    mv.visitMethodInsn(Opcodes.INVOKEINTERFACE, iface, samName, samDesc, true)
                } else {
                    val isVirtual = expr.target is JasPropertyAccess
                    if (isVirtual) {
                        generateExpression(mv, (expr.target as JasPropertyAccess).target, ctx, owner)
                    }
                    for (arg in expr.args) {
                        generateExpression(mv, arg, ctx, owner)
                    }
                    if (isVirtual) {
                        val receiverType = resolveReceiverType(expr, ctx)
                        var ownerName = when (receiverType) {
                            is JasNamedType -> receiverType.name.replace('.', '/')
                            else -> "java/lang/Object"
                        }
                        if (!ownerName.contains("/") && currentPkg.isNotEmpty()) {
                            val qualifiedName = "$currentPkg/$ownerName"
                            if (methodRegistry.containsKey(qualifiedName)) {
                                ownerName = qualifiedName
                            }
                        }
                        val desc = resolveVirtualMethodDescriptor(expr, ownerName) ?: callDescriptor(expr)
                        mv.visitMethodInsn(
                            Opcodes.INVOKEVIRTUAL,
                            ownerName,
                            name,
                            desc,
                            false
                        )
                    } else {
                        val targetOwner = if (functionRegistry.containsKey(name)) {
                            "$currentPkg/$name"
                        } else {
                            owner
                        }
                        mv.visitMethodInsn(
                            Opcodes.INVOKESTATIC,
                            targetOwner,
                            name,
                            callDescriptor(expr),
                            false
                        )
                    }
                }
            }
            is JasAssignment -> {
                if (expr.target is JasIdentifier) {
                    val slot = ctx.variables[expr.target.name]
                    if (slot != null) {
                        val local = ctx.locals.find { it.name == expr.target.name }
                        val desc = typeMapper.jvmDescriptorFromType(local?.type)
                        if (expr.op != "=") {
                            // Compound assignment: x += 1  →  x = x + 1
                            loadLocal(mv, desc, slot)
                            generateExpression(mv, expr.value, ctx, owner)
                            val op = expr.op.removeSuffix("=")
                            when (op) {
                                "+" -> mv.visitInsn(Opcodes.IADD)
                                "-" -> mv.visitInsn(Opcodes.ISUB)
                                "*" -> mv.visitInsn(Opcodes.IMUL)
                                "/" -> mv.visitInsn(Opcodes.IDIV)
                                "%" -> mv.visitInsn(Opcodes.IREM)
                                "&" -> mv.visitInsn(Opcodes.IAND)
                                "|" -> mv.visitInsn(Opcodes.IOR)
                                "^" -> mv.visitInsn(Opcodes.IXOR)
                                "<<" -> mv.visitInsn(Opcodes.ISHL)
                                ">>" -> mv.visitInsn(Opcodes.ISHR)
                                ">>>" -> mv.visitInsn(Opcodes.IUSHR)
                            }
                            storeLocal(mv, desc, slot)
                            loadLocal(mv, desc, slot)
                        } else {
                            generateExpression(mv, expr.value, ctx, owner)
                            storeLocal(mv, desc, slot)
                            loadLocal(mv, desc, slot)
                        }
                    }
                } else if (expr.target is JasArrayAccess) {
                    val aa = expr.target as JasArrayAccess
                    generateExpression(mv, aa.target, ctx, owner)
                    generateExpression(mv, aa.index, ctx, owner)
                    val elemDesc = tryResolveArrayElementDesc(aa.target, ctx)
                    if (expr.op != "=") {
                        generateExpression(mv, aa.target, ctx, owner)
                        generateExpression(mv, aa.index, ctx, owner)
                        mv.visitInsn(arrayLoadOpcode(elemDesc))
                        generateExpression(mv, expr.value, ctx, owner)
                        val op = expr.op.removeSuffix("=")
                        emitBinaryArithmeticOp(mv, op, elemDesc)
                        if (elemDesc in listOf("J", "D")) {
                            mv.visitInsn(Opcodes.DUP2_X2)
                        } else {
                            mv.visitInsn(Opcodes.DUP_X2)
                        }
                        mv.visitInsn(arrayStoreOpcode(elemDesc))
                    } else {
                         generateExpression(mv, expr.value, ctx, owner)
                         mv.visitInsn(Opcodes.DUP_X2)
                         mv.visitInsn(arrayStoreOpcode(elemDesc))
                     }
                } else if (expr.target is JasPropertyAccess) {
                    val pa = expr.target as JasPropertyAccess
                    generateExpression(mv, pa.target, ctx, owner)
                    val propDesc = fieldDescriptorMap[pa.property] ?: "Ljava/lang/Object;"
                    if (expr.op != "=") {
                        mv.visitInsn(Opcodes.DUP)
                        mv.visitFieldInsn(Opcodes.GETFIELD, owner, pa.property, propDesc)
                        generateExpression(mv, expr.value, ctx, owner)
                        val op = expr.op.removeSuffix("=")
                        emitBinaryArithmeticOp(mv, op, propDesc)
                        mv.visitFieldInsn(Opcodes.PUTFIELD, owner, pa.property, propDesc)
                    } else {
                        generateExpression(mv, expr.value, ctx, owner)
                        mv.visitInsn(Opcodes.DUP_X1)
                        mv.visitFieldInsn(Opcodes.PUTFIELD, owner, pa.property, propDesc)
                    }
                } else {
                    generateExpression(mv, expr.value, ctx, owner)
                }
            }
            is JasTernaryExpr -> {
                generateExpression(mv, expr.condition, ctx, owner)
                val falseLabel = Label()
                val endLabel = Label()
                mv.visitJumpInsn(Opcodes.IFEQ, falseLabel)
                generateExpression(mv, expr.thenExpr, ctx, owner)
                mv.visitJumpInsn(Opcodes.GOTO, endLabel)
                mv.visitLabel(falseLabel)
                generateExpression(mv, expr.elseExpr, ctx, owner)
                mv.visitLabel(endLabel)
            }
            is JasPropertyAccess -> {
                if (expr.isSafe) {
                    generateExpression(mv, expr.target, ctx, owner)
                    val nullLabel = Label()
                    val endLabel = Label()
                    mv.visitInsn(Opcodes.DUP)
                    mv.visitJumpInsn(Opcodes.IFNULL, nullLabel)
                    mv.visitFieldInsn(
                        Opcodes.GETFIELD,
                        owner,
                        expr.property,
                        "Ljava/lang/Object;"
                    )
                    mv.visitJumpInsn(Opcodes.GOTO, endLabel)
                    mv.visitLabel(nullLabel)
                    mv.visitInsn(Opcodes.POP)
                    mv.visitInsn(Opcodes.ACONST_NULL)
                    mv.visitLabel(endLabel)
                } else {
                    generateExpression(mv, expr.target, ctx, owner)
                    val propDesc = resolvePropertyDescriptor(expr)
                    mv.visitFieldInsn(
                        Opcodes.GETFIELD,
                        owner,
                        expr.property,
                        propDesc
                    )
                }
            }
            is JasNew -> {
                var typeName = typeMapper.jvmInternalNameFromType(expr.type)
                if (!typeName.contains("/") && currentPkg.isNotEmpty()) {
                    val qualifiedName = "$currentPkg/$typeName"
                    if (methodRegistry.containsKey(qualifiedName)) {
                        typeName = qualifiedName
                    }
                }
                val desc = newDescriptor(expr, ctx)
                mv.visitTypeInsn(Opcodes.NEW, typeName)
                mv.visitInsn(Opcodes.DUP)
                for (arg in expr.args) {
                    generateExpression(mv, arg, ctx, owner)
                }
                mv.visitMethodInsn(
                    Opcodes.INVOKESPECIAL,
                    typeName,
                    "<init>",
                    desc,
                    false
                )
            }
            is JasArrayAccess -> {
                generateExpression(mv, expr.target, ctx, owner)
                generateExpression(mv, expr.index, ctx, owner)
                val elemDesc = tryResolveArrayElementDesc(expr.target, ctx)
                mv.visitInsn(arrayLoadOpcode(elemDesc))
            }
            is JasCastExpr -> {
                generateExpression(mv, expr.expression, ctx, owner)
                val castDesc = typeMapper.jvmDescriptorFromType(expr.type)
                if (castDesc in listOf("I", "J", "F", "D", "Z", "B", "S", "C")) {
                    val wrapper = when (castDesc) {
                        "I" -> "java/lang/Integer"; "J" -> "java/lang/Long"
                        "F" -> "java/lang/Float"; "D" -> "java/lang/Double"
                        "Z" -> "java/lang/Boolean"; "B" -> "java/lang/Byte"
                        "S" -> "java/lang/Short"; "C" -> "java/lang/Character"
                        else -> "java/lang/Integer"
                    }
                    val unboxMethod = when (castDesc) {
                        "I" -> "intValue"; "J" -> "longValue"
                        "F" -> "floatValue"; "D" -> "doubleValue"
                        "Z" -> "booleanValue"; "B" -> "byteValue"
                        "S" -> "shortValue"; "C" -> "charValue"
                        else -> "intValue"
                    }
                    mv.visitTypeInsn(Opcodes.CHECKCAST, wrapper)
                    mv.visitMethodInsn(Opcodes.INVOKEVIRTUAL, wrapper, unboxMethod, "()$castDesc", false)
                } else {
                    val internalName = typeMapper.jvmInternalNameFromType(expr.type)
                    mv.visitTypeInsn(Opcodes.CHECKCAST, internalName)
                }
            }
            is JasNullCoalescing -> {
                generateExpression(mv, expr.left, ctx, owner)
                val nullLabel = Label()
                val endLabel = Label()
                mv.visitInsn(Opcodes.DUP)
                mv.visitJumpInsn(Opcodes.IFNULL, nullLabel)
                mv.visitJumpInsn(Opcodes.GOTO, endLabel)
                mv.visitLabel(nullLabel)
                mv.visitInsn(Opcodes.POP)
                generateExpression(mv, expr.right, ctx, owner)
                mv.visitLabel(endLabel)
            }
            is JasInstanceOfExpr -> {
                generateExpression(mv, expr.expression, ctx, owner)
                val internalName = typeMapper.jvmInternalNameFromType(expr.type)
                mv.visitTypeInsn(Opcodes.INSTANCEOF, internalName)
            }
            is JasLambdaExpr -> generateLambdaExpression(mv, expr, ctx, owner)
            is JasMethodReference -> generateMethodReference(mv, expr, ctx, owner)
            is JasArrayCreation -> {
                if (expr.init is JasArrayInitValues) {
                    val values = expr.init.values
                    pushInt(mv, values.size)
                    val elemName = typeMapper.jvmInternalNameFromType(expr.type)
                    val isPrim = elemName in listOf("int", "long", "float", "double", "boolean", "byte", "short", "char")
                    val elemDesc = typeMapper.jvmDescriptorFromType(expr.type)
                    val arrayDesc = if (isPrim) "[${elemDesc}" else "[Ljava/lang/Object;"
                    if (isPrim) {
                        val primType = when (elemName) {
                            "int" -> Opcodes.T_INT; "long" -> Opcodes.T_LONG; "float" -> Opcodes.T_FLOAT
                            "double" -> Opcodes.T_DOUBLE; "boolean" -> Opcodes.T_BOOLEAN
                            "byte" -> Opcodes.T_BYTE; "short" -> Opcodes.T_SHORT; "char" -> Opcodes.T_CHAR
                            else -> Opcodes.T_INT
                        }
                        mv.visitIntInsn(Opcodes.NEWARRAY, primType)
                    } else {
                        mv.visitTypeInsn(Opcodes.ANEWARRAY, typeMapper.jvmInternalNameFromType(expr.type))
                    }
                    for ((i, v) in values.withIndex()) {
                        mv.visitInsn(Opcodes.DUP)
                        pushInt(mv, i)
                        generateExpression(mv, v, ctx, owner)
                        mv.visitInsn(arrayStoreOpcode(elemDesc))
                    }
                } else {
                    for (dim in expr.dims) {
                        generateExpression(mv, dim, ctx, owner)
                    }
                    val elemName = typeMapper.jvmInternalNameFromType(expr.type)
                    if (expr.dims.size == 1) {
                        if (elemName in listOf("int", "long", "float", "double", "boolean", "byte", "short", "char")) {
                            val primType = when (elemName) {
                                "int" -> Opcodes.T_INT; "long" -> Opcodes.T_LONG; "float" -> Opcodes.T_FLOAT
                                "double" -> Opcodes.T_DOUBLE; "boolean" -> Opcodes.T_BOOLEAN
                                "byte" -> Opcodes.T_BYTE; "short" -> Opcodes.T_SHORT; "char" -> Opcodes.T_CHAR
                                else -> Opcodes.T_INT
                            }
                            mv.visitIntInsn(Opcodes.NEWARRAY, primType)
                        } else {
                            mv.visitTypeInsn(Opcodes.ANEWARRAY, elemName)
                        }
                    } else {
                        val arrayType = JasArrayType(expr.type)
                        val desc = typeMapper.jvmDescriptorFromType(arrayType)
                        mv.visitMultiANewArrayInsn(desc, expr.dims.size)
                    }
                }
            }
            is JasStringTemplate -> generateStringTemplate(mv, expr, ctx, owner)
            is JasDictLiteral -> {
                mv.visitTypeInsn(Opcodes.NEW, "java/util/HashMap")
                mv.visitInsn(Opcodes.DUP)
                mv.visitMethodInsn(Opcodes.INVOKESPECIAL, "java/util/HashMap", "<init>", "()V", false)
                for (entry in expr.entries) {
                    mv.visitInsn(Opcodes.DUP)
                    generateExpression(mv, entry.key, ctx, owner)
                    boxTopOfStack(mv, inferExprDesc(entry.key, ctx))
                    generateExpression(mv, entry.value, ctx, owner)
                    boxTopOfStack(mv, inferExprDesc(entry.value, ctx))
                    mv.visitMethodInsn(
                        Opcodes.INVOKEVIRTUAL,
                        "java/util/HashMap",
                        "put",
                        "(Ljava/lang/Object;Ljava/lang/Object;)Ljava/lang/Object;",
                        false
                    )
                    mv.visitInsn(Opcodes.POP)
                }
            }
        }
    }

    private fun generateStringTemplate(mv: MethodVisitor, expr: JasStringTemplate, ctx: CodegenContext, owner: String) {
        mv.visitTypeInsn(Opcodes.NEW, "java/lang/StringBuilder")
        mv.visitInsn(Opcodes.DUP)
        mv.visitMethodInsn(Opcodes.INVOKESPECIAL, "java/lang/StringBuilder", "<init>", "()V", false)
        for (part in expr.parts) {
            when (part) {
                is JasTemplateLiteral -> {
                    mv.visitLdcInsn(part.text)
                    mv.visitMethodInsn(Opcodes.INVOKEVIRTUAL, "java/lang/StringBuilder", "append", "(Ljava/lang/String;)Ljava/lang/StringBuilder;", false)
                }
                is JasTemplateExpr -> {
                    generateExpression(mv, part.expr, ctx, owner)
                    val desc = inferExprDesc(part.expr, ctx)
                    when (desc) {
                        "I", "Z", "B", "S", "C" -> {
                            mv.visitMethodInsn(Opcodes.INVOKESTATIC, "java/lang/String", "valueOf", "($desc)Ljava/lang/String;", false)
                            mv.visitMethodInsn(Opcodes.INVOKEVIRTUAL, "java/lang/StringBuilder", "append", "(Ljava/lang/String;)Ljava/lang/StringBuilder;", false)
                        }
                        "J" -> {
                            mv.visitMethodInsn(Opcodes.INVOKESTATIC, "java/lang/String", "valueOf", "(J)Ljava/lang/String;", false)
                            mv.visitMethodInsn(Opcodes.INVOKEVIRTUAL, "java/lang/StringBuilder", "append", "(Ljava/lang/String;)Ljava/lang/StringBuilder;", false)
                        }
                        "F" -> {
                            mv.visitMethodInsn(Opcodes.INVOKESTATIC, "java/lang/String", "valueOf", "(F)Ljava/lang/String;", false)
                            mv.visitMethodInsn(Opcodes.INVOKEVIRTUAL, "java/lang/StringBuilder", "append", "(Ljava/lang/String;)Ljava/lang/StringBuilder;", false)
                        }
                        "D" -> {
                            mv.visitMethodInsn(Opcodes.INVOKESTATIC, "java/lang/String", "valueOf", "(D)Ljava/lang/String;", false)
                            mv.visitMethodInsn(Opcodes.INVOKEVIRTUAL, "java/lang/StringBuilder", "append", "(Ljava/lang/String;)Ljava/lang/StringBuilder;", false)
                        }
                        else -> {
                            mv.visitMethodInsn(Opcodes.INVOKEVIRTUAL, "java/lang/StringBuilder", "append", "(Ljava/lang/Object;)Ljava/lang/StringBuilder;", false)
                        }
                    }
                }
            }
        }
        mv.visitMethodInsn(Opcodes.INVOKEVIRTUAL, "java/lang/StringBuilder", "toString", "()Ljava/lang/String;", false)
    }

    private fun generateLambdaExpression(mv: MethodVisitor, expr: JasLambdaExpr, ctx: CodegenContext, owner: String) {
        val lambdaIndex = lambdaCounter++
        val lambdaName = "lambda\$$lambdaIndex"

        val paramNames = expr.parameters.map { it.name }.toSet()
        val captured = findCapturedIdentifiers(expr.body, paramNames, ctx)

        val capturedTypes = captured.map { name ->
            ctx.locals.find { it.name == name }?.type ?: JasNamedType("any")
        }

        val syntheticParams = mutableListOf<JasParameter>()
        for ((i, name) in captured.withIndex()) {
            syntheticParams.add(JasParameter(name, capturedTypes[i]))
        }
        syntheticParams.addAll(expr.parameters)

        val returnType = JasNamedType("any")
        val pending = PendingLambda(lambdaName, syntheticParams, expr.body, returnType, captured)
        pendingLambdaMethods.add(pending)

        val captureDescs = captured.joinToString("") {
            typeMapper.jvmDescriptorFromType(capturedTypes[captured.indexOf(it)])
        }
        val paramDescs = expr.parameters.joinToString("") {
            typeMapper.jvmDescriptorFromType(it.type)
        }
        val returnDesc = "Ljava/lang/Object;"
        val implDesc = "($captureDescs$paramDescs)$returnDesc"

        val numParams = expr.parameters.size
        val (funcInterface, samName, samErasedDesc) = when (numParams) {
            0 -> Triple("java/util/function/Supplier", "get", "()Ljava/lang/Object;")
            1 -> Triple("java/util/function/Function", "apply", "(Ljava/lang/Object;)Ljava/lang/Object;")
            2 -> Triple("java/util/function/BiFunction", "apply", "(Ljava/lang/Object;Ljava/lang/Object;)Ljava/lang/Object;")
            else -> Triple("java/util/function/Function", "apply", "(Ljava/lang/Object;)Ljava/lang/Object;")
        }

        val boxedParamDescs = expr.parameters.joinToString("") {
            boxedDescriptor(typeMapper.jvmDescriptorFromType(it.type))
        }
        val instantiatedDesc = "($boxedParamDescs)$returnDesc"
        val invokedType = "($captureDescs)L$funcInterface;"

        val bsmHandle = Handle(
            Opcodes.H_INVOKESTATIC,
            "java/lang/invoke/LambdaMetafactory",
            "metafactory",
            "(Ljava/lang/invoke/MethodHandles\$Lookup;Ljava/lang/String;Ljava/lang/invoke/MethodType;Ljava/lang/invoke/MethodType;Ljava/lang/invoke/MethodHandle;Ljava/lang/invoke/MethodType;)Ljava/lang/invoke/CallSite;",
            false
        )

        val implHandle = Handle(
            Opcodes.H_INVOKESTATIC,
            currentInternalName ?: owner,
            lambdaName,
            implDesc,
            false
        )

        val samMethodType = Type.getMethodType(samErasedDesc)
        val instantiatedMethodType = Type.getMethodType(instantiatedDesc)

        mv.visitInvokeDynamicInsn(
            samName,
            invokedType,
            bsmHandle,
            samMethodType,
            implHandle,
            instantiatedMethodType
        )
    }

    private fun boxedDescriptor(rawDesc: String): String = when (rawDesc) {
        "I" -> "Ljava/lang/Integer;"
        "J" -> "Ljava/lang/Long;"
        "F" -> "Ljava/lang/Float;"
        "D" -> "Ljava/lang/Double;"
        "Z" -> "Ljava/lang/Boolean;"
        "B" -> "Ljava/lang/Byte;"
        "S" -> "Ljava/lang/Short;"
        "C" -> "Ljava/lang/Character;"
        else -> rawDesc
    }

    private fun generateMethodReference(mv: MethodVisitor, expr: JasMethodReference, ctx: CodegenContext, owner: String) {
        val targetExpr = expr.target
        val methodName = expr.method

        val handleTag: Int
        val refOwner: String

        when {
            targetExpr is JasIdentifier && targetExpr.name.isNotEmpty() &&
                targetExpr.name.first().isUpperCase() -> {
                handleTag = Opcodes.H_INVOKESTATIC
                refOwner = typeMapper.jvmInternalNameFromType(JasNamedType(targetExpr.name))
            }
            targetExpr is JasIdentifier && targetExpr.name.isNotEmpty() -> {
                handleTag = Opcodes.H_INVOKEVIRTUAL
                refOwner = "java/lang/Object"
            }
            else -> {
                handleTag = Opcodes.H_INVOKESTATIC
                refOwner = currentInternalName ?: owner
            }
        }

        val returnDesc = "Ljava/lang/Object;"
        val paramDescs = when (targetExpr) {
            is JasIdentifier -> {
                val func = functionRegistry[targetExpr.name]
                if (func != null) {
                    func.parameters.joinToString("") { typeMapper.jvmDescriptorFromType(it.type) }
                } else ""
            }
            else -> ""
        }
        val numParams = if (paramDescs.isNotEmpty()) {
            paramDescs.count { it == ';' || it == 'I' || it == 'J' || it == 'F' || it == 'D' || it == 'Z' || it == 'B' || it == 'S' || it == 'C' }
        } else 0
        val (funcInterface, samName, samDesc) = when (numParams) {
            0 -> Triple("java/util/function/Supplier", "get", "()$returnDesc")
            1 -> Triple("java/util/function/Function", "apply", "(Ljava/lang/Object;)$returnDesc")
            2 -> Triple("java/util/function/BiFunction", "apply", "(Ljava/lang/Object;Ljava/lang/Object;)$returnDesc")
            else -> Triple("java/util/function/Function", "apply", "(Ljava/lang/Object;)$returnDesc")
        }
        val invokedType = "()L$funcInterface;"

        val bsmHandle = Handle(
            Opcodes.H_INVOKESTATIC,
            "java/lang/invoke/LambdaMetafactory",
            "metafactory",
            "(Ljava/lang/invoke/MethodHandles\$Lookup;Ljava/lang/String;Ljava/lang/invoke/MethodType;Ljava/lang/invoke/MethodType;Ljava/lang/invoke/MethodHandle;Ljava/lang/invoke/MethodType;)Ljava/lang/invoke/CallSite;",
            false
        )

        val implHandle = Handle(
            handleTag,
            refOwner,
            methodName,
            samDesc,
            handleTag == Opcodes.H_INVOKEINTERFACE
        )

        val samMethodType = Type.getMethodType(samDesc)
        val instantiatedMethodType = Type.getMethodType(samDesc)

        mv.visitInvokeDynamicInsn(
            "get",
            invokedType,
            bsmHandle,
            samMethodType,
            implHandle,
            instantiatedMethodType
        )
    }

    private fun generateLambdaSyntheticMethod(cw: ClassWriter, lm: PendingLambda, owner: String) {
        val paramDescs = lm.parameters.joinToString("") {
            typeMapper.jvmDescriptorFromType(it.type)
        }
        val returnDesc = "Ljava/lang/Object;"
        val desc = "($paramDescs)$returnDesc"

        val mv = cw.visitMethod(
            Opcodes.ACC_PRIVATE or Opcodes.ACC_STATIC or Opcodes.ACC_SYNTHETIC,
            lm.name,
            desc,
            null, null
        )
        mv.visitCode()

        val locals = mutableListOf<LocalInfo>()
        val variables = mutableMapOf<String, Int>()
        var slot = 0
        for (p in lm.parameters) {
            locals.add(LocalInfo(p.name, p.type, slot))
            variables[p.name] = slot
            slot += typeSlots(p.type)
        }

        val ctx = CodegenContext(mv, locals, lm.returnType, slot, variables, true)

        val statements = lm.body.statements
        for (i in 0 until statements.size - 1) {
            generateStatement(mv, statements[i], ctx, owner)
        }
        if (statements.isNotEmpty()) {
            val lastStmt = statements.last()
            if (lastStmt is JasExpressionStatement) {
                generateExpression(mv, lastStmt.expression, ctx, owner)
                val exprDesc = inferExprDesc(lastStmt.expression, ctx)
                boxTopOfStack(mv, exprDesc)
                generateReturn(mv, lm.returnType)
            } else {
                generateStatement(mv, lastStmt, ctx, owner)
                generateReturn(mv, lm.returnType)
            }
        } else {
            generateReturn(mv, lm.returnType)
        }

        mv.visitMaxs(0, 0)
        mv.visitEnd()
    }

    private fun findCapturedIdentifiers(body: JasBlock, lambdaParamNames: Set<String>, ctx: CodegenContext): List<String> {
        val referenced = mutableSetOf<String>()
        collectIdentifiers(body, referenced)
        return referenced.filter { it in ctx.variables && it !in lambdaParamNames }
    }

    private fun collectIdentifiers(node: JasNode, result: MutableSet<String>) {
        when (node) {
            is JasIdentifier -> result.add(node.name)
            is JasIntLiteral, is JasFloatLiteral, is JasStringLiteral, is JasBoolLiteral, is JasNullLiteral -> {}
            is JasBinaryOp -> {
                collectIdentifiers(node.left, result)
                collectIdentifiers(node.right, result)
            }
            is JasUnaryOp -> collectIdentifiers(node.operand, result)
            is JasCall -> {
                collectIdentifiers(node.target, result)
                node.args.forEach { collectIdentifiers(it, result) }
            }
            is JasPropertyAccess -> collectIdentifiers(node.target, result)
            is JasAssignment -> {
                collectIdentifiers(node.target, result)
                collectIdentifiers(node.value, result)
            }
            is JasTernaryExpr -> {
                collectIdentifiers(node.condition, result)
                collectIdentifiers(node.thenExpr, result)
                collectIdentifiers(node.elseExpr, result)
            }
            is JasNullCoalescing -> {
                collectIdentifiers(node.left, result)
                collectIdentifiers(node.right, result)
            }
            is JasNew -> node.args.forEach { collectIdentifiers(it, result) }
            is JasArrayAccess -> {
                collectIdentifiers(node.target, result)
                collectIdentifiers(node.index, result)
            }
            is JasCastExpr -> collectIdentifiers(node.expression, result)
            is JasInstanceOfExpr -> collectIdentifiers(node.expression, result)
            is JasBlock -> node.statements.forEach { collectIdentifiers(it, result) }
            is JasExpressionStatement -> collectIdentifiers(node.expression, result)
            is JasReturn -> node.value?.let { collectIdentifiers(it, result) }
            is JasIf -> {
                collectIdentifiers(node.condition, result)
                collectIdentifiers(node.thenBody, result)
                node.elseBody?.let { collectIdentifiers(it, result) }
            }
            is JasWhile -> {
                collectIdentifiers(node.condition, result)
                collectIdentifiers(node.body, result)
            }
            is JasDoWhile -> {
                collectIdentifiers(node.body, result)
                collectIdentifiers(node.condition, result)
            }
            is JasForStatement -> {
                node.init?.let { collectIdentifiers(it, result) }
                node.condition?.let { collectIdentifiers(it, result) }
                node.update?.let { collectIdentifiers(it, result) }
                collectIdentifiers(node.body, result)
            }
            is JasForInStatement -> {
                collectIdentifiers(node.iterable, result)
                collectIdentifiers(node.body, result)
                node.thenBody?.let { collectIdentifiers(it, result) }
                node.elseBody?.let { collectIdentifiers(it, result) }
            }
            is JasSwitch -> {
                collectIdentifiers(node.expression, result)
                node.cases.forEach { collectIdentifiers(it.body, result) }
            }
            is JasTry -> {
                collectIdentifiers(node.body, result)
                node.catches.forEach { collectIdentifiers(it.body, result) }
                node.finallyBody?.let { collectIdentifiers(it, result) }
            }
            is JasThrow -> collectIdentifiers(node.expression, result)
            is JasVariableStatement -> node.initializer?.let { collectIdentifiers(it, result) }
            is JasAssert -> {
                collectIdentifiers(node.condition, result)
                node.message?.let { collectIdentifiers(it, result) }
            }
            is JasDefer -> collectIdentifiers(node.body, result)
            is JasLabeledStatement -> collectIdentifiers(node.statement, result)
            is JasLock -> collectIdentifiers(node.body, result)
            is JasYield -> collectIdentifiers(node.expression, result)
            is JasLambdaExpr -> {}
            is JasMethodReference -> node.target?.let { collectIdentifiers(it, result) }
            is JasArrayCreation -> node.dims.forEach { collectIdentifiers(it, result) }
            is JasArrayInitValues -> node.values.forEach { collectIdentifiers(it, result) }
            is JasCaseClause -> collectIdentifiers(node.body, result)
            is JasStringTemplate -> node.parts.forEach { collectIdentifiers(it, result) }
            is JasTemplateLiteral -> {}
            is JasTemplateExpr -> collectIdentifiers(node.expr, result)
            is JasDestructuringDeclaration -> {
                collectIdentifiers(node.initializer, result)
            }
            is JasBreakStatement -> {}
            is JasContinueStatement -> {}
            is JasDictLiteral -> {
                node.entries.forEach { collectIdentifiers(it.key, result); collectIdentifiers(it.value, result) }
            }
            is JasDictEntry -> {
                collectIdentifiers(node.key, result)
                collectIdentifiers(node.value, result)
            }
            is JasMatch -> {
                collectIdentifiers(node.expression, result)
                node.cases.forEach { collectIdentifiers(it, result) }
            }
            is JasMatchCase -> {
                node.pattern?.let { collectIdentifiers(it, result) }
                node.guard?.let { collectIdentifiers(it, result) }
                collectIdentifiers(node.body, result)
            }
            is JasWildcardPattern -> {}
            else -> {}
        }
    }

    private fun checkOrPatternBranch(mv: MethodVisitor, pattern: JasExpression, subjectSlot: Int, matchLabel: Label, failLabel: Label, ctx: CodegenContext, owner: String) {
        when (pattern) {
            is JasIntLiteral -> {
                loadLocal(mv, "Ljava/lang/Object;", subjectSlot)
                mv.visitTypeInsn(Opcodes.CHECKCAST, "java/lang/Number")
                mv.visitMethodInsn(Opcodes.INVOKEVIRTUAL, "java/lang/Number", "intValue", "()I", false)
                pushInt(mv, pattern.value.toInt())
                mv.visitJumpInsn(Opcodes.IF_ICMPEQ, matchLabel)
            }
            is JasStringLiteral -> {
                loadLocal(mv, "Ljava/lang/Object;", subjectSlot)
                mv.visitLdcInsn(pattern.text)
                mv.visitMethodInsn(Opcodes.INVOKEVIRTUAL, "java/lang/Object", "equals", "(Ljava/lang/Object;)Z", false)
                mv.visitJumpInsn(Opcodes.IFNE, matchLabel)
            }
            is JasBoolLiteral -> {
                loadLocal(mv, "Ljava/lang/Object;", subjectSlot)
                mv.visitTypeInsn(Opcodes.CHECKCAST, "java/lang/Boolean")
                mv.visitMethodInsn(Opcodes.INVOKEVIRTUAL, "java/lang/Boolean", "booleanValue", "()Z", false)
                mv.visitJumpInsn(if (pattern.value) Opcodes.IFNE else Opcodes.IFEQ, matchLabel)
            }
            is JasIdentifier -> {
                val varSlot = ctx.nextSlot
                ctx.nextSlot += 1
                ctx.variables[pattern.name] = varSlot
                ctx.locals.add(LocalInfo(pattern.name, null, varSlot))
                loadLocal(mv, "Ljava/lang/Object;", subjectSlot)
                storeLocal(mv, "Ljava/lang/Object;", varSlot)
                mv.visitJumpInsn(Opcodes.GOTO, matchLabel)
            }
            is JasBinaryOp -> {
                if (pattern.op == "or") {
                    checkOrPatternBranch(mv, pattern.left, subjectSlot, matchLabel, failLabel, ctx, owner)
                    checkOrPatternBranch(mv, pattern.right, subjectSlot, matchLabel, failLabel, ctx, owner)
                }
            }
        }
    }

    private fun inferEnumArgDesc(expr: JasExpression): String {
        return when (expr) {
            is JasIntLiteral -> "I"
            is JasFloatLiteral -> "D"
            is JasStringLiteral -> "Ljava/lang/String;"
            is JasBoolLiteral -> "Z"
            else -> "Ljava/lang/Object;"
        }
    }

    private fun inferExprDesc(expr: JasExpression, ctx: CodegenContext): String {
        return when (expr) {
            is JasIntLiteral -> "I"
            is JasFloatLiteral -> "F"
            is JasStringLiteral -> "Ljava/lang/String;"
            is JasBoolLiteral -> "Z"
            is JasNullLiteral -> "Ljava/lang/Object;"
            is JasIdentifier -> {
                val local = ctx.locals.find { it.name == expr.name }
                val t = local?.type
                if (t != null) typeMapper.jvmDescriptorFromType(t) else "Ljava/lang/Object;"
            }
            is JasBinaryOp -> inferExprDesc(expr.left, ctx)
            is JasUnaryOp -> inferExprDesc(expr.operand, ctx)
            is JasCall -> "Ljava/lang/Object;"
            is JasNew -> "Ljava/lang/Object;"
            is JasTernaryExpr -> "Ljava/lang/Object;"
            is JasNullCoalescing -> "Ljava/lang/Object;"
            is JasCastExpr -> typeMapper.jvmDescriptorFromType(expr.type)
            is JasArrayAccess -> tryResolveArrayElementDesc(expr.target, ctx)
            is JasInstanceOfExpr -> "Z"
            is JasPropertyAccess -> "Ljava/lang/Object;"
            is JasLambdaExpr -> "Ljava/lang/Object;"
            is JasMethodReference -> "Ljava/lang/Object;"
            is JasArrayCreation -> "Ljava/lang/Object;"
            is JasAssignment -> "Ljava/lang/Object;"
            is JasStringTemplate -> "Ljava/lang/String;"
            is JasDictLiteral -> "Ljava/util/HashMap;"
            else -> "Ljava/lang/Object;"
        }
    }

    private fun boxTopOfStack(mv: MethodVisitor, desc: String) {
        when (desc) {
            "I" -> mv.visitMethodInsn(Opcodes.INVOKESTATIC, "java/lang/Integer", "valueOf", "(I)Ljava/lang/Integer;", false)
            "J" -> mv.visitMethodInsn(Opcodes.INVOKESTATIC, "java/lang/Long", "valueOf", "(J)Ljava/lang/Long;", false)
            "F" -> mv.visitMethodInsn(Opcodes.INVOKESTATIC, "java/lang/Float", "valueOf", "(F)Ljava/lang/Float;", false)
            "D" -> mv.visitMethodInsn(Opcodes.INVOKESTATIC, "java/lang/Double", "valueOf", "(D)Ljava/lang/Double;", false)
            "Z" -> mv.visitMethodInsn(Opcodes.INVOKESTATIC, "java/lang/Boolean", "valueOf", "(Z)Ljava/lang/Boolean;", false)
        }
    }

    private fun callDescriptor(expr: JasCall): String {
        val name = callMethodName(expr)
        val targetFunc = functionRegistry[name]
        if (targetFunc != null) {
            val paramDescs = targetFunc.parameters.joinToString("") {
                typeMapper.jvmDescriptorFromType(it.type)
            }
            val returnDesc = typeMapper.jvmReturnDescriptor(targetFunc.returnType)
            return "($paramDescs)$returnDesc"
        }
        val paramDescs = expr.args.joinToString("") {
            "Ljava/lang/Object;"
        }
        return "($paramDescs)Ljava/lang/Object;"
    }

    private fun resolveVirtualMethodDescriptor(expr: JasCall, ownerName: String): String? {
        val name = callMethodName(expr)
        val paramDescs = expr.args.joinToString("") { "Ljava/lang/Object;" }
        val classMethods = methodRegistry[ownerName]
        if (classMethods != null) {
            val func = classMethods[name]
            if (func != null) {
                val actualParamDescs = func.parameters.joinToString("") {
                    typeMapper.jvmDescriptorFromType(it.type)
                }
                val returnDesc = typeMapper.jvmReturnDescriptor(func.returnType)
                return "($actualParamDescs)$returnDesc"
            }
        }
        return try {
            val cls = Class.forName(ownerName.replace('/', '.'))
            val paramTypes = expr.args.map { java.lang.Object::class.java }.toTypedArray()
            val method = cls.getMethod(name, *paramTypes)
            val retDesc = "L" + method.returnType.name.replace('.', '/') + ";"
            "($paramDescs)$retDesc"
        } catch (e: Exception) {
            null
        }
    }

    private fun callMethodName(expr: JasCall): String {
        return when (expr.target) {
            is JasPropertyAccess -> expr.target.property
            is JasIdentifier -> expr.target.name
            else -> "invoke"
        }
    }

    private fun newDescriptor(expr: JasNew, ctx: CodegenContext?): String {
        val paramDescs = expr.args.joinToString("") {
            expressionDescriptor(it, ctx)
        }
        return "($paramDescs)V"
    }

    private fun expressionDescriptor(expr: JasExpression, ctx: CodegenContext?): String {
        return when (expr) {
            is JasIntLiteral -> "I"
            is JasFloatLiteral -> "F"
            is JasBoolLiteral -> "Z"
            is JasStringLiteral -> "Ljava/lang/String;"
            is JasNullLiteral -> "Ljava/lang/Object;"
            is JasIdentifier -> {
                if (ctx != null) {
                    val local = ctx.locals.find { it.name == expr.name }
                    if (local?.type != null) typeMapper.jvmDescriptorFromType(local.type) else "Ljava/lang/Object;"
                } else "Ljava/lang/Object;"
            }
            is JasUnaryOp -> expressionDescriptor(expr.operand, ctx)
            is JasBinaryOp -> expressionDescriptor(expr.left, ctx)
            is JasArrayAccess -> expressionDescriptor(expr.target, ctx)
            is JasCastExpr -> typeMapper.jvmDescriptorFromType(expr.type)
            is JasNew, is JasCall, is JasPropertyAccess, is JasArrayCreation -> "Ljava/lang/Object;"
            else -> "Ljava/lang/Object;"
        }
    }

    private fun generateReturn(mv: MethodVisitor, returnType: JasType?) {
        val retDesc = typeMapper.jvmReturnDescriptor(returnType)
        when (retDesc) {
            "V" -> mv.visitInsn(Opcodes.RETURN)
            "I", "Z", "B", "S", "C" -> mv.visitInsn(Opcodes.IRETURN)
            "J" -> mv.visitInsn(Opcodes.LRETURN)
            "F" -> mv.visitInsn(Opcodes.FRETURN)
            "D" -> mv.visitInsn(Opcodes.DRETURN)
            else -> mv.visitInsn(Opcodes.ARETURN)
        }
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

    private fun emitBinaryArithmeticOp(mv: MethodVisitor, op: String, desc: String) {
        val isLong = desc == "J"
        val isDouble = desc == "D"
        val isFloat = desc == "F"
        val isString = desc.startsWith("Ljava/lang/String")
        when (op) {
            "+" -> {
                if (isString) {
                    mv.visitMethodInsn(Opcodes.INVOKEVIRTUAL, "java/lang/String", "concat", "(Ljava/lang/String;)Ljava/lang/String;", false)
                } else {
                    mv.visitInsn(if (isLong) Opcodes.LADD else if (isDouble) Opcodes.DADD else if (isFloat) Opcodes.FADD else Opcodes.IADD)
                }
            }
            "-" -> mv.visitInsn(if (isLong) Opcodes.LSUB else if (isDouble) Opcodes.DSUB else if (isFloat) Opcodes.FSUB else Opcodes.ISUB)
            "*" -> mv.visitInsn(if (isLong) Opcodes.LMUL else if (isDouble) Opcodes.DMUL else if (isFloat) Opcodes.FMUL else Opcodes.IMUL)
            "/" -> mv.visitInsn(if (isLong) Opcodes.LDIV else if (isDouble) Opcodes.DDIV else if (isFloat) Opcodes.FDIV else Opcodes.IDIV)
            "%" -> mv.visitInsn(if (isLong) Opcodes.LREM else if (isDouble) Opcodes.DREM else if (isFloat) Opcodes.FREM else Opcodes.IREM)
            "==", "!=", "<", ">", "<=", ">=" -> emitCompare(mv, op, desc)
            "&" -> mv.visitInsn(if (isLong) Opcodes.LAND else Opcodes.IAND)
            "|" -> mv.visitInsn(if (isLong) Opcodes.LOR else Opcodes.IOR)
            "^" -> mv.visitInsn(if (isLong) Opcodes.LXOR else Opcodes.IXOR)
            "<<" -> { if (isLong) mv.visitInsn(Opcodes.LSHL) else mv.visitInsn(Opcodes.ISHL) }
            ">>" -> { if (isLong) mv.visitInsn(Opcodes.LSHR) else mv.visitInsn(Opcodes.ISHR) }
            ">>>" -> { if (isLong) mv.visitInsn(Opcodes.LUSHR) else mv.visitInsn(Opcodes.IUSHR) }
            else -> mv.visitInsn(Opcodes.ICONST_0)
        }
    }

    private fun emitCompare(mv: MethodVisitor, op: String, desc: String) {
        if (desc.startsWith("L") || desc.startsWith("[")) {
            mv.visitMethodInsn(Opcodes.INVOKEVIRTUAL, "java/lang/Object", "equals", "(Ljava/lang/Object;)Z", false)
            if (op == "==") {
                return
            } else if (op == "!=") {
                val trueLabel = Label()
                val endLabel = Label()
                mv.visitJumpInsn(Opcodes.IFNE, trueLabel)
                mv.visitInsn(Opcodes.ICONST_1)
                mv.visitJumpInsn(Opcodes.GOTO, endLabel)
                mv.visitLabel(trueLabel)
                mv.visitInsn(Opcodes.ICONST_0)
                mv.visitLabel(endLabel)
                return
            } else {
                mv.visitInsn(Opcodes.ICONST_0)
                return
            }
        }
        val isLong = desc == "J"
        val isDouble = desc == "D"
        val isFloat = desc == "F"
        val trueLabel = Label()
        val endLabel = Label()
        if (isLong) {
            mv.visitInsn(Opcodes.LCMP)
            val ifOp = when (op) {
                "==" -> Opcodes.IFEQ; "!=" -> Opcodes.IFNE
                "<" -> Opcodes.IFLT; ">" -> Opcodes.IFGT
                "<=" -> Opcodes.IFLE; ">=" -> Opcodes.IFGE
                else -> Opcodes.IFEQ
            }
            mv.visitJumpInsn(ifOp, trueLabel)
        } else if (isDouble || isFloat) {
            val cmp = if (isDouble) Opcodes.DCMPG else Opcodes.FCMPG
            mv.visitInsn(cmp)
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

    private fun emitDeferredActions(mv: MethodVisitor, ctx: CodegenContext, owner: String) {
        for (body in ctx.deferredBodies.reversed()) {
            generateBlock(mv, body, ctx, owner)
        }
    }

    private fun emitCompare(mv: MethodVisitor, opcode: Int) {
        val trueLabel = Label()
        val endLabel = Label()
        mv.visitJumpInsn(opcode, trueLabel)
        mv.visitInsn(Opcodes.ICONST_0)
        mv.visitJumpInsn(Opcodes.GOTO, endLabel)
        mv.visitLabel(trueLabel)
        mv.visitInsn(Opcodes.ICONST_1)
        mv.visitLabel(endLabel)
    }

    private fun resolvePropertyDescriptor(expr: JasPropertyAccess): String {
        return fieldDescriptorMap[expr.property] ?: "Ljava/lang/Object;"
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

    private fun resolveReceiverType(expr: JasCall, ctx: CodegenContext): JasType? {
        val target = expr.target
        if (target is JasPropertyAccess) {
            val inner = target.target
            if (inner is JasIdentifier) {
                val local = ctx.locals.find { it.name == inner.name }
                local?.type?.let { return it }
                ctx.inferredTypes[inner.name]?.let { return it }
            }
        }
        return null
    }

    private fun tryResolveArrayElementDesc(expr: JasExpression, ctx: CodegenContext): String {
        return when (expr) {
            is JasIdentifier -> {
                val local = ctx.locals.find { it.name == expr.name }
                val t = local?.type
                if (t is JasArrayType) typeMapper.jvmDescriptorFromType(t.inner) else "Ljava/lang/Object;"
            }
            is JasPropertyAccess -> "Ljava/lang/Object;"
            is JasArrayCreation -> typeMapper.jvmDescriptorFromType(expr.type)
            is JasCall -> "Ljava/lang/Object;"
            is JasNew -> "Ljava/lang/Object;"
            else -> "Ljava/lang/Object;"
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

    // ── Generic signature helpers ──

    private fun jvmClassSignature(typeParams: List<JasTypeParameter>, superclass: JasType?): String {
        val sb = StringBuilder()
        sb.append('<')
        for (tp in typeParams) {
            sb.append(jvmTypeParamSignature(tp))
        }
        sb.append('>')
        sb.append(jvmTypeSignature(superclass ?: JasAnyType))
        return sb.toString()
    }

    private fun jvmMethodSignature(func: JasFunction): String {
        val sb = StringBuilder()
        sb.append('<')
        for (tp in func.typeParameters) {
            sb.append(jvmTypeParamSignature(tp))
        }
        sb.append('>')
        sb.append('(')
        for (p in func.parameters) {
            sb.append(jvmTypeSignature(p.type ?: JasAnyType))
        }
        sb.append(')')
        sb.append(jvmTypeSignature(func.returnType ?: JasUnitType))
        return sb.toString()
    }

    private fun jvmTypeParamSignature(tp: JasTypeParameter): String {
        val sb = StringBuilder()
        sb.append(tp.name)
        sb.append(':')
        sb.append(jvmTypeSignature(tp.bound ?: JasAnyType))
        return sb.toString()
    }

    private fun jvmTypeSignature(type: JasType): String {
        val desc = typeMapper.jvmDescriptorFromType(type)
        return when (type) {
            is JasPrimitiveType -> desc
            is JasUnitType -> "V"
            is JasStringType -> "Ljava/lang/String;"
            is JasAnyType -> "Ljava/lang/Object;"
            is JasNamedType -> {
                if (desc.startsWith("L") && desc.endsWith(";")) {
                    val internal = desc.substring(1, desc.length - 1)
                    if (type.typeArguments.isEmpty()) {
                        "L$internal;"
                    } else {
                        val args = type.typeArguments.joinToString("") { jvmTypeSignature(it) }
                        "L$internal<$args>;"
                    }
                } else {
                    desc
                }
            }
            is JasArrayType -> "[" + jvmTypeSignature(type.inner)
            is JasNullableType -> jvmTypeSignature(type.inner)
            is JasNonNullType -> jvmTypeSignature(type.inner)
            is JasWildcardType -> {
                if (type.bound == null) "*"
                else if (type.extends) "+${jvmTypeSignature(type.bound!!)}"
                else "-${jvmTypeSignature(type.bound!!)}"
            }
            is JasBytesType -> "Ljava/lang/Object;"
            is JasRegexType -> "Ljava/lang/Object;"
            is JasPointerType -> "J"
            is JasReferenceType -> "Ljava/lang/Object;"
            is JasTupleType -> "Ljava/lang/Object;"
            is JasFunctionType -> "Ljava/lang/Object;"
        }
    }

    private fun hasDeferBlock(block: JasBlock): Boolean {
        return block.statements.any { it is JasDefer || (it is JasBlock && hasDeferBlock(it)) }
    }
}
