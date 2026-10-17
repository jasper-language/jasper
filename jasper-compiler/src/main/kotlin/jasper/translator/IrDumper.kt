// ============================================================================
// IrDumper —— 把 Kotlin IR 树打印成缩进文本，供调试与测试断言
// 所属层次：translator（rank 2）
// 与谁协作：被测试（如 IrDumperTest）与开发者调用，用于观察后端消费前的 IR 形态；
//   它只消费 org.jetbrains.kotlin.ir.* 数据结构，不依赖 jasper 其它层。
// 对外契约：dump(vararg IrElement): String —— 接受任意多个 IR 元素，返回可读多行文本；
//   只读遍历，不修改输入的 IR。
// 模式落点：Visitor 模式的落点。内部 DumperVisitor 隔离了「IR 节点种类」这一变化点：
//   IR 新增/调整节点类型时，只需在 DumperVisitor 里补一个 visitXxx，
//   dump 主流程与调用方无需改动。
// 已知限制：未专门处理的节点会落到 visitElement / 通用分支，只打印类名；
//   输出是给人/测试看的可读文本，不是稳定协议，断言应尽量宽松。
// ============================================================================

@file:OptIn(org.jetbrains.kotlin.ir.symbols.UnsafeDuringIrConstructionAPI::class)

package jasper.translator

import org.jetbrains.kotlin.ir.IrElement
import org.jetbrains.kotlin.ir.declarations.*
import org.jetbrains.kotlin.ir.expressions.*
import org.jetbrains.kotlin.ir.expressions.impl.IrBlockImpl
import org.jetbrains.kotlin.ir.expressions.impl.IrConstImpl
import org.jetbrains.kotlin.ir.visitors.IrElementVisitorVoid

/**
 * 做什么：IR 打印器；把 IR 元素遍历成缩进文本，输出累积在内置 StringBuilder。
 *
 * 什么时候用：调试后端，或在测试里对 IR 形状做快照/包含断言时。
 *   什么时候不该用：不要把它当作稳定的序列化协议（格式会随节点处理方式变化）；
 *   不要并发复用同一实例。
 * 前置条件：无；但每次 dump 会**追加**到已有 StringBuilder（同一实例多次 dump 会拼接）。
 * 失败模式：不抛异常；未识别的节点打印类名占位，因此输出可能不完整。
 * 为什么这样设计：用 Visitor 把「遍历」与「节点种类识别」解耦，新增节点类型只改 visitor。
 */
class IrDumper {

    // 输出缓冲与当前缩进层级；indent 由 line()/各 visit 成对自增、自减维护。
    private val sb = StringBuilder()
    private var indent = 0

    /**
     * 做什么：遍历给定的 IR 元素并返回累积的文本表示。
     *
     * 什么时候用：拿到 pipeline.ir.irFiles 后需要看结构时。
     * 前置条件：无；但结果会追加到本实例之前的输出后面。
     * 失败模式：不抛异常；未知节点只打印 `<类名>`。
     */
    fun dump(vararg elements: IrElement): String {
        for (element in elements) {
            // 每个元素交给 visitor 遍历；第二个参数 null 是 IrVisitor 的 data 形参（本实现未用）。
            element.accept(DumperVisitor(), null)
        }
        return sb.toString()
    }

    // 按当前缩进写一行：每级缩进两个空格，行尾补换行。
    private fun line(text: String) {
        sb.append("  ".repeat(indent))
        sb.appendLine(text)
    }

