@file:OptIn(org.jetbrains.kotlin.ir.symbols.UnsafeDuringIrConstructionAPI::class)

package jasper.translator

import jasper.ast.*
import jasper.compiler.SymbolTable
import org.jetbrains.kotlin.ir.IrStatement
import org.jetbrains.kotlin.ir.declarations.*
import org.jetbrains.kotlin.ir.declarations.impl.IrFactoryImpl
import org.jetbrains.kotlin.ir.declarations.impl.IrFileImpl
import org.jetbrains.kotlin.ir.expressions.*
import org.jetbrains.kotlin.ir.expressions.impl.*
import org.jetbrains.kotlin.ir.symbols.*
import org.jetbrains.kotlin.ir.symbols.impl.*
import org.jetbrains.kotlin.ir.types.*
import org.jetbrains.kotlin.ir.declarations.StageController
import org.jetbrains.kotlin.ir.util.NaiveSourceBasedFileEntryImpl
import org.jetbrains.kotlin.name.FqName
import org.jetbrains.kotlin.name.Name
import org.jetbrains.kotlin.descriptors.DescriptorVisibilities
import org.jetbrains.kotlin.descriptors.Modality
import org.jetbrains.kotlin.descriptors.ClassKind
import org.jetbrains.kotlin.descriptors.SourceElement
import org.jetbrains.kotlin.types.Variance

data class TranslationResult(
    val irFiles: List<IrFile>,
    val callNames: Map<IrSimpleFunctionSymbol, String> = emptyMap(),
    val constructorTypes: Map<IrConstructorSymbol, String> = emptyMap(),
    val typeMapper: TypeMapper? = null,
    val functionDescriptors: Map<IrSimpleFunctionSymbol, String> = emptyMap(),
    val parameterDescriptors: Map<IrValueSymbol, String> = emptyMap(),
    val classSuperclassNames: Map<String, String> = emptyMap(), // className -> jvmInternalName
    val classInterfaceNames: Map<String, List<String>> = emptyMap(), // className -> jvmInternalName list
    val interfaceDeclNames: Set<String> = emptySet() // names of types declared as interfaces
)

data class TranslationContext(
    val symbols: MutableMap<String, IrValueSymbol> = mutableMapOf(),
    val varTypes: MutableMap<String, IrType> = mutableMapOf(),
    val functionSymbols: MutableMap<String, IrSimpleFunctionSymbol> = mutableMapOf(),
    val functionReturnTypes: MutableMap<String, IrType> = mutableMapOf(),
    val functionParamTypes: MutableMap<String, List<IrType>> = mutableMapOf(),
    val callNames: MutableMap<IrSimpleFunctionSymbol, String> = mutableMapOf(),
    val constructorTypes: MutableMap<IrConstructorSymbol, String> = mutableMapOf(),
    val typeMapper: TypeMapper? = null,
    val lambdas: MutableMap<String, JasLambdaExpr> = mutableMapOf(),
    val lambdaVarNames: MutableSet<String> = mutableSetOf(),
    val classSymbols: MutableMap<String, IrClassSymbol> = mutableMapOf(),
    val fieldSymbols: MutableMap<String, IrFieldSymbol> = mutableMapOf(),
    val fieldTypes: MutableMap<String, IrType> = mutableMapOf(),
    val loopStack: MutableList<IrLoop> = mutableListOf(),
    val deferredBodies: MutableList<IrBlock> = mutableListOf(),
    var currentFunction: IrSimpleFunction? = null,
    var currentFile: IrFile? = null,
    var lambdaIdCounter: Int = 0,
    var currentSuperclassName: String = "",
    var currentPkg: String = "",
    val classSuperclassNames: MutableMap<String, String> = mutableMapOf(),
    val interfaceNames: MutableMap<String, MutableList<String>> = mutableMapOf(), // class/interface -> implemented/extends interface names
    val interfaceDeclNames: MutableSet<String> = mutableSetOf() // names of types declared as interfaces
)

class JasperToIrTranslator {
    private val typeMapper = TypeMapper()
    private val factory = IrFactory(StageController())

    fun translate(ast: JasSourceFile, symbolTable: SymbolTable? = null): TranslationResult {
        val ctx = TranslationContext(typeMapper = typeMapper)
        val pkg = ast.packageName ?: ""
        ctx.currentPkg = pkg
        typeMapper.currentPkg = pkg
        predeclareTopLevelFunctions(ast.declarations, ctx)
        val fileEntry = NaiveSourceBasedFileEntryImpl("<main>", intArrayOf(), 0)
        val fileSymbol = IrFileSymbolImpl()
        val file = IrFileImpl(fileEntry, fileSymbol, FqName(pkg))
        ctx.currentFile = file
        for (decl in ast.declarations) {
            val irDecl = translateTopLevelDeclaration(decl, ctx)
            if (irDecl != null) {
                file.declarations.add(irDecl)
            }
        }
        return TranslationResult(listOf(file), ctx.callNames.toMap(), ctx.constructorTypes.toMap(), typeMapper,
            classSuperclassNames = ctx.classSuperclassNames.toMap(),
            classInterfaceNames = ctx.interfaceNames.mapValues { it.value.toList() }.toMap(),
            interfaceDeclNames = ctx.interfaceDeclNames.toSet())
    }

    fun translateAll(asts: List<JasSourceFile>, symbolTable: SymbolTable? = null): TranslationResult {
        val ctx = TranslationContext(typeMapper = typeMapper)
        for (ast in asts) {
            ctx.currentPkg = ast.packageName ?: ""
            typeMapper.currentPkg = ctx.currentPkg
            predeclareTopLevelFunctions(ast.declarations, ctx)
        }

        val files = mutableListOf<IrFile>()
        for ((index, ast) in asts.withIndex()) {
            val pkg = ast.packageName ?: ""
            ctx.currentPkg = pkg
            typeMapper.currentPkg = pkg
            val fileEntry = NaiveSourceBasedFileEntryImpl("<file$index>", intArrayOf(), 0)
            val fileSymbol = IrFileSymbolImpl()
            val file = IrFileImpl(fileEntry, fileSymbol, FqName(pkg))
            ctx.currentFile = file
            for (decl in ast.declarations) {
                val irDecl = translateTopLevelDeclaration(decl, ctx)
                if (irDecl != null) {
                    file.declarations.add(irDecl)
                }
            }
            files.add(file)
        }

        return TranslationResult(files, ctx.callNames.toMap(), ctx.constructorTypes.toMap(), typeMapper,
            classSuperclassNames = ctx.classSuperclassNames.toMap(),
            classInterfaceNames = ctx.interfaceNames.mapValues { it.value.toList() }.toMap(),
            interfaceDeclNames = ctx.interfaceDeclNames.toSet())
    }

    private fun predeclareTopLevelFunctions(declarations: List<JasDeclaration>, ctx: TranslationContext) {
        for (decl in declarations) {
            if (decl is JasFunction) {
                val symbol = ctx.functionSymbols.getOrPut(decl.name) { IrSimpleFunctionSymbolImpl() }
                ctx.callNames[symbol] = decl.name
                ctx.functionReturnTypes.putIfAbsent(decl.name, typeMapper.mapType(decl.returnType))
                ctx.functionParamTypes.putIfAbsent(decl.name, decl.parameters.map { typeMapper.mapType(it.type) })
            }
        }
    }

    private fun translateTopLevelDeclaration(decl: JasDeclaration, ctx: TranslationContext): IrDeclaration? {
        return when (decl) {
            is JasFunction -> translateFunction(decl, null, ctx)
            is JasClass -> translateClass(decl, ctx)
            is JasInterface -> translateInterface(decl, ctx)
            is JasEnum -> translateEnum(decl, ctx)
            is JasAnnotationType -> translateAnnotationType(decl, ctx)
            is JasAnnotationMember -> null
            is JasConstructor -> null
            is JasProperty -> null
            is JasImport -> null
        }
    }

    private fun translateClassMember(decl: JasDeclaration, ctx: TranslationContext, classSymbol: IrClassSymbol): IrDeclaration? {
        return when (decl) {
            is JasFunction -> translateFunction(decl, classSymbol, ctx)
            is JasConstructor -> translateConstructor(decl, classSymbol, ctx)
            is JasProperty -> translateProperty(decl, classSymbol, ctx)
            else -> null
        }
    }

    // ── function ──

    private fun translateFunction(
        func: JasFunction, parentClass: IrClassSymbol?, ctx: TranslationContext
    ): IrSimpleFunction {
        // Create type parameters first so they can be referenced by parameter/return types
        val irTypeParams = func.typeParameters.map { tp ->
            val irTp = translateTypeParameter(tp, ctx)
            irTp
        }
        // Helper to resolve JasNamedType referring to a type parameter to an IrTypeParameterSymbol-based type
        fun resolveTypeParam(type: JasType?): IrType {
            if (type is JasNamedType && type.typeArguments.isEmpty()) {
                val matching = irTypeParams.find { it.name.toString() == type.name }
                if (matching != null) {
                    return typeMapper.typeFromSymbol(matching.symbol)
                }
            }
            return typeMapper.mapType(type)
        }
        val returnType = resolveTypeParam(func.returnType)
        val symbol = if (parentClass == null) {
            ctx.functionSymbols[func.name] ?: IrSimpleFunctionSymbolImpl()
        } else {
            IrSimpleFunctionSymbolImpl()
        }
        val irFunc = factory.createSimpleFunction(
            0, 0,
            IrDeclarationOrigin.DEFINED,
            Name.identifier(func.name),
            if (func.modifiers.contains("public")) DescriptorVisibilities.PUBLIC else DescriptorVisibilities.INTERNAL,
            false, false,
            returnType,
            if (func.modifiers.contains("abstract") || func.modifiers.contains("open")) Modality.ABSTRACT else Modality.FINAL,
            symbol,
            false, false, false, false, false,
            null, false
        )
        for (irTp in irTypeParams) {
            irFunc.typeParameters = irFunc.typeParameters + irTp
        }
        for ((i, param) in func.parameters.withIndex()) {
            val paramType = resolveTypeParam(param.type)
            val paramSymbol = IrValueParameterSymbolImpl()
            val irParam = factory.createValueParameter(
                0, 0,
                IrDeclarationOrigin.DEFINED,
                Name.identifier(param.name),
                paramType,
                param.vararg,
                paramSymbol,
                i,
                null, false, false, false
            )
            irFunc.valueParameters = irFunc.valueParameters + irParam
            ctx.symbols[param.name] = paramSymbol
            ctx.varTypes[param.name] = paramType
        }
        if (func.body != null) {
            val prevFunc = ctx.currentFunction
            ctx.currentFunction = irFunc
            val body = irFunc.body as? IrBlockBody ?: factory.createBlockBody(0, 0)
            for (stmt in func.body!!.statements) {
                val irStmt = translateStatement(stmt, ctx)
                if (irStmt != null) {
                    (body.statements as MutableList<IrStatement>).add(irStmt)
                }
            }
            irFunc.body = body
            ctx.currentFunction = prevFunc
        }
        if (parentClass == null) {
            ctx.functionSymbols[func.name] = symbol
        }
        ctx.functionReturnTypes[func.name] = returnType
        ctx.functionParamTypes[func.name] = func.parameters.map { resolveTypeParam(it.type) }
        ctx.callNames[symbol] = func.name
        return irFunc
    }

