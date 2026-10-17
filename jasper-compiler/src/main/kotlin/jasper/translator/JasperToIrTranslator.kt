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

/**
 * Method names of the standard-library function-object protocols
 * (`Mapper.apply`, `Predicate.test`, `Reducer.reduce`, `Comparator.compare`,
 * `Action.accept`). They are semantic protocols: a call to one of these names on
 * a closure variable is routed to the closure's generated `invoke` method rather
 * than dispatched through the (abstract) protocol interface.
 */
private val PROTOCOL_METHODS = setOf("apply", "test", "reduce", "compare", "accept")

data class TranslationResult(
    val irFiles: List<IrFile>,
    val callNames: Map<IrSimpleFunctionSymbol, String> = emptyMap(),
    val constructorTypes: Map<IrConstructorSymbol, String> = emptyMap(),
    val typeMapper: TypeMapper? = null,
    val functionDescriptors: Map<IrSimpleFunctionSymbol, String> = emptyMap(),
    val parameterDescriptors: Map<IrValueSymbol, String> = emptyMap(),
    val classSuperclassNames: Map<String, String> = emptyMap(), // className -> jvmInternalName
    val classInterfaceNames: Map<String, List<String>> = emptyMap(), // className -> jvmInternalName list
    val interfaceDeclNames: Set<String> = emptySet(), // names of types declared as interfaces
    // Field symbols that must be emitted/accessed as JVM statics (interface
    // constants). Backend uses this for `GETSTATIC`/`PUTSTATIC` and for the
    // `public static final` field flags.
    val staticFieldSymbols: Set<IrFieldSymbol> = emptySet()
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
    // Closure variable name -> its generated `invoke` return type. Keyed per
    // variable (not the global `functionReturnTypes["invoke"]`) so two closures
    // with different shapes in the same scope do not clobber each other.
    val lambdaInvokeReturnTypes: MutableMap<String, IrType> = mutableMapOf(),
    // Member-return index: JVM-style class/interface name -> member name ->
    // return type. Used by the iteration protocol lowering to type the loop
    // variable from the concrete iterable's `next()` member.
    val memberReturnTypesByType: MutableMap<String, MutableMap<String, IrType>> = mutableMapOf(),
    val classSymbols: MutableMap<String, IrClassSymbol> = mutableMapOf(),
    val fieldSymbols: MutableMap<String, IrFieldSymbol> = mutableMapOf(),
    val fieldTypes: MutableMap<String, IrType> = mutableMapOf(),
    // Per-class field indexes (JVM-style class name -> field name -> symbol/type).
    // Needed so a property read/write resolves the field owner from the
    // *receiver's* declared type rather than globally by name; without this two
    // classes that share a property name (e.g. a mapper's source and target)
    // would collide on the last-registered symbol.
    val fieldSymbolsByClass: MutableMap<String, MutableMap<String, IrFieldSymbol>> = mutableMapOf(),
    val fieldTypesByClass: MutableMap<String, MutableMap<String, IrType>> = mutableMapOf(),
    var currentClassName: String = "",
    val loopStack: MutableList<IrLoop> = mutableListOf(),
    val deferredBodies: MutableList<IrBlock> = mutableListOf(),
    var currentFunction: IrSimpleFunction? = null,
    var currentFile: IrFile? = null,
    var lambdaIdCounter: Int = 0,
    var currentSuperclassName: String = "",
    var currentPkg: String = "",
    val classSuperclassNames: MutableMap<String, String> = mutableMapOf(),
    val interfaceNames: MutableMap<String, MutableList<String>> = mutableMapOf(), // class/interface -> implemented/extends interface names
    val interfaceDeclNames: MutableSet<String> = mutableSetOf(), // names of types declared as interfaces
    // Class names that provide a `hash()` member (handwritten or synthesized
    // from @EqualsAndHashCode); used by the late @EqualsAndHashCode body
    // generator to decide between `.hash()` and `.hashCode()` for nested
    // object fields.
    val hashableClassNames: MutableSet<String> = mutableSetOf(),
    // Field symbols for interface constants (`public static final` fields).
    val staticFieldSymbols: MutableSet<IrFieldSymbol> = mutableSetOf()
)

class JasperToIrTranslator {
    private val typeMapper = TypeMapper()
    private val factory = IrFactory(StageController())

