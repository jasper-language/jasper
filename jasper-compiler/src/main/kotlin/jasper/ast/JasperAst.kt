package jasper.ast

sealed class JasNode {
    var sourceLine: Int = 0
    var sourceColumn: Int = 0
}

// --- Declarations ---
sealed class JasDeclaration : JasNode()
data class JasFunction(
    val name: String, val parameters: List<JasParameter>, val returnType: JasType?,
    val body: JasBlock?, val modifiers: List<Any>, val typeParameters: List<JasTypeParameter> = emptyList(),
    val whereConstraints: List<JasTypeConstraint> = emptyList()
) : JasDeclaration()

data class JasClass(
    val name: String, val modifiers: List<Any>, val typeParameters: List<JasTypeParameter>,
    val superclass: JasType?, val interfaces: List<JasType>,
    val members: List<JasDeclaration>, val constructors: List<JasConstructor>
) : JasDeclaration()

data class JasInterface(
    val name: String, val modifiers: List<Any>, val typeParameters: List<JasTypeParameter>,
    val extends: List<JasType>, val members: List<JasDeclaration>
) : JasDeclaration()

data class JasEnum(
    val name: String, val modifiers: List<Any>,
    val constants: List<JasEnumConstant>, val members: List<JasDeclaration>
) : JasDeclaration()

data class JasEnumConstant(val name: String, val args: List<JasExpression>) : JasNode()

data class JasConstructor(
    val modifiers: List<Any>, val parameters: List<JasParameter>,
    val delegateCall: JasExplicitConstructorInvocation?,
    val body: JasBlock?
) : JasDeclaration()

data class JasExplicitConstructorInvocation(
    val isSuper: Boolean, val args: List<JasExpression>
) : JasNode()

data class JasProperty(
    val name: String, val type: JasType?, val modifiers: List<Any>,
    val getter: JasPropertyAccessor?, val setter: JasPropertyAccessor?,
    val isConst: Boolean = false
) : JasDeclaration()

data class JasPropertyAccessor(
    val body: JasBlock?, val parameterName: String?
) : JasNode()

data class JasAnnotationType(
    val name: String, val modifiers: List<Any>, val members: List<JasAnnotationMember>
) : JasDeclaration()

data class JasAnnotationMember(
    val name: String, val type: JasType?, val defaultValue: JasExpression?
) : JasDeclaration()

data class JasImport(
    val name: String, val alias: String?, val isOnDemand: Boolean,
    val fromSource: String?
) : JasDeclaration()

data class JasTypeParameter(val name: String, val bound: JasType?) : JasNode()
data class JasTypeConstraint(val typeParamName: String, val bound: JasType) : JasNode()
data class JasParameter(val name: String, val type: JasType?, val vararg: Boolean = false, val defaultValue: JasExpression? = null) : JasNode()

// --- Statements ---
data class JasBlock(val statements: List<JasStatement>) : JasStatement()
open class JasStatement : JasNode()
data class JasVariableStatement(val name: String, val type: JasType?, val initializer: JasExpression?, val isConst: Boolean = false) : JasStatement()
data class JasExpressionStatement(val expression: JasExpression) : JasStatement()
data class JasIf(val condition: JasExpression, val thenBody: JasBlock, val elseBody: JasBlock?) : JasStatement()
data class JasWhile(val condition: JasExpression, val body: JasBlock) : JasStatement()
data class JasDoWhile(val body: JasBlock, val condition: JasExpression) : JasStatement()
data class JasForStatement(val init: JasStatement?, val condition: JasExpression?, val update: JasExpression?, val body: JasBlock) : JasStatement()
data class JasForInStatement(val varName: String, val iterable: JasExpression, val body: JasBlock, val thenBody: JasBlock? = null, val elseBody: JasBlock? = null) : JasStatement()
data class JasSwitch(val expression: JasExpression, val cases: List<JasCaseClause>) : JasStatement()
data class JasCaseClause(val values: List<JasExpression>, val body: JasBlock) : JasNode()
data class JasReturn(val value: JasExpression?) : JasStatement()
data class JasBreakStatement(val label: String?) : JasStatement()
data class JasContinueStatement(val label: String?) : JasStatement()
data class JasThrow(val expression: JasExpression) : JasStatement()
data class JasTry(val body: JasBlock, val catches: List<JasCatchClause>, val finallyBody: JasBlock?) : JasStatement()
data class JasCatchClause(val parameter: JasParameter, val body: JasBlock) : JasNode()
data class JasDefer(val body: JasBlock) : JasStatement()
data class JasLock(val expression: JasExpression, val body: JasBlock) : JasStatement()
data class JasAssert(val condition: JasExpression, val message: JasExpression?) : JasStatement()
data class JasYield(val expression: JasExpression) : JasStatement()
data class JasLabeledStatement(val label: String, val statement: JasStatement) : JasStatement()
data class JasAssignment(val target: JasExpression, val value: JasExpression, val op: String = "=") : JasExpression()
data class JasDestructuringDeclaration(val bindings: List<JasDestructuringBinding>, val initializer: JasExpression) : JasStatement()
data class JasDestructuringBinding(val name: String, val type: JasType?) : JasNode()

