package jasper.checker

import jasper.ast.*
import jasper.compiler.SymbolTable

data class TypeError(val message: String, val node: JasNode? = null)

class TypeChecker(private val symbolTable: SymbolTable? = null) {
    private val errors = mutableListOf<TypeError>()
    private val classImplements = mutableMapOf<String, List<String>>() // className -> interfaceNames
    private val interfaceExtends = mutableMapOf<String, List<String>>() // interfaceName -> parentInterfaceNames

    fun check(sourceFile: JasSourceFile): List<TypeError> {
        errors.clear()
        classImplements.clear()
        interfaceExtends.clear()
        checkDuplicateTopLevelDeclarations(sourceFile)
        // First pass: collect class hierarchy (interfaces, implements)
        for (decl in sourceFile.declarations) {
            when (decl) {
                is JasClass -> {
                    val ifaces = decl.interfaces.mapNotNull { iface ->
                        if (iface is JasNamedType) iface.name else null
                    }
                    classImplements[decl.name] = ifaces
                }
                is JasInterface -> {
                    val parents = decl.extends.mapNotNull { ext ->
                        if (ext is JasNamedType) ext.name else null
                    }
                    interfaceExtends[decl.name] = parents
                }
                else -> {}
            }
        }
        for (decl in sourceFile.declarations) {
            checkDeclaration(decl, TypeEnvironment())
        }
        return errors.toList()
    }

    private fun error(message: String, node: JasNode? = null) {
        errors.add(TypeError(message, node))
    }

    private fun warn(message: String, node: JasNode? = null) {
        // Warnings are non-fatal diagnostics, logged but do not block compilation
    }

    class TypeEnvironment {
        private val types = mutableMapOf<String, JasType?>()
        val typeParameters = mutableMapOf<String, JasTypeParameter>()
        var loopDepth: Int = 0
        var switchDepth: Int = 0

        fun set(name: String, type: JasType?) { types[name] = type }
        fun get(name: String): JasType? = types[name]
        fun contains(name: String): Boolean = name in types
        fun addTypeParameter(tp: JasTypeParameter) { typeParameters[tp.name] = tp }
        fun getTypeParameter(name: String): JasTypeParameter? = typeParameters[name]
        fun hasTypeParameter(name: String): Boolean = name in typeParameters
        fun copy(): TypeEnvironment {
            val env = TypeEnvironment()
            env.types.putAll(this.types)
            env.typeParameters.putAll(this.typeParameters)
            env.loopDepth = this.loopDepth
            env.switchDepth = this.switchDepth
            return env
        }
    }

    private fun checkDuplicateTopLevelDeclarations(sourceFile: JasSourceFile) {
        val typeNames = mutableMapOf<String, JasDeclaration>()
        val functionArities = mutableMapOf<String, MutableSet<Int>>()

        for (decl in sourceFile.declarations) {
            when (decl) {
                is JasClass -> checkDuplicateName(typeNames, decl.name, decl)
                is JasInterface -> checkDuplicateName(typeNames, decl.name, decl)
                is JasEnum -> checkDuplicateName(typeNames, decl.name, decl)
                is JasAnnotationType -> checkDuplicateName(typeNames, decl.name, decl)
                is JasFunction -> {
                    val arities = functionArities.getOrPut(decl.name) { mutableSetOf() }
                    if (!arities.add(decl.parameters.size)) {
                        error("Duplicate function declaration '${decl.name}' with ${decl.parameters.size} parameter(s)", decl)
                    }
                }
                else -> {}
            }
        }
    }

    private fun checkDuplicateName(names: MutableMap<String, JasDeclaration>, name: String, node: JasDeclaration) {
        if (names.putIfAbsent(name, node) != null) {
            error("Duplicate type declaration '$name'", node)
        }
    }

