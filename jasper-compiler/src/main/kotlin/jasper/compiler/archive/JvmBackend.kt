// ============================================================================
// JvmBackend —— 把 Jasper AST 直接翻译成 JVM 字节码并写出 .class 文件
// 所属层次：compiler（编排层，rank 3）内的 backend 组件；物理位置在
//           jasper.compiler（archive 子目录）。职责只做「AST → 字节码」，
//           不做词法/语法/语义分析，也不编排编译流水线。
// 与谁协作：
//   上游（本应为）compiler 的编排代码：本类对外只有一个入口
//     generate(packageName, declarations, outputDir)，调用方需先把 AST
//     （List<JasDeclaration>）准备好并按包名给出输出目录。
//   下游：ASM 库（org.jetbrains.org.objectweb.asm 的 ClassWriter/MethodVisitor）
//     负责 class 文件结构，文件系统（java.io.File / java.nio.file.Path）负责落盘。
//   类型描述符：一律委托 jasper.translator.TypeMapper，把 JasType 落成 JVM
//     描述符/内部名；本文件只决定「要不要写泛型签名」并拼描述符字符串。
// 对外契约（稳定接口）：
//   generate(packageName: String?, declarations: List<JasDeclaration>, outputDir: Path)：
//     在 outputDir/<pkg>/ 下为每个类/接口/枚举/注解/顶层函数写一个 <Name>.class；
//     无返回值，失败以异常向上抛。本类持有可变全局状态（registry、lambda 计数器、
//     当前 ClassWriter 等），**不可并发/重入**调用。
// 扩展点 / 隔离的变化点：
//   - 新增 AST 节点：本文件用 when 分派（generateStatement / generateExpression /
//     collectIdentifiers / inferExprDesc 等），这些 when 都带 `else -> {}` 兜底；
//     新增节点必须在这些 when 里补齐分支，否则会被静默忽略。
//   - 新增类型：一律走 TypeMapper，本文件不维护类型表。
//   - 换 JVM 目标版本：只改各 cw.visit 的第一个参数 Opcodes.V1_8。
// 已知限制（重要）：
//   - **疑似遗留代码**：当前主流水线（JasperCompiler → translator/JvmIrBackend）中
//     没有任何地方 `new JvmBackend`；只有 test 源集（JvmBackendTest.kt）与
//     TypeMapper 注释提到它。它与 compiler/JvmIrBackend 功能重叠（都是
//     「AST/IR → ASM 字节码」），是被 JvmIrBackend 取代的早期后端，保留在 archive/
//     下仅供参考，不应被新调用点依赖。
//   - 不做诊断恢复：不支持的形态被静默兜底（退化成 java/lang/Object 或 null），
//     另有依赖 `!!`、下标、Class.forName、强制类型转换的路径会抛运行时异常。
//   - 大量语义简化（详见各函数行内注释）：try/finally 不覆盖异常路径、for-in 的
//     continue 会跳过自增、catch 分支按顺序执行而非互斥、float 字面量与推断出的
//     描述符不一致等。
// ============================================================================

package jasper.compiler

import jasper.ast.*
import jasper.translator.TypeMapper
import org.jetbrains.org.objectweb.asm.*
import java.io.File
import java.nio.file.Path

/**
 * 做什么：JVM 字节码后端（Strategy 模式里「JVM 目标」的实现）。把 Jasper AST 逐声明
 *   翻译成 ASM 可写出的字节码，并按包名落盘为 .class 文件。
 *
 * 什么时候用：本类是 archive 下的**早期后端，当前未被主编译流水线引用**（main 里没有
 *   任何 `new JvmBackend`）；主流水线已改用 compiler/JvmIrBackend（IR → 字节码）。
 *   仅在单独驱动（例如旧编排代码/测试）显式构造并调用时才有意义。
 *   什么时候不该用：不要把它接入新流水线——它与 JvmIrBackend 职责重叠。
 *
 * 前置条件：typeMapper 负责 JasType → JVM 描述符/内部名；outputDir 必须可写，
 *   包目录不存在时会 mkdirs()。本类持有可变全局状态（函数/方法/字段注册表、
 *   lambda 计数器、当前 ClassWriter/内部名），**不可并发/重入**调用。
 *
 * 失败模式：不做诊断恢复——不认识的 AST 形态被 when 的 `else` 静默丢弃；描述符/
 *   字段查不到时退化为 java/lang/Object；另有一批依赖 `!!`、下标、Class.forName、
 *   强制类型转换的路径会抛运行时异常，由调用方承担。
 *
 * 为什么这样设计：把 JVM 细节收口在本文件，只依赖 TypeMapper 一处类型映射；AST 节点
 *   用 when 穷举分派而非 Visitor，希望在一处读全「哪种 AST 形态对应哪些指令」的映射。
 */
class JvmBackend(private val typeMapper: TypeMapper = TypeMapper()) {

    /**
     * 做什么：局部变量/参数的槽位登记项，记录名字、Jasper 类型与 JVM 局部变量槽号。
     * 什么时候用：generate* 系列分配槽位时把它加进 ctx.locals，后续按名字查类型
     *   （推断描述符、选 ILOAD/FLOAD/ALOAD 等）。
     * 失败模式：type 可为 null（如 this、match/or-pattern 的临时绑定），此时按
     *   Object 处理。同名槽位在本文件的保守分配策略下只增不减（不回收作用域）。
     */
    data class LocalInfo(val name: String, val type: JasType?, val slot: Int)

    // 待生成的 lambda 合成方法描述。ASM 只允许在 visitEnd 之前 visitMethod，而 lambda
    // 体里可能再嵌 lambda，故先把描述入队，再由各 generate* 的 while 循环逐个生成
    // （见 generateLambdaSyntheticMethod）。captured 记录被闭包捕获的外层变量名。
    private data class PendingLambda(
        val name: String,
        val parameters: List<JasParameter>,
        val body: JasBlock,
        val returnType: JasType?,
        val captured: List<String>
    )

    // 当前正在写的类（visitEnd 前为 null，生成结束后复位）；lambda/metafactory 取 owner 时用。
    private var currentClassWriter: ClassWriter? = null
    // 当前类的 JVM 内部名（形如 pkg/Name）；lambda 合成方法的 owner 优先用它。
    private var currentInternalName: String? = null
    // 每个类内 lambda 的编号，用于生成唯一的合成方法名 lambda$N（每类重置）。
    private var lambdaCounter = 0
    // 本类待生成的 lambda 合成方法队列（见 PendingLambda）。
    private val pendingLambdaMethods = mutableListOf<PendingLambda>()
    // 当前包名（点已换成 '/'）；用于把未限定的名字补成同包成员。
    private var currentPkg: String = ""

    // 本编译单元所有顶层函数，按名字索引；解析静态调用的目标与描述符时用。
    private val functionRegistry = mutableMapOf<String, JasFunction>()
    // JVM 内部名 → (方法名 → JasFunction)；解析虚调用的 owner 与方法描述符时用。
    private val methodRegistry = mutableMapOf<String, MutableMap<String, JasFunction>>()
    // 字段名 → 描述符；访问字段时回填，查不到时兜底为 Ljava/lang/Object;。
    // 注意它**按名字而非按类**索引，跨类同名字段会互相覆盖。
    private val fieldDescriptorMap = mutableMapOf<String, String>()

    /**
     * 做什么：主入口。按包名建目录；第一遍登记所有顶层函数（供跨文件静态调用解析），
     *   第二遍按声明种类分派到 generateClass / generateInterface / generateEnum /
     *   generateAnnotationType / generateMainClass，为每个声明写一个 .class。
     *
     * 什么时候用：由调用方（早期编排代码/测试）在 AST 就绪后调用一次。
     *   前置条件：declarations 里的名字需在其包内唯一；outputDir 可写。
     * 失败模式：不做恢复——写盘失败抛 IOException；重复调用会先清空本类的全部可变
     *   状态（见函数体开头的 reset），因此不可并发。
     * 边界：顶层 JasFunction 被当成一个「单方法类」生成（generateMainClass），
     *   而不是包级静态函数集合里的一个成员；未识别的声明种类被 when 静默忽略。
     */
    fun generate(
        packageName: String?,
        declarations: List<JasDeclaration>,
        outputDir: Path
    ) {
        // 重置全部跨调用状态：本类不是为并发/重入设计的，每次 generate 必须是干净起点。
        currentPkg = (packageName ?: "").replace('.', '/')
        currentClassWriter = null
        currentInternalName = null
        lambdaCounter = 0
        pendingLambdaMethods.clear()
        functionRegistry.clear()
        methodRegistry.clear()
        fieldDescriptorMap.clear()
        val dir = outputDir.resolve(currentPkg).toFile()
        dir.mkdirs()

        // 第一遍：先把所有顶层函数登记进 functionRegistry，第二遍生成时才能解析
        // 跨声明的静态调用（名字 → 参数/返回描述符）。
        for (decl in declarations) {
            if (decl is JasFunction) {
                functionRegistry[decl.name] = decl
            }
        }

        // 第二遍：按声明种类分派。每种声明各写一个 .class；未列出的种类不产出文件。
        for (decl in declarations) {
            when (decl) {
                is JasClass -> generateClass(decl, currentPkg, dir)
                is JasInterface -> generateInterface(decl, currentPkg, dir)
                is JasEnum -> generateEnum(decl, currentPkg, dir)
                is JasAnnotationType -> generateAnnotationType(decl, currentPkg, dir)
                is JasFunction -> generateMainClass(decl, currentPkg, dir)
                else -> {}
            }
        }
    }

    // 生成一个普通类：内部名/超类/接口 → visit → 成员（字段+合成 getter/setter、方法）
    // → 构造器 → 排队的 lambda 合成方法 → visitEnd 落盘。
    // 为什么用 COMPUTE_FRAMES：栈映射帧与 max_locals/max_stack 交给 ASM 计算，本文件不手工
    //   维护 StackMapTable；代价是 ASM 需能解析超类（否则会退化成默认父类推断）。
    // 为什么用 V1_8：class 文件版本 52，取 Java 8 作为下限以兼容 Android/D8；
    //   V1_8 数值即 52。不手工写帧则 V1_8 是能被旧工具链接受的安全选择。
    private fun generateClass(cls: JasClass, pkg: String, dir: File) {
        // 内部名用 '/' 分隔；TypeMapper 把类型名（点号）翻成内部名（斜杠）。
        val internalName = "$pkg/${cls.name}"
        // 超类缺失时 TypeMapper 返回 "void"，这里兜底成 java/lang/Object（否则 class 非法）。
        val superName = typeMapper.jvmInternalNameFromType(cls.superclass)
            .let { if (it == "void") "java/lang/Object" else it }
        val interfaces = cls.interfaces.map {
            typeMapper.jvmInternalNameFromType(it)
        }.toTypedArray()

        // COMPUTE_FRAMES：让 ASM 顺带算 maxs 与帧。详见函数头注释。
        val cw = ClassWriter(ClassWriter.COMPUTE_FRAMES)
        currentClassWriter = cw
        currentInternalName = internalName
        // lambda 编号按类重置，保证每类内 lambda$N 唯一即可（跨类可重名）。
        lambdaCounter = 0
        pendingLambdaMethods.clear()
        fieldDescriptorMap.clear()
        // 登记本类的方法表，供虚调用解析 owner 与描述符。
        methodRegistry[internalName] = mutableMapOf()

        // 只有带类型参数时才写泛型 Signature；非泛型类写 null 避免多余常量池项。
        val classSignature = if (cls.typeParameters.isNotEmpty()) {
            jvmClassSignature(cls.typeParameters, cls.superclass)
        } else null

        // 源码版本、访问标志（PUBLIC|SUPER）、内部名、可选泛型签名、超类、接口。
        cw.visit(
            Opcodes.V1_8,
            Opcodes.ACC_PUBLIC or Opcodes.ACC_SUPER,
            internalName,
            classSignature,
            superName,
            interfaces
        )

        // 成员顺序不影响语义；属性既生成字段也生成合成访问器（JasProperty 本身不带
        // 方法体时 getter/setter 直接 GETFIELD/PUTFIELD）。
        for (member in cls.members) {
            when (member) {
                is JasProperty -> {
                    generateField(cw, member)
                    generateSyntheticAccessors(cw, member, internalName)
                }
                is JasFunction -> {
                    methodRegistry[internalName]?.put(member.name, member)
                    generateMethod(cw, member, internalName, false)
                }
                else -> {}
            }
        }
        // 构造器单独遍历：不能作为普通成员方法生成（<init> 需先调用 super 构造）。
        for (ctor in cls.constructors) {
            generateConstructor(cw, ctor, internalName)
        }

        // 生成排队中的 lambda 合成方法。用 while + index（而非 for）是因为生成一个 lambda
        // 的过程中可能又向队列追加新的 lambda，必须能把新增项一并处理掉。
        var index = 0
        while (index < pendingLambdaMethods.size) {
            generateLambdaSyntheticMethod(cw, pendingLambdaMethods[index], internalName)
            index++
        }

        cw.visitEnd()
        // 复位“当前类”状态，避免影响下一个类（lambda 的 owner 解析依赖它）。
        currentClassWriter = null
        currentInternalName = null
        dir.resolve("${cls.name}.class").writeBytes(cw.toByteArray())
    }

    // 生成接口：只写抽象方法（无 Code 属性），用 PUBLIC|INTERFACE|ABSTRACT 标志。
    // 边界：方法体一律丢弃（接口方法在早期语义里没有 default 实现），因此只 visit
    //   方法签名后立即 visitEnd；带类型参数时写泛型签名。失败模式：不设成员表，
    //   接口方法不参与 methodRegistry（虚调用只能靠调用点推断）。
    private fun generateInterface(iface: JasInterface, pkg: String, dir: File) {
        val internalName = "$pkg/${iface.name}"
        val cw = ClassWriter(ClassWriter.COMPUTE_FRAMES)
        // 接口也能是泛型的：类型参数 + 空超类（Object 由 visit 的 superName 表达）。
        val interfaceSignature = if (iface.typeParameters.isNotEmpty()) {
            jvmClassSignature(iface.typeParameters, null)
        } else null
        cw.visit(
            Opcodes.V1_8,
            Opcodes.ACC_PUBLIC or Opcodes.ACC_INTERFACE or Opcodes.ACC_ABSTRACT,
            internalName,
            interfaceSignature,
            "java/lang/Object",
            iface.extends.map { typeMapper.jvmInternalNameFromType(it) }.toTypedArray()
        )

        for (member in iface.members) {
            when (member) {
                is JasFunction -> {
                    val desc = methodDescriptor(member)
                    val methodSignature = if (member.typeParameters.isNotEmpty()) {
                        jvmMethodSignature(member)
                    } else null
                    // 抽象方法没有 Code 属性：visitMethod 后必须立刻 visitEnd，
                    // 不能 visitCode（否则 ASM 会写出非法的空 Code）。
                    val mv = cw.visitMethod(
                        Opcodes.ACC_PUBLIC or Opcodes.ACC_ABSTRACT,
                        member.name,
                        desc,
                        methodSignature, null
                    )
                    mv.visitEnd()
                }
                else -> {}
            }
        }

        cw.visitEnd()
        dir.resolve("${iface.name}.class").writeBytes(cw.toByteArray())
    }