    fun translate(ast: JasSourceFile, symbolTable: SymbolTable? = null): TranslationResult {
        val ctx = TranslationContext(typeMapper = typeMapper)
        val pkg = ast.packageName ?: ""
        ctx.currentPkg = pkg
        typeMapper.currentPkg = pkg
        predeclareRuntimeBuiltins(ctx)
        collectHashableClassNames(listOf(ast), ctx)
        collectMemberReturnTypes(ast, ctx)
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
            interfaceDeclNames = ctx.interfaceDeclNames.toSet(),
            staticFieldSymbols = ctx.staticFieldSymbols.toSet())
    }

    fun translateAll(asts: List<JasSourceFile>, symbolTable: SymbolTable? = null): TranslationResult {
        val ctx = TranslationContext(typeMapper = typeMapper)
        predeclareRuntimeBuiltins(ctx)
        for (ast in asts) {
            ctx.currentPkg = ast.packageName ?: ""
            typeMapper.currentPkg = ctx.currentPkg
            collectHashableClassNames(listOf(ast), ctx)
            collectMemberReturnTypes(ast, ctx)
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
            interfaceDeclNames = ctx.interfaceDeclNames.toSet(),
            staticFieldSymbols = ctx.staticFieldSymbols.toSet())
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

    /**
     * Declares the signatures of runtime helpers the lowering of synthesized
     * member bodies relies on. `hashCode()` is the universal JVM object-hash
     * entry point (inherited by every class); its return type must be known so
     * member calls to it lower with an `I` return descriptor instead of `V`.
     */
    private fun predeclareRuntimeBuiltins(ctx: TranslationContext) {
        ctx.functionReturnTypes["hashCode"] = typeMapper.intType()
        ctx.functionParamTypes["hashCode"] = emptyList()
        // `equals` is the universal value-equality entry point (`String.equals`,
        // user-class `equals(other: any)` synthesized by @EqualsAndHashCode, and
        // any class that overrides Object.equals). Declaring its shape up front
        // lets a member call `s.equals(o)` lower with an `Object` parameter and a
        // `bool` (Z) return instead of falling back to `(String)V`.
        ctx.functionReturnTypes["equals"] = typeMapper.boolType()
        ctx.functionParamTypes["equals"] = listOf(typeMapper.type("kotlin.Any"))
    }

    /**
     * Records the class names that provide a `hash()` member — handwritten or
     * synthesized from `@EqualsAndHashCode`. The late equality body generator
     * calls `.hash()` on nested fields of such classes (value-based) and
     * `.hashCode()` on everything else (identity/default).
     */
    private fun collectHashableClassNames(asts: List<JasSourceFile>, ctx: TranslationContext) {
        for (ast in asts) {
            for (decl in ast.declarations) {
                if (decl !is JasClass) continue
                val hasHashFunction = decl.members.any { it is JasFunction && it.name == "hash" }
                val hasEqualityAnnotation =
                    decl.modifiers.filterIsInstance<JasAnnotationUse>().any { simpleAnnotationName(it.name) == "EqualsAndHashCode" } ||
                        decl.members.filterIsInstance<JasProperty>().any { prop ->
                            prop.modifiers.filterIsInstance<JasAnnotationUse>().any { simpleAnnotationName(it.name) == "EqualsAndHashCode" }
                        }
                if (hasHashFunction || hasEqualityAnnotation) {
                    ctx.hashableClassNames.add(decl.name)
                }
            }
        }
    }

    private fun simpleAnnotationName(qualified: String): String = qualified.substringAfterLast('.')

    /**
     * Indexes member return types per declaring type (JVM-style name). The
     * iteration protocol lowering reads `next()` from this index so the loop
     * variable is typed from the *concrete* iterable rather than the global,
     * last-writer-wins `functionReturnTypes` map.
     */
    private fun collectMemberReturnTypes(ast: JasSourceFile, ctx: TranslationContext) {
        val pkg = ast.packageName ?: ""
        fun record(typeName: String, members: List<JasDeclaration>) {
            val qualified = if (pkg.isNotEmpty()) "$pkg/$typeName" else typeName
            val index = ctx.memberReturnTypesByType.getOrPut(qualified) { mutableMapOf() }
            for (member in members) {
                if (member is JasFunction) {
                    index.putIfAbsent(member.name, typeMapper.mapType(member.returnType))
                }
            }
        }
        for (decl in ast.declarations) {
            when (decl) {
                is JasClass -> record(decl.name, decl.members)
                is JasInterface -> record(decl.name, decl.members)
                else -> {}
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
        val bodyStatements: List<JasStatement>? = when {
            func.syntheticAccessor != null -> syntheticAccessorBody(func.syntheticAccessor!!, func)
            func.syntheticEquality != null -> syntheticEqualityBody(func.syntheticEquality!!, ctx)
            func.syntheticMapper != null -> syntheticMapperBody(func.syntheticMapper!!)
            func.body != null -> func.body!!.statements
            else -> null
        }
        if (bodyStatements != null) {
            val prevFunc = ctx.currentFunction
            ctx.currentFunction = irFunc
            val body = irFunc.body as? IrBlockBody ?: factory.createBlockBody(0, 0)
            scopedDefers(ctx) {
                for (stmt in bodyStatements) {
                    val irStmt = translateStatement(stmt, ctx)
                    if (irStmt != null) {
                        (body.statements as MutableList<IrStatement>).add(irStmt)
                    }
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

    /**
     * Late body generation for annotation-synthesized accessors.
     *
     * The *signature* (`getFoo()` / `setFoo(value)`) was synthesized by the
     * annotation processing phase before type checking. By the time lowering runs,
     * the property's backing field symbol (`ctx.fieldSymbols`) and the setter
     * parameter symbol are available, so the body is lowered through the normal
     * statement pipeline (`return this.prop` / `this.prop = value`).
     */
    private fun syntheticAccessorBody(
        accessor: JasSyntheticAccessor,
        func: JasFunction
    ): List<JasStatement> {
        val thisField = JasPropertyAccess(JasIdentifier("this"), accessor.propertyName)
        return if (accessor.isGetter) {
            listOf(JasReturn(thisField))
        } else {
            val paramName = func.parameters.firstOrNull()?.name ?: "value"
            listOf(JasExpressionStatement(JasAssignment(thisField, JasIdentifier(paramName))))
        }
    }

    /**
     * Late body generation for `@EqualsAndHashCode`-synthesized members
     * ([JasSyntheticEquality]). The signature (`equals(other: any): bool` /
     * `hash(): int32`) was synthesized by the annotation processing phase
     * before type checking; these bodies are lowered here directly because the
     * source syntax has no type-test expression (`other is Foo`), so the
     * instanceof guard can only be expressed at the AST→IR boundary.
     */
    private fun syntheticEqualityBody(eq: JasSyntheticEquality, ctx: TranslationContext): List<JasStatement> =
        if (eq.isEquals) syntheticEqualsBody(eq) else syntheticHashBody(eq, ctx)

    /**
     * `equals` body:
     * ```
     * if (!(other is Foo)) { return false; }
     * var other$ = cast other as Foo;
     * return this.f1 == o.f1 && ...;
     * ```
     * The instanceof guard also rejects `null` (instanceof null is false), so
     * different-class comparisons return false without throwing.
     */
    private fun syntheticEqualsBody(eq: JasSyntheticEquality): List<JasStatement> {
        val clsType = JasNamedType(eq.className)
        val other = JasIdentifier("other")
        val guard = JasIf(
            condition = JasUnaryOp("!", JasInstanceOfExpr(other, clsType), prefix = true),
            thenBody = JasBlock(listOf(JasReturn(JasBoolLiteral(false)))),
            elseBody = null
        )
        val local = JasVariableStatement("other$", clsType, JasCastExpr(other, clsType))
        val comparisons = eq.fields.map { field ->
            JasBinaryOp(
                JasPropertyAccess(JasIdentifier("this"), field.name),
                "==",
                JasPropertyAccess(JasIdentifier("other$"), field.name)
            )
        }
        val result = comparisons.reduceOrNull { acc, next -> JasBinaryOp(acc, "&&", next) }
            ?: JasBoolLiteral(true)
        return listOf(guard, local, JasReturn(result))
    }

    /**
     * `hash` body: seed and mix every participating field with
     * `h = h * 31 + fieldHash`, then `return h`. Deterministic, no randomness
     * and no object-identity dependence beyond what `equals` itself treats as
     * value (e.g. nested objects that only have identity equality).
     */
    private fun syntheticHashBody(eq: JasSyntheticEquality, ctx: TranslationContext): List<JasStatement> {
        val stmts = mutableListOf<JasStatement>()
        stmts.add(JasVariableStatement("h", JasPrimitiveType("int32"), JasIntLiteral(17, "17")))
        for (field in eq.fields) {
            val mixed = JasBinaryOp(
                JasBinaryOp(JasIdentifier("h"), "*", JasIntLiteral(31, "31")),
                "+",
                fieldHashExpr(field, ctx)
            )
            stmts.add(JasExpressionStatement(JasAssignment(JasIdentifier("h"), mixed)))
        }
        stmts.add(JasReturn(JasIdentifier("h")))
        return stmts
    }

    /** int32 contribution of one field to the `hash` accumulator. */
    private fun fieldHashExpr(field: JasEqualityField, ctx: TranslationContext): JasExpression {
        val thisField = JasPropertyAccess(JasIdentifier("this"), field.name)
        return when (val type = field.type) {
            is JasPrimitiveType -> when (type.name.lowercase()) {
                "bool" -> JasTernaryExpr(thisField, JasIntLiteral(1, "1"), JasIntLiteral(0, "0"))
                "int64" -> {
                    // XOR-fold the high 32 bits into the low 32 bits.
                    val hi = JasCastExpr(
                        JasBinaryOp(thisField, ">>>", JasIntLiteral(32, "32")),
                        JasPrimitiveType("int32")
                    )
                    val lo = JasCastExpr(thisField, JasPrimitiveType("int32"))
                    JasBinaryOp(hi, "^", lo)
                }
                "float32", "float64" -> JasCastExpr(thisField, JasPrimitiveType("int32"))
                else -> thisField // int32 / char / byte / short mix as ints
            }
            is JasStringType -> nullGuardedMemberHash(thisField, "hashCode")
            is JasArrayType -> {
                // Arrays compare by identity in `equals`; their length is a
                // deterministic, identity-free contribution.
                nullGuardedMemberHash(thisField, "length")
            }
            is JasNamedType -> {
                val hashMethod = if (type.name.substringAfterLast('.') in ctx.hashableClassNames) "hash" else "hashCode"
                nullGuardedMemberHash(thisField, hashMethod)
            }
            else -> nullGuardedMemberHash(thisField, "hashCode")
        }
    }

    /**
     * `field == null ? 0 : field.member()` — null fields hash to 0 instead of
     * throwing. `length` is lowered as ARRAYLENGTH by the property-access path.
     */
    private fun nullGuardedMemberHash(fieldAccess: JasPropertyAccess, member: String): JasExpression {
        val useExpr: JasExpression = if (member == "length") {
            JasPropertyAccess(fieldAccess, "length")
        } else {
            JasCall(JasPropertyAccess(fieldAccess, member), emptyList())
        }
        return JasTernaryExpr(
            JasBinaryOp(fieldAccess, "==", JasNullLiteral),
            JasIntLiteral(0, "0"),
            useExpr
        )
    }

    /**
     * Late body generation for annotation-synthesized mappers
     * ([JasSyntheticMapper]). The mapper method signature was synthesized by the
     * annotation processing phase; here the simple property-to-property body is
     * lowered through the normal statement pipeline:
     *
     * ```
     * var result = new Target();
     * result.t1 = src.s1;
     * ...
     * return result;
     * ```
     */
    private fun syntheticMapperBody(mapper: JasSyntheticMapper): List<JasStatement> {
        val resultName = "result$"
        val stmts = mutableListOf<JasStatement>()
        stmts.add(JasVariableStatement(resultName, mapper.targetType, JasNew(mapper.targetType, emptyList())))
        for (assignment in mapper.assignments) {
            stmts.add(JasExpressionStatement(
                JasAssignment(
                    JasPropertyAccess(JasIdentifier(resultName), assignment.targetProperty),
                    JasPropertyAccess(JasIdentifier(mapper.parameterName), assignment.sourceProperty)
                )
            ))
        }
        stmts.add(JasReturn(JasIdentifier(resultName)))
        return stmts
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
        // Track the enclosing class so `this.prop` and receiver-typed property
        // accesses resolve their field owner during member translation.
        val qualifiedClassName = if (ctx.currentPkg.isNotEmpty()) "${ctx.currentPkg}/${cls.name}" else cls.name
        val previousClassName = ctx.currentClassName
        ctx.currentClassName = qualifiedClassName
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
        ctx.currentClassName = previousClassName
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
            scopedDefers(ctx) {
                for (stmt in ctor.body!!.statements) {
                    val irStmt = translateStatement(stmt, ctx)
                    if (irStmt != null) {
                        (body.statements as MutableList<IrStatement>).add(irStmt)
                    }
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
            // An interface constant (`const MAX_ROWS: int32 = 100;`) becomes a
            // `public static final` field carrying its declared value. It must
            // not be silently dropped (the previous behavior), because a missing
            // declaration is exactly the kind of failure that hides a compiler
            // bug as a user error.
            if (member is JasProperty && member.isConst) {
                irCls.declarations.add(translateInterfaceConstant(member, ctx))
                continue
            }
            val irMember = translateClassMember(member, ctx, symbol)
            if (irMember != null) {
                irCls.declarations.add(irMember)
            }
        }
        return irCls
    }

    /**
     * Lowers an interface constant to a JVM `public static final` field.
     *
     * The declaration-site initializer is preserved on the [IrField] so the
     * backend can emit a `ConstantValue` attribute (for literals) or an
     * `<clinit>` assignment. The field symbol is registered globally *and* in
     * the static-field set so member reads (`Statement.MAX_ROWS`) lower to
     * `GETSTATIC` rather than `GETFIELD`.
     */
    private fun translateInterfaceConstant(prop: JasProperty, ctx: TranslationContext): IrField {
        val propType = typeMapper.mapType(prop.type)
        val fieldSymbol = IrFieldSymbolImpl()
        val irField = factory.createField(
            0, 0,
            IrDeclarationOrigin.DEFINED,
            Name.identifier(prop.name),
            DescriptorVisibilities.PUBLIC,
            fieldSymbol,
            propType,
            true,   // isFinal
            true,   // isStatic
            false   // isLateinit
        )
        if (prop.initializer != null) {
            irField.initializer = factory.createExpressionBody(0, 0, translateExpression(prop.initializer!!, ctx))
        }
        ctx.fieldSymbols[prop.name] = fieldSymbol
        ctx.fieldTypes[prop.name] = propType
        if (ctx.currentClassName.isNotEmpty()) {
            ctx.fieldSymbolsByClass.getOrPut(ctx.currentClassName) { mutableMapOf() }[prop.name] = fieldSymbol
            ctx.fieldTypesByClass.getOrPut(ctx.currentClassName) { mutableMapOf() }[prop.name] = propType
        }
        ctx.staticFieldSymbols.add(fieldSymbol)
        return irField
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
        if (ctx.currentClassName.isNotEmpty()) {
            ctx.fieldSymbolsByClass.getOrPut(ctx.currentClassName) { mutableMapOf() }[prop.name] = fieldSymbol
            ctx.fieldTypesByClass.getOrPut(ctx.currentClassName) { mutableMapOf() }[prop.name] = propType
        }
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
            scopedDefers(ctx) {
                for (stmt in accessor.body!!.statements) {
                    val irStmt = translateStatement(stmt, ctx)
                    if (irStmt != null) {
                        (body.statements as MutableList<IrStatement>).add(irStmt)
                    }
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
            is JasStatementIncrement -> translateStatementIncrement(stmt, ctx)
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
        // A closure assigned to a protocol-typed variable is lowered to an
        // anonymous class; the variable must carry that concrete class type so a
        // later protocol call routes to the generated `invoke` method instead of
        // dispatching on the (abstract) declared protocol interface.
        val isClosureInit = stmt.initializer is JasLambdaExpr || stmt.initializer is JasMethodReference
        if (isClosureInit) {
            // Snapshot the just-generated closure's `invoke` return type under the
            // variable's name; the global `functionReturnTypes["invoke"]` entry is
            // overwritten by every subsequent lambda.
            ctx.functionReturnTypes["invoke"]?.let { ctx.lambdaInvokeReturnTypes[stmt.name] = it }
        }
        val varType = if (isClosureInit && translatedInit != null) translatedInit.type
            else if (stmt.type != null) typeMapper.mapType(stmt.type)
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

    private fun translateStatementIncrement(stmt: JasStatementIncrement, ctx: TranslationContext): IrStatement {
        return translatePostfixIncDecAsStatement(stmt.increment, ctx)
    }

    private fun translatePostfixIncDecAsStatement(expr: JasPostfixIncDec, ctx: TranslationContext): IrStatement {
        return translatePostfixIncDec(expr, ctx, needsResult = false)
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
            val updateExpr = stmt.update!!
            val updateStmt = if (updateExpr is JasPostfixIncDec) {
                translatePostfixIncDecAsStatement(updateExpr, ctx)
            } else {
                translateExpression(updateExpr, ctx)
            }
            (loopBody.statements as MutableList<IrStatement>).add(updateStmt)
        }
        irLoop.body = loopBody
        ctx.loopStack.removeAt(ctx.loopStack.size - 1)
        (block.statements as MutableList<IrStatement>).add(irLoop)
        return block
    }

    private fun translateForIn(stmt: JasForInStatement, ctx: TranslationContext): IrStatement {
        val iterableExpr = translateExpression(stmt.iterable, ctx)
        val desc = typeMapper.jvmDescriptorFromIrType(iterableExpr.type)
        // Arrays keep the index-based lowering. A declared object type is treated
        // as an instance of the iteration protocol (`hasNext()` / `next()`); any
        // other/unknown shape falls back to the array lowering to preserve the
        // previous behaviour.
        return when {
            desc.startsWith("[") -> translateForInArray(stmt, iterableExpr, ctx)
            desc.startsWith("L") -> translateForInIterator(stmt, iterableExpr, ctx)
            else -> translateForInArray(stmt, iterableExpr, ctx)
        }
    }

    private fun translateForInArray(stmt: JasForInStatement, iterableExpr: IrExpression, ctx: TranslationContext): IrStatement {
        val block = IrNodeFactory.createBlock(0, 0, typeMapper.unitType(), IrStatementOrigin.FOR_LOOP)

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

    /**
     * Lowers `for (x in iterable)` over an object type using the standard-library
     * iteration protocol:
     *
     * ```
     * var it$ = iterable;
     * var didRun$ = false;
     * while (it$.hasNext()) {
     *     didRun$ = true;
     *     var x = it$.next();
     *     <body>
     * }
     * if (didRun$) { <then> } else { <else> }
     * ```
     *
     * The element type comes from the `next()` member discovered while lowering
     * the concrete iterable type. This consumes the protocol through the existing
     * `for ... in` syntax; no new grammar is introduced.
     */
    private fun translateForInIterator(stmt: JasForInStatement, iterableExpr: IrExpression, ctx: TranslationContext): IrStatement {
        val block = IrNodeFactory.createBlock(0, 0, typeMapper.unitType(), IrStatementOrigin.FOR_LOOP)

        val itSymbol = IrVariableSymbolImpl()
        val itVar = IrNodeFactory.createVariable(
            0, 0, IrDeclarationOrigin.DEFINED, Name.identifier("it$"),
            iterableExpr.type, itSymbol, true, false, false
        )
        itVar.initializer = iterableExpr
        (block.statements as MutableList<IrStatement>).add(itVar)

        val didRunSymbol = IrVariableSymbolImpl()
        val didRunVar = IrNodeFactory.createVariable(
            0, 0, IrDeclarationOrigin.DEFINED, Name.identifier("didRun$"),
            typeMapper.boolType(), didRunSymbol, true, false, false
        )
        didRunVar.initializer = typeMapper.falseConst()
        (block.statements as MutableList<IrStatement>).add(didRunVar)

        // Type the loop variable from the concrete iterable's `next()` member.
        val iterableClass = typeMapper.irTypeClassName(iterableExpr.type)
        val elemType = (iterableClass?.let { ctx.memberReturnTypesByType[it]?.get("next") })
            ?: ctx.functionReturnTypes["next"]
            ?: typeMapper.type("kotlin.Any")
        val elemSymbol = IrVariableSymbolImpl()
        val elemVar = IrNodeFactory.createVariable(
            0, 0, IrDeclarationOrigin.DEFINED, Name.identifier(stmt.varName),
            elemType, elemSymbol, true, false, false
        )
        ctx.symbols[stmt.varName] = elemSymbol
        ctx.varTypes[stmt.varName] = elemType

        val irLoop = IrNodeFactory.createWhileLoop(0, 0, typeMapper.unitType(), IrStatementOrigin.WHILE_LOOP)
        ctx.loopStack.add(irLoop)

        val hasNextSymbol = IrSimpleFunctionSymbolImpl()
        ctx.callNames[hasNextSymbol] = "v:hasNext"
        irLoop.condition = IrNodeFactory.createCall(
            0, 0, typeMapper.boolType(), null,
            arrayOf(IrNodeFactory.createGetValue(0, 0, iterableExpr.type, itSymbol, null)),
            emptyArray(), hasNextSymbol, null
        )

        val loopBody = IrNodeFactory.createBlock(0, 0, typeMapper.unitType(), null)
        (loopBody.statements as MutableList<IrStatement>).add(
            IrNodeFactory.createSetValue(0, 0, typeMapper.boolType(), didRunSymbol, IrStatementOrigin.EQ, typeMapper.trueConst())
        )

        val nextSymbol = IrSimpleFunctionSymbolImpl()
        ctx.callNames[nextSymbol] = "v:next"
        elemVar.initializer = IrNodeFactory.createCall(
            0, 0, elemType, null,
            arrayOf(IrNodeFactory.createGetValue(0, 0, iterableExpr.type, itSymbol, null)),
            emptyArray(), nextSymbol, null
        )
        (loopBody.statements as MutableList<IrStatement>).add(elemVar)

        for (bodyStmt in stmt.body.statements) {
            val irBodyStmt = translateStatement(bodyStmt, ctx)
            if (irBodyStmt != null) {
                (loopBody.statements as MutableList<IrStatement>).add(irBodyStmt)
            }
        }

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
            // Register the catch variable's declared type so `e.message()` and
            // other member accesses on the caught value resolve their receiver
            // type. Without this the receiver falls back to `Unit` and the
            // backend emits `L<package>/void;` as the owner (NoClassDefFoundError).
            ctx.varTypes[catchClause.parameter.name] = paramType
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

    /**
     * Runs [body] with a fresh `defer` scope, restoring the enclosing scope
     * afterwards. `defer` belongs strictly to the function that declares it;
     * without this the deferred bodies accumulate in the shared translation
     * context and leak into every later function's `return`.
     */
    private inline fun <T> scopedDefers(ctx: TranslationContext, body: () -> T): T {
        val enclosing = ctx.deferredBodies.toMutableList()
        ctx.deferredBodies.clear()
        try {
            return body()
        } finally {
            ctx.deferredBodies.clear()
            ctx.deferredBodies.addAll(enclosing)
        }
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
            is JasCharLiteral -> typeMapper.charConst(expr.value.toChar())
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
            is JasAssignmentIncrement -> translateAssignmentIncrement(expr, ctx)
            is JasPostfixIncDec -> translatePostfixIncDec(expr, ctx, needsResult = true)
            else -> typeMapper.unitExpr()
        }
    }

    private fun translateIdentifier(expr: JasIdentifier, ctx: TranslationContext): IrGetValue {
        val foundSymbol = ctx.symbols[expr.name]
        val symbol = foundSymbol ?: IrValueParameterSymbolImpl()
        val type = ctx.varTypes[expr.name]
            // `this` (and `super`) is not a declared local; its receiver type is
            // the enclosing class. Without this the receiver defaults to `Unit`
            // and a member call `this.m()` lowers to `invokevirtual <pkg>/void.m`.
            ?: if ((expr.name == "this" || expr.name == "super") && ctx.currentClassName.isNotEmpty()) {
                typeMapper.type(ctx.currentClassName)
            } else {
                typeMapper.unitType()
            }
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

    /**
     * Emits a virtual `invoke` call on a closure variable (the anonymous class
     * produced for a lambda or method reference). Shared by direct lambda calls
     * (`f(1)`) and function-object protocol calls (`f.apply(1)`).
     */
    private fun emitClosureInvoke(
        receiverName: String,
        args: List<JasExpression>,
        ctx: TranslationContext
    ): IrExpression {
        val receiverType = ctx.varTypes[receiverName] ?: typeMapper.unitType()
        val invokeSymbol = IrSimpleFunctionSymbolImpl()
        ctx.callNames[invokeSymbol] = "invoke_lambda"
        val receiver = IrNodeFactory.createGetValue(
            0, 0, receiverType, ctx.symbols[receiverName] ?: IrValueParameterSymbolImpl(),
            IrStatementOrigin.GET_PROPERTY
        )
        val argExprs = args.map { translateExpression(it, ctx) }.toTypedArray()
        val returnType = ctx.lambdaInvokeReturnTypes[receiverName]
            ?: ctx.functionReturnTypes["invoke"]
            ?: typeMapper.unitType()
        val call = IrNodeFactory.createCall(
            0, 0, returnType, IrStatementOrigin.INVOKE,
            argExprs, emptyArray(), invokeSymbol, null
        )
        (call as? org.jetbrains.kotlin.ir.expressions.impl.IrCallImpl)?.dispatchReceiver = receiver
        return call
    }

    private fun translateCall(expr: JasCall, ctx: TranslationContext): IrExpression {
        val name = when (expr.target) {
            is JasIdentifier -> expr.target.name
            is JasPropertyAccess -> expr.target.property
            else -> "invoke"
        }
        if (expr.target is JasIdentifier && name in ctx.lambdaVarNames) {
            // Lambda variable call: generate virtual invoke on the closure object
            return emitClosureInvoke(name, expr.args, ctx)
        }
        // Function-object protocol call: a lambda stored in a variable is lowered
        // to an anonymous class. A call to the protocol's method name on that
        // variable (`m.apply`, `p.test`, `r.reduce`, `c.compare`, `a.accept`) is
        // routed to the closure's generated `invoke` method. The protocol itself
        // is semantic, not a JVM interface dispatch.
        if (expr.target is JasPropertyAccess && expr.target.property in PROTOCOL_METHODS) {
            val receiverTarget = expr.target.target
            if (receiverTarget is JasIdentifier && receiverTarget.name in ctx.lambdaVarNames) {
                return emitClosureInvoke(receiverTarget.name, expr.args, ctx)
            }
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
        args.addAll(expr.args.map { translateExpression(it, ctx) }.toTypedArray())
        // Member-call arguments are widened to the declared parameter type when
        // the declared parameter is `any` (java/lang/Object) but the argument
        // has a more specific type. Without this the backend derives the call
        // descriptor from the *argument* type and misses the `Object`-typed
        // parameter (e.g. synthesized `equals(other: any)`).
        if (expr.target is JasPropertyAccess) {
            val declaredParams = ctx.functionParamTypes[name]
            if (declaredParams != null && declaredParams.size == expr.args.size) {
                for (i in expr.args.indices) {
                    val declared = declaredParams[i]
                    val argExpr = args[i + 1]
                    if (typeMapper.jvmDescriptorFromIrType(declared) == "Ljava/lang/Object;" &&
                        typeMapper.jvmDescriptorFromIrType(argExpr.type) != "Ljava/lang/Object;"
                    ) {
                        args[i + 1] = IrTypeOperatorCallImpl(
                            0, 0, declared, IrTypeOperator.CAST, declared, argExpr
                        )
                    }
                }
            }
        }
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
        val fieldSymbol = resolveFieldSymbol(expr.property, expr.target, target, ctx)
        val fieldType = resolveFieldType(expr.property, expr.target, target, ctx) ?: typeMapper.unitType()
        val irGetField = IrNodeFactory.createGetField(
            0, 0, fieldType, fieldSymbol, null, IrStatementOrigin.GET_PROPERTY
        )
        irGetField.receiver = target
        return irGetField
    }

    /**
     * JVM-style class key identifying the field owner for a property receiver.
     * `this` / `super` map to the enclosing class; everything else uses the
     * receiver's declared IR type.
     */
    private fun receiverClassKey(target: JasExpression, irReceiver: IrExpression, ctx: TranslationContext): String? {
        if (target is JasIdentifier && (target.name == "this" || target.name == "super")) {
            return ctx.currentClassName.ifEmpty { null }
        }
        return typeMapper.irTypeClassName(irReceiver.type)
    }

    /**
     * Resolves the field symbol for a property access from the receiver's type,
     * falling back to the global by-name index when the receiver type cannot be
     * mapped to a declared class. This keeps two classes that share a property
     * name (e.g. a mapper source and target) from colliding.
     */
    private fun resolveFieldSymbol(
        property: String,
        target: JasExpression,
        irReceiver: IrExpression,
        ctx: TranslationContext
    ): IrFieldSymbol {
        val key = receiverClassKey(target, irReceiver, ctx)
        if (key != null) {
            ctx.fieldSymbolsByClass[key]?.get(property)?.let { return it }
        }
        return ctx.fieldSymbols[property] ?: IrFieldSymbolImpl()
    }

    private fun resolveFieldType(
        property: String,
        target: JasExpression,
        irReceiver: IrExpression,
        ctx: TranslationContext
    ): IrType? {
        if (property == "length" && isArrayIrType(irReceiver.type)) return typeMapper.intType()
        val key = receiverClassKey(target, irReceiver, ctx)
        if (key != null) {
            ctx.fieldTypesByClass[key]?.get(property)?.let { return it }
        }
        return ctx.fieldTypes[property]
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
            // A lambda block whose last statement is a plain expression yields no
            // value (block expressions are not implicit returns in Jasper); its
            // `invoke` is void. This is what makes `Action.accept` lambdas valid.
            is JasExpressionStatement -> typeMapper.unitType()
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
        scopedDefers(ctx) {
            for (stmt in expr.body.statements) {
                val irStmt = translateStatement(stmt, ctx)
                if (irStmt != null) {
                    (body.statements as MutableList<IrStatement>).add(irStmt)
                }
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
            val fieldSymbol = resolveFieldSymbol(pa.property, pa.target, targetExpr, ctx)
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

    private fun translatePostfixIncDec(expr: JasPostfixIncDec, ctx: TranslationContext, needsResult: Boolean): IrExpression {
        val target = expr.target
        val op = expr.op
        val one = typeMapper.intConst(1)
        if (target is JasIdentifier) {
            val foundSymbol = ctx.symbols[target.name]
            if (foundSymbol != null) {
                val getX = IrNodeFactory.createGetValue(0, 0, typeMapper.intType(), foundSymbol, IrStatementOrigin.GET_PROPERTY)
                val stubSymbol = IrSimpleFunctionSymbolImpl()
                ctx.callNames[stubSymbol] = if (op == "++") "+" else "-"
                if (needsResult) {
                    val resultSymbol = IrVariableSymbolImpl()
                    val resultVar = IrNodeFactory.createVariable(
                        0, 0, IrDeclarationOrigin.DEFINED, Name.identifier("inc$"),
                        typeMapper.intType(), resultSymbol, true, false, false
                    )
                    resultVar.initializer = getX
                    val outerBlock = IrNodeFactory.createBlock(0, 0, typeMapper.intType(), null)
                    (outerBlock.statements as MutableList<IrStatement>).add(resultVar)
                    val binOp = IrNodeFactory.createCall(
                        0, 0, typeMapper.intType(), IrStatementOrigin.PLUS,
                        arrayOf(getX, one), emptyArray(), stubSymbol, null
                    )
                    val setX = IrNodeFactory.createSetValue(
                        0, 0, typeMapper.unitType(), foundSymbol,
                        IrStatementOrigin.EQ, binOp
                    )
                    val innerBlock = IrNodeFactory.createBlock(0, 0, typeMapper.intType(), null)
                    (innerBlock.statements as MutableList<IrStatement>).add(setX)
                    val getResult = IrNodeFactory.createGetValue(0, 0, typeMapper.intType(), resultSymbol, IrStatementOrigin.GET_PROPERTY)
                    (innerBlock.statements as MutableList<IrStatement>).add(getResult)
                    (outerBlock.statements as MutableList<IrStatement>).add(innerBlock)
                    return outerBlock
                } else {
                    val binOp = IrNodeFactory.createCall(
                        0, 0, typeMapper.intType(), IrStatementOrigin.PLUS,
                        arrayOf(getX, one), emptyArray(), stubSymbol, null
                    )
                    val setX = IrNodeFactory.createSetValue(
                        0, 0, typeMapper.unitType(), foundSymbol,
                        IrStatementOrigin.EQ, binOp
                    )
                    val block = IrNodeFactory.createBlock(0, 0, typeMapper.unitType(), null)
                    (block.statements as MutableList<IrStatement>).add(setX)
                    return block
                }
            }
        } else if (target is JasPropertyAccess) {
            val pa = target
            val targetExpr = translateExpression(pa.target, ctx)
            val fieldSymbol = resolveFieldSymbol(pa.property, pa.target, targetExpr, ctx)
            val getField = IrNodeFactory.createGetField(0, 0, typeMapper.intType(), fieldSymbol, null, IrStatementOrigin.GET_PROPERTY)
            getField.receiver = targetExpr
            val stubSymbol = IrSimpleFunctionSymbolImpl()
            ctx.callNames[stubSymbol] = if (op == "++") "+" else "-"
            val binOp = IrNodeFactory.createCall(
                0, 0, typeMapper.intType(), IrStatementOrigin.PLUS,
                arrayOf(getField, one), emptyArray(), stubSymbol, null
            )
            val targetExpr2 = translateExpression(pa.target, ctx)
            val setField = IrNodeFactory.createSetField(0, 0, typeMapper.unitType(), fieldSymbol, null, IrStatementOrigin.EQ)
            setField.receiver = targetExpr2
            setField.value = binOp
            if (needsResult) {
                val block = IrNodeFactory.createBlock(0, 0, typeMapper.intType(), null)
                val resultSymbol = IrVariableSymbolImpl()
                val resultVar = IrNodeFactory.createVariable(
                    0, 0, IrDeclarationOrigin.DEFINED, Name.identifier("inc$"),
                    typeMapper.intType(), resultSymbol, true, false, false
                )
                if (op == "++") {
                    resultVar.initializer = getField
                } else {
                    resultVar.initializer = IrNodeFactory.createCall(
                        0, 0, typeMapper.intType(), IrStatementOrigin.MINUS,
                        arrayOf(getField, one), emptyArray(), stubSymbol, null
                    )
                }
                (block.statements as MutableList<IrStatement>).add(resultVar)
                (block.statements as MutableList<IrStatement>).add(setField)
                val getResult = IrNodeFactory.createGetValue(0, 0, typeMapper.intType(), resultSymbol, IrStatementOrigin.GET_PROPERTY)
                (block.statements as MutableList<IrStatement>).add(getResult)
                return block
            } else {
                val block = IrNodeFactory.createBlock(0, 0, typeMapper.unitType(), null)
                (block.statements as MutableList<IrStatement>).add(setField)
                return block
            }
        } else if (target is JasArrayAccess) {
            val aa = target
            val arrayExpr = translateExpression(aa.target, ctx)
            val indexExpr = translateExpression(aa.index, ctx)
            val arrSymbol = IrVariableSymbolImpl()
            val arrVar = IrNodeFactory.createVariable(0, 0, IrDeclarationOrigin.DEFINED, Name.identifier("aa$"), arrayExpr.type, arrSymbol, true, false, false)
            arrVar.initializer = arrayExpr
            val idxSymbol = IrVariableSymbolImpl()
            val idxVar = IrNodeFactory.createVariable(0, 0, IrDeclarationOrigin.DEFINED, Name.identifier("ai$"), indexExpr.type, idxSymbol, true, false, false)
            idxVar.initializer = indexExpr
            val getSymbol = IrSimpleFunctionSymbolImpl()
            ctx.callNames[getSymbol] = "get"
            val getCall = IrNodeFactory.createCall(
                0, 0, typeMapper.intType(), null,
                arrayOf(
                    IrNodeFactory.createGetValue(0, 0, arrayExpr.type, arrSymbol, null),
                    IrNodeFactory.createGetValue(0, 0, indexExpr.type, idxSymbol, null)
                ), emptyArray(), getSymbol, null
            )
            val stubSymbol = IrSimpleFunctionSymbolImpl()
            ctx.callNames[stubSymbol] = if (op == "++") "+" else "-"
            val binOp = IrNodeFactory.createCall(
                0, 0, typeMapper.intType(), IrStatementOrigin.PLUS,
                arrayOf(getCall, one), emptyArray(), stubSymbol, null
            )
            val setSymbol = IrSimpleFunctionSymbolImpl()
            ctx.callNames[setSymbol] = "arraySet"
            val setCall = IrNodeFactory.createCall(
                0, 0, typeMapper.unitType(), null,
                arrayOf(
                    IrNodeFactory.createGetValue(0, 0, arrayExpr.type, arrSymbol, null),
                    IrNodeFactory.createGetValue(0, 0, indexExpr.type, idxSymbol, null),
                    binOp
                ), emptyArray(), setSymbol, null
            )
            if (needsResult) {
                val block = IrNodeFactory.createBlock(0, 0, typeMapper.intType(), null)
                (block.statements as MutableList<IrStatement>).add(arrVar)
                (block.statements as MutableList<IrStatement>).add(idxVar)
                val resultSymbol = IrVariableSymbolImpl()
                val resultVar = IrNodeFactory.createVariable(
                    0, 0, IrDeclarationOrigin.DEFINED, Name.identifier("inc$"),
                    typeMapper.intType(), resultSymbol, true, false, false
                )
                if (op == "++") {
                    resultVar.initializer = getCall
                } else {
                    resultVar.initializer = IrNodeFactory.createCall(
                        0, 0, typeMapper.intType(), IrStatementOrigin.MINUS,
                        arrayOf(getCall, one), emptyArray(), stubSymbol, null
                    )
                }
                (block.statements as MutableList<IrStatement>).add(resultVar)
                (block.statements as MutableList<IrStatement>).add(setCall)
                val getResult = IrNodeFactory.createGetValue(0, 0, typeMapper.intType(), resultSymbol, IrStatementOrigin.GET_PROPERTY)
                (block.statements as MutableList<IrStatement>).add(getResult)
                return block
            } else {
                val block = IrNodeFactory.createBlock(0, 0, typeMapper.unitType(), null)
                (block.statements as MutableList<IrStatement>).add(arrVar)
                (block.statements as MutableList<IrStatement>).add(idxVar)
                (block.statements as MutableList<IrStatement>).add(setCall)
                return block
            }
        }
        return typeMapper.unitExpr()
    }

    private fun translateAssignmentIncrement(expr: JasAssignmentIncrement, ctx: TranslationContext): IrExpression {
        return translateAssignment(JasAssignment(expr.target, expr.increment, "="), ctx)
    }
}
