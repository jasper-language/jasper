package jucs

/*
 * ============================================================================
 * SchemaValidationTest.kt —— 契约校验与注册表测试
 * ============================================================================
 *
 * 覆盖：
 *   - 内建契约全部合法；
 *   - 每条不变量都有一个反例（身份、动词、参数、默认值、权限作用域、效果矛盾）；
 *   - 注册表拒绝重复 ID，且拒绝时不做部分修改。
 *
 * English summary:
 *   Contract validation and registry behaviour, success and failure paths.
 */

import jucs.commands.FsCommands
import jucs.commands.SystemCommands
import jucs.registry.CommandHandler
import jucs.registry.CommandRegistry
import jucs.registry.RegistrationResult
import jucs.runtime.CommandData
import jucs.runtime.CommandOutcome
import jucs.schema.CommandSchema
import jucs.schema.Effects
import jucs.schema.JucsNames
import jucs.schema.OfficialVocabulary
import jucs.schema.ParamKind
import jucs.schema.ParamSpec
import jucs.schema.Permission
import jucs.schema.SchemaValidation
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class SchemaValidationTest {

    private fun base(
        id: String = "demo.thing.list",
        params: List<ParamSpec> = emptyList(),
        effects: Effects = Effects(readOnly = true),
        permissions: List<Permission> = listOf(Permission("demo.read")),
    ) = CommandSchema(
        id = id,
        publisher = "org.jasper.test",
        version = "1.0.0",
        summaryZh = "测试命令",
        summaryEn = "Test command.",
        params = params,
        outputType = "Record<Thing>",
        effects = effects,
        permissions = permissions,
    )

    private fun problems(schema: CommandSchema): List<String> = SchemaValidation.validate(schema)

    @Test
    fun builtinSchemasAreValid() {
        assertEquals(emptyList(), problems(SystemCommands.infoGetSchema))
        assertEquals(emptyList(), problems(FsCommands.fileListSchema))
    }

    @Test
    fun commandIdMustHaveThreeKebabSegments() {
        assertTrue(problems(base(id = "demo.thing")).any { it.contains("三段") || it.contains("命令 ID") })
        assertTrue(problems(base(id = "demo.Thing.list")).any { it.contains("kebab-case") })
        assertTrue(problems(base(id = "demo.thing.rm")).any { it.contains("官方动词") })
    }

    @Test
    fun implicitAndMetaParametersCannotBeRedeclared() {
        val implicit = base(params = listOf(ParamSpec("output", ParamKind.ENUM, "输出", "Output.", enumValues = listOf("table"))))
        assertTrue(problems(implicit).any { it.contains("隐式提供") })
        val meta = base(params = listOf(ParamSpec("help", ParamKind.BOOL, "帮助", "Help.")))
        assertTrue(problems(meta).any { it.contains("元命令") })
    }

    @Test
    fun officialParameterKindIsFixed() {
        val wrong = base(params = listOf(ParamSpec("limit", ParamKind.STRING, "上限", "Limit.")))
        assertTrue(problems(wrong).any { it.contains("官方参数") && it.contains("类型") })
    }

    @Test
    fun duplicateAndMalformedParametersAreRejected() {
        val duplicate = base(
            params = listOf(
                ParamSpec("path", ParamKind.PATH, "路径", "Path.", required = true),
                ParamSpec("path", ParamKind.PATH, "路径", "Path."),
            ),
        )
        assertTrue(problems(duplicate).any { it.contains("重复声明") })
        val malformed = base(params = listOf(ParamSpec("badName", ParamKind.STRING, "坏名", "Bad name.")))
        assertTrue(problems(malformed).any { it.contains("kebab-case") })
    }

    @Test
    fun enumDefaultsMustBelongToTheValueSet() {
        val bad = base(
            params = listOf(
                ParamSpec(
                    "order", ParamKind.ENUM, "方向", "Order.",
                    enumValues = listOf("asc", "desc"), defaultValue = "up",
                ),
            ),
        )
        assertTrue(problems(bad).any { it.contains("默认值不在枚举取值内") })
    }

    @Test
    fun requiredAndRepeatableParametersCannotCarryDefaults() {
        val required = base(
            params = listOf(ParamSpec("path", ParamKind.PATH, "路径", "Path.", required = true, defaultValue = ".")),
        )
        assertTrue(problems(required).any { it.contains("必填") })
        val repeatable = base(
            params = listOf(ParamSpec("filter", ParamKind.STRING, "过滤", "Filter.", repeatable = true, defaultValue = "x")),
        )
        assertTrue(problems(repeatable).any { it.contains("可重复") })
    }

    @Test
    fun permissionScopeMustReferenceADeclaredParameter() {
        val bad = base(permissions = listOf(Permission("filesystem.read", scopeFrom = "path")))
        assertTrue(problems(bad).any { it.contains("未在契约中声明") })
    }

    @Test
    fun readOnlyAndDestructiveAreContradictory() {
        val bad = base(effects = Effects(readOnly = true, destructive = true))
        assertTrue(problems(bad).any { it.contains("矛盾") })
    }

    @Test
    fun registryRejectsDuplicatesAndKeepsStateUnchanged() {
        val registry = CommandRegistry()
        val schema = base()
        val handler = CommandHandler { _, _ -> CommandOutcome(CommandData.Record(emptyList())) }
        assertEquals(RegistrationResult.Ok, registry.register(schema, handler))
        val again = registry.register(schema, handler)
        assertTrue(again is RegistrationResult.Rejected)
        assertTrue((again as RegistrationResult.Rejected).problems.any { it.contains("重复注册") })
        assertEquals(1, registry.all().size)
    }

    @Test
    fun registryRejectsInvalidSchemaWithoutRegistering() {
        val registry = CommandRegistry()
        val handler = CommandHandler { _, _ -> CommandOutcome(CommandData.Record(emptyList())) }
        val result = registry.register(base(id = "demo.Thing.list"), handler)
        assertTrue(result is RegistrationResult.Rejected)
        assertEquals(emptyList(), registry.all())
    }

    @Test
    fun removeIsOfficialAndRetiredDeleteGetsRemoveHint() {
        // 正向：remove 是官方动词，合法命令身份可以通过校验。
        assertEquals(emptyList(), problems(base(id = "demo.thing.remove")))
        assertTrue(OfficialVocabulary.isOfficialVerb("remove"))

        // 反向：delete 作为命令动词必须被拒绝，并明确提示改用 remove。
        val reasons = problems(base(id = "demo.thing.delete"))
        assertTrue(reasons.any { it.contains("非官方动词") })
        assertTrue(reasons.any { it.contains("remove") && it.contains("已退役") })

        // 其他非官方动词只报非官方，不附带 remove 提示。
        val other = problems(base(id = "demo.thing.show"))
        assertTrue(other.any { it.contains("非官方动词") })
        assertTrue(other.none { it.contains("已退役") })
    }

    @Test
    fun namingRulesAreConsistent() {
        assertTrue(JucsNames.isKebab("dry-run"))
        assertTrue(!JucsNames.isKebab("dry--run"))
        assertTrue(!JucsNames.isKebab("-run"))
        assertTrue(!JucsNames.isKebab("run-"))
        assertTrue(!JucsNames.isKebab("Run"))
        assertTrue(JucsNames.isCommandId("fs.file.list"))
        assertTrue(JucsNames.isSemanticVersion("0.1.0"))
        assertTrue(!JucsNames.isSemanticVersion("01.0.0"))
        assertEquals(13, OfficialVocabulary.VERBS.size)
    }
}
