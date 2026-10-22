// ============================================================================
// PlatformMethodResolver —— 平台（JDK）类型实例方法的真实签名解析器
// 所属层次：compiler 后端（JvmIrBackend）的协作组件，只做「平台类型事实查询」，
//           不参与 IR 生成、不写字节码、也不做诊断。
// 与谁协作：JvmIrBackend 在生成 `v:<name>` 成员调用、且该接收者不是本次编译单元
//           声明的类型时调用本类；本类用 JDK 反射（Class.getMethods）找到唯一的
//           公开实例方法，返回其真实 JVM 描述符与「声明类是否接口」。
// 对外契约：resolve(receiverInternalName, methodName, argumentDescriptors)：
//           命中唯一候选时返回 [PlatformMethodSignature]；加载失败/零候选/多候选
//           一律返回 null，调用方必须退回原有描述符推导逻辑。
// 设计要点（隔离的变化点）：
//   - 「平台类型有哪些方法」只在本类里通过反射回答，JvmIrBackend 不再需要维护
//     JDK 方法签名白名单；将来要换/扩展平台类型识别，只改本类。
//   - 本类不猜重载：多个候选就返回 null（宁可用旧行为，也不写出错误描述符）。
//   - 结果按（接收者, 方法名, 实参描述符串）缓存在实例内；JvmIrBackend 本身不可
//     并发/重入，因此这里不再加锁。
// 已知边界：
//   - 只解析接收者类 **自身及其父类型** 的 public 实例方法；平台静态方法（C28）
//     与链式调用类型传播（C27 第二步）不在本类职责内。
//   - 不初始化被加载的类（Class.forName(..., false, loader)），避免触发静态初始化
//     副作用；类初始化失败不会被本类观察到。
// ============================================================================

package jasper.compiler

import java.lang.reflect.Modifier
import org.jetbrains.org.objectweb.asm.Type

/**
 * 做什么：描述一个已解析的平台方法——真实 JVM 方法描述符 + 声明类是否接口。
 *
 * 什么时候用：[PlatformMethodResolver.resolve] 成功时由后端读取：
 *   - [descriptor] 直接写进 visitMethodInsn；
 *   - [declaringClassIsInterface] 决定用 INVOKEINTERFACE 还是 INVOKEVIRTUAL；
 *   - [parameterDescriptors] / [returnDescriptor] 供实参装箱宽化与返回值适配使用。
 *
 * 前置条件：仅由解析器在「恰好一个候选」时构造；不暴露给调用方手工拼装。
 * 失败模式：纯数据对象，不抛异常；描述符非法只可能来自 ASM，而 ASM 由反射得到的
 *   Method 生成，正常 JDK 类型不会失败。
 * 为什么这样设计：把「描述符」与「是否接口」放在同一个返回值里，避免后端先查描述符
 *   再回头重新反射一次；参数/返回描述符从 ASM 描述符派生，保证三处口径完全一致。
 */
data class PlatformMethodSignature(
    /** 方法的真实 JVM 描述符，如 `(I)Ljava/math/BigInteger;`。 */
    val descriptor: String,
    /** 声明该方法的类是否为接口；决定调用指令（INVOKEINTERFACE / INVOKEVIRTUAL）。 */
    val declaringClassIsInterface: Boolean
) {
    /** 形参描述符列表，顺序与 [descriptor] 一致。 */
    val parameterDescriptors: List<String> =
        Type.getArgumentTypes(descriptor).map { it.descriptor }

    /** 返回描述符（可能是 `V`）。 */
    val returnDescriptor: String = Type.getReturnType(descriptor).descriptor
}

/**
 * 做什么：用 JDK 反射把「平台接收者 + 方法名 + 实参描述符」解析成真实方法描述符。
 *
 * 什么时候用：JvmIrBackend 生成实例成员调用且 `useDeclared == false` 时；接收者必须是
 *   平台类型（不是本次编译单元声明的类型），否则不应调用本类（由调用方先行过滤）。
 *
 * 前置条件：
 *   - [receiverInternalName] 为 JVM 内部名（`java/math/BigInteger`），不是简单名；
 *   - 实参描述符已由 TypeMapper 从 IR 实参类型得到，可以是基本类型/引用/数组；
 *   - 本实例非线程安全，调用方（JvmIrBackend）必须串行使用。
 *
 * 失败模式：任何无法确定的情况都返回 null，绝不抛业务异常：
 *   - 类加载失败（非 JDK 类型、类路径缺失、非法名字）；
 *   - 零个候选（方法不存在/参数个数不符/参数类型不兼容）；
 *   - 多个候选（重载无法仅凭实参描述符消歧）。
 *   反射本身的安全异常（SecurityException）也收敛为 null，交由调用方退回旧行为。
 *
 * 为什么这样设计：平台方法的签名是外部事实，唯一可靠来源是 JDK 自身；把「加载类、
 *   匹配候选、缓存结果」全部收口到本类，JvmIrBackend 只消费结果，避免后端散落
 *   JDK 知识（旧实现的 isKnownJdkFluentCall 白名单就是这种知识的一个特例，本类
 *   不替换它，只补齐「其他平台方法」）。
 */
