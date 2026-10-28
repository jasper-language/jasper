package jasper

import jasper.compiler.JasperCompiler
import java.lang.reflect.InvocationTargetException
import java.lang.reflect.Method
import java.net.URLClassLoader
import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.Paths
import kotlin.random.Random
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * 同步函数路径的 defer 作用域语义（规范 10.8「在当前作用域结束时执行」），与协程帧
 * LANG-CONT-DEFER-01 一致：块的每条离开路径（正常结束、return、break / continue、异常）恰好执行
 * 一次、只执行已到达的 defer、同块后声明的先执行、return 值先于 defer 固定；循环体中的 defer 按趟执行。
 *
 * 全部经真实 ANTLR 解析 → 检查器 → IR → JVM 字节码并执行；随机部分用固定种子生成程序，与按规范语义
 * 直接解释（每块一个 defer 作用域，离开时后进先出，**不经过** try/finally 改写）的 Kotlin oracle 比对。
 */
class SyncDeferScopeTest {

    private fun scratchDir(name: String): Path {
        val base = Paths.get("build", "scratch", "sync-defer", name)
        if (Files.exists(base)) base.toFile().deleteRecursively()
        Files.createDirectories(base)
        return base
    }

    /** 编译源码并按函数名收集生成的静态方法。 */
    private fun compileFunctions(source: String, name: String): Map<String, Method> {
        val out = scratchDir(name)
        val result = JasperCompiler().compileToDirWithIr(source, out)
        assertTrue(
            result is JasperCompiler.CompileResult.Success,
            "compilation failed for $name: " + (result as? JasperCompiler.CompileResult.Error)?.message.orEmpty()
        )
        val loader = URLClassLoader(arrayOf(out.toUri().toURL()))
        val methods = mutableMapOf<String, Method>()
        Files.walk(out).use { paths ->
            paths.filter { it.toString().endsWith(".class") }.forEach { file ->
                val className = out.relativize(file).toString().removeSuffix(".class").replace('/', '.')
                val clazz = loader.loadClass(className)
                for (m in clazz.declaredMethods) {
                    if (java.lang.reflect.Modifier.isStatic(m.modifiers)) {
                        m.isAccessible = true
                        methods.putIfAbsent(m.name, m)
                    }
                }
            }
        }
        return methods
    }

    /** 调用结果：返回值或抛出的异常类名，以及 StringBuilder 轨迹。 */
    private data class Outcome(val result: String, val trace: String)

    private fun call(method: Method, vararg args: Any): Outcome {
        val sb = java.lang.StringBuilder()
        val result = try {
            method.invoke(null, sb, *args).toString()
        } catch (e: InvocationTargetException) {
            "throw:" + e.targetException.javaClass.simpleName
        }
        return Outcome(result, sb.toString())
    }

    private val handWritten = """
        package test;
        function branchDefer(sb: java.lang.StringBuilder, x: int32): int32 {
            if (x > 0) {
                defer { sb.append("T"); }
                sb.append("t");
            } else {
                sb.append("e");
            }
            sb.append("|");
            return 0;
        }
        function loopDefer(sb: java.lang.StringBuilder, n: int32): int32 {
            var i: int32 = 0;
            while (i < n) {
                i = i + 1;
                defer { sb.append("d"); sb.append(i); }
                if (i == 2) { continue; }
                if (i == 4) { break; }
                sb.append("b");
            }
            sb.append("|");
            return i;
        }
        function lifo(sb: java.lang.StringBuilder, x: int32): string {
            defer { sb.append("1"); }
            defer sb.append("2");
            sb.append("a");
            return sb.toString();
        }
        function leaveByThrow(sb: java.lang.StringBuilder, d: int32): int32 {
            defer { sb.append("o"); }
            if (true) {
                defer { sb.append("i"); }
                sb.append(7 / d);
            }
            return 1;
        }
        function cleanupThrows(sb: java.lang.StringBuilder, d: int32): int32 {
            defer { sb.append("O"); }
            defer { sb.append("I"); sb.append(1 / d); }
            return 5;
        }
        function labeled(sb: java.lang.StringBuilder, x: int32): int32 {
            var i: int32 = 0;
            label outer: while (i < 3) {
                i = i + 1;
                defer { sb.append("o"); }
                var j: int32 = 0;
                while (j < 3) {
                    j = j + 1;
                    defer { sb.append("i"); }
                    if (i == 2) { break outer; }
                    if (j == 2) { continue outer; }
                }
            }
            return i;
        }
        function tryBreak(sb: java.lang.StringBuilder, x: int32): int32 {
            var i: int32 = 0;
            while (i < 5) {
                i = i + 1;
                try {
                    if (i == 3) { break; }
                    sb.append("b");
                } finally {
                    sb.append("f");
                }
            }
            return i;
        }
        function finallyThrowsOnReturn(sb: java.lang.StringBuilder, d: int32): int32 {
            try {
                try {
                    return 3;
                } finally {
                    sb.append("F");
                    sb.append(1 / d);
                }
            } catch (e: java.lang.ArithmeticException) {
                sb.append("C");
            }
            return 4;
        }
    """.trimIndent()