    // 说明：DumperVisitor 是 private inner class，不属于公共 API；仍补中文注释便于接手。
    /**
     * 做什么：把 IR 节点种类映射到打印行为的 Visitor；隔离「IR 节点种类变化」。
     *
     * 什么时候用：仅由 dump() 内部实例化，每次 dump 新建以保证缩进状态互不干扰。
     * 前置条件：必须遵守「进入子节点前 indent++、之后 indent--」的成对约定维护缩进。
     * 失败模式：不抛异常；未 override 的节点走 visitElement 兜底打印类名。
     */
    private inner class DumperVisitor : IrElementVisitorVoid {

        /**
         * 做什么：所有未被专门处理的 IR 元素的兜底打印，只输出 `<类名>`。
         */
        override fun visitElement(element: IrElement) {
            line("<${element::class.simpleName}>")
        }

        /**
         * 做什么：打印一个 IR 文件（包名 + 声明列表），对应一个顶层编译单元。
         */
        override fun visitFile(declaration: IrFile) {
            line("IrFile(package=${declaration.packageFqName}) [")
            indent++
            for (d in declaration.declarations) {
                d.accept(this, null)
            }
            indent--
            line("]")
        }

        /**
         * 做什么：打印类声明：名字、kind 及其成员列表。
         */
        override fun visitClass(declaration: IrClass) {
            line("IrClass(name=${declaration.name}, kind=${declaration.kind}) [")
            indent++
            for (d in declaration.declarations) {
                d.accept(this, null)
            }
            indent--
            line("]")
        }

        /**
         * 做什么：打印普通函数：名字、返回类型、形参；再缩进打印函数体。
         */
        override fun visitSimpleFunction(declaration: IrSimpleFunction) {
            val rt = declaration.returnType
            val params = declaration.valueParameters.joinToString(", ") { "${it.name}: ${it.type}" }
            line("IrSimpleFunction(name=${declaration.name}, returnType=$rt, params=[$params])")
            indent++
            declaration.body?.accept(this, null)
            indent--
        }

        /**
         * 做什么：打印构造器：形参列表；再缩进打印函数体。
         */
        override fun visitConstructor(declaration: IrConstructor) {
            val params = declaration.valueParameters.joinToString(", ") { "${it.name}: ${it.type}" }
            line("IrConstructor(params=[$params])")
            indent++
            declaration.body?.accept(this, null)
            indent--
        }

        /**
         * 做什么：打印字段：名字与类型（无子节点）。
         */
        override fun visitField(declaration: IrField) {
            line("IrField(name=${declaration.name}, type=${declaration.type})")
        }

        /**
         * 做什么：打印属性：名字，以及（若有）后备字段、getter、setter。
         */
        override fun visitProperty(declaration: IrProperty) {
            line("IrProperty(name=${declaration.name}) [")
            indent++
            if (declaration.backingField != null) {
                line("backingField:")
                indent++
                declaration.backingField?.accept(this, null)
                indent--
            }
            if (declaration.getter != null) {
                declaration.getter?.accept(this, null)
            }
            if (declaration.setter != null) {
                declaration.setter?.accept(this, null)
            }
            indent--
            line("]")
        }

        /**
         * 做什么：打印类型参数名。对应哪条语法：声明处的 `<T>` 形参。
         */
        override fun visitTypeParameter(declaration: IrTypeParameter) {
            line("IrTypeParameter(name=${declaration.name})")
        }

        /**
         * 做什么：打印枚举项（enum entry）的名字。
         */
        override fun visitEnumEntry(declaration: IrEnumEntry) {
            line("IrEnumEntry(name=${declaration.name})")
        }

        /**
         * 做什么：打印函数体；IrBlockBody 逐语句缩进打印，其它 body 只打印类名占位。
         */
        override fun visitBody(body: IrBody) {
            if (body is IrBlockBody) {
                line("IrBlockBody [")
                indent++
                for (s in body.statements) {
                    s.accept(this, null)
                }
                indent--
                line("]")
            } else {
                line("<IrBody: ${body::class.simpleName}>")
            }
        }

        /**
         * 做什么：表达式打印的总分派入口；按具体 IrExpression 子类选择文本形态
         *   （常量 / 读写变量 / 调用 / 控制流 / 类型算子等），未识别者打印类名占位。
         */
        override fun visitExpression(expression: IrExpression) {
            when (expression) {
                is IrConstImpl -> {
                    // 常量按 kind 决定后缀（L/f/d）与引号，尽量还原字面量外观。
                    when {
                        expression.kind == IrConstKind.Null -> line("IrConst(null)")
                        expression.kind == IrConstKind.Boolean -> line("IrConst(${expression.value})")
                        expression.kind == IrConstKind.Int -> line("IrConst(${expression.value})")
                        expression.kind == IrConstKind.Long -> line("IrConst(${expression.value}L)")
                        expression.kind == IrConstKind.Float -> line("IrConst(${expression.value}f)")
                        expression.kind == IrConstKind.Double -> line("IrConst(${expression.value}d)")
                        expression.kind == IrConstKind.String -> line("IrConst(\"${expression.value}\")")
                        else -> line("IrConst(${expression.value})")
                    }
                }
                is IrGetValue -> line("IrGetValue(symbol=${expression.symbol})")
                is IrSetValue -> {
                    line("IrSetValue(symbol=${expression.symbol}) [")
                    indent++
                    expression.value?.accept(this, null)
                    indent--
                    line("]")
                }
                is IrCall -> {
                    val args = expression.valueArgumentsCount
                    line("IrCall(symbol=${expression.symbol}, args=$args)")
                }
                is IrConstructorCall -> {
                    val args = expression.valueArgumentsCount
                    line("IrConstructorCall(symbol=${expression.symbol}, args=$args)")
                }
                is IrReturn -> {
                    line("IrReturn [")
                    indent++
                    expression.value?.accept(this, null)
                    indent--
                    line("]")
                }
                is IrThrow -> {
                    line("IrThrow [")
                    indent++
                    expression.value?.accept(this, null)
                    indent--
                    line("]")
                }
                is IrWhen -> {
                    line("IrWhen(branches=${expression.branches.size}) [")
                    indent++
                    for (b in expression.branches) {
                        b.accept(this, null)
                    }
                    indent--
                    line("]")
                }
                is IrBranch -> {
                    line("IrBranch [")
                    indent++
                    line("condition:")
                    indent++
                    expression.condition.accept(this, null)
                    indent--
                    line("result:")
                    indent++
                    expression.result.accept(this, null)
                    indent--
                    line("]")
                    indent--
                }
                is IrBlockImpl -> {
                    line("IrBlock(origin=${expression.origin}) [")
                    indent++
                    for (s in expression.statements) {
                        s.accept(this, null)
                    }
                    indent--
                    line("]")
                }
                is IrGetField -> line("IrGetField(symbol=${expression.symbol})")
                is IrSetField -> {
                    line("IrSetField(symbol=${expression.symbol}) [")
                    indent++
                    expression.value?.accept(this, null)
                    indent--
                    line("]")
                }
                is IrWhileLoop -> {
                    line("IrWhileLoop [")
                    indent++
                    line("condition:")
                    indent++
                    expression.condition?.accept(this, null)
                    indent--
                    line("body:")
                    indent++
                    expression.body?.accept(this, null)
                    indent--
                    line("]")
                    indent--
                }
                is IrDoWhileLoop -> {
                    line("IrDoWhileLoop [")
                    indent++
                    line("body:")
                    indent++
                    expression.body?.accept(this, null)
                    indent--
                    line("condition:")
                    indent++
                    expression.condition?.accept(this, null)
                    indent--
                    line("]")
                    indent--
                }
                is IrTry -> {
                    line("IrTry [")
                    indent++
                    line("tryBlock:")
                    indent++
                    expression.tryResult?.accept(this, null)
                    indent--
                    for (c in expression.catches) {
                        c.accept(this, null)
                    }
                    if (expression.finallyExpression != null) {
                        line("finally:")
                        indent++
                        expression.finallyExpression?.accept(this, null)
                        indent--
                    }
                    line("]")
                    indent--
                }
                is IrCatch -> {
                    line("IrCatch [")
                    indent++
                    line("param: ${expression.catchParameter.name}")
                    indent++
                    expression.result?.accept(this, null)
                    indent--
                    line("]")
                    indent--
                }
                is IrBreak -> line("IrBreak")
                is IrContinue -> line("IrContinue")
                is IrVariable -> {
                    // 变量表达式：有初值时先打印 ` = ...` 占位再缩进打印初值。
                    val init = if (expression.initializer != null) " = ..." else ""
                    line("IrVariable(name=${expression.name}, type=${expression.type})$init")
                    if (expression.initializer != null) {
                        indent++
                        expression.initializer?.accept(this, null)
                        indent--
                    }
                }
                is IrFunctionExpression -> {
                    line("IrFunctionExpression [")
                    indent++
                    expression.function.accept(this, null)
                    indent--
                    line("]")
                }
                is IrTypeOperatorCall -> {
                    line("IrTypeOperatorCall(operator=${expression.operator}, typeOperand=${expression.typeOperand}) [")
                    indent++
                    expression.argument.accept(this, null)
                    indent--
                    line("]")
                }
                else -> line("<IrExpression: ${expression::class.simpleName}>")
            }
        }

        /**
         * 做什么：打印局部变量声明：名字、类型；有初值则缩进打印初值。
         */
        override fun visitVariable(declaration: IrVariable) {
            val init = if (declaration.initializer != null) " = ..." else ""
            line("IrVariable(name=${declaration.name}, type=${declaration.type})$init")
            if (declaration.initializer != null) {
                indent++
                declaration.initializer?.accept(this, null)
                indent--
            }
        }
    }
}