    private fun checkDeclaration(decl: JasDeclaration, env: TypeEnvironment) {
        when (decl) {
            is JasFunction -> checkFunction(decl, env)
            is JasClass -> checkClass(decl, env)
            is JasInterface -> {
                val ifaceEnv = env.copy()
                checkTypeParameters(decl.typeParameters, ifaceEnv, "interface '${decl.name}'")
                decl.extends.forEach { checkTypeReferences(it, ifaceEnv) }
            }
            is JasEnum -> {}
            is JasProperty -> {}
            is JasImport -> {}
            is JasAnnotationType -> {
                for (member in decl.members) {
                    if (member.defaultValue != null) {
                        val defaultType = inferExpression(member.defaultValue, env)
                        if (defaultType != null && member.type != null && !typesCompatible(defaultType, member.type!!)) {
                            error("Annotation member default value type mismatch: expected ${member.type}, got $defaultType", member)
                        }
                    }
                }
            }
            is JasAnnotationMember -> {
                if (decl.type == null) {
                    error("Annotation member must have a return type", decl)
                }
            }
            else -> {}
        }
    }

    private fun checkTypeParameters(typeParams: List<JasTypeParameter>, env: TypeEnvironment, context: String) {
        val seen = mutableSetOf<String>()
        for (tp in typeParams) {
            if (!seen.add(tp.name)) {
                error("Duplicate type parameter '${tp.name}' in $context", tp)
                continue
            }
            if (tp.bound != null) {
                if (!isValidTypeReference(tp.bound!!)) {
                    error("Type parameter '${tp.name}' bound '${typeDisplayName(tp.bound!!)}' is not a valid type in $context", tp)
                }
            }
            env.addTypeParameter(tp)
        }
    }

    private fun checkWhereConstraints(constraints: List<JasTypeConstraint>, env: TypeEnvironment, context: String) {
        for (wc in constraints) {
            if (!env.hasTypeParameter(wc.typeParamName)) {
                error("Where constraint references unknown type parameter '${wc.typeParamName}' in $context", null)
            }
            if (!isValidTypeReference(wc.bound)) {
                error("Where constraint bound '${typeDisplayName(wc.bound)}' is not a valid type in $context", null)
            }
        }
    }

    private fun isValidTypeReference(type: JasType): Boolean {
        return when (type) {
            is JasPrimitiveType -> true
            is JasNamedType -> true
            is JasStringType, is JasUnitType, is JasAnyType -> true
            is JasArrayType -> isValidTypeReference(type.inner)
            is JasNullableType -> isValidTypeReference(type.inner)
            is JasNonNullType -> isValidTypeReference(type.inner)
            is JasWildcardType -> type.bound == null || isValidTypeReference(type.bound!!)
            is JasFunctionType -> type.paramTypes.all { isValidTypeReference(it) } && isValidTypeReference(type.returnType)
            is JasTupleType -> type.types.all { isValidTypeReference(it) }
            else -> true
        }
    }

    private fun checkTypeArgumentCount(name: String, typeArgs: List<JasType>, node: JasNode?) {
        val typeParamCount = when {
            symbolTable?.findClass(name) != null -> symbolTable!!.findClass(name)!!.typeParameters.size
            symbolTable?.findInterface(name) != null -> symbolTable!!.findInterface(name)!!.typeParameters.size
            else -> return
        }
        if (typeArgs.isNotEmpty() && typeArgs.size != typeParamCount) {
            error("Type '$name' expects $typeParamCount type argument(s), got ${typeArgs.size}", node)
        }
    }

    private fun checkTypeReferences(type: JasType?, env: TypeEnvironment) {
        if (type == null) return
        when (type) {
            is JasNamedType -> {
                checkTypeArgumentCount(type.name, type.typeArguments, null)
                type.typeArguments.forEach { checkTypeReferences(it, env) }
            }
            is JasArrayType -> checkTypeReferences(type.inner, env)
            is JasNullableType -> checkTypeReferences(type.inner, env)
            is JasNonNullType -> checkTypeReferences(type.inner, env)
            is JasFunctionType -> {
                type.paramTypes.forEach { checkTypeReferences(it, env) }
                checkTypeReferences(type.returnType, env)
            }
            is JasTupleType -> type.types.forEach { checkTypeReferences(it, env) }
            is JasWildcardType -> type.bound?.let { checkTypeReferences(it, env) }
            else -> {}
        }
    }