    // 生成枚举类：合成 Java 枚举所需的全部样板 —— 常量静态字段、private 构造器
    // （(Ljava/lang/String;I<args>)V）、$VALUES 字段、<clinit>（new 每个常量并写数组）、
    // values()、valueOf()，再生成用户成员与 lambda。
    // 为什么手工合成：JVM 不认识 enum 语义，字节码层面枚举就是「继承 java/lang/Enum
    //   的普通类 + ACC_ENUM 标志 + 上述样板」，必须自己补齐才能在 Java/反射侧正确工作。
    // 边界/失败模式：构造器参数签名只取「第一个带参数的常量」推断（inferEnumArgDesc），
    //   若各常量参数类型/个数不一致，生成的 <init> 描述符与调用点会错配。
    private fun generateEnum(enum: JasEnum, pkg: String, dir: File) {
        val internalName = "$pkg/${enum.name}"
        val cw = ClassWriter(ClassWriter.COMPUTE_FRAMES)
        currentClassWriter = cw
        currentInternalName = internalName
        lambdaCounter = 0
        pendingLambdaMethods.clear()

        // ACC_ENUM 标志必须设，否则 Enum.valueOf 等反射路径不认；超类固定为 java/lang/Enum。
        cw.visit(
            Opcodes.V1_8,
            Opcodes.ACC_PUBLIC or Opcodes.ACC_SUPER or Opcodes.ACC_ENUM,
            internalName,
            null,
            "java/lang/Enum",
            emptyArray()
        )

        // Static final enum constant fields
        // 每个常量一个 public static final <Enum> 字段，并带 ACC_ENUM（让反射/反编译器识别为
        // 枚举常量）。在 <clinit> 里赋值。
        for (constant in enum.constants) {
            cw.visitField(
                Opcodes.ACC_PUBLIC or Opcodes.ACC_STATIC or Opcodes.ACC_FINAL or Opcodes.ACC_ENUM,
                constant.name,
                "L$internalName;",
                null, null
            ).visitEnd()
        }

        // Determine constructor args from enum constants
        // 参数描述符只从第一个「带参常量」推断，并据此生成一个能覆盖全部常量的构造器
        // （见函数头“失败模式”）。
        val hasArgs = enum.constants.any { it.args.isNotEmpty() }
        val argDescs = if (hasArgs && enum.constants.isNotEmpty()) {
            val firstWithArgs = enum.constants.firstOrNull { it.args.isNotEmpty() }
            firstWithArgs?.args?.map { inferEnumArgDesc(it) } ?: emptyList()
        } else emptyList()

        // Generate private constructor: (String, int, argTypes...)V
        // 前两个参数是 Java 枚举约定的 name/ordinal，固定放槽 1/2；用户参数从槽 3 起。
        val ctorDesc = "(Ljava/lang/String;I${argDescs.joinToString("")})V"
        val ctorMethod = cw.visitMethod(Opcodes.ACC_PRIVATE, "<init>", ctorDesc, null, null)
        ctorMethod.visitCode()
        ctorMethod.visitVarInsn(Opcodes.ALOAD, 0)
        ctorMethod.visitVarInsn(Opcodes.ALOAD, 1)
        ctorMethod.visitVarInsn(Opcodes.ILOAD, 2)
        ctorMethod.visitMethodInsn(Opcodes.INVOKESPECIAL, "java/lang/Enum", "<init>", "(Ljava/lang/String;I)V", false)
        // 用户参数同时存进私有 final 字段 $paramN，供后续（若有）读取；槽位推进按
        // long/double 占两槽的规则。
        var ctorSlot = 3
        for ((i, desc) in argDescs.withIndex()) {
            val fieldName = "\$param$i"
            cw.visitField(Opcodes.ACC_PRIVATE or Opcodes.ACC_FINAL, fieldName, desc, null, null).visitEnd()
            ctorMethod.visitVarInsn(Opcodes.ALOAD, 0)
            ctorMethod.visitVarInsn(loadOpcode(desc), ctorSlot)
            ctorMethod.visitFieldInsn(Opcodes.PUTFIELD, internalName, fieldName, desc)
            ctorSlot += if (desc == "J" || desc == "D") 2 else 1
        }
        ctorMethod.visitInsn(Opcodes.RETURN)
        ctorMethod.visitMaxs(0, 0)
        ctorMethod.visitEnd()

        // $VALUES field
        // 缓存常量数组的私有静态字段（合成标志）；values() 会 clone 它再返回，
        // 防止调用方改内部数组。
        cw.visitField(
            Opcodes.ACC_PRIVATE or Opcodes.ACC_STATIC or Opcodes.ACC_FINAL or Opcodes.ACC_SYNTHETIC,
            "\$VALUES", "[L$internalName;", null, null
        ).visitEnd()

        // <clinit>
        // 类初始化时按声明顺序 new 出每个常量并写回静态字段（ordinal 即下标），
        // 再统一构造 $VALUES 数组。顺序必须与声明顺序一致，ordinal 才正确。
        val clinit = cw.visitMethod(Opcodes.ACC_STATIC, "<clinit>", "()V", null, null)
        clinit.visitCode()
        for ((i, constant) in enum.constants.withIndex()) {
            clinit.visitTypeInsn(Opcodes.NEW, internalName)
            clinit.visitInsn(Opcodes.DUP)
            clinit.visitLdcInsn(constant.name)
            pushInt(clinit, i)
            // 常量构造参数压栈。else 分支把无法识别的表达式压成 null——若构造器形参是
            // 基本类型，这里会因类型不匹配导致验证失败（已知限制）。
            // 注意 int64 字面量走 visitLdcInsn(Long) 压 J，与 inferEnumArgDesc 返回的 "I" 可能错配。
            for (arg in constant.args) {
                when (arg) {
                    is JasIntLiteral -> {
                        if (arg.value >= Int.MIN_VALUE && arg.value <= Int.MAX_VALUE)
                            pushInt(clinit, arg.value.toInt())
                        else clinit.visitLdcInsn(arg.value)
                    }
                    is JasStringLiteral -> clinit.visitLdcInsn(arg.text)
                    is JasBoolLiteral -> clinit.visitInsn(if (arg.value) Opcodes.ICONST_1 else Opcodes.ICONST_0)
                    is JasFloatLiteral -> clinit.visitLdcInsn(arg.value)
                    else -> clinit.visitInsn(Opcodes.ACONST_NULL)
                }
            }
            clinit.visitMethodInsn(Opcodes.INVOKESPECIAL, internalName, "<init>", ctorDesc, false)
            clinit.visitFieldInsn(Opcodes.PUTSTATIC, internalName, constant.name, "L$internalName;")
        }
        // 构造 [L<Enum>; 数组：先 ANEWARRAY 建定长数组，再逐项 DUP/索引/GETSTATIC/AASTORE 填充。
        pushInt(clinit, enum.constants.size)
        clinit.visitTypeInsn(Opcodes.ANEWARRAY, internalName)
        for ((i, constant) in enum.constants.withIndex()) {
            clinit.visitInsn(Opcodes.DUP)
            pushInt(clinit, i)
            clinit.visitFieldInsn(Opcodes.GETSTATIC, internalName, constant.name, "L$internalName;")
            clinit.visitInsn(Opcodes.AASTORE)
        }
        clinit.visitFieldInsn(Opcodes.PUTSTATIC, internalName, "\$VALUES", "[L$internalName;")
        clinit.visitInsn(Opcodes.RETURN)
        clinit.visitMaxs(0, 0)
        clinit.visitEnd()

        // values()
        // 返回 $VALUES 的克隆（clone 返回 Object，需 CHECKCAST 回数组类型）。
        // 返回副本是 Java 枚举的语义约定，防止外部篡改内部数组。
        val valuesMethod = cw.visitMethod(
            Opcodes.ACC_PUBLIC or Opcodes.ACC_STATIC,
            "values", "()[L$internalName;", null, null
        )
        valuesMethod.visitCode()
        valuesMethod.visitFieldInsn(Opcodes.GETSTATIC, internalName, "\$VALUES", "[L$internalName;")
        valuesMethod.visitMethodInsn(Opcodes.INVOKEVIRTUAL, "[L$internalName;", "clone", "()Ljava/lang/Object;", false)
        valuesMethod.visitTypeInsn(Opcodes.CHECKCAST, "[L$internalName;")
        valuesMethod.visitInsn(Opcodes.ARETURN)
        valuesMethod.visitMaxs(0, 0)
        valuesMethod.visitEnd()

        // valueOf()
        // 直接委托 java/lang/Enum.valueOf(Class,String)（它按常量名查表），再把返回的 Enum
        // 造型回本枚举类型。名字非法时由 Enum.valueOf 抛 IllegalArgumentException。
        val valueOfMethod = cw.visitMethod(
            Opcodes.ACC_PUBLIC or Opcodes.ACC_STATIC,
            "valueOf", "(Ljava/lang/String;)L$internalName;", null, null
        )
        valueOfMethod.visitCode()
        valueOfMethod.visitLdcInsn(Type.getObjectType(internalName))
        valueOfMethod.visitVarInsn(Opcodes.ALOAD, 0)
        valueOfMethod.visitMethodInsn(Opcodes.INVOKESTATIC, "java/lang/Enum", "valueOf", "(Ljava/lang/Class;Ljava/lang/String;)Ljava/lang/Enum;", false)
        valueOfMethod.visitTypeInsn(Opcodes.CHECKCAST, internalName)
        valueOfMethod.visitInsn(Opcodes.ARETURN)
        valueOfMethod.visitMaxs(0, 0)
        valueOfMethod.visitEnd()

        // Generate members
        // 用户定义的方法/属性，与普通类一致（无接口实现特殊处理）。
        for (member in enum.members) {
            when (member) {
                is JasFunction -> generateMethod(cw, member, internalName, false)
                is JasProperty -> {
                    generateField(cw, member)
                    generateSyntheticAccessors(cw, member, internalName)
                }
                else -> {}
            }
        }

        // 与 generateClass 相同：while+index 以便处理 lambda 内再产生 lambda 的情况。
        var index = 0
        while (index < pendingLambdaMethods.size) {
            generateLambdaSyntheticMethod(cw, pendingLambdaMethods[index], internalName)
            index++
        }

        cw.visitEnd()
        currentClassWriter = null
        currentInternalName = null
        dir.resolve("${enum.name}.class").writeBytes(cw.toByteArray())
    }

    // 生成「顶层函数包装类」：把每个顶层 JasFunction 编译成一个同名类的静态方法。
    // 为什么：JVM 没有包级自由函数，必须挂在某个类上；这里选择「一函数一类、方法名即
    //   函数名」的约定，跨函数调用通过 functionRegistry 解析到 currentPkg/<name>。
    // 边界：只有当函数名恰为 main 且无参时，才额外生成 JVM 入口 main([Ljava/lang/String;)V
    //   桥接（调用上面的静态 main 并丢弃返回值）。
    private fun generateMainClass(func: JasFunction, pkg: String, dir: File) {
        val internalName = "$pkg/${func.name}"
        val cw = ClassWriter(ClassWriter.COMPUTE_FRAMES)
        currentClassWriter = cw
        currentInternalName = internalName
        lambdaCounter = 0
        pendingLambdaMethods.clear()
        fieldDescriptorMap.clear()

        // 普通 final 类（PUBLIC|SUPER），无超类接口。
        cw.visit(
            Opcodes.V1_8,
            Opcodes.ACC_PUBLIC or Opcodes.ACC_SUPER,
            internalName,
            null,
            "java/lang/Object",
            emptyArray()
        )

        // isStatic=true：包装方法为 ACC_PUBLIC|ACC_STATIC。
        generateMethod(cw, func, internalName, true)

        var index = 0
        while (index < pendingLambdaMethods.size) {
            generateLambdaSyntheticMethod(cw, pendingLambdaMethods[index], internalName)
            index++
        }

        // 仅当名字为 main 且无参时补 JVM 入口桥：标准的 main(String[]) 签名里没有我们的
        // 业务参数，故转调真正的静态 main()；返回值非 void 时按占槽情况 POP/POP2 丢弃。
        if (func.name == "main" && func.parameters.isEmpty()) {
            val mv = cw.visitMethod(
                Opcodes.ACC_PUBLIC or Opcodes.ACC_STATIC,
                "main", "([Ljava/lang/String;)V", null, null
            )
            mv.visitCode()
            val mainDesc = methodDescriptor(func)
            mv.visitMethodInsn(
                Opcodes.INVOKESTATIC, internalName,
                "main", mainDesc, false
            )
            val returnDesc = typeMapper.jvmReturnDescriptor(func.returnType)
            if (returnDesc != "V") {
                // long/double 占两槽，必须 POP2，否则栈不平衡导致验证失败。
                if (returnDesc == "J" || returnDesc == "D") {
                    mv.visitInsn(Opcodes.POP2)
                } else {
                    mv.visitInsn(Opcodes.POP)
                }
            }
            mv.visitInsn(Opcodes.RETURN)
            // 手写 maxs（未用 COMPUTE_MAXS/FRAMES 自动叠加时此值即上限）：1 个槽（参数数组）。
            mv.visitMaxs(1, 1)
            mv.visitEnd()
        }

        cw.visitEnd()
        currentClassWriter = null
        currentInternalName = null
        dir.resolve("${func.name}.class").writeBytes(cw.toByteArray())
    }

    // 生成注解类型：ACC_ANNOTATION|INTERFACE|ABSTRACT，实现 java/lang/annotation/Annotation，
    // 并固定写 @Retention(RUNTIME)（否则默认 CLASS，运行期反射取不到）。每个成员是一个
    // 抽象方法，可选带 AnnotationDefault 属性承载默认值。
    // 边界：只支持字面量默认值（见 generateAnnotationDefaultValue）；不写泛型/签名，
    //   也不登记 methodRegistry。
    private fun generateAnnotationType(ann: JasAnnotationType, pkg: String, dir: File) {
        val internalName = "$pkg/${ann.name}"
        val cw = ClassWriter(ClassWriter.COMPUTE_FRAMES)
        currentClassWriter = cw
        currentInternalName = internalName
        lambdaCounter = 0
        pendingLambdaMethods.clear()

        val access = Opcodes.ACC_PUBLIC or Opcodes.ACC_INTERFACE or Opcodes.ACC_ABSTRACT or Opcodes.ACC_ANNOTATION
        cw.visit(
            Opcodes.V1_8,
            access,
            internalName,
            null,
            "java/lang/Object",
            arrayOf("java/lang/annotation/Annotation")
        )

        // 固定 RUNTIME 保留策略：让注解在运行期可被反射读取（引入二参 visitAnnotation 表示运行时可见）。
        val retentionVis = cw.visitAnnotation("Ljava/lang/annotation/Retention;", true)
        retentionVis.visitEnum("value", "Ljava/lang/annotation/RetentionPolicy;", "RUNTIME")
        retentionVis.visitEnd()

        for (member in ann.members) {
            val desc = typeMapper.jvmReturnDescriptor(member.type)
            // 注解成员是无参抽象方法：描述符固定为 ()<type>。
            val mv = cw.visitMethod(
                Opcodes.ACC_PUBLIC or Opcodes.ACC_ABSTRACT,
                member.name,
                "()$desc",
                null, null
            )
            if (member.defaultValue != null) {
                // 默认值以 AnnotationDefault 属性附加在方法上；转换不出的表达式返回 null，
                // 于是静默省略默认值（不报错，也不降级为其他值）。
                val defaultVal = generateAnnotationDefaultValue(member.defaultValue, desc)
                if (defaultVal != null) {
                    val av = mv.visitAnnotationDefault()
                    av.visit(null, defaultVal)
                    av.visitEnd()
                }
            }
            mv.visitEnd()
        }

        cw.visitEnd()
        currentClassWriter = null
        currentInternalName = null
        dir.resolve("${ann.name}.class").writeBytes(cw.toByteArray())
    }

    // 把注解成员的默认值表达式转成 ASM 能写进 AnnotationDefault 的常量。
    // 为什么需要：AnnotationDefault 是「常量」（Class/int/byte/.../String/枚举常量/注解/数组），
    //   不能放任意表达式求值，故这里只能映射字面量。
    // 边界/失败模式：int 字面量按目标类型决定给 Long 还是截断为 Int；其余表达式（含引用、
    //   枚举、数组、运算）一律返回 null——调用方据此省略默认值。
    private fun generateAnnotationDefaultValue(expr: JasExpression, desc: String): Any? {
        return when (expr) {
            is JasIntLiteral -> {
                when (desc) {
                    "J" -> expr.value
                    else -> expr.value.toInt()
                }
            }
            is JasFloatLiteral -> expr.value
            is JasStringLiteral -> expr.text
            is JasBoolLiteral -> expr.value
            else -> null
        }
    }

    // 生成一个字段：static 属性 → ACC_STATIC（默认包可见，未显式加 PUBLIC），其余一律
    // ACC_PRIVATE（对外只经 getter/setter）。同时把描述符登记进 fieldDescriptorMap，
    // 供后续 GETFIELD/PUTFIELD 复用。注意不写泛型 Signature，字段泛型信息会丢失。
    private fun generateField(cw: ClassWriter, field: JasProperty) {
        val access = if (field.modifiers.contains("static"))
            Opcodes.ACC_STATIC else Opcodes.ACC_PRIVATE
        val desc = typeMapper.jvmDescriptorFromType(field.type)
        fieldDescriptorMap[field.name] = desc
        cw.visitField(access, field.name, desc, null, null).visitEnd()
    }

    // 为一个属性合成 getter/setter。
    // 命名规则：boolean（Z）的 getter 用 isXxx，其余用 getXxx；setter 统一 setXxx。
    // 有无自定义访问器体分两种路径：有体则把体当普通方法体编译，无体则直连字段。
    // 边界/失败模式：
    //   - getter 体若以 JasReturn 结尾，会先由 generateStatement 发一次返回指令，随后函数
    //     末尾又无条件补一条 returnOpcode -> 形成不可达指令（潜在验证/帧风险）。
    //   - getter 体最后一条是表达式语句时取它的值作为返回；否则按语句生成，返回值由
    //     “末尾补 return” 承担——因此体里没有 return 时会返回字段默认值/未初始化值。
    //   - 未登记进 methodRegistry，属性访问器不参与虚调用解析。
    private fun generateSyntheticAccessors(cw: ClassWriter, prop: JasProperty, owner: String) {
        val fieldDesc = typeMapper.jvmDescriptorFromType(prop.type)

        // boolean 属性遵循 JavaBeans 的 is 前缀约定。
        val prefix = if (fieldDesc == "Z") "is" else "get"
        val getterName = prefix + prop.name.replaceFirstChar { it.uppercase() }
        val getterMv = cw.visitMethod(Opcodes.ACC_PUBLIC, getterName, "()$fieldDesc", null, null)
        getterMv.visitCode()
        if (prop.getter?.body != null) {
            // 编译自定义 getter 体：this 占槽 0，局部变量从槽 1 起（getter 无参）。
            val statements = prop.getter.body.statements
            val locals = mutableListOf<LocalInfo>()
            val variables = mutableMapOf<String, Int>()
            locals.add(LocalInfo("this", null, 0))
            val ctx = CodegenContext(getterMv, locals, prop.type, 1, variables, false)
            // 除最后一条外都按普通语句生成。
            for (i in 0 until statements.size - 1) {
                generateStatement(getterMv, statements[i], ctx, owner)
            }
            // 最后一条：若是表达式语句，直接求值把它留在操作数栈上当返回值；否则按语句处理。
            if (statements.isNotEmpty()) {
                val last = statements.last()
                if (last is JasExpressionStatement) {
                    generateExpression(getterMv, last.expression, ctx, owner)
                } else {
                    generateStatement(getterMv, last, ctx, owner)
                }
            }
        } else {
            // 无体：直接 AL0;GETFIELD <owner>.<prop>:<desc>。
            getterMv.visitVarInsn(Opcodes.ALOAD, 0)
            getterMv.visitFieldInsn(Opcodes.GETFIELD, owner, prop.name, fieldDesc)
        }
        getterMv.visitInsn(returnOpcode(fieldDesc))
        getterMv.visitMaxs(0, 0)
        getterMv.visitEnd()

        val setterName = "set" + prop.name.replaceFirstChar { it.uppercase() }
        val setterMv = cw.visitMethod(Opcodes.ACC_PUBLIC, setterName, "($fieldDesc)V", null, null)
        setterMv.visitCode()
        if (prop.setter?.body != null) {
            // 编译自定义 setter 体：this 占 0，形参占 1（long/double 占 1、2），局部变量从其后起。
            val locals = mutableListOf<LocalInfo>()
            val variables = mutableMapOf<String, Int>()
            locals.add(LocalInfo("this", null, 0))
            val paramName = prop.setter.parameterName ?: "value"
            val paramSlot = 1
            locals.add(LocalInfo(paramName, prop.type, paramSlot))
            variables[paramName] = paramSlot
            val nextSlot = paramSlot + if (fieldDesc == "J" || fieldDesc == "D") 2 else 1
            val ctx = CodegenContext(setterMv, locals, null, nextSlot, variables, false)
            generateBlock(setterMv, prop.setter.body, ctx, owner)
        } else {
            // 无体：AL0; (ILOAD/LLOAD/... 1); PUTFIELD <owner>.<prop>:<desc>。
            setterMv.visitVarInsn(Opcodes.ALOAD, 0)
            setterMv.visitVarInsn(loadOpcode(fieldDesc), 1)
            setterMv.visitFieldInsn(Opcodes.PUTFIELD, owner, prop.name, fieldDesc)
        }
        // setter 恒为 void：末尾无条件补 RETURN。
        setterMv.visitInsn(Opcodes.RETURN)
        setterMv.visitMaxs(0, 0)
        setterMv.visitEnd()
    }

    // descriptor → 对应的返回指令（IRETURN/LRETURN/FRETURN/DRETURN/ARETURN）。
    // 为什么：JVM 的返回指令按类型分，基本类型窄化（Z/B/S/C）统一走 IRETURN；
    //   引用与数组走 ARETURN。未知描述符兜底 ARETURN。
    private fun returnOpcode(desc: String): Int {
        return when {
            desc == "I" || desc == "Z" || desc == "B" || desc == "S" || desc == "C" -> Opcodes.IRETURN
            desc == "J" -> Opcodes.LRETURN
            desc == "F" -> Opcodes.FRETURN
            desc == "D" -> Opcodes.DRETURN
            else -> Opcodes.ARETURN
        }
    }

    // descriptor → 对应的局部变量加载指令（ILOAD/LLOAD/FLOAD/DLOAD/ALOAD）。
    // 窄基本类型（Z/B/S/C）在 JVM 栈上都按 int 处理，故用 ILOAD；未知描述符兜底 ALOAD。
    private fun loadOpcode(desc: String): Int {
        return when {
            desc == "I" || desc == "Z" || desc == "B" || desc == "S" || desc == "C" -> Opcodes.ILOAD
            desc == "J" -> Opcodes.LLOAD
            desc == "F" -> Opcodes.FLOAD
            desc == "D" -> Opcodes.DLOAD
            else -> Opcodes.ALOAD
        }
    }

    // 生成一个方法：拼描述符/可选泛型签名 → visitMethod/visitCode → 初始化参数槽位
    // → 生成函数体 → 若函数体未以 return 结尾则补一条默认返回 → visitMaxs/visitEnd。
    // 局部变量槽分配：非静态 this 占槽 0，静态从槽 0 起；每个参数按 typeSlots 占 1 或 2 个
    //   槽（long/double 占两槽）；函数体里的临时值/局部变量继续顺延 nextSlot，不回收。
    // 已知缺陷（疑似）：函数体末尾的补 return 分支只调 generateReturn，**没有**像
    //   JasReturn 语句那样先 emitDeferredActions；因此「函数不显式 return 但有 defer」
    //   时 defer 体不会执行。另有 hasDeferred 的 if/else 两个分支代码完全相同（死分支），
    //   应是早期重构残留。
    private fun generateMethod(
        cw: ClassWriter,
        func: JasFunction,
        owner: String,
        isStatic: Boolean
    ) {
        val desc = methodDescriptor(func)
        // 只有带类型参数的方法才写泛型 Signature。
        val methodSignature = if (func.typeParameters.isNotEmpty()) {
            jvmMethodSignature(func)
        } else null
        val access = Opcodes.ACC_PUBLIC or (if (isStatic) Opcodes.ACC_STATIC else 0)
        val mv = cw.visitMethod(access, func.name, desc, methodSignature, null)
        mv.visitCode()

        if (func.body != null) {
            val locals = mutableListOf<LocalInfo>()
            val variables = mutableMapOf<String, Int>()
            // 静态方法参数从槽 0 起，实例方法 this 占槽 0、参数从 1 起。
            var slot = if (isStatic) 0 else 1
            for (p in func.parameters) {
                locals.add(LocalInfo(p.name, p.type, slot))
                variables[p.name] = slot
                slot += typeSlots(p.type)
            }

            // nextSlot = 参数之后第一个空闲槽；returnType 供 generateReturn 选返回指令。
            val ctx = CodegenContext(mv, locals, func.returnType, slot, variables, isStatic)
            // 计算过但未真正利用（两个分支同体，见函数头“已知缺陷”）。
            val hasDeferred = hasDeferBlock(func.body)
            if (hasDeferred) {
                generateBlock(mv, func.body, ctx, owner)
            } else {
                generateBlock(mv, func.body, ctx, owner)
            }
            // 函数体若没有任何顶层 JasReturn，补一条隐式返回（void 直接 RETURN，
            // 非 void 会吃掉栈顶——若类型不符可能造成验证错误）。
            if (!func.body.statements.any { it is JasReturn }) {
                generateReturn(mv, func.returnType)
            }
        } else {
            // 无函数体（如抽象/外部声明）：仍写出可验证的最小方法体。
            generateReturn(mv, func.returnType)
        }

        mv.visitMaxs(0, 0)
        mv.visitEnd()
    }

