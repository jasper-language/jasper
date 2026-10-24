// ============================================================================
// JasperAstBuilder —— ANTLR 解析树 → Jasper AST 的 Visitor 构建器
// ============================================================================
// 职责：把 jasper.parser.JasperParser 产出的语法树自顶向下翻译为 jasper.ast 中的
//       Jas* 节点（JasSourceFile / JasDeclaration / JasStatement / JasExpression / JasType）。
// 所属层次：ast（最底层）。只依赖 jasper.parser（ANTLR 生成物）与同层节点定义，
//       不反向依赖 checker / translator / compiler。
// 协作关系：上游 compiler 编排流程调用 buildSourceFile(ctx) 取回 JasSourceFile；
//       下游校验与翻译阶段只消费本构建器产出的 AST 节点。
// 对外契约：buildSourceFile(ctx): JasSourceFile —— 返回包名、顶层类型声明与 import 列表；
//       所有节点经 at(ctx) 回填 sourceLine / sourceColumn。
// 扩展点（Visitor 模式隔离）：新增或修改一条语法规则时，变化被收束在「对应规则标签的
//       那一个 visitXxx 覆写」内——新增规则 = 新增一个覆写，其余回调与下游消费者不受影响。
// 已知限制：1) visitFieldDeclaration 仅返回单个 JasProperty，多变量字段声明会丢后续绑定；
//       2) 部分 switch / try-with-resources 形态未完整建模；3) 未知类型原子回退为
//       JasAnyType 或以文本构造 JasNamedType，属容错而非精确解析。
// ============================================================================
package jasper.ast

import jasper.parser.JasperParser
import jasper.parser.JasperParserBaseVisitor
import org.antlr.v4.runtime.ParserRuleContext
import org.antlr.v4.runtime.tree.ParseTree

/**
 * 解析树 → AST 的 Visitor 实现：把 JasperParser 的每条规则翻译成对应的 Jas* 节点。
 *
 * 扩展点：新增一条语法规则时，只需新增对应的 visitXxx 覆写，变化被收束在该覆写内部。
 */
class JasperAstBuilder : JasperParserBaseVisitor<JasNode?>() {

    /**
     * 把解析树节点的起始位置回填到 AST 节点的源码行列。
     *
     * 前置条件：ctx 非空；返回 this 以便在构造表达式后链式调用。
     */
    private fun <T : JasNode> T.at(ctx: ParserRuleContext): T {
        // 为什么：下游报错/调试需要源码坐标；边界：只记录规则起始 token 的位置。
        sourceLine = ctx.start.line
        sourceColumn = ctx.start.charPositionInLine
        return this
    }

    /**
     * Visitor 的公共入口：把整个源文件解析树组装为 JasSourceFile。
     *
     * 前置条件：ctx 为 SourceFileContext 根节点。失败模式：包声明缺失时 packageName 为 null；
     * 顶层非声明节点会被忽略（仅保留 JasDeclaration）。
     */
    fun buildSourceFile(ctx: JasperParser.SourceFileContext): JasSourceFile {
        var packageName: String? = null
        // 包声明复用 JasImport 承载包名；缺失时 packageName 保持 null。
        if (ctx.packageDeclaration() != null) {
            val pkg = visitPackageDeclaration(ctx.packageDeclaration())
            if (pkg is JasImport) {
                packageName = pkg.name
            }
        }
        val imports = mutableListOf<JasDeclaration>()
        // import 有单条 / on-demand / from 三类标签，统一在 collectImports 归一化。
        for (imp in ctx.importDeclaration()) {
            collectImports(imp, imports)
        }
        val declarations = mutableListOf<JasDeclaration>()
        // 契约：源文件只保留一个顶层类型声明（非 JasDeclaration 的节点被忽略）。
        val topLevelType = visit(ctx.topLevelTypeDeclaration())
        if (topLevelType is JasDeclaration) declarations.add(topLevelType)
        return JasSourceFile(packageName, declarations, imports)
    }

    /**
     * 归一化 import 声明，把单条 / on-demand / from-import 三种形态统一收集进 out。
     *
     * 前置条件：imp 为 ImportDeclaration 的某个标签上下文。边界：from-import 支持限定名
     * 与字符串两种来源；通配（clause=all）合成 name="*" 且 isOnDemand=true。
     */
    private fun collectImports(imp: JasperParser.ImportDeclarationContext, out: MutableList<JasDeclaration>) {
        // 前缀声明三种标签：精确导入 / 通配导入 / from-import。
        when (imp) {
            is JasperParser.ImportSingleContext -> {
                visitImportSingle(imp)?.let { if (it is JasDeclaration) out.add(it) }
            }
            is JasperParser.ImportOnDemandContext -> {
                visitImportOnDemand(imp)?.let { if (it is JasDeclaration) out.add(it) }
            }
            is JasperParser.ImportFromContext -> {
                // from-import：来源可为限定名或字符串，子句决定通配还是逐项（逐项带 alias）。
                val fromCtx = imp.fromImportDeclaration()
                val source: String?
                val clause: JasperParser.ImportClauseContext
                when (fromCtx) {
                    is JasperParser.FromImportQualifiedContext -> {
                        source = fromCtx.qn.text; clause = fromCtx.clause
                    }
                    is JasperParser.FromImportStringContext -> {
                        source = fromCtx.s.text; clause = fromCtx.clause
                    }
                    else -> { source = null; clause = null!! }
                }
                if (clause is JasperParser.ImportClauseAllContext) {
                    out.add(JasImport("*", null, true, source))
                } else if (clause is JasperParser.ImportClauseItemsContext) {
                    for (item in clause.importItem()) {
                        out.add(JasImport(item.name.text, item.alias?.text, false, source))
                    }
                }
            }
        }
    }

    /**
     * packageDeclaration 规则：复用 JasImport 作为包名载体（isOnDemand=false，无来源）。
     */
    override fun visitPackageDeclaration(ctx: JasperParser.PackageDeclarationContext): JasNode? {
        return JasImport(ctx.qualifiedName().text, null, false, null)
    }

    /**
     * ImportSingle 规则（Import qn ';'）：产出精确导入 JasImport(qn, alias=null)。
     */
    override fun visitImportSingle(ctx: JasperParser.ImportSingleContext): JasNode? {
        return JasImport(ctx.qualifiedName().text, null, false, null)
    }

    /**
     * ImportOnDemand 规则（Import qn '.*' ';'）：名后追加 ".*" 并置 isOnDemand=true。
     */
    override fun visitImportOnDemand(ctx: JasperParser.ImportOnDemandContext): JasNode? {
        return JasImport(ctx.qualifiedName().text + ".*", null, true, null)
    }


    // 鈹€鈹€ Normal class declaration 鈹€鈹€

    /**
     * NormalClassDeclaration 规则：聚合修饰符 / 类型参数 / 父类 / 接口 / 成员 / 构造器，产出 JasClass。
     *
     * 边界：类体成员里 JasConstructor 单独收集，其余 JasDeclaration 进 members；父类与接口
     * 解析失败（as? JasType 为 null）时会被静默丢弃，故 namedType 覆写必须存在。
     */
    override fun visitNormalClassDeclaration(ctx: JasperParser.NormalClassDeclarationContext): JasNode? {
        val mods = doExtractMods(ctx.modifier())
        val typeParams = if (ctx.tparams != null) doVisitTypeParams(ctx.tparams) else emptyList()
        val superclass = if (ctx.sc != null) visit(ctx.sc.namedType()) as? JasType else null
        val interfaces = if (ctx.sis != null) ctx.sis.namedType().mapNotNull { visit(it) as? JasType } else emptyList()
        val members = mutableListOf<JasDeclaration>()
        val constructors = mutableListOf<JasConstructor>()
        for (member in ctx.body.classBodyDeclaration()) {
            when (val decl = visit(member)) {
                is JasConstructor -> constructors.add(decl)
                is JasDeclaration -> members.add(decl)
                else -> {}
            }
        }
        return JasClass(ctx.name.text, mods, typeParams, superclass, interfaces, members, constructors).at(ctx)
    }

    /**
     * ClassBodyDeclaration 的 MemberField 标签：字段声明的转发壳，直接 visit 内层 decl。
     */
    override fun visitMemberField(ctx: JasperParser.MemberFieldContext): JasNode? = visit(ctx.decl)
    /**
     * ClassBodyDeclaration 的 MemberProperty 标签：属性声明的转发壳，直接 visit 内层 decl。
     */
    override fun visitMemberProperty(ctx: JasperParser.MemberPropertyContext): JasNode? = visit(ctx.decl)
    /**
     * ClassBodyDeclaration 的 MemberConstructor 标签：构造器声明的转发壳。
     */
    override fun visitMemberConstructor(ctx: JasperParser.MemberConstructorContext): JasNode? = visit(ctx.decl)
    /**
     * ClassBodyDeclaration 的 MemberMethod 标签：方法声明的转发壳。
     */
    override fun visitMemberMethod(ctx: JasperParser.MemberMethodContext): JasNode? = visit(ctx.decl)
    /**
     * ClassBodyDeclaration 的 MemberClass 标签：嵌套类声明的转发壳。
     */
    override fun visitMemberClass(ctx: JasperParser.MemberClassContext): JasNode? = visit(ctx.decl)
    /**
     * ClassBodyDeclaration 的 MemberEnum 标签：嵌套枚举声明的转发壳。
     */
    override fun visitMemberEnum(ctx: JasperParser.MemberEnumContext): JasNode? = visit(ctx.decl)
    /**
     * ClassBodyDeclaration 的 MemberInterface 标签：嵌套接口声明的转发壳。
     */
    override fun visitMemberInterface(ctx: JasperParser.MemberInterfaceContext): JasNode? = visit(ctx.decl)
    /**
     * ClassBodyDeclaration 的 MemberAnnotationType 标签：嵌套注解类型声明的转发壳。
     */
    override fun visitMemberAnnotationType(ctx: JasperParser.MemberAnnotationTypeContext): JasNode? = visit(ctx.decl)
    /**
     * ClassBodyDeclaration 的 MemberEmpty 标签（单独的分号）：无 AST 产出，返回 null。
     */
    override fun visitMemberEmpty(ctx: JasperParser.MemberEmptyContext): JasNode? = null

    /**
     * fieldDeclaration 规则：为每个 variableBinding 构造 JasProperty，返回单绑定或 null。
     *
     * 已知限制：仅当恰好一个绑定时返回该 JasProperty，多绑定声明会丢弃其余绑定。
     */
    override fun visitFieldDeclaration(ctx: JasperParser.FieldDeclarationContext): JasNode? {
        val mods = doExtractMods(ctx.modifier())
        val props = ctx.tail.variableBinding().map { vb ->
            val b = vb.core
            // 声明处初始化器：实例字段目前仍由构造器赋值，静态字段则依赖它生成 <clinit>。
            // 这里只负责如实把语法树搬进 AST，具体消费方式由 translator 按 static/final 决定。
            val initializer = if (vb.`init` != null) visit(vb.`init`) as? JasExpression else null
            JasProperty(
                b.name.text,
                if (b.typeRef != null) visit(b.typeRef) as? JasType else null,
                mods,
                null,
                null,
                ctx.kind.text == "const",
                initializer
            ).at(ctx)
        }
        return if (props.size == 1) props[0] else null
    }

    // ── Property declaration ──

    /**
     * PropertyDeclarationFull 规则（mods propertyHeader propertyBody ';'）：产出带 getter/setter 的 JasProperty。
     *
     * 边界：getter 参数名 null，setter 参数名固定为 "value"；无 body 时两者均 null。
     */
    override fun visitPropertyDeclarationFull(ctx: JasperParser.PropertyDeclarationFullContext): JasNode? {
        val mods = doExtractMods(ctx.modifier())
        val header = ctx.header
        val name = header.bindingRef.name.text
        val type = if (header.bindingRef.typeRef != null) visit(header.bindingRef.typeRef) as? JasType else null
        val isConst = header.kind.text == "const"
        var getter: JasPropertyAccessor? = null
        var setter: JasPropertyAccessor? = null
        if (ctx.body is JasperParser.PropertyBodyNodeContext) {
            for (acc in (ctx.body as JasperParser.PropertyBodyNodeContext).propertyAccessorDecl()) {
                when (acc) {
                    is JasperParser.PropertyAccessorGetContext -> getter = JasPropertyAccessor(null, null).at(ctx)
                    is JasperParser.PropertyAccessorSetContext -> setter = JasPropertyAccessor(null, "value").at(ctx)
                }
            }
        }
        return JasProperty(name, type, mods, getter, setter, isConst).at(ctx)
    }

    /**
     * PropertyAccessorGet 规则：产出 JasPropertyAccessor(body=null, parameterName=null)。
     */
    override fun visitPropertyAccessorGet(ctx: JasperParser.PropertyAccessorGetContext): JasNode? {
        return JasPropertyAccessor(null, null).at(ctx)
    }

    /**
     * PropertyAccessorSet 规则：产出 JasPropertyAccessor(body=null, parameterName="value")。
     */
    override fun visitPropertyAccessorSet(ctx: JasperParser.PropertyAccessorSetContext): JasNode? {
        return JasPropertyAccessor(null, "value").at(ctx)
    }

    // 鈹€鈹€ Method declaration 鈹€鈹€

    /**
     * MethodDeclarationFunctionStyle 规则：聚合修饰符 / 泛型 / 参数 / 返回类型 / where 约束 / 方法体，产出 JasFunction。
     *
     * 边界：方法体非 block（MethodBodyEmpty，即 ';'）时 body 置 null。
     */
    override fun visitMethodDeclarationFunctionStyle(ctx: JasperParser.MethodDeclarationFunctionStyleContext): JasNode? {
        val mods = doExtractMods(ctx.modifier())
        val typeParams = if (ctx.tparams != null) doVisitTypeParams(ctx.tparams) else emptyList()
        val params = if (ctx.plist != null) doVisitFuncParams(ctx.plist) else emptyList()
        val returnType = if (ctx.ret != null) visit(ctx.ret) as? JasType else null
        val whereConstraints = if (ctx.where != null) doVisitWhere(ctx.where) else emptyList()
        val body = when (val b = ctx.body) {
            is JasperParser.MethodBodyBlockContext -> visit(b.block()) as? JasBlock
            else -> null
        }
        return JasFunction(ctx.name.text, params, returnType, body, mods, typeParams, whereConstraints, throwsTypes = doVisitThrows(ctx.thr)).at(ctx)
    }