    // ── class ──

    private fun translateClass(cls: JasClass, ctx: TranslationContext): IrClass {
        val symbol = IrClassSymbolImpl()
        val kind = if (cls.modifiers.contains("annotation")) ClassKind.ANNOTATION_CLASS
            else if (cls.modifiers.contains("data")) ClassKind.CLASS
            else if (cls.modifiers.contains("sealed")) ClassKind.CLASS
            else ClassKind.CLASS
        val visibility = if (cls.modifiers.contains("public")) DescriptorVisibilities.PUBLIC
            else DescriptorVisibilities.INTERNAL
        val irCls = factory.createClass(
            0, 0,
            IrDeclarationOrigin.DEFINED,
            Name.identifier(cls.name),
            visibility,
            symbol,
            kind,
            if (cls.modifiers.contains("abstract") || cls.modifiers.contains("open")) Modality.OPEN else Modality.FINAL,
            false, false, false, false, false, false, false, false,
            SourceElement.NO_SOURCE
        )
        ctx.classSymbols[cls.name] = symbol
        // Record implemented interfaces
        val ifaceNames = cls.interfaces.mapNotNull { iface ->
            if (iface is JasNamedType) {
                val raw = iface.name
                if (raw.contains("/")) raw else {
                    val p = ctx.currentPkg
                    if (p.isNotEmpty()) "$p/$raw" else raw
                }
            } else null
        }
        ctx.interfaceNames[cls.name] = ifaceNames.toMutableList()
        for (tp in cls.typeParameters) {
            val irTp = translateTypeParameter(tp, ctx)
            irCls.typeParameters = irCls.typeParameters + irTp
        }
        for (member in cls.members) {
            val irMember = translateClassMember(member, ctx, symbol)
            if (irMember != null) {
                irCls.declarations.add(irMember)
                if (irMember is IrProperty && irMember.backingField != null) {
                    irCls.declarations.add(irMember.backingField!!)
                }
            }
        }
        val rawSuper = if (cls.superclass != null) typeMapper.jvmInternalNameFromType(cls.superclass) else "java/lang/Object"
        val qualifiedSuper = if (rawSuper.contains("/")) rawSuper else {
            val p = ctx.currentPkg
            if (p.isNotEmpty()) "$p/$rawSuper" else rawSuper
        }
        ctx.currentSuperclassName = qualifiedSuper
        ctx.classSuperclassNames[cls.name] = qualifiedSuper
        if (cls.constructors.isEmpty()) {
            // Generate a default no-arg constructor
            val defaultCtorSymbol = IrConstructorSymbolImpl()
            val defaultCtor = factory.createConstructor(
                0, 0, IrDeclarationOrigin.DEFINED,
                Name.special("<init>"),
                DescriptorVisibilities.PUBLIC,
                false, false, typeMapper.unitType(),
                defaultCtorSymbol, true, false, null
            )
            val defaultCtorBody = factory.createBlockBody(0, 0)
            defaultCtor.body = defaultCtorBody
            irCls.declarations.add(defaultCtor)
        } else {
            for (ctor in cls.constructors) {
                val irCtor = translateConstructor(ctor, symbol, ctx)
                irCls.declarations.add(irCtor)
            }
        }
        ctx.currentSuperclassName = ""
        return irCls
    }

    private fun translateConstructor(ctor: JasConstructor, classSymbol: IrClassSymbol, ctx: TranslationContext): IrConstructor {
        val ctorSymbol = IrConstructorSymbolImpl()
        val returnType = typeMapper.unitType()
        val irCtor = factory.createConstructor(
            0, 0,
            IrDeclarationOrigin.DEFINED,
            Name.special("<init>"),
            DescriptorVisibilities.PUBLIC,
            false, false,
            returnType,
            ctorSymbol,
            true, false, null
        )
        for ((i, param) in ctor.parameters.withIndex()) {
            val paramType = typeMapper.mapType(param.type)
            val paramSymbol = IrValueParameterSymbolImpl()
            val irParam = factory.createValueParameter(
                0, 0,
                IrDeclarationOrigin.DEFINED,
                Name.identifier(param.name),
                paramType,
                param.vararg,
                paramSymbol,
                i,
                null, false, false, false
            )
            irCtor.valueParameters = irCtor.valueParameters + irParam
            ctx.symbols[param.name] = paramSymbol
            ctx.varTypes[param.name] = paramType
        }
        val body = irCtor.body as? IrBlockBody ?: factory.createBlockBody(0, 0)
        // Emit delegate call (super() or this()) if present
        val delegateCall = ctor.delegateCall
        if (delegateCall != null) {
            val delegateTarget = if (delegateCall.isSuper) "super:${ctx.currentSuperclassName}" else "this"
            val delegateSymbol = IrSimpleFunctionSymbolImpl()
            ctx.callNames[delegateSymbol] = "ctor_delegate:$delegateTarget"
            // Include `this` as first arg so backend emits ALOAD 0
            val allArgs = mutableListOf<IrExpression>()
            val thisGet = IrNodeFactory.createGetValue(0, 0, typeMapper.unitType(), IrValueParameterSymbolImpl(), null)
            allArgs.add(thisGet)
            allArgs.addAll(delegateCall.args.map { translateExpression(it, ctx) })
            val delegateCallExpr = IrNodeFactory.createCall(
                0, 0, typeMapper.unitType(), null,
                allArgs.toTypedArray(),
                emptyArray(), delegateSymbol, null
            )
            (body.statements as MutableList<IrStatement>).add(delegateCallExpr)
        }
        if (ctor.body != null) {
            for (stmt in ctor.body!!.statements) {
                val irStmt = translateStatement(stmt, ctx)
                if (irStmt != null) {
                    (body.statements as MutableList<IrStatement>).add(irStmt)
                }
            }
        }
        irCtor.body = body
        return irCtor
    }

    // ── interface ──

    private fun translateInterface(iface: JasInterface, ctx: TranslationContext): IrClass {
        val symbol = IrClassSymbolImpl()
        val irCls = factory.createClass(
            0, 0,
            IrDeclarationOrigin.DEFINED,
            Name.identifier(iface.name),
            DescriptorVisibilities.PUBLIC,
            symbol,
            ClassKind.INTERFACE,
            Modality.ABSTRACT,
            false, false, false, false, false, false, false, false,
            SourceElement.NO_SOURCE
        )
        ctx.classSymbols[iface.name] = symbol
        ctx.interfaceDeclNames.add(iface.name)
        // Record extended interfaces
        val ifaceNames = iface.extends.mapNotNull { ext ->
            if (ext is JasNamedType) {
                val raw = ext.name
                if (raw.contains("/")) raw else {
                    val p = ctx.currentPkg
                    if (p.isNotEmpty()) "$p/$raw" else raw
                }
            } else null
        }
        ctx.interfaceNames[iface.name] = ifaceNames.toMutableList()
        for (tp in iface.typeParameters) {
            val irTp = translateTypeParameter(tp, ctx)
            irCls.typeParameters = irCls.typeParameters + irTp
        }
        for (member in iface.members) {
            val irMember = translateClassMember(member, ctx, symbol)
            if (irMember != null) {
                irCls.declarations.add(irMember)
            }
        }
        return irCls
    }

    // ── enum ──

    private fun translateEnum(enum: JasEnum, ctx: TranslationContext): IrClass {
        val symbol = IrClassSymbolImpl()
        val irCls = factory.createClass(
            0, 0,
            IrDeclarationOrigin.DEFINED,
            Name.identifier(enum.name),
            DescriptorVisibilities.PUBLIC,
            symbol,
            ClassKind.ENUM_CLASS,
            Modality.FINAL,
            false, false, false, false, false, false, false, false,
            SourceElement.NO_SOURCE
        )
        ctx.classSymbols[enum.name] = symbol
        for (const in enum.constants) {
            val irConst = translateEnumConstant(const, ctx)
            irCls.declarations.add(irConst)
        }
        for (member in enum.members) {
            val irMember = translateClassMember(member, ctx, symbol)
            if (irMember != null) {
                irCls.declarations.add(irMember)
            }
        }
        return irCls
    }

    private fun translateEnumConstant(const: JasEnumConstant, ctx: TranslationContext): IrEnumEntry {
        val entrySymbol = IrEnumEntrySymbolImpl()
        return factory.createEnumEntry(0, 0, IrDeclarationOrigin.DEFINED, Name.identifier(const.name), entrySymbol)
    }

    private fun translateEnumEntry(decl: JasEnumConstant, ctx: TranslationContext): IrDeclaration {
        val entrySymbol = IrEnumEntrySymbolImpl()
        return factory.createEnumEntry(0, 0, IrDeclarationOrigin.DEFINED, Name.identifier(decl.name), entrySymbol)
    }

    // ── annotation type ──

    private fun translateAnnotationType(ann: JasAnnotationType, ctx: TranslationContext): IrClass? {
        val symbol = IrClassSymbolImpl()
        val irCls = factory.createClass(
            0, 0,
            IrDeclarationOrigin.DEFINED,
            Name.identifier(ann.name),
            DescriptorVisibilities.PUBLIC,
            symbol,
            ClassKind.ANNOTATION_CLASS,
            Modality.OPEN,
            false, false, false, false, false, false, false, false,
            SourceElement.NO_SOURCE
        )
        ctx.classSymbols[ann.name] = symbol
        for (member in ann.members) {
            val irMember = translateAnnotationMember(member, ctx)
            if (irMember != null) {
                irCls.declarations.add(irMember)
            }
        }
        return irCls
    }

    private fun translateAnnotationMember(member: JasAnnotationMember, ctx: TranslationContext): IrDeclaration? {
        return null
    }

    // ── property ──

    private fun translateProperty(prop: JasProperty, classSymbol: IrClassSymbol, ctx: TranslationContext): IrProperty? {
        val propSymbol = IrPropertySymbolImpl()
        val propType = typeMapper.mapType(prop.type)
        val irProp = factory.createProperty(
            0, 0,
            IrDeclarationOrigin.DEFINED,
            Name.identifier(prop.name),
            DescriptorVisibilities.PRIVATE,
            Modality.FINAL,
            propSymbol,
            true, false, false, false, false,
            null, false, false
        )
        val fieldSymbol = IrFieldSymbolImpl()
        val irField = factory.createField(
            0, 0,
            IrDeclarationOrigin.DEFINED,
            Name.identifier(prop.name),
            DescriptorVisibilities.PRIVATE,
            fieldSymbol,
            propType,
            false,
            true,
            false
        )
        irProp.backingField = irField
        ctx.fieldSymbols[prop.name] = fieldSymbol
        ctx.fieldTypes[prop.name] = propType
        if (prop.getter != null) {
            val getterFunc = translatePropertyAccessor(prop.getter!!, prop.name, propType, isGetter = true, ctx)
            irProp.getter = getterFunc
        }
        if (prop.setter != null) {
            val setterFunc = translatePropertyAccessor(prop.setter!!, prop.name, propType, isGetter = false, ctx)
            irProp.setter = setterFunc
        }
        return irProp
    }