    // 生成构造器 <init>：先无条件调用 java/lang/Object.<init>()V，再（若有委托调用）
    // 生成 delegateCall 的参数与调用，最后生成构造器体。
    // 已知缺陷（疑似，重要）：
    //   - 无论真实超类是谁，这里**总是**调用 Object.<init>，不会调用 cls.superclass 的构造器，
    //     因此继承类的父类初始化是错的。
    //   - 若存在 delegateCall（this(...) / super(...)），会在已经调用过 Object.<init> 之后
    //     再发一次 invokespecial <init>，对同一对象重复初始化，JVM 会抛 VerifyError。
    //   - delegateCall 的实参描述符被硬编码为 Ljava/lang/Object;，与真实构造器签名不匹配。
    // 局部变量槽分配与 generateMethod 相同：this 占槽 0，参数从槽 1 起。
    private fun generateConstructor(
        cw: ClassWriter,
        ctor: JasConstructor,
        owner: String
    ) {
        val desc = constructorDescriptor(ctor)
        val mv = cw.visitMethod(Opcodes.ACC_PUBLIC, "<init>", desc, null, null)
        mv.visitCode()

        // 强制意义的父类初始化：恒定调用 Object.<init>（见函数头缺陷说明）。
        mv.visitVarInsn(Opcodes.ALOAD, 0)
        mv.visitMethodInsn(Opcodes.INVOKESPECIAL, "java/lang/Object", "<init>", "()V", false)

        val locals = mutableListOf<LocalInfo>()
        val variables = mutableMapOf<String, Int>()
        locals.add(LocalInfo("this", null, 0))
        var slot = 1
        for (p in ctor.parameters) {
            locals.add(LocalInfo(p.name, p.type, slot))
            variables[p.name] = slot
            slot += typeSlots(p.type)
        }

        if (ctor.delegateCall != null) {
            // 委托构造调用：先 AL0（新对象自身作 receiver），再依次求值实参，
            // 然后 invokespecial <init>。isSuper 时目标被写成 java/lang/Object。
            val ctx = CodegenContext(mv, locals, null, slot, variables, false)
            mv.visitVarInsn(Opcodes.ALOAD, 0)
            for (arg in ctor.delegateCall!!.args) {
                generateExpression(mv, arg, ctx, owner)
            }
            val target = if (ctor.delegateCall!!.isSuper) "java/lang/Object" else owner
            val paramDescs = ctor.delegateCall!!.args.joinToString("") { "Ljava/lang/Object;" }
            mv.visitMethodInsn(Opcodes.INVOKESPECIAL, target, "<init>", "($paramDescs)V", false)
        }

        if (ctor.body != null) {
            val ctx = CodegenContext(mv, locals, null, slot, variables, false)
            generateBlock(mv, ctor.body, ctx, owner)
        }

        mv.visitInsn(Opcodes.RETURN)
        mv.visitMaxs(0, 0)
        mv.visitEnd()
    }

    // 函数 → JVM 方法描述符 "(<参数描述符>)<返回描述符>"。委托 TypeMapper 逐个翻译类型。
    private fun methodDescriptor(func: JasFunction): String {
        val paramDescs = func.parameters.joinToString("") {
            typeMapper.jvmDescriptorFromType(it.type)
        }
        val returnDesc = typeMapper.jvmReturnDescriptor(func.returnType)
        return "($paramDescs)$returnDesc"
    }

    // 构造器 → JVM 方法描述符 "(<参数描述符>)V"（构造器恒返回 void）。
    private fun constructorDescriptor(ctor: JasConstructor): String {
        val paramDescs = ctor.parameters.joinToString("") {
            typeMapper.jvmDescriptorFromType(it.type)
        }
        return "($paramDescs)V"
    }

    // 类型在局部变量表中占用的槽数：long(J)/double(D) 各占 2 槽，其余 1 槽。
    // 这是所有槽位推进（参数、局部变量、临时值）的唯一依据。
    private fun typeSlots(type: JasType?): Int {
        val desc = typeMapper.jvmDescriptorFromType(type)
        return if (desc == "J" || desc == "D") 2 else 1
    }

    // 单个方法/块的编译期上下文。所有字段都在一次方法生成内活动，方法结束即丢弃。
    // 为什么集中放这里：generateBlock/Statement/Expression 递归时需携带当前
    //   方法访问器、符号表（变量名→槽）、返回类型与控制流栈，用参数传递会很啰嗦。
    private class CodegenContext(
        val mv: MethodVisitor,
        val locals: MutableList<LocalInfo>,
        val returnType: JasType?,
        var nextSlot: Int,
        val variables: MutableMap<String, Int>,
        val isStatic: Boolean
    ) {
        // 循环栈：每进入一层 while/do/for/for-in 压入 (continue 目标 label, break 目标 label)；
        //   break/continue 取栈顶。嵌套循环靠这个栈自然支持（但不支持带标签的跳转目标）。
        val loopStack = mutableListOf<Pair<Label, Label>>()
        // defer 体栈：遇到 JasDefer 压入，函数返回/生成器 yield 时按**逆序**执行（后进先出）。
        //   注意只在显式 return/yield 路径消费，隐式落到函数末尾的 return 不消费（见 generateMethod）。
        val deferredBodies = mutableListOf<JasBlock>()
        // 当前处于开启状态的锁（MONITORENTER 之后、MONITOREXIT 之前）的槽号栈。
        //   用于在 return 前按逆序 MONITOREXIT 释放锁；JasLock 的异常路径另有自己的 handler。
        val lockSlots = mutableListOf<Int>()
        // 名字 → 由初始化器推断出的更精确类型（例如 `var x = new Foo()` 时把 x 记成 Foo），
        //   供 resolveReceiverType 解析虚调用 owner 使用；查不到时回退到 locals 里的声明类型。
        val inferredTypes = mutableMapOf<String, JasType>()
    }

    // 顺序生成块内所有语句（块本身不引入新的作用域/槽回收）。
    private fun generateBlock(mv: MethodVisitor, block: JasBlock, ctx: CodegenContext, owner: String) {
        for (stmt in block.statements) {
            generateStatement(mv, stmt, ctx, owner)
        }
    }