    // 鈹€鈹€ Parameters 鈹€鈹€

    /**
     * 私有辅助：把 FunctionParameterList 逐个 visit 并过滤成 JasParameter 列表。
     *
     * 前置条件：ctx 非空。
     */
    private fun doVisitFuncParams(ctx: JasperParser.FunctionParameterListContext): List<JasParameter> {
        return ctx.functionParameter().mapNotNull { visit(it) as? JasParameter }
    }

    /**
     * 私有辅助：把 `throws_` 规则解析成异常类型列表。
     *
     * 做什么：遍历 `Throws (exceptionType (',' exceptionType)* ','?)`，对每个
     * exceptionType 复用其内部 typeExpr 的解析结果，得到 `List<JasType>`。
     * 什么时候用：method / constructor / interface method 三条声明路径解析 `thr` 子句时。
     * 前置条件：ctx 可空；为 null（未写 throws）时返回空列表。
     * 失败模式：单个 exceptionType 的 typeExpr 若解析不出 JasType（理论上不会，因为
     *   exceptionType 规则只是 typeExpr 的包装）会被 mapNotNull 静默丢弃，其余照常保留。
     * 为什么这样设计：不重复造类型解析——直接复用 visit(typeExpr) 的既有能力；
     *   仅把「列表收集」这一重复逻辑收束到单一辅助，避免三处声明路径各写一遍。
     */
    private fun doVisitThrows(ctx: JasperParser.Throws_Context?): List<JasType> {
        if (ctx == null) return emptyList()
        return ctx.exceptionType().mapNotNull { visit(it.typeExpr()) as? JasType }
    }

    /**
     * functionParameter 规则：由 binding 取名字与类型，ELLIPSIS 存在即视为变参，产出 JasParameter。
     */
    override fun visitFunctionParameter(ctx: JasperParser.FunctionParameterContext): JasNode? {
        val b = ctx.bind
        val name = b.name.text
        val declared = if (b.typeRef != null) visit(b.typeRef) as? JasType else null
        val isVararg = ctx.ELLIPSIS() != null
        // 真 varargs（语法裁决②）：`xs: T...` 在函数体内就是元素数组 `T[]`。
        // 这里一次性把形参类型改写成数组，下游（检查器/翻译器/后端描述符）统一按数组处理，
        // vararg 标记只留给调用点决定「是否把多余实参打包成数组」。
        val type = if (isVararg && declared != null) JasArrayType(declared) else declared
        return JasParameter(name, type, isVararg, null).at(ctx)
    }

    // 鈹€鈹€ Constructor 鈹€鈹€

    /**
     * constructorDeclaration 规则：聚合修饰符 / 参数 / 显式构造调用 / 语句体，产出 JasConstructor。
     *
     * 边界：显式 this/super 调用提取为 delegateCall；语句体为空时仍包一个空 JasBlock。
     */
    override fun visitConstructorDeclaration(ctx: JasperParser.ConstructorDeclarationContext): JasNode? {
        val mods = doExtractMods(ctx.modifier())
        val header = ctx.header
        val params = if (header.plist != null) doVisitFuncParams(header.plist) else emptyList()
        val bodyCtx = ctx.body
        var delegate: JasExplicitConstructorInvocation? = null
        val stmts = mutableListOf<JasStatement>()
        if (bodyCtx.explicitConstructorInvocation() != null) {
            delegate = visit(bodyCtx.explicitConstructorInvocation()) as? JasExplicitConstructorInvocation
        }
        for (bs in bodyCtx.blockStatement()) {
            val s = visit(bs)
            if (s is JasStatement) stmts.add(s)
        }
        return JasConstructor(mods, params, delegate, if (stmts.isEmpty()) JasBlock(emptyList()) else JasBlock(stmts), throwsTypes = doVisitThrows(header.thr)).at(ctx)
    }

    /**
     * explicitConstructorInvocation 规则：Super() 记为 isSuper=true，其余（this）为 false，附带实参列表。
     */
    override fun visitExplicitConstructorInvocation(ctx: JasperParser.ExplicitConstructorInvocationContext): JasNode? {
        val isSuper = ctx.Super() != null
        val args = if (ctx.arguments() != null) doVisitExprList(ctx.arguments().exprList()) else emptyList()
        return JasExplicitConstructorInvocation(isSuper, args).at(ctx)
    }

    // 鈹€鈹€ Enum 鈹€鈹€

    /**
     * enumDeclaration 规则：收集枚举常量与枚举体成员，产出 JasEnum。
     */
    override fun visitEnumDeclaration(ctx: JasperParser.EnumDeclarationContext): JasNode? {
        val mods = doExtractMods(ctx.modifier())
        val constants = mutableListOf<JasEnumConstant>()
        val members = mutableListOf<JasDeclaration>()
        for (ec in ctx.body.enumConstant()) {
            visit(ec)?.let { if (it is JasEnumConstant) constants.add(it) }
        }
        if (ctx.body.enumBodyDeclarations() != null) {
            for (cd in ctx.body.enumBodyDeclarations().classBodyDeclaration()) {
                visit(cd)?.let { if (it is JasDeclaration) members.add(it) }
            }
        }
        return JasEnum(ctx.name.text, mods, constants, members).at(ctx)
    }

    /**
     * enumConstant 规则：取标识符名与可选实参，产出 JasEnumConstant。
     */
    override fun visitEnumConstant(ctx: JasperParser.EnumConstantContext): JasNode? {
        val args = if (ctx.arguments() != null) doVisitExprList(ctx.arguments().exprList()) else emptyList()
        return JasEnumConstant(ctx.Identifier().text, args).at(ctx)
    }

    // 鈹€鈹€ Interface 鈹€鈹€

    /**
     * NormalInterfaceDeclaration 规则：聚合修饰符 / 类型参数 / extends 列表 / 成员，产出 JasInterface。
     */
    override fun visitNormalInterfaceDeclaration(ctx: JasperParser.NormalInterfaceDeclarationContext): JasNode? {
        val mods = doExtractMods(ctx.modifier())
        val typeParams = if (ctx.tparams != null) doVisitTypeParams(ctx.tparams) else emptyList()
        val extends = if (ctx.exts != null) ctx.exts.typeExpr().mapNotNull { visit(it) as? JasType } else emptyList()
        val members = mutableListOf<JasDeclaration>()
        for (im in ctx.body.interfaceMemberDeclaration()) {
            visit(im)?.let { if (it is JasDeclaration) members.add(it) }
        }
        return JasInterface(ctx.name.text, mods, typeParams, extends, members).at(ctx)
    }

    /**
     * InterfaceMemberConst 规则：把接口常量构造成带初始化器的 JasProperty。
     *
     * 为什么：接口常量是声明而非语句，若走语句形态会从 AST 中消失；此处显式保留
     * 初值，供下游生成 public static final 字段。
     */
    override fun visitInterfaceMemberConst(ctx: JasperParser.InterfaceMemberConstContext): JasNode? {
        // An interface constant (`const MAX_ROWS: int32 = 100;`) is a declaration,
        // not a statement: it must survive into the AST so the translator can
        // emit a `public static final` field. Build a JasProperty (which carries
        // its initializer) instead of delegating to the statement-shaped
        // `visitConstantDeclaration`, which previously made the declaration
        // silently disappear (only `JasDeclaration`s are kept as interface
        // members).
        // 为什么保留为声明：只有 JasDeclaration 会被接口成员收集，走语句形态会丢失。
        val decl = ctx.decl
        val mods = doExtractMods(decl.modifier())
        val binding = decl.tail.variableBinding().firstOrNull() ?: return null
        val core = binding.core
        val type = if (core.typeRef != null) visit(core.typeRef) as? JasType else null
        val init = if (binding.`init` != null) visit(binding.`init`) as? JasExpression else null
        return JasProperty(core.name.text, type, mods, null, null, true, init).at(ctx)
    }
    /**
     * InterfaceMemberDeclaration 的 InterfaceMemberProperty 标签：属性声明转发壳。
     */
    override fun visitInterfaceMemberProperty(ctx: JasperParser.InterfaceMemberPropertyContext): JasNode? = visit(ctx.decl)
    /**
     * InterfaceMemberDeclaration 的 InterfaceMemberMethod 标签：带方法体的方法转发壳。
     */
    override fun visitInterfaceMemberMethod(ctx: JasperParser.InterfaceMemberMethodContext): JasNode? = visit(ctx.decl)
    /**
     * InterfaceMemberDeclaration 的 InterfaceMemberAbstractMethod 标签：抽象方法转发壳。
     */
    override fun visitInterfaceMemberAbstractMethod(ctx: JasperParser.InterfaceMemberAbstractMethodContext): JasNode? = visit(ctx.decl)
    /**
     * InterfaceMemberDeclaration 的 InterfaceMemberClass 标签：嵌套类转发壳。
     */
    override fun visitInterfaceMemberClass(ctx: JasperParser.InterfaceMemberClassContext): JasNode? = visit(ctx.decl)
    /**
     * InterfaceMemberDeclaration 的 InterfaceMemberEnum 标签：嵌套枚举转发壳。
     */
    override fun visitInterfaceMemberEnum(ctx: JasperParser.InterfaceMemberEnumContext): JasNode? = visit(ctx.decl)
    /**
     * InterfaceMemberDeclaration 的 InterfaceMemberInterface 标签：嵌套接口转发壳。
     */
    override fun visitInterfaceMemberInterface(ctx: JasperParser.InterfaceMemberInterfaceContext): JasNode? = visit(ctx.decl)
    /**
     * InterfaceMemberDeclaration 的 InterfaceMemberAnnotationType 标签：嵌套注解类型转发壳。
     */
    override fun visitInterfaceMemberAnnotationType(ctx: JasperParser.InterfaceMemberAnnotationTypeContext): JasNode? = visit(ctx.decl)
    /**
     * InterfaceMemberDeclaration 的 InterfaceMemberEmpty 标签（分号）：无产出，返回 null。
     */
    override fun visitInterfaceMemberEmpty(ctx: JasperParser.InterfaceMemberEmptyContext): JasNode? = null

    /**
     * ConstantDeclaration 规则：委托 buildVarStmt 构造常量变量语句（isConst=true）。
     */
    override fun visitConstantDeclaration(ctx: JasperParser.ConstantDeclarationContext): JasNode? {
        return buildVarStmt(ctx.tail, true, ctx)
    }

    /**
     * interfacePropertyDeclaration 规则：取属性头名字与可选类型，产出 modifiers 为空的 JasProperty。
     */
    override fun visitInterfacePropertyDeclaration(ctx: JasperParser.InterfacePropertyDeclarationContext): JasNode? {
        val header = ctx.header
        val name = header.bindingRef.name.text
        val type = if (header.bindingRef.typeRef != null) visit(header.bindingRef.typeRef) as? JasType else null
        return JasProperty(name, type, emptyList(), null, null, header.kind.text == "const").at(ctx)
    }

    /**
     * InterfaceMethodDeclarationFunctionStyle 规则：与类方法同构，但 body 恒为 null（无实现）。
     */
    override fun visitInterfaceMethodDeclarationFunctionStyle(ctx: JasperParser.InterfaceMethodDeclarationFunctionStyleContext): JasNode? {
        val mods = doExtractMods(ctx.modifier())
        val typeParams = if (ctx.tparams != null) doVisitTypeParams(ctx.tparams) else emptyList()
        val params = if (ctx.plist != null) doVisitFuncParams(ctx.plist) else emptyList()
        val returnType = if (ctx.ret != null) visit(ctx.ret) as? JasType else null
        val whereConstraints = if (ctx.where != null) doVisitWhere(ctx.where) else emptyList()
        return JasFunction(ctx.name.text, params, returnType, null, mods, typeParams, whereConstraints, throwsTypes = doVisitThrows(ctx.thr)).at(ctx)
    }

    // 鈹€鈹€ Annotation type 鈹€鈹€

    /**
     * annotationTypeDeclaration 规则：收集注解成员，产出 JasAnnotationType。
     */
    override fun visitAnnotationTypeDeclaration(ctx: JasperParser.AnnotationTypeDeclarationContext): JasNode? {
        val mods = doExtractMods(ctx.modifier())
        val members = mutableListOf<JasAnnotationMember>()
        for (amd in ctx.body.annotationTypeMemberDeclaration()) {
            visit(amd)?.let { if (it is JasAnnotationMember) members.add(it) }
        }
        return JasAnnotationType(ctx.name.text, mods, members).at(ctx)
    }

    /**
     * annotationTypeElementDeclaration 规则：取元素名 / 类型 / 可选默认值，产出 JasAnnotationMember。
     */
    override fun visitAnnotationTypeElementDeclaration(ctx: JasperParser.AnnotationTypeElementDeclarationContext): JasNode? {
        val mods = doExtractMods(ctx.modifier())
        val type = visit(ctx.ty) as? JasType
        val defaultVal = if (ctx.defaultValue() != null) visit(ctx.defaultValue().elementValue()) as? JasExpression else null
        return JasAnnotationMember(ctx.name.text, type, defaultVal).at(ctx)
    }

    /**
     * TypeOrPrimitive 的 Primitive 标签：转发到 primitiveType 解析。
     */
    override fun visitTypeOrPrimitivePrimitive(ctx: JasperParser.TypeOrPrimitivePrimitiveContext): JasNode? = visit(ctx.primitiveType())
    /**
     * TypeOrPrimitive 的 Type 标签：转发到 typeExpr 解析。
     */
    override fun visitTypeOrPrimitiveType(ctx: JasperParser.TypeOrPrimitiveTypeContext): JasNode? = visit(ctx.typeExpr())

    // 鈹€鈹€ Type expressions 鈹€鈹€

