// ============================================================================
// InheritedPlatformMemberTest.kt —— 源码类调用从平台超类继承的成员
// ============================================================================
// 职责：this / 同类接收者上的继承成员（含 protected）拿到真实签名；源码声明的同名成员优先；
//   过去这类调用按 Unit 拼描述符，运行期 NoSuchMethodError。
// ============================================================================

package jasper

import kotlin.test.Test
import kotlin.test.assertEquals

class InheritedPlatformMemberTest {
    private fun run(source: String, method: String = "main"): Any? {
        val loader = CompiledRuntimeLoader.compile("inherited-platform", mapOf("Foo.jas" to source))
        return loader.loadClass("t.Foo").getMethod(method).invoke(null)
    }

    @Test
    fun `public members of the platform superclass`() {
        val source = """
            package t;
            public class Foo extends java.lang.Thread {
                public function describe(): string {
                    this.setName("worker");
                    var n: string = this.getName();
                    return n + ":" + this.getClass().getName();
                }
                public static function main(): string {
                    return new Foo().describe();
                }
            }
        """.trimIndent()
        assertEquals("worker:t.Foo", run(source))
    }

    @Test
    fun `members of Object through a class without a platform superclass`() {
        val source = """
            package t;
            public class Foo {
                public static function main(): string {
                    return new Foo().name();
                }
                public function name(): string {
                    return this.getClass().getSimpleName();
                }
            }
        """.trimIndent()
        assertEquals("Foo", run(source))
    }

    @Test
    fun `protected members reach subclasses and the value keeps its type`() {
        val source = """
            package t;
            public class Foo extends java.lang.ClassLoader {
                public constructor(parent: java.lang.ClassLoader) {
                    super(parent);
                }
                public function seen(name: string): bool {
                    var found: java.lang.Class? = this.findLoadedClass(name);
                    return found != null;
                }
                public static function main(): bool {
                    var plain: java.lang.Object = new java.lang.Object();
                    return new Foo(plain.getClass().getClassLoader()).seen("t.Nothing");
                }
            }
        """.trimIndent()
        assertEquals(false, run(source))
    }

    @Test
    fun `a source member with the same name wins over the inherited one`() {
        val source = """
            package t;
            public class Base extends java.lang.Thread {
                public function hello(): string { return "base"; }
                public function join(x: any): string { return "joined"; }
            }
            public class Foo extends Base {
                public function getName(a: int32): string { return "own" + a; }
                public function isInterrupted(): bool { return true; }
                public static function main(): string {
                    var t: Foo = new Foo();
                    return t.getName(1) + ":" + t.isInterrupted() + ":" + t.hello() + ":" + t.join(5);
                }
            }
        """.trimIndent()
        assertEquals("own1:true:base:joined", run(source))
    }
}
