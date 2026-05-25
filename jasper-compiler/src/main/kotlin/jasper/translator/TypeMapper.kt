package jasper.translator

import jasper.ast.*
import org.jetbrains.kotlin.ir.types.*
import org.jetbrains.kotlin.ir.types.impl.*
import org.jetbrains.kotlin.ir.symbols.*
import org.jetbrains.kotlin.ir.symbols.impl.*
import org.jetbrains.kotlin.ir.expressions.impl.IrConstImpl
import org.jetbrains.kotlin.ir.expressions.IrExpression
import org.jetbrains.kotlin.ir.declarations.*
import org.jetbrains.kotlin.types.Variance

class TypeMapper {

    var currentPkg: String = ""

    private val symbols = mutableMapOf<String, IrClassSymbol>()
    private val symbolToName = mutableMapOf<IrClassifierSymbol, String>()

    fun irTypeClassName(type: IrType): String? {
        if (type is IrSimpleType) {
            return symbolToName[type.classifier]
        }
        return null
    }

    private fun classNameToJvmDescriptor(name: String): String {
        if (name.startsWith("[")) return name
        val mapped = builtinTypes[name.lowercase()] ?: name
        return jvmDescriptor(mapped)
    }

    fun jvmDescriptorFromIrType(type: IrType): String {
        val name = irTypeClassName(type)
        if (name != null) {
            return classNameToJvmDescriptor(name)
        }
        return "Ljava/lang/Object;"
    }

    fun jvmReturnDescriptorFromIrType(type: IrType): String {
        val d = jvmDescriptorFromIrType(type)
        return if (d == "V") "V" else d
    }

    fun jvmInternalNameFromIrType(type: IrType): String {
        val name = irTypeClassName(type)
        if (name != null) {
            return if (name.startsWith("[")) name else jvmInternalName(builtinTypes[name.lowercase()] ?: name)
        }
        return "java/lang/Object"
    }

    private val builtinTypes = mapOf(
        "int32" to "kotlin.Int",
        "int64" to "kotlin.Long",
        "float32" to "kotlin.Float",
        "float64" to "kotlin.Double",
        "bool" to "kotlin.Boolean",
        "boolean" to "kotlin.Boolean",
        "string" to "kotlin.String",
        "void" to "kotlin.Unit",
        "byte" to "kotlin.Byte",
        "short" to "kotlin.Short",
        "char" to "kotlin.Char"
    )

    private val jvmDescriptors = mapOf(
        "kotlin.Int" to "I",
        "kotlin.Long" to "J",
        "kotlin.Float" to "F",
        "kotlin.Double" to "D",
        "kotlin.Boolean" to "Z",
        "kotlin.Byte" to "B",
        "kotlin.Short" to "S",
        "kotlin.Char" to "C",
        "kotlin.String" to "Ljava/lang/String;",
        "kotlin.Unit" to "V"
    )

    private val jvmInternalNames = mapOf(
        "kotlin.Int" to "int",
        "kotlin.Long" to "long",
        "kotlin.Float" to "float",
        "kotlin.Double" to "double",
        "kotlin.Boolean" to "boolean",
        "kotlin.Byte" to "byte",
        "kotlin.Short" to "short",
        "kotlin.Char" to "char",
        "kotlin.String" to "java/lang/String",
        "kotlin.Unit" to "void"
    )

    fun type(name: String): IrType {
        val symbol = symbols.getOrPut(name) { IrClassSymbolImpl().also { symbolToName[it] = name } }
        return IrSimpleTypeImpl(symbol, false, emptyList(), emptyList(), null)
    }

    fun typeFromSymbol(symbol: IrClassifierSymbol): IrType {
        return IrSimpleTypeImpl(symbol, false, emptyList(), emptyList(), null)
    }

    fun nullable(inner: IrType): IrType {
        if (inner is IrSimpleTypeImpl) {
            return IrSimpleTypeImpl(
                inner.classifier, true,
                inner.arguments, inner.annotations, inner.abbreviation
            )
        }
        return inner
    }