    private fun checkFunction(func: JasFunction, env: TypeEnvironment) {
        val funcEnv = env.copy()
        checkTypeParameters(func.typeParameters, funcEnv, "function '${func.name}'")
        checkWhereConstraints(func.whereConstraints, funcEnv, "function '${func.name}'")
        val params = mutableSetOf<String>()
        for (param in func.parameters) {
            if (!params.add(param.name)) {
                error("Duplicate parameter '${param.name}' in function '${func.name}'", param)
            }
            checkTypeReferences(param.type, funcEnv)
            funcEnv.set(param.name, param.type)
        }
        checkTypeReferences(func.returnType, funcEnv)
        if (func.body != null) {
            for (stmt in func.body!!.statements) {
                checkStatement(stmt, funcEnv, func.returnType)
            }
        }
    }

    private fun checkClass(cls: JasClass, env: TypeEnvironment) {
        val clsEnv = env.copy()
        checkTypeParameters(cls.typeParameters, clsEnv, "class '${cls.name}'")
        checkTypeReferences(cls.superclass, clsEnv)
        cls.interfaces.forEach { checkTypeReferences(it, clsEnv) }
        val memberNames = mutableSetOf<String>()
        for (member in cls.members) {
            when (member) {
                is JasProperty -> {
                    if (!memberNames.add(member.name)) {
                        error("Duplicate member '${member.name}' in class '${cls.name}'", member)
                    }
                    checkTypeReferences(member.type, clsEnv)
                    clsEnv.set(member.name, member.type)
                }
                is JasFunction -> {
                    val signature = "${member.name}/${member.parameters.size}"
                    if (!memberNames.add(signature)) {
                        error("Duplicate method '${member.name}' with ${member.parameters.size} parameter(s) in class '${cls.name}'", member)
                    }
                }
                else -> {}
            }
            checkDeclaration(member, clsEnv)
        }
        for (ctor in cls.constructors) {
            val ctorEnv = clsEnv.copy()
            val params = mutableSetOf<String>()
            for (param in ctor.parameters) {
                if (!params.add(param.name)) {
                    error("Duplicate constructor parameter '${param.name}' in class '${cls.name}'", param)
                }
                checkTypeReferences(param.type, ctorEnv)
                ctorEnv.set(param.name, param.type)
            }
            if (ctor.body != null) {
                for (stmt in ctor.body!!.statements) {
                    checkStatement(stmt, ctorEnv, JasUnitType)
                }
            }
        }
    }