    @Test
    fun `defer in an untaken branch never runs and a block end runs it`() {
        val fns = compileFunctions(handWritten, "hand-branch")
        assertEquals(Outcome("0", "tT|"), call(fns.getValue("branchDefer"), 1))
        assertEquals(Outcome("0", "e|"), call(fns.getValue("branchDefer"), 0))
    }

    @Test
    fun `loop body defer runs once per iteration including continue and break`() {
        val fns = compileFunctions(handWritten, "hand-loop")
        assertEquals(Outcome("4", "bd1d2bd3d4|"), call(fns.getValue("loopDefer"), 5))
        assertEquals(Outcome("0", "|"), call(fns.getValue("loopDefer"), 0))
    }

    @Test
    fun `later defer runs first and the return value is fixed before cleanup`() {
        val fns = compileFunctions(handWritten, "hand-lifo")
        assertEquals(Outcome("a", "a21"), call(fns.getValue("lifo"), 0))
    }

    @Test
    fun `exceptions leave through every reached defer exactly once`() {
        val fns = compileFunctions(handWritten, "hand-throw")
        assertEquals(Outcome("1", "7io"), call(fns.getValue("leaveByThrow"), 1))
        assertEquals(Outcome("throw:ArithmeticException", "io"), call(fns.getValue("leaveByThrow"), 0))
        // defer 体在 return 路径上抛出：它本身只执行一次，外层 defer 照常执行，异常向外传播。
        assertEquals(Outcome("5", "I1O"), call(fns.getValue("cleanupThrows"), 1))
        assertEquals(Outcome("throw:ArithmeticException", "IO"), call(fns.getValue("cleanupThrows"), 0))
    }

    @Test
    fun `labeled jumps and try-finally inside loops run the cleanups they leave`() {
        val fns = compileFunctions(handWritten, "hand-jumps")
        assertEquals(Outcome("2", "iioio"), call(fns.getValue("labeled"), 0))
        assertEquals(Outcome("3", "bfbff"), call(fns.getValue("tryBreak"), 0))
        // return 路径内联的 finally 抛出时由外层 catch 处理，finally 不重进自身。
        assertEquals(Outcome("4", "FC"), call(fns.getValue("finallyThrowsOnReturn"), 0))
        assertEquals(Outcome("3", "F1"), call(fns.getValue("finallyThrowsOnReturn"), 1))
    }

    // ---------------------------------------------------------------------
    // 随机程序：固定种子生成，Kotlin oracle 按规范语义直接解释。
    // ---------------------------------------------------------------------

    private sealed class Cond {
        data class Gt(val variable: String, val bound: Int) : Cond()
    }

    private sealed class Stmt {
        data class Append(val c: Char) : Stmt()
        data class Defer(val body: List<Stmt>) : Stmt()
        data class If(val cond: Cond, val then: List<Stmt>, val otherwise: List<Stmt>) : Stmt()
        data class While(val id: Int, val bound: Int, val body: List<Stmt>) : Stmt()
        data class Break(val loop: Int, val labeled: Boolean) : Stmt()
        data class Continue(val loop: Int, val labeled: Boolean) : Stmt()
        data class Return(val value: Int) : Stmt()
        /** `sb.append(7 / dz)`，dz 恒为 0：抛出 ArithmeticException。 */
        object Divide : Stmt()
    }

    private sealed class Signal {
        object Normal : Signal()
        data class Break(val loop: Int) : Signal()
        data class Continue(val loop: Int) : Signal()
        data class Return(val value: Int) : Signal()
        object Throw : Signal()
    }

    private class Stats {
        var multiDeferBlocks = 0
        var loopDefers = 0
        var throwing = 0
        var crossJumps = 0
        var deferThrows = 0
    }

    private class Generator(private val random: Random, private val stats: Stats) {
        private var nextLoop = 0
        private var letter = 0

        private fun nextChar(): Char = "abcdefghijklmnopqrstuvwxyz"[letter++ % 26]

