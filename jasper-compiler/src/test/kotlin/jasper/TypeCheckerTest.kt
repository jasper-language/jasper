package jasper

import jasper.checker.TypeChecker
import jasper.compiler.SymbolTable
import jasper.ast.*
import kotlin.test.Test
import kotlin.test.assertTrue
import kotlin.test.assertEquals
import kotlin.test.assertFalse

class TypeCheckerTest {

    private fun check(sourceFile: JasSourceFile, symbolTable: SymbolTable? = null): List<jasper.checker.TypeError> {
        val checker = TypeChecker(symbolTable)
        return checker.check(sourceFile)
    }

    @Test
    fun `non boolean if condition reports error`() {
        val block = JasBlock(listOf(
            JasIf(JasIntLiteral(1L, "1"), JasBlock(emptyList()), null)
        ))
        val func = JasFunction("main", emptyList(), JasUnitType, block, emptyList())
        val file = JasSourceFile(null, listOf(func))
        val errors = check(file)
        assertTrue(errors.any { it.message.contains("condition must be bool") },
            "Expected non-bool if condition error, got: ${errors.joinToString { it.message }}")
    }

    @Test
    fun `return type mismatch reports error`() {
        val block = JasBlock(listOf(JasReturn(JasStringLiteral("nope"))))
        val func = JasFunction("main", emptyList(), JasPrimitiveType("int32"), block, emptyList())
        val file = JasSourceFile(null, listOf(func))
        val errors = check(file)
        assertTrue(errors.any { it.message.contains("Return type mismatch") },
            "Expected return type mismatch error, got: ${errors.joinToString { it.message }}")
    }

    @Test
    fun `break outside loop reports error`() {
        val block = JasBlock(listOf(JasBreakStatement(null)))
        val func = JasFunction("main", emptyList(), JasUnitType, block, emptyList())
        val file = JasSourceFile(null, listOf(func))
        val errors = check(file)
        assertTrue(errors.any { it.message.contains("Break outside") },
            "Expected break outside loop error, got: ${errors.joinToString { it.message }}")
    }

    @Test
    fun `continue outside loop reports error`() {
        val block = JasBlock(listOf(JasContinueStatement(null)))
        val func = JasFunction("main", emptyList(), JasUnitType, block, emptyList())
        val file = JasSourceFile(null, listOf(func))
        val errors = check(file)
        assertTrue(errors.any { it.message.contains("Continue outside") },
            "Expected continue outside loop error, got: ${errors.joinToString { it.message }}")
    }

    @Test
    fun `duplicate local declaration reports error`() {
        val block = JasBlock(listOf(
            JasVariableStatement("x", JasPrimitiveType("int32"), JasIntLiteral(1L, "1")),
            JasVariableStatement("x", JasPrimitiveType("int32"), JasIntLiteral(2L, "2"))
        ))
        val func = JasFunction("main", emptyList(), JasUnitType, block, emptyList())
        val file = JasSourceFile(null, listOf(func))
        val errors = check(file)
        assertTrue(errors.any { it.message.contains("Duplicate local declaration") },
            "Expected duplicate local error, got: ${errors.joinToString { it.message }}")
    }

    @Test
    fun `assign incompatible types reports error`() {
        val block = JasBlock(listOf(
            JasVariableStatement("x", JasPrimitiveType("int32"), JasStringLiteral("hello"))
        ))
        val func = JasFunction("main", emptyList(), JasUnitType, block, emptyList())
        val file = JasSourceFile(null, listOf(func))
        val errors = check(file)
        assertTrue(errors.any { it.message.contains("Type mismatch") },
            "Expected type mismatch error, got: ${errors.joinToString { it.message }}")
    }

    @Test
    fun `void return from non-void function reports error`() {
        val block = JasBlock(listOf(JasReturn(null)))
        val func = JasFunction("main", emptyList(), JasPrimitiveType("int32"), block, emptyList())
        val file = JasSourceFile(null, listOf(func))
        val errors = check(file)
        assertTrue(errors.any { it.message.contains("Return type mismatch") },
            "Expected void return error, got: ${errors.joinToString { it.message }}")
    }

    @Test
    fun `numeric widening int32 to int64 is compatible`() {
        val block = JasBlock(listOf(
            JasVariableStatement("x", JasPrimitiveType("int64"), JasIntLiteral(42L, "42"))
        ))
        val func = JasFunction("main", emptyList(), JasUnitType, block, emptyList())
        val file = JasSourceFile(null, listOf(func))
        val errors = check(file)
        assertFalse(errors.any { it.message.contains("Type mismatch") },
            "int32 literal should widen to int64, got errors: ${errors.joinToString { it.message }}")
    }

