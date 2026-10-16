package jasper.annotations

import jasper.ast.JasAnnotationArgument
import jasper.ast.JasAnnotationUse
import jasper.ast.JasAnnotationNamedArg
import jasper.ast.JasAnnotationPositionalArg
import jasper.ast.JasAnyType
import jasper.ast.JasArrayType
import jasper.ast.JasAssignment
import jasper.ast.JasClass
import jasper.ast.JasDeclaration
import jasper.ast.JasEqualityField
import jasper.ast.JasFunction
import jasper.ast.JasIdentifier
import jasper.ast.JasInterface
import jasper.ast.JasMapperAssignment
import jasper.ast.JasNamedType
import jasper.ast.JasNonNullType
import jasper.ast.JasNullableType
import jasper.ast.JasParameter
import jasper.ast.JasPrimitiveType
import jasper.ast.JasProperty
import jasper.ast.JasStringLiteral
import jasper.ast.JasStringType
import jasper.ast.JasSyntheticAccessor
import jasper.ast.JasSyntheticEquality
import jasper.ast.JasSyntheticMapper
import jasper.ast.JasType
import jasper.ast.JasUnitType
import jasper.ast.JasSourceFile

/**
 * One annotation occurrence captured by the annotation processing phase.
 *
 * This is deliberately a plain data record (no compilation state) so that it can
 * later be serialized into the `.jaso` manifest as a service/extension-point
 * index without changing the compiler core.
 */
data class AnnotationRecord(
    val annotationName: String,
    val targetKind: String,
    val targetName: String,
    val arguments: List<JasAnnotationArgument> = emptyList()
)

/**
 * Structured in-memory annotation index produced by [AnnotationProcessor].
 *
 * Shape: `className -> ordered list of annotation declarations` attached to the
 * class itself or to its members. Every entry records the annotation name, the
 * kind of declaration it sits on (`class` / `property` / `function`), the
 * declaration name, and the raw arguments. This is exactly the data a future
 * `.jaso` manifest needs for a service/extension-point index; it is produced now
 * because the data is already in the compiler's hands.
 */
data class AnnotationIndex(val byClass: Map<String, List<AnnotationRecord>>) {
    fun forClass(className: String): List<AnnotationRecord> = byClass[className] ?: emptyList()

    companion object {
        val EMPTY = AnnotationIndex(emptyMap())
    }
}

/** Result of running the annotation phase: the rewritten AST plus the index. */
data class AnnotatedSourceFile(
    val ast: JasSourceFile,
    val index: AnnotationIndex,
    val errors: List<AnnotationError> = emptyList()
)

/**
 * A compile-time diagnostic produced by the annotation processing phase (e.g.
 * an unmapped target property of an `@Mapper` method). It is position-carrying
 * like [jasper.compiler.SymbolError]; the compiler maps it into the structured
 * `CompileError` hierarchy.
 */
data class AnnotationError(val message: String, val line: Int = 0, val column: Int = 0)

/**
 * The annotation processing phase.
 *
 * It sits **between AST building and the TypeChecker**. It reads annotations
 * (`@Getter` / `@Setter` / `@EqualsAndHashCode`), synthesizes the *member
 * signatures* those annotations imply, and attaches them to the owning class so
 * the TypeChecker can resolve them. It never synthesizes method bodies: bodies
 * need type information and are generated later, during lowering
 * (`JasperToIrTranslator`, driven by [JasSyntheticAccessor] /
 * [JasSyntheticEquality] / [JasSyntheticMapper]).
 *
 * For `@Mapper` it additionally synthesizes a whole *implementation class*
 * (`<Interface>Impl`) for an annotated interface, plus the mapper method
 * signatures (with a [JasSyntheticMapper] plan). Semantic problems that can be
 * decided from signatures alone — most importantly an **unmapped target
 * property** — are reported as [AnnotationError]s.
 *
 * Code without these annotations is returned unchanged (same AST instance where
 * possible) so the existing path is byte-for-byte identical.
 */
class AnnotationProcessor {

