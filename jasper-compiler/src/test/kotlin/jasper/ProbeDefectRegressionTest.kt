package jasper

import jasper.compiler.JasperCompiler
import java.net.URLClassLoader
import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.Paths
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * Regression tests for the first batch of local compiler defects reported by the
 * JDBC-shaped probe (`probes/jdbc/FINDINGS.md`): #4, #5, #11, #15 and #17.
 *
 * Every test compiles real Jasper source through the full pipeline
 * (ANTLR -> AST -> checker -> IR -> JVM bytecode) and runs the resulting class,
 * so a silent bad-bytecode regression fails the JVM verifier here rather than in
 * a user's program. Output is written under this module's own `build/scratch/`
 * directory (never an external temp path).
 */
class ProbeDefectRegressionTest {

    private fun scratchDir(name: String): Path {
        val base = Paths.get("build", "scratch", "probe-defects", name)
        if (Files.exists(base)) base.toFile().deleteRecursively()
        Files.createDirectories(base)
        return base
    }

    private fun compile(source: String, name: String): Path {
        val outputDir = scratchDir(name)
        val result = JasperCompiler().compileToDirWithIr(source, outputDir)
        assertTrue(
            result is JasperCompiler.CompileResult.Success,
            "compilation failed for $name: " + (result as? JasperCompiler.CompileResult.Error)?.message.orEmpty()
        )
        return outputDir
    }

    private fun loader(outputDir: Path): URLClassLoader =
        URLClassLoader(arrayOf(outputDir.toUri().toURL()))

    private fun invokeStatic(outputDir: Path, className: String, method: String): Any? {
        val clazz = loader(outputDir).loadClass(className)
        val target = clazz.methods.first { it.name == method && it.parameterCount == 0 }
        return target.invoke(null)
    }

    /** #4: a class may call its own instance methods through `this.` */
    @Test
    fun `this method call resolves to the enclosing class`() {
        val source = """
            package test;
            public class T {
                private function val(): int32 { return 7; }
                public function run(): int32 { return this.val() + 1; }
            }
            public function main(): int32 { var t = new T(); return t.run(); }
        """.trimIndent()
        val out = compile(source, "this-call")
        assertEquals(8, invokeStatic(out, "test.main", "main"))
    }

    /** #5: a void free function is legal in statement position */
    @Test
    fun `void free function can be called as a statement`() {
        val source = """
            package test;
            function sink(v: int32): void { }
            public function main(): int32 { sink(1); return 0; }
        """.trimIndent()
        val out = compile(source, "void-statement-call")
        assertEquals(0, invokeStatic(out, "test.main", "main"))
    }

    /** #11: interface constants are visible as fields and readable */
    @Test
    fun `interface constant is emitted and readable`() {
        val source = """
            package test;
            public interface Limits {
                public const MAX_ROWS: int32 = 100;
                public function close(): void;
            }
            public function main(): int32 { return Limits.MAX_ROWS; }
        """.trimIndent()
        val out = compile(source, "interface-constant")
        val clazz = loader(out).loadClass("test.Limits")
        val constantField = clazz.declaredFields.firstOrNull { it.name == "MAX_ROWS" }
        assertTrue(constantField != null, "interface constant MAX_ROWS should be emitted as a field")
        assertTrue(
            java.lang.reflect.Modifier.isStatic(constantField.modifiers) &&
                java.lang.reflect.Modifier.isFinal(constantField.modifiers),
            "interface constant must be static final, got modifiers=${constantField.modifiers}"
        )
        assertEquals(100, invokeStatic(out, "test.main", "main"))
    }

    /** #15: `defer` is scoped to the function that declares it */
    @Test
    fun `defer does not leak into later functions`() {
        val source = """
            package test;
            function first(sb: java.lang.StringBuilder): int32 {
                defer { sb.append("F"); }
                sb.append("a");
                return 1;
            }
            function second(sb: java.lang.StringBuilder): int32 {
                return 2;
            }
            public function main(): string {
                var sb = new java.lang.StringBuilder();
                var x: int32 = first(sb);
                var y: int32 = second(sb);
                sb.append(x + y);
                return sb.toString();
            }
        """.trimIndent()
        val out = compile(source, "defer-scope")
        assertEquals("aF3", invokeStatic(out, "test.main", "main"))
    }

    /** #17: `string.equals` compares by value and is callable */
    @Test
    fun `string equals uses value semantics`() {
        val source = """
            package test;
            public function main(): int32 {
                var hits: int32 = 0;
                if ("a".equals("a")) { hits = hits + 1; }
                if ("a".equals("b")) { hits = hits + 100; }
                return hits;
            }
        """.trimIndent()
        val out = compile(source, "string-equals")
        assertEquals(1, invokeStatic(out, "test.main", "main"))
    }
}