    // 语句级 when 分派：把每种 JasStatement 映射成一段字节码。这是「AST → 指令」的
    // 主要变化点——新增语句类型必须在这里补分支，否则落入末尾 `else -> {}` 被静默丢弃。
    // 约定：所有表达式在求值后都把结果压在操作数栈上；语句负责消费/丢弃它（如 POP）。
    private fun generateStatement(mv: MethodVisitor, stmt: JasStatement, ctx: CodegenContext, owner: String) {
        when (stmt) {
            // 表达式语句：求值后 POP 丢弃结果。println 等“逻辑上 void”的调用在
            // generateExpression 里已自行压入一个 null 占位，保证这里的 POP 有东西可弹。
            is JasExpressionStatement -> {
                generateExpression(mv, stmt.expression, ctx, owner)
                mv.visitInsn(Opcodes.POP)
            }
            // 变量声明：推断更精确类型（供虚调用 owner 解析）→ 按声明类型分配槽位
            // （long/double 占两槽）→ 登记名字→槽 → 求值初始化器并 STORE。
            // 注意 desc 用的是**声明类型** stmt.type（可能为 null → Object），初始化器类型不参与
            // 描述符选择，因此把子类存进父类型声明槽时不会插入 CHECKCAST。
            is JasVariableStatement -> {
                val inferredType = stmt.type ?: when (val init = stmt.initializer) {
                    is JasNew -> init.type
                    else -> null
                }
                if (inferredType != null && inferredType != stmt.type) {
                    ctx.inferredTypes[stmt.name] = inferredType
                }
                val desc = typeMapper.jvmDescriptorFromType(stmt.type)
                val slot = ctx.nextSlot
                ctx.nextSlot += if (desc == "J" || desc == "D") 2 else 1
                ctx.variables[stmt.name] = slot
                ctx.locals.add(LocalInfo(stmt.name, stmt.type, slot))
                if (stmt.initializer != null) {
                    generateExpression(mv, stmt.initializer, ctx, owner)
                    storeLocal(mv, desc, slot)
                }
            }
            // 嵌套块：直接内联生成，不引入新作用域，也不回收块内槽位。
            is JasBlock -> generateBlock(mv, stmt, ctx, owner)
            // return：按「是否持锁 / 是否有 defer」分三条路径，保证返回前释放锁、执行 defer。
            // 栈与顺序的语义等价要求：必须先算好返回值再释放锁/跑 defer，否则锁/defer 里的
            // 代码可能覆盖栈顶或重新获取资源；因此持锁路径把返回值暂存进临时槽。
            is JasReturn -> {
                val hasDeferred = ctx.deferredBodies.isNotEmpty()
                val hasLocks = ctx.lockSlots.isNotEmpty()
                if (hasLocks) {
                    // 持锁返回：返回值先求值存入临时槽，再逆序 MONITOREXIT，跑 defer，最后取回值返回。
                    val retSlot: Int?
                    val retDesc: String
                    if (stmt.value != null) {
                        generateExpression(mv, stmt.value, ctx, owner)
                        retDesc = typeMapper.jvmDescriptorFromType(ctx.returnType)
                        retSlot = ctx.nextSlot
                        // 缺陷：无条件只加 1 槽。返回类型为 long/double 时 storeLocal 会占两槽，
                        //   相邻槽未被预留，后续 defer 分配局部变量可能与之重叠。
                        ctx.nextSlot += 1
                        storeLocal(mv, retDesc, retSlot)
                    } else {
                        retSlot = null
                        retDesc = "V"
                    }
                    // reversed()：锁按后进先出释放，保证嵌套锁的配对。
                    for (ls in ctx.lockSlots.reversed()) {
                        loadLocal(mv, "Ljava/lang/Object;", ls)
                        mv.visitInsn(Opcodes.MONITOREXIT)
                    }
                    if (hasDeferred) {
                        emitDeferredActions(mv, ctx, owner)
                    }
                    if (retSlot != null) {
                        loadLocal(mv, retDesc, retSlot)
                    }
                    generateReturn(mv, ctx.returnType)
                } else if (hasDeferred) {
                    // 有 defer：先跑 defer（其后进先出由 emitDeferredActions 保证），再求值返回值。
                    emitDeferredActions(mv, ctx, owner)
                    if (stmt.value != null) {
                        generateExpression(mv, stmt.value, ctx, owner)
                    }
                    generateReturn(mv, ctx.returnType)
                } else {
                    // 最简单路径：直接求值并返回。
                    if (stmt.value != null) {
                        generateExpression(mv, stmt.value, ctx, owner)
                    }
                    generateReturn(mv, ctx.returnType)
                }
            }
            // if/else：条件为 0(假) 跳 else，then 结束跳 end，避免落入 else。无 else 时 else
            // 标签即紧邻 end。IFNE/IFEQ 直接吃 int/boolean 结果，不做显式 != 0 转换。
            is JasIf -> {
                generateExpression(mv, stmt.condition, ctx, owner)
                val elseLabel = Label()
                val endLabel = Label()
                mv.visitJumpInsn(Opcodes.IFEQ, elseLabel)
                generateBlock(mv, stmt.thenBody, ctx, owner)
                mv.visitJumpInsn(Opcodes.GOTO, endLabel)
                mv.visitLabel(elseLabel)
                if (stmt.elseBody != null) {
                    generateBlock(mv, stmt.elseBody, ctx, owner)
                }
                mv.visitLabel(endLabel)
            }
            // while：先判条件再进体。loopStack 压 (loopLabel=continue 目标, endLabel=break 目标)；
            //   用 removeLast 保证与进入时配对（异常路径不清理，属于已知局限）。
            is JasWhile -> {
                val loopLabel = Label()
                val endLabel = Label()
                ctx.loopStack.add(Pair(loopLabel, endLabel))
                mv.visitLabel(loopLabel)
                generateExpression(mv, stmt.condition, ctx, owner)
                mv.visitJumpInsn(Opcodes.IFEQ, endLabel)
                generateBlock(mv, stmt.body, ctx, owner)
                mv.visitJumpInsn(Opcodes.GOTO, loopLabel)
                mv.visitLabel(endLabel)
                ctx.loopStack.removeLast()
            }
            // do-while：先执行体再判条件，条件非 0 回跳。continue 目标 loopLabel 会重新执行体
            //   并再次判条件，符合 do-while 语义。
            is JasDoWhile -> {
                val loopLabel = Label()
                val endLabel = Label()
                ctx.loopStack.add(Pair(loopLabel, endLabel))
                mv.visitLabel(loopLabel)
                generateBlock(mv, stmt.body, ctx, owner)
                generateExpression(mv, stmt.condition, ctx, owner)
                mv.visitJumpInsn(Opcodes.IFNE, loopLabel)
                mv.visitLabel(endLabel)
                ctx.loopStack.removeLast()
            }
            // C 风格 for：init → 判条件 → 体 → update → 回跳。
            // 已知缺陷（疑似）：continue 目标设为 loopLabel 而非 update 之前，continue 会
            //   跳过 update，可能造成死循环/计数不推进。exitLabel 与 endLabel 都指向循环出口
            //   （break 走 endLabel）。
            is JasForStatement -> {
                if (stmt.init != null) {
                    generateStatement(mv, stmt.init, ctx, owner)
                }
                val loopLabel = Label()
                val exitLabel = Label()
                val endLabel = Label()
                ctx.loopStack.add(Pair(loopLabel, endLabel))
                mv.visitLabel(loopLabel)
                if (stmt.condition != null) {
                    generateExpression(mv, stmt.condition, ctx, owner)
                    mv.visitJumpInsn(Opcodes.IFEQ, exitLabel)
                }
                generateBlock(mv, stmt.body, ctx, owner)
                if (stmt.update != null) {
                    generateExpression(mv, stmt.update, ctx, owner)
                    mv.visitInsn(Opcodes.POP)
                }
                mv.visitJumpInsn(Opcodes.GOTO, loopLabel)
                mv.visitLabel(exitLabel)
                mv.visitLabel(endLabel)
                ctx.loopStack.removeLast()
            }
            // for-in：只按「数组」模型生成 —— ARRAYLENGTH 取长度，下标循环，arrayLoad 取元素。
            // 元素描述符由 tryResolveArrayElementDesc 推断；推断不出就按 Object 处理（多一次
            //   CHECKCAST 或不做造型）。基本类型元素直接 arrayLoad 得到原始值，引用类型则
            //   统一按 Object 槽装载后可选 CHECKCAST 到具体类型。
            // else/then（循环-else 语义）：用一个 loopEntered 标志记录「体是否至少执行过一次」；
            //   循环正常结束后若从未进入则跑 elseBody，否则跑 thenBody。
            // 已知缺陷（疑似）：
            //   - continue 目标 = loopLabel，跳过底部的 index++，会重复处理同一下标（死循环）。
            //   - 元素变量恒占 1 槽（未按 long/double 占 2 槽推进），与后续槽位可能重叠。
            //   - 只支持数组；iterable 是 List/String 等非数组时 ARRAYLENGTH 会验证失败。
            is JasForInStatement -> {
                // 仅当存在 elseBody 时才分配 loopEntered 标志槽；否则用哨兵 -1 表示不追踪。
                val loopEnteredSlot = if (stmt.elseBody != null) {
                    val slot = ctx.nextSlot
                    ctx.nextSlot += 1
                    pushInt(mv, 0)
                    storeLocal(mv, "I", slot)
                    slot
                } else -1
                // arraySlot 存数组引用，indexSlot 存当前下标（都是保守分配的独立槽）。
                val arraySlot = ctx.nextSlot
                ctx.nextSlot += 1
                val indexSlot = ctx.nextSlot
                ctx.nextSlot += 1
                val resolvedElemDesc = tryResolveArrayElementDesc(stmt.iterable, ctx) ?: "Ljava/lang/Object;"
                val isPrimitiveElem = resolvedElemDesc in listOf("I", "J", "F", "D", "Z", "B", "S", "C")
                val elemDesc = if (isPrimitiveElem) resolvedElemDesc else "Ljava/lang/Object;"
                // 数组引用本身按 Object 槽装载；元素为基本类型时数组描述符用于说明（不用于造型）。
                val arrayDesc = if (elemDesc == "Ljava/lang/Object;") "Ljava/lang/Object;" else "[$elemDesc"
                generateExpression(mv, stmt.iterable, ctx, owner)
                storeLocal(mv, arrayDesc, arraySlot)
                pushInt(mv, 0)
                storeLocal(mv, "I", indexSlot)
                val loopLabel = Label()
                val breakLabel = Label()
                val thenLabel = Label()
                // for-in 的 break 走 breakLabel（循环出口），continue 走 loopLabel（有缺陷，见函数头）。
                ctx.loopStack.add(Pair(loopLabel, breakLabel))
                mv.visitLabel(loopLabel)
                // 条件：index >= length 则跳出（IF_ICMPGE 到 thenLabel）。
                loadLocal(mv, "I", indexSlot)
                loadLocal(mv, arrayDesc, arraySlot)
                mv.visitInsn(Opcodes.ARRAYLENGTH)
                mv.visitJumpInsn(Opcodes.IF_ICMPGE, thenLabel)
                if (stmt.elseBody != null) {
                    // 进入过循环体：置 loopEntered=1。
                    pushInt(mv, 1)
                    storeLocal(mv, "I", loopEnteredSlot)
                }
                // 取 array[index]：arrayLoadOpcode 依元素类型选 IALOAD/LALOAD/.../AALOAD。
                loadLocal(mv, arrayDesc, arraySlot)
                loadLocal(mv, "I", indexSlot)
                mv.visitInsn(arrayLoadOpcode(elemDesc))
                // 引用类型且元素类型已知（非 Object）时插入 CHECKCAST（AALOAD 得到的是 Object）。
                if (!isPrimitiveElem && resolvedElemDesc != "Ljava/lang/Object;") {
                    val internalName = resolvedElemDesc.removePrefix("L").removeSuffix(";").replace('.', '/')
                    mv.visitTypeInsn(Opcodes.CHECKCAST, internalName)
                }
                // 元素变量槽：登记名字→槽并记录元素类型（供体里推断描述符）。
                val varSlot = ctx.nextSlot
                ctx.nextSlot += 1
                val elementType = when (resolvedElemDesc) {
                    "I" -> JasPrimitiveType("int32")
                    "J" -> JasPrimitiveType("int64")
                    "F" -> JasPrimitiveType("float32")
                    "D" -> JasPrimitiveType("float64")
                    "Z" -> JasPrimitiveType("bool")
                    "Ljava/lang/String;" -> JasPrimitiveType("string")
                    else -> null
                }
                storeLocal(mv, elemDesc, varSlot)
                ctx.variables[stmt.varName] = varSlot
                ctx.locals.add(LocalInfo(stmt.varName, elementType, varSlot))
                generateBlock(mv, stmt.body, ctx, owner)
                // index++，回到循环头。
                loadLocal(mv, "I", indexSlot)
                pushInt(mv, 1)
                mv.visitInsn(Opcodes.IADD)
                storeLocal(mv, "I", indexSlot)
                mv.visitJumpInsn(Opcodes.GOTO, loopLabel)
                mv.visitLabel(thenLabel)
                ctx.loopStack.removeAt(ctx.loopStack.size - 1)
                // 循环结束后的 then/else 分派：进入过 → thenBody，否则 → elseBody。
                if (stmt.elseBody != null) {
                    val elseBodyLabel = Label()
                    loadLocal(mv, "I", loopEnteredSlot)
                    mv.visitJumpInsn(Opcodes.IFEQ, elseBodyLabel)
                    if (stmt.thenBody != null) {
                        generateBlock(mv, stmt.thenBody, ctx, owner)
                    }
                    mv.visitJumpInsn(Opcodes.GOTO, breakLabel)
                    mv.visitLabel(elseBodyLabel)
                    generateBlock(mv, stmt.elseBody, ctx, owner)
                } else {
                    if (stmt.thenBody != null) {
                        generateBlock(mv, stmt.thenBody, ctx, owner)
                    }
                }
                mv.visitLabel(breakLabel)
            }
            // switch：先求值（须为 int 语义），再建分派表。values 为空的 case 视作 default。
            // 表型选择：分支值区间跨度 <=10 用 table-switch（稠密跳转表），否则用 lookup-switch
            //   （键值对匹配），这是纯性能优化，语义一致。
            // 边界/缺陷：只收集 JasIntLiteral 分支值，其它类型（字符串/长整型）被静默忽略；
            //   每个 case 体结束都 GOTO 到出口，因此**没有 fallthrough**；无 default 时走出口。
            is JasSwitch -> {
                generateExpression(mv, stmt.expression, ctx, owner)
                val endLabel = Label()
                val caseLabels = stmt.cases.map { Label() }
                val table = mutableListOf<Int>()
                val labels = mutableListOf<Label>()
                var defaultLabel = endLabel
                for ((i, case) in stmt.cases.withIndex()) {
                    if (case.values.isEmpty()) {
                        defaultLabel = caseLabels[i]
                    } else {
                        for (v in case.values) {
                            if (v is JasIntLiteral) {
                                table.add(v.value.toInt())
                                labels.add(caseLabels[i])
                            }
                        }
                    }
                }
                if (table.isNotEmpty()) {
                    val min = table.min()
                    val max = table.max()
                    if (max - min > 10) {
                        mv.visitLookupSwitchInsn(defaultLabel, table.toIntArray(), labels.toTypedArray())
                    } else {
                        // 稠密表：区间内未命中的下标统一指向 default（先填 default 再覆盖命中项）。
                        val denseLabels = Array(max - min + 1) { defaultLabel }
                        for (i in table.indices) {
                            val idx = table[i] - min
                            denseLabels[idx] = labels[i]
                        }
                        mv.visitTableSwitchInsn(min, max, defaultLabel, *denseLabels)
                    }
                } else {
                    // 没有任何 int 分支：直接跳 default（等价于无匹配）。
                    mv.visitJumpInsn(Opcodes.GOTO, defaultLabel)
                }
                for ((i, case) in stmt.cases.withIndex()) {
                    mv.visitLabel(caseLabels[i])
                    generateBlock(mv, case.body, ctx, owner)
                    mv.visitJumpInsn(Opcodes.GOTO, endLabel)
                }
                mv.visitLabel(endLabel)
            }
            // try/catch/finally。实现方式非常简化，只有**一条**异常表项：
            //   (tryStart, tryEnd) -> catchStart, 捕获 java/lang/Throwable。
            // 所有 catch 子句都在 catchStart 之后**顺序**生成，彼此不做类型判断/互斥跳转。
            // 已知缺陷（重要，属于疑似早期遗留）：
            //   - catch 子句没有类型匹配：第 1 个 catch（若是 Throwable）会吞掉所有异常，
            //     后面的 catch 体仍会继续执行（除非前面抛异常），语义与真正的 catch 不符。
            //   - finally 只在 try 体正常结束 / catch 体之后执行，**不在异常路径执行**，也没有
            //     重抛逻辑；若 catches 为空且无 finally，异常会被 catchStart 吞掉后静默丢弃。
            //   - try 体抛出的异常不会执行 finally。
            is JasTry -> {
                val tryStart = Label()
                val tryEnd = Label()
                val catchStart = Label()
                val endLabel = Label()
                // 唯一 handler 捕获 Throwable；catch 范围不含 handler 自身（tryEnd 在 catchStart 前）。
                mv.visitTryCatchBlock(tryStart, tryEnd, catchStart, "java/lang/Throwable")
                mv.visitLabel(tryStart)
                generateBlock(mv, stmt.body, ctx, owner)
                mv.visitLabel(tryEnd)
                mv.visitJumpInsn(Opcodes.GOTO, endLabel)
                mv.visitLabel(catchStart)
                for (catchClause in stmt.catches) {
                    // 异常对象先按 Throwable 存入槽，再（若非 Throwable）CHECKCAST 到声明类型。
                    val catchDesc = typeMapper.jvmDescriptorFromType(catchClause.parameter.type)
                    val catchSlot = ctx.nextSlot
                    ctx.nextSlot += 1
                    storeLocal(mv, "Ljava/lang/Throwable;", catchSlot)
                    val catchInternalName = typeMapper.jvmInternalNameFromType(catchClause.parameter.type)
                    if (catchInternalName != "java/lang/Throwable") {
                        loadLocal(mv, "Ljava/lang/Throwable;", catchSlot)
                        mv.visitTypeInsn(Opcodes.CHECKCAST, catchInternalName)
                        storeLocal(mv, catchDesc, catchSlot)
                    }
                    ctx.variables[catchClause.parameter.name] = catchSlot
                    generateBlock(mv, catchClause.body, ctx, owner)
                }
                if (stmt.finallyBody != null) {
                    generateBlock(mv, stmt.finallyBody, ctx, owner)
                }
                mv.visitLabel(endLabel)
            }
            // throw：表达式求值后直接 ATHROW（引用类型；null 由 JVM 抛 NPE）。
            is JasThrow -> {
                generateExpression(mv, stmt.expression, ctx, owner)
                mv.visitInsn(Opcodes.ATHROW)
            }
            // 带标签语句：标签信息被丢弃，只内联生成子语句。因此带标签的 break/continue
            //   无法定向到标签（见下方 break/continue 只取 loopStack 栈顶）。
            is JasLabeledStatement -> generateStatement(mv, stmt.statement, ctx, owner)
            // break：跳 loopStack 栈顶的第二个元素（break/出口标签）。不在循环里时静默无操作。
            is JasBreakStatement -> {
                ctx.loopStack.lastOrNull()?.second?.let { mv.visitJumpInsn(Opcodes.GOTO, it) }
            }
            // continue：跳 loopStack 栈顶的第一个元素（continue 目标标签）。不在循环里时静默无操作。
            is JasContinueStatement -> {
                ctx.loopStack.lastOrNull()?.first?.let { mv.visitJumpInsn(Opcodes.GOTO, it) }
            }
            // defer：此处不生成任何指令，只把体记入 deferredBodies；真正的执行发生在
            //   return / yield 路径（见 emitDeferredActions，按逆序 LIFO 运行）。
            is JasDefer -> {
                ctx.deferredBodies.add(stmt.body)
            }
            // assert：条件非 0 跳过，否则 new AssertionError（有 message 用 (Object) 构造器）
            //   并 ATHROW。注意无条件生成、不检查 -ea/系统属性，即「永远开启」的断言。
            is JasAssert -> {
                generateExpression(mv, stmt.condition, ctx, owner)
                val assertOk = Label()
                mv.visitJumpInsn(Opcodes.IFNE, assertOk)
                mv.visitTypeInsn(Opcodes.NEW, "java/lang/AssertionError")
                mv.visitInsn(Opcodes.DUP)
                if (stmt.message != null) {
                    generateExpression(mv, stmt.message, ctx, owner)
                    mv.visitMethodInsn(Opcodes.INVOKESPECIAL, "java/lang/AssertionError", "<init>", "(Ljava/lang/Object;)V", false)
                } else {
                    mv.visitMethodInsn(Opcodes.INVOKESPECIAL, "java/lang/AssertionError", "<init>", "()V", false)
                }
                mv.visitInsn(Opcodes.ATHROW)
                mv.visitLabel(assertOk)
            }
            // lock：等价于 synchronized(expr) 的字节码 —— DUP 接收者、存入槽、MONITORENTER，
            //   体放在 try 范围；正常路径 MONITOREXIT 后跳过 handler，异常路径由 handler 释放锁
            //   再原样重抛（保证异常不丢锁）。lockSlot 同时压入 ctx.lockSlots，供 return 提前释放。
            // 边界：表达式为 null 时 MONITORENTER 抛 NPE；锁对象类型不校验（按 Object 存槽）。
            is JasLock -> {
                generateExpression(mv, stmt.expression, ctx, owner)
                mv.visitInsn(Opcodes.DUP)
                val lockSlot = ctx.nextSlot
                ctx.nextSlot += 1
                storeLocal(mv, "Ljava/lang/Object;", lockSlot)
                mv.visitInsn(Opcodes.MONITORENTER)
                val tryStart = Label()
                val tryEnd = Label()
                val catchLabel = Label()
                val endLabel = Label()
                ctx.lockSlots.add(lockSlot)
                // try 范围覆盖锁体；handler 自身不在 try 内（避免自捕获死循环）。
                mv.visitTryCatchBlock(tryStart, tryEnd, catchLabel, "java/lang/Throwable")
                mv.visitLabel(tryStart)
                generateBlock(mv, stmt.body, ctx, owner)
                mv.visitLabel(tryEnd)
                ctx.lockSlots.removeLast()
                // 正常退出：释放锁，跳到出口。
                loadLocal(mv, "Ljava/lang/Object;", lockSlot)
                mv.visitInsn(Opcodes.MONITOREXIT)
                mv.visitJumpInsn(Opcodes.GOTO, endLabel)
                // 异常路径：存异常 → 释放锁 → 重新抛出（finally 语义）。
                mv.visitLabel(catchLabel)
                val exSlot = ctx.nextSlot
                ctx.nextSlot += 1
                storeLocal(mv, "Ljava/lang/Throwable;", exSlot)
                loadLocal(mv, "Ljava/lang/Object;", lockSlot)
                mv.visitInsn(Opcodes.MONITOREXIT)
                loadLocal(mv, "Ljava/lang/Throwable;", exSlot)
                mv.visitInsn(Opcodes.ATHROW)
                mv.visitLabel(endLabel)
            }
            // yield：本后端**没有真正的生成器**，把 yield 直接编译成「返回值 + 结束方法」。
            //   语义上等价于 return，只是返回前同样要跑 defer。缺陷：yieldSlot 恒 +1，
            //   long/double 返回值会占用两槽而只预留一槽。
            is JasYield -> {
                generateExpression(mv, stmt.expression, ctx, owner)
                val yieldSlot = ctx.nextSlot
                ctx.nextSlot += 1
                val desc = inferExprDesc(stmt.expression, ctx)
                storeLocal(mv, desc, yieldSlot)
                emitDeferredActions(mv, ctx, owner)
                loadLocal(mv, desc, yieldSlot)
                generateReturn(mv, ctx.returnType)
            }
            // 解构声明：把初始化器整体存入一个 Object 槽（视作数组），再对每个绑定按序号
            //   取出元素并存入各自的变量槽。
            // 已知缺陷（疑似）：arrayLoadOpcode 依赖 binding 的元素类型（如 int→IALOAD），但
            //   数组被存成 Object，若初始化器实际不是该基本类型数组会验证失败；引用类型元素
            //   也没有从 AALOAD 的 Object 结果插入 CHECKCAST。
            is JasDestructuringDeclaration -> {
                generateExpression(mv, stmt.initializer, ctx, owner)
                val arrSlot = ctx.nextSlot
                ctx.nextSlot += 1
                storeLocal(mv, "Ljava/lang/Object;", arrSlot)
                for ((i, binding) in stmt.bindings.withIndex()) {
                    loadLocal(mv, "Ljava/lang/Object;", arrSlot)
                    pushInt(mv, i)
                    val elemDesc = typeMapper.jvmDescriptorFromType(binding.type)
                    mv.visitInsn(arrayLoadOpcode(elemDesc))
                    val varSlot = ctx.nextSlot
                    ctx.nextSlot += if (elemDesc == "J" || elemDesc == "D") 2 else 1
                    ctx.variables[binding.name] = varSlot
                    ctx.locals.add(LocalInfo(binding.name, binding.type, varSlot))
                    storeLocal(mv, elemDesc, varSlot)
                }
            }
            // match：先对主体表达式求值一次（基本类型先装箱成包装类），存入 Object 槽 subjectSlot；
            //   然后逐 case 生成「模式测试 → guard → 体」。每个体结束 GOTO 到 endLabel；
            //   测试失败/guard 为假则落到 nextLabel（下一个 case 或 endLabel）。
            // 为什么存成 Object：模式匹配需要按引用比较（String.equals、Number.intValue 等），
            //   装箱后用统一表示更省分支。
            // 缺陷/边界：
            //   - 只有 int/string/bool 字面量、标识符绑定、or 组合与通配符被处理；其余模式
            //     （null、else、其他类型）一律视作「永不匹配」跳到下一个 case。
            //   - 标识符模式不做测试（永远匹配并把主体绑到变量），因此它之后的 case 实际不可达。
            //   - int 模式先 CHECKCAST java/lang/Number：主体不是数字包装类时抛 ClassCastException。
            is JasMatch -> {
                val subjectSlot = ctx.nextSlot
                ctx.nextSlot += 1
                generateExpression(mv, stmt.expression, ctx, owner)
                val subjectDesc = inferExprDesc(stmt.expression, ctx)
                // 基本类型先装箱，保证后面统一按 Object 取用（Number.intValue / Boolean.booleanValue / equals）。
                if (subjectDesc in listOf("I", "J", "F", "D", "Z", "B", "S", "C")) {
                    boxTopOfStack(mv, subjectDesc)
                }
                storeLocal(mv, "Ljava/lang/Object;", subjectSlot)
                val endLabel = Label()
                // 每个 case 的「失败落点」：最后一个 case 之后就是 endLabel，避免多打一个标签。
                val nextCaseLabels = mutableListOf<Label>()
                for (i in stmt.cases.indices) {
                    nextCaseLabels.add(if (i < stmt.cases.size - 1) Label() else endLabel)
                }
                for ((i, mc) in stmt.cases.withIndex()) {
                    val nextLabel = nextCaseLabels[i]
                    val p = mc.pattern
                    var patternMatchLabel: Label? = null
                    when (p) {
                        // 通配符：无条件匹配，不生成测试。
                        is JasWildcardPattern -> {}
                        // 整型字面量：主体当 Number 取 intValue 后与常量比较，不等跳下一个 case。
                        is JasIntLiteral -> {
                            loadLocal(mv, "Ljava/lang/Object;", subjectSlot)
                            mv.visitTypeInsn(Opcodes.CHECKCAST, "java/lang/Number")
                            mv.visitMethodInsn(Opcodes.INVOKEVIRTUAL, "java/lang/Number", "intValue", "()I", false)
                            pushInt(mv, p.value.toInt())
                            mv.visitJumpInsn(Opcodes.IF_ICMPNE, nextLabel)
                        }
                        // 字符串字面量：Object.equals 比较，不等（IFEQ）跳下一个 case。
                        is JasStringLiteral -> {
                            loadLocal(mv, "Ljava/lang/Object;", subjectSlot)
                            mv.visitLdcInsn(p.text)
                            mv.visitMethodInsn(Opcodes.INVOKEVIRTUAL, "java/lang/Object", "equals", "(Ljava/lang/Object;)Z", false)
                            mv.visitJumpInsn(Opcodes.IFEQ, nextLabel)
                        }
                        // 布尔字面量：主体当 Boolean 取 booleanValue 后与常量比较。
                        is JasBoolLiteral -> {
                            loadLocal(mv, "Ljava/lang/Object;", subjectSlot)
                            mv.visitTypeInsn(Opcodes.CHECKCAST, "java/lang/Boolean")
                            mv.visitMethodInsn(Opcodes.INVOKEVIRTUAL, "java/lang/Boolean", "booleanValue", "()Z", false)
                            pushInt(mv, if (p.value) 1 else 0)
                            mv.visitJumpInsn(Opcodes.IF_ICMPNE, nextLabel)
                        }
                        // 标识符模式：绑定变量 = 主体（不做测试，永远匹配）。
                        is JasIdentifier -> {
                            val varSlot = ctx.nextSlot
                            ctx.nextSlot += 1
                            ctx.variables[p.name] = varSlot
                            ctx.locals.add(LocalInfo(p.name, null, varSlot))
                            loadLocal(mv, "Ljava/lang/Object;", subjectSlot)
                            storeLocal(mv, "Ljava/lang/Object;", varSlot)
                        }
                        // or 组合：两条分支任一命中即跳 patternMatchLabel；都失败则落 nextLabel。
                        is JasBinaryOp -> {
                            if (p.op == "or") {
                                patternMatchLabel = Label()
                                checkOrPatternBranch(mv, p.left, subjectSlot, patternMatchLabel!!, nextLabel, ctx, owner)
                                checkOrPatternBranch(mv, p.right, subjectSlot, patternMatchLabel!!, nextLabel, ctx, owner)
                                mv.visitJumpInsn(Opcodes.GOTO, nextLabel)
                                mv.visitLabel(patternMatchLabel!!)
                            } else {
                                mv.visitJumpInsn(Opcodes.GOTO, nextLabel)
                            }
                        }
                        // 空模式与其它未支持模式：视作永不匹配。
                        null -> {
                            mv.visitJumpInsn(Opcodes.GOTO, nextLabel)
                        }
                        else -> {
                            mv.visitJumpInsn(Opcodes.GOTO, nextLabel)
                        }
                    }
                    // guard：模式命中后求值，为假则跳到下一个 case。
                    if (mc.guard != null) {
                        generateExpression(mv, mc.guard, ctx, owner)
                        mv.visitJumpInsn(Opcodes.IFEQ, nextLabel)
                    }
                    generateBlock(mv, mc.body, ctx, owner)
                    mv.visitJumpInsn(Opcodes.GOTO, endLabel)
                    // 落点标签（最后一个 case 复用 endLabel，不重复定义）。
                    if (nextLabel !== endLabel) {
                        mv.visitLabel(nextLabel)
                    }
                }
                mv.visitLabel(endLabel)
            }
            else -> {}
        }
    }