    private fun checkStatement(stmt: JasStatement, env: TypeEnvironment, returnType: JasType?) {
        when (stmt) {
            is JasBlock -> stmt.statements.forEach { checkStatement(it, env, returnType) }
            is JasVariableStatement -> {
                if (env.contains(stmt.name)) {
                    error("Duplicate local declaration '${stmt.name}'", stmt)
                }
                if (stmt.initializer != null) {
                    val initType = inferExpression(stmt.initializer, env)
                    if (stmt.type != null && initType != null) {
                        if (!typesCompatible(initType, stmt.type!!)) {
                            error("Type mismatch: cannot assign ${typeDisplayName(initType)} to variable of type ${typeDisplayName(stmt.type!!)}", stmt)
                        }
                    }
                }
                env.set(stmt.name, stmt.type ?: stmt.initializer?.let { inferExpression(it, env) })
            }
            is JasExpressionStatement -> checkExpression(stmt.expression, env)
            is JasReturn -> {
                if (stmt.value != null) {
                    val valueType = inferExpression(stmt.value, env)
                    if (returnType != null && valueType != null) {
                        if (!typesCompatible(valueType, returnType)) {
                            error("Return type mismatch: expected ${typeDisplayName(returnType)}, got ${typeDisplayName(valueType)}", stmt)
                        }
                    }
                } else if (returnType != null && returnType !is JasUnitType) {
                    error("Return type mismatch: expected ${typeDisplayName(returnType)}, got void", stmt)
                }
            }
            is JasIf -> {
                checkBooleanCondition(stmt.condition, env, "if", stmt)
                checkStatement(stmt.thenBody, env, returnType)
                if (stmt.elseBody != null) checkStatement(stmt.elseBody, env, returnType)
            }
            is JasWhile -> {
                checkBooleanCondition(stmt.condition, env, "while", stmt)
                env.loopDepth++
                checkStatement(stmt.body, env, returnType)
                env.loopDepth--
            }
            is JasDoWhile -> {
                env.loopDepth++
                checkStatement(stmt.body, env, returnType)
                env.loopDepth--
                checkBooleanCondition(stmt.condition, env, "do-while", stmt)
            }
            is JasForStatement -> {
                val forEnv = env.copy()
                if (stmt.init != null) checkStatement(stmt.init, forEnv, returnType)
                if (stmt.condition != null) checkBooleanCondition(stmt.condition, forEnv, "for", stmt)
                if (stmt.update != null) checkExpression(stmt.update, forEnv)
                forEnv.loopDepth++
                checkStatement(stmt.body, forEnv, returnType)
                forEnv.loopDepth--
            }
            is JasForInStatement -> {
                val iterableType = inferExpression(stmt.iterable, env)
                val loopEnv = env.copy()
                loopEnv.set(stmt.varName, (iterableType as? JasArrayType)?.inner)
                loopEnv.loopDepth++
                checkStatement(stmt.body, loopEnv, returnType)
                loopEnv.loopDepth--
                if (stmt.thenBody != null) checkStatement(stmt.thenBody, env, returnType)
                if (stmt.elseBody != null) checkStatement(stmt.elseBody, env, returnType)
            }
            is JasThrow -> inferExpression(stmt.expression, env)
            is JasTry -> {
                checkStatement(stmt.body, env, returnType)
                for (catchClause in stmt.catches) {
                    val catchEnv = env.copy()
                    catchEnv.set(catchClause.parameter.name, catchClause.parameter.type)
                    checkStatement(catchClause.body, catchEnv, returnType)
                }
                if (stmt.finallyBody != null) checkStatement(stmt.finallyBody, env, returnType)
            }
            is JasSwitch -> {
                inferExpression(stmt.expression, env)
                if (stmt.cases.isEmpty()) {
                    error("Switch must have at least one case", stmt)
                }
                env.switchDepth++
                for (caseClause in stmt.cases) {
                    for (value in caseClause.values) {
                        inferExpression(value, env)
                    }
                    checkStatement(caseClause.body, env, returnType)
                }
                env.switchDepth--
            }
            is JasBreakStatement -> {
                if (env.loopDepth == 0 && env.switchDepth == 0) {
                    error("Break outside loop or switch", stmt)
                }
            }
            is JasContinueStatement -> {
                if (env.loopDepth == 0) {
                    error("Continue outside loop", stmt)
                }
            }
            is JasDefer -> {
                checkStatement(stmt.body, env, returnType)
            }
            is JasLock -> {
                inferExpression(stmt.expression, env)
                checkStatement(stmt.body, env, returnType)
            }
            is JasAssert -> {
                checkBooleanCondition(stmt.condition, env, "assert", stmt)
                if (stmt.message != null) inferExpression(stmt.message, env)
            }
            is JasYield -> {
                error("yield is not yet supported (generator functions are not implemented)", stmt)
                inferExpression(stmt.expression, env)
            }
            is JasLabeledStatement -> {
                checkStatement(stmt.statement, env, returnType)
            }
            is JasDestructuringDeclaration -> {
                val initType = inferExpression(stmt.initializer, env)
                for (binding in stmt.bindings) {
                    env.set(binding.name, binding.type ?: initType)
                }
            }
            is JasMatch -> {
                inferExpression(stmt.expression, env)
                for (matchCase in stmt.cases) {
                    checkMatchCase(matchCase, env, returnType)
                }
            }
            else -> {}
        }
    }

    private fun checkMatchCase(matchCase: JasMatchCase, env: TypeEnvironment, returnType: JasType?) {
        if (matchCase.pattern != null) {
            when (val pattern = matchCase.pattern) {
                is JasWildcardPattern -> {}
                is JasPattern -> {}
                is JasExpression -> inferExpression(pattern, env)
                else -> {}
            }
        }
        if (matchCase.guard != null) {
            checkBooleanCondition(matchCase.guard, env, "match guard", matchCase)
        }
        checkStatement(matchCase.body, env, returnType)
    }

    private fun checkBooleanCondition(expr: JasExpression, env: TypeEnvironment, context: String, node: JasNode) {
        val type = inferExpression(expr, env)
        if (type != null && !typesCompatible(type, JasPrimitiveType("bool"))) {
            error("$context condition must be bool, got ${typeDisplayName(type)}", node)
        }
    }

    private fun checkExpression(expr: JasExpression, env: TypeEnvironment) {
        inferExpression(expr, env)
    }