    /**
     * TypeExpression 规则（typeAtom typeSuffix* typeQualifier?）：由原子加后缀拼装 JasType。
     *
     * 边界：TypeSuffixDot 仅在当前结果是 JasNamedType 时追加限定名；数组后缀包 JasArrayType；
     * 末尾 typeQualifier（'?'）包 JasNullableType。非节点原子回退为 JasAnyType。
     */
    override fun visitTypeExpression(ctx: JasperParser.TypeExpressionContext): JasNode? {
        val atomCtx = ctx.typeAtom()
        val type = if (atomCtx is JasperParser.TypeAtomNodeContext) {
            doVisitTypeAtomNode(atomCtx)
        } else JasAnyType
        val suffixes = ctx.typeSuffix()
        val qualifier = ctx.typeQualifier()
        var result = type
        // 后缀折叠：'.' 追加限定名（仅命名类型生效），'[]' 包一层数组类型。
        for (s in suffixes) {
            when (s) {
                is JasperParser.TypeSuffixDotContext -> {
                    if (result is JasNamedType) {
                        result = JasNamedType(result.name + "." + s.Identifier().text, result.typeArguments)
                    }
                }
                is JasperParser.TypeSuffixArrayContext -> result = JasArrayType(result)
            }
        }
        // 末尾 '?' 把整体包成可空类型。
        if (qualifier != null) {
            result = JasNullableType(result)
        }
        return result.at(ctx)
    }

    /**
     * 私有辅助：解析 typeAtomNode 的原子基础类型，若带前缀星号（TypePrefixAsterisk）则包 JasPointerType。
     */
    private fun doVisitTypeAtomNode(ctx: JasperParser.TypeAtomNodeContext): JasType {
        val base = doVisitAtomBase(ctx.typeAtomBase())
        val prefix = ctx.typePrefix()
        if (prefix is JasperParser.TypePrefixAsteriskContext) return JasPointerType(base)
        return base
    }

    /**
     * 私有辅助：visit typeAtomBase，若结果不是 JasType（未覆盖的标签）则以原始文本兜底为 JasNamedType。
     */
    private fun doVisitAtomBase(ctx: JasperParser.TypeAtomBaseContext): JasType {
        return when (val r = visit(ctx)) {
            is JasType -> r
            else -> JasNamedType(ctx.text)
        }
    }

    /**
     * TypeAtomNode 的 Primitive 标签：转发到 primitiveType。
     */
    override fun visitTypeAtomNodePrimitive(ctx: JasperParser.TypeAtomNodePrimitiveContext): JasNode? = visit(ctx.primitiveType())
    /**
     * TypeAtomNode 的 String 标签：产出单例 JasStringType。
     */
    override fun visitTypeAtomNodeString(ctx: JasperParser.TypeAtomNodeStringContext): JasNode? = JasStringType
    /**
     * TypeAtomNode 的 Bytes 标签：产出单例 JasBytesType。
     */
    override fun visitTypeAtomNodeBytes(ctx: JasperParser.TypeAtomNodeBytesContext): JasNode? = JasBytesType
    /**
     * TypeAtomNode 的 Regex 标签：产出单例 JasRegexType。
     */
    override fun visitTypeAtomNodeRegex(ctx: JasperParser.TypeAtomNodeRegexContext): JasNode? = JasRegexType
    /**
     * TypeAtomNode 的 Any 标签：产出单例 JasAnyType。
     */
    override fun visitTypeAtomNodeAny(ctx: JasperParser.TypeAtomNodeAnyContext): JasNode? = JasAnyType
    /**
     * TypeAtomNode 的 Void/Unit 标签：产出单例 JasUnitType。
     */
    override fun visitTypeAtomNodeUnit(ctx: JasperParser.TypeAtomNodeUnitContext): JasNode? = JasUnitType

    /**
     * TypeAtomNode 的 Tuple 标签：把各成员类型包成 JasTupleType。
     */
    override fun visitTypeAtomNodeTuple(ctx: JasperParser.TypeAtomNodeTupleContext): JasNode? {
        return JasTupleType(ctx.typeExpr().mapNotNull { visit(it) as? JasType }).at(ctx)
    }

    /**
     * TypeAtomNode 的 Group 标签（括号分组）：直接转发内层 typeExpr。
     */
    override fun visitTypeAtomNodeGroup(ctx: JasperParser.TypeAtomNodeGroupContext): JasNode? = visit(ctx.typeExpr())

    /**
     * TypeAtomNode 的 Identifier 标签：构造 JasNamedType(名, 可选类型实参)。
     */
    override fun visitTypeAtomNodeIdentifier(ctx: JasperParser.TypeAtomNodeIdentifierContext): JasNode? {
        val args = if (ctx.typeArguments() != null) {
            ctx.typeArguments().typeArgument().mapNotNull { visit(it) as? JasType }
        } else emptyList()
        return JasNamedType(ctx.Identifier().text, args).at(ctx)
    }

    /**
     * TypeAtomNode 的 Dict 标签：构造 JasNamedType("dict", 可选类型实参)。
     */
    override fun visitTypeAtomNodeDict(ctx: JasperParser.TypeAtomNodeDictContext): JasNode? {
        val args = if (ctx.typeArguments() != null) {
            ctx.typeArguments().typeArgument().mapNotNull { visit(it) as? JasType }
        } else emptyList()
        return JasNamedType("dict", args).at(ctx)
    }

    /**
     * `namedType : annotation* qualifiedName typeArguments?  #NamedTypeRef`
     *
     * 父类（extends）与接口（implements）位置使用受限类型 namedType。
     * 此方法必须存在：缺失时 visit() 会走 visitChildren 返回非 JasType，
     * 调用侧 as? JasType / mapNotNull 会**静默丢弃** superclass / interfaces，
     * 于是类照常编译，运行期 invokeinterface 才抛 NoSuchMethodError。
     */
    override fun visitNamedTypeRef(ctx: JasperParser.NamedTypeRefContext): JasNode? {
        // 限定名逐段以 '.' 拼接成 JasNamedType 的名字。
        val name = ctx.qualifiedName().Identifier().joinToString(".") { it.text }
        val args = if (ctx.typeArguments() != null) {
            ctx.typeArguments().typeArgument().mapNotNull { visit(it) as? JasType }
        } else emptyList()
        return JasNamedType(name, args).at(ctx)
    }

    /**
     * primitiveType 规则：以原文构造 JasPrimitiveType 并回填位置。
     */
    override fun visitPrimitiveType(ctx: JasperParser.PrimitiveTypeContext): JasNode? = JasPrimitiveType(ctx.getText()).at(ctx)
    /**
     * TypeSuffix 的 Array 标签（'[' ']'）：占位产出 JasArrayType(JasAnyType)，真实内层由 TypeExpression 包裹。
     */
    override fun visitTypeSuffixArray(ctx: JasperParser.TypeSuffixArrayContext): JasNode? = JasArrayType(JasAnyType).at(ctx)
    /**
     * TypeQualifier 的 Nullable 标签（'?'）：占位产出 JasNullableType(JasAnyType)。
     */
    override fun visitTypeQualNullable(ctx: JasperParser.TypeQualNullableContext): JasNode? = JasNullableType(JasAnyType).at(ctx)
    /**
     * typePrefix 的 Asterisk 标签（指针 '*'）：占位产出 JasPointerType(JasAnyType)。
     */
    override fun visitTypePrefixAsterisk(ctx: JasperParser.TypePrefixAsteriskContext): JasNode? = JasPointerType(JasAnyType).at(ctx)
    /**
     * typeArgument 的 Type 标签：转发内层 typeExpr。
     */
    override fun visitTypeArgumentType(ctx: JasperParser.TypeArgumentTypeContext): JasNode? = visit(ctx.typeExpr())
    /**
     * typeArgument 的 Wildcard 标签：产出无上界、extends 方向的 JasWildcardType。
     */
    override fun visitTypeArgumentWildcard(ctx: JasperParser.TypeArgumentWildcardContext): JasNode? = JasWildcardType(null, true).at(ctx)

    /**
     * wildcard 规则（Any (Extends|Super typeExpr)?）：带可选上/下界，extends 由关键字判定。
     */
    override fun visitTypeWildcard(ctx: JasperParser.TypeWildcardContext): JasNode? {
        val bound = if (ctx.typeExpr() != null) visit(ctx.typeExpr()) as? JasType else null
        return JasWildcardType(bound, ctx.Extends() != null).at(ctx)
    }

    /**
     * typeParam 规则：取类型参数名与其可选上界，产出 JasTypeParameter。
     *
     * 边界：仅 TypeBoundAlternative 形态的上界被采纳，其余形态上界为 null。
     */
    override fun visitTypeParam(ctx: JasperParser.TypeParamContext): JasNode? {
        val bound = if (ctx.typeBound() != null) {
            val alt = ctx.typeBound()
            if (alt is JasperParser.TypeBoundAlternativeContext) {
                visit(alt.typeExpr()) as? JasType
            } else null
        } else null
        return JasTypeParameter(ctx.Identifier().text, bound).at(ctx)
    }

    /**
     * typeBound 的 Alternative 标签（Extends|Super typeExpr）：转发内层 typeExpr。
     */
    override fun visitTypeBoundAlternative(ctx: JasperParser.TypeBoundAlternativeContext): JasNode? {
        return visit(ctx.typeExpr())
    }

    // 鈹€鈹€ Statements 鈹€鈹€

    /**
     * block 规则：把 blockStatement 逐条 visit 并过滤成 JasStatement，包成 JasBlock。
     */
    override fun visitBlock(ctx: JasperParser.BlockContext): JasNode? {
        return JasBlock(ctx.blockStatement().mapNotNull { visit(it) as? JasStatement }).at(ctx)
    }

    /**
     * statement 的 Block 标签：转发到 block 规则。
     */
    override fun visitStatementBlock(ctx: JasperParser.StatementBlockContext): JasNode? = visit(ctx.block())
    /**
     * statement 的 Empty 标签（单分号）：无语句，返回 null。
     */
    override fun visitStatementEmpty(ctx: JasperParser.StatementEmptyContext): JasNode? = null

    /**
     * statement 的 ExpressionStatement 标签：把表达式包成 JasExpressionStatement。
     *
     * 边界：表达式解析失败（非 JasExpression）时返回 null，静默丢弃该语句。
     */
    override fun visitStatementExpressionStatement(ctx: JasperParser.StatementExpressionStatementContext): JasNode? {
        val expr = visit(ctx.expression()) as? JasExpression ?: return null
        return JasExpressionStatement(expr).at(ctx)
    }

    /**
     * statement 的 Increment 标签（postfixIncrement ';'）：包成 JasStatementIncrement。
     */
    override fun visitStatementIncrement(ctx: JasperParser.StatementIncrementContext): JasNode? {
        val inc = visitPostfixIncrement(ctx.postfixIncrement() as JasperParser.PostfixIncDecContext)
        return JasStatementIncrement(inc).at(ctx)
    }

    /**
     * statement 的 If 标签：转发到 ifStatement 规则。
     */
    override fun visitStatementIf(ctx: JasperParser.StatementIfContext): JasNode? = visit(ctx.ifStatement())

    /**
     * ifStatement 规则：解析条件、then 块与可选 else 块，产出 JasIf。
     *
     * 边界：else 可为 else 块或 else-if（后者包成只含一条语句的 JasBlock）。
     */
    override fun visitIfStatement(ctx: JasperParser.IfStatementContext): JasNode? {
        val condition = visit(ctx.expression()) as JasExpression
        val thenBody = visit(ctx.block(0)) as JasBlock
        val elseBody = if (ctx.block().size > 1) {
            visit(ctx.block(1)) as? JasBlock
        } else if (ctx.ifStatement() != null) {
            val inner = visit(ctx.ifStatement()) as? JasStatement
            if (inner != null) JasBlock(listOf(inner)).at(ctx) else null
        } else null
        return JasIf(condition, thenBody, elseBody).at(ctx)
    }

    /**
     * statement 的 While 标签：转发到 whileStatement。
     */
    override fun visitStatementWhile(ctx: JasperParser.StatementWhileContext): JasNode? = visit(ctx.whileStatement())

    /**
     * whileStatement 规则：条件 + 循环体，产出 JasWhile。
     */
    override fun visitWhileStatement(ctx: JasperParser.WhileStatementContext): JasNode? {
        return JasWhile(
            visit(ctx.expression()) as JasExpression,
            visit(ctx.block()) as JasBlock,
            buildLoopSuffix(ctx.loopSuffix())
        ).at(ctx)
    }

    /**
     * statement 的 Do 标签：转发到 doStatement。
     */
    override fun visitStatementDo(ctx: JasperParser.StatementDoContext): JasNode? = visit(ctx.doStatement())

    /**
     * doStatement 规则：循环体 + 条件，产出 JasDoWhile。
     */
    override fun visitDoStatement(ctx: JasperParser.DoStatementContext): JasNode? {
        return JasDoWhile(visit(ctx.block()) as JasBlock, visit(ctx.expression()) as JasExpression).at(ctx)
    }

    /**
     * statement 的 For 标签：转发到 forStatement。
     */
    override fun visitStatementFor(ctx: JasperParser.StatementForContext): JasNode? = visit(ctx.forStatement())

    /**
     * forStatement 规则：分派 for-in 与经典三段式两种形态，并解析可选的 then/else 循环体。
     *
     * 边界：既非 forIn 也非 forClassic 时退化为无条件、无初始化的 JasForStatement。
     */
    override fun visitForStatement(ctx: JasperParser.ForStatementContext): JasNode? {
        val control = ctx.forControl()
        val body = visit(ctx.block()) as JasBlock
        val suffix = buildLoopSuffix(ctx.loopSuffix())

        if (control.forInControl() != null) {
            val forInCtx = control.forInControl()
            val varName = doExtractForInVar(forInCtx.forInBinding())
            val iterable = visit(forInCtx.expression()) as JasExpression
            return JasForInStatement(varName, iterable, body, null, null, suffix).at(ctx)
        }

        if (control.forClassicControl() != null) {
            val classicCtx = control.forClassicControl()
            val init = doBuildForInit(classicCtx.`init`, ctx)
            val condition = if (classicCtx.cond != null) visit(classicCtx.cond) as? JasExpression else null
            val update = if (classicCtx.update != null) {
                val u = classicCtx.update
                when (u) {
                    is JasperParser.ForClassicUpdateExprsContext -> visit(u.expression) as? JasExpression
                    is JasperParser.ForClassicUpdateIncrementContext -> visitPostfixIncrement(u.postfixIncrement() as JasperParser.PostfixIncDecContext)
                    else -> null
                }
            } else null
            return JasForStatement(init, condition, update, body, suffix).at(ctx)
        }
        return JasForStatement(null, null, null, body, suffix).at(ctx)
    }