    fun process(ast: JasSourceFile): AnnotatedSourceFile {
        val records = linkedMapOf<String, MutableList<AnnotationRecord>>()
        val errors = mutableListOf<AnnotationError>()

        // Index class property lists and all declared type names up front so
        // @Mapper methods can resolve their source/target shapes (v1: types must
        // be classes declared in the same source file).
        val classProperties = linkedMapOf<String, List<JasProperty>>()
        val declaredTypeNames = mutableSetOf<String>()
        for (decl in ast.declarations) {
            when (decl) {
                is JasClass -> {
                    classProperties[decl.name] = decl.members.filterIsInstance<JasProperty>()
                    declaredTypeNames.add(decl.name)
                }
                is JasInterface -> declaredTypeNames.add(decl.name)
                is jasper.ast.JasEnum -> declaredTypeNames.add(decl.name)
                is jasper.ast.JasAnnotationType -> declaredTypeNames.add(decl.name)
                else -> {}
            }
        }

        var changed = false
        val declarations = mutableListOf<JasDeclaration>()
        for (decl in ast.declarations) {
            when (decl) {
                is JasClass -> {
                    val processed = processClass(decl, records)
                    if (processed !== decl) changed = true
                    declarations.add(processed)
                }
                is JasInterface -> {
                    val impl = processInterface(decl, records, classProperties, declaredTypeNames, errors)
                    declarations.add(decl)
                    if (impl != null) {
                        declarations.add(impl)
                        changed = true
                    }
                }
                else -> declarations.add(decl)
            }
        }
        val newAst = if (changed) ast.copy(declarations = declarations) else ast
        val index = AnnotationIndex(records.mapValues { it.value.toList() })
        return AnnotatedSourceFile(newAst, index, errors.toList())
    }

    private fun processClass(
        cls: JasClass,
        records: MutableMap<String, MutableList<AnnotationRecord>>
    ): JasClass {
        val classAnnotations = cls.modifiers.filterIsInstance<JasAnnotationUse>()
        val classHasGetter = classAnnotations.any { simpleName(it.name) == GETTER }
        val classHasSetter = classAnnotations.any { simpleName(it.name) == SETTER }
        val classHasEquality = classAnnotations.any { simpleName(it.name) == EQUALS_AND_HASH_CODE }

        for (annotation in classAnnotations) {
            record(records, cls.name, "class", cls.name, annotation)
        }

        val existingFunctionNames = cls.members
            .filterIsInstance<JasFunction>()
            .map { it.name }
            .toMutableSet()

        val generated = mutableListOf<JasDeclaration>()
        val propertyLevelEqualityFields = mutableListOf<JasEqualityField>()
        for (member in cls.members) {
            when (member) {
                is JasProperty -> {
                    val propAnnotations = member.modifiers.filterIsInstance<JasAnnotationUse>()
                    for (annotation in propAnnotations) {
                        record(records, cls.name, "property", member.name, annotation)
                    }
                    if (propAnnotations.any { simpleName(it.name) == EQUALS_AND_HASH_CODE } &&
                        isEligibleForEquality(member)
                    ) {
                        propertyLevelEqualityFields.add(JasEqualityField(member.name, member.type))
                    }
                    val wantGetter = classHasGetter || propAnnotations.any { simpleName(it.name) == GETTER }
                    val wantSetter = classHasSetter || propAnnotations.any { simpleName(it.name) == SETTER }

                    if (wantGetter && isEligibleForGetter(member)) {
                        val name = accessorName("get", member.name)
                        if (existingFunctionNames.add(name)) {
                            generated.add(getterSignature(name, member))
                        }
                    }
                    if (wantSetter && isEligibleForSetter(member)) {
                        val name = accessorName("set", member.name)
                        if (existingFunctionNames.add(name)) {
                            generated.add(setterSignature(name, member))
                        }
                    }
                }
                is JasFunction -> {
                    for (annotation in member.modifiers.filterIsInstance<JasAnnotationUse>()) {
                        record(records, cls.name, "function", member.name, annotation)
                    }
                }
                else -> {}
            }
        }

        // @EqualsAndHashCode (class-level or property-level): synthesize the
        // `equals` / `hash` *signatures*. Handwritten methods always win: if a
        // function with the same name already exists, the respective member is
        // not generated (idempotence).
        if (classHasEquality || propertyLevelEqualityFields.isNotEmpty()) {
            val fields = if (classHasEquality) {
                cls.members.filterIsInstance<JasProperty>()
                    .filter { isEligibleForEquality(it) }
                    .map { JasEqualityField(it.name, it.type) }
            } else {
                propertyLevelEqualityFields
            }
            if (existingFunctionNames.add(EQUALS_METHOD)) {
                generated.add(equalitySignature(isEquals = true, className = cls.name, fields = fields))
            }
            if (existingFunctionNames.add(HASH_METHOD)) {
                generated.add(equalitySignature(isEquals = false, className = cls.name, fields = fields))
            }
        }

        if (generated.isEmpty()) return cls
        return cls.copy(members = cls.members + generated)
    }

    // ── @Mapper ──