    private fun inferExpression(expr: JasExpression, env: TypeEnvironment): JasType? {
        return when (expr) {
            is JasIntLiteral -> {
                val raw = expr.raw
                if (raw.endsWith("L") || raw.endsWith("l")) {
                    JasPrimitiveType("int64")
                } else {
                    JasPrimitiveType("int32")
                }
            }
            is JasFloatLiteral -> JasPrimitiveType("float64")
            is JasStringLiteral -> JasStringType
            is JasBoolLiteral -> JasPrimitiveType("bool")
            is JasNullLiteral -> JasNullableType(JasAnyType)
            is JasIdentifier -> {
                if (env.contains(expr.name)) {
                    env.get(expr.name)
                } else if (env.hasTypeParameter(expr.name)) {
                    val tp = env.getTypeParameter(expr.name)!!
                    tp.bound ?: JasAnyType
                } else if (symbolTable != null) {
                    if (symbolTable.findClass(expr.name) != null ||
                        symbolTable.findInterface(expr.name) != null ||
                        symbolTable.findEnum(expr.name) != null ||
                        symbolTable.findAnnotation(expr.name) != null) {
                        JasNamedType(expr.name)
                    } else if (symbolTable.findFunctions(expr.name).isNotEmpty()) {
                        // Function reference - return function type
                        JasFunctionType(emptyList(), JasAnyType)
                    } else {
                        warn("Unresolved reference: '${expr.name}'", expr)
                        null
                    }
                } else {
                    warn("Unresolved reference: '${expr.name}' (no symbol table)", expr)
                    null
                }
            }
            is JasBinaryOp -> {
                val leftType = inferExpression(expr.left, env)
                val rightType = inferExpression(expr.right, env)
                when (expr.op) {
                    "&&", "||" -> JasPrimitiveType("bool")
                    "==", "!=" -> JasPrimitiveType("bool")
                    "<", ">", "<=", ">=" -> JasPrimitiveType("bool")
                    else -> leftType ?: rightType ?: JasPrimitiveType("int32")
                }
            }
            is JasUnaryOp -> {
                inferExpression(expr.operand, env)
                when (expr.op) {
                    "!" -> JasPrimitiveType("bool")
                    else -> inferExpression(expr.operand, env) ?: JasPrimitiveType("int32")
                }
            }
            is JasCall -> {
                val argTypes = expr.args.map { inferExpression(it, env) }
                if (symbolTable != null && expr.target is JasIdentifier) {
                    val name = (expr.target as JasIdentifier).name
                    val funcs = symbolTable.findFunctions(name)
                    if (funcs.isEmpty()) {
                        JasAnyType
                    } else {
                        val byArity = funcs.filter { it.parameters.size == expr.args.size }
                        if (byArity.isEmpty()) {
                            error("Function '$name' expects ${funcs.joinToString(" or ") { it.parameters.size.toString() }} argument(s), got ${expr.args.size}", expr)
                            funcs.first().returnType ?: JasAnyType
                        } else {
                            val compatible = byArity.firstOrNull { fn ->
                                fn.parameters.zip(argTypes).all { (param, argType) ->
                                    param.type == null || argType == null ||
                                    typesCompatible(argType, param.type) ||
                                    (param.type is JasNamedType && fn.typeParameters.any { tp -> tp.name == (param.type as JasNamedType).name && (param.type as JasNamedType).typeArguments.isEmpty() })
                                }
                            }
                            if (compatible == null) {
                                val signature = byArity.joinToString(" or ") { fn ->
                                    "(${fn.parameters.joinToString(", ") { p -> p.type?.let(::typeDisplayName) ?: "any" }})"
                                }
                                error("Function '$name' argument types do not match expected $signature", expr)
                                byArity.first().returnType ?: JasAnyType
                            } else {
                                if (compatible.typeParameters.isNotEmpty()) {
                                    val typeParamNames = compatible.typeParameters.map { it.name }.toSet()
                                    val inferred = mutableMapOf<String, JasType>()
                                    for ((param, argType) in compatible.parameters.zip(argTypes)) {
                                        if (param.type != null && argType != null) {
                                            unify(argType, param.type, typeParamNames, inferred)
                                        }
                                    }
                                    if (inferred.isNotEmpty()) {
                                        expr.inferredTypeArgs = inferred.toMap()
                                    }
                                    for (wc in compatible.whereConstraints) {
                                        val boundType = inferred[wc.typeParamName]
                                        if (boundType != null && !typesCompatible(boundType, wc.bound)) {
                                            error("Type parameter '${wc.typeParamName}' does not satisfy constraint '${typeDisplayName(wc.bound)}'", expr)
                                        }
                                    }
                                    val substitutedReturn = if (inferred.isNotEmpty() && compatible.returnType != null) {
                                        substitute(compatible.returnType, inferred)
                                    } else {
                                        compatible.returnType
                                    }
                                    substitutedReturn ?: JasUnitType
                                } else {
                                    compatible.returnType ?: JasUnitType
                                }
                            }
                        }
                    }
                } else {
                    val targetType = inferExpression(expr.target, env)
                    if (targetType is JasFunctionType) {
                        if (targetType.paramTypes.size != expr.args.size) {
                            error("Function value expects ${targetType.paramTypes.size} argument(s), got ${expr.args.size}", expr)
                        } else {
                            targetType.paramTypes.zip(argTypes).forEachIndexed { index, (expected, actual) ->
                                if (actual != null && !typesCompatible(actual, expected)) {
                                    error("Function argument ${index + 1} type mismatch: expected ${typeDisplayName(expected)}, got ${typeDisplayName(actual)}", expr)
                                }
                            }
                        }
                        targetType.returnType
                    } else {
                        JasAnyType
                    }
                }
            }
            is JasPropertyAccess -> {
                val targetType = inferExpression(expr.target, env)
                when {
                    targetType is JasNamedType -> {
                        findMemberType(targetType.name, expr.property) ?: run {
                            warn("Cannot resolve member '${expr.property}' on type '${targetType.name}'", expr)
                            JasAnyType
                        }
                    }
                    targetType is JasArrayType && expr.property == "length" -> JasPrimitiveType("int32")
                    targetType != null -> {
                        val typeName = typeDisplayName(targetType)
                        warn("Property access '${expr.property}' on non-named type '$typeName'", expr)
                        JasAnyType
                    }
                    else -> {
                        warn("Cannot resolve property '${expr.property}' on unresolved target", expr)
                        JasAnyType
                    }
                }
            }
            is JasArrayAccess -> {
                val targetType = inferExpression(expr.target, env)
                val indexType = inferExpression(expr.index, env)
                if (indexType != null && !typesCompatible(indexType, JasPrimitiveType("int32"))) {
                    error("Array index must be int32, got ${typeDisplayName(indexType)}", expr)
                }
                when (targetType) {
                    is JasArrayType -> targetType.inner
                    is JasStringType -> JasPrimitiveType("int32")
                    null -> {
                        warn("Array access on unresolved type", expr)
                        JasAnyType
                    }
                    else -> {
                        warn("Array access on non-array type ${typeDisplayName(targetType)}", expr)
                        JasAnyType
                    }
                }
            }
            is JasNew -> {
                if (expr.type is JasNamedType) {
                    val namedType = expr.type as JasNamedType
                    checkTypeArgumentCount(namedType.name, namedType.typeArguments, expr)
                }
                expr.type
            }
            is JasArrayCreation -> JasArrayType(expr.type)
            is JasLambdaExpr -> {
                val lambdaEnv = env.copy()
                for (param in expr.parameters) {
                    lambdaEnv.set(param.name, param.type)
                }
                for (stmt in expr.body.statements) {
                    checkStatement(stmt, lambdaEnv, null)
                }
                JasFunctionType(
                    expr.parameters.map { it.type ?: JasAnyType },
                    inferLambdaReturnType(expr.body, lambdaEnv) ?: JasAnyType
                )
            }
            is JasMethodReference -> JasFunctionType(emptyList(), JasAnyType)
            is JasTernaryExpr -> {
                inferExpression(expr.condition, env)
                val thenType = inferExpression(expr.thenExpr, env)
                val elseType = inferExpression(expr.elseExpr, env)
                thenType ?: elseType ?: JasAnyType
            }
            is JasCastExpr -> {
                if (expr.type is JasNamedType) {
                    checkTypeArgumentCount((expr.type as JasNamedType).name, (expr.type as JasNamedType).typeArguments, expr)
                }
                expr.type
            }
            is JasInstanceOfExpr -> JasPrimitiveType("bool")
            is JasNullCoalescing -> {
                val leftType = inferExpression(expr.left, env)
                val rightType = inferExpression(expr.right, env)
                when (leftType) {
                    is JasNullableType -> leftType.inner
                    null -> rightType ?: JasAnyType
                    else -> leftType
                }
            }
            is JasStringTemplate -> JasStringType
            is JasDictLiteral -> JasNamedType("Map")
            is JasAssignment -> {
                val valueType = inferExpression(expr.value, env)
                val targetType = inferExpression(expr.target, env)
                if (valueType != null && targetType != null && !typesCompatible(valueType, targetType)) {
                    error("Type mismatch: cannot assign ${typeDisplayName(valueType)} to ${typeDisplayName(targetType)}", expr)
                }
                targetType ?: JasAnyType
            }
            else -> {
                warn("Unhandled expression type: ${expr::class.simpleName}", expr)
                JasAnyType
            }
        }
    }