    private fun translatePropertyAccessor(
        accessor: JasPropertyAccessor,
        propName: String,
        propType: IrType,
        isGetter: Boolean,
        ctx: TranslationContext
    ): IrSimpleFunction {
        val symbol = IrSimpleFunctionSymbolImpl()
        val returnType = if (isGetter) propType else typeMapper.unitType()
        val accessorKind = if (isGetter) "getter" else "setter"
        val irFunc = factory.createSimpleFunction(
            0, 0,
            IrDeclarationOrigin.DEFINED,
            Name.identifier("<$accessorKind-$propName>"),
            DescriptorVisibilities.PUBLIC,
            false, false,
            returnType,
            Modality.FINAL,
            symbol,
            false, false, false, false, false,
            null, false
        )
        if (!isGetter) {
            val paramType = propType
            val paramSymbol = IrValueParameterSymbolImpl()
            val paramName = accessor.parameterName ?: "value"
            val irParam = factory.createValueParameter(
                0, 0,
                IrDeclarationOrigin.DEFINED,
                Name.identifier(paramName),
                paramType,
                false,
                paramSymbol,
                0,
                null, false, false, false
            )
            irFunc.valueParameters = listOf(irParam)
            ctx.symbols[paramName] = paramSymbol
            ctx.varTypes[paramName] = paramType
        }
        if (accessor.body != null) {
            val prevFunc = ctx.currentFunction
            ctx.currentFunction = irFunc
            val body = irFunc.body as? IrBlockBody ?: factory.createBlockBody(0, 0)
            for (stmt in accessor.body!!.statements) {
                val irStmt = translateStatement(stmt, ctx)
                if (irStmt != null) {
                    (body.statements as MutableList<IrStatement>).add(irStmt)
                }
            }
            irFunc.body = body
            ctx.currentFunction = prevFunc
        }
        ctx.functionSymbols["<$accessorKind-$propName>"] = symbol
        return irFunc
    }

    // ── type parameter ──

    private fun translateTypeParameter(tp: JasTypeParameter, ctx: TranslationContext): IrTypeParameter {
        val tpSymbol = IrTypeParameterSymbolImpl()
        val irTp = factory.createTypeParameter(
            0, 0, IrDeclarationOrigin.DEFINED,
            Name.identifier(tp.name),
            tpSymbol,
            Variance.INVARIANT,
            0, false
        )
        if (tp.bound != null) {
            irTp.superTypes = listOf(typeMapper.mapType(tp.bound))
        }
        return irTp
    }

    // ── statements ──

    private fun translateStatement(stmt: JasStatement, ctx: TranslationContext): IrStatement? {
        return when (stmt) {
            is JasBlock -> translateBlock(stmt, ctx)
            is JasVariableStatement -> translateVariableStatement(stmt, ctx)
            is JasExpressionStatement -> translateExpressionStatement(stmt, ctx)
            is JasReturn -> translateReturn(stmt, ctx)
            is JasIf -> translateIf(stmt, ctx)
            is JasWhile -> translateWhile(stmt, ctx)
            is JasDoWhile -> translateDoWhile(stmt, ctx)
            is JasForStatement -> translateFor(stmt, ctx)
            is JasForInStatement -> translateForIn(stmt, ctx)
            is JasSwitch -> translateSwitch(stmt, ctx)
            is JasBreakStatement -> translateBreak(stmt, ctx)
            is JasContinueStatement -> translateContinue(stmt, ctx)
            is JasThrow -> translateThrow(stmt, ctx)
            is JasTry -> translateTry(stmt, ctx)
            is JasLabeledStatement -> translateStatement(stmt.statement, ctx)
            is JasDefer -> translateDefer(stmt, ctx)
            is JasLock -> translateLock(stmt, ctx)
            is JasAssert -> translateAssert(stmt, ctx)
            is JasYield -> translateYield(stmt, ctx)
            is JasDestructuringDeclaration -> translateDestructuringDeclaration(stmt, ctx)
            is JasMatch -> translateMatch(stmt, ctx)
            else -> null
        }
    }

    private fun translateBlock(block: JasBlock, ctx: TranslationContext): IrBlock {
        val body = IrNodeFactory.createBlock(0, 0, typeMapper.unitType(), null)
        for (stmt in block.statements) {
            val irStmt = translateStatement(stmt, ctx)
            if (irStmt != null) {
                (body.statements as MutableList<IrStatement>).add(irStmt)
            }
        }
        return body
    }

    private fun translateVariableStatement(stmt: JasVariableStatement, ctx: TranslationContext): IrVariable {
        if (stmt.initializer is JasLambdaExpr) {
            ctx.lambdas[stmt.name] = stmt.initializer
            ctx.lambdaVarNames.add(stmt.name)
        }
        if (stmt.initializer is JasMethodReference) {
            ctx.lambdaVarNames.add(stmt.name)
        }
        val varSymbol = IrVariableSymbolImpl()
        // Translate the initializer once to avoid double-creating anonymous classes
        val translatedInit = if (stmt.initializer != null) translateExpression(stmt.initializer!!, ctx) else null
        val varType = if (stmt.type != null) typeMapper.mapType(stmt.type)
            else translatedInit?.type ?: typeMapper.unitType()
        val irVar = IrNodeFactory.createVariable(
            0, 0, IrDeclarationOrigin.DEFINED, Name.identifier(stmt.name),
            varType, varSymbol, !stmt.isConst, stmt.isConst, false
        )
        irVar.initializer = translatedInit
        ctx.symbols[stmt.name] = varSymbol
        ctx.varTypes[stmt.name] = varType
        return irVar
    }

    private fun translateExpressionStatement(stmt: JasExpressionStatement, ctx: TranslationContext): IrStatement {
        return translateExpression(stmt.expression, ctx)
    }

    private fun translateIf(stmt: JasIf, ctx: TranslationContext): IrWhen {
        val condition = translateExpression(stmt.condition, ctx)
        val thenResult = translateBlock(stmt.thenBody, ctx)
        val irWhen = IrNodeFactory.createWhen(0, 0, typeMapper.unitType(), IrStatementOrigin.IF)
        irWhen.branches.add(IrNodeFactory.createBranch(0, 0, condition, thenResult))
        if (stmt.elseBody != null) {
            val elseResult = translateBlock(stmt.elseBody!!, ctx)
            irWhen.branches.add(IrNodeFactory.createBranch(0, 0, typeMapper.trueConst(), elseResult))
        }
        return irWhen
    }

    private fun translateWhile(stmt: JasWhile, ctx: TranslationContext): IrWhileLoop {
        val irLoop = IrNodeFactory.createWhileLoop(0, 0, typeMapper.unitType(), IrStatementOrigin.WHILE_LOOP)
        ctx.loopStack.add(irLoop)
        irLoop.condition = translateExpression(stmt.condition, ctx)
        irLoop.body = translateBlock(stmt.body, ctx)
        ctx.loopStack.removeAt(ctx.loopStack.size - 1)
        return irLoop
    }

    private fun translateDoWhile(stmt: JasDoWhile, ctx: TranslationContext): IrDoWhileLoop {
        val irLoop = IrNodeFactory.createDoWhileLoop(0, 0, typeMapper.unitType(), IrStatementOrigin.DO_WHILE_LOOP)
        ctx.loopStack.add(irLoop)
        irLoop.body = translateBlock(stmt.body, ctx)
        irLoop.condition = translateExpression(stmt.condition, ctx)
        ctx.loopStack.removeAt(ctx.loopStack.size - 1)
        return irLoop
    }

    private fun translateFor(stmt: JasForStatement, ctx: TranslationContext): IrStatement {
        val block = IrNodeFactory.createBlock(0, 0, typeMapper.unitType(), IrStatementOrigin.FOR_LOOP)
        if (stmt.init != null) {
            val irInit = translateStatement(stmt.init!!, ctx)
            if (irInit != null) {
                (block.statements as MutableList<IrStatement>).add(irInit)
            }
        }
        val irLoop = IrNodeFactory.createWhileLoop(0, 0, typeMapper.unitType(), IrStatementOrigin.WHILE_LOOP)
        ctx.loopStack.add(irLoop)
        irLoop.condition = if (stmt.condition != null) translateExpression(stmt.condition!!, ctx) else typeMapper.trueConst()
        val loopBody = IrNodeFactory.createBlock(0, 0, typeMapper.unitType(), null)
        for (bodyStmt in stmt.body.statements) {
            val irBodyStmt = translateStatement(bodyStmt, ctx)
            if (irBodyStmt != null) {
                (loopBody.statements as MutableList<IrStatement>).add(irBodyStmt)
            }
        }
        if (stmt.update != null) {
            (loopBody.statements as MutableList<IrStatement>).add(translateExpression(stmt.update!!, ctx))
        }
        irLoop.body = loopBody
        ctx.loopStack.removeAt(ctx.loopStack.size - 1)
        (block.statements as MutableList<IrStatement>).add(irLoop)
        return block
    }