    /**
     * 把文法里的循环后置块转成 [LoopSuffix]。
     *
     * 三个软关键字子句任选子集；重复子句按第一次出现保留，实际执行由翻译层的
     * entered/broke 标志互斥选择。没有子句时返回 null。
     */
    private fun buildLoopSuffix(ctx: JasperParser.LoopSuffixContext?): LoopSuffix? {
        if (ctx == null) return null
        var onComplete: JasBlock? = null
        var onBreak: JasBlock? = null
        var onZeroRuns: JasBlock? = null
        for (clause in ctx.loopClause()) {
            val block = visit(clause.block()) as? JasBlock ?: continue
            when {
                clause.softOnComplete() != null -> if (onComplete == null) onComplete = block
                clause.softOnBreak() != null -> if (onBreak == null) onBreak = block
                clause.softOnZeroRuns() != null -> if (onZeroRuns == null) onZeroRuns = block
            }
        }
        return if (onComplete == null && onBreak == null && onZeroRuns == null) {
            null
        } else {
            LoopSuffix(onComplete, onBreak, onZeroRuns)
        }
    }

    /**
     * 私有辅助：从 for-in 绑定里抽取迭代变量名；声明式与无类型式两种绑定分别取首个原子。
     *
     * 边界：无法抽取时返回占位名 "_"。
     */
    private fun doExtractForInVar(ctx: JasperParser.ForInBindingContext): String {
        if (ctx.forInDeclBinding() != null) {
            val decl = ctx.forInDeclBinding()
            if (decl.bindingPattern().isNotEmpty()) {
                val atom = decl.bindingPattern(0).bindingAtom()
                if (atom.binding() != null) return atom.binding().name.text
            }
        } else {
            val pats = ctx.bindingPatternNoType()
            if (pats.isNotEmpty()) {
                val atom = pats[0].bindingNoTypeAtom()
                if (atom.Identifier() != null) return atom.Identifier().text
            }
        }
        return "_"
    }

    /**
     * 私有辅助：把经典 for 的初始化段构造为 JasStatement。
     *
     * 前置条件：initCtx 可为 null（返回 null）。边界：声明式多绑定包成 JasBlock；表达式式仅在
     * 单表达式时返回 JasExpressionStatement，多表达式返回 null。位置取 parentCtx（整条 for）。
     */
    private fun doBuildForInit(initCtx: JasperParser.ForClassicInitContext?, parentCtx: ParserRuleContext): JasStatement? {
        if (initCtx == null) return null
        // 初始化段两种形态：声明式（Var/Const + 绑定）或表达式式。
        if (initCtx is JasperParser.ForClassicInitDeclContext) {
            val stmts = initCtx.variableBinding().map { vb ->
                val b = vb.core
                val name = b.name.text
                val type = if (b.typeRef != null) visit(b.typeRef) as? JasType else null
                val expr = if (vb.`init` != null) visit(vb.`init`) as? JasExpression else null
                JasVariableStatement(name, type, expr, initCtx.kind.text == "const").at(parentCtx)
            }
            return if (stmts.size == 1) stmts[0] else JasBlock(stmts).at(parentCtx)
        }
        if (initCtx is JasperParser.ForClassicInitExprsContext) {
            val exprs = initCtx.expression().mapNotNull { visit(it) as? JasExpression }
            // 多表达式初始化无法用单条 JasExpressionStatement 表达，返回 null（已知限制）。
            return if (exprs.size == 1) JasExpressionStatement(exprs[0]).at(parentCtx) else null
        }
        return null
    }

    /**
     * statement 的 Switch 标签：转发到 switchStatement。
     */
    override fun visitStatementSwitch(ctx: JasperParser.StatementSwitchContext): JasNode? = visit(ctx.switchStatement())

    /**
     * switchStatement 规则：解析选择表达式、各 case 与 default，产出 JasSwitch。
     *
     * 已知限制：default 分支假定存在（visit(ctx.defaultClause()) 非空），且每个 case 仅取单个值表达式。
     */
    override fun visitSwitchStatement(ctx: JasperParser.SwitchStatementContext): JasNode? {
        val expr = visit(ctx.expression()) as JasExpression
        val cases = mutableListOf<JasCaseClause>()
        for (cc in ctx.switchCaseClause()) {
            cases.add(JasCaseClause(listOfNotNull(visit(cc.expression()) as? JasExpression), visit(cc.block()) as JasBlock).at(ctx))
        }
        // 已知限制：假定存在 default 子句；每个 case 仅取单个值表达式。
        cases.add(JasCaseClause(emptyList(), visit(ctx.defaultClause().block()) as JasBlock).at(ctx))
        return JasSwitch(expr, cases).at(ctx)
    }

    /**
     * statement 的 Return 标签：转发到 returnStatement。
     */
    override fun visitStatementReturn(ctx: JasperParser.StatementReturnContext): JasNode? = visit(ctx.returnStatement())

    /**
     * returnStatement 规则：取表达式的首个结果作为返回值，产出 JasReturn。
     */
    override fun visitReturnStatement(ctx: JasperParser.ReturnStatementContext): JasNode? {
        val value = if (ctx.exprList() != null) {
            ctx.exprList().expression().mapNotNull { visit(it) as? JasExpression }.firstOrNull()
        } else null
        return JasReturn(value).at(ctx)
    }

    /**
     * statement 的 Break 标签：转发到 breakStatement。
     */
    override fun visitStatementBreak(ctx: JasperParser.StatementBreakContext): JasNode? = visit(ctx.breakStatement())
    /**
     * breakStatement 规则：带可选标签，产出 JasBreakStatement。
     */
    override fun visitBreakStatement(ctx: JasperParser.BreakStatementContext): JasNode? = JasBreakStatement(ctx.Identifier()?.text).at(ctx)
    /**
     * statement 的 Continue 标签：转发到 continueStatement。
     */
    override fun visitStatementContinue(ctx: JasperParser.StatementContinueContext): JasNode? = visit(ctx.continueStatement())
    /**
     * continueStatement 规则：带可选标签，产出 JasContinueStatement。
     */
    override fun visitContinueStatement(ctx: JasperParser.ContinueStatementContext): JasNode? = JasContinueStatement(ctx.Identifier()?.text).at(ctx)
    /**
     * statement 的 Throw 标签：转发到 throwStatement。
     */
    override fun visitStatementThrow(ctx: JasperParser.StatementThrowContext): JasNode? = visit(ctx.throwStatement())

    /**
     * throwStatement 规则：抛出表达式，产出 JasThrow。
     */
    override fun visitThrowStatement(ctx: JasperParser.ThrowStatementContext): JasNode? {
        return JasThrow(visit(ctx.expression()) as JasExpression).at(ctx)
    }

    /**
     * statement 的 Try 标签：转发到 tryStatement。
     */
    override fun visitStatementTry(ctx: JasperParser.StatementTryContext): JasNode? = visit(ctx.tryStatement())

    /**
     * tryStatement 的 TryCatches 标签（try 块 + 若干 catch，无 finally）：产出 finallyBody 为 null 的 JasTry。
     */
    override fun visitTryCatches(ctx: JasperParser.TryCatchesContext): JasNode? {
        // try-catch（无 finally）：finallyBody 置 null。
        return JasTry(visit(ctx.block()) as JasBlock, ctx.catchClause().mapNotNull { visit(it) as? JasCatchClause }, null).at(ctx)
    }

    /**
     * tryStatement 的 TryFinally 标签（try 块 + 可选 catch + finally）：产出带 finallyBody 的 JasTry。
     */
    override fun visitTryFinally(ctx: JasperParser.TryFinallyContext): JasNode? {
        val body = visit(ctx.block()) as JasBlock
        val catches = ctx.catchClause().mapNotNull { visit(it) as? JasCatchClause }
        // try-finally 形态：finally 块挂在 JasTry.finallyBody 上。
        val finallyBody = visit(ctx.finallyClause().block()) as? JasBlock
        return JasTry(body, catches, finallyBody).at(ctx)
    }

    /**
     * catchClause 规则：由绑定构造 JasParameter，连同 catch 块产出 JasCatchClause。
     */
    override fun visitCatchClause(ctx: JasperParser.CatchClauseContext): JasNode? {
        val b = ctx.bind
        val param = JasParameter(b.name.text, if (b.typeRef != null) visit(b.typeRef) as? JasType else null).at(ctx)
        return JasCatchClause(param, visit(ctx.body) as JasBlock).at(ctx)
    }

    /**
     * finallyClause 规则：转发到 block 规则。
     */
    override fun visitFinallyClause(ctx: JasperParser.FinallyClauseContext): JasNode? = visit(ctx.block())
    /**
     * statement 的 Defer 标签：转发到 deferStatement。
     */
    override fun visitStatementDefer(ctx: JasperParser.StatementDeferContext): JasNode? = visit(ctx.deferStatement())

    /**
     * deferStatement 规则：把单条语句规整成 JasBlock 后包成 JasDefer。
     */
    override fun visitDeferStatement(ctx: JasperParser.DeferStatementContext): JasNode? {
        val stmt = visit(ctx.statement())
        val block = if (stmt is JasBlock) stmt else JasBlock(listOfNotNull(stmt as? JasStatement)).at(ctx)
        return JasDefer(block).at(ctx)
    }

    /**
     * statement 的 Lock 标签：转发到 lockStatement。
     */
    override fun visitStatementLock(ctx: JasperParser.StatementLockContext): JasNode? = visit(ctx.lockStatement())

    /**
     * lockStatement 规则：锁表达式 + 块，产出 JasLock。
     */
    override fun visitLockStatement(ctx: JasperParser.LockStatementContext): JasNode? {
        return JasLock(visit(ctx.expression()) as JasExpression, visit(ctx.block()) as JasBlock).at(ctx)
    }

    /**
     * statement 的 Assert 标签：转发到 assertStatement。
     */
    override fun visitStatementAssert(ctx: JasperParser.StatementAssertContext): JasNode? = visit(ctx.assertStatement())

    /**
     * assertStatement 的 Simple 标签（无消息）：产出 message 为 null 的 JasAssert。
     */
    override fun visitAssertSimple(ctx: JasperParser.AssertSimpleContext): JasNode? {
        return JasAssert(visit(ctx.expression()) as JasExpression, null).at(ctx)
    }

    /**
     * assertStatement 的 WithMessage 标签（':' 消息）：产出带 message 的 JasAssert。
     */
    override fun visitAssertWithMessage(ctx: JasperParser.AssertWithMessageContext): JasNode? {
        return JasAssert(visit(ctx.expression(0)) as JasExpression, visit(ctx.expression(1)) as? JasExpression).at(ctx)
    }

    /**
     * statement 的 Yield 标签：转发到 yieldStatement。
     */
    override fun visitStatementYield(ctx: JasperParser.StatementYieldContext): JasNode? = visit(ctx.yieldStatement())

    /**
     * yieldStatement 规则：产出 JasYield（携带产出表达式）。
     */
    override fun visitYieldStatement(ctx: JasperParser.YieldStatementContext): JasNode? {
        return JasYield(visit(ctx.expression()) as JasExpression).at(ctx)
    }

    /**
     * statement 的 Labeled 标签：转发到 labeledStatement。
     */
    override fun visitStatementLabeled(ctx: JasperParser.StatementLabeledContext): JasNode? = visit(ctx.labeledStatement())

    /**
     * labeledStatement 规则：标签 + 循环语句，产出 JasLabeledStatement。
     */
    override fun visitLabeledStatement(ctx: JasperParser.LabeledStatementContext): JasNode? {
        return JasLabeledStatement(ctx.label.text, visit(ctx.loopStatement()) as JasStatement).at(ctx)
    }

    /**
     * localVariableDeclarationStatement 规则：委托 buildVarStmt，isConst 由 kind 关键字判定。
     */
    override fun visitLocalVariableDeclarationStatement(ctx: JasperParser.LocalVariableDeclarationStatementContext): JasNode? {
        return buildVarStmt(ctx.tail, ctx.kind.text == "const", ctx)
    }

    /**
     * VariableBinding 的 IncDec 标签（binding '=' postfixIncrement）：产出增量初值的 JasVariableStatement。
     */
    override fun visitVariableBindingIncDec(ctx: JasperParser.VariableBindingIncDecContext): JasNode? {
        val b = ctx.core
        val init = visit(ctx.init) as? JasExpression
        return JasVariableStatement(
            b.name.text,
            if (b.typeRef != null) visit(b.typeRef) as? JasType else null,
            init,
            false
        ).at(ctx)
    }

    // 鈹€鈹€ Expressions 鈹€鈹€

    /**
     * expression 的 Lambda 标签：转发到 lambdaExpression。
     */
    override fun visitExprLambda(ctx: JasperParser.ExprLambdaContext): JasNode? = visit(ctx.lambdaExpression())

    /**
     * lambdaExpression 规则：解析参数列表与块体，产出 JasLambdaExpr。
     *
     * 边界：推断式参数（仅有标识符）产出无类型 JasParameter 且 inferredTypes=true；无块体时
     * 兜底为空 JasBlock。
     */
    override fun visitLambdaExpression(ctx: JasperParser.LambdaExpressionContext): JasNode? {
        val paramsCtx = ctx.lambdaParameters()
        val params = mutableListOf<JasParameter>()
        var inferred = false
        when (paramsCtx) {
            is JasperParser.LambdaParamsInferredListContext -> {
                for (id in paramsCtx.Identifier()) params.add(JasParameter(id.text, null).at(ctx)); inferred = true
            }
            is JasperParser.LambdaParamsTypedListContext -> {
                if (paramsCtx.plist != null) params.addAll(doVisitFuncParams(paramsCtx.plist))
            }
        }
        val body = visit(ctx.block()) as? JasBlock ?: JasBlock(emptyList()).at(ctx)
        return JasLambdaExpr(params, body, inferred).at(ctx)
    }

    /**
     * expression 的 Assignment 标签：转发到 assignmentExpression。
     */
    override fun visitExprAssignment(ctx: JasperParser.ExprAssignmentContext): JasNode? = visit(ctx.assignmentExpression())