    fun nonNull(inner: IrType): IrType {
        if (inner is IrSimpleTypeImpl) {
            return IrSimpleTypeImpl(
                inner.classifier, false,
                inner.arguments, inner.annotations, inner.abbreviation
            )
        }
        return inner
    }

    fun mapType(type: JasType?): IrType {
        if (type == null) return unitType()
        return when (type) {
            is JasPrimitiveType -> {
                val jvmName = builtinTypes[type.name.lowercase()] ?: type.name
                type(jvmName)
            }
            is JasNamedType -> {
                val rawName = type.name
                val builtin = builtinTypes[rawName.lowercase()]
                val jvmName = builtin ?: (
                    if (currentPkg.isNotEmpty() && !isLikelyTypeParam(rawName) && !rawName.contains('.') && !rawName.contains('/'))
                        "$currentPkg/$rawName"
                    else rawName
                    )
                val base = type(jvmName)
                if (type.typeArguments.isEmpty()) {
                    base
                } else {
                    IrSimpleTypeImpl(
                        (base as IrSimpleType).classifier,
                        false,
                        mapTypeArguments(type.typeArguments),
                        emptyList(),
                        null
                    )
                }
            }
            is JasUnitType -> unitType()
            is JasStringType -> stringType()
            is JasAnyType -> type("kotlin.Any")
            is JasBytesType -> type("kotlin.ByteArray")
            is JasRegexType -> type("kotlin.text.Regex")
            is JasNullableType -> nullable(mapType(type.inner))
            is JasNonNullType -> nonNull(mapType(type.inner))
            is JasArrayType -> arrayType(mapType(type.inner))
            is JasFunctionType -> type("FunctionType")
            is JasWildcardType -> {
                if (type.bound != null) mapType(type.bound) else type("kotlin.Any")
            }
            is JasPointerType -> type("kotlin.Long")
            is JasReferenceType -> type("kotlin.Any")
            is JasTupleType -> type("TupleType")
            else -> unitType()
        }
    }

    private fun isLikelyTypeParam(name: String): Boolean {
        return name.length == 1 && name[0].isUpperCase()
    }

    fun makeTypeProjection(type: IrType, variance: Variance = Variance.INVARIANT): IrTypeArgument {
        // Kotlin 2.1.0 made IrTypeProjectionImpl internal; use anonymous object instead
        return object : IrTypeProjection {
            override val variance: Variance get() = variance
            override val type: IrType get() = type
            override fun equals(other: Any?): Boolean =
                other is IrTypeProjection && other.variance == variance && other.type == type
            override fun hashCode(): Int = variance.hashCode() * 31 + type.hashCode()
        }
    }

    private fun makeStarProjection(): IrTypeArgument {
        return object : IrTypeProjection {
            override val variance: Variance get() = Variance.INVARIANT
            override val type: IrType get() = type("kotlin.Any")
            override fun equals(other: Any?): Boolean = other is IrStarProjection
            override fun hashCode(): Int = java.lang.System.identityHashCode(this)
        }
    }

    private fun mapTypeArguments(args: List<JasType>): List<IrTypeArgument> {
        return args.map { arg ->
            when (arg) {
                is JasWildcardType -> {
                    val boundType = if (arg.bound != null) mapType(arg.bound) else type("kotlin.Any")
                    makeTypeProjection(
                        boundType,
                        if (arg.extends) Variance.OUT_VARIANCE else Variance.IN_VARIANCE
                    )
                }
                is JasNamedType -> {
                    makeTypeProjection(
                        mapType(arg),
                        Variance.INVARIANT
                    )
                }
                else -> makeStarProjection()
            }
        }
    }

    fun arrayType(inner: IrType): IrType {
        val innerName = irTypeClassName(inner)
        val jvmDesc = if (innerName != null) jvmDescriptor(innerName) else "Ljava/lang/Object;"
        val arrayJvmName = if (jvmDesc.length == 1) "[$jvmDesc" else "[$jvmDesc"
        return type(arrayJvmName)
    }