        fun program(): List<Stmt> = block(depth = 0, loops = emptyList(), inDefer = false, inLoopBody = false)

        private fun cond(loops: List<Int>): Cond =
            if (loops.isNotEmpty() && random.nextBoolean()) Cond.Gt("i${loops.random(random)}", random.nextInt(0, 3))
            else Cond.Gt("x", random.nextInt(0, 4))

        private fun block(depth: Int, loops: List<Int>, inDefer: Boolean, inLoopBody: Boolean): List<Stmt> {
            val length = random.nextInt(1, if (depth >= 2) 3 else 5)
            val stmts = mutableListOf<Stmt>()
            var defers = 0
            repeat(length) {
                val s = statement(depth, loops, inDefer)
                if (s is Stmt.Defer) {
                    defers++
                    if (inLoopBody) stats.loopDefers++
                }
                stmts.add(s)
            }
            if (defers >= 2) stats.multiDeferBlocks++
            return stmts
        }

        private fun statement(depth: Int, loops: List<Int>, inDefer: Boolean): Stmt {
            val roll = random.nextInt(100)
            // 循环体内提高循环占比，使嵌套循环与跨层跳转足够多。
            val loopBias = if (loops.isNotEmpty()) 12 else 0
            return when {
                roll < 25 || depth >= 4 -> Stmt.Append(nextChar())
                roll < 45 && !inDefer -> Stmt.Defer(block(depth + 1, loops, inDefer = true, inLoopBody = false))
                roll < 62 -> {
                    val guarded = guardedJump(loops, inDefer)
                    if (guarded != null) Stmt.If(cond(loops), listOf(guarded), emptyList())
                    else Stmt.Append(nextChar())
                }
                roll < 80 - loopBias -> Stmt.If(
                    cond(loops),
                    block(depth + 1, loops, inDefer, inLoopBody = false),
                    if (random.nextBoolean()) block(depth + 1, loops, inDefer, inLoopBody = false) else emptyList()
                )
                !inDefer -> {
                    val id = nextLoop++
                    Stmt.While(id, random.nextInt(0, 4), block(depth + 1, loops + id, inDefer, inLoopBody = true))
                }
                else -> Stmt.Append(nextChar())
            }
        }

        /** 只在条件分支中出现的离开语句；defer 体内只允许抛出（跳出 defer 体不在本测试范围）。 */
        private fun guardedJump(loops: List<Int>, inDefer: Boolean): Stmt? {
            if (inDefer) {
                stats.deferThrows++
                return Stmt.Divide
            }
            return when (random.nextInt(6)) {
                0 -> { stats.throwing++; Stmt.Divide }
                1 -> Stmt.Return(random.nextInt(1, 9))
                else -> if (loops.isEmpty()) Stmt.Return(random.nextInt(1, 9)) else {
                    // 有外层循环时多半跳到外层，覆盖穿过多层 defer 的跳转。
                    val target = if (loops.size > 1 && random.nextInt(10) < 7) loops[random.nextInt(loops.size - 1)]
                        else loops.last()
                    val labeled = target != loops.last() || random.nextBoolean()
                    if (target != loops.last()) stats.crossJumps++
                    if (random.nextBoolean()) Stmt.Break(target, labeled) else Stmt.Continue(target, labeled)
                }
            }
        }
    }

    private fun render(name: String, program: List<Stmt>): String {
        val out = StringBuilder()
        out.append("function $name(sb: java.lang.StringBuilder, x: int32): int32 {\n")
        out.append("    var dz: int32 = 0;\n")
        renderBlock(program, out, 1)
        out.append("    return 99;\n}\n")
        return out.toString()
    }

    private fun renderBlock(stmts: List<Stmt>, out: StringBuilder, indent: Int) {
        val pad = "    ".repeat(indent)
        for (s in stmts) {
            when (s) {
                is Stmt.Append -> out.append("$pad sb.append(\"${s.c}\");\n")
                is Stmt.Defer -> {
                    out.append("$pad defer {\n")
                    renderBlock(s.body, out, indent + 1)
                    out.append("$pad }\n")
                }
                is Stmt.If -> {
                    val c = s.cond as Cond.Gt
                    out.append("$pad if (${c.variable} > ${c.bound}) {\n")
                    renderBlock(s.then, out, indent + 1)
                    if (s.otherwise.isNotEmpty()) {
                        out.append("$pad } else {\n")
                        renderBlock(s.otherwise, out, indent + 1)
                    }
                    out.append("$pad }\n")
                }
                is Stmt.While -> {
                    out.append("$pad var i${s.id}: int32 = 0;\n")
                    out.append("$pad label l${s.id}: while (i${s.id} < ${s.bound}) {\n")
                    out.append("$pad     i${s.id} = i${s.id} + 1;\n")
                    renderBlock(s.body, out, indent + 1)
                    out.append("$pad }\n")
                }
                is Stmt.Break -> out.append(if (s.labeled) "$pad break l${s.loop};\n" else "$pad break;\n")
                is Stmt.Continue -> out.append(if (s.labeled) "$pad continue l${s.loop};\n" else "$pad continue;\n")
                is Stmt.Return -> out.append("$pad return ${s.value};\n")
                Stmt.Divide -> out.append("$pad sb.append(7 / dz);\n")
            }
        }
    }