    /**
     * Processes one interface declaration. Non-`@Mapper` interfaces are indexed
     * and returned untouched (null impl). For an `@Mapper` interface a concrete
     * implementation class (`<Interface>Impl`) is synthesized: one method per
     * abstract mapper method, each carrying a [JasSyntheticMapper] plan and no
     * body (bodies are generated during lowering).
     *
     * Returns the synthesized impl class, or null when nothing was generated.
     */
    private fun processInterface(
        iface: JasInterface,
        records: MutableMap<String, MutableList<AnnotationRecord>>,
        classProperties: Map<String, List<JasProperty>>,
        declaredTypeNames: Set<String>,
        errors: MutableList<AnnotationError>
    ): JasClass? {
        val annotations = iface.modifiers.filterIsInstance<JasAnnotationUse>()
        for (annotation in annotations) {
            record(records, iface.name, "interface", iface.name, annotation)
        }
        for (member in iface.members) {
            when (member) {
                is JasFunction -> for (a in member.modifiers.filterIsInstance<JasAnnotationUse>()) {
                    record(records, iface.name, "function", member.name, a)
                }
                is JasProperty -> for (a in member.modifiers.filterIsInstance<JasAnnotationUse>()) {
                    record(records, iface.name, "property", member.name, a)
                }
                else -> {}
            }
        }

        if (annotations.none { simpleName(it.name) == MAPPER }) return null

        val implName = iface.name + MAPPER_IMPL_SUFFIX
        // Idempotence: never synthesize the same impl twice (e.g. re-processing
        // an AST that was already through this phase).
        if (implName in declaredTypeNames) return null

        if (iface.typeParameters.isNotEmpty()) {
            errors.add(AnnotationError(
                "@Mapper interface '${iface.name}' must not declare type parameters (v1 limitation)",
                iface.sourceLine, iface.sourceColumn
            ))
            return null
        }

        val implFunctions = mutableListOf<JasDeclaration>()
        for (member in iface.members) {
            if (member !is JasFunction) continue
            if (member.body != null) continue // only abstract mapper methods
            buildMapperFunction(iface.name, member, classProperties, errors)?.let { implFunctions.add(it) }
        }

        if (implFunctions.isEmpty()) return null

        return JasClass(
            name = implName,
            modifiers = listOf("public"),
            typeParameters = emptyList(),
            superclass = null,
            interfaces = listOf(JasNamedType(iface.name)),
            members = implFunctions,
            constructors = emptyList()
        )
    }