    // 表达式级 when 分派：把每种 JasExpression 生成成「在操作数栈上留下一个值」的指令序列。
    // 不变量：返回后栈顶恰好是该表达式的结果（基本类型按 JVM 栈表示，引用按 Object）。不变量
    //   由调用方消费（STORE/POP/作为上一级操作数）；不认识的类型落入末尾 else，**不压任何值**，
    //   会让调用方少一个栈元素（潜在验证错误）。
    private fun generateExpression(mv: MethodVisitor, expr: JasExpression, ctx: CodegenContext, owner: String) {
        when (expr) {
            // 整型字面量：能放进 int 就用 pushInt（ICONST/BIPUSH/SIPUSH），否则 LDC long。
            is JasIntLiteral -> {
                val v = expr.value
                if (v >= Int.MIN_VALUE && v <= Int.MAX_VALUE) {
                    pushInt(mv, v.toInt())
                } else {
                    mv.visitLdcInsn(v)
                }
            }
            // 浮点字面量：AST 里 value 是 Double，visitLdcInsn 会压入**double(D)**；
            //   但 inferExprDesc 对 JasFloatLiteral 返回 "F"，两者不一致（疑似缺陷），
            //   后续按 F 处理（如 FSTORE/FRETURN）可能验证失败。此处保持原样。
            is JasFloatLiteral -> mv.visitLdcInsn(expr.value)
            // 字符串：常量池 LDC。
            is JasStringLiteral -> mv.visitLdcInsn(expr.text)
            // 布尔：ICONST_1/ICONST_0（JVM 栈上即 int）。
            is JasBoolLiteral -> mv.visitInsn(if (expr.value) Opcodes.ICONST_1 else Opcodes.ICONST_0)
            // null：ACONST_NULL。
            is JasNullLiteral -> mv.visitInsn(Opcodes.ACONST_NULL)
            // 标识符：this/super 取槽 0；否则查变量槽，按该变量的声明类型选载入指令。
            //   查不到变量名时静默压 null（而非报错），会把「未定义变量」变成 null 传播。
            is JasIdentifier -> {
                if (expr.name == "this" || expr.name == "super") {
                    mv.visitVarInsn(Opcodes.ALOAD, 0)
                } else {
                    val slot = ctx.variables[expr.name]
                    if (slot != null) {
                        val local = ctx.locals.find { it.name == expr.name }
                        val desc = typeMapper.jvmDescriptorFromType(local?.type)
                        loadLocal(mv, desc, slot)
                    } else {
                        mv.visitInsn(Opcodes.ACONST_NULL)
                    }
                }
            }
            // 二元运算。&& / || 走短路求值（按左操作数结果跳过右操作数），结果压 0/1；
            // 其余运算符先求左、再求右，再按**左操作数**的类型描述符发算术/比较指令。
            // 注意：两侧类型不同（如 int + long）时不做隐式提升，可能生成非法指令。
            // 两个短路分支里的 visitFrame 在当前 COMPUTE_FRAMES 模式下由 ASM 自行计算，
            //   属冗余（保留是为了兼容手工帧模式）。
            is JasBinaryOp -> {
                when (expr.op) {
                    "&&" -> {
                        generateExpression(mv, expr.left, ctx, owner)
                        val falseLabel = Label()
                        val endLabel = Label()
                        mv.visitJumpInsn(Opcodes.IFEQ, falseLabel)
                        generateExpression(mv, expr.right, ctx, owner)
                        mv.visitJumpInsn(Opcodes.IFEQ, falseLabel)
                        mv.visitInsn(Opcodes.ICONST_1)
                        mv.visitJumpInsn(Opcodes.GOTO, endLabel)
                        mv.visitLabel(falseLabel)
                        mv.visitFrame(Opcodes.F_SAME, 0, null, 0, null)
                        mv.visitInsn(Opcodes.ICONST_0)
                        mv.visitLabel(endLabel)
                        mv.visitFrame(Opcodes.F_SAME1, 0, null, 1, arrayOf(Opcodes.INTEGER))
                    }
                    "||" -> {
                        generateExpression(mv, expr.left, ctx, owner)
                        val trueLabel = Label()
                        val endLabel = Label()
                        mv.visitJumpInsn(Opcodes.IFNE, trueLabel)
                        generateExpression(mv, expr.right, ctx, owner)
                        mv.visitJumpInsn(Opcodes.IFNE, trueLabel)
                        mv.visitInsn(Opcodes.ICONST_0)
                        mv.visitJumpInsn(Opcodes.GOTO, endLabel)
                        mv.visitLabel(trueLabel)
                        mv.visitFrame(Opcodes.F_SAME, 0, null, 0, null)
                        mv.visitInsn(Opcodes.ICONST_1)
                        mv.visitLabel(endLabel)
                        mv.visitFrame(Opcodes.F_SAME1, 0, null, 1, arrayOf(Opcodes.INTEGER))
                    }
                    else -> {
                        generateExpression(mv, expr.left, ctx, owner)
                        generateExpression(mv, expr.right, ctx, owner)
                        val desc = inferExprDesc(expr.left, ctx)
                        emitBinaryArithmeticOp(mv, expr.op, desc)
                    }
                }
            }
            // 一元运算。await/go 直接透传操作数（本后端不实现异步/协程）；
            // - 用 INEG（**仅 int**，long/double 取负会不正确）；! 用分支翻 0/1；
            // ~ 用「-1 XOR」按位取反（**仅 int**）。
            is JasUnaryOp -> {
                when (expr.op) {
                    "await", "go" -> generateExpression(mv, expr.operand, ctx, owner)
                    else -> {
                        generateExpression(mv, expr.operand, ctx, owner)
                        when (expr.op) {
                            "-" -> mv.visitInsn(Opcodes.INEG)
                            "!" -> {
                                val trueLabel = Label()
                                val endLabel = Label()
                                mv.visitJumpInsn(Opcodes.IFEQ, trueLabel)
                                mv.visitInsn(Opcodes.ICONST_0)
                                mv.visitJumpInsn(Opcodes.GOTO, endLabel)
                                mv.visitLabel(trueLabel)
                                mv.visitInsn(Opcodes.ICONST_1)
                                mv.visitLabel(endLabel)
                            }
                            "~" -> {
                                mv.visitInsn(Opcodes.ICONST_M1)
                                mv.visitInsn(Opcodes.IXOR)
                            }
                        }
                    }
                }
            }
            // 调用。按优先级分四种形态：
            //   1) println(x)          —— 内建，直接走 System.out；
            //   2) __jasper_pow__      —— 编译器内建幂运算 intrinsic；
            //   3) 目标是被赋过值的局部变量 —— 当作「函数值调用」，走 SAM 接口 INVOKEINTERFACE；
            //   4) 其余 —— 属性访问形式视为虚调用（INVOKEVIRTUAL），否则静态调用（INVOKESTATIC）。
            is JasCall -> {
                val name = callMethodName(expr)
                // 内建 println：退化为 println(Object)。返回值处补 ACONST_NULL，使调用方若把它
                //   当表达式语句 POP 时栈仍平衡（println 本身返回 void）。
                if (name == "println" && expr.args.size == 1) {
                    mv.visitFieldInsn(Opcodes.GETSTATIC, "java/lang/System", "out", "Ljava/io/PrintStream;")
                    generateExpression(mv, expr.args[0], ctx, owner)
                    mv.visitMethodInsn(Opcodes.INVOKEVIRTUAL, "java/io/PrintStream", "println", "(Ljava/lang/Object;)V", false)
                    mv.visitInsn(Opcodes.ACONST_NULL)
                } else if (name == "__jasper_pow__" && expr.args.size == 2) {
                    // 幂运算 intrinsic：int → double → Math.pow → 再转回 int。
                    //   缺陷：只做 I2D/D2I，结果是 int，丢掉小数/溢出语义。
                    generateExpression(mv, expr.args[0], ctx, owner)
                    mv.visitInsn(Opcodes.I2D)
                    generateExpression(mv, expr.args[1], ctx, owner)
                    mv.visitInsn(Opcodes.I2D)
                    mv.visitMethodInsn(
                        Opcodes.INVOKESTATIC,
                        "java/lang/Math",
                        "pow",
                        "(DD)D",
                        false
                    )
                    mv.visitInsn(Opcodes.D2I)
                } else if (expr.target is JasIdentifier && ctx.variables.containsKey(name)) {
                    // 函数值调用：name 是变量（存放 Function/BiFunction/Supplier 对象），
                    //   把实参装箱成 Object 后按参数个数选对应 SAM 接口方法调用。
                    val slot = ctx.variables[name]!!
                    loadLocal(mv, "Ljava/lang/Object;", slot)
                    for (arg in expr.args) {
                        generateExpression(mv, arg, ctx, owner)
                        val argDesc = inferExprDesc(arg, ctx)
                        // 基本类型实参必须装箱，才能匹配 (Ljava/lang/Object;) 形参。
                        if (argDesc in listOf("I", "J", "F", "D", "Z", "B", "S", "C")) {
                            boxTopOfStack(mv, argDesc)
                        }
                    }
                    // 依据元数映射到 java.util.function 的 SAM：0→Supplier.get、
                    //   1→Function.apply、2→BiFunction.apply、其它退化到 Function。
                    val numArgs = expr.args.size
                    val iface: String
                    val samName: String
                    val samDesc: String
                    when (numArgs) {
                        0 -> { iface = "java/util/function/Supplier"; samName = "get"; samDesc = "()Ljava/lang/Object;" }
                        1 -> { iface = "java/util/function/Function"; samName = "apply"; samDesc = "(Ljava/lang/Object;)Ljava/lang/Object;" }
                        2 -> { iface = "java/util/function/BiFunction"; samName = "apply"; samDesc = "(Ljava/lang/Object;Ljava/lang/Object;)Ljava/lang/Object;" }
                        else -> { iface = "java/util/function/Function"; samName = "apply"; samDesc = "(Ljava/lang/Object;)Ljava/lang/Object;" }
                    }
                    mv.visitMethodInsn(Opcodes.INVOKEINTERFACE, iface, samName, samDesc, true)
                } else {
                    // 目标形如 a.b(...)（JasPropertyAccess）当虚调用；否则当静态调用。
                    val isVirtual = expr.target is JasPropertyAccess
                    if (isVirtual) {
                        // 先压接收者，再压实参（实参不做类型提升/装箱）。
                        generateExpression(mv, (expr.target as JasPropertyAccess).target, ctx, owner)
                    }
                    for (arg in expr.args) {
                        generateExpression(mv, arg, ctx, owner)
                    }
                    if (isVirtual) {
                        // owner 解析：接收者推断为命名类型就用其内部名，否则 Object；
                        //   未带包前缀时尝试补上 currentPkg（若该名字在方法表里）。
                        val receiverType = resolveReceiverType(expr, ctx)
                        var ownerName = when (receiverType) {
                            is JasNamedType -> receiverType.name.replace('.', '/')
                            else -> "java/lang/Object"
                        }
                        if (!ownerName.contains("/") && currentPkg.isNotEmpty()) {
                            val qualifiedName = "$currentPkg/$ownerName"
                            if (methodRegistry.containsKey(qualifiedName)) {
                                ownerName = qualifiedName
                            }
                        }
                        // 描述符：优先本类已登记的方法表，否则反射查 java 类（可能失败→空），
                        //   再退化为「全 Object 参数、Object 返回」的猜测描述符。
                        val desc = resolveVirtualMethodDescriptor(expr, ownerName) ?: callDescriptor(expr)
                        mv.visitMethodInsn(
                            Opcodes.INVOKEVIRTUAL,
                            ownerName,
                            name,
                            desc,
                            false
                        )
                    } else {
                        // 静态调用：若 name 是已登记的顶层函数，owner 定为 currentPkg/name
                        //   （一函数一类的约定），否则沿用当前生成上下文的 owner。
                        val targetOwner = if (functionRegistry.containsKey(name)) {
                            "$currentPkg/$name"
                        } else {
                            owner
                        }
                        mv.visitMethodInsn(
                            Opcodes.INVOKESTATIC,
                            targetOwner,
                            name,
                            callDescriptor(expr),
                            false
                        )
                    }
                }
            }
            // 赋值（本身是表达式，理想上要在栈上留下被赋的值）。
            // 分三种目标：标识符变量、数组元素、属性访问；都不匹配时退化为只求值 RHS（**不留值**）。
            is JasAssignment -> {
                if (expr.target is JasIdentifier) {
                    // 变量赋值：先取变量槽。查不到变量名时**整段不生成**，赋值被静默丢弃且
                    //   不留栈值（潜在栈不平衡）。
                    val slot = ctx.variables[expr.target.name]
                    if (slot != null) {
                        val local = ctx.locals.find { it.name == expr.target.name }
                        val desc = typeMapper.jvmDescriptorFromType(local?.type)
                        if (expr.op != "=") {
                            // Compound assignment: x += 1  →  x = x + 1
                            // 缺陷：复合运算符只映射 int 版指令（IADD/...），long/float/double/
                            //   字符串拼接会生成错误指令。
                            loadLocal(mv, desc, slot)
                            generateExpression(mv, expr.value, ctx, owner)
                            val op = expr.op.removeSuffix("=")
                            when (op) {
                                "+" -> mv.visitInsn(Opcodes.IADD)
                                "-" -> mv.visitInsn(Opcodes.ISUB)
                                "*" -> mv.visitInsn(Opcodes.IMUL)
                                "/" -> mv.visitInsn(Opcodes.IDIV)
                                "%" -> mv.visitInsn(Opcodes.IREM)
                                "&" -> mv.visitInsn(Opcodes.IAND)
                                "|" -> mv.visitInsn(Opcodes.IOR)
                                "^" -> mv.visitInsn(Opcodes.IXOR)
                                "<<" -> mv.visitInsn(Opcodes.ISHL)
                                ">>" -> mv.visitInsn(Opcodes.ISHR)
                                ">>>" -> mv.visitInsn(Opcodes.IUSHR)
                            }
                            storeLocal(mv, desc, slot)
                            // 再载入一次，使赋值表达式在栈上留下值。
                            loadLocal(mv, desc, slot)
                        } else {
                            generateExpression(mv, expr.value, ctx, owner)
                            storeLocal(mv, desc, slot)
                            loadLocal(mv, desc, slot)
                        }
                    }
                } else if (expr.target is JasArrayAccess) {
                    // 数组元素赋值：先压 [array, index]（供写回），再处理值。
                    val aa = expr.target as JasArrayAccess
                    generateExpression(mv, aa.target, ctx, owner)
                    generateExpression(mv, aa.index, ctx, owner)
                    val elemDesc = tryResolveArrayElementDesc(aa.target, ctx)
                    if (expr.op != "=") {
                        // 复合：再压一份 [array,index]、取旧值、算新值，用 DUP_X2/DUP2_X2 复制新值到
                        //   array/index 之下，最后 xASTORE 写回；写回后栈顶仍留一份新值（表达式值）。
                        generateExpression(mv, aa.target, ctx, owner)
                        generateExpression(mv, aa.index, ctx, owner)
                        mv.visitInsn(arrayLoadOpcode(elemDesc))
                        generateExpression(mv, expr.value, ctx, owner)
                        val op = expr.op.removeSuffix("=")
                        emitBinaryArithmeticOp(mv, op, elemDesc)
                        if (elemDesc in listOf("J", "D")) {
                            mv.visitInsn(Opcodes.DUP2_X2)
                        } else {
                            mv.visitInsn(Opcodes.DUP_X2)
                        }
                        mv.visitInsn(arrayStoreOpcode(elemDesc))
                    } else {
                        // 简单赋值：压入新值后用 DUP_X2 把值复制到 [array,index] 之下再写回，留下值。
                         generateExpression(mv, expr.value, ctx, owner)
                         mv.visitInsn(Opcodes.DUP_X2)
                         mv.visitInsn(arrayStoreOpcode(elemDesc))
                     }
                } else if (expr.target is JasPropertyAccess) {
                    // 属性赋值：字段描述符查 fieldDescriptorMap，兜底 Object（会导致字段类型不匹配）。
                    val pa = expr.target as JasPropertyAccess
                    generateExpression(mv, pa.target, ctx, owner)
                    val propDesc = fieldDescriptorMap[pa.property] ?: "Ljava/lang/Object;"
                    if (expr.op != "=") {
                        // 复合：DUP 接收者 → GETFIELD 取旧值 → 算新值 → PUTFIELD。
                        // 缺陷：PUTFIELD 会同时弹掉接收者与新值，**不留下表达式值**，
                        //   与「属性赋值是表达式」的语义不符（栈不平衡）。
                        mv.visitInsn(Opcodes.DUP)
                        mv.visitFieldInsn(Opcodes.GETFIELD, owner, pa.property, propDesc)
                        generateExpression(mv, expr.value, ctx, owner)
                        val op = expr.op.removeSuffix("=")
                        emitBinaryArithmeticOp(mv, op, propDesc)
                        mv.visitFieldInsn(Opcodes.PUTFIELD, owner, pa.property, propDesc)
                    } else {
                        // 简单赋值：压新值后 DUP_X1 复制到接收者之下，PUTFIELD 后留下值。
                        generateExpression(mv, expr.value, ctx, owner)
                        mv.visitInsn(Opcodes.DUP_X1)
                        mv.visitFieldInsn(Opcodes.PUTFIELD, owner, pa.property, propDesc)
                    }
                } else {
                    // 未支持的赋值目标：只求值 RHS，不留值（静默降级）。
                    generateExpression(mv, expr.value, ctx, owner)
                }
            }
            // 三元表达式：条件为假走 else 分支，两支汇合到 endLabel，栈上留一个值。
            is JasTernaryExpr -> {
                generateExpression(mv, expr.condition, ctx, owner)
                val falseLabel = Label()
                val endLabel = Label()
                mv.visitJumpInsn(Opcodes.IFEQ, falseLabel)
                generateExpression(mv, expr.thenExpr, ctx, owner)
                mv.visitJumpInsn(Opcodes.GOTO, endLabel)
                mv.visitLabel(falseLabel)
                generateExpression(mv, expr.elseExpr, ctx, owner)
                mv.visitLabel(endLabel)
            }
            // 属性访问。isSafe（?.）先对接收者做 null 检查：为 null 压 null 跳过取字段，
            //   否则正常 GETFIELD；两条路径都汇合到 endLabel，栈上留一个 Object。
            // 非安全访问直接 GETFIELD，描述符优先查 fieldDescriptorMap（兜底 Object）。
            is JasPropertyAccess -> {
                if (expr.isSafe) {
                    generateExpression(mv, expr.target, ctx, owner)
                    val nullLabel = Label()
                    val endLabel = Label()
                    mv.visitInsn(Opcodes.DUP)
                    mv.visitJumpInsn(Opcodes.IFNULL, nullLabel)
                    mv.visitFieldInsn(
                        Opcodes.GETFIELD,
                        owner,
                        expr.property,
                        "Ljava/lang/Object;"
                    )
                    mv.visitJumpInsn(Opcodes.GOTO, endLabel)
                    mv.visitLabel(nullLabel)
                    mv.visitInsn(Opcodes.POP)
                    mv.visitInsn(Opcodes.ACONST_NULL)
                    mv.visitLabel(endLabel)
                } else {
                    generateExpression(mv, expr.target, ctx, owner)
                    val propDesc = resolvePropertyDescriptor(expr)
                    mv.visitFieldInsn(
                        Opcodes.GETFIELD,
                        owner,
                        expr.property,
                        propDesc
                    )
                }
            }
            // new：解析类内部名（未带包则尝试补 currentPkg）→ NEW + DUP（保留一份引用给
            //   <init> 后的栈顶）→ 求值实参 → INVOKESPECIAL <init>。
            // 缺陷：新对象调用的是本类的字段 owner，而非真正声明该类的 owner；且构造参数
            //   描述符由 newDescriptor 粗略推断，与实际构造器可能不符。
            is JasNew -> {
                var typeName = typeMapper.jvmInternalNameFromType(expr.type)
                if (!typeName.contains("/") && currentPkg.isNotEmpty()) {
                    val qualifiedName = "$currentPkg/$typeName"
                    if (methodRegistry.containsKey(qualifiedName)) {
                        typeName = qualifiedName
                    }
                }
                val desc = newDescriptor(expr, ctx)
                mv.visitTypeInsn(Opcodes.NEW, typeName)
                mv.visitInsn(Opcodes.DUP)
                for (arg in expr.args) {
                    generateExpression(mv, arg, ctx, owner)
                }
                mv.visitMethodInsn(
                    Opcodes.INVOKESPECIAL,
                    typeName,
                    "<init>",
                    desc,
                    false
                )
            }
            // 数组读取：压 [array, index] 后按元素类型发 xALOAD。
            is JasArrayAccess -> {
                generateExpression(mv, expr.target, ctx, owner)
                generateExpression(mv, expr.index, ctx, owner)
                val elemDesc = tryResolveArrayElementDesc(expr.target, ctx)
                mv.visitInsn(arrayLoadOpcode(elemDesc))
            }
            // 类型转换。目标为基本类型时走**包装类拆箱**（CHECKCAST <Wrapper>; xxxValue），
            //   即假定栈上的值已装箱——直接对原始基本类型做此转换会 ClassCastException。
            // 目标为引用类型时只发 CHECKCAST。
            is JasCastExpr -> {
                generateExpression(mv, expr.expression, ctx, owner)
                val castDesc = typeMapper.jvmDescriptorFromType(expr.type)
                if (castDesc in listOf("I", "J", "F", "D", "Z", "B", "S", "C")) {
                    val wrapper = when (castDesc) {
                        "I" -> "java/lang/Integer"; "J" -> "java/lang/Long"
                        "F" -> "java/lang/Float"; "D" -> "java/lang/Double"
                        "Z" -> "java/lang/Boolean"; "B" -> "java/lang/Byte"
                        "S" -> "java/lang/Short"; "C" -> "java/lang/Character"
                        else -> "java/lang/Integer"
                    }
                    val unboxMethod = when (castDesc) {
                        "I" -> "intValue"; "J" -> "longValue"
                        "F" -> "floatValue"; "D" -> "doubleValue"
                        "Z" -> "booleanValue"; "B" -> "byteValue"
                        "S" -> "shortValue"; "C" -> "charValue"
                        else -> "intValue"
                    }
                    mv.visitTypeInsn(Opcodes.CHECKCAST, wrapper)
                    mv.visitMethodInsn(Opcodes.INVOKEVIRTUAL, wrapper, unboxMethod, "()$castDesc", false)
                } else {
                    val internalName = typeMapper.jvmInternalNameFromType(expr.type)
                    mv.visitTypeInsn(Opcodes.CHECKCAST, internalName)
                }
            }
            // 空合并 ??：左值为非 null 时保留左值跳出口，否则弹掉左值再求右值。
            is JasNullCoalescing -> {
                generateExpression(mv, expr.left, ctx, owner)
                val nullLabel = Label()
                val endLabel = Label()
                mv.visitInsn(Opcodes.DUP)
                mv.visitJumpInsn(Opcodes.IFNULL, nullLabel)
                mv.visitJumpInsn(Opcodes.GOTO, endLabel)
                mv.visitLabel(nullLabel)
                mv.visitInsn(Opcodes.POP)
                generateExpression(mv, expr.right, ctx, owner)
                mv.visitLabel(endLabel)
            }
            // instanceof：求值后直接 INSTANCEOF <目标内部名>，结果 0/1。
            is JasInstanceOfExpr -> {
                generateExpression(mv, expr.expression, ctx, owner)
                val internalName = typeMapper.jvmInternalNameFromType(expr.type)
                mv.visitTypeInsn(Opcodes.INSTANCEOF, internalName)
            }
            // lambda 与函数引用：交给各自的合成方法（生成私有静态方法 + invokedynamic/
            //   LambdaMetafactory），这里只做转发。
            is JasLambdaExpr -> generateLambdaExpression(mv, expr, ctx, owner)
            is JasMethodReference -> generateMethodReference(mv, expr, ctx, owner)
            // 数组创建，两种形式：
            //   (a) 带初值 {...}：先 push 长度 NEWARRAY/ANEWARRAY，再对每个元素 DUP/索引/求值/xASTORE；
            //   (b) 带维度表达式 [n] / [n][m]：一维用 NEWARRAY/ANEWARRAY，多维用 MULTIANEWARRAY。
            // 注意：arrayDesc 计算后并未被使用（死变量）；非基本类型元素统一按 Object 数组创建。
            is JasArrayCreation -> {
                if (expr.init is JasArrayInitValues) {
                    val values = expr.init.values
                    pushInt(mv, values.size)
                    val elemName = typeMapper.jvmInternalNameFromType(expr.type)
                    val isPrim = elemName in listOf("int", "long", "float", "double", "boolean", "byte", "short", "char")
                    val elemDesc = typeMapper.jvmDescriptorFromType(expr.type)
                    val arrayDesc = if (isPrim) "[${elemDesc}" else "[Ljava/lang/Object;"
                    if (isPrim) {
                        // 基本类型数组：NEWARRAY + 元素类型码。
                        val primType = when (elemName) {
                            "int" -> Opcodes.T_INT; "long" -> Opcodes.T_LONG; "float" -> Opcodes.T_FLOAT
                            "double" -> Opcodes.T_DOUBLE; "boolean" -> Opcodes.T_BOOLEAN
                            "byte" -> Opcodes.T_BYTE; "short" -> Opcodes.T_SHORT; "char" -> Opcodes.T_CHAR
                            else -> Opcodes.T_INT
                        }
                        mv.visitIntInsn(Opcodes.NEWARRAY, primType)
                    } else {
                        // 引用类型数组：ANEWARRAY <元素内部名>。
                        mv.visitTypeInsn(Opcodes.ANEWARRAY, typeMapper.jvmInternalNameFromType(expr.type))
                    }
                    // 逐个填值：DUP 数组引用 → 压下标 → 求值 → xASTORE。
                    for ((i, v) in values.withIndex()) {
                        mv.visitInsn(Opcodes.DUP)
                        pushInt(mv, i)
                        generateExpression(mv, v, ctx, owner)
                        mv.visitInsn(arrayStoreOpcode(elemDesc))
                    }
                } else {
                    // 先按顺序求值各维度长度，再发分配指令。
                    for (dim in expr.dims) {
                        generateExpression(mv, dim, ctx, owner)
                    }
                    val elemName = typeMapper.jvmInternalNameFromType(expr.type)
                    if (expr.dims.size == 1) {
                        if (elemName in listOf("int", "long", "float", "double", "boolean", "byte", "short", "char")) {
                            val primType = when (elemName) {
                                "int" -> Opcodes.T_INT; "long" -> Opcodes.T_LONG; "float" -> Opcodes.T_FLOAT
                                "double" -> Opcodes.T_DOUBLE; "boolean" -> Opcodes.T_BOOLEAN
                                "byte" -> Opcodes.T_BYTE; "short" -> Opcodes.T_SHORT; "char" -> Opcodes.T_CHAR
                                else -> Opcodes.T_INT
                            }
                            mv.visitIntInsn(Opcodes.NEWARRAY, primType)
                        } else {
                            mv.visitTypeInsn(Opcodes.ANEWARRAY, elemName)
                        }
                    } else {
                        // 多维：MULTIANEWARRAY，维度数取自 dims.size。
                        val arrayType = JasArrayType(expr.type)
                        val desc = typeMapper.jvmDescriptorFromType(arrayType)
                        mv.visitMultiANewArrayInsn(desc, expr.dims.size)
                    }
                }
            }
            // 字符串模板：转发给 generateStringTemplate（StringBuilder 拼接）。
            is JasStringTemplate -> generateStringTemplate(mv, expr, ctx, owner)
            // 字典字面量：new HashMap，然后逐项 DUP、压键值（基本类型先装箱）、put，POP 丢弃 put 的返回值。
            is JasDictLiteral -> {
                mv.visitTypeInsn(Opcodes.NEW, "java/util/HashMap")
                mv.visitInsn(Opcodes.DUP)
                mv.visitMethodInsn(Opcodes.INVOKESPECIAL, "java/util/HashMap", "<init>", "()V", false)
                for (entry in expr.entries) {
                    mv.visitInsn(Opcodes.DUP)
                    generateExpression(mv, entry.key, ctx, owner)
                    boxTopOfStack(mv, inferExprDesc(entry.key, ctx))
                    generateExpression(mv, entry.value, ctx, owner)
                    boxTopOfStack(mv, inferExprDesc(entry.value, ctx))
                    mv.visitMethodInsn(
                        Opcodes.INVOKEVIRTUAL,
                        "java/util/HashMap",
                        "put",
                        "(Ljava/lang/Object;Ljava/lang/Object;)Ljava/lang/Object;",
                        false
                    )
                    mv.visitInsn(Opcodes.POP)
                }
            }
        }
    }