    /**
     * assignmentExpression 的 Assignment 标签：分派增量赋值与普通赋值，产出 JasAssignmentIncrement / JasAssignment。
     */
    override fun visitAssignExprAssignment(ctx: JasperParser.AssignExprAssignmentContext): JasNode? {
        val a = ctx.assignment()
        if (a is JasperParser.AssignmentExprIncrementContext) {
            val target = visitAssignmentTarget(a.target)
            val inc = visitPostfixIncrement(a.inc as JasperParser.PostfixIncDecContext)
            return JasAssignmentIncrement(target, inc).at(ctx)
        }
        if (a is JasperParser.AssignmentExprContext) {
            val target = visitAssignmentTarget(a.target)
            val rhs = visit(a.rhs) as JasExpression
            return JasAssignment(target, rhs, a.op.text).at(ctx)
        }
        return null
    }

    /**
     * 私有辅助：把赋值目标解析为 JasExpression。
     *
     * 边界：限定名 → 属性访问链；主链 → 逐个套用后缀；解引用 → JasUnaryOp("*")；兜底返回
     * 空名 JasIdentifier("") 而非 null。
     */
    private fun visitAssignmentTarget(ctx: JasperParser.AssignmentTargetContext): JasExpression {
        return when (ctx) {
            is JasperParser.AssignmentExprTargetQualifiedNameContext -> doQualifiedNameToExpr(ctx.qualifiedName())
            is JasperParser.AssignmentExprTargetPrimaryChainContext -> {
                var expr = visit(ctx.primaryAtom()) as? JasExpression
                for (suf in ctx.primarySuffix()) {
                    expr = if (expr != null) doApplySuffix(expr, suf) else null
                }
                expr ?: JasIdentifier("")
            }
            is JasperParser.AssignmentExprTargetDerefContext -> {
                val target = visitAssignmentTarget(ctx.assignmentTarget())
                JasUnaryOp("*", target).at(ctx)
            }
            else -> JasIdentifier("")
        }
    }

    /**
     * 私有辅助：由目标与 ++/-- 运算符构造 JasPostfixIncDec。
     */
    private fun visitPostfixIncrement(ctx: JasperParser.PostfixIncDecContext): JasPostfixIncDec {
        val target = visitAssignmentTarget(ctx.target)
        val op = ctx.op.text
        return JasPostfixIncDec(target, op).at(ctx)
    }

    /**
     * assignmentExpression 的 Conditional 标签：转发到 conditionalExpression。
     */
    override fun visitAssignExprConditional(ctx: JasperParser.AssignExprConditionalContext): JasNode? {
        return visit(ctx.conditionalExpression())
    }

    /**
     * conditionalExpression 规则：三元 '?:' 形态产出 JasTernaryExpr，否则退化为条件本身。
     */
    override fun visitCondExpr(ctx: JasperParser.CondExprContext): JasNode? {
        val condition = visit(ctx.nullFallbackExpression()) as JasExpression
        if (ctx.expression() != null && ctx.conditionalExpression() != null) {
            return JasTernaryExpr(condition, visit(ctx.expression()) as JasExpression, visit(ctx.conditionalExpression()) as JasExpression).at(ctx)
        }
        return condition
    }

    /**
     * nullFallbackExpression 规则：转发到 nullCoalesceExpression。
     */
    override fun visitNullFallbackExpr(ctx: JasperParser.NullFallbackExprContext): JasNode? {
        return visit(ctx.nullCoalesceExpression())
    }

    /**
     * nullCoalesceExpression 规则：把 a ?? b ?? c 左折叠成嵌套 JasNullCoalescing。
     */
    override fun visitNullCoalesceExpr(ctx: JasperParser.NullCoalesceExprContext): JasNode? {
        val exprs = ctx.binaryExpression()
        var result = visit(exprs[0]) as JasExpression
        for (i in 1 until exprs.size) {
            val right = visit(exprs[i]) as? JasExpression ?: break
            result = JasNullCoalescing(result, right)
        }
        return result
    }

    /**
     * binaryExpression 的 Unary 标签（无二元运算符）：转发到 unaryExpression。
     */
    override fun visitBinaryUnary(ctx: JasperParser.BinaryUnaryContext): JasNode? = visit(ctx.unaryExpression())

    /**
     * 二元运算标签的统一构造入口（内部工具，非 ANTLR 回调）。
     *
     * 做什么：把一条 `binaryExpression op binaryExpression` 的 ANTLR 上下文收敛成
     *         `JasBinaryOp(左, 运算符, 右)`。
     * 何时用：仅在下方 10 个二元标签访问器里调用；`#BinaryUnary` 不走这里。
     * 前置条件：`left` / `right` 必须是同一条二元分支上下文的左、右操作数
     *           （即调用方用 `ctx.binaryExpression(0)` / `ctx.binaryExpression(1)` 取出）。
     * 失败模式：`visit` 结果若不是 `JasExpression` 会抛类型转换异常——由文法保证左/右
     *           操作数都是表达式，正常不会发生。
     * 为什么这样设计：新文法把每个二元分支右侧由 `unaryExpression` 改为递归的
     *           `binaryExpression`，从而用直接左递归恢复真实优先级（`1 + 2 * 3` 解析为
     *           `+(1, *(2,3))`）。注意 ANTLR 只为「带标签的子上下文」生成
     *           `binaryExpression(int)`，基类 `BinaryExpressionContext` 上没有该访问器，
     *           所以这里接收已取出的左右子树，而不是整个 ctx。把这段重复逻辑抽到一个
     *           私有函数（变化点隔离在运算符文本上），避免 10 处重复且口径不一致。
     */
    private fun binaryOp(left: ParseTree, op: String, right: ParseTree): JasBinaryOp {
        return JasBinaryOp(visit(left) as JasExpression, op, visit(right) as JasExpression)
    }

    /**
     * binaryExpression 的乘法类标签（* / %）：产出 JasBinaryOp，运算符取 op 原文。
     */
    override fun visitBinaryMultiplication(ctx: JasperParser.BinaryMultiplicationContext): JasNode? =
        binaryOp(ctx.binaryExpression(0), ctx.op.text, ctx.binaryExpression(1)).at(ctx)

    /**
     * binaryExpression 的加法类标签（+ / -）：产出 JasBinaryOp，运算符取 op 原文。
     */
    override fun visitBinaryAddition(ctx: JasperParser.BinaryAdditionContext): JasNode? =
        binaryOp(ctx.binaryExpression(0), ctx.op.text, ctx.binaryExpression(1)).at(ctx)

    /**
     * binaryExpression 的移位标签（<< >> >>>）：产出 JasBinaryOp，运算符取 op 原文。
     */
    override fun visitBinaryShift(ctx: JasperParser.BinaryShiftContext): JasNode? =
        binaryOp(ctx.binaryExpression(0), ctx.op.text, ctx.binaryExpression(1)).at(ctx)

    /**
     * binaryExpression 的关系标签（< > <= >=）：产出 JasBinaryOp，运算符取 op 原文。
     */
    override fun visitBinaryRelational(ctx: JasperParser.BinaryRelationalContext): JasNode? =
        binaryOp(ctx.binaryExpression(0), ctx.op.text, ctx.binaryExpression(1)).at(ctx)

    /**
     * binaryExpression 的相等标签（== !=）：产出 JasBinaryOp，运算符取 op 原文。
     */
    override fun visitBinaryEquality(ctx: JasperParser.BinaryEqualityContext): JasNode? =
        binaryOp(ctx.binaryExpression(0), ctx.op.text, ctx.binaryExpression(1)).at(ctx)

    /**
     * binaryExpression 的逻辑与标签（&&）：产出运算符固定为 "&&" 的 JasBinaryOp。
     * 说明：文法该分支的 op 是 `(And | AND_AND)`，词法器不同拼写都归一为 "&&"。
     */
    override fun visitBinaryAnd(ctx: JasperParser.BinaryAndContext): JasNode? =
        binaryOp(ctx.binaryExpression(0), "&&", ctx.binaryExpression(1)).at(ctx)

    /**
     * binaryExpression 的逻辑或标签（||）：产出运算符固定为 "||" 的 JasBinaryOp。
     * 说明：文法该分支的 op 是 `(Or | OR_OR)`，词法器不同拼写都归一为 "||"。
     */
    override fun visitBinaryOr(ctx: JasperParser.BinaryOrContext): JasNode? =
        binaryOp(ctx.binaryExpression(0), "||", ctx.binaryExpression(1)).at(ctx)

    /**
     * binaryExpression 的按位与标签（&）：产出运算符为 "&" 的 JasBinaryOp。
     */
    override fun visitBinaryBitAnd(ctx: JasperParser.BinaryBitAndContext): JasNode? =
        binaryOp(ctx.binaryExpression(0), "&", ctx.binaryExpression(1)).at(ctx)

    /**
     * binaryExpression 的按位或标签（|）：产出运算符为 "|" 的 JasBinaryOp。
     * 说明：文法允许全角 `｜`（FULLWIDTH_PIPE），此处统一归一为半角 "|"。
     */
    override fun visitBinaryBitOr(ctx: JasperParser.BinaryBitOrContext): JasNode? =
        binaryOp(ctx.binaryExpression(0), "|", ctx.binaryExpression(1)).at(ctx)

    /**
     * binaryExpression 的按位异或标签（^）：产出运算符为 "^" 的 JasBinaryOp。
     */
    override fun visitBinaryBitExclusiveOr(ctx: JasperParser.BinaryBitExclusiveOrContext): JasNode? =
        binaryOp(ctx.binaryExpression(0), "^", ctx.binaryExpression(1)).at(ctx)

    /**
     * unaryExpression 的一元加标签：产出 JasUnaryOp("+")。
     */
    override fun visitUnaryPlus(ctx: JasperParser.UnaryPlusContext): JasNode? {
        return JasUnaryOp("+", visit(ctx.unaryExpression()) as JasExpression).at(ctx)
    }

    /**
     * unaryExpression 的一元减标签：产出 JasUnaryOp("-")。
     */
    override fun visitUnaryMinus(ctx: JasperParser.UnaryMinusContext): JasNode? {
        return JasUnaryOp("-", visit(ctx.unaryExpression()) as JasExpression).at(ctx)
    }

    /**
     * unaryExpression 的解引用标签（*）：产出 JasUnaryOp("*")。
     */
    override fun visitUnaryDeref(ctx: JasperParser.UnaryDerefContext): JasNode? {
        return JasUnaryOp("*", visit(ctx.unaryExpression()) as JasExpression).at(ctx)
    }

    /**
     * unaryExpression 的取址标签（&）：产出 JasUnaryOp("&")。
     */
    override fun visitUnaryAddressOf(ctx: JasperParser.UnaryAddressOfContext): JasNode? {
        return JasUnaryOp("&", visit(ctx.unaryExpression()) as JasExpression).at(ctx)
    }

    /**
     * unaryExpression 的逻辑非标签（! / Not）：产出 JasUnaryOp("!")。
     */
    override fun visitUnaryLogicalNot(ctx: JasperParser.UnaryLogicalNotContext): JasNode? {
        return JasUnaryOp("!", visit(ctx.unaryExpression()) as JasExpression).at(ctx)
    }

    /**
     * unaryExpression 的按位非标签（~）：产出 JasUnaryOp("~")。
     */
    override fun visitUnaryBitNot(ctx: JasperParser.UnaryBitNotContext): JasNode? {
        return JasUnaryOp("~", visit(ctx.unaryExpression()) as JasExpression).at(ctx)
    }

    /**
     * unaryExpression 的 Await 标签：转发到 awaitExpression。
     */
    override fun visitUnaryAwait(ctx: JasperParser.UnaryAwaitContext): JasNode? = visit(ctx.awaitExpression())

    /**
     * awaitExpression 规则：产出运算符为 "await" 的 JasUnaryOp。
     */
    override fun visitAwaitExpression(ctx: JasperParser.AwaitExpressionContext): JasNode? {
        return JasUnaryOp("await", visit(ctx.unaryExpression()) as JasExpression).at(ctx)
    }

    /**
     * unaryExpression 的 Cast 标签：转发到 castExpression。
     */
    override fun visitUnaryCast(ctx: JasperParser.UnaryCastContext): JasNode? = visit(ctx.castExpression())

    /**
     * castExpression 的 Primitive 标签（(primitiveType) expr）：产出 JasCastExpr(isSafe=false)。
     */
    override fun visitCastPrimitive(ctx: JasperParser.CastPrimitiveContext): JasNode? {
        return JasCastExpr(visit(ctx.unaryExpression()) as JasExpression, visit(ctx.primitiveType()) as JasPrimitiveType).at(ctx)
    }

    /**
     * castExpression 的 As 标签（expr as type）：产出 JasCastExpr(isSafe=false)。
     *
     * 边界：目标类型解析失败时返回 null。
     */
    override fun visitCastAs(ctx: JasperParser.CastAsContext): JasNode? {
        val type = visit(ctx.ty) as? JasType ?: return null
        return JasCastExpr(visit(ctx.expr) as JasExpression, type, false).at(ctx)
    }

    /**
     * unaryExpression 的 Primary 标签：转发到 primary。
     */
    override fun visitUnaryPrimary(ctx: JasperParser.UnaryPrimaryContext): JasNode? = visit(ctx.primary())

    // 鈹€鈹€ Primary expressions 鈹€鈹€

    /**
     * primary 的 Chain 标签：以 primaryAtom 为基，从左到右依次套用 primarySuffix，产出表达式链。
     */
    override fun visitPrimaryChain(ctx: JasperParser.PrimaryChainContext): JasNode? {
        var result = visit(ctx.primaryAtom()) as? JasExpression ?: return null
        // 链式折叠：以原子为基，从左到右依次套用每个 primarySuffix。
        for (suffix in ctx.primarySuffix()) {
            result = doApplySuffix(result, suffix)
        }
        return result
    }