class PlatformMethodResolver {

    /**
     * 缓存键：接收者内部名 + 方法名 + 实参描述符串。
     * 用数据类而不是拼接字符串，避免方法名/描述符里出现分隔符时发生键冲突。
     */
    private data class CacheKey(
        val receiverInternalName: String,
        val methodName: String,
        val argumentDescriptors: List<String>
    )

    /**
     * 解析缓存。值允许为 null（表示「已查过且无唯一结果」），避免对同一调用点
     * 反复反射。HashMap 是任务书要求的实现；本类不并发使用，无需同步包装。
     */
    private val cache = HashMap<CacheKey, PlatformMethodSignature?>()

    /**
     * 编译器自身使用的类加载器；Class.forName 的第三个参数必须是它，才能看到
     * 编译器运行时可见的 JDK/依赖类。极端环境下 classLoader 为 null 时退回系统加载器。
     */
    private val loader: ClassLoader =
        PlatformMethodResolver::class.java.classLoader ?: ClassLoader.getSystemClassLoader()

    /**
     * 做什么：解析平台接收者上唯一的公开实例方法，返回其真实描述符。
     *
     * 什么时候用：后端确认接收者不是本次编译单元声明的类型后调用。
     * 前置条件：参数见类注释；[argumentDescriptors] 的长度必须等于实参个数。
     * 失败模式：见类注释——加载失败/零候选/多候选都返回 null，不抛业务异常。
     * 为什么这样设计：把「候选唯一性」作为返回非 null 的唯一条件，强制后端在
     *   无法确定时保留旧行为，而不是猜一个重载写出运行期才炸的描述符。
     */
    fun resolve(
        receiverInternalName: String,
        methodName: String,
        argumentDescriptors: List<String>
    ): PlatformMethodSignature? {
        val key = CacheKey(receiverInternalName, methodName, argumentDescriptors)
        // containsKey 与 get 分开：缓存的 null 也要被视为「已解析过」。
        if (cache.containsKey(key)) return cache[key]
        val resolved = resolveUncached(receiverInternalName, methodName, argumentDescriptors)
        cache[key] = resolved
        return resolved
    }

    /**
     * 做什么：真正执行类加载与候选匹配；不做缓存。
     * 什么时候用：仅 [resolve] 未命中缓存时。
     * 前置条件：同 [resolve]。
     * 失败模式：所有反射/加载异常都在此收敛为 null。
     * 为什么单独拆出：缓存逻辑与解析逻辑分离，便于阅读与单测推演；解析部分保持纯查询。
     */
    private fun resolveUncached(
        receiverInternalName: String,
        methodName: String,
        argumentDescriptors: List<String>
    ): PlatformMethodSignature? {
        val receiverClass = loadClass(internalNameToBinaryName(receiverInternalName)) ?: return null
        val candidates = try {
            receiverClass.methods.filter { method ->
                // 只考虑 public 实例方法：static 不在此机制范围（C28），
                // bridge/synthetic 是编译器生成的转发器，保留它们会让候选重复。
                !Modifier.isStatic(method.modifiers) &&
                    !method.isBridge &&
                    !method.isSynthetic &&
                    method.name == methodName &&
                    method.parameterCount == argumentDescriptors.size
            }
        } catch (e: SecurityException) {
            // 安全管理器拒绝反射时按「无法确定」处理，退回旧行为。
            return null
        }
        val matched = candidates.filter { method ->
            argumentDescriptors.indices.all { i ->
                argumentAssignable(argumentDescriptors[i], method.parameterTypes[i])
            }
        }
        // 零个或多个都不猜：多个候选说明仅凭 JVM 描述符无法消歧（例如 int 实参
        // 同时匹配 int 与 Object 形参），此时交给调用方沿用旧逻辑。
        if (matched.size != 1) return null
        val method = matched.single()
        return try {
            PlatformMethodSignature(
                descriptor = Type.getMethodDescriptor(method),
                declaringClassIsInterface = method.declaringClass.isInterface
            )
        } catch (e: SecurityException) {
            null
        }
    }

    /**
     * 做什么：判断一个实参描述符是否可赋给平台方法的形参类型。
     *
     * 什么时候用：候选过滤时对每个形参调用一次。
     * 前置条件：argDescriptor 是合法 JVM 描述符；parameterClass 来自反射。
     * 失败模式：不抛异常。实参类加载不到时只接受 Object 形参（保守策略），
     *   避免把未知类型错误地当成某个接口/父类的子类型。
     * 为什么这样设计：JVM 调用描述符必须与形参完全一致，唯一允许的「不同」是
     *   源语言层面的装箱/引用宽化——这里显式建模这些允许项，其余一律视为不兼容。
     */
    private fun argumentAssignable(argDescriptor: String, parameterClass: Class<*>): Boolean {
        val primitiveClass = primitiveClassForDescriptor(argDescriptor)
        if (primitiveClass != null) {
            // 基本类型：形参必须是同一基本类型；或目标形参是其包装类 / Object（装箱）。
            if (parameterClass.isPrimitive) return primitiveClass == parameterClass
            return parameterClass == java.lang.Object::class.java ||
                parameterClass == boxedClassFor(primitiveClass)
        }
        // 引用/数组类型：尝试用加载器解析实参类，再按 JVM 赋值兼容性判断。
        val argumentClass = loadClass(descriptorToBinaryName(argDescriptor)) ?: return parameterClass == java.lang.Object::class.java
        return parameterClass.isAssignableFrom(argumentClass)
    }