    private fun findMemberType(typeName: String, memberName: String): JasType? {
        val cls = symbolTable?.findClass(typeName)
        if (cls != null) {
            for (member in cls.members) {
                if (member is JasProperty && member.name == memberName) return member.type
                if (member is JasFunction && member.name == memberName) {
                    return JasFunctionType(member.parameters.map { it.type ?: JasAnyType }, member.returnType ?: JasUnitType)
                }
            }
        }

        val iface = symbolTable?.findInterface(typeName)
        if (iface != null) {
            for (member in iface.members) {
                if (member is JasProperty && member.name == memberName) return member.type
                if (member is JasFunction && member.name == memberName) {
                    return JasFunctionType(member.parameters.map { it.type ?: JasAnyType }, member.returnType ?: JasUnitType)
                }
            }
        }

        return null
    }

    private fun inferLambdaReturnType(body: JasBlock, env: TypeEnvironment): JasType? {
        for (stmt in body.statements) {
            when (stmt) {
                is JasReturn -> return stmt.value?.let { inferExpression(it, env) } ?: JasUnitType
                is JasBlock -> inferLambdaReturnType(stmt, env)?.let { return it }
                is JasIf -> {
                    val thenType = inferLambdaReturnType(stmt.thenBody, env)
                    val elseType = stmt.elseBody?.let { inferLambdaReturnType(it, env) }
                    if (thenType != null && elseType != null && typesCompatible(thenType, elseType)) {
                        return thenType
                    }
                }
            }
        }
        return null
    }

