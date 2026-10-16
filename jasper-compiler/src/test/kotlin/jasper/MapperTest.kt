package jasper

import jasper.compiler.JasperCompiler
import java.net.URLClassLoader
import java.nio.file.Files
import java.nio.file.Path
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/**
 * Q6c: `@Mapper` / `@Mapping` — MapStruct-family minimal form.
 *
 * `@Mapper` on an interface synthesizes an implementation class during the
 * annotation processing phase (signatures + plan) whose bodies are generated
 * during lowering. v1 supports simple property-to-property mapping only:
 * same-name/same-type fields auto-map, `@Mapping(source, target)` renames, and
 * an **unmapped target property is a compile-time error**.
 *
 * Behavioral tests go through the real compile → JVM class loading → reflective
 * execution path.
 */
class MapperTest {

    private fun compile(source: String): Pair<JasperCompiler.CompileResult, Path> {
        val outputDir = Files.createTempDirectory("mappertest")
        val result = JasperCompiler().compileToDirWithIr(source.trimIndent(), outputDir)
        return result to outputDir
    }

    private fun <T> withCompiled(source: String, className: String, block: (ClassLoader, Class<*>) -> T): T {
        val (result, outputDir) = compile(source)
        assertTrue(
            result is JasperCompiler.CompileResult.Success,
            "Compilation failed: ${(result as? JasperCompiler.CompileResult.Error)?.message ?: result}"
        )
        return try {
            val loader = URLClassLoader(arrayOf(outputDir.toUri().toURL()))
            block(loader, loader.loadClass(className))
        } finally {
            outputDir.toFile().deleteRecursively()
        }
    }

    @Test
    fun `same named properties auto map`() {
        withCompiled(
            """
            package test;
            class Person {
                var name: string;
                var age: int32;
                constructor(name: string, age: int32) { this.name = name; this.age = age; }
            }
            class PersonDto {
                var name: string;
                var age: int32;
            }
            @Mapper
            interface PersonMapper {
                function toDto(p: Person): PersonDto;
            }
            function main(): string {
                var mapper: PersonMapper = new PersonMapperImpl();
                var dto = mapper.toDto(new Person("alice", 30));
                return dto.name + ":" + toString(dto.age);
            }
            """,
            "test.main"
        ) { _, clazz ->
            assertEquals("alice:30", clazz.getMethod("main").invoke(null))
        }
    }

    @Test
    fun `explicit mapping renames property`() {
        withCompiled(
            """
            package test;
            class Person {
                var firstName: string;
                var age: int32;
                constructor(firstName: string, age: int32) { this.firstName = firstName; this.age = age; }
            }
            class PersonDto {
                var name: string;
                var age: int32;
            }
            @Mapper
            interface PersonMapper {
                @Mapping(source = "firstName", target = "name")
                function toDto(p: Person): PersonDto;
            }
            function main(): string {
                var mapper: PersonMapper = new PersonMapperImpl();
                var dto = mapper.toDto(new Person("bob", 7));
                return dto.name + ":" + toString(dto.age);
            }
            """,
            "test.main"
        ) { _, clazz ->
            assertEquals("bob:7", clazz.getMethod("main").invoke(null))
        }
    }

    @Test
    fun `unmapped target property is a compile error`() {
        val (result, outputDir) = compile(
            """
            package test;
            class Person {
                var name: string;
                constructor(name: string) { this.name = name; }
            }
            class PersonDto {
                var name: string;
                var nickname: string;
            }
            @Mapper
            interface PersonMapper {
                function toDto(p: Person): PersonDto;
            }
            function main(): string { return "unused"; }
            """
        )
        try {
            assertTrue(result is JasperCompiler.CompileResult.Error, "Expected a compile error, got: $result")
            val msg = (result as JasperCompiler.CompileResult.Error).message
            assertTrue(msg.contains("nickname"), "Error must name the unmapped property, got: $msg")
            assertTrue(msg.contains("ANNOTATION"), "Expected annotation-category diagnostic, got: $msg")
        } finally {
            outputDir.toFile().deleteRecursively()
        }
    }

    @Test
    fun `generated implementation satisfies the interface contract`() {
        withCompiled(
            """
            package test;
            class Person {
                var name: string;
                constructor(name: string) { this.name = name; }
            }
            class PersonDto {
                var name: string;
            }
            @Mapper
            interface PersonMapper {
                function toDto(p: Person): PersonDto;
            }
            function main(): string {
                var mapper: PersonMapper = new PersonMapperImpl();
                return mapper.toDto(new Person("carol")).name;
            }
            """,
            "test.main"
        ) { loader, clazz ->
            val iface = loader.loadClass("test.PersonMapper")
            val impl = loader.loadClass("test.PersonMapperImpl")
            assertTrue(iface.isAssignableFrom(impl), "Impl must implement the @Mapper interface")
            assertFalse(impl.isInterface, "Impl must be a concrete class")

            // Instantiate the generated class and call the mapper method directly.
            val personCls = loader.loadClass("test.Person")
            val person = personCls.getConstructor(String::class.java).newInstance("dave")
            val instance = impl.getDeclaredConstructor().newInstance()
            val dto = impl.getMethod("toDto", personCls).invoke(instance, person)
            assertEquals("dave", dto.javaClass.getField("name").get(dto))

            // And through the interface-typed entry point.
            assertEquals("carol", clazz.getMethod("main").invoke(null))
        }
    }

    @Test
    fun `explicit mapping to unknown target is a compile error`() {
        val (result, outputDir) = compile(
            """
            package test;
            class Person {
                var name: string;
                constructor(name: string) { this.name = name; }
            }
            class PersonDto {
                var name: string;
            }
            @Mapper
            interface PersonMapper {
                @Mapping(source = "name", target = "nope")
                function toDto(p: Person): PersonDto;
            }
            function main(): string { return "unused"; }
            """
        )
        try {
            assertTrue(result is JasperCompiler.CompileResult.Error, "Expected a compile error, got: $result")
            assertTrue((result as JasperCompiler.CompileResult.Error).message.contains("nope"),
                "Error must name the unknown target property, got: ${result.message}")
        } finally {
            outputDir.toFile().deleteRecursively()
        }
    }

    @Test
    fun `interface without Mapper annotation does not produce an implementation`() {
        val (result, outputDir) = compile(
            """
            package test;
            interface Greeter {
                function greet(): string;
            }
            function main(): string { return "ok"; }
            """
        )
        try {
            assertTrue(result is JasperCompiler.CompileResult.Success, "Compilation failed: $result")
            assertFalse(outputDir.resolve("test/GreeterImpl.class").toFile().exists(),
                "A non-@Mapper interface must not gain a synthesized implementation")
        } finally {
            outputDir.toFile().deleteRecursively()
        }
    }
}
