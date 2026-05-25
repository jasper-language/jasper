# Jasper 语法设计总览

本文记录当前已经收口的 Jasper 语法设计。`.g4` 以 `jasper-compiler/src/main/antlr` 为准，根目录 `.g4` 保持同步快照。

## 核心定位

Jasper 是 GC 语言，也是变量安全型语言：

- 默认非空，只有 `T?` 可以为 `null`。
- 变量使用前必须完成初始化。
- `var` 是可重新赋值绑定，`const` 是不可重新赋值绑定。
- 对象生命周期由 GC 管理。
- 有显式 GC 安全指针语法，但没有 `unsafe`，也没有借用系统。

## 已确认语法

类型核心：

```jasper
T
T?
*T
T[]
List<T>
(A, B)
```

指针表达式：

```jasper
var p: *T = &value;
var x: T = *p;
```

控制流：

```jasper
switch (x) {
    case 1 => { }
    default => { }
}
```

- 没有 `match`。
- `switch` 必须有 `default`。
- `case => block` 明确禁止 fallthrough。

并发和延迟：

```jasper
go task();
var result = await future;
defer cleanup();
```

lambda：

```jasper
var f = lambda () { return 1; };
var add = lambda (a: int32, b: int32) {
    return a + b;
};
```

异常：

```jasper
try {
    risky();
} catch (e: Error) {
    handle(e);
} finally {
    cleanup();
}
```

## 保留但不实现

以下关键字永久保留给非 GC 方向或底层实验，但 Jasper 当前及未来主线不实现：

```text
raw shared weak auto unique atomic pointer handle
```

它们是 lexer 保留字，不能作为普通标识符使用；parser 不把它们接入有效语言语法。

## 当前修饰符

当前有效声明修饰符：

```text
public protected private static abstract final
```

访问级别：

- `public`：任意模块可见。
- `protected`：本类与子类可见。
- `private`：当前声明作用域或当前类可见。

Jasper 是单继承语言，因此 `protected` 沿唯一 superclass 链解析；同包非子类不可见。`protected constructor` 允许出现。

`final` 的语义：

- `final class`：禁止继承。
- `final function`：禁止 override。
- `final` 不负责变量不可重新赋值；变量绑定不可变由 `const` 表达。

`export` / `extern` 不作为关键字语法，也不进入保留关键字集合。包导出或外部绑定需要时使用注解，例如 `@Export` / `@Extern`。

`late` / `decorate` 不作为关键字语法。需要时使用注解表达：

```jasper
@Late
var cache: string;

@Decorate
function f(): void { }

@Export
class User { }
```

`throws` 保留为函数签名元信息，不做 Java 风格 checked exception。实际异常控制流仍是 `throw + try/catch/finally`。

`lock` 是并发控制语句，不是修饰符：

```jasper
lock (mutex) {
    criticalSection();
}
```

## File Structure

Each `.jas` file contains exactly one top-level type declaration: `class`, `interface`, `enum`, or annotation type. Top-level functions, variables, and statements are not valid file items.

## `.jaso` Module Loading

`.jaso` is Jasper's planned compiled module format and runtime loader input. It is a runtime-managed module unit, not a Java-style set of dynamically loaded classes.

Jasper explicitly does not use Java's open-ended `ClassLoader` model as the language module model. JVM may be an execution backend, but JVM class loading does not define Jasper module semantics.

## 当前后端路线

当前完成路线是：

```text
.g4 -> ANTLR -> Jasper AST -> TypeChecker -> Kotlin IR -> JVM bytecode -> GraalVM
```

最终目标是支持 JVM 产物或二进制产物。现阶段二进制路线优先通过 GraalVM
native-image 消化 JVM bytecode，而不是提前推进 LLVM。

因此 Jasper 语义不能建立在 Java/Kotlin 的开放动态机制上：普通执行路径不应依赖开放式
Java 反射、动态类加载、运行时代码生成、classpath 扫描或隐藏的 Kotlin runtime dispatch。

反射可以存在，但必须是 Jasper 自己控制的能力：显式、编译器可知，并由编译器生成的元数据
或注册信息支撑，能够被 GraalVM native-image 保留下来，而不是继承 Java 那种开放式反射查找模型。

The Jasper runtime owns module verification, lifecycle, dependencies, permissions, exported symbols, tasks, resources, and unload/dispose decisions.
