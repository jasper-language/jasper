// ============================================================================
// Java8BytecodeCompatibilityTest —— 生成 class 的 Java 8 / Android 兼容闸门
// 所属层次：compiler 端到端产物校验。
// 为什么必须读原始字节：仅声称 ASM 使用 V1_8 不足以发现后续误改；class 版本与常量池
//   标签只有写出后检查，才能证明实际产物可被 Java 8 与 D8 读取。
// 产物位置：build/scratch/java8-bytecode/（仓库内）。
// ============================================================================

package jasper

import jasper.compiler.JasperCompiler
import java.nio.charset.StandardCharsets
import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.Paths
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * 做什么：编译含 C28 平台静态调用、普通类与枚举的源码，并逐个校验生成 class 是 Java 8。
 *
 * 什么时候用：修改任一 JVM 后端、ASM 版本或 class 生成选项时必须运行。
 * 前置条件：JasperCompiler 能写出完整 class 文件。
 * 失败模式：任一类 major/minor 非 52/0，或出现 Java 9+ 常量池项/属性时测试失败。
 * 为什么直接解析字节：major/minor 与常量池标签是 Java 8/D8 兼容性的明确边界，不能由
 *   编译器配置或注解间接证明；测试也不依赖 javap 的输出格式。
 */
class Java8BytecodeCompatibilityTest {

    /** 编译代表性源码并返回输出目录。 */
    private fun compileRepresentativeSource(): Path {
        val source = """
            package test;
            public class Box {
                public constructor() {}
                public static function tag(): int32 { return 2; }
            }
            public enum Color { Red, Green }
            public function main(): int32 {
                var box = new Box();
                if (Color.values().length != 2) { return 1; }
                return java.lang.Integer.parseInt("40") + Box.tag();
            }
        """.trimIndent()
        val output = Paths.get("build", "scratch", "java8-bytecode", "representative")
        if (Files.exists(output)) output.toFile().deleteRecursively()
        Files.createDirectories(output)
        val result = JasperCompiler().compileToDirWithIr(source, output)
        assertTrue(
            result is JasperCompiler.CompileResult.Success,
            "compilation failed: " + (result as? JasperCompiler.CompileResult.Error)?.message.orEmpty()
        )
        return output
    }

    /** 读取并校验输出目录里的每个 class 文件。 */
    @Test
    fun `generated classes are Java 8 with no Java 9 plus structures`() {
        val output = compileRepresentativeSource()
        val classFiles = Files.walk(output).use { stream ->
            stream.filter { it.fileName.toString().endsWith(".class") }.toList()
        }
        assertTrue(classFiles.size >= 3, "expected top-level, class, and enum outputs, got $classFiles")
        for (classFile in classFiles) {
            val parser = ClassFileParser(Files.readAllBytes(classFile))
            assertEquals(listOf(52, 0), parser.versionMajorMinor(), "Java 8 class version required: $classFile")
            val forbidden = parser.java9PlusStructures()
            assertTrue(
                forbidden.isEmpty(),
                "found Java 9+ class-file structures in $classFile: ${forbidden.joinToString()}"
            )
        }
    }

    /**
     * 最小 class 文件解析器：只读取版本、常量池标签与成员/类属性名。
     *
     * 做什么：定位需要禁止的 Java 9+ 结构，不解释方法字节码。
     * 什么时候用：上述兼容性测试对每个生成 class 调用一次。
     * 前置条件：输入是 Jasper 产出的合法 class 文件。
     * 失败模式：截断/非法索引抛 IllegalArgumentException，使测试失败而非静默放行。
     * 为什么手写最小解析：检测目标正好是 class 文件外框；直接检查字节比依赖 ASM 的版本
     *   容忍度更严格，也能明确覆盖 CONSTANT_Module/Package 与 Record 属性。
     */
    private class ClassFileParser(private val bytes: ByteArray) {
        private var pos = 8
        private lateinit var utf8: List<String?>

        fun versionMajorMinor(): List<Int> {
            require(bytes.size >= 8)
            require(
                bytes[0].toInt() and 0xff == 0xca &&
                    bytes[1].toInt() and 0xff == 0xfe &&
                    bytes[2].toInt() and 0xff == 0xba &&
                    bytes[3].toInt() and 0xff == 0xbe
            ) { "bad class-file magic" }
            val minor = u2At(4)
            val major = u2At(6)
            return listOf(major, minor)
        }

        fun java9PlusStructures(): List<String> {
            require(versionMajorMinor() == listOf(52, 0))
            pos = 8
            val forbidden = mutableListOf<String>()
            val cpCount = u2()
            val strings = MutableList<String?>(cpCount) { null }
            var index = 1
            while (index < cpCount) {
                when (u1()) {
                    1 -> {
                        val length = u2()
                        strings[index] = String(bytes, pos, length, StandardCharsets.UTF_8)
                        pos += length
                    }
                    3, 4, 9, 10, 11, 12, 17, 18 -> {
                        if ((bytes[pos - 1].toInt() and 0xff) == 17) {
                            forbidden += "CONSTANT_Dynamic"
                        }
                        pos += 4
                    }
                    5, 6 -> {
                        pos += 8
                        index++
                    }
                    7, 8, 16 -> pos += 2
                    19 -> {
                        forbidden += "CONSTANT_Module"
                        pos += 2
                    }
                    20 -> {
                        forbidden += "CONSTANT_Package"
                        pos += 2
                    }
                    15 -> pos += 3
                    else -> throw IllegalArgumentException("unknown constant-pool tag at $pos")
                }
                index++
            }
            utf8 = strings
            pos += 6 // access_flags, this_class, super_class
            val interfaceCount = u2()
            pos += interfaceCount * 2
            repeat(u2()) { skipMember(forbidden) }
            repeat(u2()) { skipMember(forbidden) }
            repeat(u2()) { checkAttribute(forbidden) }
            return forbidden.distinct()
        }

        private fun skipMember(forbidden: MutableList<String>) {
            pos += 6 // access_flags, name_index, descriptor_index
            repeat(u2()) { checkAttribute(forbidden) }
        }

        private fun checkAttribute(forbidden: MutableList<String>) {
            val name = utf8[u2()] ?: throw IllegalArgumentException("bad attribute name index")
            val length = u4()
            if (name in JAVA9_PLUS_ATTRIBUTES) forbidden += name
            pos += length
        }

        private fun u1(): Int {
            require(pos < bytes.size) { "truncated class file" }
            return bytes[pos++].toInt() and 0xff
        }

        private fun u2(): Int {
            val high = u1()
            return (high shl 8) or u1()
        }

        private fun u4At(offset: Int): Int {
            require(offset + 4 <= bytes.size)
            return ((bytes[offset].toInt() and 0xff) shl 24) or
                ((bytes[offset + 1].toInt() and 0xff) shl 16) or
                ((bytes[offset + 2].toInt() and 0xff) shl 8) or
                (bytes[offset + 3].toInt() and 0xff)
        }

        private fun u2At(offset: Int): Int = (u1AtIndex(offset) shl 8) or u1AtIndex(offset + 1)

        private fun u1AtIndex(offset: Int): Int {
            require(offset < bytes.size)
            return bytes[offset].toInt() and 0xff
        }

        private fun u4(): Int {
            val value = u4At(pos)
            pos += 4
            return value
        }

        companion object {
            private val JAVA9_PLUS_ATTRIBUTES = setOf(
                "Module",
                "ModulePackages",
                "ModuleMainClass",
                "Record",
                "NestHost",
                "NestMembers",
                "PermittedSubclasses"
            )
        }
    }
}
