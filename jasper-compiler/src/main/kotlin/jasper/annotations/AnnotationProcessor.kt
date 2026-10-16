package jasper.annotations

import jasper.ast.JasAnnotationArgument
import jasper.ast.JasAnnotationUse
import jasper.ast.JasClass
import jasper.ast.JasDeclaration
import jasper.ast.JasFunction
import jasper.ast.JasParameter
import jasper.ast.JasProperty
import jasper.ast.JasSyntheticAccessor
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
data class AnnotatedSourceFile(val ast: JasSourceFile, val index: AnnotationIndex)

/**
 * The annotation processing phase.
 *
 * It sits **between AST building and the TypeChecker**. It reads annotations
 * (`@Getter` / `@Setter` for this stage), synthesizes the *member signatures*
 * those annotations imply, and attaches them to the owning class so the
 * TypeChecker can resolve them. It never synthesizes method bodies: bodies need
 * type information and are generated later, during lowering
 * (`JasperToIrTranslator`, driven by [JasSyntheticAccessor]).
 *
 * Code without these annotations is returned unchanged (same AST instance where
 * possible) so the existing path is byte-for-byte identical.
 */
class AnnotationProcessor {

    fun process(ast: JasSourceFile): AnnotatedSourceFile {
        val records = linkedMapOf<String, MutableList<AnnotationRecord>>()
        var changed = false
        val declarations = ast.declarations.map { decl ->
            if (decl is JasClass) {
                val processed = processClass(decl, records)
                if (processed !== decl) changed = true
                processed
            } else {
                decl
            }
        }
        val newAst = if (changed) ast.copy(declarations = declarations) else ast
        val index = AnnotationIndex(records.mapValues { it.value.toList() })
        return AnnotatedSourceFile(newAst, index)
    }

    private fun processClass(
        cls: JasClass,
        records: MutableMap<String, MutableList<AnnotationRecord>>
    ): JasClass {
        val classAnnotations = cls.modifiers.filterIsInstance<JasAnnotationUse>()
        val classHasGetter = classAnnotations.any { simpleName(it.name) == GETTER }
        val classHasSetter = classAnnotations.any { simpleName(it.name) == SETTER }

        for (annotation in classAnnotations) {
            record(records, cls.name, "class", cls.name, annotation)
        }

        val existingFunctionNames = cls.members
            .filterIsInstance<JasFunction>()
            .map { it.name }
            .toMutableSet()

        val generated = mutableListOf<JasDeclaration>()
        for (member in cls.members) {
            when (member) {
                is JasProperty -> {
                    val propAnnotations = member.modifiers.filterIsInstance<JasAnnotationUse>()
                    for (annotation in propAnnotations) {
                        record(records, cls.name, "property", member.name, annotation)
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

        if (generated.isEmpty()) return cls
        return cls.copy(members = cls.members + generated)
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

    private fun isEligibleForGetter(prop: JasProperty): Boolean =
        prop.type != null && !isStatic(prop)

    private fun isEligibleForSetter(prop: JasProperty): Boolean =
        prop.type != null && !isStatic(prop) && !prop.isConst

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
    }
}
