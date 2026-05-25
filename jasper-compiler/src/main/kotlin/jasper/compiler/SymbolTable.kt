package jasper.compiler

import jasper.ast.*

data class SymbolError(val message: String, val kind: String, val name: String, val line: Int = 0, val column: Int = 0)

class SymbolTable {
    val functions: MutableMap<String, MutableList<JasFunction>> = mutableMapOf()
    val classes: MutableMap<String, JasClass> = mutableMapOf()
    val interfaces: MutableMap<String, JasInterface> = mutableMapOf()
    val enums: MutableMap<String, JasEnum> = mutableMapOf()
    val annotations: MutableMap<String, JasAnnotationType> = mutableMapOf()
    val packages: MutableMap<String, MutableList<String>> = mutableMapOf()
    val imports: MutableMap<String, String> = mutableMapOf()
    val importDeclarations: MutableMap<String, JasImport> = mutableMapOf()
    var currentPackage: String? = null
    var errors: MutableList<SymbolError> = mutableListOf()

    fun registerBuiltins() {
        val anyParam = listOf(JasParameter("value", JasAnyType))
        registerFunction("println", JasFunction(
            name = "println",
            parameters = anyParam,
            returnType = JasUnitType,
            body = null,
            modifiers = listOf("public", "static"),
            typeParameters = emptyList(),
            whereConstraints = emptyList()
        ))
        registerFunction("print", JasFunction(
            name = "print",
            parameters = anyParam,
            returnType = JasUnitType,
            body = null,
            modifiers = listOf("public", "static"),
            typeParameters = emptyList(),
            whereConstraints = emptyList()
        ))
        registerFunction("toString", JasFunction(
            name = "toString",
            parameters = anyParam,
            returnType = JasStringType,
            body = null,
            modifiers = listOf("public", "static"),
            typeParameters = emptyList(),
            whereConstraints = emptyList()
        ))
    }

    private fun registerFunction(name: String, func: JasFunction) {
        functions.getOrPut(name) { mutableListOf() }.add(func)
    }

    fun collectDeclarations(ast: JasSourceFile) {
        currentPackage = ast.packageName
        ast.packageName?.let { pkg ->
            packages.getOrPut(pkg) { mutableListOf() }
        }
        val seenTypes = mutableMapOf<String, String>() // name -> kind
        for (decl in ast.declarations) {
            when (decl) {
                is JasFunction -> {
                    functions.getOrPut(decl.name) { mutableListOf() }.add(decl)
                }
                is JasClass -> {
                    val prev = seenTypes.put(decl.name, "class")
                    if (prev != null) {
                        errors.add(SymbolError("Duplicate class declaration '$decl.name' (previous: $prev)", "class", decl.name, line = decl.sourceLine, column = decl.sourceColumn))
                    }
                    if (classes.containsKey(decl.name)) {
                        errors.add(SymbolError("Duplicate class declaration '$decl.name'", "class", decl.name, line = decl.sourceLine, column = decl.sourceColumn))
                    }
                    classes[decl.name] = decl
                }
                is JasInterface -> {
                    val prev = seenTypes.put(decl.name, "interface")
                    if (prev != null) {
                        errors.add(SymbolError("Duplicate interface declaration '$decl.name' (previous: $prev)", "interface", decl.name, line = decl.sourceLine, column = decl.sourceColumn))
                    }
                    if (interfaces.containsKey(decl.name)) {
                        errors.add(SymbolError("Duplicate interface declaration '$decl.name'", "interface", decl.name, line = decl.sourceLine, column = decl.sourceColumn))
                    }
                    interfaces[decl.name] = decl
                }
                is JasEnum -> {
                    val prev = seenTypes.put(decl.name, "enum")
                    if (prev != null) {
                        errors.add(SymbolError("Duplicate enum declaration '$decl.name' (previous: $prev)", "enum", decl.name, line = decl.sourceLine, column = decl.sourceColumn))
                    }
                    if (enums.containsKey(decl.name)) {
                        errors.add(SymbolError("Duplicate enum declaration '$decl.name'", "enum", decl.name, line = decl.sourceLine, column = decl.sourceColumn))
                    }
                    enums[decl.name] = decl
                }
                is JasAnnotationType -> {
                    val prev = seenTypes.put(decl.name, "annotation")
                    if (prev != null) {
                        errors.add(SymbolError("Duplicate annotation declaration '$decl.name' (previous: $prev)", "annotation", decl.name, line = decl.sourceLine, column = decl.sourceColumn))
                    }
                    if (annotations.containsKey(decl.name)) {
                        errors.add(SymbolError("Duplicate annotation declaration '$decl.name'", "annotation", decl.name, line = decl.sourceLine, column = decl.sourceColumn))
                    }
                    annotations[decl.name] = decl
                }
                is JasImport -> {
                    val simpleName = decl.name.split('.').lastOrNull() ?: decl.name
                    imports[simpleName] = decl.name
                    importDeclarations[simpleName] = decl
                    if (decl.alias != null) {
                        imports[decl.alias!!] = decl.name
                        importDeclarations[decl.alias!!] = decl
                    }
                }
                else -> {}
            }
        }
    }

    fun findAnnotation(name: String): JasAnnotationType? {
        return annotations[name]
    }

    fun resolveType(name: String): JasType? {
        if (classes.containsKey(name)) return JasNamedType(name)
        if (interfaces.containsKey(name)) return JasNamedType(name)
        if (enums.containsKey(name)) return JasNamedType(name)
        if (annotations.containsKey(name)) return JasNamedType(name)
        return null
    }

    fun findFunctions(name: String): List<JasFunction> {
        return functions[name] ?: emptyList()
    }

    fun findFunction(name: String): JasFunction? {
        return functions[name]?.firstOrNull()
    }

    fun findClass(name: String): JasClass? {
        return classes[name]
    }

    fun findInterface(name: String): JasInterface? {
        return interfaces[name]
    }

    fun findEnum(name: String): JasEnum? {
        return enums[name]
    }

    fun findImport(name: String): JasImport? {
        return importDeclarations[name]
    }

    fun resolveImport(simpleName: String): String? {
        return imports[simpleName]
    }

    fun merge(other: SymbolTable) {
        for ((name, funcs) in other.functions) {
            functions.getOrPut(name) { mutableListOf() }.addAll(funcs)
        }
        for ((name, cls) in other.classes) {
            if (classes.containsKey(name)) {
                errors.add(SymbolError("Duplicate class declaration '$name' during merge", "class", name, line = cls.sourceLine, column = cls.sourceColumn))
            }
            classes[name] = cls
        }
        for ((name, iface) in other.interfaces) {
            if (interfaces.containsKey(name)) {
                errors.add(SymbolError("Duplicate interface declaration '$name' during merge", "interface", name, line = iface.sourceLine, column = iface.sourceColumn))
            }
            interfaces[name] = iface
        }
        for ((name, enumDecl) in other.enums) {
            if (enums.containsKey(name)) {
                errors.add(SymbolError("Duplicate enum declaration '$name' during merge", "enum", name, line = enumDecl.sourceLine, column = enumDecl.sourceColumn))
            }
            enums[name] = enumDecl
        }
        for ((name, ann) in other.annotations) {
            if (annotations.containsKey(name)) {
                errors.add(SymbolError("Duplicate annotation declaration '$name' during merge", "annotation", name, line = ann.sourceLine, column = ann.sourceColumn))
            }
            annotations[name] = ann
        }
        importDeclarations.putAll(other.importDeclarations)
        imports.putAll(other.imports)
        packages.putAll(other.packages)
    }
}
