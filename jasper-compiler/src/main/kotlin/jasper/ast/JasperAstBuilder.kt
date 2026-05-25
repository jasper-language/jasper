package jasper.ast

import jasper.parser.JasperParser
import jasper.parser.JasperParserBaseVisitor
import org.antlr.v4.runtime.ParserRuleContext

class JasperAstBuilder : JasperParserBaseVisitor<JasNode?>() {

    private fun <T : JasNode> T.at(ctx: ParserRuleContext): T {
        sourceLine = ctx.start.line
        sourceColumn = ctx.start.charPositionInLine
        return this
    }

    fun buildSourceFile(ctx: JasperParser.SourceFileContext): JasSourceFile {
        var packageName: String? = null
        if (ctx.packageDeclaration() != null) {
            val pkg = visitPackageDeclaration(ctx.packageDeclaration())
            if (pkg is JasImport) {
                packageName = pkg.name
            }
        }
        val imports = mutableListOf<JasDeclaration>()
        for (imp in ctx.importDeclaration()) {
            collectImports(imp, imports)
        }
        val declarations = mutableListOf<JasDeclaration>()
        val topLevelType = visit(ctx.topLevelTypeDeclaration())
        if (topLevelType is JasDeclaration) declarations.add(topLevelType)
        return JasSourceFile(packageName, declarations, imports)
    }