    // 字符串模板 → StringBuilder 拼接：新建 builder，逐段 append，最后 toString。
    //   文本段按 String 追加；表达式段按类型选择转换方式（基本类型经 String.valueOf，
    //   引用类型直接 append(Object)）。
    // 缺陷：String.valueOf 没有 (byte)/(short) 重载，若 desc 为 "B"/"S" 会解析到不存在的
    //   方法描述符（潜在 NoSuchMethodError/验证问题）。
    private fun generateStringTemplate(mv: MethodVisitor, expr: JasStringTemplate, ctx: CodegenContext, owner: String) {
        mv.visitTypeInsn(Opcodes.NEW, "java/lang/StringBuilder")
        mv.visitInsn(Opcodes.DUP)
        mv.visitMethodInsn(Opcodes.INVOKESPECIAL, "java/lang/StringBuilder", "<init>", "()V", false)
        for (part in expr.parts) {
            when (part) {
                // 纯文本段：LDC + append(String)。
                is JasTemplateLiteral -> {
                    mv.visitLdcInsn(part.text)
                    mv.visitMethodInsn(Opcodes.INVOKEVIRTUAL, "java/lang/StringBuilder", "append", "(Ljava/lang/String;)Ljava/lang/StringBuilder;", false)
                }
                // 插值段：先求值表达式，再按推断描述符选择 String.valueOf 重载或 append(Object)。
                is JasTemplateExpr -> {
                    generateExpression(mv, part.expr, ctx, owner)
                    val desc = inferExprDesc(part.expr, ctx)
                    when (desc) {
                        "I", "Z", "B", "S", "C" -> {
                            mv.visitMethodInsn(Opcodes.INVOKESTATIC, "java/lang/String", "valueOf", "($desc)Ljava/lang/String;", false)
                            mv.visitMethodInsn(Opcodes.INVOKEVIRTUAL, "java/lang/StringBuilder", "append", "(Ljava/lang/String;)Ljava/lang/StringBuilder;", false)
                        }
                        "J" -> {
                            mv.visitMethodInsn(Opcodes.INVOKESTATIC, "java/lang/String", "valueOf", "(J)Ljava/lang/String;", false)
                            mv.visitMethodInsn(Opcodes.INVOKEVIRTUAL, "java/lang/StringBuilder", "append", "(Ljava/lang/String;)Ljava/lang/StringBuilder;", false)
                        }
                        "F" -> {
                            mv.visitMethodInsn(Opcodes.INVOKESTATIC, "java/lang/String", "valueOf", "(F)Ljava/lang/String;", false)
                            mv.visitMethodInsn(Opcodes.INVOKEVIRTUAL, "java/lang/StringBuilder", "append", "(Ljava/lang/String;)Ljava/lang/StringBuilder;", false)
                        }
                        "D" -> {
                            mv.visitMethodInsn(Opcodes.INVOKESTATIC, "java/lang/String", "valueOf", "(D)Ljava/lang/String;", false)
                            mv.visitMethodInsn(Opcodes.INVOKEVIRTUAL, "java/lang/StringBuilder", "append", "(Ljava/lang/String;)Ljava/lang/StringBuilder;", false)
                        }
                        else -> {
                            // 引用类型（或推断不出）：直接 append(Object)。
                            mv.visitMethodInsn(Opcodes.INVOKEVIRTUAL, "java/lang/StringBuilder", "append", "(Ljava/lang/Object;)Ljava/lang/StringBuilder;", false)
                        }
                    }
                }
            }
        }
        mv.visitMethodInsn(Opcodes.INVOKEVIRTUAL, "java/lang/StringBuilder", "toString", "()Ljava/lang/String;", false)
    }

    // 生成 lambda 表达式：把 lambda 体变成一个**私有静态合成方法**，并用 invokedynamic +
    //   LambdaMetafactory 在调用点创建 SAM 实例（JDK8 lambda 的标准做法）。
    // 闭包转换：先扫描体找出被引用的外层局部变量（captured），把它们作为合成方法的前置参数
    //   （按引用捕获语义退化为按值捕获），captured 类型从 ctx.locals 查，查不到记为 any(Object)。
    // invokedynamic 的 invokedType 形如 (captureDescs)L<FuncInterface>;，即捕获值作为调用点
    //   的“实参”绑定进方法句柄；SAM 接口按 lambda 形参个数选 Supplier/Function/BiFunction，
    //   3 个以上退化为 Function（samDesc 仍按 1 参，会与实现不匹配）。
    // 缺陷：合成方法是静态的，无法捕获 this；lambda 形参类型为 null 时描述符退化为 Object；
    //   instantiatedDesc 只对形参做装箱描述，与实现描述符可能不一致。
    private fun generateLambdaExpression(mv: MethodVisitor, expr: JasLambdaExpr, ctx: CodegenContext, owner: String) {
        // 每个 lambda 分配唯一编号，合成方法命名 lambda$N。
        val lambdaIndex = lambdaCounter++
        val lambdaName = "lambda\$$lambdaIndex"

        // 形参名集合用于排除“lambda 自己的参数”，被引用的其它局部变量即捕获项。
        val paramNames = expr.parameters.map { it.name }.toSet()
        val captured = findCapturedIdentifiers(expr.body, paramNames, ctx)

        // 捕获项类型：优先用外层局部变量声明类型，缺失时按 any(Object) 处理。
        val capturedTypes = captured.map { name ->
            ctx.locals.find { it.name == name }?.type ?: JasNamedType("any")
        }

        // 合成方法的参数 = [捕获项..., lambda 形参...]。
        val syntheticParams = mutableListOf<JasParameter>()
        for ((i, name) in captured.withIndex()) {
            syntheticParams.add(JasParameter(name, capturedTypes[i]))
        }
        syntheticParams.addAll(expr.parameters)

        // 合成方法统一以 Object 为返回类型（SAM 的擦除签名要求），实现里再把返回值装箱。
        val returnType = JasNamedType("any")
        val pending = PendingLambda(lambdaName, syntheticParams, expr.body, returnType, captured)
        pendingLambdaMethods.add(pending)

        // 捕获项/形参/返回的描述符拼装。captured.indexOf(it) 在循环里是 O(n^2)（名字唯一，结果正确）。
        val captureDescs = captured.joinToString("") {
            typeMapper.jvmDescriptorFromType(capturedTypes[captured.indexOf(it)])
        }
        val paramDescs = expr.parameters.joinToString("") {
            typeMapper.jvmDescriptorFromType(it.type)
        }
        val returnDesc = "Ljava/lang/Object;"
        val implDesc = "($captureDescs$paramDescs)$returnDesc"

        // SAM 目标接口/方法名/擦除签名：按形参个数选择。
        val numParams = expr.parameters.size
        val (funcInterface, samName, samErasedDesc) = when (numParams) {
            0 -> Triple("java/util/function/Supplier", "get", "()Ljava/lang/Object;")
            1 -> Triple("java/util/function/Function", "apply", "(Ljava/lang/Object;)Ljava/lang/Object;")
            2 -> Triple("java/util/function/BiFunction", "apply", "(Ljava/lang/Object;Ljava/lang/Object;)Ljava/lang/Object;")
            else -> Triple("java/util/function/Function", "apply", "(Ljava/lang/Object;)Ljava/lang/Object;")
        }

        // instantiatedMethodType：形参按装箱类型描述（SAM 擦除用 Object，但实例化类型可更具体）。
        val boxedParamDescs = expr.parameters.joinToString("") {
            boxedDescriptor(typeMapper.jvmDescriptorFromType(it.type))
        }
        val instantiatedDesc = "($boxedParamDescs)$returnDesc"
        // invokedType：捕获项作为调用点实参，返回 SAM 接口实例。
        val invokedType = "($captureDescs)L$funcInterface;"

        // LambdaMetafactory 的引导方法句柄（标准签名）。
        val bsmHandle = Handle(
            Opcodes.H_INVOKESTATIC,
            "java/lang/invoke/LambdaMetafactory",
            "metafactory",
            "(Ljava/lang/invoke/MethodHandles\$Lookup;Ljava/lang/String;Ljava/lang/invoke/MethodType;Ljava/lang/invoke/MethodType;Ljava/lang/invoke/MethodHandle;Ljava/lang/invoke/MethodType;)Ljava/lang/invoke/CallSite;",
            false
        )

        // 实现方法句柄指向本类里刚排队的合成方法（优先用当前类内部名）。
        val implHandle = Handle(
            Opcodes.H_INVOKESTATIC,
            currentInternalName ?: owner,
            lambdaName,
            implDesc,
            false
        )

        val samMethodType = Type.getMethodType(samErasedDesc)
        val instantiatedMethodType = Type.getMethodType(instantiatedDesc)

        mv.visitInvokeDynamicInsn(
            samName,
            invokedType,
            bsmHandle,
            samMethodType,
            implHandle,
            instantiatedMethodType
        )
    }

    // 基本类型描述符 → 对应包装类描述符（用于 SAM 的实例化方法类型）。
    //   非基本类型原样返回；不认识的基本类型也原样返回（可能不合法）。
    private fun boxedDescriptor(rawDesc: String): String = when (rawDesc) {
        "I" -> "Ljava/lang/Integer;"
        "J" -> "Ljava/lang/Long;"
        "F" -> "Ljava/lang/Float;"
        "D" -> "Ljava/lang/Double;"
        "Z" -> "Ljava/lang/Boolean;"
        "B" -> "Ljava/lang/Byte;"
        "S" -> "Ljava/lang/Short;"
        "C" -> "Ljava/lang/Character;"
        else -> rawDesc
    }

    // 生成方法引用：同样是 invokedynamic + LambdaMetafactory，但被引用的方法句柄直接指向目标方法。
    // 目标解析是启发式的：
    //   - 目标标识符首字母大写 → 当作类名，生成 H_INVOKESTATIC（类静态方法引用）；
    //   - 目标标识符小写      → 当作“接收者变量”，生成 H_INVOKEVIRTUAL，owner 写死 Object；
    //   - 其它（无目标/属性）  → H_INVOKESTATIC，owner 取当前类。
    // 形参描述符只对「已登记的顶层函数」从 functionRegistry 取，否则为空；再按参数个数选 SAM。
    // 缺陷：implHandle 直接用 SAM 的擦除描述符 samDesc 当作被引用方法的描述符，与实际方法签名
    //   往往不符（例如 println/自定义方法），且形参不做装箱/造型，容易解析失败。
    private fun generateMethodReference(mv: MethodVisitor, expr: JasMethodReference, ctx: CodegenContext, owner: String) {
        val targetExpr = expr.target
        val methodName = expr.method

        val handleTag: Int
        val refOwner: String

        when {
            // 首字母大写 → 视为类名的静态方法引用。
            targetExpr is JasIdentifier && targetExpr.name.isNotEmpty() &&
                targetExpr.name.first().isUpperCase() -> {
                handleTag = Opcodes.H_INVOKESTATIC
                refOwner = typeMapper.jvmInternalNameFromType(JasNamedType(targetExpr.name))
            }
            // 小写标识符 → 视为接收者变量的实例方法引用（owner 退化到 Object）。
            targetExpr is JasIdentifier && targetExpr.name.isNotEmpty() -> {
                handleTag = Opcodes.H_INVOKEVIRTUAL
                refOwner = "java/lang/Object"
            }
            // 其它 → 当前类的静态方法引用。
            else -> {
                handleTag = Opcodes.H_INVOKESTATIC
                refOwner = currentInternalName ?: owner
            }
        }

        val returnDesc = "Ljava/lang/Object;"
        // 形参描述符：仅当目标是已登记顶层函数时可得；否则空串。
        val paramDescs = when (targetExpr) {
            is JasIdentifier -> {
                val func = functionRegistry[targetExpr.name]
                if (func != null) {
                    func.parameters.joinToString("") { typeMapper.jvmDescriptorFromType(it.type) }
                } else ""
            }
            else -> ""
        }
        // 按描述符字符粗数参数个数（引用类型数 ';'，基本类型数字母）——启发式，数组可能重复计数。
        val numParams = if (paramDescs.isNotEmpty()) {
            paramDescs.count { it == ';' || it == 'I' || it == 'J' || it == 'F' || it == 'D' || it == 'Z' || it == 'B' || it == 'S' || it == 'C' }
        } else 0
        val (funcInterface, samName, samDesc) = when (numParams) {
            0 -> Triple("java/util/function/Supplier", "get", "()$returnDesc")
            1 -> Triple("java/util/function/Function", "apply", "(Ljava/lang/Object;)$returnDesc")
            2 -> Triple("java/util/function/BiFunction", "apply", "(Ljava/lang/Object;Ljava/lang/Object;)$returnDesc")
            else -> Triple("java/util/function/Function", "apply", "(Ljava/lang/Object;)$returnDesc")
        }
        // 方法引用不捕获值，故 invokedType 无参。
        val invokedType = "()L$funcInterface;"

        val bsmHandle = Handle(
            Opcodes.H_INVOKESTATIC,
            "java/lang/invoke/LambdaMetafactory",
            "metafactory",
            "(Ljava/lang/invoke/MethodHandles\$Lookup;Ljava/lang/String;Ljava/lang/invoke/MethodType;Ljava/lang/invoke/MethodType;Ljava/lang/invoke/MethodHandle;Ljava/lang/invoke/MethodType;)Ljava/lang/invoke/CallSite;",
            false
        )

        // 被引用方法的句柄；最后一个布尔参数是「是否为接口方法」。
        val implHandle = Handle(
            handleTag,
            refOwner,
            methodName,
            samDesc,
            handleTag == Opcodes.H_INVOKEINTERFACE
        )

        val samMethodType = Type.getMethodType(samDesc)
        val instantiatedMethodType = Type.getMethodType(samDesc)

        mv.visitInvokeDynamicInsn(
            "get",
            invokedType,
            bsmHandle,
            samMethodType,
            implHandle,
            instantiatedMethodType
        )
    }

