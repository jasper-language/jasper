package jasper

import jasper.translator.IrDumper
import jasper.compiler.JasperCompiler
import kotlin.test.Test
import kotlin.test.assertTrue

class IrDumperTest {

    @Test
    fun `test dump ir for simple function`() {
        val source = """
            package test;
            function main(): void {
                var x: int32 = 42;
            }
        """.trimIndent()

        val pipeline = JasperCompiler().compileToPipeline(source)
        val dump = IrDumper().dump(pipeline.ir.irFiles[0])

        println("=== IR Dump ===")
        println(dump)

        assertTrue(dump.contains("IrFile"), "Should contain IrFile")
        assertTrue(dump.contains("IrSimpleFunction"), "Should contain IrSimpleFunction")
        assertTrue(dump.contains("main"), "Should contain function name")
        assertTrue(dump.contains("IrVariable"), "Should contain IrVariable")
        assertTrue(dump.contains("x"), "Should contain variable name")
        assertTrue(dump.contains("IrConst(42)"), "Should contain constant 42")
    }

    @Test
    fun `test dump ir for if while for`() {
        val source = """
            package test;
            function test(): void {
                var i: int32 = 0;
                while (i < 10) {
                    if (i == 5) {
                        i = i + 1;
                    } else {
                        break;
                    }
                    i = i + 1;
                }
            }
        """.trimIndent()

        val pipeline = JasperCompiler().compileToPipeline(source)
        val dump = IrDumper().dump(pipeline.ir.irFiles[0])

        println("=== IR Dump (if/while/for) ===")
        println(dump)

        assertTrue(dump.contains("IrWhileLoop"))
        assertTrue(dump.contains("IrWhen"))
    }

    @Test
    fun `test dump ir for class`() {
        val source = """
            package test;
            class Box {
                var value: int32;
                constructor(v: int32) {
                    this.value = v;
                }
            }
        """.trimIndent()

        val pipeline = JasperCompiler().compileToPipeline(source)
        val dump = IrDumper().dump(pipeline.ir.irFiles[0])

        println("=== IR Dump (class) ===")
        println(dump)

        assertTrue(dump.contains("IrClass"))
        assertTrue(dump.contains("IrConstructor"))
        assertTrue(dump.contains("IrField"))
        assertTrue(dump.contains("Box"))
    }

    @Test
    fun `test dump ir for lambda`() {
        val source = """
            package test;
            function main(): void {
                var f = (x: int32) => x + 1;
            }
        """.trimIndent()

        val pipeline = JasperCompiler().compileToPipeline(source)
        val dump = IrDumper().dump(pipeline.ir.irFiles[0])

        println("=== IR Dump (lambda) ===")
        println(dump)

        // Lambdas are translated as anonymous IrClass with an invoke method
        assertTrue(dump.contains("IrConstructorCall"), "Should contain IrConstructorCall for lambda init")
    }
}