    /**
     * primaryAtom 的 Literal 标签：转发到 literal 规则。
     */
    override fun visitPrimaryAtomLiteral(ctx: JasperParser.PrimaryAtomLiteralContext): JasNode? = visit(ctx.literal())
    /**
     * primaryAtom 的 Name 标签：把限定名折叠成标识符/属性访问链。
     */
    override fun visitPrimaryAtomName(ctx: JasperParser.PrimaryAtomNameContext): JasNode? = doQualifiedNameToExpr(ctx.qualifiedName())
    /**
     * primaryAtom 的 Tuple 标签：产出 JasTupleExpr。
     */
    override fun visitPrimaryAtomTuple(ctx: JasperParser.PrimaryAtomTupleContext): JasNode? {
        return JasTupleExpr(ctx.expression().mapNotNull { visit(it) as? JasExpression }).at(ctx)
    }
    /**
     * primaryAtom 的 Paren 标签：括号不产生节点，直接转发内层表达式。
     */
    override fun visitPrimaryAtomParen(ctx: JasperParser.PrimaryAtomParenContext): JasNode? = visit(ctx.expression())
    /**
     * primaryAtom 的 This 标签：产出 JasIdentifier("this")。
     */
    override fun visitPrimaryAtomThis(ctx: JasperParser.PrimaryAtomThisContext): JasNode? = JasIdentifier("this").at(ctx)
    /**
     * primaryAtom 的 Super 标签：产出 JasIdentifier("super")。
     */
    override fun visitPrimaryAtomSuper(ctx: JasperParser.PrimaryAtomSuperContext): JasNode? = JasIdentifier("super").at(ctx)

    /**
     * primaryAtom 的 TypeThis 标签（T.this）：产出限定名到属性 "this" 的 JasPropertyAccess。
     */
    override fun visitPrimaryAtomTypeThis(ctx: JasperParser.PrimaryAtomTypeThisContext): JasNode? {
        return JasPropertyAccess(JasIdentifier(ctx.qualifiedName().text).at(ctx), "this").at(ctx)
    }

    /**
     * primaryAtom 的 TypeSuper 标签（T.super）：产出限定名到属性 "super" 的 JasPropertyAccess。
     */
    override fun visitPrimaryAtomTypeSuper(ctx: JasperParser.PrimaryAtomTypeSuperContext): JasNode? {
        return JasPropertyAccess(JasIdentifier(ctx.qualifiedName().text).at(ctx), "super").at(ctx)
    }

    /**
     * primaryAtom 的 ClassLiteral 标签（T.class）：产出名为 "T.class" 的 JasIdentifier。
     */
    override fun visitPrimaryAtomClassLiteral(ctx: JasperParser.PrimaryAtomClassLiteralContext): JasNode? {
        return JasIdentifier(ctx.qualifiedName().text + ".class").at(ctx)
    }

    /**
     * primaryAtom 的 New 标签：转发到 classInstanceCreationExpression。
     */
    override fun visitPrimaryAtomNew(ctx: JasperParser.PrimaryAtomNewContext): JasNode? {
        return visit(ctx.classInstanceCreationExpression())
    }

    /**
     * classInstanceCreationExpression 的 Unqualified 标签（new T(args)）：产出 JasNew。
     *
     * 边界：带钻石（diamond，无显式类型实参）时类型实参为空列表。
     */
    override fun visitNewUnqualified(ctx: JasperParser.NewUnqualifiedContext): JasNode? {
        val args = if (ctx.arguments() != null) doVisitExprList(ctx.arguments().exprList()) else emptyList()
        val typeArgs = if (ctx.typeArgumentsOrDiamond() != null) {
            if (ctx.typeArguments() != null) {
                ctx.typeArguments().typeArgument().mapNotNull { visit(it) as? JasType }
            } else {
                emptyList()
            }
        } else emptyList()
        return JasNew(JasNamedType(ctx.Identifier().joinToString(".") { it.text }, typeArgs), args).at(ctx)
    }

    /**
     * primaryAtom 的 ArrayCreation 标签：转发到 arrayCreationExpression。
     */
    override fun visitPrimaryAtomArrayCreation(ctx: JasperParser.PrimaryAtomArrayCreationContext): JasNode? {
        return visit(ctx.arrayCreationExpression())
    }

    /**
     * arrayCreationExpression 的 Primitive 维度标签（new int[n]...）：产出 JasArrayCreation（init=null）。
     *
     * 边界：维度表达式解析失败时以 JasIntLiteral(0) 占位。
     */
    override fun visitArrayNewPrimitiveDimExprs(ctx: JasperParser.ArrayNewPrimitiveDimExprsContext): JasNode? {
        val type = visit(ctx.primitiveType()) as JasPrimitiveType
        val dims = ctx.dimExprs().dimExpr().map { de -> visit(de.expression()) as? JasExpression ?: JasIntLiteral(0L, "0") }
        return JasArrayCreation(type, dims, null).at(ctx)
    }

    /**
     * arrayCreationExpression 的引用类型维度标签（new T[n]...）：产出 JasArrayCreation（init=null）。
     *
     * 边界：元素类型解析失败返回 null；维度表达式失败以 JasIntLiteral(0) 占位。
     */
    override fun visitArrayNewRefDimExprs(ctx: JasperParser.ArrayNewRefDimExprsContext): JasNode? {
        val type = visit(ctx.typeExpr()) as? JasType ?: return null
        val dims = ctx.dimExprs().dimExpr().map { de -> visit(de.expression()) as? JasExpression ?: JasIntLiteral(0L, "0") }
        return JasArrayCreation(type, dims, null).at(ctx)
    }

    /**
     * arrayCreationExpression 的原语初始化标签（new int[]{...}）：产出带 JasArrayInit 的 JasArrayCreation。
     */
    override fun visitArrayInitPrimitive(ctx: JasperParser.ArrayInitPrimitiveContext): JasNode? {
        val type = visit(ctx.primitiveType()) as JasPrimitiveType
        return JasArrayCreation(type, emptyList(), visit(ctx.arrayInitializer()) as? JasArrayInit).at(ctx)
    }

    /**
     * arrayCreationExpression 的引用初始化标签（new T[]{...}）：产出带 JasArrayInit 的 JasArrayCreation。
     */
    override fun visitArrayInitRef(ctx: JasperParser.ArrayInitRefContext): JasNode? {
        val type = visit(ctx.typeExpr()) as? JasType ?: return null
        return JasArrayCreation(type, emptyList(), visit(ctx.arrayInitializer()) as? JasArrayInit).at(ctx)
    }

    /**
     * primaryAtom 的 ArrayLiteral 标签（`[...]`）：构造无显式元素类型的 [JasArrayLiteral]。
     *
     * 为什么不能直接转发 `arrayInitializer`：该规则产出的是 [JasArrayInitValues]（一个
     *   [JasArrayInit]，不是 [JasExpression]），而 `variableBinding` 的初始化器要求
     *   `expression`；直接转发会让 `var a: T[] = [..]` 在 AST 构树时因非空强转失败而
     *   报“Failed to parse source”。这里显式包成表达式节点，元素类型留给 checker 推断。
     * 边界：嵌套数组初始化（`[[1],[2]]`）当前语言不支持，`arrayInitializer` 会过滤掉
     *   嵌套项；空字面量 `[]` 产出空 values，由 checker 兜底为 `any[]`。
     */
    override fun visitPrimaryAtomArrayLiteral(ctx: JasperParser.PrimaryAtomArrayLiteralContext): JasNode? {
        val init = visit(ctx.arrayInitializer()) as? JasArrayInitValues
        return JasArrayLiteral(init?.values ?: emptyList()).at(ctx)
    }

    /**
     * arrayInitializer 规则：把各 variableInitializer 的表达式收集为 JasArrayInitValues。
     *
     * 边界：嵌套数组初始化（VariableInitializerArray）不在本方法展开，被过滤掉。
     */
    override fun visitArrayInitializer(ctx: JasperParser.ArrayInitializerContext): JasNode? {
        val values = ctx.variableInitializer().mapNotNull { vi ->
            if (vi is JasperParser.VariableInitializerExpressionContext) visit(vi.expression()) as? JasExpression else null
        }
        return JasArrayInitValues(values).at(ctx)
    }

    // 鈹€鈹€ Primary suffix helper 鈹€鈹€

    /**
     * 私有辅助：对表达式基元套用一个 primarySuffix。
     *
     * 边界：调用 → JasCall；点访问 → JasPropertyAccess（带实参则包 JasCall）；安全点 → isSafe=true；
     * 下标 → JasArrayAccess；其余后缀（如非空断言）原样返回 base。
     */
    private fun doApplySuffix(base: JasExpression, suffix: JasperParser.PrimarySuffixContext): JasExpression {
        // 后缀分派：调用 / 属性访问 / 安全访问 / 下标；其余后缀（如非空断言）原样返回 base。
        return when (suffix) {
            is JasperParser.PrimarySuffixCallContext -> JasCall(base, doVisitExprList(suffix.arguments().exprList())).at(suffix)
            is JasperParser.PrimarySuffixDotContext -> {
                val name = suffix.Identifier().text
                if (suffix.arguments() != null) JasCall(JasPropertyAccess(base, name).at(suffix), doVisitExprList(suffix.arguments().exprList())).at(suffix)
                else JasPropertyAccess(base, name).at(suffix)
            }
            is JasperParser.PrimarySuffixSafeDotContext -> {
                val name = suffix.Identifier().text
                if (suffix.arguments() != null) JasCall(JasPropertyAccess(base, name, true).at(suffix), doVisitExprList(suffix.arguments().exprList())).at(suffix)
                else JasPropertyAccess(base, name, true).at(suffix)
            }
            is JasperParser.PrimarySuffixIndexContext -> JasArrayAccess(base, visit(suffix.expression()) as JasExpression).at(suffix)
            else -> base
        }
    }

    /**
     * primary 的 MethodReference 标签：转发到 methodReference。
     */
    override fun visitPrimaryMethodReference(ctx: JasperParser.PrimaryMethodReferenceContext): JasNode? {
        return visit(ctx.methodReference())
    }

    /**
     * methodReference 的 ExpressionName 标签（Q::m）：产出 JasMethodReference，并把限定名原文存入 packageName。
     */
    override fun visitMethodReferenceExpressionName(ctx: JasperParser.MethodReferenceExpressionNameContext): JasNode? {
        val qualName = ctx.qualifiedName()
        return JasMethodReference(doQualifiedNameToExpr(qualName), ctx.Identifier().text, packageName = qualName.text).at(ctx)
    }

    // 鈹€鈹€ Literals 鈹€鈹€

    /**
     * literal 的 Decimal 标签：去掉下划线与整数后缀 L/l 后转 Long，产出 JasIntLiteral（原始文本保留）。
     *
     * 边界：词法规则把类型后缀计入 token 文本，若不去后缀 `1000L` 会被当成非法并回退为 0；
     * 溢出/非法时数值仍回退为 0（与既有行为一致）。
     */
    override fun visitDecimalLiteral(ctx: JasperParser.DecimalLiteralContext): JasNode? {
        val cleaned = stripIntegerSuffix(ctx.text.replace("_", ""))
        return JasIntLiteral(cleaned.toLongOrNull() ?: 0L, ctx.text).at(ctx)
    }

    /**
     * literal 的 Hex 标签：剥离 0x/0X、下划线与整数后缀后按 16 进制转 Long，产出 JasIntLiteral。
     */
    override fun visitHexadecimalLiteral(ctx: JasperParser.HexadecimalLiteralContext): JasNode? {
        val cleaned = stripIntegerSuffix(
            ctx.text.replace("_", "").removePrefix("0x").removePrefix("0X"),
        )
        return JasIntLiteral(cleaned.toLongOrNull(16) ?: 0L, ctx.text).at(ctx)
    }

    /**
     * literal 的 Octal 标签：剥离 0o/0O、下划线与整数后缀后按 8 进制转 Long，产出 JasIntLiteral。
     */
    override fun visitOctalLiteral(ctx: JasperParser.OctalLiteralContext): JasNode? {
        val cleaned = stripIntegerSuffix(
            ctx.text.replace("_", "").removePrefix("0o").removePrefix("0O"),
        )
        return JasIntLiteral(cleaned.toLongOrNull(8) ?: 0L, ctx.text).at(ctx)
    }

    /**
     * literal 的 Binary 标签：剥离 0b/0B、下划线与整数后缀后按 2 进制转 Long，产出 JasIntLiteral。
     */
    override fun visitBinaryLiteral(ctx: JasperParser.BinaryLiteralContext): JasNode? {
        val cleaned = stripIntegerSuffix(
            ctx.text.replace("_", "").removePrefix("0b").removePrefix("0B"),
        )
        return JasIntLiteral(cleaned.toLongOrNull(2) ?: 0L, ctx.text).at(ctx)
    }

    /**
     * 去掉整数 token 末尾的 L/l 类型后缀。
     *
     * 为什么需要：Jasper 词法规则 `INTEGER_TYPE_SUFFIX` 把后缀并入 token 文本，
     * 而数值解析只接受纯数字，导致带后缀字面量被静默解析为 0。此处统一剥离，
     * 保持 AST 原始文本（第二个参数）不变以便错误定位。
     */
    private fun stripIntegerSuffix(text: String): String =
        if (text.isNotEmpty() && (text.last() == 'l' || text.last() == 'L')) text.dropLast(1) else text

    /**
     * literal 的 Float 标签：去除下划线后转 Double，产出 JasFloatLiteral。
     */
    override fun visitFloatLiteral(ctx: JasperParser.FloatLiteralContext): JasNode? {
        return JasFloatLiteral(ctx.text.replace("_", "").toDoubleOrNull() ?: 0.0, ctx.text).at(ctx)
    }

    /**
     * literal 的 HexFloat 标签：暂未解析数值，产出值为 0.0 的 JasFloatLiteral（保留原文）。
     */
    override fun visitHexFloatLiteral(ctx: JasperParser.HexFloatLiteralContext): JasNode? {
        return JasFloatLiteral(0.0, ctx.text).at(ctx)
    }

    /**
     * literal 的 Char 标签：去掉引号并反转义，单码点产出 JasCharLiteral(value)；否则值为 0 的占位。
     */
    override fun visitCharLiteral(ctx: JasperParser.CharLiteralContext): JasNode? {
        val text = ctx.text
        val unescaped = doUnescape(text.drop(1).dropLast(1))
        return if (unescaped.length == 1) {
            JasCharLiteral(unescaped.codePointAt(0), text)
        } else {
            JasCharLiteral(0, text)
        }.at(ctx)
    }

    /**
     * literal 的 String 标签：反转义后产出 JasStringLiteral。
     */
    override fun visitStringLiteral(ctx: JasperParser.StringLiteralContext): JasNode? {
        return JasStringLiteral(doUnescape(ctx.text)).at(ctx)
    }