    /**
     * Builds the impl-side signature for one mapper method and validates the
     * mapping plan. On any problem an [AnnotationError] is recorded and null is
     * returned (no half-built method is emitted).
     */
    private fun buildMapperFunction(
        interfaceName: String,
        method: JasFunction,
        classProperties: Map<String, List<JasProperty>>,
        errors: MutableList<AnnotationError>
    ): JasFunction? {
        val line = method.sourceLine
        val column = method.sourceColumn
        val before = errors.size

        if (method.parameters.size != 1) {
            errors.add(AnnotationError(
                "Mapper method '${method.name}' in '${interfaceName}' must declare exactly one source parameter (v1 limitation)",
                line, column
            ))
            return null
        }
        val sourceParam = method.parameters[0]
        val sourceTypeName = (sourceParam.type as? JasNamedType)?.name
        val targetTypeName = (method.returnType as? JasNamedType)?.name
        if (sourceTypeName == null) {
            errors.add(AnnotationError(
                "Mapper method '${method.name}': source parameter '${sourceParam.name}' must have a declared class type",
                line, column
            ))
            return null
        }
        if (targetTypeName == null) {
            errors.add(AnnotationError(
                "Mapper method '${method.name}': return type must be a declared class type",
                line, column
            ))
            return null
        }
        val sourceProps = classProperties[sourceTypeName]
        val targetProps = classProperties[targetTypeName]
        if (sourceProps == null) {
            errors.add(AnnotationError(
                "Mapper method '${method.name}': source type '${sourceTypeName}' is not a class declared in this source file (v1 limitation)",
                line, column
            ))
            return null
        }
        if (targetProps == null) {
            errors.add(AnnotationError(
                "Mapper method '${method.name}': target type '${targetTypeName}' is not a class declared in this source file (v1 limitation)",
                line, column
            ))
            return null
        }

        // Explicit rename mappings: target property -> source property.
        val explicitByTarget = linkedMapOf<String, String>()
        for (mapping in method.modifiers.filterIsInstance<JasAnnotationUse>()
            .filter { simpleName(it.name) == MAPPING }) {
            val args = annotationStringArgs(mapping)
            val source = args["source"]
            val target = args["target"]
            if (source == null || target == null) {
                errors.add(AnnotationError(
                    "@Mapping on '${method.name}' requires both 'source' and 'target' string arguments",
                    mapping.sourceLine, mapping.sourceColumn
                ))
                continue
            }
            val tProp = targetProps.firstOrNull { it.name == target }
            if (tProp == null) {
                errors.add(AnnotationError(
                    "@Mapping target '$target' is not a property of target type '${targetTypeName}'",
                    mapping.sourceLine, mapping.sourceColumn
                ))
                continue
            }
            val sProp = sourceProps.firstOrNull { it.name == source }
            if (sProp == null) {
                errors.add(AnnotationError(
                    "@Mapping source '$source' is not a property of source type '${sourceTypeName}'",
                    mapping.sourceLine, mapping.sourceColumn
                ))
                continue
            }
            if (!sameSimpleType(sProp.type, tProp.type)) {
                errors.add(AnnotationError(
                    "@Mapping '${source}' -> '$target' type mismatch: ${describeType(sProp.type)} vs ${describeType(tProp.type)}",
                    mapping.sourceLine, mapping.sourceColumn
                ))
                continue
            }
            explicitByTarget[target] = source
        }

        // Auto-mapping: same-name, same-type source property.
        val assignments = mutableListOf<JasMapperAssignment>()
        for (tProp in targetProps) {
            val explicitSource = explicitByTarget[tProp.name]
            if (explicitSource != null) {
                assignments.add(JasMapperAssignment(tProp.name, explicitSource))
                continue
            }
            val autoSource = sourceProps.firstOrNull { it.name == tProp.name }
            if (autoSource == null) {
                errors.add(AnnotationError(
                    "Unmapped target property '${tProp.name}' in mapper method '${method.name}': no source property named '${tProp.name}' on '${sourceTypeName}'",
                    line, column
                ))
                continue
            }
            if (!sameSimpleType(autoSource.type, tProp.type)) {
                errors.add(AnnotationError(
                    "Type mismatch for property '${tProp.name}' in mapper method '${method.name}': ${describeType(autoSource.type)} vs ${describeType(tProp.type)}",
                    line, column
                ))
                continue
            }
            assignments.add(JasMapperAssignment(tProp.name, autoSource.name))
        }

        if (errors.size > before) return null

        return JasFunction(
            name = method.name,
            parameters = method.parameters,
            returnType = method.returnType,
            body = null,
            modifiers = listOf("public"),
            syntheticMapper = JasSyntheticMapper(
                parameterName = sourceParam.name,
                targetType = JasNamedType(targetTypeName),
                assignments = assignments
            )
        )
    }

    /**
     * Structural equality for the v1 "same type" rule (primitives, `string`,
     * named types without type arguments). Nullable wrappers are transparent so
     * `string?` and `string` are treated as the same source shape in v1.
     */
    private fun sameSimpleType(a: JasType?, b: JasType?): Boolean {
        val ka = typeKey(a) ?: return false
        return ka == typeKey(b)
    }

    private fun typeKey(type: JasType?): String? = when (type) {
        null -> null
        is JasPrimitiveType -> primitiveAlias(type.name)
        is JasStringType -> "string"
        is JasNamedType -> "named:${type.name}"
        is JasNullableType -> typeKey(type.inner)
        is JasNonNullType -> typeKey(type.inner)
        is JasAnyType -> "any"
        is JasUnitType -> "unit"
        is JasArrayType -> "array:" + typeKey(type.inner)
        else -> type.toString()
    }

    private fun primitiveAlias(name: String): String = when (name.lowercase()) {
        "int" -> "int32"
        "long" -> "int64"
        "float" -> "float32"
        "double" -> "float64"
        "boolean" -> "bool"
        else -> name.lowercase()
    }

    private fun describeType(type: JasType?): String = when (type) {
        null -> "<none>"
        is JasPrimitiveType -> type.name
        is JasStringType -> "string"
        is JasNamedType -> type.name
        is JasNullableType -> describeType(type.inner) + "?"
        is JasNonNullType -> describeType(type.inner) + "!"
        else -> type.toString()
    }