    private fun unify(
        argType: JasType,
        paramType: JasType,
        typeParams: Set<String>,
        inferred: MutableMap<String, JasType>
    ): Boolean {
        return when {
            paramType is JasNamedType && paramType.name in typeParams && paramType.typeArguments.isEmpty() -> {
                val existing = inferred[paramType.name]
                if (existing != null) {
                    typesCompatible(argType, existing)
                } else {
                    inferred[paramType.name] = argType
                    true
                }
            }
            paramType is JasNamedType && argType is JasNamedType && paramType.name == argType.name -> {
                paramType.typeArguments.zip(argType.typeArguments).all { (p, a) ->
                    unify(a, p, typeParams, inferred)
                }
            }
            typesCompatible(argType, paramType) -> true
            else -> false
        }
    }

    private fun substitute(type: JasType, mapping: Map<String, JasType>): JasType {
        return when (type) {
            is JasNamedType -> {
                val sub = mapping[type.name]
                if (sub != null && type.typeArguments.isEmpty()) {
                    sub
                } else {
                    val newArgs = type.typeArguments.map { substitute(it, mapping) }
                    if (newArgs != type.typeArguments) JasNamedType(type.name, newArgs) else type
                }
            }
            is JasWildcardType -> {
                val newBound = type.bound?.let { substitute(it, mapping) }
                if (newBound != type.bound) JasWildcardType(newBound, type.extends) else type
            }
            else -> type
        }
    }