// --- Pattern Matching ---
data class JasMatch(val expression: JasExpression, val cases: List<JasMatchCase>) : JasStatement()
data class JasMatchCase(val pattern: JasNode?, val guard: JasExpression?, val body: JasBlock) : JasNode()
sealed class JasPattern : JasNode()
object JasWildcardPattern : JasPattern()

// --- Expressions ---
open class JasExpression : JasNode()
data class JasIntLiteral(val value: Long, val raw: String) : JasExpression()
data class JasFloatLiteral(val value: Double, val raw: String) : JasExpression()
data class JasStringLiteral(val text: String, val raw: Boolean = false) : JasExpression()
data class JasBoolLiteral(val value: Boolean) : JasExpression()
object JasNullLiteral : JasExpression()
data class JasIdentifier(val name: String) : JasExpression()
data class JasBinaryOp(val left: JasExpression, val op: String, val right: JasExpression) : JasExpression()
data class JasUnaryOp(val op: String, val operand: JasExpression, val prefix: Boolean = true) : JasExpression()
class JasCall(
    val target: JasExpression,
    val args: List<JasExpression>,
    val isSafe: Boolean = false
) : JasExpression() {
    var inferredTypeArgs: Map<String, JasType>? = null
}
data class JasPropertyAccess(val target: JasExpression, val property: String, val isSafe: Boolean = false) : JasExpression()
data class JasArrayAccess(val target: JasExpression, val index: JasExpression) : JasExpression()
data class JasNew(val type: JasType, val args: List<JasExpression>) : JasExpression()
data class JasArrayCreation(val type: JasType, val dims: List<JasExpression>, val init: JasArrayInit?) : JasExpression()
sealed class JasArrayInit : JasNode()
data class JasArrayInitValues(val values: List<JasExpression>) : JasArrayInit()
data class JasArrayInitLength(val length: JasExpression) : JasArrayInit()
data class JasLambdaExpr(val parameters: List<JasParameter>, val body: JasBlock, val inferredTypes: Boolean = false) : JasExpression()
data class JasMethodReference(val target: JasExpression?, val method: String, val isSuper: Boolean = false, val packageName: String? = null) : JasExpression()
data class JasInstanceOfExpr(val expression: JasExpression, val type: JasType) : JasExpression()
data class JasCastExpr(val expression: JasExpression, val type: JasType, val isSafe: Boolean = false) : JasExpression()
data class JasNullCoalescing(val left: JasExpression, val right: JasExpression) : JasExpression()
data class JasTernaryExpr(val condition: JasExpression, val thenExpr: JasExpression, val elseExpr: JasExpression) : JasExpression()
data class JasTupleExpr(val elements: List<JasExpression>) : JasExpression()
data class JasStringTemplate(val parts: List<JasTemplatePart>) : JasExpression()
sealed class JasTemplatePart : JasNode()
data class JasTemplateLiteral(val text: String) : JasTemplatePart()
data class JasTemplateExpr(val expr: JasExpression) : JasTemplatePart()
data class JasDictLiteral(val entries: List<JasDictEntry>) : JasExpression()
data class JasDictEntry(val key: JasExpression, val value: JasExpression) : JasNode()
data class JasAnnotationUse(val name: String, val arguments: List<JasAnnotationArgument>) : JasNode()
sealed class JasAnnotationArgument : JasNode()
data class JasAnnotationNamedArg(val name: String, val value: JasExpression) : JasAnnotationArgument()
data class JasAnnotationPositionalArg(val value: JasExpression) : JasAnnotationArgument()

// --- Types ---
sealed class JasType : JasNode()
data class JasNamedType(val name: String, val typeArguments: List<JasType> = emptyList()) : JasType()
data class JasPrimitiveType(val name: String) : JasType()
object JasUnitType : JasType()
object JasAnyType : JasType()
object JasStringType : JasType()
object JasBytesType : JasType()
object JasRegexType : JasType()
data class JasNullableType(val inner: JasType) : JasType()
data class JasNonNullType(val inner: JasType) : JasType()
data class JasArrayType(val inner: JasType) : JasType()
data class JasFunctionType(val paramTypes: List<JasType>, val returnType: JasType) : JasType()
data class JasWildcardType(val bound: JasType? = null, val extends: Boolean = true) : JasType()
data class JasTupleType(val types: List<JasType>) : JasType()
data class JasPointerType(val inner: JasType) : JasType()
data class JasReferenceType(val inner: JasType) : JasType()

data class JasSourceFile(
    val packageName: String?,
    val declarations: List<JasDeclaration>,
    val imports: List<JasDeclaration> = emptyList()
)