    @Test
    fun `switch with no cases reports error`() {
        val block = JasBlock(listOf(
            JasSwitch(JasIntLiteral(1L, "1"), emptyList())
        ))
        val func = JasFunction("main", emptyList(), JasUnitType, block, emptyList())
        val file = JasSourceFile(null, listOf(func))
        val errors = check(file)
        assertTrue(errors.any { it.message.contains("Switch must have at least one case") },
            "Expected switch must have case error, got: ${errors.joinToString { it.message }}")
    }

    @Test
    fun `duplicate function parameter reports error`() {
        val func = JasFunction(
            "test", 
            listOf(JasParameter("x", JasPrimitiveType("int32")), JasParameter("x", JasPrimitiveType("int32"))),
            JasUnitType, JasBlock(emptyList()), emptyList()
        )
        val file = JasSourceFile(null, listOf(func))
        val errors = check(file)
        assertTrue(errors.any { it.message.contains("Duplicate parameter") },
            "Expected duplicate parameter error, got: ${errors.joinToString { it.message }}")
    }

    @Test
    fun `array index must be int32 reports error`() {
        val block = JasBlock(listOf(
            JasVariableStatement("arr", JasArrayType(JasPrimitiveType("int32")), null),
            JasExpressionStatement(JasArrayAccess(JasIdentifier("arr"), JasStringLiteral("bad")))
        ))
        val func = JasFunction("main", emptyList(), JasUnitType, block, emptyList())
        val file = JasSourceFile(null, listOf(func))
        val errors = check(file)
        assertTrue(errors.any { it.message.contains("Array index must be int32") },
            "Expected array index error, got: ${errors.joinToString { it.message }}")
    }

    @Test
    fun `duplicate top level function with same arity reports error`() {
        val func1 = JasFunction("test", listOf(JasParameter("x", JasPrimitiveType("int32"))), JasUnitType, JasBlock(emptyList()), emptyList())
        val func2 = JasFunction("test", listOf(JasParameter("x", JasPrimitiveType("int32"))), JasUnitType, JasBlock(emptyList()), emptyList())
        val file = JasSourceFile(null, listOf(func1, func2))
        val errors = check(file)
        assertTrue(errors.any { it.message.contains("Duplicate function") },
            "Expected duplicate function error, got: ${errors.joinToString { it.message }}")
    }

    @Test
    fun `assign int32 to float64 is valid widening`() {
        val block = JasBlock(listOf(
            JasVariableStatement("x", JasPrimitiveType("float64"), JasIntLiteral(42L, "42"))
        ))
        val func = JasFunction("main", emptyList(), JasUnitType, block, emptyList())
        val file = JasSourceFile(null, listOf(func))
        val errors = check(file)
        assertFalse(errors.any { it.message.contains("Type mismatch") },
            "int32 should widen to float64, got errors: ${errors.joinToString { it.message }}")
    }

    @Test
    fun `bool condition in while loop is valid`() {
        val block = JasBlock(listOf(
            JasWhile(JasBoolLiteral(true), JasBlock(emptyList()))
        ))
        val func = JasFunction("main", emptyList(), JasUnitType, block, emptyList())
        val file = JasSourceFile(null, listOf(func))
        val errors = check(file)
        assertFalse(errors.any { it.message.contains("condition must be bool") },
            "Bool while condition should be valid, got errors: ${errors.joinToString { it.message }}")
    }

    @Test
    fun `assign nullable to non-null reports error`() {
        val block = JasBlock(listOf(
            JasVariableStatement("x", JasNullableType(JasPrimitiveType("int32")), JasIntLiteral(42L, "42")),
            JasVariableStatement("y", JasPrimitiveType("int32"), JasIdentifier("x"))
        ))
        val func = JasFunction("main", emptyList(), JasUnitType, block, emptyList())
        val file = JasSourceFile(null, listOf(func))
        val errors = check(file)
        assertTrue(errors.any { it.message.contains("Type mismatch") || it.message.contains("assign") },
            "Expected nullable-to-non-null type error, got: ${errors.joinToString { it.message }}")
    }

    @Test
    fun `return nullable from non-null function reports error`() {
        val block = JasBlock(listOf(
            JasReturn(JasNullLiteral)
        ))
        val func = JasFunction("main", emptyList(), JasPrimitiveType("int32"), block, emptyList())
        val file = JasSourceFile(null, listOf(func))
        val errors = check(file)
        assertTrue(errors.any { it.message.contains("Return type mismatch") },
            "Expected return type mismatch for nullable->non-null, got: ${errors.joinToString { it.message }}")
    }

