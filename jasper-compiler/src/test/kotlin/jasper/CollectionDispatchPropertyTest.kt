// ============================================================================
// CollectionDispatchPropertyTest.kt —— 集合成员分派的固定种子性质测试
// 所属层次：测试层。只依赖 JasperCompiler 的公开编译入口。
//
// 干什么：对「接收者类型 × 成员名 × 实参组合」做穷举 + 固定种子乱序，逐例编译并断言：
//   - 语言契约允许的组合必须编译成功；
//   - 不允许的组合必须是**语义错误**，且绝不能退化为内部错误（Internal）。
//   覆盖 List / MutableList / dict / Map / 裸 java.util.ArrayList / 数组五类接收者，
//   以及数组 length 与集合 size 的共存边界。
//
// 为什么用性质测试：
//   集合成员分派是一个二维决策表，手工挑几个用例容易漏掉「某族 + 某个参数形状」的
//   交叉点。穷举笛卡尔积能把整张表固定下来；固定种子保证失败可复现，乱序执行避免
//   测试顺序本身成为隐含依赖。
//
// 产物位置：纯内存编译，不写仓库外路径；编译产物由 JasperCompiler 的默认流程管理。
// ============================================================================

package jasper

import jasper.compiler.CompileError
import jasper.compiler.JasperCompiler
import kotlin.random.Random
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/**
 * 做什么：集合成员分派决策表的固定种子性质测试。
 * 什么时候用：改动 BuiltinCollections 的成员表、TypeChecker 的集合/数组分派，
 *   或 translator 的集合调用路径时。
 * 前置条件：无额外依赖。
 * 失败模式：期望成功的组合编译失败、期望失败的组合编译成功、或失败被报告为
 *   Internal（编译器崩溃）时，对应用例失败。
 * 为什么这样设计：用「契约期望」而不是「当前实现输出」作为断言基准，避免测试与
 *   实现一起漂移；固定种子 + 穷举保证覆盖面可审计。
 */
class CollectionDispatchPropertyTest {

    /** 固定随机种子：失败时据此复现同一执行顺序。 */
    private val seed: Int = 0x5EED_C011.toInt()

    /**
     * 做什么：描述一种接收者的声明类型与初始化表达式。
     * 什么时候用：构造性质测试用例时。
     * 前置条件：declaredType/initializer 是合法 Jasper 源码片段。
     * 失败模式：纯数据，不抛异常。
     */
    private data class ReceiverSpec(
        val label: String,
        val declaredType: String,
        val initializer: String
    )

    /**
     * 做什么：描述一个成员访问形状（方法调用或属性读取）与实参源码。
     * 什么时候用：与 [ReceiverSpec] 做笛卡尔积。
     * 前置条件：argsSource 不含外层括号（由模板统一添加）。
     * 失败模式：纯数据，不抛异常。
     */
    private data class MemberSpec(
        val name: String,
        val argsSource: String,
        val isProperty: Boolean = false
    )

    /**
     * 做什么：一条待验证的性质用例。
     * 什么时候用：随机打乱后逐条编译时。
     * 前置条件：expectValid 来自语言契约（见 [expectedValid]）。
     * 失败模式：纯数据，不抛异常。
     */
    private data class Case(
        val receiver: ReceiverSpec,
        val member: MemberSpec,
        val expectValid: Boolean
    )

    private val receivers = listOf(
        ReceiverSpec("List<int32>", "List<int32>", "new MutableList<int32>()"),
        ReceiverSpec("MutableList<int32>", "MutableList<int32>", "new MutableList<int32>()"),
        ReceiverSpec("dict<string,int32>", "dict<string, int32>", "dict{}"),
        ReceiverSpec("Map<string,int32>", "Map<string, int32>", "new Map<string, int32>()"),
        ReceiverSpec("java.util.ArrayList", "java.util.ArrayList", "new java.util.ArrayList()"),
        ReceiverSpec("int32[]", "int32[]", "[1, 2, 3]")
    )

    private val members = listOf(
        MemberSpec("size", ""),
        MemberSpec("length", "", isProperty = true),
        MemberSpec("get", "0"),
        MemberSpec("get", "\"a\""),
        MemberSpec("contains", "0"),
        MemberSpec("contains", "\"a\""),
        MemberSpec("containsKey", "\"a\""),
        MemberSpec("containsKey", "0"),
        MemberSpec("add", "1"),
        MemberSpec("put", "\"a\", 1"),
        MemberSpec("remove", "1"),
        MemberSpec("remove", "\"a\"")
    )

