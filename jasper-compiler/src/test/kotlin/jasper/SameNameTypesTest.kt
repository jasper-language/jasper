package jasper

import jasper.compiler.JasperCompiler
import java.net.URLClassLoader
import java.nio.file.Files
import java.nio.file.Paths
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs

/**
 * 两个包里同名的类型能共存（Q238，appfw FB-merge-001 / FB-theme-06，LANG-SAME-NAME-TYPES-01）。
 *
 * FB-merge-001：`pb.Runner.show(t: Thing)` 的 Thing 按调用方作用域解析，别包另有 `pa.Thing` 时合法实参被拒。
 * FB-theme-06：别包同名类型只能用限定名或导入别名区分，但限定名 `pb.Variant.Primary` 报 Unresolved reference，
 * 别名 `UiVariant.Primary` 触发编译器内部错误。期望值由用例写出。
 */
class SameNameTypesTest {
    private fun run(name: String, files: Map<String, String>, pkg: String): Any? {
        val dir = Paths.get("build", "scratch", "same-name-types", name)
        if (Files.exists(dir)) dir.toFile().deleteRecursively()
        Files.createDirectories(dir)
        val result = JasperCompiler().compileAllWithStdlib(files, dir)
        assertIs<JasperCompiler.CompileResult.Success>(result, "$name: " + (result as? JasperCompiler.CompileResult.Error)?.message.orEmpty())
        URLClassLoader(arrayOf(dir.toUri().toURL()), javaClass.classLoader).use { loader ->
            val owner = Files.list(dir.resolve(pkg)).use { it.toList() }
                .filter { it.fileName.toString().endsWith(".class") }
                .map { loader.loadClass("$pkg." + it.fileName.toString().removeSuffix(".class")) }
                .first { c -> c.methods.any { it.name == "entry" } }
            return owner.getMethod("entry").invoke(null)
        }
    }

    @Test
    fun `member signatures resolve simple names in the declaring file (FB-merge-001)`() {
        val files = mapOf(
            "a/Thing.jas" to "package pa;\npublic @interface Thing {\npublic string scope() default \"x\";\n}\n",
            "b/Thing.jas" to "package pb;\npublic abstract class Thing {\npublic abstract function name(): string;\n}\n",
            "b/Runner.jas" to "package pb;\npublic class Runner {\npublic constructor() { }\npublic function show(t: Thing): string { return t.name(); }\n}\n",
            "t/Impl.jas" to "package pt;\nimport pb.Thing;\npublic class Impl extends Thing {\npublic function name(): string { return \"impl\"; }\n}\n",
            "t/Main.jas" to "package pt;\nimport pb.Runner;\npublic function entry(): string {\nvar r = new Runner();\nreturn r.show(new Impl());\n}\n"
        )
        assertEquals("impl", run("merge-001", files, "pt"))
    }

    private val variants = mapOf(
        "a/Variant.jas" to "package pa;\npublic enum Variant { Primary, Ghost }\n",
        "b/Variant.jas" to "package pb;\npublic enum Variant { Primary, Destructive }\n"
    )

    @Test
    fun `a qualified name picks the other package's type (FB-theme-06 a)`() {
        val main = """
            package pt;
            import pa.Variant;
            public function entry(): string {
                var mine: Variant = Variant.Ghost;
                var theirs: pb.Variant = pb.Variant.Destructive;
                return "" + mine + "/" + theirs + "/" + (theirs == pb.Variant.Destructive);
            }
        """.trimIndent()
        assertEquals("Ghost/Destructive/true", run("qualified", variants + ("t/Main.jas" to main), "pt"))
    }

    @Test
    fun `an import alias names the other package's type (FB-theme-06 b)`() {
        val main = """
            package pt;
            import pa.Variant;
            from pb import Variant as UiVariant;
            public function entry(): string {
                var mine: Variant = Variant.Primary;
                var theirs: UiVariant = UiVariant.Destructive;
                return "" + mine + "/" + theirs + "/" + (theirs == UiVariant.Destructive);
            }
        """.trimIndent()
        assertEquals("Primary/Destructive/true", run("alias", variants + ("t/Main.jas" to main), "pt"))
    }
}
