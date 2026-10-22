package jasper

import jasper.compiler.PlatformMethodResolver
import kotlin.random.Random
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull

/**
 * 做什么：用固定种子验证平台数值重载解析与描述符拆分对一组确定输入始终成立。
 *
 * 什么时候用：修改 PlatformMethodResolver 的候选筛选、精确重载优先或描述符派生逻辑时。
 * 前置条件：测试 JVM 提供标准库 `java.lang.Math`。
 * 失败模式：解析返回 null、选错数值重载，或 descriptor 的形参/返回拆分不一致。
 * 为什么固定种子：失败可用同一 JVM/标准库与同一源码无状态复现；生成范围仅含 Java 8
 *   Math 已提供明确重载的四个数值类型，不含依赖 JDK 版本的候选。
 */
class PlatformMethodDescriptorPropertyTest {
    @Test
    fun `fixed seed Math numeric descriptors round trip`() {
        val resolver = PlatformMethodResolver()
        val random = Random(0xC2801)
        val numericDescriptors = listOf("I", "J", "F", "D")

        repeat(128) {
            val descriptor = numericDescriptors[random.nextInt(numericDescriptors.size)]
            val max = assertNotNull(
                resolver.resolveStatic("java/lang/Math", "max", listOf(descriptor, descriptor)),
                "Math.max($descriptor,$descriptor) must resolve"
            )
            assertEquals("($descriptor$descriptor)$descriptor", max.descriptor)
            assertEquals(listOf(descriptor, descriptor), max.parameterDescriptors)
            assertEquals(descriptor, max.returnDescriptor)

            val abs = assertNotNull(
                resolver.resolveStatic("java/lang/Math", "abs", listOf(descriptor)),
                "Math.abs($descriptor) must resolve"
            )
            assertEquals("($descriptor)$descriptor", abs.descriptor)
        }
    }
}