    private fun translateForIn(stmt: JasForInStatement, ctx: TranslationContext): IrStatement {
        val block = IrNodeFactory.createBlock(0, 0, typeMapper.unitType(), IrStatementOrigin.FOR_LOOP)
        val iterableExpr = translateExpression(stmt.iterable, ctx)

        val arrSymbol = IrVariableSymbolImpl()
        val arrVar = IrNodeFactory.createVariable(
            0, 0, IrDeclarationOrigin.DEFINED, Name.identifier("arr$"),
            iterableExpr.type, arrSymbol, true, false, false
        )
        arrVar.initializer = iterableExpr
        (block.statements as MutableList<IrStatement>).add(arrVar)

        val idxSymbol = IrVariableSymbolImpl()
        val idxVar = IrNodeFactory.createVariable(
            0, 0, IrDeclarationOrigin.DEFINED, Name.identifier("i$"),
            typeMapper.intType(), idxSymbol, true, false, false
        )
        idxVar.initializer = typeMapper.intConst(0)
        (block.statements as MutableList<IrStatement>).add(idxVar)

        val didRunSymbol = IrVariableSymbolImpl()
        val didRunVar = IrNodeFactory.createVariable(
            0, 0, IrDeclarationOrigin.DEFINED, Name.identifier("didRun$"),
            typeMapper.boolType(), didRunSymbol, true, false, false
        )
        didRunVar.initializer = typeMapper.falseConst()
        (block.statements as MutableList<IrStatement>).add(didRunVar)

        val elemSymbol = IrVariableSymbolImpl()
        val elemType = arrayElementType(iterableExpr.type)
        val elemVar = IrNodeFactory.createVariable(
            0, 0, IrDeclarationOrigin.DEFINED, Name.identifier(stmt.varName),
            elemType, elemSymbol, true, false, false
        )
        ctx.symbols[stmt.varName] = elemSymbol
        ctx.varTypes[stmt.varName] = elemType

        val irLoop = IrNodeFactory.createWhileLoop(0, 0, typeMapper.unitType(), IrStatementOrigin.WHILE_LOOP)
        ctx.loopStack.add(irLoop)

        val sizeSymbol = IrSimpleFunctionSymbolImpl()
        ctx.callNames[sizeSymbol] = "size"
        val sizeCall = IrNodeFactory.createCall(
            0, 0, typeMapper.intType(), null,
            arrayOf(IrNodeFactory.createGetValue(0, 0, iterableExpr.type, arrSymbol, null)),
            emptyArray(), sizeSymbol, null
        )
        val lessSymbol = IrSimpleFunctionSymbolImpl()
        ctx.callNames[lessSymbol] = "<"
        irLoop.condition = IrNodeFactory.createCall(
            0, 0, typeMapper.boolType(), null,
            arrayOf(
                IrNodeFactory.createGetValue(0, 0, typeMapper.intType(), idxSymbol, null),
                sizeCall
            ), emptyArray(), lessSymbol, null
        )

        val loopBody = IrNodeFactory.createBlock(0, 0, typeMapper.unitType(), null)
        (loopBody.statements as MutableList<IrStatement>).add(
            IrNodeFactory.createSetValue(0, 0, typeMapper.boolType(), didRunSymbol, IrStatementOrigin.EQ, typeMapper.trueConst())
        )

        val getSymbol = IrSimpleFunctionSymbolImpl()
        ctx.callNames[getSymbol] = "get"
        elemVar.initializer = IrNodeFactory.createCall(
            0, 0, elemType, null,
            arrayOf(
                IrNodeFactory.createGetValue(0, 0, iterableExpr.type, arrSymbol, null),
                IrNodeFactory.createGetValue(0, 0, typeMapper.intType(), idxSymbol, null)
            ), emptyArray(), getSymbol, null
        )
        (loopBody.statements as MutableList<IrStatement>).add(elemVar)

        for (bodyStmt in stmt.body.statements) {
            val irBodyStmt = translateStatement(bodyStmt, ctx)
            if (irBodyStmt != null) {
                (loopBody.statements as MutableList<IrStatement>).add(irBodyStmt)
            }
        }

        val incSymbol = IrSimpleFunctionSymbolImpl()
        ctx.callNames[incSymbol] = "inc"
        val incCall = IrNodeFactory.createCall(
            0, 0, typeMapper.intType(), null,
            arrayOf(IrNodeFactory.createGetValue(0, 0, typeMapper.intType(), idxSymbol, null)),
            emptyArray(), incSymbol, null
        )
        (loopBody.statements as MutableList<IrStatement>).add(
            IrNodeFactory.createSetValue(0, 0, typeMapper.intType(), idxSymbol, IrStatementOrigin.EQ, incCall)
        )

        irLoop.body = loopBody
        ctx.loopStack.removeAt(ctx.loopStack.size - 1)
        (block.statements as MutableList<IrStatement>).add(irLoop)
        if (stmt.thenBody != null || stmt.elseBody != null) {
            val afterLoop = IrNodeFactory.createWhen(0, 0, typeMapper.unitType(), IrStatementOrigin.IF)
            val didRun = IrNodeFactory.createGetValue(0, 0, typeMapper.boolType(), didRunSymbol, null)
            if (stmt.thenBody != null) {
                afterLoop.branches.add(IrNodeFactory.createBranch(0, 0, didRun, translateBlock(stmt.thenBody, ctx)))
            }
            if (stmt.elseBody != null) {
                afterLoop.branches.add(IrNodeFactory.createBranch(0, 0, typeMapper.trueConst(), translateBlock(stmt.elseBody, ctx)))
            }
            (block.statements as MutableList<IrStatement>).add(afterLoop)
        }
        return block
    }

    private fun arrayElementType(arrayType: IrType): IrType {
        return when (typeMapper.jvmDescriptorFromIrType(arrayType)) {
            "[I" -> typeMapper.intType()
            "[J" -> typeMapper.longType()
            "[F" -> typeMapper.floatType()
            "[D" -> typeMapper.doubleType()
            "[Z" -> typeMapper.boolType()
            "[B" -> typeMapper.mapTypeName("byte")
            "[S" -> typeMapper.mapTypeName("short")
            "[C" -> typeMapper.mapTypeName("char")
            else -> typeMapper.type("kotlin.Any")
        }
    }

    private fun translateSwitch(stmt: JasSwitch, ctx: TranslationContext): IrWhen {
        val irWhen = IrNodeFactory.createWhen(0, 0, typeMapper.unitType(), IrStatementOrigin.WHEN)
        val subject = translateExpression(stmt.expression, ctx)
        for (case in stmt.cases) {
            for (value in case.values) {
                val valExpr = translateExpression(value, ctx)
                val equalsSymbol = IrSimpleFunctionSymbolImpl()
                ctx.callNames[equalsSymbol] = "=="
                val cond = IrNodeFactory.createCall(
                    0, 0, typeMapper.boolType(), null,
                    arrayOf(subject, valExpr), emptyArray(), equalsSymbol, null
                )
                val result = translateBlock(case.body, ctx)
                irWhen.branches.add(IrNodeFactory.createBranch(0, 0, cond, result))
            }
            if (case.values.isEmpty()) {
                val result = translateBlock(case.body, ctx)
                irWhen.branches.add(IrNodeFactory.createBranch(0, 0, typeMapper.trueConst(), result))
            }
        }
        val hasDefault = stmt.cases.any { it.values.isEmpty() }
        if (!hasDefault) {
            irWhen.branches.add(IrNodeFactory.createBranch(0, 0, typeMapper.trueConst(), typeMapper.unitExpr()))
        }
        return irWhen
    }

    private fun translateReturn(stmt: JasReturn, ctx: TranslationContext): IrReturn {
        var value = if (stmt.value != null) translateExpression(stmt.value!!, ctx) else typeMapper.unitExpr()
        if (ctx.deferredBodies.isNotEmpty()) {
            val block = IrNodeFactory.createBlock(0, 0, value.type, null)
            for (body in ctx.deferredBodies.reversed()) {
                for (s in body.statements) {
                    (block.statements as MutableList<IrStatement>).add(s)
                }
            }
            (block.statements as MutableList<IrStatement>).add(value)
            value = block
        }
        val target = ctx.currentFunction?.symbol ?: IrSimpleFunctionSymbolImpl()
        return IrNodeFactory.createReturn(0, 0, value.type, value, target)
    }

    private fun translateBreak(stmt: JasBreakStatement, ctx: TranslationContext): IrStatement {
        if (ctx.loopStack.isNotEmpty()) {
            val loop = ctx.loopStack.last()
            return IrNodeFactory.createBreak(0, 0, typeMapper.unitType(), loop)
        }
        return typeMapper.unitExpr()
    }

    private fun translateContinue(stmt: JasContinueStatement, ctx: TranslationContext): IrStatement {
        if (ctx.loopStack.isNotEmpty()) {
            val loop = ctx.loopStack.last()
            return IrNodeFactory.createContinue(0, 0, typeMapper.unitType(), loop)
        }
        return typeMapper.unitExpr()
    }

    private fun translateThrow(stmt: JasThrow, ctx: TranslationContext): IrThrow {
        val value = translateExpression(stmt.expression, ctx)
        return IrNodeFactory.createThrow(0, 0, typeMapper.unitType(), value)
    }

    private fun translateTry(stmt: JasTry, ctx: TranslationContext): IrTry {
        val irTry = IrNodeFactory.createTry(0, 0, typeMapper.unitType())
        irTry.tryResult = translateBlock(stmt.body, ctx)
        for (catchClause in stmt.catches) {
            val paramType = typeMapper.mapType(catchClause.parameter.type)
            val paramSymbol = IrVariableSymbolImpl()
            val catchParam = IrNodeFactory.createVariable(
                0, 0, IrDeclarationOrigin.DEFINED,
                Name.identifier(catchClause.parameter.name),
                paramType, paramSymbol, true, false, false
            )
            val irCatch = IrNodeFactory.createCatch(0, 0, catchParam, null)
            ctx.symbols[catchClause.parameter.name] = paramSymbol
            irCatch.result = translateBlock(catchClause.body, ctx)
            irTry.catches.add(irCatch)
        }
        if (stmt.finallyBody != null) {
            irTry.finallyExpression = translateBlock(stmt.finallyBody!!, ctx)
        }
        return irTry
    }

    private fun translateAssignmentAsStatement(stmt: JasAssignment, ctx: TranslationContext): IrStatement {
        return translateAssignment(stmt, ctx)
    }

    private fun translateDefer(stmt: JasDefer, ctx: TranslationContext): IrBlock {
        val defBlock = translateBlock(stmt.body, ctx)
        ctx.deferredBodies.add(defBlock)
        return IrNodeFactory.createBlock(0, 0, typeMapper.unitType(), null)
    }

    private fun translateLock(stmt: JasLock, ctx: TranslationContext): IrBlock {
        val block = IrNodeFactory.createBlock(0, 0, typeMapper.unitType(), null)
        val monitorExpr = translateExpression(stmt.expression, ctx)
        val monitorSymbol = IrVariableSymbolImpl()
        val monitorVar = IrNodeFactory.createVariable(
            0, 0, IrDeclarationOrigin.DEFINED, Name.identifier("lock$"),
            monitorExpr.type, monitorSymbol, true, false, false
        )
        monitorVar.initializer = monitorExpr
        (block.statements as MutableList<IrStatement>).add(monitorVar)
        val tryBlock = IrNodeFactory.createTry(0, 0, typeMapper.unitType())
        tryBlock.tryResult = translateBlock(stmt.body, ctx)
        tryBlock.finallyExpression = typeMapper.unitExpr()
        (block.statements as MutableList<IrStatement>).add(tryBlock)
        return block
    }

    private fun translateAssert(stmt: JasAssert, ctx: TranslationContext): IrBlock {
        val block = IrNodeFactory.createBlock(0, 0, typeMapper.unitType(), null)
        val cond = translateExpression(stmt.condition, ctx)
        val irWhen = IrNodeFactory.createWhen(0, 0, typeMapper.unitType(), IrStatementOrigin.IF)
        val thenBlock = IrNodeFactory.createBlock(0, 0, typeMapper.unitType(), null)
        val assertErrorCtor = IrNodeFactory.createConstructorCall(
            0, 0, typeMapper.type("java.lang.AssertionError"), null,
            if (stmt.message != null) arrayOf(translateExpression(stmt.message!!, ctx)) else emptyArray(),
            emptyArray(), IrConstructorSymbolImpl(), SourceElement.NO_SOURCE, 0
        )
        val throwStmt = IrNodeFactory.createThrow(0, 0, typeMapper.unitType(), assertErrorCtor)
        (thenBlock.statements as MutableList<IrStatement>).add(throwStmt)
        irWhen.branches.add(IrNodeFactory.createBranch(0, 0, typeMapper.falseConst(), thenBlock))
        (block.statements as MutableList<IrStatement>).add(irWhen)
        return block
    }