    fun mapTypeName(name: String): IrType {
        val jvmName = builtinTypes[name] ?: name
        return type(jvmName)
    }

    fun jvmDescriptor(typeName: String): String {
        if (typeName.startsWith("[")) return typeName
        return jvmDescriptors[typeName] ?: "L${typeName.replace('.', '/')};"
    }

    fun jvmDescriptorFromType(type: JasType?): String {
        if (type == null) return "V"
        return when (type) {
            is JasNamedType -> {
                val mapped = builtinTypes[type.name.lowercase()] ?: type.name
                jvmDescriptor(mapped)
            }
            is JasPrimitiveType -> {
                val mapped = builtinTypes[type.name.lowercase()] ?: type.name
                jvmDescriptor(mapped)
            }
            is JasUnitType -> "V"
            is JasStringType -> "Ljava/lang/String;"
            is JasAnyType -> "Ljava/lang/Object;"
            is JasArrayType -> "[" + jvmDescriptorFromType(type.inner)
            is JasNullableType -> jvmDescriptorFromType(type.inner)
            is JasNonNullType -> jvmDescriptorFromType(type.inner)
            is JasFunctionType -> "Ljava/lang/Object;"
            is JasTupleType -> "Ljava/lang/Object;"
            is JasWildcardType -> {
                if (type.bound != null) jvmDescriptorFromType(type.bound) else "Ljava/lang/Object;"
            }
            else -> "Ljava/lang/Object;"
        }
    }

    fun jvmInternalName(typeName: String): String {
        if (typeName.startsWith("[")) return typeName
        return jvmInternalNames[typeName] ?: typeName.replace('.', '/')
    }

    fun jvmInternalNameFromType(type: JasType?): String {
        if (type == null) return "void"
        return when (type) {
            is JasNamedType -> {
                val mapped = builtinTypes[type.name.lowercase()] ?: type.name
                jvmInternalName(mapped)
            }
            is JasPrimitiveType -> {
                val mapped = builtinTypes[type.name.lowercase()] ?: type.name
                jvmInternalName(mapped)
            }
            is JasUnitType -> "void"
            is JasStringType -> "java/lang/String"
            is JasAnyType -> "java/lang/Object"
            is JasArrayType -> "java/lang/Object"
            is JasNullableType -> jvmInternalNameFromType(type.inner)
            is JasNonNullType -> jvmInternalNameFromType(type.inner)
            is JasFunctionType -> "java/lang/Object"
            is JasTupleType -> "java/lang/Object"
            is JasWildcardType -> {
                if (type.bound != null) jvmInternalNameFromType(type.bound) else "java/lang/Object"
            }
            else -> "java/lang/Object"
        }
    }

    fun jvmReturnDescriptor(type: JasType?): String {
        val d = jvmDescriptorFromType(type)
        return if (d == "V") "V" else d
    }

    fun katakana(jasName: String): String {
        return builtinTypes[jasName] ?: jasName
    }

    fun unitType(): IrType = type("kotlin.Unit")
    fun intType(): IrType = type("kotlin.Int")
    fun longType(): IrType = type("kotlin.Long")
    fun floatType(): IrType = type("kotlin.Float")
    fun doubleType(): IrType = type("kotlin.Double")
    fun boolType(): IrType = type("kotlin.Boolean")
    fun stringType(): IrType = type("kotlin.String")

    fun unitExpr(): IrExpression = IrConstImpl.constNull(0, 0, unitType())
    fun trueConst(): IrExpression = IrConstImpl.constTrue(0, 0, boolType())
    fun falseConst(): IrExpression = IrConstImpl.constFalse(0, 0, boolType())