    @Test
    fun `assign non-null to nullable is valid`() {
        val block = JasBlock(listOf(
            JasVariableStatement("x", JasNullableType(JasPrimitiveType("int32")), JasIntLiteral(42L, "42"))
        ))
        val func = JasFunction("main", emptyList(), JasUnitType, block, emptyList())
        val file = JasSourceFile(null, listOf(func))
        val errors = check(file)
        assertFalse(errors.any { it.message.contains("Type mismatch") },
            "Non-null to nullable should be valid, got errors: ${errors.joinToString { it.message }}")
    }

    @Test
    fun `assign int32 to int64 is valid widening`() {
        val block = JasBlock(listOf(
            JasVariableStatement("x", JasPrimitiveType("int64"), JasIntLiteral(42L, "42"))
        ))
        val func = JasFunction("main", emptyList(), JasUnitType, block, emptyList())
        val file = JasSourceFile(null, listOf(func))
        val errors = check(file)
        assertFalse(errors.any { it.message.contains("Type mismatch") },
            "int32 should widen to int64, got errors: ${errors.joinToString { it.message }}")
    }

    @Test
    fun `function call arity mismatch reports error`() {
        val symbolTable = SymbolTable()
        symbolTable.collectDeclarations(JasSourceFile(null, listOf(
            JasFunction("add", listOf(JasParameter("a", JasPrimitiveType("int32")), JasParameter("b", JasPrimitiveType("int32"))), JasPrimitiveType("int32"), JasBlock(emptyList()), emptyList())
        )))
        val block = JasBlock(listOf(
            JasExpressionStatement(JasCall(JasIdentifier("add"), listOf(JasIntLiteral(1L, "1"))))
        ))
        val func = JasFunction("main", emptyList(), JasUnitType, block, emptyList())
        val file = JasSourceFile(null, listOf(func))
        val errors = check(file, symbolTable)
        assertTrue(errors.any { it.message.contains("expects") && it.message.contains("argument") },
            "Expected arity mismatch error, got: ${errors.joinToString { it.message }}")
    }

    @Test
    fun `duplicate type parameter reports error`() {
        val func = JasFunction(
            "test", emptyList(), JasUnitType, JasBlock(emptyList()), emptyList(),
            typeParameters = listOf(
                JasTypeParameter("T", JasNamedType("Number")),
                JasTypeParameter("T", JasNamedType("Number"))
            )
        )
        val file = JasSourceFile(null, listOf(func))
        val errors = check(file)
        assertTrue(errors.any { it.message.contains("Duplicate type parameter") },
            "Expected duplicate type parameter error, got: ${errors.joinToString { it.message }}")
    }

    @Test
    fun `where constraint on unknown type parameter reports error`() {
        val func = JasFunction(
            "test", emptyList(), JasUnitType, JasBlock(emptyList()), emptyList(),
            whereConstraints = listOf(JasTypeConstraint("U", JasNamedType("Number")))
        )
        val file = JasSourceFile(null, listOf(func))
        val errors = check(file)
        assertTrue(errors.any { it.message.contains("unknown type parameter") },
            "Expected unknown type parameter in where constraint error, got: ${errors.joinToString { it.message }}")
    }

    @Test
    fun `type reference in class member validated`() {
        val st = SymbolTable()
        st.collectDeclarations(JasSourceFile(null, listOf(
            JasClass("Box", emptyList(), listOf(JasTypeParameter("T", null)), null, emptyList(), emptyList(), emptyList())
        )))
        val cls = JasClass(
            "Holder", emptyList(), emptyList(), null, emptyList(),
            listOf(JasProperty("item", JasNamedType("Box", listOf(JasNamedType("String"))), emptyList(), null, null)),
            emptyList()
        )
        val file = JasSourceFile(null, listOf(cls))
        val errors = check(file, st)
        assertFalse(errors.any { it.message.contains("expects") },
            "Box<String> should be valid with 1 type arg, got errors: ${errors.joinToString { it.message }}")
    }

    @Test
    fun `type argument count mismatch on named type reports error`() {
        val st = SymbolTable()
        st.collectDeclarations(JasSourceFile(null, listOf(
            JasClass("Box", emptyList(), listOf(JasTypeParameter("T", null)), null, emptyList(), emptyList(), emptyList())
        )))
        val cls = JasClass(
            "Holder", emptyList(), emptyList(), null, emptyList(),
            listOf(JasProperty("item", JasNamedType("Box", listOf(JasNamedType("String"), JasPrimitiveType("int32"))), emptyList(), null, null)),
            emptyList()
        )
        val file = JasSourceFile(null, listOf(cls))
        val errors = check(file, st)
        assertTrue(errors.any { it.message.contains("expects") && it.message.contains("2") },
            "Expected type argument count mismatch error, got: ${errors.joinToString { it.message }}")
    }
}