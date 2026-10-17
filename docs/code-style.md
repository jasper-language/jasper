# Jasper 代码风格与工程规范（中文）

> 本文是 `jasper-build/TASKBOOK.md` 第 2b 节（用户 2026-10-17 定，长期有效）在**本仓库**的可执行落地版。
> 目标不是"看起来规范"，而是保证：**一个没参与过本项目的中国工程师，只读代码与注释，就能接手某个模块而不必来问**。
> 机械验收脚本：`scripts/check-code-style.sh`（文件头说明 / 公共 API 中文 KDoc / 依赖方向 / 近似重复）。

---

## 0. 四句话总纲

1. **工程化**：分层清晰、错误显式、无死代码、可测试、可重复构建。
2. **设计模式化**：只在**确实存在变化点**处使用模式，并在注释里写清"它隔离了什么变化"；**反对为模式而模式**。
3. **模块化 + 高内聚低耦合 + 可复用**：依赖单向、禁止环；重复逻辑抽到**正确的层**；不造上帝类。
4. **详细的中文注释**：公共 API 有 KDoc，复杂逻辑解释"为什么 / 边界 / 前置条件"。

---

## 1. 目录与层次职责表

| 目录 / 包 | 层次（rank） | 单一职责 | 对外契约（谁可以依赖它） |
|---|---|---|---|
| `jasper.ast` | 0（最低） | 语言无关的 AST 数据模型 + ANTLR 解析树 → AST 的构建 | 所有层可依赖 |
| `jasper.compiler.SymbolTable` | 1（symbol） | 符号收集与查询（类型、成员签名、导入） | checker / translator / compiler |
| `jasper.checker` | 2 | 语义与类型检查，产出诊断 | compiler |
| `jasper.translator` | 2 | AST → 中间表示（IR）翻译、类型映射、IR 打印 | compiler + backend |
| `jasper.annotations` | 2 | 注解处理阶段：`@Getter/@Setter/@EqualsAndHashCode/@Mapper` 两段式签名合成 | compiler |
| `jasper.stdlib` | 2 | 标准库源码资源的确定性装载（不依赖编译器内部） | compiler |
| `jasper.llvm`（`jasper-llvm` 子项目） | 2（backend） | LLVM 后端（实验性） | 仅依赖 `ast` |
| `jasper.compiler`（编排层，含 `compiler/archive`） | 3（最高） | 串联"预处理→解析→注解→检查→翻译→后端"的流水线 | 只被 `main`/测试调用 |
| ANTLR 生成物（`jasper.parser`，构建期生成） | 不入层 | 词法/语法分析器 | 由 `ast` 与 `compiler` 使用 |

### 依赖方向表（上 → 下，只许这么走）

```
compiler(3)
  ├── annotations(2)
  ├── translator(2)  ──► symbol(1) ──► ast(0)
  ├── checker(2)     ──► symbol(1)
  ├── stdlib(2)      （不依赖任何 jasper.* 内部包）
  └── ast(0)
backend=llvm(2) ──► ast(0)
```

- **下层永远不知道上层存在**。例如 `ast` 不得 import `checker`，`checker` 不得 import `compiler`。
- 同层之间**不互相依赖**（checker / translator / annotations / stdlib / backend 之间无 import）。
- `jasper.compiler.SymbolTable` 是唯一被"越级"引用的基础设施（物理位置在 `compiler` 包，语义层次在 `symbol`）。
  脚本显式把它归入 symbol 层；**这是一个已知的包放置问题**，已在模块化审计里登记为待办，不是"允许随意跨层"。

---

## 2. 高内聚低耦合自检问题

改任何一处前，先回答：

1. **这个类/文件是不是做了两件事？** 出现"顺便也做点别的"就拆。
2. **这个依赖是必须的吗？** 能不能只依赖一个更稳定的接口/数据类，而不是整个对象？
3. **换个后端 / 换个目标平台要改几处？** 如果要改多处，说明变化点没有被隔离。
4. **这段逻辑会不会在第二处出现？** 会，就抽到正确层（见 §3），别复制。
5. **新来的工程师只读注释能否接手？** 不能，就补注释而不是补口头说明。

---

## 3. 可复用策略（抽象必须放在正确的层）

- **禁止复制粘贴式复用**：同一段逻辑出现第 2 次就要抽公共件（工具函数 / 协议 / 泛型 / 策略对象）。
- **参数化而非复制**：用泛型、接口、策略对象消除"同一逻辑按类型/平台抄一遍"。
- **复用要有边界**：抽公共件必须能回答"谁在用、为什么放在这一层"，避免造上帝工具类。
  - 只服务单一层内部的小工具 → 留在该层（如 `jasper.translator` 内部的类型映射辅助）。
  - 被多个下层共享的模型 → 放到**最低的公共层**（如 AST 类型、`SymbolTable`）。
  - 跨目标复用（JVM / native / JS）的公共机制 → 不得把 JVM 细节渗进上层。