    fun intConst(value: Int): IrExpression = IrConstImpl.int(0, 0, intType(), value)
    fun longConst(value: Long): IrExpression = IrConstImpl.long(0, 0, longType(), value)
    fun floatConst(value: Float): IrExpression = IrConstImpl.float(0, 0, floatType(), value)
    fun doubleConst(value: Double): IrExpression = IrConstImpl.double(0, 0, doubleType(), value)
    fun stringConst(value: String): IrExpression = IrConstImpl.string(0, 0, stringType(), value)
    fun boolConst(value: Boolean): IrExpression = if (value) trueConst() else falseConst()
    fun nullConst(): IrExpression = IrConstImpl.constNull(0, 0, nullable(type("kotlin.Any")))

    // ── JVM Signature utilities ──

    @OptIn(org.jetbrains.kotlin.ir.symbols.UnsafeDuringIrConstructionAPI::class)
    fun irTypeSignature(type: IrType): String {
        if (type is IrSimpleType) {
            val classifier = type.classifier
            if (classifier is IrTypeParameterSymbol) {
                val name = classifier.owner.name.toString()
                return "T$name;"
            }
            val name = symbolToName[classifier]
            if (name != null) {
                val mapped = builtinTypes[name.lowercase()] ?: name
                if (mapped.startsWith("[")) return mapped
                val desc = jvmDescriptor(mapped)
                if (desc.startsWith("L") && desc.endsWith(";")) {
                    val internal = desc.substring(1, desc.length - 1)
                    if (type.arguments.isEmpty()) {
                        return "L$internal;"
                    } else {
                        val args = type.arguments.joinToString("") { irTypeArgumentSignature(it) }
                        return "L$internal<$args>;"
                    }
                }
                return desc
            }
        }
        return "Ljava/lang/Object;"
    }

    fun irTypeArgumentSignature(arg: IrTypeArgument): String {
        return when (arg) {
            is IrStarProjection -> "*"
            is IrTypeProjection -> {
                val prefix = when (arg.variance) {
                    Variance.OUT_VARIANCE -> "+"
                    Variance.IN_VARIANCE -> "-"
                    else -> ""
                }
                "$prefix${irTypeSignature(arg.type)}"
            }
            else -> "*"
        }
    }

    fun irTypeParameterSignature(tp: IrTypeParameter): String {
        val sb = StringBuilder()
        sb.append(tp.name.toString())
        sb.append(':')
        if (tp.superTypes.isNotEmpty()) {
            sb.append(irTypeSignature(tp.superTypes.first()))
        } else {
            sb.append("Ljava/lang/Object;")
        }
        return sb.toString()
    }

    fun buildClassSignature(cls: IrClass, superClassInternal: String): String? {
        if (cls.typeParameters.isEmpty()) return null
        val sb = StringBuilder()
        sb.append('<')
        for (tp in cls.typeParameters) {
            sb.append(irTypeParameterSignature(tp))
        }
        sb.append('>')
        sb.append('L')
        sb.append(superClassInternal)
        sb.append(';')
        return sb.toString()
    }

    fun buildMethodSignature(func: IrSimpleFunction): String? {
        if (func.typeParameters.isEmpty()) return null
        val sb = StringBuilder()
        sb.append('<')
        for (tp in func.typeParameters) {
            sb.append(irTypeParameterSignature(tp))
        }
        sb.append('>')
        sb.append('(')
        for (p in func.valueParameters) {
            sb.append(irTypeSignature(p.type))
        }
        sb.append(')')
        sb.append(irTypeSignature(func.returnType))
        return sb.toString()
    }

    fun buildFieldSignature(field: IrField): String? {
        val type = field.type
        if (type is IrSimpleType && type.arguments.isNotEmpty()) {
            return irTypeSignature(type)
        }
        return null
    }

    fun hasGenericParams(func: IrSimpleFunction): Boolean {
        val retType = func.returnType
        if (retType is IrSimpleType && retType.arguments.isNotEmpty()) return true
        for (p in func.valueParameters) {
            val paramType = p.type
            if (paramType is IrSimpleType && paramType.arguments.isNotEmpty()) return true
        }
        return false
    }

}