    private fun translateYield(stmt: JasYield, ctx: TranslationContext): IrBlock {
        val block = IrNodeFactory.createBlock(0, 0, typeMapper.unitType(), null)
        val value = translateExpression(stmt.expression, ctx)
        val yieldSymbol = IrVariableSymbolImpl()
        val yieldVar = IrNodeFactory.createVariable(
            0, 0, IrDeclarationOrigin.DEFINED, Name.identifier("yield$"),
            value.type, yieldSymbol, true, false, false
        )
        yieldVar.initializer = value
        (block.statements as MutableList<IrStatement>).add(yieldVar)
        var retVal: IrExpression = IrNodeFactory.createGetValue(0, 0, value.type, yieldSymbol, null)
        if (ctx.deferredBodies.isNotEmpty()) {
            val retBlock = IrNodeFactory.createBlock(0, 0, value.type, null)
            for (body in ctx.deferredBodies.reversed()) {
                for (s in body.statements) {
                    (retBlock.statements as MutableList<IrStatement>).add(s)
                }
            }
            (retBlock.statements as MutableList<IrStatement>).add(retVal)
            retVal = retBlock
        }
        val target = ctx.currentFunction?.symbol ?: IrSimpleFunctionSymbolImpl()
        (block.statements as MutableList<IrStatement>).add(
            IrNodeFactory.createReturn(0, 0, retVal.type, retVal, target)
        )
        return block
    }

    private fun translateDestructuringDeclaration(stmt: JasDestructuringDeclaration, ctx: TranslationContext): IrBlock {
        val block = IrNodeFactory.createBlock(0, 0, typeMapper.unitType(), null)
        val initializer = translateExpression(stmt.initializer, ctx)
        val arrSymbol = IrVariableSymbolImpl()
        val arrVar = IrNodeFactory.createVariable(
            0, 0, IrDeclarationOrigin.DEFINED, Name.identifier("destr$"),
            typeMapper.unitType(), arrSymbol, true, false, false
        )
        arrVar.initializer = initializer
        (block.statements as MutableList<IrStatement>).add(arrVar)
        for ((i, binding) in stmt.bindings.withIndex()) {
            val varType = typeMapper.mapType(binding.type)
            val varSymbol = IrVariableSymbolImpl()
            val getSymbol = IrSimpleFunctionSymbolImpl()
            ctx.callNames[getSymbol] = "get"
            val getCall = IrNodeFactory.createCall(
                0, 0, varType, null,
                arrayOf(
                    IrNodeFactory.createGetValue(0, 0, typeMapper.unitType(), arrSymbol, null),
                    typeMapper.intConst(i)
                ), emptyArray(), getSymbol, null
            )
            val irVar = IrNodeFactory.createVariable(
                0, 0, IrDeclarationOrigin.DEFINED, Name.identifier(binding.name),
                varType, varSymbol, true, false, false
            )
            irVar.initializer = getCall
            ctx.symbols[binding.name] = varSymbol
            (block.statements as MutableList<IrStatement>).add(irVar)
        }
        return block
    }

    private fun translateMatch(stmt: JasMatch, ctx: TranslationContext): IrWhen {
        val subject = translateExpression(stmt.expression, ctx)
        val resultType = typeMapper.unitType()
        val irWhen = IrNodeFactory.createWhen(0, 0, resultType, IrStatementOrigin.WHEN)
        for (case in stmt.cases) {
            when (case.pattern) {
                is JasWildcardPattern -> {
                    val body = translateBlock(case.body, ctx)
                    irWhen.branches.add(IrNodeFactory.createBranch(0, 0, typeMapper.trueConst(), body))
                }
                is JasIntLiteral -> {
                    val patternExpr = translateExpression(case.pattern as JasExpression, ctx)
                    val equalsSymbol = IrSimpleFunctionSymbolImpl()
                    ctx.callNames[equalsSymbol] = "=="
                    val equalsCall = IrNodeFactory.createCall(
                        0, 0, typeMapper.boolType(), null,
                        arrayOf(subject, patternExpr), emptyArray(), equalsSymbol, null
                    )
                    val body = translateBlock(case.body, ctx)
                    if (case.guard != null) {
                        val guardExpr = translateExpression(case.guard!!, ctx)
                        val andSymbol = IrSimpleFunctionSymbolImpl()
                        ctx.callNames[andSymbol] = "&&"
                        val combinedCond = IrNodeFactory.createCall(
                            0, 0, typeMapper.boolType(), null,
                            arrayOf(equalsCall, guardExpr), emptyArray(),
                            andSymbol, null
                        )
                        irWhen.branches.add(IrNodeFactory.createBranch(0, 0, combinedCond, body))
                    } else {
                        irWhen.branches.add(IrNodeFactory.createBranch(0, 0, equalsCall, body))
                    }
                }
                is JasStringLiteral -> {
                    val patternExpr = translateExpression(case.pattern as JasExpression, ctx)
                    val equalsSymbol = IrSimpleFunctionSymbolImpl()
                    ctx.callNames[equalsSymbol] = "=="
                    val equalsCall = IrNodeFactory.createCall(
                        0, 0, typeMapper.boolType(), null,
                        arrayOf(subject, patternExpr), emptyArray(), equalsSymbol, null
                    )
                    val body = translateBlock(case.body, ctx)
                    irWhen.branches.add(IrNodeFactory.createBranch(0, 0, equalsCall, body))
                }
                is JasBoolLiteral -> {
                    val patternExpr = translateExpression(case.pattern as JasExpression, ctx)
                    val equalsSymbol = IrSimpleFunctionSymbolImpl()
                    ctx.callNames[equalsSymbol] = "=="
                    val equalsCall = IrNodeFactory.createCall(
                        0, 0, typeMapper.boolType(), null,
                        arrayOf(subject, patternExpr), emptyArray(), equalsSymbol, null
                    )
                    val body = translateBlock(case.body, ctx)
                    irWhen.branches.add(IrNodeFactory.createBranch(0, 0, equalsCall, body))
                }
                is JasIdentifier -> {
                    val body = IrNodeFactory.createBlock(0, 0, typeMapper.unitType(), null)
                    val idName = (case.pattern as JasIdentifier).name
                    val bindingSymbol = IrVariableSymbolImpl()
                    val bindingVar = IrNodeFactory.createVariable(
                        0, 0, IrDeclarationOrigin.DEFINED,
                        Name.identifier(idName),
                        typeMapper.unitType(), bindingSymbol, true, false, false
                    )
                    bindingVar.initializer = subject
                    (body.statements as MutableList<IrStatement>).add(bindingVar)
                    ctx.symbols[idName] = bindingSymbol
                    val caseBody = translateBlock(case.body, ctx)
                    for (s in caseBody.statements) {
                        (body.statements as MutableList<IrStatement>).add(s)
                    }
                    irWhen.branches.add(IrNodeFactory.createBranch(0, 0, typeMapper.trueConst(), body))
                }
                else -> {
                    val body = translateBlock(case.body, ctx)
                    irWhen.branches.add(IrNodeFactory.createBranch(0, 0, typeMapper.trueConst(), body))
                }
            }
        }
        return irWhen
    }

    // ── expressions ──

    private fun translateExpression(expr: JasExpression, ctx: TranslationContext): IrExpression {
        // JasMatch extends JasStatement, not JasExpression, so handled in translateStatement
        return when (expr) {
            is JasIntLiteral -> {
                val raw = expr.raw
                if (raw.endsWith("L") || raw.endsWith("l")) {
                    typeMapper.longConst(expr.value)
                } else {
                    typeMapper.intConst(expr.value.toInt())
                }
            }
            is JasFloatLiteral -> when {
                expr.raw.contains('.') || expr.raw.contains('e') || expr.raw.contains('E') -> typeMapper.doubleConst(expr.value)
                else -> typeMapper.floatConst(expr.value.toFloat())
            }
            is JasStringLiteral -> typeMapper.stringConst(expr.text)
            is JasBoolLiteral -> typeMapper.boolConst(expr.value)
            is JasNullLiteral -> typeMapper.nullConst()
            is JasIdentifier -> translateIdentifier(expr, ctx)
            is JasBinaryOp -> translateBinaryOp(expr, ctx)
            is JasUnaryOp -> translateUnaryOp(expr, ctx)
            is JasCall -> translateCall(expr, ctx)
            is JasPropertyAccess -> translatePropertyAccess(expr, ctx)
            is JasArrayAccess -> translateArrayAccess(expr, ctx)
            is JasNew -> translateNew(expr, ctx)
            is JasArrayCreation -> translateArrayCreation(expr, ctx)
            is JasLambdaExpr -> translateLambda(expr, ctx)
            is JasMethodReference -> translateMethodReference(expr, ctx)
            is JasInstanceOfExpr -> translateInstanceOf(expr, ctx)
            is JasCastExpr -> translateCast(expr, ctx)
            is JasNullCoalescing -> translateNullCoalescing(expr, ctx)
            is JasTernaryExpr -> translateTernary(expr, ctx)
            is JasStringTemplate -> translateStringTemplate(expr, ctx)
            is JasDictLiteral -> {
                val resultType = typeMapper.type("java.util.HashMap")
                val block = IrNodeFactory.createBlock(0, 0, resultType, null)
                val ctorSymbol = IrConstructorSymbolImpl()
                val irCtor = IrNodeFactory.createConstructorCall(
                    0, 0, resultType, null,
                    emptyArray(), emptyArray(), ctorSymbol,
                    SourceElement.NO_SOURCE, 0
                )
                val mapSymbol = IrVariableSymbolImpl()
                val mapVar = IrNodeFactory.createVariable(
                    0, 0, IrDeclarationOrigin.DEFINED, Name.identifier("map$"),
                    resultType, mapSymbol, true, false, false
                )
                mapVar.initializer = irCtor
                (block.statements as MutableList<IrStatement>).add(mapVar)
                for (entry in expr.entries) {
                    val key = translateExpression(entry.key, ctx)
                    val value = translateExpression(entry.value, ctx)
                    val putSymbol = IrSimpleFunctionSymbolImpl()
                    ctx.callNames[putSymbol] = "put"
                    val putCall = IrNodeFactory.createCall(
                        0, 0, typeMapper.unitType(), null,
                        arrayOf(
                            IrNodeFactory.createGetValue(0, 0, resultType, mapSymbol, null),
                            key, value
                        ), emptyArray(), putSymbol, null
                    )
                    (block.statements as MutableList<IrStatement>).add(putCall)
                }
                (block.statements as MutableList<IrStatement>).add(
                    IrNodeFactory.createGetValue(0, 0, resultType, mapSymbol, null)
                )
                block
            }
            is JasAssignment -> translateAssignment(expr, ctx)
            else -> typeMapper.unitExpr()
        }
    }

    private fun translateIdentifier(expr: JasIdentifier, ctx: TranslationContext): IrGetValue {
        val foundSymbol = ctx.symbols[expr.name]
        val symbol = foundSymbol ?: IrValueParameterSymbolImpl()
        val type = ctx.varTypes[expr.name] ?: typeMapper.unitType()
        return IrNodeFactory.createGetValue(0, 0, type, symbol, IrStatementOrigin.GET_PROPERTY)
    }

