package jasper

import jasper.annotations.AnnotationProcessor
import jasper.compiler.JasperCompiler
import java.net.URLClassLoader
import java.nio.file.Files
import java.nio.file.Path
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

class AnnotationProcessingTest {

    private fun compile(source: String): Pair<JasperCompiler.CompileResult, Path> {
        val outputDir = Files.createTempDirectory("anntest")
        val result = JasperCompiler().compileToDirWithIr(source.trimIndent(), outputDir)
        return result to outputDir
    }

    private fun <T> withCompiled(source: String, className: String, block: (Class<*>) -> T): T {
        val (result, outputDir) = compile(source)
        assertTrue(
            result is JasperCompiler.CompileResult.Success,
            "Compilation failed: ${(result as? JasperCompiler.CompileResult.Error)?.message ?: result}"
        )
        return try {
            val loader = URLClassLoader(arrayOf(outputDir.toUri().toURL()))
            block(loader.loadClass(className))
        } finally {
            outputDir.toFile().deleteRecursively()
        }
    }

    @Test
    fun `class level getter compiles and reads field`() {
        withCompiled(
            """
            package test;
            @Getter
            class Foo {
                var name: string;
                constructor(name: string) { this.name = name; }
            }
            function main(): string {
                var f = new Foo("hello");
                return f.getName();
            }
            """,
            "test.main"
        ) { clazz ->
            val ret = clazz.getMethod("main").invoke(null)
            assertEquals("hello", ret)
        }
    }

    @Test
    fun `class level setter writes field readable by getter`() {
        withCompiled(
            """
            package test;
            @Getter
            @Setter
            class Foo {
                var name: string;
                constructor() { this.name = ""; }
            }
            function main(): string {
                var f = new Foo();
                f.setName("world");
                return f.getName();
            }
            """,
            "test.main"
        ) { clazz ->
            val ret = clazz.getMethod("main").invoke(null)
            assertEquals("world", ret)
        }
    }

    @Test
    fun `property level getter only affects that property`() {
        withCompiled(
            """
            package test;
            class Foo {
                @Getter var name: string;
                var age: int32;
                constructor(name: string, age: int32) { this.name = name; this.age = age; }
            }
            function main(): string {
                var f = new Foo("hi", 3);
                return f.getName();
            }
            """,
            "test.Foo"
        ) { clazz ->
            assertNotNull(clazz.getMethod("getName"))
            assertFailsWith<NoSuchMethodException> { clazz.getMethod("getAge") }
        }
    }

    @Test
    fun `handwritten method with same name is not duplicated`() {
        withCompiled(
            """
            package test;
            @Getter
            class Foo {
                var name: string;
                constructor(name: string) { this.name = name; }
                function getName(): string { return "handwritten"; }
            }
            function main(): string {
                var f = new Foo("ignored");
                return f.getName();
            }
            """,
            "test.main"
        ) { clazz ->
            val ret = clazz.getMethod("main").invoke(null)
            assertEquals("handwritten", ret)
        }
    }

    @Test
    fun `class without annotations is unchanged`() {
        withCompiled(
            """
            package test;
            class Foo {
                var x: int32;
                constructor(x: int32) { this.x = x; }
                function getX(): int32 { return this.x; }
            }
            function main(): int32 {
                var f = new Foo(7);
                return f.getX();
            }
            """,
            "test.Foo"
        ) { clazz ->
            assertNotNull(clazz.getMethod("getX"))
            val names = clazz.declaredMethods.map { it.name }.toSet()
            assertTrue("getName" !in names && "isX" !in names,
                "Unannotated class must not gain synthesized accessors, got: $names")
        }
    }

    @Test
    fun `annotation index is produced and structured`() {
        val pipeline = JasperCompiler().compileToPipeline(
            """
            package test;
            @Getter
            @Setter
            class Foo {
                @Deprecated var name: string;
            }
            """.trimIndent()
        )
        val records = pipeline.annotationIndex.forClass("Foo")
        assertTrue(records.any { it.annotationName == "Getter" && it.targetKind == "class" },
            "Expected class-level @Getter record, got: $records")
        assertTrue(records.any { it.annotationName == "Setter" && it.targetKind == "class" },
            "Expected class-level @Setter record, got: $records")
        assertTrue(records.any { it.annotationName == "Deprecated" && it.targetKind == "property" && it.targetName == "name" },
            "Expected property-level @Deprecated record, got: $records")
    }

    @Test
    fun `processor leaves unannotated ast untouched`() {
        val pipeline = JasperCompiler().compileToPipeline(
            """
            package test;
            class Foo {
                var name: string;
            }
            """.trimIndent()
        )
        val cls = pipeline.ast.declarations.filterIsInstance<jasper.ast.JasClass>().single { it.name == "Foo" }
        assertTrue(cls.members.none { it is jasper.ast.JasFunction && it.syntheticAccessor != null })
        assertTrue(AnnotationProcessor().process(pipeline.ast).index.byClass.isEmpty())
    }
}