    private fun collectImports(imp: JasperParser.ImportDeclarationContext, out: MutableList<JasDeclaration>) {
        when (imp) {
            is JasperParser.ImportSingleContext -> {
                visitImportSingle(imp)?.let { if (it is JasDeclaration) out.add(it) }
            }
            is JasperParser.ImportOnDemandContext -> {
                visitImportOnDemand(imp)?.let { if (it is JasDeclaration) out.add(it) }
            }
            is JasperParser.ImportFromContext -> {
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
                    is JasperParser.FromImportRawStringContext -> {
                        source = fromCtx.rs.text; clause = fromCtx.clause
                    }
                    is JasperParser.FromImportTripleStringContext -> {
                        source = fromCtx.ts.text; clause = fromCtx.clause
                    }
                    is JasperParser.FromImportRawTripleStringContext -> {
                        source = fromCtx.rts.text; clause = fromCtx.clause
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

    override fun visitPackageDeclaration(ctx: JasperParser.PackageDeclarationContext): JasNode? {
        return JasImport(ctx.qualifiedName().text, null, false, null)
    }

    override fun visitImportSingle(ctx: JasperParser.ImportSingleContext): JasNode? {
        return JasImport(ctx.qualifiedName().text, null, false, null)
    }

    override fun visitImportOnDemand(ctx: JasperParser.ImportOnDemandContext): JasNode? {
        return JasImport(ctx.qualifiedName().text + ".*", null, true, null)
    }


    // 鈹€鈹€ Normal class declaration 鈹€鈹€

    override fun visitNormalClassDeclaration(ctx: JasperParser.NormalClassDeclarationContext): JasNode? {
        val mods = doExtractMods(ctx.modifier())
        val typeParams = if (ctx.tparams != null) doVisitTypeParams(ctx.tparams) else emptyList()
        val superclass = if (ctx.sc != null) visit(ctx.sc.typePostfix()) as? JasType else null
        val interfaces = if (ctx.sis != null) ctx.sis.typePostfix().mapNotNull { visit(it) as? JasType } else emptyList()
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

    override fun visitMemberField(ctx: JasperParser.MemberFieldContext): JasNode? = visit(ctx.decl)
    override fun visitMemberProperty(ctx: JasperParser.MemberPropertyContext): JasNode? = visit(ctx.decl)
    override fun visitMemberConstructor(ctx: JasperParser.MemberConstructorContext): JasNode? = visit(ctx.decl)
    override fun visitMemberMethod(ctx: JasperParser.MemberMethodContext): JasNode? = visit(ctx.decl)
    override fun visitMemberClass(ctx: JasperParser.MemberClassContext): JasNode? = visit(ctx.decl)
    override fun visitMemberEnum(ctx: JasperParser.MemberEnumContext): JasNode? = visit(ctx.decl)
    override fun visitMemberInterface(ctx: JasperParser.MemberInterfaceContext): JasNode? = visit(ctx.decl)
    override fun visitMemberAnnotationType(ctx: JasperParser.MemberAnnotationTypeContext): JasNode? = visit(ctx.decl)
    override fun visitMemberEmpty(ctx: JasperParser.MemberEmptyContext): JasNode? = null

    override fun visitFieldDeclaration(ctx: JasperParser.FieldDeclarationContext): JasNode? {
        val mods = doExtractMods(ctx.modifier())
        val props = ctx.tail.variableBinding().map { vb ->
            val b = vb.core
            JasProperty(
                b.name.text,
                if (b.typeRef != null) visit(b.typeRef) as? JasType else null,
                mods,
                null,
                null,
                ctx.kind.text == "const"
            ).at(ctx)
        }
        return if (props.size == 1) props[0] else null
    }

    // ── Property declaration ──

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

    override fun visitPropertyAccessorGet(ctx: JasperParser.PropertyAccessorGetContext): JasNode? {
        return JasPropertyAccessor(null, null).at(ctx)
    }

    override fun visitPropertyAccessorSet(ctx: JasperParser.PropertyAccessorSetContext): JasNode? {
        return JasPropertyAccessor(null, "value").at(ctx)
    }

    // 鈹€鈹€ Method declaration 鈹€鈹€

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
        return JasFunction(ctx.name.text, params, returnType, body, mods, typeParams, whereConstraints).at(ctx)
    }

    // 鈹€鈹€ Parameters 鈹€鈹€

    private fun doVisitFuncParams(ctx: JasperParser.FunctionParameterListContext): List<JasParameter> {
        return ctx.functionParameter().mapNotNull { visit(it) as? JasParameter }
    }

    override fun visitFunctionParameter(ctx: JasperParser.FunctionParameterContext): JasNode? {
        val b = ctx.bind
        val name = b.name.text
        val type = if (b.typeRef != null) visit(b.typeRef) as? JasType else null
        val isVararg = ctx.ELLIPSIS() != null
        return JasParameter(name, type, isVararg, null).at(ctx)
    }

    // 鈹€鈹€ Constructor 鈹€鈹€

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
        return JasConstructor(mods, params, delegate, if (stmts.isEmpty()) JasBlock(emptyList()) else JasBlock(stmts)).at(ctx)
    }

    override fun visitExplicitConstructorInvocation(ctx: JasperParser.ExplicitConstructorInvocationContext): JasNode? {
        val isSuper = ctx.Super() != null
        val args = if (ctx.arguments() != null) doVisitExprList(ctx.arguments().exprList()) else emptyList()
        return JasExplicitConstructorInvocation(isSuper, args).at(ctx)
    }

    // 鈹€鈹€ Enum 鈹€鈹€

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

    override fun visitEnumConstant(ctx: JasperParser.EnumConstantContext): JasNode? {
        val args = if (ctx.arguments() != null) doVisitExprList(ctx.arguments().exprList()) else emptyList()
        return JasEnumConstant(ctx.Identifier().text, args).at(ctx)
    }

    // 鈹€鈹€ Interface 鈹€鈹€

    override fun visitNormalInterfaceDeclaration(ctx: JasperParser.NormalInterfaceDeclarationContext): JasNode? {
        val mods = doExtractMods(ctx.modifier())
        val typeParams = if (ctx.tparams != null) doVisitTypeParams(ctx.tparams) else emptyList()
        val extends = if (ctx.exts != null) ctx.exts.typePostfix().mapNotNull { visit(it) as? JasType } else emptyList()
        val members = mutableListOf<JasDeclaration>()
        for (im in ctx.body.interfaceMemberDeclaration()) {
            visit(im)?.let { if (it is JasDeclaration) members.add(it) }
        }
        return JasInterface(ctx.name.text, mods, typeParams, extends, members).at(ctx)
    }

    override fun visitInterfaceMemberConst(ctx: JasperParser.InterfaceMemberConstContext): JasNode? = visit(ctx.decl)
    override fun visitInterfaceMemberProperty(ctx: JasperParser.InterfaceMemberPropertyContext): JasNode? = visit(ctx.decl)
    override fun visitInterfaceMemberMethod(ctx: JasperParser.InterfaceMemberMethodContext): JasNode? = visit(ctx.decl)
    override fun visitInterfaceMemberAbstractMethod(ctx: JasperParser.InterfaceMemberAbstractMethodContext): JasNode? = visit(ctx.decl)
    override fun visitInterfaceMemberClass(ctx: JasperParser.InterfaceMemberClassContext): JasNode? = visit(ctx.decl)
    override fun visitInterfaceMemberEnum(ctx: JasperParser.InterfaceMemberEnumContext): JasNode? = visit(ctx.decl)
    override fun visitInterfaceMemberInterface(ctx: JasperParser.InterfaceMemberInterfaceContext): JasNode? = visit(ctx.decl)
    override fun visitInterfaceMemberAnnotationType(ctx: JasperParser.InterfaceMemberAnnotationTypeContext): JasNode? = visit(ctx.decl)
    override fun visitInterfaceMemberEmpty(ctx: JasperParser.InterfaceMemberEmptyContext): JasNode? = null

    override fun visitConstantDeclaration(ctx: JasperParser.ConstantDeclarationContext): JasNode? {
        return buildVarStmt(ctx.tail, true, ctx)
    }

    override fun visitInterfacePropertyDeclaration(ctx: JasperParser.InterfacePropertyDeclarationContext): JasNode? {
        val header = ctx.header
        val name = header.bindingRef.name.text
        val type = if (header.bindingRef.typeRef != null) visit(header.bindingRef.typeRef) as? JasType else null
        return JasProperty(name, type, emptyList(), null, null, header.kind.text == "const").at(ctx)
    }

    override fun visitInterfaceMethodDeclarationFunctionStyle(ctx: JasperParser.InterfaceMethodDeclarationFunctionStyleContext): JasNode? {
        val mods = doExtractMods(ctx.modifier())
        val typeParams = if (ctx.tparams != null) doVisitTypeParams(ctx.tparams) else emptyList()
        val params = if (ctx.plist != null) doVisitFuncParams(ctx.plist) else emptyList()
        val returnType = if (ctx.ret != null) visit(ctx.ret) as? JasType else null
        val whereConstraints = if (ctx.where != null) doVisitWhere(ctx.where) else emptyList()
        return JasFunction(ctx.name.text, params, returnType, null, mods, typeParams, whereConstraints).at(ctx)
    }

    // 鈹€鈹€ Annotation type 鈹€鈹€

    override fun visitAnnotationTypeDeclaration(ctx: JasperParser.AnnotationTypeDeclarationContext): JasNode? {
        val mods = doExtractMods(ctx.modifier())
        val members = mutableListOf<JasAnnotationMember>()
        for (amd in ctx.body.annotationTypeMemberDeclaration()) {
            visit(amd)?.let { if (it is JasAnnotationMember) members.add(it) }
        }
        return JasAnnotationType(ctx.name.text, mods, members).at(ctx)
    }

    override fun visitAnnotationTypeElementDeclaration(ctx: JasperParser.AnnotationTypeElementDeclarationContext): JasNode? {
        val mods = doExtractMods(ctx.modifier())
        val type = visit(ctx.ty) as? JasType
        val defaultVal = if (ctx.defaultValue() != null) visit(ctx.defaultValue().elementValue()) as? JasExpression else null
        return JasAnnotationMember(ctx.name.text, type, defaultVal).at(ctx)
    }

    override fun visitTypeOrPrimitivePrimitive(ctx: JasperParser.TypeOrPrimitivePrimitiveContext): JasNode? = visit(ctx.primitiveType())
    override fun visitTypeOrPrimitiveType(ctx: JasperParser.TypeOrPrimitiveTypeContext): JasNode? = visit(ctx.typeExpr())

    // 鈹€鈹€ Type expressions 鈹€鈹€

    override fun visitTypeExpression(ctx: JasperParser.TypeExpressionContext): JasNode? {
        val atomCtx = ctx.typeAtom()
        val type = if (atomCtx is JasperParser.TypeAtomNodeContext) {
            doVisitTypeAtomNode(atomCtx)
        } else JasAnyType
        val suffixes = ctx.typeSuffix()
        val qualifiers = ctx.typeQualifier()
        var result = type
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
        for (q in qualifiers) {
            result = when (q) {
                is JasperParser.TypeQualNullableContext -> JasNullableType(result)
                else -> result
            }
        }
        return result.at(ctx)
    }

    private fun doVisitTypeAtomNode(ctx: JasperParser.TypeAtomNodeContext): JasType {
        val base = doVisitAtomBase(ctx.typeAtomBase())
        val prefix = ctx.typePrefix()
        if (prefix is JasperParser.TypePrefixAsteriskContext) return JasPointerType(base)
        return base
    }

    private fun doVisitAtomBase(ctx: JasperParser.TypeAtomBaseContext): JasType {
        return when (val r = visit(ctx)) {
            is JasType -> r
            else -> JasNamedType(ctx.text)
        }
    }

    override fun visitTypeAtomNodePrimitive(ctx: JasperParser.TypeAtomNodePrimitiveContext): JasNode? = visit(ctx.primitiveType())
    override fun visitTypeAtomNodeString(ctx: JasperParser.TypeAtomNodeStringContext): JasNode? = JasStringType
    override fun visitTypeAtomNodeBytes(ctx: JasperParser.TypeAtomNodeBytesContext): JasNode? = JasBytesType
    override fun visitTypeAtomNodeRegex(ctx: JasperParser.TypeAtomNodeRegexContext): JasNode? = JasRegexType
    override fun visitTypeAtomNodeAny(ctx: JasperParser.TypeAtomNodeAnyContext): JasNode? = JasAnyType
    override fun visitTypeAtomNodeUnit(ctx: JasperParser.TypeAtomNodeUnitContext): JasNode? = JasUnitType

    override fun visitTypeAtomNodeTuple(ctx: JasperParser.TypeAtomNodeTupleContext): JasNode? {
        return JasTupleType(ctx.typeExpr().mapNotNull { visit(it) as? JasType }).at(ctx)
    }

    override fun visitTypeAtomNodeGroup(ctx: JasperParser.TypeAtomNodeGroupContext): JasNode? = visit(ctx.typeExpr())

    override fun visitTypeAtomNodeIdentifier(ctx: JasperParser.TypeAtomNodeIdentifierContext): JasNode? {
        val args = if (ctx.typeArguments() != null) {
            ctx.typeArguments().typeArgument().mapNotNull { visit(it) as? JasType }
        } else emptyList()
        return JasNamedType(ctx.Identifier().text, args).at(ctx)
    }

    override fun visitPrimitiveType(ctx: JasperParser.PrimitiveTypeContext): JasNode? = JasPrimitiveType(ctx.getText()).at(ctx)
    override fun visitTypeSuffixArray(ctx: JasperParser.TypeSuffixArrayContext): JasNode? = JasArrayType(JasAnyType).at(ctx)
    override fun visitTypeQualNullable(ctx: JasperParser.TypeQualNullableContext): JasNode? = JasNullableType(JasAnyType).at(ctx)
    override fun visitTypePrefixAsterisk(ctx: JasperParser.TypePrefixAsteriskContext): JasNode? = JasPointerType(JasAnyType).at(ctx)
    override fun visitTypeArgumentType(ctx: JasperParser.TypeArgumentTypeContext): JasNode? = visit(ctx.typeExpr())
    override fun visitTypeArgumentWildcard(ctx: JasperParser.TypeArgumentWildcardContext): JasNode? = JasWildcardType(null, true).at(ctx)

    override fun visitTypeWildcard(ctx: JasperParser.TypeWildcardContext): JasNode? {
        val bound = if (ctx.typeExpr() != null) visit(ctx.typeExpr()) as? JasType else null
        return JasWildcardType(bound, ctx.Extends() != null).at(ctx)
    }

    override fun visitTypeParam(ctx: JasperParser.TypeParamContext): JasNode? {
        val bound = if (ctx.typeBound() != null) {
            val alt = ctx.typeBound()
            if (alt is JasperParser.TypeBoundAlternativeContext) {
                visit(alt.typeExpr()) as? JasType
            } else null
        } else null
        return JasTypeParameter(ctx.Identifier().text, bound).at(ctx)
    }

    override fun visitTypeBoundAlternative(ctx: JasperParser.TypeBoundAlternativeContext): JasNode? {
        return visit(ctx.typeExpr())
    }


    override fun visitTypePostfix(ctx: JasperParser.TypePostfixContext): JasNode? {
        return if (ctx.typeAtom() is JasperParser.TypeAtomNodeContext) {
            doVisitTypeAtomNode(ctx.typeAtom() as JasperParser.TypeAtomNodeContext).at(ctx)
        } else JasAnyType
    }

    // 鈹€鈹€ Statements 鈹€鈹€

    override fun visitBlock(ctx: JasperParser.BlockContext): JasNode? {
        return JasBlock(ctx.blockStatement().mapNotNull { visit(it) as? JasStatement }).at(ctx)
    }

    override fun visitStatementBlock(ctx: JasperParser.StatementBlockContext): JasNode? = visit(ctx.block())
    override fun visitStatementEmpty(ctx: JasperParser.StatementEmptyContext): JasNode? = null

    override fun visitStatementExpressionStatement(ctx: JasperParser.StatementExpressionStatementContext): JasNode? {
        val expr = visit(ctx.expression()) as? JasExpression ?: return null
        return JasExpressionStatement(expr).at(ctx)
    }

    override fun visitStatementIf(ctx: JasperParser.StatementIfContext): JasNode? = visit(ctx.ifStatement())

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

    override fun visitStatementWhile(ctx: JasperParser.StatementWhileContext): JasNode? = visit(ctx.whileStatement())

    override fun visitWhileStatement(ctx: JasperParser.WhileStatementContext): JasNode? {
        return JasWhile(visit(ctx.expression()) as JasExpression, visit(ctx.block()) as JasBlock).at(ctx)
    }

    override fun visitStatementDo(ctx: JasperParser.StatementDoContext): JasNode? = visit(ctx.doStatement())

    override fun visitDoStatement(ctx: JasperParser.DoStatementContext): JasNode? {
        return JasDoWhile(visit(ctx.block()) as JasBlock, visit(ctx.expression()) as JasExpression).at(ctx)
    }

    override fun visitStatementFor(ctx: JasperParser.StatementForContext): JasNode? = visit(ctx.forStatement())

    override fun visitForStatement(ctx: JasperParser.ForStatementContext): JasNode? {
        val control = ctx.forControl()
        val body = visit(ctx.block()) as JasBlock
        var thenBody: JasBlock? = null
        var elseBody: JasBlock? = null
        if (ctx.loopThenElse() != null) {
            val lte = ctx.loopThenElse()
            thenBody = visit(lte.block(0)) as? JasBlock
            elseBody = if (lte.block().size > 1) visit(lte.block(1)) as? JasBlock else null
        }

        if (control.forInControl() != null) {
            val forInCtx = control.forInControl()
            val varName = doExtractForInVar(forInCtx.forInBinding())
            val iterable = visit(forInCtx.expression()) as JasExpression
            return JasForInStatement(varName, iterable, body, thenBody, elseBody).at(ctx)
        }

        if (control.forClassicControl() != null) {
            val classicCtx = control.forClassicControl()
            val init = doBuildForInit(classicCtx.`init`, ctx)
            val condition = if (classicCtx.cond != null) visit(classicCtx.cond) as? JasExpression else null
            val update = if (classicCtx.update != null) {
                val u = classicCtx.update as JasperParser.ForClassicUpdateExprsContext
                visit(u.expression) as? JasExpression
            } else null
            return JasForStatement(init, condition, update, body).at(ctx)
        }
        return JasForStatement(null, null, null, body).at(ctx)
    }

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

    private fun doBuildForInit(initCtx: JasperParser.ForClassicInitContext?, parentCtx: ParserRuleContext): JasStatement? {
        if (initCtx == null) return null
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
            return if (exprs.size == 1) JasExpressionStatement(exprs[0]).at(parentCtx) else null
        }
        return null
    }

    override fun visitStatementSwitch(ctx: JasperParser.StatementSwitchContext): JasNode? = visit(ctx.switchStatement())

    override fun visitSwitchStatement(ctx: JasperParser.SwitchStatementContext): JasNode? {
        val expr = visit(ctx.expression()) as JasExpression
        val cases = mutableListOf<JasCaseClause>()
        for (cc in ctx.switchCaseClause()) {
            cases.add(JasCaseClause(listOfNotNull(visit(cc.expression()) as? JasExpression), visit(cc.block()) as JasBlock).at(ctx))
        }
        cases.add(JasCaseClause(emptyList(), visit(ctx.defaultClause().block()) as JasBlock).at(ctx))
        return JasSwitch(expr, cases).at(ctx)
    }

    override fun visitStatementReturn(ctx: JasperParser.StatementReturnContext): JasNode? = visit(ctx.returnStatement())

    override fun visitReturnStatement(ctx: JasperParser.ReturnStatementContext): JasNode? {
        val value = if (ctx.exprList() != null) {
            ctx.exprList().expression().mapNotNull { visit(it) as? JasExpression }.firstOrNull()
        } else null
        return JasReturn(value).at(ctx)
    }

    override fun visitStatementBreak(ctx: JasperParser.StatementBreakContext): JasNode? = visit(ctx.breakStatement())
    override fun visitBreakStatement(ctx: JasperParser.BreakStatementContext): JasNode? = JasBreakStatement(ctx.Identifier()?.text).at(ctx)
    override fun visitStatementContinue(ctx: JasperParser.StatementContinueContext): JasNode? = visit(ctx.continueStatement())
    override fun visitContinueStatement(ctx: JasperParser.ContinueStatementContext): JasNode? = JasContinueStatement(ctx.Identifier()?.text).at(ctx)
    override fun visitStatementThrow(ctx: JasperParser.StatementThrowContext): JasNode? = visit(ctx.throwStatement())

    override fun visitThrowStatement(ctx: JasperParser.ThrowStatementContext): JasNode? {
        return JasThrow(visit(ctx.expression()) as JasExpression).at(ctx)
    }

    override fun visitStatementTry(ctx: JasperParser.StatementTryContext): JasNode? = visit(ctx.tryStatement())

    override fun visitTryCatches(ctx: JasperParser.TryCatchesContext): JasNode? {
        return JasTry(visit(ctx.block()) as JasBlock, ctx.catchClause().mapNotNull { visit(it) as? JasCatchClause }, null).at(ctx)
    }

    override fun visitTryFinally(ctx: JasperParser.TryFinallyContext): JasNode? {
        val body = visit(ctx.block()) as JasBlock
        val catches = ctx.catchClause().mapNotNull { visit(it) as? JasCatchClause }
        val finallyBody = visit(ctx.finallyClause().block()) as? JasBlock
        return JasTry(body, catches, finallyBody).at(ctx)
    }

    override fun visitCatchClause(ctx: JasperParser.CatchClauseContext): JasNode? {
        val b = ctx.bind
        val param = JasParameter(b.name.text, if (b.typeRef != null) visit(b.typeRef) as? JasType else null).at(ctx)
        return JasCatchClause(param, visit(ctx.body) as JasBlock).at(ctx)
    }

    override fun visitFinallyClause(ctx: JasperParser.FinallyClauseContext): JasNode? = visit(ctx.block())
    override fun visitStatementDefer(ctx: JasperParser.StatementDeferContext): JasNode? = visit(ctx.deferStatement())

    override fun visitDeferStatement(ctx: JasperParser.DeferStatementContext): JasNode? {
        val stmt = visit(ctx.statement())
        val block = if (stmt is JasBlock) stmt else JasBlock(listOfNotNull(stmt as? JasStatement)).at(ctx)
        return JasDefer(block).at(ctx)
    }

    override fun visitStatementLock(ctx: JasperParser.StatementLockContext): JasNode? = visit(ctx.lockStatement())

    override fun visitLockStatement(ctx: JasperParser.LockStatementContext): JasNode? {
        return JasLock(visit(ctx.expression()) as JasExpression, visit(ctx.block()) as JasBlock).at(ctx)
    }

    override fun visitStatementAssert(ctx: JasperParser.StatementAssertContext): JasNode? = visit(ctx.assertStatement())

    override fun visitAssertSimple(ctx: JasperParser.AssertSimpleContext): JasNode? {
        return JasAssert(visit(ctx.expression()) as JasExpression, null).at(ctx)
    }

    override fun visitAssertWithMessage(ctx: JasperParser.AssertWithMessageContext): JasNode? {
        return JasAssert(visit(ctx.expression(0)) as JasExpression, visit(ctx.expression(1)) as? JasExpression).at(ctx)
    }

    override fun visitStatementYield(ctx: JasperParser.StatementYieldContext): JasNode? = visit(ctx.yieldStatement())

    override fun visitYieldStatement(ctx: JasperParser.YieldStatementContext): JasNode? {
        return JasYield(visit(ctx.expression()) as JasExpression).at(ctx)
    }

    override fun visitStatementLabeled(ctx: JasperParser.StatementLabeledContext): JasNode? = visit(ctx.labeledStatement())

    override fun visitLabeledStatement(ctx: JasperParser.LabeledStatementContext): JasNode? {
        return JasLabeledStatement(ctx.label.text, visit(ctx.loopStatement()) as JasStatement).at(ctx)
    }

    override fun visitLocalVariableDeclarationStatement(ctx: JasperParser.LocalVariableDeclarationStatementContext): JasNode? {
        return buildVarStmt(ctx.tail, ctx.kind.text == "const", ctx)
    }

    // 鈹€鈹€ Expressions 鈹€鈹€

    override fun visitExprLambda(ctx: JasperParser.ExprLambdaContext): JasNode? = visit(ctx.lambdaExpression())

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

    override fun visitExprAssignment(ctx: JasperParser.ExprAssignmentContext): JasNode? = visit(ctx.assignmentExpression())

    override fun visitAssignExprAssignment(ctx: JasperParser.AssignExprAssignmentContext): JasNode? {
        val a = ctx.assignment()
        val isAssignmentExpr = a is JasperParser.AssignmentExprContext
        val target = if (isAssignmentExpr) {
            val ae = a as JasperParser.AssignmentExprContext
            when (val tc = ae.target) {
                is JasperParser.AssignmentExprTargetQualifiedNameContext -> doQualifiedNameToExpr(tc.qualifiedName())
                is JasperParser.AssignmentExprTargetPrimaryChainContext -> {
                    var expr = visit(tc.primaryAtom()) as? JasExpression
                    for (suf in tc.primarySuffix()) {
                        expr = if (expr != null) doApplySuffix(expr, suf) else null
                    }
                    expr
                }
                else -> null
            }
        } else null

        if (isAssignmentExpr && target != null) {
            val ae = a as JasperParser.AssignmentExprContext
            return JasAssignment(target, visit(ae.rhs) as JasExpression, ae.op.text).at(ctx)
        }
        val ae = a as JasperParser.AssignmentExprContext
        return JasExpressionStatement(visit(ae.rhs) as JasExpression).at(ctx)
    }

    override fun visitAssignExprConditional(ctx: JasperParser.AssignExprConditionalContext): JasNode? {
        return visit(ctx.conditionalExpression())
    }

    override fun visitCondExpr(ctx: JasperParser.CondExprContext): JasNode? {
        val condition = visit(ctx.nullFallbackExpression()) as JasExpression
        if (ctx.expression() != null && ctx.conditionalExpression() != null) {
            return JasTernaryExpr(condition, visit(ctx.expression()) as JasExpression, visit(ctx.conditionalExpression()) as JasExpression).at(ctx)
        }
        return condition
    }

    override fun visitNullFallbackExpr(ctx: JasperParser.NullFallbackExprContext): JasNode? {
        val left = visit(ctx.nullCoalesceExpression(0)) as JasExpression
        return if (ctx.nullCoalesceExpression().size > 1) {
            JasNullCoalescing(left, visit(ctx.nullCoalesceExpression(1)) as JasExpression).at(ctx)
        } else left
    }

    override fun visitNullCoalesceExpr(ctx: JasperParser.NullCoalesceExprContext): JasNode? {
        val exprs = ctx.binaryExpression()
        var result = visit(exprs[0]) as JasExpression
        for (i in 1 until exprs.size) {
            val right = visit(exprs[i]) as? JasExpression ?: break
            result = JasNullCoalescing(result, right)
        }
        return result
    }

    override fun visitBinaryUnary(ctx: JasperParser.BinaryUnaryContext): JasNode? = visit(ctx.unaryExpression())

    override fun visitBinaryMultiplication(ctx: JasperParser.BinaryMultiplicationContext): JasNode? {
        return JasBinaryOp(visit(ctx.binaryExpression()) as JasExpression, ctx.op.text, visit(ctx.unaryExpression()) as JasExpression).at(ctx)
    }

    override fun visitBinaryAddition(ctx: JasperParser.BinaryAdditionContext): JasNode? {
        return JasBinaryOp(visit(ctx.binaryExpression()) as JasExpression, ctx.op.text, visit(ctx.unaryExpression()) as JasExpression).at(ctx)
    }

    override fun visitBinaryShift(ctx: JasperParser.BinaryShiftContext): JasNode? {
        return JasBinaryOp(visit(ctx.binaryExpression()) as JasExpression, ctx.op.text, visit(ctx.unaryExpression()) as JasExpression).at(ctx)
    }

    override fun visitBinaryRelational(ctx: JasperParser.BinaryRelationalContext): JasNode? {
        return JasBinaryOp(visit(ctx.binaryExpression()) as JasExpression, ctx.op.text, visit(ctx.unaryExpression()) as JasExpression).at(ctx)
    }

    override fun visitBinaryEquality(ctx: JasperParser.BinaryEqualityContext): JasNode? {
        return JasBinaryOp(visit(ctx.binaryExpression()) as JasExpression, ctx.op.text, visit(ctx.unaryExpression()) as JasExpression).at(ctx)
    }

    override fun visitBinaryAnd(ctx: JasperParser.BinaryAndContext): JasNode? {
        return JasBinaryOp(visit(ctx.binaryExpression()) as JasExpression, "&&", visit(ctx.unaryExpression()) as JasExpression).at(ctx)
    }

    override fun visitBinaryOr(ctx: JasperParser.BinaryOrContext): JasNode? {
        return JasBinaryOp(visit(ctx.binaryExpression()) as JasExpression, "||", visit(ctx.unaryExpression()) as JasExpression).at(ctx)
    }

    override fun visitBinaryBitAnd(ctx: JasperParser.BinaryBitAndContext): JasNode? {
        return JasBinaryOp(visit(ctx.binaryExpression()) as JasExpression, "&", visit(ctx.unaryExpression()) as JasExpression).at(ctx)
    }

    override fun visitBinaryBitOr(ctx: JasperParser.BinaryBitOrContext): JasNode? {
        return JasBinaryOp(visit(ctx.binaryExpression()) as JasExpression, "|", visit(ctx.unaryExpression()) as JasExpression).at(ctx)
    }

    override fun visitBinaryBitExclusiveOr(ctx: JasperParser.BinaryBitExclusiveOrContext): JasNode? {
        return JasBinaryOp(visit(ctx.binaryExpression()) as JasExpression, "^", visit(ctx.unaryExpression()) as JasExpression).at(ctx)
    }

    override fun visitUnaryPlus(ctx: JasperParser.UnaryPlusContext): JasNode? {
        return JasUnaryOp("+", visit(ctx.unaryExpression()) as JasExpression).at(ctx)
    }

    override fun visitUnaryMinus(ctx: JasperParser.UnaryMinusContext): JasNode? {
        return JasUnaryOp("-", visit(ctx.unaryExpression()) as JasExpression).at(ctx)
    }

    override fun visitUnaryDeref(ctx: JasperParser.UnaryDerefContext): JasNode? {
        return JasUnaryOp("*", visit(ctx.unaryExpression()) as JasExpression).at(ctx)
    }

    override fun visitUnaryAddressOf(ctx: JasperParser.UnaryAddressOfContext): JasNode? {
        return JasUnaryOp("&", visit(ctx.unaryExpression()) as JasExpression).at(ctx)
    }

    override fun visitUnaryLogicalNot(ctx: JasperParser.UnaryLogicalNotContext): JasNode? {
        return JasUnaryOp("!", visit(ctx.unaryExpression()) as JasExpression).at(ctx)
    }

    override fun visitUnaryBitNot(ctx: JasperParser.UnaryBitNotContext): JasNode? {
        return JasUnaryOp("~", visit(ctx.unaryExpression()) as JasExpression).at(ctx)
    }

    override fun visitUnaryAwait(ctx: JasperParser.UnaryAwaitContext): JasNode? = visit(ctx.awaitExpression())

    override fun visitAwaitExpression(ctx: JasperParser.AwaitExpressionContext): JasNode? {
        return JasUnaryOp("await", visit(ctx.unaryExpression()) as JasExpression).at(ctx)
    }

    override fun visitUnaryGo(ctx: JasperParser.UnaryGoContext): JasNode? = visit(ctx.goExpression())
    override fun visitGoExpression(ctx: JasperParser.GoExpressionContext): JasNode? = visit(ctx.goTarget())
    override fun visitGoTargetPrimary(ctx: JasperParser.GoTargetPrimaryContext): JasNode? = visit(ctx.primary())
    override fun visitGoTargetLambda(ctx: JasperParser.GoTargetLambdaContext): JasNode? = visit(ctx.lambdaExpression())
    override fun visitGoTargetBlock(ctx: JasperParser.GoTargetBlockContext): JasNode? = visit(ctx.block())
    override fun visitGoTargetParen(ctx: JasperParser.GoTargetParenContext): JasNode? = visit(ctx.expression())
    override fun visitUnaryCast(ctx: JasperParser.UnaryCastContext): JasNode? = visit(ctx.castExpression())

    override fun visitCastPrimitive(ctx: JasperParser.CastPrimitiveContext): JasNode? {
        return JasCastExpr(visit(ctx.unaryExpression()) as JasExpression, visit(ctx.primitiveType()) as JasPrimitiveType).at(ctx)
    }

    override fun visitCastAs(ctx: JasperParser.CastAsContext): JasNode? {
        val type = visit(ctx.ty) as? JasType ?: return null
        return JasCastExpr(visit(ctx.expr) as JasExpression, type, false).at(ctx)
    }

    override fun visitUnaryPrimary(ctx: JasperParser.UnaryPrimaryContext): JasNode? = visit(ctx.primary())

    // 鈹€鈹€ Primary expressions 鈹€鈹€

    override fun visitPrimaryChain(ctx: JasperParser.PrimaryChainContext): JasNode? {
        var result = visit(ctx.primaryAtom()) as? JasExpression ?: return null
        for (suffix in ctx.primarySuffix()) {
            result = doApplySuffix(result, suffix)
        }
        return result
    }

    override fun visitPrimaryAtomLiteral(ctx: JasperParser.PrimaryAtomLiteralContext): JasNode? = visit(ctx.literal())
    override fun visitPrimaryAtomName(ctx: JasperParser.PrimaryAtomNameContext): JasNode? = doQualifiedNameToExpr(ctx.qualifiedName())
    override fun visitPrimaryAtomTuple(ctx: JasperParser.PrimaryAtomTupleContext): JasNode? {
        return JasTupleExpr(ctx.expression().mapNotNull { visit(it) as? JasExpression }).at(ctx)
    }
    override fun visitPrimaryAtomParen(ctx: JasperParser.PrimaryAtomParenContext): JasNode? = visit(ctx.expression())
    override fun visitPrimaryAtomThis(ctx: JasperParser.PrimaryAtomThisContext): JasNode? = JasIdentifier("this").at(ctx)
    override fun visitPrimaryAtomSuper(ctx: JasperParser.PrimaryAtomSuperContext): JasNode? = JasIdentifier("super").at(ctx)

    override fun visitPrimaryAtomTypeThis(ctx: JasperParser.PrimaryAtomTypeThisContext): JasNode? {
        return JasPropertyAccess(JasIdentifier(ctx.qualifiedName().text).at(ctx), "this").at(ctx)
    }

    override fun visitPrimaryAtomTypeSuper(ctx: JasperParser.PrimaryAtomTypeSuperContext): JasNode? {
        return JasPropertyAccess(JasIdentifier(ctx.qualifiedName().text).at(ctx), "super").at(ctx)
    }

    override fun visitPrimaryAtomClassLiteral(ctx: JasperParser.PrimaryAtomClassLiteralContext): JasNode? {
        return JasIdentifier(ctx.qualifiedName().text + ".class").at(ctx)
    }

    override fun visitPrimaryAtomNew(ctx: JasperParser.PrimaryAtomNewContext): JasNode? {
        return visit(ctx.classInstanceCreationExpression())
    }

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

    override fun visitPrimaryAtomArrayCreation(ctx: JasperParser.PrimaryAtomArrayCreationContext): JasNode? {
        return visit(ctx.arrayCreationExpression())
    }

    override fun visitArrayNewPrimitiveDimExprs(ctx: JasperParser.ArrayNewPrimitiveDimExprsContext): JasNode? {
        val type = visit(ctx.primitiveType()) as JasPrimitiveType
        val dims = ctx.dimExprs().dimExpr().map { de -> visit(de.expression()) as? JasExpression ?: JasIntLiteral(0L, "0") }
        return JasArrayCreation(type, dims, null).at(ctx)
    }

    override fun visitArrayNewRefDimExprs(ctx: JasperParser.ArrayNewRefDimExprsContext): JasNode? {
        val type = visit(ctx.typePostfix()) as? JasType ?: return null
        val dims = ctx.dimExprs().dimExpr().map { de -> visit(de.expression()) as? JasExpression ?: JasIntLiteral(0L, "0") }
        return JasArrayCreation(type, dims, null).at(ctx)
    }

    override fun visitArrayInitPrimitive(ctx: JasperParser.ArrayInitPrimitiveContext): JasNode? {
        val type = visit(ctx.primitiveType()) as JasPrimitiveType
        return JasArrayCreation(type, emptyList(), visit(ctx.arrayInitializer()) as? JasArrayInit).at(ctx)
    }

    override fun visitArrayInitRef(ctx: JasperParser.ArrayInitRefContext): JasNode? {
        val type = visit(ctx.typePostfix()) as? JasType ?: return null
        return JasArrayCreation(type, emptyList(), visit(ctx.arrayInitializer()) as? JasArrayInit).at(ctx)
    }

    override fun visitArrayInitializer(ctx: JasperParser.ArrayInitializerContext): JasNode? {
        val values = ctx.variableInitializer().mapNotNull { vi ->
            if (vi is JasperParser.VariableInitializerExpressionContext) visit(vi.expression()) as? JasExpression else null
        }
        return JasArrayInitValues(values).at(ctx)
    }

    // 鈹€鈹€ Primary suffix helper 鈹€鈹€

    private fun doApplySuffix(base: JasExpression, suffix: JasperParser.PrimarySuffixContext): JasExpression {
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

    override fun visitPrimaryMethodReference(ctx: JasperParser.PrimaryMethodReferenceContext): JasNode? {
        return visit(ctx.methodReference())
    }

    override fun visitMethodReferenceExpressionName(ctx: JasperParser.MethodReferenceExpressionNameContext): JasNode? {
        val qualName = ctx.qualifiedName()
        return JasMethodReference(doQualifiedNameToExpr(qualName), ctx.Identifier().text, packageName = qualName.text).at(ctx)
    }

    // 鈹€鈹€ Literals 鈹€鈹€

    override fun visitDecimalLiteral(ctx: JasperParser.DecimalLiteralContext): JasNode? {
        return JasIntLiteral(ctx.text.replace("_", "").toLongOrNull() ?: 0L, ctx.text).at(ctx)
    }

    override fun visitHexadecimalLiteral(ctx: JasperParser.HexadecimalLiteralContext): JasNode? {
        val cleaned = ctx.text.replace("_", "").removePrefix("0x").removePrefix("0X")
        return JasIntLiteral(cleaned.toLongOrNull(16) ?: 0L, ctx.text).at(ctx)
    }

    override fun visitOctalLiteral(ctx: JasperParser.OctalLiteralContext): JasNode? {
        val cleaned = ctx.text.replace("_", "").removePrefix("0o").removePrefix("0O")
        return JasIntLiteral(cleaned.toLongOrNull(8) ?: 0L, ctx.text).at(ctx)
    }

    override fun visitBinaryLiteral(ctx: JasperParser.BinaryLiteralContext): JasNode? {
        val cleaned = ctx.text.replace("_", "").removePrefix("0b").removePrefix("0B")
        return JasIntLiteral(cleaned.toLongOrNull(2) ?: 0L, ctx.text).at(ctx)
    }

    override fun visitFloatLiteral(ctx: JasperParser.FloatLiteralContext): JasNode? {
        return JasFloatLiteral(ctx.text.replace("_", "").toDoubleOrNull() ?: 0.0, ctx.text).at(ctx)
    }

    override fun visitHexFloatLiteral(ctx: JasperParser.HexFloatLiteralContext): JasNode? {
        return JasFloatLiteral(0.0, ctx.text).at(ctx)
    }

    override fun visitStringLiteral(ctx: JasperParser.StringLiteralContext): JasNode? {
        return JasStringLiteral(doUnescape(ctx.text)).at(ctx)
    }

    override fun visitRawStringLiteral(ctx: JasperParser.RawStringLiteralContext): JasNode? {
        return JasStringLiteral(ctx.text.drop(1).dropLast(1), raw = true).at(ctx)
    }

    override fun visitTripleStringLiteral(ctx: JasperParser.TripleStringLiteralContext): JasNode? {
        return JasStringLiteral(ctx.text.drop(3).dropLast(3)).at(ctx)
    }

    override fun visitFormatStringLiteral(ctx: JasperParser.FormatStringLiteralContext): JasNode? {
        return doParseFString(ctx.text)
    }

    override fun visitByteStringLiteral(ctx: JasperParser.ByteStringLiteralContext): JasNode? = JasStringLiteral(ctx.text).at(ctx)
    override fun visitUnicodeStringLiteral(ctx: JasperParser.UnicodeStringLiteralContext): JasNode? = JasStringLiteral(ctx.text).at(ctx)
    override fun visitTrueLiteral(ctx: JasperParser.TrueLiteralContext): JasNode? = JasBoolLiteral(true).at(ctx)
    override fun visitFalseLiteral(ctx: JasperParser.FalseLiteralContext): JasNode? = JasBoolLiteral(false).at(ctx)
    override fun visitNullLiteral(ctx: JasperParser.NullLiteralContext): JasNode? = JasNullLiteral
    override fun visitLiteralDict(ctx: JasperParser.LiteralDictContext): JasNode? = visit(ctx.dictLiteral())

    override fun visitLiteralJson(ctx: JasperParser.LiteralJsonContext): JasNode? = visit(ctx.jsonLiteral())

    override fun visitDictLiteral(ctx: JasperParser.DictLiteralContext): JasNode? {
        val entries = ctx.dictEntry().map { de ->
            JasDictEntry(
                visit(de.key) as? JasExpression ?: JasStringLiteral(""),
                visit(de.value) as? JasExpression ?: JasNullLiteral
            ).at(ctx)
        }
        return JasDictLiteral(entries).at(ctx)
    }

    override fun visitJsonLiteral(ctx: JasperParser.JsonLiteralContext): JasNode? {
        val entries = ctx.jsonEntry().map { je ->
            JasDictEntry(
                visit(je.key) as? JasExpression ?: JasStringLiteral(""),
                visit(je.value) as? JasExpression ?: JasNullLiteral
            ).at(ctx)
        }
        return JasDictLiteral(entries).at(ctx)
    }

    // 鈹€鈹€ Format string 鈹€鈹€

    // 鈹€鈹€ F-string expression parser 鈹€鈹€

    private sealed class FStringToken {
        data class Ident(val text: String) : FStringToken()
        data class Number(val text: String) : FStringToken()
        data class StringLit(val text: String) : FStringToken()
        data class Op(val text: String) : FStringToken()
        object LParen : FStringToken()
        object RParen : FStringToken()
        object LBracket : FStringToken()
        object RBracket : FStringToken()
        object Dot : FStringToken()
        object Comma : FStringToken()
        data class Bool(val value: Boolean) : FStringToken()
        object Null : FStringToken()
    }

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

    private class FStringExprParser(private val tokens: List<FStringToken>) {
        private var pos = 0

        private fun peek(): FStringToken? = tokens.getOrNull(pos)
        private fun consume(): FStringToken = tokens[pos].also { pos++ }

        fun parse(): JasExpression = parseExpression(0)

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

    private fun parseFStringExpr(text: String): JasExpression {
        if (text.isBlank()) return JasIdentifier("")
        val tokens = tokenizeFStringExpr(text)
        val parser = FStringExprParser(tokens)
        return parser.parse()
    }

    private fun doParseFString(text: String): JasStringTemplate {
        val parts = mutableListOf<JasTemplatePart>()
        val content = text.removePrefix("f\"").removeSuffix("\"")
        val sb = StringBuilder()
        var i = 0
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

    override fun visitPatternRoot(ctx: JasperParser.PatternRootContext): JasNode? = visit(ctx.patternOr())
    override fun visitPatternPrimaryAtom(ctx: JasperParser.PatternPrimaryAtomContext): JasNode? = visit(ctx.primaryPattern())
    override fun visitPatternPrimaryParen(ctx: JasperParser.PatternPrimaryParenContext): JasNode? = visit(ctx.pattern())

    override fun visitPrimaryPattern(ctx: JasperParser.PrimaryPatternContext): JasNode? {
        if (ctx.Underscore() != null) return JasWildcardPattern
        if (ctx.literal() != null) return visit(ctx.literal())
        if (ctx.qualifiedName() != null) return doQualifiedNameToExpr(ctx.qualifiedName())
        return null
    }

    // 鈹€鈹€ Type parameter helpers 鈹€鈹€

    private fun doVisitTypeParams(ctx: JasperParser.TypeParametersContext): List<JasTypeParameter> {
        return ctx.typeParameter().mapNotNull { visit(it) as? JasTypeParameter }
    }

    private fun doVisitWhere(ctx: JasperParser.WhereClauseContext): List<JasTypeConstraint> {
        return ctx.whereConstraint().mapNotNull { visit(it) as? JasTypeConstraint }
    }

    override fun visitWhereConstraint(ctx: JasperParser.WhereConstraintContext): JasNode? {
        return JasTypeConstraint(ctx.name.text, JasNamedType(ctx.constraint.text)).at(ctx)
    }

    // 鈹€鈹€ Helpers 鈹€鈹€

    private fun doQualifiedNameToExpr(ctx: JasperParser.QualifiedNameContext): JasExpression {
        val ids = ctx.Identifier().map { it.text }
        var expr: JasExpression = JasIdentifier(ids[0])
        for (i in 1 until ids.size) expr = JasPropertyAccess(expr, ids[i])
        return expr
    }

    private fun doVisitExprList(ctx: JasperParser.ExprListContext?): List<JasExpression> {
        if (ctx == null) return emptyList()
        return ctx.expression().mapNotNull { visit(it) as? JasExpression }
    }

    private fun doExtractMods(mods: List<JasperParser.ModifierContext>): List<Any> {
        return mods.map { m ->
            if (m.annotation() != null) {
                val annCtx = m.annotation() as JasperParser.AnnotationUseNodeContext
                visitAnnotationUseNode(annCtx) as JasAnnotationUse
            }
            else m.getText().lowercase()
        }
    }

    // 鈹€鈹€ Annotation visitors 鈹€鈹€

    override fun visitAnnotationUseNode(ctx: JasperParser.AnnotationUseNodeContext): JasNode? {
        val name = ctx.qualifiedName().text
        val args = if (ctx.annotationArguments() != null) {
            val argsCtx = ctx.annotationArguments() as JasperParser.AnnotationArgumentsNodeContext
            parseAnnotationArgs(argsCtx)
        } else emptyList()
        return JasAnnotationUse(name, args).at(ctx)
    }

    private fun parseAnnotationArgs(ctx: JasperParser.AnnotationArgumentsNodeContext): List<JasAnnotationArgument> {
        if (ctx.annotationArgumentList() == null) return emptyList()
        val listCtx = ctx.annotationArgumentList()
        return when (listCtx) {
            is JasperParser.AnnotationArgumentsNamedNodeContext -> {
                listCtx.annotationNamedArgument().mapNotNull { parseAnnotationNamedArg(it) }
            }
            is JasperParser.AnnotationArgumentsMixedNodeContext -> {
                val result = mutableListOf<JasAnnotationArgument>()
                for (pa in listCtx.annotationPositionalArgument()) {
                    val arg = parseAnnotationPositionalArg(pa)
                    if (arg != null) result.add(arg)
                }
                for (na in listCtx.annotationNamedArgument()) {
                    val arg = parseAnnotationNamedArg(na)
                    if (arg != null) result.add(arg)
                }
                result
            }
            else -> emptyList()
        }
    }

    private fun parseAnnotationNamedArg(ctx: JasperParser.AnnotationNamedArgumentContext): JasAnnotationNamedArg? {
        val namedCtx = ctx as? JasperParser.AnnotationArgumentNamedNodeContext ?: return null
        val value = parseAnnotationArgValue(namedCtx.annotationArgumentValue()) ?: return null
        return JasAnnotationNamedArg(namedCtx.Identifier().text, value)
    }

    private fun parseAnnotationPositionalArg(ctx: JasperParser.AnnotationPositionalArgumentContext): JasAnnotationPositionalArg? {
        val posCtx = ctx as? JasperParser.AnnotationArgumentPositionalNodeContext ?: return null
        val value = parseAnnotationArgValue(posCtx.annotationArgumentValue()) ?: return null
        return JasAnnotationPositionalArg(value)
    }

    private fun parseAnnotationArgValue(ctx: JasperParser.AnnotationArgumentValueContext): JasExpression? {
        return when (ctx) {
            is JasperParser.AnnotationArgumentValueExpressionNodeContext -> {
                visit(ctx.expression()) as? JasExpression
            }
            else -> null
        }
    }

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

    private fun buildVarStmt(tail: JasperParser.VariableDeclarationTailContext, isConst: Boolean, ctx: ParserRuleContext): JasNode? {
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
