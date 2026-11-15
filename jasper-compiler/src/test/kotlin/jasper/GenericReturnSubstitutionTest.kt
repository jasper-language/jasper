package jasper

import jasper.compiler.JasperCompiler
import java.net.URLClassLoader
import java.nio.file.Files
import java.nio.file.Paths
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertTrue

/**
 * 泛型方法的返回类型按调用点推断出的类型实参代换（Q236，appfw FB-app-001 / FB-data-02 / FB-data-03 / FB-web-019 /
 * FB-build-001 / FB-build-012 / FB-UI-003，LANG-GENERIC-RETURN-01）。
 *
 * 修复前：顶层泛型函数能推断，类的静态 / 实例泛型方法、经子接口继承的泛型方法不代换，返回类型停在裸类型形参 R：
 * 写显式类型报 `cannot assign R to ...`，不写类型则 R 能赋给任何类型、运行期才 VerifyError / ClassCastException。
 */
class GenericReturnSubstitutionTest {
    private val decls = """
        import std.Future;
        class Box {
            public var n: int32 = 3;
            public constructor() { }
        }
        class Util {
            public static function first<T>(f: Future<T>): T { return f.get(); }
            public static function wrap<T>(v: T): Future<T> {
                var f: Future<T> = new Future<T>();
                f.complete(v);
                return f;
            }
        }
        interface Cmd<R> { }
        class Receipt {
            public var n: int32;
            public constructor(n: int32) { this.n = n; }
        }
        class Place implements Cmd<Receipt> {
            public constructor() { }
        }
        class Bus {
            public function send<R>(cmd: Cmd<R>, value: any): Future<R> {
                var f: Future<R> = new Future<R>();
                f.complete(cast value as R);
                return f;
            }
        }
        interface Repo<E, ID> {
            public function find(id: ID): Future<E?>;
        }
        class User {
            public var name: string = "u";
            public constructor() { }
        }
        interface UserRepo extends Repo<User, int64> { }
        class UserRepoImpl implements UserRepo {
            public constructor() { }
            public function find(id: int64): Future<User?> {
                var f: Future<User?> = new Future<User?>();
                f.complete(new User());
                return f;
            }
        }
    """.trimIndent()

    private fun compile(name: String, body: String, ret: String): Pair<JasperCompiler.CompileResult, java.nio.file.Path> {
        val dir = Paths.get("build", "scratch", "generic-return", name)
        if (Files.exists(dir)) dir.toFile().deleteRecursively()
        Files.createDirectories(dir)
        val source = "package t;\n$decls\npublic function entry(): $ret {\n$body\n}\n"
        return JasperCompiler().compileAllWithStdlib(mapOf("t.jas" to source), dir) to dir
    }

    private fun run(name: String, ret: String, body: String): Any? {
        val (result, dir) = compile(name, body, ret)
        assertIs<JasperCompiler.CompileResult.Success>(result, "$name: " + (result as? JasperCompiler.CompileResult.Error)?.message.orEmpty())
        URLClassLoader(arrayOf(dir.toUri().toURL()), javaClass.classLoader).use { loader ->
            val owner = Files.list(dir.resolve("t")).use { it.toList() }
                .filter { it.fileName.toString().endsWith(".class") }
                .map { loader.loadClass("t." + it.fileName.toString().removeSuffix(".class")) }
                .first { c -> c.methods.any { it.name == "entry" } }
            return owner.getMethod("entry").invoke(null)
        }
    }

    private fun rejected(name: String, body: String, expect: String) {
        val (result, _) = compile(name, body, "int32")
        val error = assertIs<JasperCompiler.CompileResult.Error>(result, "$name should not compile")
        assertTrue(error.errors.any { expect in it.message }, "$name: expected '$expect' in\n${error.message}")
    }

    @Test
    fun `class static generic methods substitute their return type (FB-data-02)`() {
        assertEquals(3, run("static-typed", "int32", "var f: Future<Box> = new Future<Box>();\nf.complete(new Box());\nvar b: Box = Util.first(f);\nreturn b.n;"))
        assertEquals(3, run("static-untyped", "int32", "var f = Util.wrap(new Box());\nvar b = Util.first(f);\nreturn b.n;"))
        assertEquals(42, run("static-int", "int32", "var f: Future<int32> = Util.wrap(42);\nvar v: int32 = Util.first(f);\nreturn v;"))
    }

    @Test
    fun `instance generic methods infer through implemented interfaces (FB-app-001)`() {
        assertEquals(7, run("instance-typed", "int32", "var bus = new Bus();\nvar c: Cmd<Receipt> = new Place();\nvar a: Receipt = bus.send(c, new Receipt(7)).get();\nreturn a.n;"))
        assertEquals(8, run("instance-untyped", "int32", "var bus = new Bus();\nvar x = bus.send(new Place(), new Receipt(8)).get();\nreturn x.n;"))
    }

    @Test
    fun `methods inherited through a sub interface are substituted (FB-data-03)`() {
        assertEquals("u", run("sub-interface", "string", "var r: UserRepo = new UserRepoImpl();\nvar id: int64 = 5L;\nvar f: Future<User?> = r.find(id);\nreturn f.get()?.name ?? \"none\";"))
        assertEquals("u", run("sub-interface-untyped", "string", "var r: UserRepo = new UserRepoImpl();\nvar u = r.find(5L).get();\nreturn u?.name ?? \"none\";"))
    }

    @Test
    fun `a substituted result is no longer assignable to an unrelated type`() {
        rejected("unsound-future", "var bus = new Bus();\nvar c: Cmd<Receipt> = new Place();\nvar wrong: Future<string> = bus.send(c, new Receipt(1));\nreturn 0;", "Type mismatch")
        rejected("unsound-value", "var f: Future<Box> = Util.wrap(new Box());\nvar s: string = Util.first(f);\nreturn 0;", "Type mismatch")
    }
}