    private fun translateBinaryOp(expr: JasBinaryOp, ctx: TranslationContext): IrExpression {
        when (expr.op) {
            "&&" -> {
                val irWhen = IrNodeFactory.createWhen(0, 0, typeMapper.boolType(), IrStatementOrigin.WHEN)
                val leftExpr = translateExpression(expr.left, ctx)
                val rightExpr = translateExpression(expr.right, ctx)
                val thenBlock = IrNodeFactory.createBlock(0, 0, typeMapper.boolType(), null)
                (thenBlock.statements as MutableList<IrStatement>).add(rightExpr)
                irWhen.branches.add(IrNodeFactory.createBranch(0, 0, leftExpr, thenBlock))
                irWhen.branches.add(IrNodeFactory.createBranch(0, 0, typeMapper.trueConst(), typeMapper.falseConst()))
                return irWhen
            }
            "||" -> {
                val irWhen = IrNodeFactory.createWhen(0, 0, typeMapper.boolType(), IrStatementOrigin.WHEN)
                val leftExpr = translateExpression(expr.left, ctx)
                val rightExpr = translateExpression(expr.right, ctx)
                val elseBlock = IrNodeFactory.createBlock(0, 0, typeMapper.boolType(), null)
                (elseBlock.statements as MutableList<IrStatement>).add(rightExpr)
                irWhen.branches.add(IrNodeFactory.createBranch(0, 0, leftExpr, typeMapper.trueConst()))
                irWhen.branches.add(IrNodeFactory.createBranch(0, 0, typeMapper.trueConst(), elseBlock))
                return irWhen
            }
            else -> {
                val left = translateExpression(expr.left, ctx)
                val right = translateExpression(expr.right, ctx)
                val resultType = when (expr.op) {
                    "==", "!=", "<", ">", "<=", ">=" -> typeMapper.boolType()
                    else -> left.type
                }
                val stubSymbol = IrSimpleFunctionSymbolImpl()
                ctx.callNames[stubSymbol] = expr.op
                val call = IrNodeFactory.createCall(
                    0, 0, resultType, IrStatementOrigin.PLUS,
                    arrayOf(left, right), emptyArray(),
                    stubSymbol, null
                )
                return call
            }
        }
    }

    private fun translateUnaryOp(expr: JasUnaryOp, ctx: TranslationContext): IrExpression {
        val operand = translateExpression(expr.operand, ctx)
        val funcName = when (expr.op) {
            "-" -> "negate"
            "!" -> "not"
            "~" -> "inv"
            "+" -> "unaryPlus"
            "++" -> "inc"
            "--" -> "dec"
            else -> "unknown"
        }
        val symbol = ctx.functionSymbols[funcName] ?: IrSimpleFunctionSymbolImpl()
        ctx.callNames[symbol] = funcName
        return IrNodeFactory.createCall(
            0, 0, operand.type, null,
            arrayOf(operand), emptyArray(), symbol, null
        )
    }

    private fun translateCall(expr: JasCall, ctx: TranslationContext): IrExpression {
        val name = when (expr.target) {
            is JasIdentifier -> expr.target.name
            is JasPropertyAccess -> expr.target.property
            else -> "invoke"
        }
        if (expr.target is JasIdentifier && name in ctx.lambdaVarNames) {
            // Lambda variable call: generate virtual invoke on the closure object
            val receiverType = ctx.varTypes[name] ?: typeMapper.unitType()
            val invokeSymbol = IrSimpleFunctionSymbolImpl()
            ctx.callNames[invokeSymbol] = "invoke_lambda"
            val receiver = IrNodeFactory.createGetValue(
                0, 0, receiverType, ctx.symbols[name] ?: IrValueParameterSymbolImpl(),
                IrStatementOrigin.GET_PROPERTY
            )
            val argExprs = expr.args.map { translateExpression(it, ctx) }.toTypedArray()
            val returnType = ctx.functionReturnTypes["invoke"] ?: typeMapper.unitType()
            val call = IrNodeFactory.createCall(
                0, 0, returnType, IrStatementOrigin.INVOKE,
                argExprs, emptyArray(), invokeSymbol, null
            )
            (call as? org.jetbrains.kotlin.ir.expressions.impl.IrCallImpl)?.dispatchReceiver = receiver
            return call
        }
        // Legacy inline path for lambdas (backward compat during transition)
        if (expr.target is JasIdentifier) {
            val lambda = ctx.lambdas[name]
            if (lambda != null) {
                return translateLambdaInvocation(lambda, expr.args, ctx)
            }
        }
        val symbol = ctx.functionSymbols[name]
            ?: IrSimpleFunctionSymbolImpl()
        ctx.callNames[symbol] = if (expr.target is JasPropertyAccess) "v:$name" else name
        val args = mutableListOf<IrExpression>()
        if (expr.target is JasPropertyAccess) {
            args.add(translateExpression(expr.target.target, ctx))
        }
        args.addAll(expr.args.map { translateExpression(it, ctx) })
        val irArgs = args.toTypedArray()
        // Compute the call's return type: use inferred type arguments to substitute
        // type parameters in the function's return type.
        val baseReturnType = ctx.functionReturnTypes[name] ?: typeMapper.unitType()
        val returnType = if (expr.inferredTypeArgs != null && expr.inferredTypeArgs!!.isNotEmpty()) {
            substituteIrType(baseReturnType, expr.inferredTypeArgs!!)
        } else {
            baseReturnType
        }
        return IrNodeFactory.createCall(
            0, 0, returnType, IrStatementOrigin.INVOKE,
            irArgs, emptyArray<IrType>(), symbol, null
        )
    }

    private fun substituteIrType(
        type: IrType,
        inferredArgs: Map<String, JasType>
    ): IrType {
        if (type is IrSimpleType) {
            val classifier = type.classifier
            if (classifier is IrTypeParameterSymbol) {
                val tpName = try { classifier.owner.name.toString() } catch (e: Exception) { null }
                if (tpName != null) {
                    val jasArg = inferredArgs[tpName]
                    if (jasArg != null) {
                        return typeMapper.mapType(jasArg)
                    }
                }
            }
        }
        return type
    }

    private fun translateLambdaInvocation(lambda: JasLambdaExpr, args: List<JasExpression>, ctx: TranslationContext): IrExpression {
        val block = IrNodeFactory.createBlock(0, 0, typeMapper.unitType(), IrStatementOrigin.INVOKE)
        val previousSymbols = mutableMapOf<String, IrValueSymbol?>()
        val previousTypes = mutableMapOf<String, IrType?>()

        for ((index, param) in lambda.parameters.withIndex()) {
            val argExpr = args.getOrNull(index)?.let { translateExpression(it, ctx) } ?: typeMapper.unitExpr()
            val paramType = param.type?.let { typeMapper.mapType(it) } ?: argExpr.type
            val paramSymbol = IrVariableSymbolImpl()
            val paramVar = IrNodeFactory.createVariable(
                0, 0, IrDeclarationOrigin.DEFINED, Name.identifier(param.name),
                paramType, paramSymbol, true, false, false
            )
            paramVar.initializer = argExpr
            (block.statements as MutableList<IrStatement>).add(paramVar)
            previousSymbols[param.name] = ctx.symbols[param.name]
            previousTypes[param.name] = ctx.varTypes[param.name]
            ctx.symbols[param.name] = paramSymbol
            ctx.varTypes[param.name] = paramType
        }

        val result = when (val last = lambda.body.statements.lastOrNull()) {
            is JasExpressionStatement -> translateExpression(last.expression, ctx)
            is JasReturn -> last.value?.let { translateExpression(it, ctx) } ?: typeMapper.unitExpr()
            else -> typeMapper.unitExpr()
        }

        for (param in lambda.parameters) {
            val oldSymbol = previousSymbols[param.name]
            val oldType = previousTypes[param.name]
            if (oldSymbol != null) ctx.symbols[param.name] = oldSymbol else ctx.symbols.remove(param.name)
            if (oldType != null) ctx.varTypes[param.name] = oldType else ctx.varTypes.remove(param.name)
        }

        (block.statements as MutableList<IrStatement>).add(result)
        return IrNodeFactory.createBlock(0, 0, result.type, block.origin).also {
            (it.statements as MutableList<IrStatement>).addAll(block.statements)
        }
    }

    private fun translatePropertyAccess(expr: JasPropertyAccess, ctx: TranslationContext): IrExpression {
        val target = translateExpression(expr.target, ctx)
        val fieldSymbol = ctx.fieldSymbols[expr.property] ?: IrFieldSymbolImpl()
        val fieldType = when {
            expr.property == "length" && isArrayIrType(target.type) -> typeMapper.intType()
            else -> ctx.fieldTypes[expr.property] ?: typeMapper.unitType()
        }
        val irGetField = IrNodeFactory.createGetField(
            0, 0, fieldType, fieldSymbol, null, IrStatementOrigin.GET_PROPERTY
        )
        irGetField.receiver = target
        return irGetField
    }

    private fun isArrayIrType(type: IrType): Boolean =
        typeMapper.jvmDescriptorFromIrType(type).startsWith("[")

    private fun translateArrayAccess(expr: JasArrayAccess, ctx: TranslationContext): IrExpression {
        val target = translateExpression(expr.target, ctx)
        val index = translateExpression(expr.index, ctx)
        val getSymbol = ctx.functionSymbols["get"] ?: IrSimpleFunctionSymbolImpl()
        ctx.callNames[getSymbol] = "get"
        return IrNodeFactory.createCall(
            0, 0, typeMapper.unitType(), null,
            arrayOf(target, index), emptyArray(), getSymbol, null
        )
    }

    private fun translateNew(expr: JasNew, ctx: TranslationContext): IrExpression {
        val ctorSymbol = IrConstructorSymbolImpl()
        val type = typeMapper.mapType(expr.type)
        val args = expr.args.map { translateExpression(it, ctx) }.toTypedArray()
        ctx.constructorTypes[ctorSymbol] = "object"
        return IrNodeFactory.createConstructorCall(
            0, 0, type, null,
            args, emptyArray(), ctorSymbol,
            SourceElement.NO_SOURCE, 0
        )
    }

