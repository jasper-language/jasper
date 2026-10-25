// S4-C 阶段接线：真实 ANTLR 解析 -> 类型检查 -> 挂起分段，不生成协程字节码。
package jasper

import jasper.ast.*
import jasper.checker.TypeChecker
import jasper.compiler.SymbolTable
import jasper.parser.JasperLexer
import jasper.parser.JasperParser
import jasper.translator.LinearSuspensionPlanner
import org.antlr.v4.runtime.CharStreams
import org.antlr.v4.runtime.CommonTokenStream
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/** 不再由测试谓词猜测挂起点，而消费检查器按真实类型身份标注的结果。 */
class SuspensionPlanningIntegrationTest {
    /** 使用权威文法生成的解析器，保留源位置，禁止手写解析或替换 await token。 */
    private fun parse(source: String): JasSourceFile {
        val parser = JasperParser(CommonTokenStream(JasperLexer(CharStreams.fromString(source))))
        val tree = parser.sourceFile()
        assertEquals(0, parser.numberOfSyntaxErrors)
        return JasperAstBuilder().buildSourceFile(tree)
    }

    private fun program() = parse("""
        package demo;
        import std.Future;
        public class Program {
            public static function sample(input: Future<int32>, kept: int32): int32 {
                var sync: int32 = await 1;
                var x: int32 = await input;
                return kept + x + sync;
            }
        }
    """.trimIndent())

    /** 声明足够供类型身份与泛型检查；运行时执行由真实标准库端到端测试覆盖。 */
    private fun symbols(program: JasSourceFile): SymbolTable = SymbolTable().also {
        it.registerBuiltins()
        it.collectDeclarations(parse("package std; public class Future<T> { }"))
        it.collectDeclarations(program)
    }

    private fun function(file: JasSourceFile) =
        (file.declarations.single() as JasClass).members.filterIsInstance<JasFunction>().single()

    /** 普通 await 不切段，Future await 切段，跨段布局与结果类型使用同一检查器事实。 */
    @Test
    fun `parsed typed future flows into suspension planner`() {
        val file = program()
        val errors = TypeChecker(symbols(file)).check(file)
        assertTrue(errors.any { it.message.contains("non-blocking await lowering") })
        assertFalse(errors.any { it.message.contains("type mismatch", ignoreCase = true) }, errors.toString())
        val plan = LinearSuspensionPlanner.plan(function(file))
        assertEquals(null, plan.unsupported)
        val point = plan.points.single()
        assertEquals(1, point.statementIndex)
        assertEquals("x", point.resultBinding)
        assertEquals(JasPrimitiveType("int32"), point.resultType)
        assertEquals(setOf("kept", "sync"), point.liveLocals)
        assertTrue(point.expression.sourceLine > 0)
    }

    /** 同一 AST 重查为普通值时清除旧标记，避免 IDE 或增量编译沿用过时挂起布局。 */
    @Test
    fun `rechecking same body clears outdated suspension facts`() {
        val file = program()
        val checker = TypeChecker(symbols(file))
        checker.check(file)
        assertEquals(1, LinearSuspensionPlanner.plan(function(file)).points.size)
        val original = file.declarations.single() as JasClass
        val changedFunction = function(file).copy(parameters = function(file).parameters.map {
            if (it.name == "input") it.copy(type = JasPrimitiveType("int32")) else it
        })
        val changed = file.copy(declarations = listOf(original.copy(members = listOf(changedFunction))))
        val errors = checker.check(changed)
        assertTrue(errors.isEmpty(), errors.toString())
        assertTrue(LinearSuspensionPlanner.plan(changedFunction).points.isEmpty())
    }
}
