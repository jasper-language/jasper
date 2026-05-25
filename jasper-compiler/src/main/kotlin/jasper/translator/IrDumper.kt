@file:OptIn(org.jetbrains.kotlin.ir.symbols.UnsafeDuringIrConstructionAPI::class)

package jasper.translator

import org.jetbrains.kotlin.ir.IrElement
import org.jetbrains.kotlin.ir.declarations.*
import org.jetbrains.kotlin.ir.expressions.*
import org.jetbrains.kotlin.ir.expressions.impl.IrBlockImpl
import org.jetbrains.kotlin.ir.expressions.impl.IrConstImpl
import org.jetbrains.kotlin.ir.visitors.IrElementVisitorVoid

class IrDumper {

    private val sb = StringBuilder()
    private var indent = 0

    fun dump(vararg elements: IrElement): String {
        for (element in elements) {
            element.accept(DumperVisitor(), null)
        }
        return sb.toString()
    }

    private fun line(text: String) {
        sb.append("  ".repeat(indent))
        sb.appendLine(text)
    }

    private inner class DumperVisitor : IrElementVisitorVoid {

        override fun visitElement(element: IrElement) {
            line("<${element::class.simpleName}>")
        }

        override fun visitFile(declaration: IrFile) {
            line("IrFile(package=${declaration.packageFqName}) [")
            indent++
            for (d in declaration.declarations) {
                d.accept(this, null)
            }
            indent--
            line("]")
        }

        override fun visitClass(declaration: IrClass) {
            line("IrClass(name=${declaration.name}, kind=${declaration.kind}) [")
            indent++
            for (d in declaration.declarations) {
                d.accept(this, null)
            }
            indent--
            line("]")
        }

        override fun visitSimpleFunction(declaration: IrSimpleFunction) {
            val rt = declaration.returnType
            val params = declaration.valueParameters.joinToString(", ") { "${it.name}: ${it.type}" }
            line("IrSimpleFunction(name=${declaration.name}, returnType=$rt, params=[$params])")
            indent++
            declaration.body?.accept(this, null)
            indent--
        }

        override fun visitConstructor(declaration: IrConstructor) {
            val params = declaration.valueParameters.joinToString(", ") { "${it.name}: ${it.type}" }
            line("IrConstructor(params=[$params])")
            indent++
            declaration.body?.accept(this, null)
            indent--
        }

        override fun visitField(declaration: IrField) {
            line("IrField(name=${declaration.name}, type=${declaration.type})")
        }

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

        override fun visitTypeParameter(declaration: IrTypeParameter) {
            line("IrTypeParameter(name=${declaration.name})")
        }

        override fun visitEnumEntry(declaration: IrEnumEntry) {
            line("IrEnumEntry(name=${declaration.name})")
        }

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

        override fun visitExpression(expression: IrExpression) {
            when (expression) {
                is IrConstImpl -> {
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