    private fun translateArrayCreation(expr: JasArrayCreation, ctx: TranslationContext): IrExpression {
        val resultType = typeMapper.mapType(JasArrayType(expr.type))
        val elemDesc = typeMapper.jvmDescriptorFromType(expr.type)
        if (expr.init is JasArrayInitValues) {
            val values = expr.init.values
            val block = IrNodeFactory.createBlock(0, 0, resultType, null)
            val ctorSymbol = IrConstructorSymbolImpl()
            ctx.constructorTypes[ctorSymbol] = "array:$elemDesc"
            val dimExpr = typeMapper.intConst(values.size)
            val createArray = IrNodeFactory.createConstructorCall(
                0, 0, resultType, null,
                arrayOf(dimExpr), emptyArray(), ctorSymbol,
                SourceElement.NO_SOURCE, 0
            )
            val arrSymbol = IrVariableSymbolImpl()
            val arrVar = IrNodeFactory.createVariable(
                0, 0, IrDeclarationOrigin.DEFINED, Name.identifier("arr$"),
                resultType, arrSymbol, true, false, false
            )
            arrVar.initializer = createArray
            (block.statements as MutableList<IrStatement>).add(arrVar)
            for ((i, v) in values.withIndex()) {
                val valExpr = translateExpression(v, ctx)
                val setSymbol = IrSimpleFunctionSymbolImpl()
                ctx.callNames[setSymbol] = "arraySet"
                val setCall = IrNodeFactory.createCall(
                    0, 0, resultType, null,
                    arrayOf(
                        IrNodeFactory.createGetValue(0, 0, resultType, arrSymbol, null),
                        typeMapper.intConst(i),
                        valExpr
                    ), emptyArray(), setSymbol, null
                )
                (block.statements as MutableList<IrStatement>).add(setCall)
            }
            (block.statements as MutableList<IrStatement>).add(
                IrNodeFactory.createGetValue(0, 0, resultType, arrSymbol, null)
            )
            return block
        } else {
            val ctorSymbol = IrConstructorSymbolImpl()
            ctx.constructorTypes[ctorSymbol] = "array:$elemDesc"
            val dims = expr.dims.map { translateExpression(it, ctx) }
            return IrNodeFactory.createConstructorCall(
                0, 0, resultType, null,
                dims.toTypedArray(), emptyArray(), ctorSymbol,
                SourceElement.NO_SOURCE, 0
            )
        }
    }

    private fun translateLambda(expr: JasLambdaExpr, ctx: TranslationContext): IrExpression {
        val lambdaId = ctx.lambdaIdCounter++
        val className = "Lambda\$$lambdaId"
        val classSymbol = IrClassSymbolImpl()
        val irClass = factory.createClass(
            0, 0, IrDeclarationOrigin.DEFINED,
            Name.identifier(className),
            DescriptorVisibilities.PUBLIC, classSymbol,
            ClassKind.CLASS, Modality.FINAL,
            false, false, false, false, false, false, false, false,
            SourceElement.NO_SOURCE
        )
        ctx.currentFile?.declarations?.add(irClass)

        // Add a default constructor for the anonymous class
        val ctorSymbol = IrConstructorSymbolImpl()
        val irCtor = factory.createConstructor(
            0, 0, IrDeclarationOrigin.DEFINED,
            Name.special("<init>"),
            DescriptorVisibilities.PUBLIC,
            false, false, typeMapper.unitType(),
            ctorSymbol, true, false, null
        )
        val ctorBody = factory.createBlockBody(0, 0)
        irCtor.body = ctorBody
        irClass.declarations.add(irCtor)

        // Determine return type from the last expression
        val inferredReturnType = when (val last = expr.body.statements.lastOrNull()) {
            is JasExpressionStatement -> typeMapper.mapType(expr.parameters.firstOrNull()?.type)
            is JasReturn -> last.value?.let { typeMapper.mapType(expr.parameters.firstOrNull()?.type) }
            else -> typeMapper.unitType()
        }
        val returnType = typeMapper.mapType(expr.parameters.firstOrNull()?.type) ?: typeMapper.unitType()

        // Determine the lambda return type from the body
        val lambdaReturnType = when (val last = expr.body.statements.lastOrNull()) {
            is JasReturn -> {
                val returnVal = last.value
                if (returnVal != null) {
                    // Infer from literal types
                    when (returnVal) {
                        is JasIntLiteral -> typeMapper.intType()
                        is JasFloatLiteral -> typeMapper.doubleType()
                        is JasStringLiteral -> typeMapper.stringType()
                        is JasBoolLiteral -> typeMapper.boolType()
                        is JasBinaryOp -> typeMapper.intType()
                        else -> typeMapper.intType()
                    }
                } else typeMapper.unitType()
            }
            is JasExpressionStatement -> typeMapper.intType()
            else -> typeMapper.unitType()
        }
        
        // Create the invoke method
        val invokeSymbol = IrSimpleFunctionSymbolImpl()
        val irFunc = factory.createSimpleFunction(
            0, 0, IrDeclarationOrigin.DEFINED,
            Name.identifier("invoke"),
            DescriptorVisibilities.PUBLIC, false, false, lambdaReturnType,
            Modality.FINAL, invokeSymbol,
            false, false, false, false, false, null, false
        )
        ctx.functionSymbols["invoke"] = invokeSymbol
        ctx.functionReturnTypes["invoke"] = lambdaReturnType

        // Add parameters to invoke method
        for ((i, param) in expr.parameters.withIndex()) {
            val paramType = typeMapper.mapType(param.type)
            val paramSymbol = IrValueParameterSymbolImpl()
            val irParam = factory.createValueParameter(
                0, 0, IrDeclarationOrigin.DEFINED,
                Name.identifier(param.name), paramType,
                param.vararg, paramSymbol, i, null, false, false, false
            )
            irFunc.valueParameters = irFunc.valueParameters + irParam
            ctx.symbols[param.name] = paramSymbol
            ctx.varTypes[param.name] = paramType
        }

        // Translate lambda body
        val body = factory.createBlockBody(0, 0)
        val prevFunc = ctx.currentFunction
        ctx.currentFunction = irFunc
        for (stmt in expr.body.statements) {
            val irStmt = translateStatement(stmt, ctx)
            if (irStmt != null) {
                (body.statements as MutableList<IrStatement>).add(irStmt)
            }
        }
        // Add return if needed
        val lastStmt = expr.body.statements.lastOrNull()
        if (lastStmt !is JasReturn && !body.statements.any { it is IrReturn }) {
            val retVal = when (lastStmt) {
                is JasExpressionStatement -> translateExpression(lastStmt.expression, ctx)
                else -> typeMapper.unitExpr()
            }
            if (retVal !is IrConstImpl || retVal.kind != IrConstKind.Null) {
                // For expression-style lambdas, return the last expression
            }
        }
        ctx.currentFunction = prevFunc
        irFunc.body = body
        irClass.declarations.add(irFunc)

        // Create a constructor call to instantiate the anonymous class
        val instanceCtorSymbol = IrConstructorSymbolImpl()
        val ctorReturnType = typeMapper.mapType(JasNamedType(className))
        ctx.constructorTypes[instanceCtorSymbol] = "object"
        return IrNodeFactory.createConstructorCall(
            0, 0, ctorReturnType, null,
            emptyArray(), emptyArray(), instanceCtorSymbol,
            SourceElement.NO_SOURCE, 0
        )
    }

    private fun translateMethodReference(expr: JasMethodReference, ctx: TranslationContext): IrExpression {
        val lambdaId = ctx.lambdaIdCounter++
        val className = "Lambda\$$lambdaId"
        val classSymbol = IrClassSymbolImpl()
        val irClass = factory.createClass(
            0, 0, IrDeclarationOrigin.DEFINED,
            Name.identifier(className),
            DescriptorVisibilities.PUBLIC, classSymbol,
            ClassKind.CLASS, Modality.FINAL,
            false, false, false, false, false, false, false, false,
            SourceElement.NO_SOURCE
        )
        ctx.currentFile?.declarations?.add(irClass)

        val ctorSymbol = IrConstructorSymbolImpl()
        val irCtor = factory.createConstructor(
            0, 0, IrDeclarationOrigin.DEFINED,
            Name.special("<init>"),
            DescriptorVisibilities.PUBLIC,
            false, false, typeMapper.unitType(),
            ctorSymbol, true, false, null
        )
        val ctorBody = factory.createBlockBody(0, 0)
        irCtor.body = ctorBody
        irClass.declarations.add(irCtor)

        val paramTypes = ctx.functionParamTypes[expr.method] ?: emptyList()
        val returnType = ctx.functionReturnTypes[expr.method] ?: typeMapper.unitType()
        val invokeSymbol = IrSimpleFunctionSymbolImpl()
        ctx.functionSymbols["invoke"] = invokeSymbol
        ctx.functionReturnTypes["invoke"] = returnType

        val irFunc = factory.createSimpleFunction(
            0, 0, IrDeclarationOrigin.DEFINED,
            Name.identifier("invoke"),
            DescriptorVisibilities.PUBLIC, false, false, returnType,
            Modality.FINAL, invokeSymbol,
            false, false, false, false, false, null, false
        )

        val funcSymbol = ctx.functionSymbols[expr.method] ?: IrSimpleFunctionSymbolImpl()
        val paramSymbols = mutableListOf<IrValueParameterSymbol>()
        for ((i, pt) in paramTypes.withIndex()) {
            val paramSymbol = IrValueParameterSymbolImpl()
            paramSymbols.add(paramSymbol)
            val irParam = factory.createValueParameter(
                0, 0, IrDeclarationOrigin.DEFINED,
                Name.identifier("p$i"), pt,
                false, paramSymbol, i, null, false, false, false
            )
            irFunc.valueParameters = irFunc.valueParameters + irParam
            ctx.symbols["p$i"] = paramSymbol
            ctx.varTypes["p$i"] = pt
        }

        val body = factory.createBlockBody(0, 0)
        val bodyStatements = body.statements as MutableList<IrStatement>

        val callResult: IrExpression
        // If target is a function name identifier (not a variable/receiver), treat as static reference
        val isStaticFunctionRef = ctx.functionSymbols.containsKey(expr.method)
        if (expr.target != null && !isStaticFunctionRef) {
            val receiver = translateExpression(expr.target!!, ctx)
            val argExprs = paramSymbols.mapIndexed { i, _ ->
                val ps = ctx.symbols["p$i"] ?: IrValueParameterSymbolImpl()
                IrNodeFactory.createGetValue(0, 0, paramTypes.getOrElse(i) { typeMapper.unitType() }, ps, null)
            }.toTypedArray()
            val callArgs = arrayOfNulls<IrExpression>(argExprs.size + 1)
            callArgs[0] = receiver
            argExprs.copyInto(callArgs, 1)
            val call = IrNodeFactory.createCall(
                0, 0, returnType, IrStatementOrigin.INVOKE,
                callArgs.filterNotNull().toTypedArray(), emptyArray(), funcSymbol, null
            )
            ctx.callNames[funcSymbol] = "v:${expr.method}"
            callResult = call
        } else {
            val call = IrNodeFactory.createCall(
                0, 0, returnType, IrStatementOrigin.INVOKE,
                paramSymbols.mapIndexed { i, _ ->
                    val ps = ctx.symbols["p$i"] ?: IrValueParameterSymbolImpl()
                    IrNodeFactory.createGetValue(0, 0, paramTypes.getOrElse(i) { typeMapper.unitType() }, ps, null)
                }.toTypedArray(), emptyArray(), funcSymbol, null
            )
            ctx.callNames[funcSymbol] = expr.method
            callResult = call
        }

        // Wrap call in IrReturn for non-unit return types so the backend emits a return instruction
        if (returnType != typeMapper.unitType()) {
            bodyStatements.add(IrNodeFactory.createReturn(0, 0, returnType, callResult, irFunc.symbol))
        } else {
            bodyStatements.add(callResult)
        }
        irFunc.body = body
        irClass.declarations.add(irFunc)

        for (i in paramTypes.indices) {
            ctx.symbols.remove("p$i")
            ctx.varTypes.remove("p$i")
        }

        val instanceCtorSymbol = IrConstructorSymbolImpl()
        val ctorReturnType = typeMapper.mapType(JasNamedType(className))
        ctx.constructorTypes[instanceCtorSymbol] = "object"
        return IrNodeFactory.createConstructorCall(
            0, 0, ctorReturnType, null,
            emptyArray(), emptyArray(), instanceCtorSymbol,
            SourceElement.NO_SOURCE, 0
        )
    }