    /**
     * literal 的 RawString 标签：仅去外层引号、不做反转义，产出 raw=true 的 JasStringLiteral。
     */
    override fun visitRawStringLiteral(ctx: JasperParser.RawStringLiteralContext): JasNode? {
        return JasStringLiteral(ctx.text.drop(1).dropLast(1), raw = true).at(ctx)
    }

    /**
     * literal 的 TripleString 标签：去掉三引号后产出 JasStringLiteral。
     */
    override fun visitTripleStringLiteral(ctx: JasperParser.TripleStringLiteralContext): JasNode? {
        return JasStringLiteral(ctx.text.drop(3).dropLast(3)).at(ctx)
    }

    /**
     * literal 的 FormatString 标签：交给 doParseFString 解析插值，产出 JasStringTemplate。
     */
    override fun visitFormatStringLiteral(ctx: JasperParser.FormatStringLiteralContext): JasNode? {
        return doParseFString(ctx.text)
    }

    /**
     * literal 的 ByteString 标签：以原文构造 JasStringLiteral（不反转义）。
     */
    override fun visitByteStringLiteral(ctx: JasperParser.ByteStringLiteralContext): JasNode? = JasStringLiteral(ctx.text).at(ctx)
    /**
     * literal 的 UnicodeString 标签：以原文构造 JasStringLiteral（不反转义）。
     */
    override fun visitUnicodeStringLiteral(ctx: JasperParser.UnicodeStringLiteralContext): JasNode? = JasStringLiteral(ctx.text).at(ctx)
    /**
     * literal 的 True 标签：产出 JasBoolLiteral(true)。
     */
    override fun visitTrueLiteral(ctx: JasperParser.TrueLiteralContext): JasNode? = JasBoolLiteral(true).at(ctx)
    /**
     * literal 的 False 标签：产出 JasBoolLiteral(false)。
     */
    override fun visitFalseLiteral(ctx: JasperParser.FalseLiteralContext): JasNode? = JasBoolLiteral(false).at(ctx)
    /**
     * literal 的 Null 标签：产出单例 JasNullLiteral。
     */
    override fun visitNullLiteral(ctx: JasperParser.NullLiteralContext): JasNode? = JasNullLiteral
    /**
     * literal 的 Dict 标签：转发到 dictLiteral。
     */
    override fun visitLiteralDict(ctx: JasperParser.LiteralDictContext): JasNode? = visit(ctx.dictLiteral())

    /**
     * dictLiteral 规则：逐条解析键值，产出 JasDictLiteral。
     *
     * 边界：键/值解析失败时分别以 JasStringLiteral("") / JasNullLiteral 兜底。
     */
    override fun visitDictLiteral(ctx: JasperParser.DictLiteralContext): JasNode? {
        val entries = ctx.dictEntry().map { de ->
            JasDictEntry(
                visit(de.key) as? JasExpression ?: JasStringLiteral(""),
                visit(de.value) as? JasExpression ?: JasNullLiteral
            ).at(ctx)
        }
        return JasDictLiteral(entries).at(ctx)
    }

    // 鈹€鈹€ Format string 鈹€鈹€

    // 鈹€鈹€ F-string expression parser 鈹€鈹€

    /**
     * f-string 插值表达式的词法记号集合（私有 sealed 类），供 FStringExprParser 消费。
     */
    private sealed class FStringToken {
        /**
         * 标识符记号。
         */
        data class Ident(val text: String) : FStringToken()
        /**
         * 数字字面量记号（保留原文，解析时再区分整数/浮点）。
         */
        data class Number(val text: String) : FStringToken()
        /**
         * 字符串字面量记号（含引号原文）。
         */
        data class StringLit(val text: String) : FStringToken()
        /**
         * 运算符记号（含多字符运算符）。
         */
        data class Op(val text: String) : FStringToken()
        /**
         * 左圆括号记号。
         */
        object LParen : FStringToken()
        /**
         * 右圆括号记号。
         */
        object RParen : FStringToken()
        /**
         * 左方括号记号。
         */
        object LBracket : FStringToken()
        /**
         * 右方括号记号。
         */
        object RBracket : FStringToken()
        /**
         * 成员访问点记号。
         */
        object Dot : FStringToken()
        /**
         * 实参分隔逗号记号。
         */
        object Comma : FStringToken()
        /**
         * 布尔字面量记号。
         */
        data class Bool(val value: Boolean) : FStringToken()
        /**
         * null 字面量记号。
         */
        object Null : FStringToken()
    }

    /**
     * 私有辅助：把 f-string 插值的文本切分成 FStringToken 序列（手写词法器）。
     *
     * 边界：跳过空白；识别括号/点/逗号/字符串/数字/标识符与多字符运算符；无法识别的字符直接跳过。
     */
    private fun tokenizeFStringExpr(text: String): List<FStringToken> {
        val tokens = mutableListOf<FStringToken>()
        var i = 0
        while (i < text.length) {
            val c = text[i]
            when {
                c.isWhitespace() -> i++
                c == '(' -> { tokens.add(FStringToken.LParen); i++ }
                c == ')' -> { tokens.add(FStringToken.RParen); i++ }
                c == '[' -> { tokens.add(FStringToken.LBracket); i++ }
                c == ']' -> { tokens.add(FStringToken.RBracket); i++ }
                c == '.' -> { tokens.add(FStringToken.Dot); i++ }
                c == ',' -> { tokens.add(FStringToken.Comma); i++ }
                c == '"' -> {
                    val sb = StringBuilder()
                    sb.append(c); i++
                    while (i < text.length && text[i] != '"') {
                        if (text[i] == '\\') { sb.append(text[i]); i++; if (i < text.length) sb.append(text[i]); i++ }
                        else { sb.append(text[i]); i++ }
                    }
                    if (i < text.length) { sb.append(text[i]); i++ }
                    tokens.add(FStringToken.StringLit(sb.toString()))
                }
                c == '+' || c == '-' || c == '*' || c == '/' || c == '%' || c == '~' -> {
                    tokens.add(FStringToken.Op(c.toString())); i++
                }
                c == '!' -> {
                    if (i + 1 < text.length && text[i + 1] == '=') { tokens.add(FStringToken.Op("!=")); i += 2 }
                    else { tokens.add(FStringToken.Op("!")); i++ }
                }
                c == '&' -> {
                    if (i + 1 < text.length && text[i + 1] == '&') { tokens.add(FStringToken.Op("&&")); i += 2 }
                    else { tokens.add(FStringToken.Op("&")); i++ }
                }
                c == '|' -> {
                    if (i + 1 < text.length && text[i + 1] == '|') { tokens.add(FStringToken.Op("||")); i += 2 }
                    else { tokens.add(FStringToken.Op("|")); i++ }
                }
                c == '=' -> {
                    if (i + 1 < text.length && text[i + 1] == '=') { tokens.add(FStringToken.Op("==")); i += 2 }
                    else i++
                }
                c == '<' -> {
                    when {
                        i + 1 < text.length && text[i + 1] == '<' -> { tokens.add(FStringToken.Op("<<")); i += 2 }
                        i + 1 < text.length && text[i + 1] == '=' -> { tokens.add(FStringToken.Op("<=")); i += 2 }
                        else -> { tokens.add(FStringToken.Op("<")); i++ }
                    }
                }
                c == '>' -> {
                    when {
                        i + 1 < text.length && text[i + 1] == '>' -> {
                            if (i + 2 < text.length && text[i + 2] == '>') { tokens.add(FStringToken.Op(">>>")); i += 3 }
                            else { tokens.add(FStringToken.Op(">>")); i += 2 }
                        }
                        i + 1 < text.length && text[i + 1] == '=' -> { tokens.add(FStringToken.Op(">=")); i += 2 }
                        else -> { tokens.add(FStringToken.Op(">")); i++ }
                    }
                }
                c.isDigit() -> {
                    val sb = StringBuilder()
                    while (i < text.length && text[i].isDigit()) { sb.append(text[i]); i++ }
                    if (i < text.length && text[i] == '.') {
                        sb.append(text[i]); i++
                        while (i < text.length && text[i].isDigit()) { sb.append(text[i]); i++ }
                    }
                    if (i < text.length && (text[i] == 'e' || text[i] == 'E')) {
                        sb.append(text[i]); i++
                        if (i < text.length && (text[i] == '+' || text[i] == '-')) { sb.append(text[i]); i++ }
                        while (i < text.length && text[i].isDigit()) { sb.append(text[i]); i++ }
                    }
                    tokens.add(FStringToken.Number(sb.toString()))
                }
                c.isLetter() || c == '_' -> {
                    val sb = StringBuilder()
                    while (i < text.length && (text[i].isLetterOrDigit() || text[i] == '_')) { sb.append(text[i]); i++ }
                    val word = sb.toString()
                    when (word) {
                        "true" -> tokens.add(FStringToken.Bool(true))
                        "false" -> tokens.add(FStringToken.Bool(false))
                        "null" -> tokens.add(FStringToken.Null)
                        else -> tokens.add(FStringToken.Ident(word))
                    }
                }
                else -> i++
            }
        }
        return tokens
    }

    /**
     * f-string 插值的私有递归下降解析器：把记号序列按运算符优先级还原为 JasExpression。
     */
    private class FStringExprParser(private val tokens: List<FStringToken>) {
        private var pos = 0

        /**
         * 私有辅助：查看当前记号而不前进；越界返回 null。
         */
        private fun peek(): FStringToken? = tokens.getOrNull(pos)
        /**
         * 私有辅助：取出当前记号并前移位置（不做越界检查，调用方需确保有记号）。
         */
        private fun consume(): FStringToken = tokens[pos].also { pos++ }

        /**
         * 入口：以最低优先级开始解析完整表达式。
         */
        fun parse(): JasExpression = parseExpression(0)

        /**
         * 私有辅助：返回二元运算符的优先级（数值越大越紧），未知运算符为 0。
         */
        private fun precOf(op: String): Int = when (op) {
            "||" -> 1
            "&&" -> 2
            "|" -> 3
            "^" -> 4
            "&" -> 5
            "==", "!=" -> 6
            "<", ">", "<=", ">=" -> 7
            "<<", ">>", ">>>" -> 8
            "+", "-" -> 9
            "*", "/", "%" -> 10
            else -> 0
        }

        /**
         * 私有辅助：优先级爬升法解析二元表达式，产出左结合的 JasBinaryOp 链。
         */
        private fun parseExpression(minPrec: Int): JasExpression {
            var left = parsePrefix()
            while (true) {
                val tok = peek() ?: break
                if (tok !is FStringToken.Op) break
                val opPrec = precOf(tok.text)
                if (opPrec < minPrec) break
                consume()
                val right = parseExpression(opPrec + 1)
                left = JasBinaryOp(left, tok.text, right)
            }
            return left
        }

        /**
         * 私有辅助：解析前缀项（标识符/数字/字符串/布尔/null/括号/一元运算符）。
         *
         * 边界：标识符后接后缀链；一元 +/-/!/~ 以高于乘法的优先级解析操作数；未知记号兜底
         * 为空名 JasIdentifier("")。
         */
        private fun parsePrefix(): JasExpression {
            val tok = consume()
            return when (tok) {
                is FStringToken.Ident -> {
                    var expr: JasExpression = JasIdentifier(tok.text)
                    parsePostfix(expr)
                }
                is FStringToken.Number -> {
                    val raw = tok.text
                    if (raw.contains('.') || raw.contains('e') || raw.contains('E')) {
                        JasFloatLiteral(raw.toDoubleOrNull() ?: 0.0, raw)
                    } else {
                        JasIntLiteral(raw.toLongOrNull() ?: 0L, raw)
                    }
                }
                is FStringToken.StringLit -> JasStringLiteral(tok.text.removeSurrounding("\""))
                is FStringToken.Bool -> JasBoolLiteral(tok.value)
                is FStringToken.Null -> JasNullLiteral
                is FStringToken.LParen -> {
                    val expr = parseExpression(0)
                    if (peek() is FStringToken.RParen) consume()
                    expr
                }
                is FStringToken.Op -> {
                    val prec = when (tok.text) {
                        "+", "-", "!", "~" -> precOf("*") + 1
                        else -> 0
                    }
                    val operand = parseExpression(prec)
                    JasUnaryOp(tok.text, operand)
                }
                else -> JasIdentifier("")
            }
        }

        /**
         * 私有辅助：循环解析后缀（.name(...) / (...) / [...]），产出属性访问、调用、下标链。
         */
        private fun parsePostfix(expr: JasExpression): JasExpression {
            var result = expr
            while (true) {
                result = when (peek()) {
                    is FStringToken.Dot -> {
                        consume()
                        val name = when (val t = consume()) {
                            is FStringToken.Ident -> t.text
                            else -> ""
                        }
                        if (peek() is FStringToken.LParen) {
                            consume()
                            val args = parseArgs()
                            if (peek() is FStringToken.RParen) consume()
                            JasCall(JasPropertyAccess(result, name), args)
                        } else {
                            JasPropertyAccess(result, name)
                        }
                    }
                    is FStringToken.LParen -> {
                        consume()
                        val args = parseArgs()
                        if (peek() is FStringToken.RParen) consume()
                        JasCall(result, args)
                    }
                    is FStringToken.LBracket -> {
                        consume()
                        val index = parseExpression(0)
                        if (peek() is FStringToken.RBracket) consume()
                        JasArrayAccess(result, index)
                    }
                    else -> return result
                }
            }
        }

        /**
         * 私有辅助：解析圆括号内的实参列表（以逗号分隔，遇右括号停止）。
         */
        private fun parseArgs(): List<JasExpression> {
            val args = mutableListOf<JasExpression>()
            while (true) {
                val tok = peek() ?: break
                if (tok is FStringToken.RParen) break
                args.add(parseExpression(0))
                if (peek() is FStringToken.Comma) consume()
            }
            return args
        }
    }

    /**
     * 私有辅助：对一段插值文本做词法+语法解析，产出 JasExpression。
     *
     * 边界：文本为空白时返回空名 JasIdentifier("")。
     */
    private fun parseFStringExpr(text: String): JasExpression {
        if (text.isBlank()) return JasIdentifier("")
        val tokens = tokenizeFStringExpr(text)
        val parser = FStringExprParser(tokens)
        return parser.parse()
    }