    /** 规范语义解释器：每个块一个 defer 作用域，离开（任何信号）时后进先出执行已到达的 defer。 */
    private class Oracle(private val x: Int) {
        val trace = StringBuilder()
        private val counters = mutableMapOf<String, Int>()

        private fun eval(c: Cond): Boolean = when (c) {
            is Cond.Gt -> (if (c.variable == "x") x else counters.getValue(c.variable)) > c.bound
        }

        fun runBlock(stmts: List<Stmt>): Signal {
            val defers = mutableListOf<List<Stmt>>()
            var signal: Signal = Signal.Normal
            for (s in stmts) {
                if (s is Stmt.Defer) { defers.add(s.body); continue }
                signal = run(s)
                if (signal != Signal.Normal) break
            }
            for (body in defers.asReversed()) {
                val cleanup = runBlock(body)
                // 清理自身抛出时取代正在传播的信号（与 finally 相同），外层 defer 照常执行。
                if (cleanup != Signal.Normal) signal = cleanup
            }
            return signal
        }

        private fun run(s: Stmt): Signal = when (s) {
            is Stmt.Append -> { trace.append(s.c); Signal.Normal }
            is Stmt.Defer -> error("defer handled by runBlock")
            is Stmt.If -> runBlock(if (eval(s.cond)) s.then else s.otherwise)
            is Stmt.While -> {
                val name = "i${s.id}"
                counters[name] = 0
                var result: Signal = Signal.Normal
                while (counters.getValue(name) < s.bound) {
                    counters[name] = counters.getValue(name) + 1
                    val sig = runBlock(s.body)
                    if (sig == Signal.Break(s.id)) break
                    if (sig == Signal.Continue(s.id) || sig == Signal.Normal) continue
                    result = sig
                    break
                }
                result
            }
            is Stmt.Break -> Signal.Break(s.loop)
            is Stmt.Continue -> Signal.Continue(s.loop)
            is Stmt.Return -> Signal.Return(s.value)
            Stmt.Divide -> Signal.Throw
        }
    }

    private fun oracle(program: List<Stmt>, x: Int): Outcome {
        val o = Oracle(x)
        val result = when (val sig = o.runBlock(program)) {
            Signal.Normal -> "99"
            is Signal.Return -> sig.value.toString()
            Signal.Throw -> "throw:ArithmeticException"
            else -> error("jump escaped the function: $sig")
        }
        return Outcome(result, o.trace.toString())
    }

    @Test
    fun `random programs with seed 20261115 match the scope-semantics oracle`() {
        val random = Random(20261115)
        val stats = Stats()
        val programs = List(400) { Generator(random, stats).program() }
        var compared = 0
        for (batch in programs.indices.chunked(100)) {
            val source = StringBuilder("package test;\n")
            for (i in batch) source.append(render("p$i", programs[i]))
            val fns = compileFunctions(source.toString(), "random-${batch.first()}")
            for (i in batch) {
                for (x in 0..4) {
                    assertEquals(
                        oracle(programs[i], x), call(fns.getValue("p$i"), x),
                        "program p$i with x=$x:\n" + render("p$i", programs[i])
                    )
                    compared++
                }
            }
        }
        assertEquals(2000, compared)
        // 数量下限：保证随机程序确实覆盖了各类离开路径。
        assertTrue(stats.multiDeferBlocks > 40, "multi-defer blocks: ${stats.multiDeferBlocks}")
        assertTrue(stats.loopDefers > 40, "loop-body defers: ${stats.loopDefers}")
        assertTrue(stats.throwing > 40, "throwing statements: ${stats.throwing}")
        assertTrue(stats.crossJumps > 40, "cross-loop jumps: ${stats.crossJumps}")
        assertTrue(stats.deferThrows > 40, "throwing defer bodies: ${stats.deferThrows}")
    }
}