    private fun translateInstanceOf(expr: JasInstanceOfExpr, ctx: TranslationContext): IrExpression {
        val operand = translateExpression(expr.expression, ctx)
        val targetType = typeMapper.mapType(expr.type)
        return IrTypeOperatorCallImpl(
            0, 0, typeMapper.boolType(),
            IrTypeOperator.INSTANCEOF,
            targetType, operand
        )
    }

    private fun translateCast(expr: JasCastExpr, ctx: TranslationContext): IrExpression {
        val operand = translateExpression(expr.expression, ctx)
        val targetType = typeMapper.mapType(expr.type)
        return IrTypeOperatorCallImpl(
            0, 0, targetType,
            IrTypeOperator.CAST,
            targetType, operand
        )
    }

    private fun translateNullCoalescing(expr: JasNullCoalescing, ctx: TranslationContext): IrExpression {
        val leftExpr = translateExpression(expr.left, ctx)
        val rightExpr = translateExpression(expr.right, ctx)
        val resultType = rightExpr.type
        val block = IrNodeFactory.createBlock(0, 0, resultType, null)
        val tempSymbol = IrVariableSymbolImpl()
        val tempVar = IrNodeFactory.createVariable(
            0, 0, IrDeclarationOrigin.DEFINED, Name.identifier("tmp$"),
            leftExpr.type, tempSymbol, true, false, false
        )
        tempVar.initializer = leftExpr
        (block.statements as MutableList<IrStatement>).add(tempVar)
        val irWhen = IrNodeFactory.createWhen(0, 0, resultType, IrStatementOrigin.ELVIS)
        val notNullCheck = IrTypeOperatorCallImpl(
            0, 0, typeMapper.boolType(),
            IrTypeOperator.IMPLICIT_NOTNULL,
            leftExpr.type,
            IrNodeFactory.createGetValue(0, 0, leftExpr.type, tempSymbol, null)
        )
        irWhen.branches.add(IrNodeFactory.createBranch(
            0, 0, notNullCheck,
            IrNodeFactory.createGetValue(0, 0, leftExpr.type, tempSymbol, null)
        ))
        irWhen.branches.add(IrNodeFactory.createBranch(0, 0, typeMapper.trueConst(), rightExpr))
        (block.statements as MutableList<IrStatement>).add(irWhen)
        return block
    }

    private fun translateTernary(expr: JasTernaryExpr, ctx: TranslationContext): IrExpression {
        val cond = translateExpression(expr.condition, ctx)
        val thenExpr = translateExpression(expr.thenExpr, ctx)
        val elseExpr = translateExpression(expr.elseExpr, ctx)
        val resultType = thenExpr.type
        val irWhen = IrNodeFactory.createWhen(0, 0, resultType, IrStatementOrigin.WHEN)
        irWhen.branches.add(IrNodeFactory.createBranch(0, 0, cond, thenExpr))
        irWhen.branches.add(IrNodeFactory.createBranch(0, 0, typeMapper.trueConst(), elseExpr))
        return irWhen
    }

    private fun translateStringTemplate(expr: JasStringTemplate, ctx: TranslationContext): IrExpression {
        val parts = mutableListOf<IrExpression>()
        for (part in expr.parts) {
            when (part) {
                is JasTemplateLiteral -> parts.add(typeMapper.stringConst(part.text))
                is JasTemplateExpr -> parts.add(translateExpression(part.expr, ctx))
            }
        }
        if (parts.isEmpty()) return typeMapper.stringConst("")
        // Desugar f"Hello {name}!" → "Hello + name + "!"
        var result: IrExpression = parts.first()
        for (i in 1 until parts.size) {
            val stubSymbol = IrSimpleFunctionSymbolImpl()
            ctx.callNames[stubSymbol] = "string_plus"
            result = IrNodeFactory.createCall(
                0, 0, typeMapper.stringType(), IrStatementOrigin.PLUS,
                arrayOf(result, parts[i]), emptyArray(), stubSymbol, null
            )
        }
        return result
    }

    private fun translateAssignment(expr: JasAssignment, ctx: TranslationContext): IrExpression {
        val value = translateExpression(expr.value, ctx)
        if (expr.target is JasIdentifier) {
            val foundSymbol = ctx.symbols[expr.target.name]
            if (foundSymbol != null) {
                if (expr.op != "=") {
                    val op = expr.op.removeSuffix("=")
                    val getX = IrNodeFactory.createGetValue(0, 0, value.type, foundSymbol, IrStatementOrigin.GET_PROPERTY)
                    val stubSymbol = IrSimpleFunctionSymbolImpl()
                    ctx.callNames[stubSymbol] = op
                    val binOp = IrNodeFactory.createCall(
                        0, 0, value.type, IrStatementOrigin.PLUS,
                        arrayOf(getX, value), emptyArray(), stubSymbol, null
                    )
                    return IrNodeFactory.createSetValue(
                        0, 0, value.type, foundSymbol,
                        IrStatementOrigin.EQ, binOp
                    )
                }
                return IrNodeFactory.createSetValue(
                    0, 0, typeMapper.unitType(), foundSymbol,
                    IrStatementOrigin.EQ, value
                )
            }
        } else if (expr.target is JasPropertyAccess) {
            val pa = expr.target as JasPropertyAccess
            val targetExpr = translateExpression(pa.target, ctx)
            val fieldSymbol = ctx.fieldSymbols[pa.property] ?: IrFieldSymbolImpl()
            if (expr.op != "=") {
                val op = expr.op.removeSuffix("=")
                val getField = IrNodeFactory.createGetField(0, 0, value.type, fieldSymbol, null, IrStatementOrigin.GET_PROPERTY)
                getField.receiver = targetExpr
                val stubSymbol = IrSimpleFunctionSymbolImpl()
                ctx.callNames[stubSymbol] = op
                val binOp = IrNodeFactory.createCall(
                    0, 0, value.type, IrStatementOrigin.PLUS,
                    arrayOf(getField, value), emptyArray(), stubSymbol, null
                )
                val targetExpr2 = translateExpression(pa.target, ctx)
                val setField = IrNodeFactory.createSetField(0, 0, value.type, fieldSymbol, null, IrStatementOrigin.EQ)
                setField.receiver = targetExpr2
                setField.value = binOp
                return setField
            }
            val setField = IrNodeFactory.createSetField(0, 0, value.type, fieldSymbol, null, IrStatementOrigin.EQ)
            setField.receiver = targetExpr
            setField.value = value
            return setField
        } else if (expr.target is JasArrayAccess) {
            val aa = expr.target as JasArrayAccess
            val arrayExpr = translateExpression(aa.target, ctx)
            val indexExpr = translateExpression(aa.index, ctx)
            val resultType = value.type
            val block = IrNodeFactory.createBlock(0, 0, resultType, null)
            val arrSymbol = IrVariableSymbolImpl()
            val arrVar = IrNodeFactory.createVariable(0, 0, IrDeclarationOrigin.DEFINED, Name.identifier("aa$"), arrayExpr.type, arrSymbol, true, false, false)
            arrVar.initializer = arrayExpr
            (block.statements as MutableList<IrStatement>).add(arrVar)
            val idxSymbol = IrVariableSymbolImpl()
            val idxVar = IrNodeFactory.createVariable(0, 0, IrDeclarationOrigin.DEFINED, Name.identifier("ai$"), indexExpr.type, idxSymbol, true, false, false)
            idxVar.initializer = indexExpr
            (block.statements as MutableList<IrStatement>).add(idxVar)
            if (expr.op != "=") {
                val op = expr.op.removeSuffix("=")
                val getSymbol = IrSimpleFunctionSymbolImpl()
                ctx.callNames[getSymbol] = "get"
                val getCall = IrNodeFactory.createCall(
                    0, 0, resultType, null,
                    arrayOf(
                        IrNodeFactory.createGetValue(0, 0, arrayExpr.type, arrSymbol, null),
                        IrNodeFactory.createGetValue(0, 0, indexExpr.type, idxSymbol, null)
                    ), emptyArray(), getSymbol, null
                )
                val stubSymbol = IrSimpleFunctionSymbolImpl()
                ctx.callNames[stubSymbol] = op
                val binOp = IrNodeFactory.createCall(
                    0, 0, resultType, IrStatementOrigin.PLUS,
                    arrayOf(getCall, value), emptyArray(), stubSymbol, null
                )
                val valSymbol = IrVariableSymbolImpl()
                val valVar = IrNodeFactory.createVariable(0, 0, IrDeclarationOrigin.DEFINED, Name.identifier("av$"), resultType, valSymbol, true, false, false)
                valVar.initializer = binOp
                (block.statements as MutableList<IrStatement>).add(valVar)
                val setSymbol = IrSimpleFunctionSymbolImpl()
                ctx.callNames[setSymbol] = "arraySet"
                val setCall = IrNodeFactory.createCall(
                    0, 0, resultType, null,
                    arrayOf(
                        IrNodeFactory.createGetValue(0, 0, arrayExpr.type, arrSymbol, null),
                        IrNodeFactory.createGetValue(0, 0, indexExpr.type, idxSymbol, null),
                        IrNodeFactory.createGetValue(0, 0, resultType, valSymbol, null)
                    ), emptyArray(), setSymbol, null
                )
                (block.statements as MutableList<IrStatement>).add(setCall)
                (block.statements as MutableList<IrStatement>).add(IrNodeFactory.createGetValue(0, 0, resultType, valSymbol, null))
            } else {
                val valSymbol = IrVariableSymbolImpl()
                val valVar = IrNodeFactory.createVariable(0, 0, IrDeclarationOrigin.DEFINED, Name.identifier("av$"), resultType, valSymbol, true, false, false)
                valVar.initializer = value
                (block.statements as MutableList<IrStatement>).add(valVar)
                val setSymbol = IrSimpleFunctionSymbolImpl()
                ctx.callNames[setSymbol] = "arraySet"
                val setCall = IrNodeFactory.createCall(
                    0, 0, resultType, null,
                    arrayOf(
                        IrNodeFactory.createGetValue(0, 0, arrayExpr.type, arrSymbol, null),
                        IrNodeFactory.createGetValue(0, 0, indexExpr.type, idxSymbol, null),
                        IrNodeFactory.createGetValue(0, 0, resultType, valSymbol, null)
                    ), emptyArray(), setSymbol, null
                )
                (block.statements as MutableList<IrStatement>).add(setCall)
                (block.statements as MutableList<IrStatement>).add(IrNodeFactory.createGetValue(0, 0, resultType, valSymbol, null))
            }
            return block
        }
        return value
    }
}