    /**
     * 私有辅助：把 FORMAT_STRING_LITERAL 原文拆成「字面量片段 + 插值片段」，产出 JasStringTemplate。
     *
     * 实现要点：花括号计数支持嵌套；反斜杠转义原样保留；插值内容交给 parseFStringExpr。
     */
    private fun doParseFString(text: String): JasStringTemplate {
        val parts = mutableListOf<JasTemplatePart>()
        val content = text.removePrefix("f\"").removeSuffix("\"")
        val sb = StringBuilder()
        var i = 0
        // 逐字符扫描：{...} 内为插值（按花括号深度配对），其余累积为字面量片段。
        while (i < content.length) {
            when (content[i]) {
                '{' -> {
                    if (sb.isNotEmpty()) { parts.add(JasTemplateLiteral(sb.toString())); sb.clear() }
                    i++; val exprSb = StringBuilder(); var depth = 1
                    while (i < content.length && depth > 0) {
                        when (content[i]) {
                            '{' -> { depth++; exprSb.append(content[i]) }
                            '}' -> { depth--; if (depth > 0) exprSb.append(content[i]) }
                            else -> exprSb.append(content[i])
                        }
                        i++
                    }
                    if (exprSb.isNotEmpty()) parts.add(JasTemplateExpr(parseFStringExpr(exprSb.toString().trim())))
                }
                '\\' -> { sb.append(content[i]); i++; if (i < content.length) { sb.append(content[i]); i++ } }
                else -> { sb.append(content[i]); i++ }
            }
        }
        if (sb.isNotEmpty()) parts.add(JasTemplateLiteral(sb.toString()))
        return JasStringTemplate(parts)
    }

    // 鈹€鈹€ Patterns 鈹€鈹€

    // 鈹€鈹€ Type parameter helpers 鈹€鈹€

    /**
     * 私有辅助：把 TypeParameters 逐个 visit 并过滤成 JasTypeParameter 列表。
     */
    private fun doVisitTypeParams(ctx: JasperParser.TypeParametersContext): List<JasTypeParameter> {
        return ctx.typeParameter().mapNotNull { visit(it) as? JasTypeParameter }
    }

    /**
     * 私有辅助：把 whereClause 的约束逐个 visit 并过滤成 JasTypeConstraint 列表。
     */
    private fun doVisitWhere(ctx: JasperParser.WhereClauseContext): List<JasTypeConstraint> {
        // 泛型约束：where 子句的每条约束映射为一个 JasTypeConstraint。
        return ctx.whereConstraint().mapNotNull { visit(it) as? JasTypeConstraint }
    }

    /**
     * whereConstraint 规则（name Extends type）：以约束类型原文构造 JasNamedType，产出 JasTypeConstraint。
     */
    override fun visitWhereConstraint(ctx: JasperParser.WhereConstraintContext): JasNode? {
        // where T : Bound —— 约束仅按文本构造 JasNamedType，不做泛型参数展开。
        return JasTypeConstraint(ctx.name.text, JasNamedType(ctx.constraint.text)).at(ctx)
    }

    // 鈹€鈹€ Helpers 鈹€鈹€

    /**
     * 私有辅助：把限定名 a.b.c 折叠为 JasIdentifier(a) + 逐级 JasPropertyAccess。
     *
     * 前置条件：限定名至少含一个标识符。
     */
    private fun doQualifiedNameToExpr(ctx: JasperParser.QualifiedNameContext): JasExpression {
        val ids = ctx.Identifier().map { it.text }
        var expr: JasExpression = JasIdentifier(ids[0])
        for (i in 1 until ids.size) expr = JasPropertyAccess(expr, ids[i])
        return expr
    }

    /**
     * 私有辅助：把 ExprList 的表达式过滤成 JasExpression 列表；ctx 为 null 时返回空列表。
     */
    private fun doVisitExprList(ctx: JasperParser.ExprListContext?): List<JasExpression> {
        if (ctx == null) return emptyList()
        return ctx.expression().mapNotNull { visit(it) as? JasExpression }
    }

    /**
     * 私有辅助：把 modifier 列表聚合为「注解节点 + 小写关键字」的混合列表（List<Any>）。
     *
     * 边界：注解走 visitAnnotationUseNode 得 JasAnnotationUse；其余修饰符取原文转小写。
     */
    private fun doExtractMods(mods: List<JasperParser.ModifierContext>): List<Any> {
        // 修饰符聚合：注解转 JasAnnotationUse，普通修饰符取小写关键字，混存于 List<Any>。
        return mods.map { m ->
            if (m.annotation() != null) {
                val annCtx = m.annotation() as JasperParser.AnnotationUseNodeContext
                visitAnnotationUseNode(annCtx) as JasAnnotationUse
            }
            else m.getText().lowercase()
        }
    }

    // 鈹€鈹€ Annotation visitors 鈹€鈹€

    /**
     * annotation 的 Use 标签（'@' qualifiedName annotationArguments?）：产出 JasAnnotationUse。
     */
    override fun visitAnnotationUseNode(ctx: JasperParser.AnnotationUseNodeContext): JasNode? {
        val name = ctx.qualifiedName().text
        val args = if (ctx.annotationArguments() != null) {
            val argsCtx = ctx.annotationArguments() as JasperParser.AnnotationArgumentsNodeContext
            parseAnnotationArgs(argsCtx)
        } else emptyList()
        return JasAnnotationUse(name, args).at(ctx)
    }

    /**
     * 私有辅助：解析注解实参列表，统一产出 JasAnnotationArgument 列表。
     *
     * 做什么：把语法层的三种实参形态（纯命名 / 位置+命名混合 / 单靠位置）统一收集，
     *   并做一次「命名归一化」后返回。
     * 什么时候用：visitAnnotationUseNode 解析 `@X(...)` 的括号内容时。
     * 前置条件：ctx 为 AnnotationArgumentsNodeContext。
     * 失败模式：无参数返回空列表；单个实参解析失败（值非法/展开等）则该条被跳过。
     * 为什么这样设计：文法里虽有专有的 annotationNamedArgument 规则，但
     *   `name = value` 因歧义常被判入位置分支（parser 优先匹配 annotationPositionalArgument）。
     *   集中在这里调用 [normalizeAnnotationArgument] 折叠，可让所有消费者只面对
     *   JasAnnotationNamedArg，无需各自复制适配逻辑（隔离「文法歧义」这一变化点）。
     * 边界：纯命名路径与混合路径最终都经同一归一化，语义一致；位置参数顺序先于命名参数。
     */
    private fun parseAnnotationArgs(ctx: JasperParser.AnnotationArgumentsNodeContext): List<JasAnnotationArgument> {
        if (ctx.annotationArgumentList() == null) return emptyList()
        val listCtx = ctx.annotationArgumentList()
        // 先按文法原样收集（位置在前、命名在后），再统一做命名归一化。
        val collected = mutableListOf<JasAnnotationArgument>()
        when (listCtx) {
            is JasperParser.AnnotationArgumentsNamedNodeContext -> {
                listCtx.annotationNamedArgument().mapNotNullTo(collected) { parseAnnotationNamedArg(it) }
            }
            is JasperParser.AnnotationArgumentsMixedNodeContext -> {
                listCtx.annotationPositionalArgument().mapNotNullTo(collected) { parseAnnotationPositionalArg(it) }
                listCtx.annotationNamedArgument().mapNotNullTo(collected) { parseAnnotationNamedArg(it) }
            }
            else -> {}
        }
        return collected.map { normalizeAnnotationArgument(it) }
    }

    /**
     * 私有辅助：把「位置参数里包着 `name = value`」归一化为命名实参 [JasAnnotationNamedArg]。
     *
     * 做什么：输入一个已解析的注解实参；若它是位置实参且其值恰为 `标识符 = 值` 的赋值，
     *   则改判为命名实参；否则原样返回。
     * 什么时候用：parseAnnotationArgs 收集完所有实参后逐条调用。
     * 前置条件：入参是 parseAnnotationPositionalArg / parseAnnotationNamedArg 的产物。
     * 失败模式：无（纯结构改写，不抛异常）。
     * 为什么这样设计：ANTLR 的歧义消解把 `@Table(name = "person")` 判成位置分支，
     *   于是 AST 里出现 JasAnnotationPositionalArg(JasAssignment(...))。在 AST 构建层
     *   一次性折叠成命名实参，可让注解消费者免于复制适配（隔离「文法歧义」变化点）。
     * 边界：仅当 op 恰为 "=" 且 target 是 JasIdentifier 时才折叠——`==`、`!=`、复合赋值
     *   （`+=` 等）、或 target 非标识符（如属性访问）都保持位置实参原样，避免误伤。
     */
    private fun normalizeAnnotationArgument(arg: JasAnnotationArgument): JasAnnotationArgument {
        if (arg !is JasAnnotationPositionalArg) return arg
        val value = arg.value
        if (value is JasAssignment && value.op == "=" && value.target is JasIdentifier) {
            return JasAnnotationNamedArg((value.target as JasIdentifier).name, value.value)
        }
        return arg
    }

    /**
     * 私有辅助：把命名实参（Identifier '=' value）解析为 JasAnnotationNamedArg。
     *
     * 边界：上下文类型不符或值解析失败时返回 null。
     */
    private fun parseAnnotationNamedArg(ctx: JasperParser.AnnotationNamedArgumentContext): JasAnnotationNamedArg? {
        val namedCtx = ctx as? JasperParser.AnnotationArgumentNamedNodeContext ?: return null
        val value = parseAnnotationArgValue(namedCtx.annotationArgumentValue()) ?: return null
        return JasAnnotationNamedArg(namedCtx.Identifier().text, value)
    }

    /**
     * 私有辅助：把位置实参解析为 JasAnnotationPositionalArg。
     *
     * 边界：上下文类型不符或值解析失败时返回 null。
     */
    private fun parseAnnotationPositionalArg(ctx: JasperParser.AnnotationPositionalArgumentContext): JasAnnotationPositionalArg? {
        val posCtx = ctx as? JasperParser.AnnotationArgumentPositionalNodeContext ?: return null
        val value = parseAnnotationArgValue(posCtx.annotationArgumentValue()) ?: return null
        return JasAnnotationPositionalArg(value)
    }

    /**
     * 私有辅助：解析单个注解实参值。
     *
     * 做什么：把 annotationArgumentValue 的三种形态转成表达式节点——表达式、嵌套注解、数组。
     * 什么时候用：解析命名/位置实参的值时（[parseAnnotationNamedArg]、[parseAnnotationPositionalArg]）。
     * 前置条件：ctx 非空。
     * 失败模式：上下文类型不符或内层注解节点非法时返回 null（该条实参被上层跳过）。
     * 为什么这样设计：嵌套注解需递归回到 visitAnnotationUseNode，其内部实参会再次经过
     *   [parseAnnotationArgs] 的命名归一化，从而天然递归生效；用 [JasAnnotationLiteral]
     *   把注解实例包装成表达式以适配 JasExpression 值字段。
     * 边界：数组分支把元素递归解析；展开（`...`）不属于实参值形态，不在此处理。
     */
    private fun parseAnnotationArgValue(ctx: JasperParser.AnnotationArgumentValueContext): JasExpression? {
        return when (ctx) {
            is JasperParser.AnnotationArgumentValueExpressionNodeContext -> {
                visit(ctx.expression()) as? JasExpression
            }
            is JasperParser.AnnotationArgumentValueAtNodeContext -> {
                // 嵌套注解：递归构建 JasAnnotationUse（其内部实参自动走命名归一化），再包成表达式。
                val annCtx = ctx.annotation() as? JasperParser.AnnotationUseNodeContext ?: return null
                val use = visitAnnotationUseNode(annCtx) as? JasAnnotationUse ?: return null
                JasAnnotationLiteral(use).at(ctx) as JasExpression
            }
            is JasperParser.AnnotationArgumentValueArrayNodeContext -> {
                val values = ctx.annotationArgumentValue().mapNotNull { parseAnnotationArgValue(it) }
                JasArrayInitValues(values).at(ctx) as JasExpression
            }
            else -> null
        }
    }

    /**
     * 私有辅助：去掉字符串外层引号并处理常见转义序列（换行、制表、回车、反斜杠、引号等）。
     *
     * 边界：未知转义保留其字符本身；孤立的反斜杠被丢弃。
     */
    private fun doUnescape(text: String): String {
        val content = text.removeSurrounding("\"")
        val sb = StringBuilder()
        var i = 0
        while (i < content.length) {
            when (content[i]) {
                '\\' -> {
                    i++
                    if (i < content.length) sb.append(when (content[i]) {
                        'n' -> '\n'; 't' -> '\t'; 'r' -> '\r'
                        '\\' -> '\\'; '"' -> '"'; '\'' -> '\''
                        else -> content[i]
                    })
                }
                else -> sb.append(content[i])
            }
            i++
        }
        return sb.toString()
    }

    /**
     * 私有辅助：由变量声明尾部构造变量语句（供常量/局部变量/接口常量复用）。
     *
     * 前置条件：tail 非空；ctx 用于回填源码位置。边界：增量绑定（inc/dec）单独成句；普通
     * 多绑定包成 JasBlock；单绑定直接返回 JasVariableStatement。
     */
    private fun buildVarStmt(tail: JasperParser.VariableDeclarationTailContext, isConst: Boolean, ctx: ParserRuleContext): JasNode? {
        // Handle variableBindingIncrement (single binding with postfix inc/dec)
        // 增量绑定（x++）不是初始化表达式，需单独构造 JasVariableStatement。
        val incCtx = tail.variableBindingIncrement()
        if (incCtx is JasperParser.VariableBindingIncDecContext) {
            val b = incCtx.core
            val init = visitPostfixIncrement(incCtx.init as JasperParser.PostfixIncDecContext)
            return JasVariableStatement(
                b.name.text,
                if (b.typeRef != null) visit(b.typeRef) as? JasType else null,
                init,
                isConst
            ).at(ctx)
        }
        // Handle variableBinding (multiple bindings)
        // 普通绑定：多绑定包成 JasBlock，单绑定直接返回 JasVariableStatement。
        val stmts = tail.variableBinding().map { vb ->
            val b = vb.core
            JasVariableStatement(
                b.name.text,
                if (b.typeRef != null) visit(b.typeRef) as? JasType else null,
                if (vb.`init` != null) visit(vb.`init`) as? JasExpression else null,
                isConst
            ).at(ctx)
        }
        return if (stmts.size == 1) stmts[0] else JasBlock(stmts).at(ctx)
    }

}
