package jasper.translator;

import org.jetbrains.kotlin.descriptors.SourceElement;
import org.jetbrains.kotlin.ir.declarations.*;
import org.jetbrains.kotlin.ir.declarations.impl.IrVariableImpl;
import org.jetbrains.kotlin.ir.expressions.*;
import org.jetbrains.kotlin.ir.expressions.impl.*;
import org.jetbrains.kotlin.ir.types.*;
import org.jetbrains.kotlin.ir.symbols.*;
import org.jetbrains.kotlin.ir.util.IrElementConstructorIndicator;
import org.jetbrains.kotlin.name.Name;

public class IrNodeFactory {
    private static final IrElementConstructorIndicator IND = IrElementConstructorIndicator.INSTANCE;

    public static IrCall createCall(
            int start, int end, IrType type, IrStatementOrigin origin,
            IrExpression[] valueArgs, IrType[] typeArgs,
            IrSimpleFunctionSymbol symbol, IrClassSymbol superQualifier) {
        return new IrCallImpl(IND, start, end, type, origin, valueArgs, typeArgs, symbol, superQualifier);
    }

    public static IrReturn createReturn(
            int start, int end, IrType type, IrExpression value, IrReturnTargetSymbol target) {
        return new IrReturnImpl(IND, start, end, type, value, target);
    }

    public static IrGetValue createGetValue(
            int start, int end, IrType type, IrValueSymbol symbol, IrStatementOrigin origin) {
        return new IrGetValueImpl(IND, start, end, type, symbol, origin);
    }

    public static IrSetValue createSetValue(
            int start, int end, IrType type, IrValueSymbol symbol,
            IrStatementOrigin origin, IrExpression value) {
        return new IrSetValueImpl(IND, start, end, type, symbol, origin, value);
    }

    public static IrWhen createWhen(int start, int end, IrType type, IrStatementOrigin origin) {
        return new IrWhenImpl(IND, start, end, type, origin);
    }

    public static IrBranch createBranch(int start, int end, IrExpression condition, IrExpression result) {
        return new IrBranchImpl(IND, start, end, condition, result);
    }

    public static IrBlock createBlock(int start, int end, IrType type, IrStatementOrigin origin) {
        return new IrBlockImpl(IND, start, end, type, origin);
    }

    public static IrWhileLoop createWhileLoop(int start, int end, IrType type, IrStatementOrigin origin) {
        return new IrWhileLoopImpl(IND, start, end, type, origin);
    }

    public static IrDoWhileLoop createDoWhileLoop(int start, int end, IrType type, IrStatementOrigin origin) {
        return new IrDoWhileLoopImpl(IND, start, end, type, origin);
    }

    public static IrVariable createVariable(
            int start, int end, IrDeclarationOrigin origin, Name name, IrType type,
            IrVariableSymbol symbol, boolean isVar, boolean isConst, boolean isLateinit) {
        return new IrVariableImpl(IND, start, end, origin, name, type, symbol, isVar, isConst, isLateinit);
    }

    public static IrGetField createGetField(
            int start, int end, IrType type, IrFieldSymbol symbol,
            IrClassSymbol superQualifier, IrStatementOrigin origin) {
        return new IrGetFieldImpl(IND, start, end, type, symbol, superQualifier, origin);
    }

    public static IrSetField createSetField(
            int start, int end, IrType type, IrFieldSymbol symbol,
            IrClassSymbol superQualifier, IrStatementOrigin origin) {
        return new IrSetFieldImpl(IND, start, end, type, symbol, superQualifier, origin);
    }

    public static IrConstructorCall createConstructorCall(
            int start, int end, IrType type, IrStatementOrigin origin,
            IrExpression[] valueArgs, IrType[] typeArgs,
            IrConstructorSymbol symbol, SourceElement source,
            int constructorTypeArgumentsCount) {
        return new IrConstructorCallImpl(IND, start, end, type, origin, valueArgs, typeArgs, symbol, source, constructorTypeArgumentsCount);
    }

    public static IrTry createTry(int start, int end, IrType type) {
        return new IrTryImpl(IND, start, end, type);
    }

    public static IrCatch createCatch(int start, int end, IrVariable catchParameter, IrStatementOrigin origin) {
        return new IrCatchImpl(IND, start, end, catchParameter, origin);
    }

    public static IrThrow createThrow(int start, int end, IrType type, IrExpression value) {
        return new IrThrowImpl(IND, start, end, type, value);
    }

    public static IrBreak createBreak(int start, int end, IrType type, IrLoop loop) {
        return new IrBreakImpl(IND, start, end, type, loop);
    }

    public static IrContinue createContinue(int start, int end, IrType type, IrLoop loop) {
        return new IrContinueImpl(IND, start, end, type, loop);
    }

    public static IrFunctionExpression createFunctionExpression(
            int start, int end, IrType type, IrStatementOrigin origin,
            IrSimpleFunction function) {
        return new IrFunctionExpressionImpl(IND, start, end, type, origin, function);
    }
}
