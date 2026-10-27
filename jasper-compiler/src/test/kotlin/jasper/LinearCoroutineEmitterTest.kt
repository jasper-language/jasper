// 内部发射器端到端验证：ANTLR 源函数 -> 生成 AST -> 类型检查 -> Kotlin IR -> JVM。
package jasper

import jasper.ast.*
import jasper.checker.TypeChecker
import jasper.compiler.JasperCompiler
import jasper.compiler.JvmIrBackend
import jasper.compiler.SymbolTable
import jasper.parser.JasperLexer
import jasper.parser.JasperParser
import jasper.stdlib.StdLib
import jasper.translator.JasperToIrTranslator
import jasper.translator.LinearCoroutineEmitter
import org.antlr.v4.runtime.CharStreams
import org.antlr.v4.runtime.CommonTokenStream
import java.net.URLClassLoader
import java.nio.file.Files
import java.nio.file.Paths
import java.util.concurrent.TimeUnit
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertSame
import kotlin.test.assertTrue

/** 验证自动发射的单 await 状态类；不宣称公开源函数调用 ABI 已接通。 */
class LinearCoroutineEmitterTest {
    private fun parse(source: String): JasSourceFile {
        val parser = JasperParser(CommonTokenStream(JasperLexer(CharStreams.fromString(source))))
        val tree = parser.sourceFile()
        assertEquals(0, parser.numberOfSyntaxErrors)
        return JasperAstBuilder().buildSourceFile(tree)
    }

    /** 标准库先经正式解析入口；只把生成 AST 插入 AST->IR 路径，不拼接或重写源文本。 */
    private fun compile(name: String): URLClassLoader {
        val library = JasperCompiler().compileAll(StdLib.sources()).values.map { it.ast }
        val spec = parse("""
            package std;
            public class Spec {
                public static function calculate(input: Future<int32>, kept: int32): int32 {
                    var before: int32 = kept + 1;
                    var got: int32 = await input;
                    var after: int32 = before + got;
                    return after * 2;
                }
            }
        """.trimIndent())
        val symbols = SymbolTable().also { table ->
            table.registerBuiltins()
            (library + spec).forEach(table::collectDeclarations)
        }
        val diagnostics = TypeChecker(symbols).check(spec)
        assertTrue(diagnostics.isNotEmpty())
        assertTrue(diagnostics.all { it.message.contains("non-blocking await lowering") }, diagnostics.toString())
        val function = (spec.declarations.single() as JasClass).members.filterIsInstance<JasFunction>().single()
        val generated = JasSourceFile("std", listOf(LinearCoroutineEmitter.emit(function, "GeneratedFrame")))
        val producer = parse("""
            package std;
            public class FrameProducer implements Runnable {
                private var input: Future<int32>;
                public constructor(input: Future<int32>) { this.input = input; }
                public function run(): void { this.input.complete(10); }
            }
        """.trimIndent())
        val sources = library + generated + producer
        val generatedSymbols = SymbolTable().also { table ->
            table.registerBuiltins(); sources.forEach(table::collectDeclarations)
        }
        assertTrue(generatedSymbols.errors.isEmpty(), generatedSymbols.errors.toString())
        for (file in sources) {
            val errors = TypeChecker(generatedSymbols).check(file)
            assertTrue(errors.isEmpty(), "generated AST check: $errors")
        }
        val translated = JasperToIrTranslator().translateAll(sources, generatedSymbols)
        val output = Paths.get("build", "tmp", "linear-coroutine-emitter", name)
        output.toFile().deleteRecursively()
        Files.createDirectories(output)
        JvmIrBackend(translated.typeMapper ?: jasper.translator.TypeMapper()).generate(translated, output)
        return URLClassLoader(arrayOf(output.toUri().toURL()))
    }

    /** 单工作线程的生产者必须能在消费者挂起后执行；跨挂起字段保存得到 26。 */
    @Test
    fun `generated frame suspends and restores locals on one worker`() {
        compile("pending").use { loader -> runFrame(loader, "pending") }
    }

    /** 已完成、失败和取消均经过生成的恢复段；失败快照保持原对象。 */
    @Test
    fun `generated frame handles ready failed and cancelled outcomes`() {
        compile("terminal").use { loader ->
            for (mode in listOf("ready", "failed", "cancelled")) runFrame(loader, mode)
        }
    }

    /** Kotlin 只做平台边界驱动与有界等待；程序计数器、保存值、恢复逻辑均来自发射器。 */
    private fun runFrame(loader: ClassLoader, mode: String) {
        val futureType = loader.loadClass("std.Future")
        val executor = loader.loadClass("std.JvmExecutor").getConstructor(Int::class.javaPrimitiveType).newInstance(1)
        val input = futureType.getConstructor().newInstance()
        val output = futureType.getConstructor().newInstance()
        val failure = loader.loadClass("std.Failure").getConstructor(String::class.java, String::class.java).newInstance("expected", "error")
        try {
            when (mode) {
                "ready" -> futureType.getMethod("complete", Any::class.java).invoke(input, 10)
                "failed" -> futureType.getMethod("fail", loader.loadClass("std.Failure")).invoke(input, failure)
                "cancelled" -> futureType.getMethod("cancel").invoke(input)
            }
            val frame = loader.loadClass("std.GeneratedFrame").getConstructor(
                loader.loadClass("std.Executor"), futureType, futureType, Int::class.javaPrimitiveType
            ).newInstance(executor, output, input, 2)
            frame.javaClass.getMethod("start").invoke(frame)
            if (mode == "pending") {
                val producer = loader.loadClass("std.FrameProducer").getConstructor(futureType).newInstance(input)
                executor.javaClass.getMethod("schedule", loader.loadClass("std.Runnable")).invoke(executor, producer)
            }
            val done = futureType.getMethod("isDone")
            val deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(5)
            while (done.invoke(output) != true && System.nanoTime() < deadline) Thread.sleep(1)
            assertEquals(true, done.invoke(output), "generated frame did not complete: $mode")
            when (mode) {
                "pending", "ready" -> assertEquals(26, futureType.getMethod("get").invoke(output))
                "failed" -> assertSame(failure, futureType.getMethod("failureOrNull").invoke(output))
                "cancelled" -> assertEquals("Cancelled", futureType.getMethod("status").invoke(output).toString())
            }
        } finally {
            executor.javaClass.getMethod("shutdown").invoke(executor)
            assertEquals(true, executor.javaClass.getMethod("awaitTermination", Long::class.javaPrimitiveType).invoke(executor, 5000L))
        }
    }
}