- **可验证**：近似重复纳入 `scripts/check-code-style.sh` 的候选清单；新增重复需在报告里说明理由。

---

## 4. 命名约定

| 对象 | 约定 | 示例 |
|---|---|---|
| 包 | 全小写，按层次分 | `jasper.translator` |
| 类 / 接口 / 对象 | 大驼峰；AST 节点以 `Jas` 前缀 | `JasFunction`、`TypeMapper` |
| 函数 / 属性 | 小驼峰；布尔用 `is/has/can` | `findFunction`、`isSafe` |
| 常量 | 全大写下划线 | `TypeChecker.MAX_DEPTH` |
| 测试 | 反引号中文/英文句子，**说明守住什么** | `` `test 未闭合字符串产生词法错误` `` |

- 同一概念全仓同名（例如"成员签名"一律用 `memberSignatures`，不要混用 `members`/`fields`）。
- 公共 API 必须写清：**做什么 / 什么时候用（不该用）/ 前置条件 / 失败模式 / 为什么这样设计**。

---

## 5. 中文注释模板

### 5.1 文件头模块说明（每个 `*.kt` 顶部，`package` 之前或紧后，20 行内）

```kotlin
// ============================================================================
// <模块名> —— <一句话职责>
// 所属层次：<ast/checker/translator/compiler/annotations/stdlib/backend>
// 与谁协作：<上游谁调用我>；我调用 <下游谁>
// 对外契约：<我对外提供什么稳定接口>
// 扩展点 / 已知限制：<未来变化点在哪；当前不支持什么>
// ============================================================================
```

### 5.2 公共 API KDoc（`class` / `interface` / `object` / `fun` 紧邻上方）

```kotlin
/**
 * 做什么：<一句话>
 *
 * 什么时候用：<调用时机>；什么时候不该用：<反例>。
 * 前置条件：<入参不变量 / 状态要求>。
 * 失败模式：<抛什么异常、返回什么、调用方要处理什么>。
 * 为什么这样设计：<关键取舍；为什么不用另一种显眼做法>。
 */
```

> 说明：Kotlin 约定 **override 继承父类型契约**；但本仓要求公共 override 也补一行中文说明，
> 讲清它对应哪条语法规则 / 哪个 IR 形态，方便接手（见 §9 判据）。

### 5.3 复杂逻辑行内注释（`//`）

只写"这句在赋值"没有价值，必须覆盖**三要素**：

- **为什么**这样做（而不是另一种做法）；
- **边界**在哪（什么输入会越界、槽位/帧/异常范围怎么算）；
- **前置条件 / 不变量**是什么。

---

## 6. 模式清单（每条写清"隔离了什么变化点"与落点）

| 模式 | 隔离的变化点 | 本仓落点 |
|---|---|---|
| **Visitor** | grammar 规则集 / AST 遍历方式的变化 | ANTLR `JasperParserBaseVisitor` + `ast/JasperAstBuilder.kt`；IR 打印 `translator/IrDumper.kt` 的 `IrElementVisitorVoid` |
| **Registry / Factory** | "新增一种注解"时不再改主流程 | `annotations/AnnotationProcessor.kt`：按注解名分派处理器；`AnnotationIndex` 登记结果 |
| **Strategy** | 后端 / 目标平台的变化（JVM 先落地，native/JS 后续接入） | `JvmIrBackend` / `archive/JvmBackend` / `jasper-llvm/LlvmBackend` 对同一 AST 的实现 |
| **两段式合成** | "生成的方法在类型检查时还不可见"的问题 | 注解阶段只合成**签名**（`JasSyntheticAccessor` 等）；方法体在 lowering 阶段生成（`translator/JasperToIrTranslator.kt`） |

> **引入判据**：这个模式让**哪一处未来变化**不再扩散？写不进注释就不要引入。

---

## 7. 明确禁止

- ❌ 空话注释：`// 处理数据`、`// 循环`、`// TODO 以后再说`（无信息量）。
- ❌ 注释与行为不一致（改了行为不改注释 = 缺陷，评审按 bug 处理）。
- ❌ 为模式而模式：没有变化点硬塞 Factory/Strategy。
- ❌ 用 `!!` / 吞异常掩盖失败；诊断必须带位置与可读中文信息。
- ❌ 死代码：不用的函数/分支删掉，不留"以后可能用"。
- ❌ 破坏单向依赖：为写注释方便而新增跨层/循环依赖。

---

## 8. 验收方式

```bash
./scripts/check-code-style.sh                               # 目标：注释 0 缺失、非法依赖 0、依赖环 0
taskset -c 0-3 ./gradlew test --console=plain --no-daemon    # 0 failed，测试数不下降
./scripts/check-grammar.sh                                   # ✓ 语法与权威仓库一致
```

评审五问：**职责是否单一？依赖方向是否单向无环？变化点是否被模式隔离？复用是否抽到了正确的层？新来的工程师能否只靠注释接手？**