    // 生成一个 lambda 合成方法（由 generateLambdaExpression 排队，generateClass 循环消费）。
    // 形态固定为 private static synthetic，返回 Object；参数 = [捕获项..., lambda 形参...]，
    //   槽位从 0 起顺序分配（静态方法无 this）。
    // 返回语义：体最后一条是表达式语句时，对其求值并装箱后返回；否则按语句生成再补 return。
    // 缺陷：非表达式语句结尾时补的 return 可能没有可返回的值（与 generateMethod 同类问题）。
    private fun generateLambdaSyntheticMethod(cw: ClassWriter, lm: PendingLambda, owner: String) {
        val paramDescs = lm.parameters.joinToString("") {
            typeMapper.jvmDescriptorFromType(it.type)
        }
        val returnDesc = "Ljava/lang/Object;"
        val desc = "($paramDescs)$returnDesc"

        val mv = cw.visitMethod(
            Opcodes.ACC_PRIVATE or Opcodes.ACC_STATIC or Opcodes.ACC_SYNTHETIC,
            lm.name,
            desc,
            null, null
        )
        mv.visitCode()

        // 形参即本地符号表；静态方法从槽 0 起。
        val locals = mutableListOf<LocalInfo>()
        val variables = mutableMapOf<String, Int>()
        var slot = 0
        for (p in lm.parameters) {
            locals.add(LocalInfo(p.name, p.type, slot))
            variables[p.name] = slot
            slot += typeSlots(p.type)
        }

        val ctx = CodegenContext(mv, locals, lm.returnType, slot, variables, true)

        // 除最后一条外按普通语句生成。
        val statements = lm.body.statements
        for (i in 0 until statements.size - 1) {
            generateStatement(mv, statements[i], ctx, owner)
        }
        if (statements.isNotEmpty()) {
            val lastStmt = statements.last()
            if (lastStmt is JasExpressionStatement) {
                // 尾表达式：求值 + 装箱后作为 Object 返回（匹配合成方法的返回类型）。
                generateExpression(mv, lastStmt.expression, ctx, owner)
                val exprDesc = inferExprDesc(lastStmt.expression, ctx)
                boxTopOfStack(mv, exprDesc)
                generateReturn(mv, lm.returnType)
            } else {
                generateStatement(mv, lastStmt, ctx, owner)
                generateReturn(mv, lm.returnType)
            }
        } else {
            // 空体：直接返回（ARETURN 需要栈上有引用，这里可能缺值 → 潜在验证错误）。
            generateReturn(mv, lm.returnType)
        }

        mv.visitMaxs(0, 0)
        mv.visitEnd()
    }

    // 找出 lambda 体里引用到、且属于外层作用域的局部变量名（即需要闭包捕获的变量）。
    // 判定：被引用（collectIdentifiers）∩ 外层已有变量（ctx.variables）∖ lambda 自身形参。
    // 边界：静态扫描、不区分读写；只认 ctx.variables 里登记过的名字，因此全局/字段/未登记
    //   变量不会被捕获。
    private fun findCapturedIdentifiers(body: JasBlock, lambdaParamNames: Set<String>, ctx: CodegenContext): List<String> {
        val referenced = mutableSetOf<String>()
        collectIdentifiers(body, referenced)
        return referenced.filter { it in ctx.variables && it !in lambdaParamNames }
    }

    // 递归收集节点里出现的所有标识符名（用于闭包捕获分析）。
    // 注意：这是一个与 AST 结构**平行**的遍历，必须随 AST 节点新增而同步补分支；
    //   缺失分支会静默漏掉子树（落到末尾 else），导致漏捕获（生成时名字查不到 → 压 null）。
    private fun collectIdentifiers(node: JasNode, result: MutableSet<String>) {
        when (node) {
            is JasIdentifier -> result.add(node.name)
            is JasIntLiteral, is JasFloatLiteral, is JasStringLiteral, is JasBoolLiteral, is JasNullLiteral -> {}
            is JasBinaryOp -> {
                collectIdentifiers(node.left, result)
                collectIdentifiers(node.right, result)
            }
            is JasUnaryOp -> collectIdentifiers(node.operand, result)
            is JasCall -> {
                collectIdentifiers(node.target, result)
                node.args.forEach { collectIdentifiers(it, result) }
            }
            is JasPropertyAccess -> collectIdentifiers(node.target, result)
            is JasAssignment -> {
                collectIdentifiers(node.target, result)
                collectIdentifiers(node.value, result)
            }
            is JasTernaryExpr -> {
                collectIdentifiers(node.condition, result)
                collectIdentifiers(node.thenExpr, result)
                collectIdentifiers(node.elseExpr, result)
            }
            is JasNullCoalescing -> {
                collectIdentifiers(node.left, result)
                collectIdentifiers(node.right, result)
            }
            is JasNew -> node.args.forEach { collectIdentifiers(it, result) }
            is JasArrayAccess -> {
                collectIdentifiers(node.target, result)
                collectIdentifiers(node.index, result)
            }
            is JasCastExpr -> collectIdentifiers(node.expression, result)
            is JasInstanceOfExpr -> collectIdentifiers(node.expression, result)
            is JasBlock -> node.statements.forEach { collectIdentifiers(it, result) }
            is JasExpressionStatement -> collectIdentifiers(node.expression, result)
            is JasReturn -> node.value?.let { collectIdentifiers(it, result) }
            is JasIf -> {
                collectIdentifiers(node.condition, result)
                collectIdentifiers(node.thenBody, result)
                node.elseBody?.let { collectIdentifiers(it, result) }
            }
            is JasWhile -> {
                collectIdentifiers(node.condition, result)
                collectIdentifiers(node.body, result)
            }
            is JasDoWhile -> {
                collectIdentifiers(node.body, result)
                collectIdentifiers(node.condition, result)
            }
            is JasForStatement -> {
                node.init?.let { collectIdentifiers(it, result) }
                node.condition?.let { collectIdentifiers(it, result) }
                node.update?.let { collectIdentifiers(it, result) }
                collectIdentifiers(node.body, result)
            }
            is JasForInStatement -> {
                collectIdentifiers(node.iterable, result)
                collectIdentifiers(node.body, result)
                node.thenBody?.let { collectIdentifiers(it, result) }
                node.elseBody?.let { collectIdentifiers(it, result) }
            }
            is JasSwitch -> {
                collectIdentifiers(node.expression, result)
                node.cases.forEach { collectIdentifiers(it.body, result) }
            }
            is JasTry -> {
                collectIdentifiers(node.body, result)
                node.catches.forEach { collectIdentifiers(it.body, result) }
                node.finallyBody?.let { collectIdentifiers(it, result) }
            }
            is JasThrow -> collectIdentifiers(node.expression, result)
            is JasVariableStatement -> node.initializer?.let { collectIdentifiers(it, result) }
            is JasAssert -> {
                collectIdentifiers(node.condition, result)
                node.message?.let { collectIdentifiers(it, result) }
            }
            is JasDefer -> collectIdentifiers(node.body, result)
            is JasLabeledStatement -> collectIdentifiers(node.statement, result)
            is JasLock -> collectIdentifiers(node.body, result)
            is JasYield -> collectIdentifiers(node.expression, result)
            is JasLambdaExpr -> {}
            is JasMethodReference -> node.target?.let { collectIdentifiers(it, result) }
            is JasArrayCreation -> node.dims.forEach { collectIdentifiers(it, result) }
            is JasArrayInitValues -> node.values.forEach { collectIdentifiers(it, result) }
            is JasCaseClause -> collectIdentifiers(node.body, result)
            is JasStringTemplate -> node.parts.forEach { collectIdentifiers(it, result) }
            is JasTemplateLiteral -> {}
            is JasTemplateExpr -> collectIdentifiers(node.expr, result)
            is JasDestructuringDeclaration -> {
                collectIdentifiers(node.initializer, result)
            }
            is JasBreakStatement -> {}
            is JasContinueStatement -> {}
            is JasDictLiteral -> {
                node.entries.forEach { collectIdentifiers(it.key, result); collectIdentifiers(it.value, result) }
            }
            is JasDictEntry -> {
                collectIdentifiers(node.key, result)
                collectIdentifiers(node.value, result)
            }
            is JasMatch -> {
                collectIdentifiers(node.expression, result)
                node.cases.forEach { collectIdentifiers(it, result) }
            }
            is JasMatchCase -> {
                node.pattern?.let { collectIdentifiers(it, result) }
                node.guard?.let { collectIdentifiers(it, result) }
                collectIdentifiers(node.body, result)
            }
            is JasWildcardPattern -> {}
            else -> {}
        }
    }

    // or 模式的分支测试：命中则跳到 matchLabel，否则顺序落到下一个分支/失败。
    // 与 generateStatement 的 match 分支共用同一套比较逻辑，但这里把「命中」作为正向跳转。
    // 缺陷：failLabel 形参**从未被使用**（失败天然是顺序落空），属冗余参数。
    private fun checkOrPatternBranch(mv: MethodVisitor, pattern: JasExpression, subjectSlot: Int, matchLabel: Label, failLabel: Label, ctx: CodegenContext, owner: String) {
        when (pattern) {
            // 整型：当 Number 取 intValue 后与常量比较，相等跳 matchLabel。
            is JasIntLiteral -> {
                loadLocal(mv, "Ljava/lang/Object;", subjectSlot)
                mv.visitTypeInsn(Opcodes.CHECKCAST, "java/lang/Number")
                mv.visitMethodInsn(Opcodes.INVOKEVIRTUAL, "java/lang/Number", "intValue", "()I", false)
                pushInt(mv, pattern.value.toInt())
                mv.visitJumpInsn(Opcodes.IF_ICMPEQ, matchLabel)
            }
            // 字符串：equals 为真（IFNE）跳 matchLabel。
            is JasStringLiteral -> {
                loadLocal(mv, "Ljava/lang/Object;", subjectSlot)
                mv.visitLdcInsn(pattern.text)
                mv.visitMethodInsn(Opcodes.INVOKEVIRTUAL, "java/lang/Object", "equals", "(Ljava/lang/Object;)Z", false)
                mv.visitJumpInsn(Opcodes.IFNE, matchLabel)
            }
            // 布尔：取 booleanValue 后按字面量真假选 IFNE/IFEQ 跳 matchLabel。
            is JasBoolLiteral -> {
                loadLocal(mv, "Ljava/lang/Object;", subjectSlot)
                mv.visitTypeInsn(Opcodes.CHECKCAST, "java/lang/Boolean")
                mv.visitMethodInsn(Opcodes.INVOKEVIRTUAL, "java/lang/Boolean", "booleanValue", "()Z", false)
                mv.visitJumpInsn(if (pattern.value) Opcodes.IFNE else Opcodes.IFEQ, matchLabel)
            }
            // 标识符：绑定主体到变量（无测试）后直接命中。
            is JasIdentifier -> {
                val varSlot = ctx.nextSlot
                ctx.nextSlot += 1
                ctx.variables[pattern.name] = varSlot
                ctx.locals.add(LocalInfo(pattern.name, null, varSlot))
                loadLocal(mv, "Ljava/lang/Object;", subjectSlot)
                storeLocal(mv, "Ljava/lang/Object;", varSlot)
                mv.visitJumpInsn(Opcodes.GOTO, matchLabel)
            }
            // 嵌套 or：递归展开左右分支。
            is JasBinaryOp -> {
                if (pattern.op == "or") {
                    checkOrPatternBranch(mv, pattern.left, subjectSlot, matchLabel, failLabel, ctx, owner)
                    checkOrPatternBranch(mv, pattern.right, subjectSlot, matchLabel, failLabel, ctx, owner)
                }
            }
        }
    }

    // 枚举常量实参表达式 → 构造器参数描述符。只支持字面量；其余一律 Ljava/lang/Object;。
    // 注意 int64 字面量会被这里判成 "I"，与 <clinit> 里压 long 的指令可能不一致（已在上文标注）。
    private fun inferEnumArgDesc(expr: JasExpression): String {
        return when (expr) {
            is JasIntLiteral -> "I"
            is JasFloatLiteral -> "D"
            is JasStringLiteral -> "Ljava/lang/String;"
            is JasBoolLiteral -> "Z"
            else -> "Ljava/lang/Object;"
        }
    }

    // 表达式 → JVM 描述符（用于选择载入/返回/装箱/比较等指令）。静态、局部推断，不做完整类型检查。
    // 大量情况兜底为 Ljava/lang/Object;；引用字段访问、调用、new、数组创建都退化为 Object。
    // 注意与生成指令的偏差：JasFloatLiteral 这里返回 "F"，但 generateExpression 实际压入的是
    //   double(D)；二者不一致（疑似缺陷）。
    private fun inferExprDesc(expr: JasExpression, ctx: CodegenContext): String {
        return when (expr) {
            is JasIntLiteral -> "I"
            is JasFloatLiteral -> "F"
            is JasStringLiteral -> "Ljava/lang/String;"
            is JasBoolLiteral -> "Z"
            is JasNullLiteral -> "Ljava/lang/Object;"
            // 标识符：查声明类型（含推断类型），查不到按 Object。
            is JasIdentifier -> {
                val local = ctx.locals.find { it.name == expr.name }
                val t = local?.type
                if (t != null) typeMapper.jvmDescriptorFromType(t) else "Ljava/lang/Object;"
            }
            is JasBinaryOp -> inferExprDesc(expr.left, ctx)
            is JasUnaryOp -> inferExprDesc(expr.operand, ctx)
            is JasCall -> "Ljava/lang/Object;"
            is JasNew -> "Ljava/lang/Object;"
            is JasTernaryExpr -> "Ljava/lang/Object;"
            is JasNullCoalescing -> "Ljava/lang/Object;"
            is JasCastExpr -> typeMapper.jvmDescriptorFromType(expr.type)
            is JasArrayAccess -> tryResolveArrayElementDesc(expr.target, ctx)
            is JasInstanceOfExpr -> "Z"
            is JasPropertyAccess -> "Ljava/lang/Object;"
            is JasLambdaExpr -> "Ljava/lang/Object;"
            is JasMethodReference -> "Ljava/lang/Object;"
            is JasArrayCreation -> "Ljava/lang/Object;"
            is JasAssignment -> "Ljava/lang/Object;"
            is JasStringTemplate -> "Ljava/lang/String;"
            is JasDictLiteral -> "Ljava/util/HashMap;"
            else -> "Ljava/lang/Object;"
        }
    }

    // 按描述符把栈顶基本类型装箱成包装类。只处理 I/J/F/D/Z；**不处理 B/S/C**（栈上按 int 表示，
    //   但装箱通常会归一成 Integer），调用方需自行保证 desc 落在支持集合内。
    private fun boxTopOfStack(mv: MethodVisitor, desc: String) {
        when (desc) {
            "I" -> mv.visitMethodInsn(Opcodes.INVOKESTATIC, "java/lang/Integer", "valueOf", "(I)Ljava/lang/Integer;", false)
            "J" -> mv.visitMethodInsn(Opcodes.INVOKESTATIC, "java/lang/Long", "valueOf", "(J)Ljava/lang/Long;", false)
            "F" -> mv.visitMethodInsn(Opcodes.INVOKESTATIC, "java/lang/Float", "valueOf", "(F)Ljava/lang/Float;", false)
            "D" -> mv.visitMethodInsn(Opcodes.INVOKESTATIC, "java/lang/Double", "valueOf", "(D)Ljava/lang/Double;", false)
            "Z" -> mv.visitMethodInsn(Opcodes.INVOKESTATIC, "java/lang/Boolean", "valueOf", "(Z)Ljava/lang/Boolean;", false)
        }
    }

    // 调用点描述符：优先采用 functionRegistry 里登记的目标函数签名（参数 + 返回）；
    //   目标未知时退化为「全部实参按 Object、返回 Object」的猜测签名。
    private fun callDescriptor(expr: JasCall): String {
        val name = callMethodName(expr)
        val targetFunc = functionRegistry[name]
        if (targetFunc != null) {
            val paramDescs = targetFunc.parameters.joinToString("") {
                typeMapper.jvmDescriptorFromType(it.type)
            }
            val returnDesc = typeMapper.jvmReturnDescriptor(targetFunc.returnType)
            return "($paramDescs)$returnDesc"
        }
        val paramDescs = expr.args.joinToString("") {
            "Ljava/lang/Object;"
        }
        return "($paramDescs)Ljava/lang/Object;"
    }

    // 解析虚调用的方法描述符：先查本类方法表 methodRegistry[ownerName]；查不到再用反射加载
    //   ownerName 对应类、按「全 Object 形参」查找方法取返回类型；失败返回 null。
    // 局限：反射假定形参都是 Object，基本类型形参的方法几乎匹配不到；ownerName 的 '/' 会被
    //   换成 '.' 再 Class.forName；找不到时返回 null（调用方兜底为猜测描述符）。
    private fun resolveVirtualMethodDescriptor(expr: JasCall, ownerName: String): String? {
        val name = callMethodName(expr)
        val paramDescs = expr.args.joinToString("") { "Ljava/lang/Object;" }
        val classMethods = methodRegistry[ownerName]
        if (classMethods != null) {
            val func = classMethods[name]
            if (func != null) {
                val actualParamDescs = func.parameters.joinToString("") {
                    typeMapper.jvmDescriptorFromType(it.type)
                }
                val returnDesc = typeMapper.jvmReturnDescriptor(func.returnType)
                return "($actualParamDescs)$returnDesc"
            }
        }
        return try {
            val cls = Class.forName(ownerName.replace('/', '.'))
            val paramTypes = expr.args.map { java.lang.Object::class.java }.toTypedArray()
            val method = cls.getMethod(name, *paramTypes)
            val retDesc = "L" + method.returnType.name.replace('.', '/') + ";"
            "($paramDescs)$retDesc"
        } catch (e: Exception) {
            null
        }
    }

    // 调用的方法名：a.b(...) 取属性名 b；裸调用 f(...) 取标识符名 f；其它情况退化为 "invoke"。
    private fun callMethodName(expr: JasCall): String {
        return when (expr.target) {
            is JasPropertyAccess -> expr.target.property
            is JasIdentifier -> expr.target.name
            else -> "invoke"
        }
    }

