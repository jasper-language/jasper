package jasper

import jasper.compiler.JasperCompiler
import java.io.PrintWriter
import java.io.StringWriter
import java.net.URLClassLoader
import java.nio.file.Files
import java.nio.file.Paths
import kotlin.test.Test

class S4CompileProbeTest {
    private fun compileSource(name: String, source: String): String {
        val out = Paths.get("build", "scratch", "s4-compile-probe", name)
        if (Files.exists(out)) out.toFile().deleteRecursively()
        Files.createDirectories(out)
        return try {
            val r = JasperCompiler().compileToDirWithIr(source, out)
            when (r) {
                is JasperCompiler.CompileResult.Success -> "SUCCESS"
                is JasperCompiler.CompileResult.Error -> "ERROR:\n" + r.errors.joinToString("\n") + "\n" + r.message
            }
        } catch (t: Throwable) {
            val sw = StringWriter()
            t.printStackTrace(PrintWriter(sw))
            "THROWN:\n" + sw.toString()
        }
    }

    @Test
    fun probe() {
        val out = Paths.get("build", "scratch", "s4-compile-probe")
        if (Files.exists(out)) out.toFile().deleteRecursively()
        Files.createDirectories(out)
        val stdlib = try {
            val r = JasperCompiler().compileAllWithStdlib(
                mapOf("Main.jas" to "package std;\npublic function main(): int32 { return 0; }\n"),
                out
            )
            when (r) {
                is JasperCompiler.CompileResult.Success -> "SUCCESS"
                is JasperCompiler.CompileResult.Error -> buildString {
                    appendLine("ERROR:")
                    for (e in r.errors) {
                        appendLine(e.toString())
                        if (e is jasper.compiler.CompileError.Internal) {
                            val sw = StringWriter()
                            e.cause?.printStackTrace(PrintWriter(sw))
                            appendLine(sw.toString())
                        }
                    }
                    appendLine(r.message)
                }
            }
        } catch (t: Throwable) {
            val sw = StringWriter()
            t.printStackTrace(PrintWriter(sw))
            "THROWN:\n" + sw.toString()
        }
        val snippets = linkedMapOf(
            "math-min-vars" to """
                package test;
                public function pick(a: int64, b: int64): int64 { return java.lang.Math.min(a, b); }
            """.trimIndent(),
            "math-min-literal" to """
                package test;
                public function pick(a: int64): int64 { return java.lang.Math.min(a, 0L); }
            """.trimIndent(),
            "long-compare" to """
                package test;
                public function pick(a: int64): int32 { return java.lang.Long.compare(a, 0L); }
            """.trimIndent(),
            "int64-less" to """
                package test;
                public function pick(a: int64, b: int64): int64 { if (a < b) { return a; } return b; }
            """.trimIndent(),
            "math-min-int32" to """
                package test;
                public function pick(a: int32, b: int32): int32 { return java.lang.Math.min(a, b); }
            """.trimIndent(),
            "generic-try-catch-finally" to """
                package test;
                public interface Body<T> { public function call(): T; }
                public class Runner<T> {
                    private var body: Body<T>;
                    public constructor(body: Body<T>) { this.body = body; }
                    public function go(): void {
                        try {
                            var value: T = this.body.call();
                            if (value == null) { return; }
                        } catch (known: java.lang.IllegalStateException) {
                            var a: int32 = 1;
                        } catch (other: java.lang.Throwable) {
                            var b: int32 = 2;
                        } finally {
                            var c: int32 = 3;
                        }
                    }
                }
                public function main(): int32 { return 0; }
            """.trimIndent(),
            "generic-local-only" to """
                package test;
                public interface Body<T> { public function call(): T; }
                public class Runner<T> {
                    private var body: Body<T>;
                    public constructor(body: Body<T>) { this.body = body; }
                    public function go(): void {
                        var value: T = this.body.call();
                        if (value == null) { return; }
                    }
                }
                public function main(): int32 { return 0; }
            """.trimIndent(),
            "try-catch-finally-int" to """
                package test;
                public class Runner {
                    public function go(): void {
                        try {
                            var value: int32 = this.call();
                            if (value == 0) { return; }
                        } catch (known: java.lang.IllegalStateException) {
                            var a: int32 = 1;
                        } catch (other: java.lang.Throwable) {
                            var b: int32 = 2;
                        } finally {
                            var c: int32 = 3;
                        }
                    }
                    private function call(): int32 { return 1; }
                }
                public function main(): int32 { return 0; }
            """.trimIndent(),
            "generic-catch-no-finally" to """
                package test;
                public interface Body<T> { public function call(): T; }
                public class Runner<T> {
                    private var body: Body<T>;
                    public constructor(body: Body<T>) { this.body = body; }
                    public function go(): void {
                        try {
                            var value: T = this.body.call();
                            if (value == null) { return; }
                        } catch (known: java.lang.IllegalStateException) {
                            var a: int32 = 1;
                        } catch (other: java.lang.Throwable) {
                            var b: int32 = 2;
                        }
                    }
                }
                public function main(): int32 { return 0; }
            """.trimIndent(),
            "try-multi-catch-finally-no-return" to """
                package test;
                public class Runner {
                    public function go(): void {
                        try {
                            var value: int32 = this.call();
                            var doubled: int32 = value + value;
                        } catch (known: java.lang.IllegalStateException) {
                            var a: int32 = 1;
                        } catch (other: java.lang.Throwable) {
                            var b: int32 = 2;
                        } finally {
                            var c: int32 = 3;
                        }
                    }
                    private function call(): int32 { return 1; }
                }
                public function main(): int32 { return 0; }
            """.trimIndent(),
            "try-one-catch-finally" to """
                package test;
                public class Runner {
                    public function go(): void {
                        try {
                            var value: int32 = this.call();
                        } catch (known: java.lang.IllegalStateException) {
                            var a: int32 = 1;
                        } finally {
                            var c: int32 = 3;
                        }
                    }
                    private function call(): int32 { return 1; }
                }
                public function main(): int32 { return 0; }
            """.trimIndent(),
            "try-multi-catch" to """
                package test;
                public class Runner {
                    public function go(): void {
                        try {
                            var value: int32 = this.call();
                        } catch (known: java.lang.IllegalStateException) {
                            var a: int32 = 1;
                        } catch (other: java.lang.Throwable) {
                            var b: int32 = 2;
                        }
                    }
                    private function call(): int32 { return 1; }
                }
                public function main(): int32 { return 0; }
            """.trimIndent(),
            "try-finally" to """
                package test;
                public class Runner {
                    public function go(): void {
                        try {
                            var value: int32 = this.call();
                        } finally {
                            var c: int32 = 3;
                        }
                    }
                    private function call(): int32 { return 1; }
                }
                public function main(): int32 { return 0; }
            """.trimIndent()
        )
        val newFiles = listOf(
            "Runnable.jas", "Executor.jas", "Future.jas", "CancellationToken.jas",
            "TaskGroup.jas", "Context.jas", "CleanupBudget.jas", "JvmExecutor.jas"
        )
        val allStdlib = jasper.stdlib.StdLib.sources()
        val cleanupDir = Paths.get("build", "scratch", "s4-compile-probe", "cleanup-inspect")
        if (Files.exists(cleanupDir)) cleanupDir.toFile().deleteRecursively()
        Files.createDirectories(cleanupDir)
        val cleanupCompile = JasperCompiler().compileAllWithStdlib(
            mapOf("Main.jas" to "package std;\npublic function main(): int32 { return 0; }\n"),
            cleanupDir
        )
        val cleanupInfo = buildString {
            appendLine("compile=$cleanupCompile")
            val classFile = cleanupDir.resolve("std/CleanupBudget.class")
            if (Files.exists(classFile)) {
                URLClassLoader(arrayOf(cleanupDir.toUri().toURL())).use { loader ->
                    val clazz = loader.loadClass("std.CleanupBudget")
                    for (method in clazz.declaredMethods) {
                        appendLine(
                            "method=${method.name} static=" +
                                java.lang.reflect.Modifier.isStatic(method.modifiers)
                        )
                    }
                }
            } else {
                appendLine("CleanupBudget.class missing")
            }
        }
        val progressive = linkedMapOf<String, String>()
        val mainSource = "package std;\npublic function main(): int32 { return 0; }\n"
        for (newFile in newFiles) {
            progressive[newFile] = allStdlib.getValue(newFile)
            val subset = LinkedHashMap<String, String>()
            for ((name, text) in allStdlib) {
                if (name in newFiles && name !in progressive) continue
                subset[name] = text
            }
            subset["Main.jas"] = mainSource
            val dir = Paths.get("build", "scratch", "s4-compile-probe", "prefix-$newFile")
            if (Files.exists(dir)) dir.toFile().deleteRecursively()
            Files.createDirectories(dir)
            val prefixResult = try {
                when (val r = JasperCompiler().compileAllToDir(subset, dir)) {
                    is JasperCompiler.CompileResult.Success -> "SUCCESS"
                    is JasperCompiler.CompileResult.Error -> "ERROR: " + r.errors.joinToString("; ") { e ->
                        when (e) {
                            is jasper.compiler.CompileError.Internal ->
                                "Internal(${e.message}) cause=${e.cause?.message}"
                            else -> e.toString()
                        }
                    }
                }
            } catch (t: Throwable) {
                "THROWN: " + t.message
            }
            snippets["prefix-through-$newFile"] = ""
            snippets["prefix-result-$newFile"] = prefixResult
        }
        val text = buildString {
            appendLine("=== stdlib ===")
            appendLine(stdlib)
            appendLine("=== cleanup class ===")
            appendLine(cleanupInfo)
            for ((name, source) in snippets) {
                if (name.startsWith("prefix-result-")) {
                    appendLine("=== $name ===")
                    appendLine(source)
                } else if (source.isNotEmpty()) {
                    appendLine("=== $name ===")
                    appendLine(compileSource("snippet-$name", source))
                }
            }
        }
        Files.createDirectories(Paths.get("..", "evidence"))
        Files.writeString(Paths.get("..", "evidence", "s4-compile-probe.txt"), text)
    }
}