    private fun typeDisplayName(type: JasType): String = when (type) {
        is JasPrimitiveType -> type.name.lowercase()
        is JasNamedType -> type.name
        is JasStringType -> "string"
        is JasUnitType -> "void"
        is JasAnyType -> "any"
        is JasArrayType -> "${typeDisplayName(type.inner)}[]"
        is JasNullableType -> "${typeDisplayName(type.inner)}?"
        is JasNonNullType -> "${typeDisplayName(type.inner)}!"
        is JasFunctionType -> "(...) -> ${typeDisplayName(type.returnType)}"
        is JasTupleType -> "(${type.types.joinToString(", ") { typeDisplayName(it) }})"
        is JasWildcardType -> if (type.bound != null) "? ${if (type.extends) "extends" else "super"} ${typeDisplayName(type.bound)}" else "?"
        is JasPointerType -> "*${typeDisplayName(type.inner)}"
        is JasReferenceType -> "&${typeDisplayName(type.inner)}"
        else -> "unknown"
    }

    private fun typesCompatible(actual: JasType, expected: JasType): Boolean {
        if (actual == expected) return true
        if (canonicalTypeName(actual) == canonicalTypeName(expected)) return true
        // Nullability: assigning nullable to non-null is rejected
        if (actual is JasNullableType && expected !is JasNullableType) {
            return false
        }
        if (expected is JasAnyType || actual is JasAnyType) return true
        if (actual is JasNamedType && actual.name == "Any") return true
        if (expected is JasNamedType && expected.name == "Any") return true
        if (actual is JasNamedType && expected is JasNamedType && mapTypesCompatible(actual.name, expected.name)) return true
        // Interface subtype checking
        if (actual is JasNamedType && expected is JasNamedType && isSubtype(actual.name, expected.name)) return true
        // Numeric widening conversions
        if (isNumericWidening(actual, expected)) return true
        if (actual is JasNullableType && expected is JasNullableType) {
            return typesCompatible(actual.inner, expected.inner)
        }
        if (expected is JasNullableType) {
            return typesCompatible(actual, expected.inner)
        }
        if (actual is JasArrayType && expected is JasArrayType) {
            return typesCompatible(actual.inner, expected.inner)
        }
        return false
    }

    private fun isSubtype(subName: String, superName: String, visited: MutableSet<String> = mutableSetOf()): Boolean {
        if (subName == superName) return true
        if (!visited.add(subName)) return false
        // Check direct class implements interfaces
        val ifaces = classImplements[subName]
        if (ifaces != null) {
            if (superName in ifaces) return true
            // Transitively check each implemented interface's parent hierarchy
            for (iface in ifaces) {
                if (isSubtype(iface, superName, visited)) return true
            }
        }
        // Check interface extends hierarchy
        val parents = interfaceExtends[subName]
        if (parents != null) {
            for (parent in parents) {
                if (isSubtype(parent, superName, visited)) return true
            }
        }
        return false
    }

    private val numericWidening = mapOf(
        "int32" to listOf("int64", "float64"),
        "int64" to listOf("float64"),
        "float32" to listOf("float64"),
        "byte" to listOf("short", "int32", "int64", "float32", "float64"),
        "short" to listOf("int32", "int64", "float32", "float64"),
        "char" to listOf("int32", "int64", "float32", "float64")
    )

    private fun isNumericWidening(actual: JasType, expected: JasType): Boolean {
        val actualName = canonicalTypeName(actual) ?: return false
        val expectedName = canonicalTypeName(expected) ?: return false
        val targets = numericWidening[actualName] ?: return false
        return expectedName in targets
    }

    private fun canonicalTypeName(type: JasType): String? {
        return when (type) {
            is JasPrimitiveType -> when (type.name.lowercase()) {
                "bool", "boolean" -> "bool"
                "int", "int32" -> "int32"
                else -> type.name.lowercase()
            }
            is JasNamedType -> when (type.name) {
                "Bool", "Boolean", "boolean", "bool" -> "bool"
                "String", "string", "java.lang.String" -> "string"
                "Int", "int", "int32" -> "int32"
                "Map", "HashMap", "java.util.Map", "java.util.HashMap" -> "map"
                else -> type.name
            }
            is JasStringType -> "string"
            is JasUnitType -> "void"
            else -> null
        }
    }

    private fun mapTypesCompatible(actual: String, expected: String): Boolean {
        val mapNames = setOf("Map", "HashMap", "java.util.Map", "java.util.HashMap")
        return actual in mapNames && expected in mapNames
    }
}