    // new 的构造器描述符 "(${各实参描述符})V"。实参描述符由 expressionDescriptor 粗略推断，
    //   与实际构造器签名可能不符（基本类型/引用都可能退化为 Object）。
    private fun newDescriptor(expr: JasNew, ctx: CodegenContext?): String {
        val paramDescs = expr.args.joinToString("") {
            expressionDescriptor(it, ctx)
        }
        return "($paramDescs)V"
    }

    // 表达式 → 描述符（供 newDescriptor 推断构造实参）。比 inferExprDesc 更弱，Net 上大量
    //   情况退化为 Object，且 ctx 可为 null（无上下文时标识符直接按 Object）。
    private fun expressionDescriptor(expr: JasExpression, ctx: CodegenContext?): String {
        return when (expr) {
            is JasIntLiteral -> "I"
            is JasFloatLiteral -> "F"
            is JasBoolLiteral -> "Z"
            is JasStringLiteral -> "Ljava/lang/String;"
            is JasNullLiteral -> "Ljava/lang/Object;"
            is JasIdentifier -> {
                if (ctx != null) {
                    val local = ctx.locals.find { it.name == expr.name }
                    if (local?.type != null) typeMapper.jvmDescriptorFromType(local.type) else "Ljava/lang/Object;"
                } else "Ljava/lang/Object;"
            }
            is JasUnaryOp -> expressionDescriptor(expr.operand, ctx)
            is JasBinaryOp -> expressionDescriptor(expr.left, ctx)
            is JasArrayAccess -> expressionDescriptor(expr.target, ctx)
            is JasCastExpr -> typeMapper.jvmDescriptorFromType(expr.type)
            is JasNew, is JasCall, is JasPropertyAccess, is JasArrayCreation -> "Ljava/lang/Object;"
            else -> "Ljava/lang/Object;"
        }
    }

    // 按返回类型描述符发返回指令。Void→RETURN，窄基本类型→IRETURN，其余按类型分。
    // 调用方需保证此时栈顶已有正确类型的返回值（否则验证失败）。
    private fun generateReturn(mv: MethodVisitor, returnType: JasType?) {
        val retDesc = typeMapper.jvmReturnDescriptor(returnType)
        when (retDesc) {
            "V" -> mv.visitInsn(Opcodes.RETURN)
            "I", "Z", "B", "S", "C" -> mv.visitInsn(Opcodes.IRETURN)
            "J" -> mv.visitInsn(Opcodes.LRETURN)
            "F" -> mv.visitInsn(Opcodes.FRETURN)
            "D" -> mv.visitInsn(Opcodes.DRETURN)
            else -> mv.visitInsn(Opcodes.ARETURN)
        }
    }

    // 按描述符从局部变量槽载入到栈（xLOAD）。窄基本类型（Z/B/S/C）统一 ILOAD。
    private fun loadLocal(mv: MethodVisitor, desc: String, slot: Int) {
        when (desc) {
            "I", "Z", "B", "S", "C" -> mv.visitVarInsn(Opcodes.ILOAD, slot)
            "J" -> mv.visitVarInsn(Opcodes.LLOAD, slot)
            "F" -> mv.visitVarInsn(Opcodes.FLOAD, slot)
            "D" -> mv.visitVarInsn(Opcodes.DLOAD, slot)
            else -> mv.visitVarInsn(Opcodes.ALOAD, slot)
        }
    }

    // 按描述符把栈顶存入局部变量槽（xSTORE）。窄基本类型（Z/B/S/C）统一 ISTORE。
    private fun storeLocal(mv: MethodVisitor, desc: String, slot: Int) {
        when (desc) {
            "I", "Z", "B", "S", "C" -> mv.visitVarInsn(Opcodes.ISTORE, slot)
            "J" -> mv.visitVarInsn(Opcodes.LSTORE, slot)
            "F" -> mv.visitVarInsn(Opcodes.FSTORE, slot)
            "D" -> mv.visitVarInsn(Opcodes.DSTORE, slot)
            else -> mv.visitVarInsn(Opcodes.ASTORE, slot)
        }
    }

    // 二元算术/比较指令发射器：按 desc 选 long/double/float/int 版指令。
    //   "+" 对字符串特判为 String.concat；比较运算符转交 emitCompare；其余按位/移位。
    // 未知运算符兜底压 ICONST_0（多半与期望类型不符，属静默降级而非报错）。
    private fun emitBinaryArithmeticOp(mv: MethodVisitor, op: String, desc: String) {
        val isLong = desc == "J"
        val isDouble = desc == "D"
        val isFloat = desc == "F"
        val isString = desc.startsWith("Ljava/lang/String")
        when (op) {
            "+" -> {
                if (isString) {
                    mv.visitMethodInsn(Opcodes.INVOKEVIRTUAL, "java/lang/String", "concat", "(Ljava/lang/String;)Ljava/lang/String;", false)
                } else {
                    mv.visitInsn(if (isLong) Opcodes.LADD else if (isDouble) Opcodes.DADD else if (isFloat) Opcodes.FADD else Opcodes.IADD)
                }
            }
            "-" -> mv.visitInsn(if (isLong) Opcodes.LSUB else if (isDouble) Opcodes.DSUB else if (isFloat) Opcodes.FSUB else Opcodes.ISUB)
            "*" -> mv.visitInsn(if (isLong) Opcodes.LMUL else if (isDouble) Opcodes.DMUL else if (isFloat) Opcodes.FMUL else Opcodes.IMUL)
            "/" -> mv.visitInsn(if (isLong) Opcodes.LDIV else if (isDouble) Opcodes.DDIV else if (isFloat) Opcodes.FDIV else Opcodes.IDIV)
            "%" -> mv.visitInsn(if (isLong) Opcodes.LREM else if (isDouble) Opcodes.DREM else if (isFloat) Opcodes.FREM else Opcodes.IREM)
            "==", "!=", "<", ">", "<=", ">=" -> emitCompare(mv, op, desc)
            "&" -> mv.visitInsn(if (isLong) Opcodes.LAND else Opcodes.IAND)
            "|" -> mv.visitInsn(if (isLong) Opcodes.LOR else Opcodes.IOR)
            "^" -> mv.visitInsn(if (isLong) Opcodes.LXOR else Opcodes.IXOR)
            "<<" -> { if (isLong) mv.visitInsn(Opcodes.LSHL) else mv.visitInsn(Opcodes.ISHL) }
            ">>" -> { if (isLong) mv.visitInsn(Opcodes.LSHR) else mv.visitInsn(Opcodes.ISHR) }
            ">>>" -> { if (isLong) mv.visitInsn(Opcodes.LUSHR) else mv.visitInsn(Opcodes.IUSHR) }
            else -> mv.visitInsn(Opcodes.ICONST_0)
        }
    }

    // 比较运算 → 0/1 布尔结果。
    //   引用/数组：用 Object.equals（==/!= 语义），<、> 等无意义时恒压 0（ICONST_0）。
    //   数值：long 用 LCMP、float/double 用 FCMPG/DCMPG 得 -1/0/1，再按 op 选 IF 指令翻成 0/1；
    //   int 直接用 IF_ICMPxx。所有路径最终都在栈上留 0/1。
    private fun emitCompare(mv: MethodVisitor, op: String, desc: String) {
        if (desc.startsWith("L") || desc.startsWith("[")) {
            mv.visitMethodInsn(Opcodes.INVOKEVIRTUAL, "java/lang/Object", "equals", "(Ljava/lang/Object;)Z", false)
            if (op == "==") {
                return
            } else if (op == "!=") {
                // equals 结果取反。
                val trueLabel = Label()
                val endLabel = Label()
                mv.visitJumpInsn(Opcodes.IFNE, trueLabel)
                mv.visitInsn(Opcodes.ICONST_1)
                mv.visitJumpInsn(Opcodes.GOTO, endLabel)
                mv.visitLabel(trueLabel)
                mv.visitInsn(Opcodes.ICONST_0)
                mv.visitLabel(endLabel)
                return
            } else {
                // 引用类型的大小比较无定义：恒为 false。
                mv.visitInsn(Opcodes.ICONST_0)
                return
            }
        }
        val isLong = desc == "J"
        val isDouble = desc == "D"
        val isFloat = desc == "F"
        val trueLabel = Label()
        val endLabel = Label()
        if (isLong) {
            mv.visitInsn(Opcodes.LCMP)
            val ifOp = when (op) {
                "==" -> Opcodes.IFEQ; "!=" -> Opcodes.IFNE
                "<" -> Opcodes.IFLT; ">" -> Opcodes.IFGT
                "<=" -> Opcodes.IFLE; ">=" -> Opcodes.IFGE
                else -> Opcodes.IFEQ
            }
            mv.visitJumpInsn(ifOp, trueLabel)
        } else if (isDouble || isFloat) {
            // DCMPG/FCMPG：NaN 时得 1（配合 IF 系列使 NaN 比较恒为 false，符合 IEEE 语义）。
            val cmp = if (isDouble) Opcodes.DCMPG else Opcodes.FCMPG
            mv.visitInsn(cmp)
            val ifOp = when (op) {
                "==" -> Opcodes.IFEQ; "!=" -> Opcodes.IFNE
                "<" -> Opcodes.IFLT; ">" -> Opcodes.IFGT
                "<=" -> Opcodes.IFLE; ">=" -> Opcodes.IFGE
                else -> Opcodes.IFEQ
            }
            mv.visitJumpInsn(ifOp, trueLabel)
        } else {
            val ifOp = when (op) {
                "==" -> Opcodes.IF_ICMPEQ; "!=" -> Opcodes.IF_ICMPNE
                "<" -> Opcodes.IF_ICMPLT; ">" -> Opcodes.IF_ICMPGT
                "<=" -> Opcodes.IF_ICMPLE; ">=" -> Opcodes.IF_ICMPGE
                else -> Opcodes.IF_ICMPEQ
            }
            mv.visitJumpInsn(ifOp, trueLabel)
        }
        // 命中 true 跳 trueLabel 压 1，否则压 0，汇合到 endLabel。
        mv.visitInsn(Opcodes.ICONST_0)
        mv.visitJumpInsn(Opcodes.GOTO, endLabel)
        mv.visitLabel(trueLabel)
        mv.visitInsn(Opcodes.ICONST_1)
        mv.visitLabel(endLabel)
    }

    // 执行所有已登记的 defer 体，**逆序**（后登记的先执行，符合 defer 的 LIFO 语义）。
    // 注意：执行后并不清空 ctx.deferredBodies，若同一方法内有多处返回各自调用，只会在各条
    //   实际执行路径上跑各自那一次（不会重复），但语义上更接近「复制到每个出口」。
    private fun emitDeferredActions(mv: MethodVisitor, ctx: CodegenContext, owner: String) {
        for (body in ctx.deferredBodies.reversed()) {
            generateBlock(mv, body, ctx, owner)
        }
    }

    // 把「单个跳转指令 opcode」的结果翻成 0/1 布尔并留栈。是 emitCompare(op,desc) 的底层辅助。
    // 已知：当前没有任何调用点（死代码/备用）。
    private fun emitCompare(mv: MethodVisitor, opcode: Int) {
        val trueLabel = Label()
        val endLabel = Label()
        mv.visitJumpInsn(opcode, trueLabel)
        mv.visitInsn(Opcodes.ICONST_0)
        mv.visitJumpInsn(Opcodes.GOTO, endLabel)
        mv.visitLabel(trueLabel)
        mv.visitInsn(Opcodes.ICONST_1)
        mv.visitLabel(endLabel)
    }

    // 属性访问的字段描述符：在 fieldDescriptorMap 里**按名字**查（不区分 owner），查不到按 Object。
    private fun resolvePropertyDescriptor(expr: JasPropertyAccess): String {
        return fieldDescriptorMap[expr.property] ?: "Ljava/lang/Object;"
    }

    // 元素类型描述符 → 数组读取指令。Z/B 共用 BALOAD，C 用 CALOAD，S 用 SALOAD，
    //   引用类型用 AALOAD。
    private fun arrayLoadOpcode(desc: String): Int {
        return when (desc) {
            "I" -> Opcodes.IALOAD; "J" -> Opcodes.LALOAD; "F" -> Opcodes.FALOAD
            "D" -> Opcodes.DALOAD; "Z", "B" -> Opcodes.BALOAD; "C" -> Opcodes.CALOAD
            "S" -> Opcodes.SALOAD; else -> Opcodes.AALOAD
        }
    }

    // 元素类型描述符 → 数组写入指令（与 arrayLoadOpcode 对称）。
    private fun arrayStoreOpcode(desc: String): Int {
        return when (desc) {
            "I" -> Opcodes.IASTORE; "J" -> Opcodes.LASTORE; "F" -> Opcodes.FASTORE
            "D" -> Opcodes.DASTORE; "Z", "B" -> Opcodes.BASTORE; "C" -> Opcodes.CASTORE
            "S" -> Opcodes.SASTORE; else -> Opcodes.AASTORE
        }
    }

    // 推断虚调用的接收者类型：仅当调用形如「标识符.方法()」时可用——取该标识符的声明类型
    //   （locals），退而求其次取初始化器推断类型（inferredTypes）。其它形态返回 null（→ Object）。
    private fun resolveReceiverType(expr: JasCall, ctx: CodegenContext): JasType? {
        val target = expr.target
        if (target is JasPropertyAccess) {
            val inner = target.target
            if (inner is JasIdentifier) {
                val local = ctx.locals.find { it.name == inner.name }
                local?.type?.let { return it }
                ctx.inferredTypes[inner.name]?.let { return it }
            }
        }
        return null
    }

    // 推断「数组表达式的元素描述符」，用于选择 xALOAD/xASTORE。
    //   - 标识符：其声明类型为 JasArrayType 时取内层描述符，否则 Object；
    //   - 数组创建表达式：直接取该数组类型描述符；
    //   - 属性访问/调用/new 等：一律 Object（无法静态确定）。
    private fun tryResolveArrayElementDesc(expr: JasExpression, ctx: CodegenContext): String {
        return when (expr) {
            is JasIdentifier -> {
                val local = ctx.locals.find { it.name == expr.name }
                val t = local?.type
                if (t is JasArrayType) typeMapper.jvmDescriptorFromType(t.inner) else "Ljava/lang/Object;"
            }
            is JasPropertyAccess -> "Ljava/lang/Object;"
            is JasArrayCreation -> typeMapper.jvmDescriptorFromType(expr.type)
            is JasCall -> "Ljava/lang/Object;"
            is JasNew -> "Ljava/lang/Object;"
            else -> "Ljava/lang/Object;"
        }
    }

    // 压入 int 常量：-1..5 用 ICONST，字节范围用 BIPUSH，短整型范围用 SIPUSH，其余 LDC。
    // 目的：尽可能用单字节指令，减小常量池/代码体积（纯优化，语义不变）。
    private fun pushInt(mv: MethodVisitor, value: Int) {
        when (value) {
            -1 -> mv.visitInsn(Opcodes.ICONST_M1)
            0 -> mv.visitInsn(Opcodes.ICONST_0)
            1 -> mv.visitInsn(Opcodes.ICONST_1)
            2 -> mv.visitInsn(Opcodes.ICONST_2)
            3 -> mv.visitInsn(Opcodes.ICONST_3)
            4 -> mv.visitInsn(Opcodes.ICONST_4)
            5 -> mv.visitInsn(Opcodes.ICONST_5)
            in Byte.MIN_VALUE..Byte.MAX_VALUE -> mv.visitIntInsn(Opcodes.BIPUSH, value)
            in Short.MIN_VALUE..Short.MAX_VALUE -> mv.visitIntInsn(Opcodes.SIPUSH, value)
            else -> mv.visitLdcInsn(value)
        }
    }

    // ── Generic signature helpers ──
    // 泛型 Signature 属性（JVMS §4.7.9）的拼接工具：仅在类/方法带类型参数时调用，
    //   产出供反射/调试使用的泛型信息；不影响字节码语义，缺了只是丢失泛型元数据。
    // 类型本体仍由 TypeMapper 提供描述符，这里只负责按 JVM 签名文法组装字符串。

    // 类签名：<T1:Bound1;T2:Bound2;...>LSuper<...>;  —— 类型参数列表 + 超类签名。
    //   无超类时按 Object(JasAnyType) 处理。
    private fun jvmClassSignature(typeParams: List<JasTypeParameter>, superclass: JasType?): String {
        val sb = StringBuilder()
        sb.append('<')
        for (tp in typeParams) {
            sb.append(jvmTypeParamSignature(tp))
        }
        sb.append('>')
        sb.append(jvmTypeSignature(superclass ?: JasAnyType))
        return sb.toString()
    }

    // 方法签名：<类型参数>(参数签名)返回签名。
    private fun jvmMethodSignature(func: JasFunction): String {
        val sb = StringBuilder()
        sb.append('<')
        for (tp in func.typeParameters) {
            sb.append(jvmTypeParamSignature(tp))
        }
        sb.append('>')
        sb.append('(')
        for (p in func.parameters) {
            sb.append(jvmTypeSignature(p.type ?: JasAnyType))
        }
        sb.append(')')
        sb.append(jvmTypeSignature(func.returnType ?: JasUnitType))
        return sb.toString()
    }

    // 单个类型参数签名：Name:Bound。无界时按 Object(JasAnyType) 处理。
    private fun jvmTypeParamSignature(tp: JasTypeParameter): String {
        val sb = StringBuilder()
        sb.append(tp.name)
        sb.append(':')
        sb.append(jvmTypeSignature(tp.bound ?: JasAnyType))
        return sb.toString()
    }

    // 类型 → 泛型签名。基本类型即描述符；Unit→V；命名类型带类型实参时写成 L<内部名><实参...>;；
    //   数组前缀 '['；可空/非空包装直接透传；通配符 * / +上界 / -下界。
    // 已知限制：bytes/regex/reference/tuple/function 统一退化为 Ljava/lang/Object;，
    //   pointer 退化为 "J"（早期内存模型残留）。这些类型在当前 JVM 后端没有真实表示。
    private fun jvmTypeSignature(type: JasType): String {
        val desc = typeMapper.jvmDescriptorFromType(type)
        return when (type) {
            is JasPrimitiveType -> desc
            is JasUnitType -> "V"
            is JasStringType -> "Ljava/lang/String;"
            is JasAnyType -> "Ljava/lang/Object;"
            is JasNamedType -> {
                if (desc.startsWith("L") && desc.endsWith(";")) {
                    val internal = desc.substring(1, desc.length - 1)
                    if (type.typeArguments.isEmpty()) {
                        "L$internal;"
                    } else {
                        val args = type.typeArguments.joinToString("") { jvmTypeSignature(it) }
                        "L$internal<$args>;"
                    }
                } else {
                    desc
                }
            }
            is JasArrayType -> "[" + jvmTypeSignature(type.inner)
            is JasNullableType -> jvmTypeSignature(type.inner)
            is JasNonNullType -> jvmTypeSignature(type.inner)
            is JasWildcardType -> {
                if (type.bound == null) "*"
                else if (type.extends) "+${jvmTypeSignature(type.bound!!)}"
                else "-${jvmTypeSignature(type.bound!!)}"
            }
            is JasBytesType -> "Ljava/lang/Object;"
            is JasRegexType -> "Ljava/lang/Object;"
            is JasPointerType -> "J"
            is JasReferenceType -> "Ljava/lang/Object;"
            is JasTupleType -> "Ljava/lang/Object;"
            is JasFunctionType -> "Ljava/lang/Object;"
        }
    }

    // 判断块里是否（直接或经嵌套 JasBlock）含 JasDefer。
    // 局限：只下探嵌套块，不进入 if/while/for 等语句体；因此经典位置（循环体内 defer）检测不到。
    //   返回值当前仅在 generateMethod 里被计算但未被使用（见该处说明）。
    private fun hasDeferBlock(block: JasBlock): Boolean {
        return block.statements.any { it is JasDefer || (it is JasBlock && hasDeferBlock(it)) }
    }
}