    /**
     * Extracts the `name = "value"` string arguments of an annotation.
     *
     * Note on the grammar: `@Mapping(source = "a", target = "b")` does **not**
     * parse through the grammar's dedicated `annotationNamedArgument` rule — ANTLR
     * resolves the ambiguity in favour of the positional branch, so `source = "a"`
     * arrives as a `JasAnnotationPositionalArg` wrapping a `JasAssignment`. Both
     * spellings are accepted here so the annotation reads the same either way.
     * (`.g4` files are locked; this adapter is the correct place to absorb it.)
     */
    private fun annotationStringArgs(annotation: JasAnnotationUse): Map<String, String> {
        val result = linkedMapOf<String, String>()
        for (argument in annotation.arguments) {
            when (argument) {
                is JasAnnotationNamedArg -> {
                    val value = argument.value as? JasStringLiteral ?: continue
                    result[argument.name] = value.text
                }
                is JasAnnotationPositionalArg -> {
                    val value = argument.value
                    if (value is JasAssignment && value.op == "=" &&
                        value.target is JasIdentifier && value.value is JasStringLiteral
                    ) {
                        result[(value.target as JasIdentifier).name] = (value.value as JasStringLiteral).text
                    }
                }
                else -> {}
            }
        }
        return result
    }

    /**
     * Synthesizes the *signature only* of a getter: `getFoo(): T`, body = null.
     * The body is produced later by the translator from [JasSyntheticAccessor].
     */
    private fun getterSignature(name: String, prop: JasProperty): JasFunction = JasFunction(
        name = name,
        parameters = emptyList(),
        returnType = prop.type,
        body = null,
        modifiers = listOf("public"),
        syntheticAccessor = JasSyntheticAccessor(prop.name, isGetter = true)
    )

    /**
     * Synthesizes the *signature only* of a setter: `setFoo(value: T): void`,
     * body = null. The body is produced later by the translator.
     */
    private fun setterSignature(name: String, prop: JasProperty): JasFunction = JasFunction(
        name = name,
        parameters = listOf(JasParameter("value", prop.type)),
        returnType = JasUnitType,
        body = null,
        modifiers = listOf("public"),
        syntheticAccessor = JasSyntheticAccessor(prop.name, isGetter = false)
    )

    /**
     * Synthesizes the *signature only* of a `@EqualsAndHashCode` member:
     * `equals(other: any): bool` or `hash(): int32`. The body is produced later
     * by the translator from [JasSyntheticEquality].
     */
    private fun equalitySignature(isEquals: Boolean, className: String, fields: List<JasEqualityField>): JasFunction {
        val marker = JasSyntheticEquality(isEquals = isEquals, className = className, fields = fields)
        return if (isEquals) {
            JasFunction(
                name = EQUALS_METHOD,
                parameters = listOf(JasParameter("other", JasAnyType)),
                returnType = JasPrimitiveType("bool"),
                body = null,
                modifiers = listOf("public"),
                syntheticEquality = marker
            )
        } else {
            JasFunction(
                name = HASH_METHOD,
                parameters = emptyList(),
                returnType = JasPrimitiveType("int32"),
                body = null,
                modifiers = listOf("public"),
                syntheticEquality = marker
            )
        }
    }

    private fun isEligibleForGetter(prop: JasProperty): Boolean =
        prop.type != null && !isStatic(prop)

    private fun isEligibleForSetter(prop: JasProperty): Boolean =
        prop.type != null && !isStatic(prop) && !prop.isConst

    /** Equality/hash participation: typed instance fields only (no statics). */
    private fun isEligibleForEquality(prop: JasProperty): Boolean =
        prop.type != null && !isStatic(prop)

    private fun isStatic(prop: JasProperty): Boolean =
        prop.modifiers.any { it is String && it == "static" }

    private fun accessorName(prefix: String, propName: String): String {
        if (propName.isEmpty()) return prefix
        return prefix + propName[0].uppercaseChar() + propName.substring(1)
    }

    private fun simpleName(qualified: String): String = qualified.substringAfterLast('.')

    private fun record(
        records: MutableMap<String, MutableList<AnnotationRecord>>,
        className: String,
        targetKind: String,
        targetName: String,
        annotation: JasAnnotationUse
    ) {
        records.getOrPut(className) { mutableListOf() }.add(
            AnnotationRecord(
                annotationName = simpleName(annotation.name),
                targetKind = targetKind,
                targetName = targetName,
                arguments = annotation.arguments
            )
        )
    }

    companion object {
        const val GETTER = "Getter"
        const val SETTER = "Setter"
        const val EQUALS_AND_HASH_CODE = "EqualsAndHashCode"
        const val EQUALS_METHOD = "equals"
        const val HASH_METHOD = "hash"
        const val MAPPER = "Mapper"
        const val MAPPING = "Mapping"
        const val MAPPER_IMPL_SUFFIX = "Impl"
    }
}