    /**
     * 做什么：把 JVM 内部名转成 Class.forName 需要的二进制名。
     * 什么时候用：加载接收者类时。
     * 前置条件：内部名不含数组描述符（接收者方法调用不会是数组类型）。
     * 失败模式：不校验；非法名字交给 Class.forName 抛异常后由 [loadClass] 吞掉。
     * 为什么单独成函数：数组描述符与普通内部名的转换规则不同，集中一处避免调用方各自替换。
     */
    private fun internalNameToBinaryName(internalName: String): String =
        internalName.replace('/', '.')

    /**
     * 做什么：把实参 JVM 描述符转成 Class.forName 需要的名字。
     * 什么时候用：判断引用实参是否可赋给形参时。
     * 前置条件：desc 以 `L`/`[` 开头（基本类型由调用方先行分流）。
     * 失败模式：非法描述符在加载阶段返回 null，由调用方按「加载不到」处理。
     * 为什么单独成函数：对象描述符去掉首尾 `L`/`;`，数组描述符则保留 `[` 只替换 `/`，
     *   这两种形态不能共用一条简单替换规则。
     */
    private fun descriptorToBinaryName(desc: String): String {
        return if (desc.startsWith("[")) {
            desc.replace('/', '.')
        } else {
            desc.removePrefix("L").removeSuffix(";").replace('/', '.')
        }
    }

    /**
     * 做什么：尝试加载类但不初始化；失败返回 null。
     * 什么时候用：接收者类与引用实参类的加载。
     * 前置条件：binaryName 为 Class.forName 可接受的二进制名。
     * 失败模式：ClassNotFoundException / LinkageError / SecurityException 全部返回 null，
     *   由调用方退回旧行为；本函数不记录日志、不抛异常。
     * 为什么用 initialize=false：解析签名只需要方法元数据，初始化平台类可能触发
     *   不必要的静态初始化副作用；解析器应保持只读。
     */
    private fun loadClass(binaryName: String): Class<*>? {
        return try {
            Class.forName(binaryName, false, loader)
        } catch (e: ClassNotFoundException) {
            null
        } catch (e: LinkageError) {
            null
        } catch (e: SecurityException) {
            null
        }
    }

    /**
     * 做什么：把基本类型描述符映射到对应的 Class；引用/数组描述符返回 null。
     * 什么时候用：[argumentAssignable] 判断基本类型实参时。
     * 前置条件：desc 为单字符 JVM 描述符或引用描述符。
     * 失败模式：未知字符按引用处理（返回 null），后续加载失败即判定不兼容。
     * 为什么集中映射：basic descriptor → Class 的映射只在这里出现一次，避免多个分支各写一套。
     */
    private fun primitiveClassForDescriptor(desc: String): Class<*>? {
        return when (desc) {
            "I" -> java.lang.Integer.TYPE
            "J" -> java.lang.Long.TYPE
            "F" -> java.lang.Float.TYPE
            "D" -> java.lang.Double.TYPE
            "Z" -> java.lang.Boolean.TYPE
            "B" -> java.lang.Byte.TYPE
            "S" -> java.lang.Short.TYPE
            "C" -> java.lang.Character.TYPE
            else -> null
        }
    }

    /**
     * 做什么：返回基本类型对应的包装类。
     * 什么时候用：[argumentAssignable] 中判断「实参是基本类型、形参是包装类」这一允许项。
     * 前置条件：primitiveClass 必须是 [primitiveClassForDescriptor] 返回的八种之一。
     * 失败模式：未知类型返回 Object.class（保守地只接受 Object 形参）。
     * 为什么需要：JVM 描述符层面的装箱目标只可能是对应包装类，集中映射避免字符串拼装。
     */
    private fun boxedClassFor(primitiveClass: Class<*>): Class<*> {
        return when (primitiveClass) {
            java.lang.Integer.TYPE -> java.lang.Integer::class.java
            java.lang.Long.TYPE -> java.lang.Long::class.java
            java.lang.Float.TYPE -> java.lang.Float::class.java
            java.lang.Double.TYPE -> java.lang.Double::class.java
            java.lang.Boolean.TYPE -> java.lang.Boolean::class.java
            java.lang.Byte.TYPE -> java.lang.Byte::class.java
            java.lang.Short.TYPE -> java.lang.Short::class.java
            java.lang.Character.TYPE -> java.lang.Character::class.java
            else -> java.lang.Object::class.java
        }
    }
}