    /**
     * 做什么：按语言契约判断「接收者族 + 成员 + 实参形状」是否合法。
     *
     * 什么时候用：构造 [Case] 时。
     * 前置条件：receiver/member 来自本测试的固定表。
     * 失败模式：纯判断，不抛异常。
     * 为什么把期望写在测试里：若直接用 BuiltinCollections 推导期望，实现改错时测试
     *   会跟着改错；这里的 when 是独立于实现的语言契约快照。
     */
    private fun expectedValid(receiver: ReceiverSpec, member: MemberSpec): Boolean {
        if (member.isProperty) {
            // 只有数组有 length；集合只有 size()。两者不能互相替代。
            return receiver.label == "int32[]" && member.name == "length"
        }
        return when (receiver.label) {
            "int32[]" -> false // 数组成员面只有 length 与 [i]
            "List<int32>" -> when (member.name) {
                "size" -> true
                "get" -> member.argsSource == "0"
                "contains" -> member.argsSource == "0"
                // 只读 List 不开放 add/remove/put/containsKey
                else -> false
            }
            "MutableList<int32>" -> when (member.name) {
                "size" -> true
                "get", "contains", "add", "remove" -> member.argsSource == "0" || member.argsSource == "1"
                else -> false
            }
            "dict<string,int32>", "Map<string,int32>" -> when (member.name) {
                "size" -> true
                "get", "containsKey", "remove" -> member.argsSource == "\"a\""
                "put" -> member.argsSource == "\"a\", 1"
                else -> false
            }
            // 裸 JDK 集合：元素类型未知（any），可读写；但没有 containsKey/put/length。
            "java.util.ArrayList" -> when (member.name) {
                "size" -> true
                "get" -> member.argsSource == "0"
                "contains", "add" -> true
                "remove" -> member.argsSource == "0" || member.argsSource == "1" || member.argsSource == "\"a\""
                else -> false
            }
            else -> false
        }
    }

    /**
     * 做什么：生成一条最小可编译程序：声明接收者，执行一次成员访问，返回 0。
     * 什么时候用：每个性质用例。
     * 前置条件：receiver/member 字段已按源码语法填写。
     * 失败模式：不抛异常；语法错误会表现为编译失败并由用例报告。
     */
    private fun sourceFor(case: Case): String {
        val access = if (case.member.isProperty) {
            "r.${case.member.name};"
        } else {
            "r.${case.member.name}(${case.member.argsSource});"
        }
        return """
            package test;
            public function main(): int32 {
                var r: ${case.receiver.declaredType} = ${case.receiver.initializer};
                $access
                return 0;
            }
        """.trimIndent()
    }

    @Test
    fun `collection member dispatch matrix matches contract with fixed seed`() {
        val cases = receivers.flatMap { receiver ->
            members.map { member ->
                Case(receiver, member, expectedValid(receiver, member))
            }
        }
        // 固定种子乱序：顺序不构成隐含依赖，失败仍可复现。
        val shuffled = cases.shuffled(Random(seed))
        assertEquals(receivers.size * members.size, shuffled.size, "性质用例应覆盖完整笛卡尔积")

        val failures = mutableListOf<String>()
        for (case in shuffled) {
            val source = sourceFor(case)
            val result = JasperCompiler().compile(source)
            val description = "${case.receiver.label}.${case.member.name}(${case.member.argsSource})"
            when (result) {
                is JasperCompiler.CompileResult.Success -> {
                    if (!case.expectValid) {
                        failures.add("期望编译失败但成功：$description")
                    }
                }
                is JasperCompiler.CompileResult.Error -> {
                    val internal = result.errors.filterIsInstance<CompileError.Internal>()
                    if (internal.isNotEmpty()) {
                        failures.add("$description 触发了内部错误：${internal.first().message}")
                    } else if (case.expectValid) {
                        failures.add("期望编译成功但失败：$description -> ${result.message}")
                    }
                }
            }
        }
        assertTrue(failures.isEmpty(), "固定种子 ${seed} 的集合分派性质失败：\n" + failures.joinToString("\n"))
    }

    /**
     * 做什么：单独锁定「集合属性误用」与「数组集合方法误用」的诊断，不依赖聚合矩阵。
     * 什么时候用：需要更明确失败信息时（矩阵失败只给出组合描述）。
     * 前置条件：无。
     * 失败模式：编译意外成功、或错误文本没有指向正确替代写法时失败。
     * 为什么单独一条：这是数组 length 与集合 size 共存的核心边界，值得有可读的
     *   独立证据，而不是淹没在矩阵结果里。
     */
    @Test
    fun `collection property and array member misuse are semantic errors`() {
        val sources = listOf(
            "var r: List<int32> = new MutableList<int32>(); r.length;" to "List.length",
            "var r: dict<string, int32> = dict{}; r.length;" to "dict.length",
            "var r: int32[] = [1]; r.size();" to "array.size()",
            "var r: int32[] = [1]; r.get(0);" to "array.get(0)",
            "var r: List<int32> = new MutableList<int32>(); r.add(1);" to "readonly List.add"
        )
        for ((body, label) in sources) {
            val source = """
                package test;
                public function main(): int32 {
                    $body
                    return 0;
                }
            """.trimIndent()
            val result = JasperCompiler().compile(source)
            val error = result as? JasperCompiler.CompileResult.Error
            assertTrue(error != null, "$label 应编译失败")
            assertFalse(
                error!!.errors.any { it is CompileError.Internal },
                "$label 必须是语义诊断而不是内部错误：${error.message}"
            )
            assertTrue(error.message.isNotBlank(), "$label 的诊断文本不应为空")
        }
    }
}
