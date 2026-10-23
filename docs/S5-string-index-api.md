# S5 第二步：按码点访问字符串的 API 设计

> 状态：**设计提案（待用户裁决）**。本文件只回答“按码点访问 `string` 的公开 API
> 应该长什么样”，不修改任何 `.g4`、检查器、翻译器、后端或标准库实现。
>
> 依据：`docs/11-语言约定.md` §11.3 已定 `string.length` 是 **Unicode 码点数**；
> S5 第一步（提交 `f64ae22`）已经实现 `.length` 码点计数、`.byteLength()`（UTF-8 字节数），
> 并在检查器里拒绝 `s[i]`。本设计不得与这些已落地行为冲突。
>
> 术语：本文的“码点”指 Unicode **scalar value**（U+0000–U+10FFFF，排除代理区
> U+D800–U+DFFF）；“码元”特指 UTF-16 code unit。Jasper 的语言级字符串语义按
> **原始码点序列**定义，不做隐式 Unicode 规范化（§11.5）。

---

## 0. 结论摘要

- 本文比较 **5 种**候选方案：
  1. `charAt(i): int32`（UTF-16 码元）；
  2. `codePointAt(i): int32`（码点）；
  3. `s[i]` 返回码点（`int32` / `char`）；
  4. `s[i]` 返回单码点 `string`；
  5. `codePoints()` / `chars()` / `iterate()` 迭代协议。
- **推荐方案 2：`String.codePointAt(i: int32): int32`**，索引单位与 `.length` 完全一致；
  不新增 `charAt`，不重新打开 `s[i]`，保持 `.length`、`byteLength()`、`s[i]` 拒绝的现状。
- 推荐方案需要改动的层：**检查器**（成员解析与参数校验）、**翻译器**（合成调用）、
  **JVM 后端**（码点索引 → UTF-16 偏移 → 取码点）、**未来原生后端**（UTF-8 扫描）。
  `TypeMapper` 在“返回 `int32`”的前提下不需要改；若改成返回 `char`，必须先修当前
  `char` 的 16 位实现（见 §6.5），因此不建议本轮绑定 `char`。
- 仍需用户裁决的 3 个问题见 §7：**API 形态**、**越界/负索引语义**、**返回类型与
  非法代理项策略**。

---

## 1. 共同前提

所有候选都建立在以下已确定语义上，不能各自另立一套“长度”：

| 事实 | 当前状态 | 对本设计的影响 |
|------|----------|----------------|
| `string.length` | Unicode 码点数，O(n)（已实现） | 任何“下标”的单位必须能让 `i < s.length` 成立 |
| `string.byteLength()` | UTF-8 字节数（已实现） | 与字符访问正交，不作为索引单位 |
| `s[i]` | 检查器拒绝（已实现） | 推荐方案继续保持拒绝；若要启用，必须明确重新裁决 |
| `==` / `hash` | 按原始码点序列，不做规范化 | 取出的码点/子串也应保持原始序列语义 |
| `.graphemeCount()` | 语言约定中存在，v1 未实现 | 码点访问不等于“用户感知字符”访问 |
| `char` | 文档定义为 `char32`（32 位 scalar） | 但当前 JVM 映射是 16 位 `C`，存在实现偏差 |

**字符串抽象值**：本文假设 Jasper 的合法 `string` 是“Unicode scalar value 序列”。
从 JVM/平台边界进入的、含未配对代理项的字符串属于**畸形输入**，其处理策略是 §7
的裁决项之一，不能由后端静默决定。

**不引入新语法**：`codePointAt(...)` / `chars()` 是普通成员调用；`s[i]` 现有文法本来就
可以解析为下标表达式（只是检查器拒绝）。因此本设计不涉及 `.g4`。

---

## 2. 候选方案

### 2.1 方案 A：`charAt(i: int32): int32`（UTF-16 码元）

**语法示例**

```jasper
var s: string = "a😀b";
var unit: int32 = s.charAt(1);   // 0xD83D，😀 的高位代理项
```

**语义定义（若采用）**

- 索引单位：UTF-16 code unit。
- 合法范围：`0 <= i < utf16Length`。但语言目前**没有** `utf16Length` 成员，只有
  码点数的 `.length`，因此调用方无法用 `.length` 正确表达这个范围。
- 返回值：`0..0xFFFF` 的码元值，可能是孤立代理项；它不是 Unicode scalar value。
- 越界：`i < 0` 或 `i >= utf16Length` 时抛越界错误。
- 代理对中间位置：`charAt` 必然返回“半个字符”（高/低代理项），这是本方案的固有结果，
  不是异常边界。
- 复杂度：JVM/Android 上 `String.charAt` 是 O(1)；原生 UTF-8 运行时要么额外维护
  UTF-16 视图（内存/转换成本），要么每次 O(n) 扫描，无法同时做到“语言无关 + O(1)”。
- 分配：无。

**中文 / emoji / 组合字符**

- 中文（BMP）：通常 1 个 UTF-16 单元，和码点数一致。
- emoji（补充平面）：1 个码点、2 个 UTF-16 单元，返回两个代理项。
- 组合字符：每个码点一个单元，仍然不保证“用户感知字符”边界。

**结论**：不推荐。它把 JVM 的 UTF-16 表示泄漏成语言公开 API，并且与已经实现的
`.length`（码点）单位不一致；这是最危险的一类“看起来能跑、遇到 emoji 就错位”的设计。

---

### 2.2 方案 B：`codePointAt(i: int32): int32`（码点）

**语法示例**

```jasper
var s: string = "a😀b";
var cp: int32 = s.codePointAt(1);  // 0x1F600
var n: int32 = s.length;           // 3，索引合法范围 [0, 3)
```

**语义定义**

- 索引单位：Unicode 码点，**与 `s.length` 完全一致**。
- 合法范围：`0 <= i < s.length`。
- 返回值：该位置的 Unicode scalar value（`0..0x10FFFF`，合法字符串中不会出现
  U+D800–U+DFFF）。
- 越界：`i < 0`、`i >= s.length`（含空串任意索引）抛越界错误；不钳制、不回绕。
- 复杂度：O(i)，最坏 O(n)；额外空间 O(1)。**不承诺 O(1)**。
- 分配：无（返回 `int32`，不创建子串）。
- 求值次数：接收者表达式只求值一次，索引表达式只求值一次，顺序为“接收者 → 索引”。
- 规范化：不改变原始码点序列；不做 NFC/NFD。
- 畸形输入：如果平台边界传入了含未配对代理项的字符串，必须按 §7 的策略显式处理，
  不能把代理项当作合法 scalar 静默返回。

**中文 / emoji / 组合字符**

- 中文：每个汉字 1 个码点，行为直观。
- emoji：`"a😀b"` 的索引 1 返回 `0x1F600`，不会拆成代理项。
- 组合字符：`"e\u0301"` 的索引 0 是 `e`、索引 1 是 U+0301；它返回的是**码点**，
  不是字素簇。需要“é 作为一个整体”时应使用未来的字素簇 API，而不是本方法。
- ZWJ emoji / 旗帜 / 肤色修饰：每个码点单独返回；同样不承诺字素边界。

**JVM/Android 映射**

JVM 的 `String.codePointAt(int)` 参数是 **UTF-16 索引**，不能直接用语言层的码点索引
`i` 调用。正确做法是先把“第 i 个码点”映射为 UTF-16 偏移，再取码点：

```text
offset = s.offsetByCodePoints(0, i)   // O(i)，并负责大部分越界检查
cp     = s.codePointAt(offset)        // O(1)
```

两个方法都是 Java/Android 标准 API；`offsetByCodePoints` 对负数或超出码点数的
`i` 会抛 `IndexOutOfBoundsException`，`i == codePointCount` 时返回 `length()`，
随后 `codePointAt(length())` 会抛越界。后端可以依赖这一行为，也可以显式检查以统一
错误文本；具体异常映射见 §7。

**结论**：推荐。它是唯一同时满足“索引单位 = `.length` 单位”“不泄漏 UTF-16”
“无额外分配”“原生后端可移植”的随机访问方案。

---

### 2.3 方案 C：`s[i]` 返回码点（`int32` / `char`）

**语法示例**

```jasper
var s: string = "a😀b";
var cp: int32 = s[1];   // 0x1F600
```

**语义定义（若采用）**

- 索引单位：码点，合法范围 `[0, s.length)`；越界/负数行为同方案 B。
- 返回值：`int32` 码点，或 `char`（`char32`）码点。
- 复杂度：与 B 相同，O(i) 最坏 O(n)；但**语法本身不显示这个成本**。
- 需要改的现状：S5 第一步已经在检查器里把 `string` 的 `s[i]` 判为错误；采用本方案
  等于重新打开该裁决，并要改检查器、翻译器（以及可能的诊断/测试），但不需要改 `.g4`。

**问题**

1. **隐藏 O(n)**：下标语法在数组/容器上是 O(1) 的直觉，字符串上却是 O(n)。这会让
   `for (var i = 0; i < s.length; i++) { s[i] }` 变成 O(n²)，而源码完全看不出来。
2. **与 S5 第一步冲突**：现有已实现行为是“拒绝字符串整数索引，要求显式 API”。
   重新启用会让“字符串不是数组”这一语言约定变得模糊。
3. **返回 `char` 时绑定当前 `char` 缺陷**：当前 `char` 在 JVM 上映射为 16 位 `C`，
   补充平面码点会截断；返回 `int32` 虽可避开，但下标语法仍保留上述两个问题。
4. **未来难以收回**：语法一旦公开，后续想改成方法调用或迭代协议，会形成兼容负担。

**结论**：不推荐本轮采用。它的语义本身可以和 `.length` 对齐，但语法形式会误导性能
预期，并且要推翻刚刚落地的“拒绝 `s[i]`”决定。

---

### 2.4 方案 D：`s[i]` 返回单码点 `string`

**语法示例**

```jasper
var s: string = "a😀b";
var ch: string = s[1];   // "😀"，长度为 1 的 string
```

**语义定义（若采用）**

- 索引单位：码点；合法范围 `[0, s.length)`；越界/负数抛错。
- 返回值：恰好包含一个码点的 `string`（不是 `char`，也不是码点整数）。
- 复杂度：O(i) 定位 + 一次对象分配；最坏 O(n) 定位，且每次访问都可能分配。
- 分配：有。对 `for` 循环逐字符处理会制造大量短命对象，GC 压力明显。
- 等价性：`s[i] == "😀"` 可以直接比较；`s[i].length == 1` 恒成立（合法输入下）。

**中文 / emoji / 组合字符**

- 中文/emoji 都能返回完整码点，不会拆代理对。
- 组合字符仍然按码点拆分，不保证字素簇。
- 与方案 C 一样，下标语法隐藏 O(n) 与分配成本；而且“取一个字符却得到一个字符串”
  容易让调用方继续拼接，形成 O(n²) 的字符串构建。

**结论**：不推荐。它比方案 C 多了一层分配，却没有解决下标语法的性能误导问题。

---

### 2.5 方案 E：迭代协议 `codePoints()` / `chars()` / `iterate()`

**语法示例（示意，具体名字待定）**

```jasper
var s: string = "a😀b";
for (cp in s.codePoints()) {       // cp: int32
    // 依次得到 0x61、0x1F600、0x62
}

// 或者显式迭代器形态（若标准库提供 Iterable/Iterator 协议）
var it = s.codePoints().iterator();
while (it.hasNext()) {
    var cp: int32 = it.next();
}
```

**语义定义（若采用）**

- 按字符串顺序产出**码点**，不产出 UTF-16 码元。
- 无索引，因此没有“越界/负索引”问题；空串产出 0 个元素。
- 复杂度：完整遍历 O(n)，每个元素 O(1) 均摊；不提供随机访问。
- 分配：取决于迭代器实现；理想情况下是游标对象（O(1)），不应为每个码点分配子串。
- 命名风险：Java 的 `String.chars()` 产出的是 **UTF-16 码元**，不是码点；若 Jasper 的
  `chars()` 产出码点，会和平台 API 同名不同义。建议用 `codePoints()` 明确语义。

**中文 / emoji / 组合字符**

- 中文/emoji 均按码点产出，不拆代理对。
- 组合字符仍然是多个码点；字素簇需要独立 API。
- 对“顺序扫描整个字符串”的算法，E 是比 B 更好的性能路径；但它不能替代随机访问。

**结论**：不作为唯一的替代方案，但**推荐作为方案 B 的后续配套**：B 负责随机访问，
E 负责线性扫描，避免“用 O(n) 的 `codePointAt` 写 O(n²) 循环”。本轮是否同轮实现，
见 §7 之外的实现排期（本任务不实现）。

---

## 3. 对比

| 维度 | A `charAt`（码元） | B `codePointAt`（码点） | C `s[i]` 码点 | D `s[i]` string | E 迭代协议 |
|------|-------------------|--------------------------|----------------|------------------|-------------|
| 与 `.length`（码点）一致 | **否**：单位不同 | **是** | **是** | **是** | **是** |
| 中文（BMP） | 正常 | 正常 | 正常 | 正常 | 正常 |
| emoji（补充平面） | 拆成两个代理项 | 1 个码点 | 1 个码点 | 1 个码点字符串 | 1 个码点 |
| 组合字符 / ZWJ | 按码点拆 | 按码点拆 | 按码点拆 | 按码点拆 | 按码点拆 |
| 复杂度 | JVM O(1)；原生 O(n) 或需 UTF-16 视图 | O(i)，最坏 O(n) | 同 B，但语法隐藏成本 | O(i)+分配，最坏 O(n) | O(n) 全遍历 |
| 额外分配 | 无 | 无 | 无 | 每次一个 string | 游标/迭代器（可实现为 O(1)） |
| JVM/Android 映射 | `String.charAt` 直接映射 | `offsetByCodePoints` + `codePointAt` | 同 B | 同 B + `new String(...)` | `codePoints()`/手动扫描 |
| 原生 UTF-8 可移植性 | 差：需 UTF-16 视图或逐次转码 | 好：顺序解码 | 好（但语法误导） | 好，但多分配 | 最好：天然顺序 |
| 自举实现难度 | JVM 容易，原生难 | 中等：码点索引扫描 + 边界 | 同 B | 同 B + 分配/GC | 中等：迭代器协议 |
| 主要风险 | 与 `.length` 错位、代理项泄漏 | O(n) 被误当 O(1) | 隐藏 O(n)、推翻已落地拒绝 | 隐藏 O(n)+分配 | 无随机访问、命名歧义 |

**与平台字符串的映射成本**：JVM/Android 内部是 UTF-16。B/C/D 每次都要把“码点索引”
换算成 UTF-16 偏移，因此是 O(i)；A 是唯一在 JVM 上 O(1) 的方案，但代价是语言层
绑定 UTF-16。未来 Jasper OS 原生运行时若采用 UTF-8（当前最自然的选择），B/C/D 都
需要顺序解码，E 最自然；若采用 UTF-32，B 可以 O(1)，但内存开销和与 JVM 的表示差异
又是另一组权衡。**语言 API 不应把某一种内部编码写死**，这是推荐 B 而不是 A 的核心理由。

**自举难度**：B 需要一个“从字符串起点数到第 i 个码点”的解码循环；这和 `.length`
的码点计数可以共享同一个 UTF-8 解码器/游标，代码量可控。A 需要 UTF-16 表示或转码，
在自举的 UTF-8 运行时上反而更重。D 还需要分配器与 GC 配合；E 需要一个迭代器协议
（标准库已有 `Iterable` 方向，见 `docs/11-语言约定.md` §11.6 的相关协议）。

---

## 4. 推荐方案

### 4.1 推荐

**采用方案 B：**

```text
String.codePointAt(index: int32): int32
```

同时明确：

- **不新增 `charAt`**；`charAt` 的 UTF-16 语义与 `.length` 冲突，且会把平台表示
  泄漏到语言层。
- **不重新启用 `s[i]`**；S5 第一步的拒绝行为保持不变。
- **`codePoints()` 迭代协议作为后续配套**，不与本方案互斥；它解决“顺序扫描不应
  写成 O(n²)”的问题，但不是随机访问的替代品。
- `.length`、`.byteLength()`、`.graphemeCount()` 的既有语义不变。

### 4.2 推荐理由

1. **单位唯一**：索引单位、`.length` 单位、`==` 比较单位都是码点。不会出现
   “`i < s.length` 却取到半个 emoji”的错位。
2. **平台中立**：JVM/Android 的 UTF-16 只是后端实现细节；未来 UTF-8 原生运行时
   可以按同一语义实现，不需要把字符串改成 UTF-16。
3. **成本显式**：方法名表明这是“按码点取字符”，文档明确 O(i)；下标语法 `s[i]`
   会让人误以为 O(1)，方法调用至少不会强化这个错觉。
4. **无分配**：返回 `int32`，适合热路径；不会像方案 D 那样在循环里制造短命 string。
5. **不绑定 `char` 的当前缺陷**：`int32` 能表示全部 scalar value；不需要先修
   `char` 的 16 位映射（该修复应独立立项，见 §6.5）。
6. **实现路径最小**：不需要改 `.g4`；可以复用现有 `byteLength()` 的“检查器特例 +
   翻译器合成调用 + 后端指令”模式，改动面可控。

### 4.3 建议的规范语义（供实现任务直接引用）

- 接收者：非空 `string`；平台边界传入 `null` 时按现有 JVM 语义抛 NPE（语言 `string`
  类型本身不可空）。
- 参数：恰好 1 个，类型 `int32`；多余/缺失实参在编译期报错。
- 索引：`0 <= index < s.length`。
- 返回：该索引处的 Unicode scalar value，类型 `int32`。
- 越界：负数、`index >= s.length`、空串任意索引 → 抛语言级越界错误（JVM 后端可映射
  为 `IndexOutOfBoundsException`；不静默返回 0、不钳制、不回绕）。
- 复杂度：O(index)，最坏 O(n)，O(1) 额外空间。
- 求值：接收者只求值一次，索引只求值一次；顺序为接收者 → 索引。
- Unicode：不规范化、不按字素簇合并；代理项/畸形输入按 §7 的裁决处理。
- 命名：与 Java `String.codePointAt` 同名但**索引单位不同**（Jasper 是码点索引，
  Java 是 UTF-16 索引）；文档和错误信息必须写清楚，后端绝不能把 `index` 直接传给
  `java/lang/String.codePointAt(I)I`。

---

## 5. 推荐方案需要改动的层

本节只描述未来实现任务的范围，**本任务不实施**。

### 5.1 检查器（`jasper.checker.TypeChecker`）

- 在 `string` 内建成员的分流处（当前处理 `.length` 与 `byteLength()` 的位置）增加
  `codePointAt`：
  - 接收者静态类型必须是 `JasStringType`；
  - 实参个数必须为 1；
  - 实参类型必须与 `int32` 兼容；
  - 返回类型为 `JasPrimitiveType("int32")`。
- 保持 `JasArrayAccess` 对 `JasStringType` 的拒绝；不要因为新增方法而放宽 `s[i]`。
- 若用户最终选择其他方案，这里就是需要改判的**唯一语义入口**；不要让翻译器或后端
  自行猜测成员名。

### 5.2 翻译器（`jasper.translator.JasperToIrTranslator`）

- 增加与 `emitStringByteLength` 同构的 `emitStringCodePointAt(receiver, index, ctx)`：
  - 接收者只翻译一次（避免 `next().codePointAt(0)` 触发两次副作用）；
  - 索引只翻译一次；
  - 用合成调用名（例如 `__jasper_string_code_point_at__`）把“码点访问”语义交给后端，
    复用现有 `callNames` 契约。
- 返回类型用 `typeMapper.intType()`；不要把它翻译成对 `java/lang/String` 的真实方法
  调用，否则会命中 Java 的 UTF-16 索引语义。

### 5.3 JVM 后端（`jasper.compiler.JvmIrBackend`）

- 增加 `__jasper_string_code_point_at__` 分支。
- 建议的栈序列（接收者只出现一次）：

  ```text
  receiver                  // [s]
  DUP                       // [s, s]
  ICONST_0                  // [s, s, 0]
  index                     // [s, s, 0, index]
  INVOKEVIRTUAL java/lang/String.offsetByCodePoints(II)I
                            // [s, offset]
  INVOKEVIRTUAL java/lang/String.codePointAt(I)I
                            // [cp]
  ```

  `offsetByCodePoints(0, index)` 负责把码点索引换成 UTF-16 偏移，并对负数和超出
  码点总数的索引抛越界；`index == length` 时偏移等于 `length()`，随后 `codePointAt`
  抛越界。这样不需要额外的临时局部变量，也不会重复求值接收者。
- 若最终要求统一的错误文本/错误类型，可在调用前显式检查，但要注意显式
  `codePointCount` 会多一次 O(n) 扫描；优先依赖一次 `offsetByCodePoints` 的边界行为。
- 不要生成 `String.charAt`；也不要把 `index` 直接喂给 `String.codePointAt`。

### 5.4 `TypeMapper`

- 推荐方案返回 `int32`，`TypeMapper` **不需要改**：`string` 已经映射为
  `java/lang/String`，`int32` 已经是 `kotlin.Int` / `I`。
- 如果用户裁决改成返回 `char`（`char32`），则必须先解决当前 `char` 的实现偏差：
  - `TypeMapper.builtinTypes["char"] = "kotlin.Char"`、`jvmDescriptors["kotlin.Char"] = "C"`；
  - `JasCharLiteral` 翻译时使用 `expr.value.toChar()`，补充平面码点会截断；
  - 这不是本设计能顺手修的内容，应单独立项，否则会把 16 位缺陷带进公开 API。

### 5.5 未来原生后端（Jasper OS / LLVM 方向）

- 若原生字符串是 UTF-8：实现“从起点解码并前进 `index` 个码点”，再解出一个 scalar；
  需要与 `.length` 共用同一个 UTF-8 解码/游标模块，避免两套边界规则。
- 若未来改成 UTF-32：`codePointAt` 可以 O(1)，但 API 语义不变；这正是把表示留在
  后端、把“码点索引”留在语言层的好处。
- ABI 建议保持简单：接收者句柄/指针 + `int32` 索引 → `int32` 码点；不新增结构体，
  不改变现有字符串 ABI。若原生后端需要额外的长度/容量字段，应在其内部游标中处理，
  不把布局暴露给语言层。
- 畸形 UTF-8/代理项的处理必须与 JVM 后端一致（见 §7），不能一个后端抛错、另一个
  后端返回替换字符。

### 5.6 测试与标准库（后续）

- 检查器负向用例：参数个数、参数类型、`s[i]` 仍被拒绝。
- 端到端用例：ASCII、BMP 中文、补充平面 emoji、空串、首/尾索引、负数、`index == length`、
  接收者副作用只求值一次。
- 若后续加入 `codePoints()`：验证与 `codePointAt` 对同一字符串产出完全一致的序列，
  并验证空串/组合字符/ZWJ 序列。
- 本任务只出设计，不新增上述测试。

---

## 6. 不兼容风险

### 6.1 `.length` 是码点、`charAt` / `s[i]` 是码元时的错位

这是最需要避免的组合。以 `"a😀b"` 为例：

```text
码点数（.length）        = 3
UTF-16 码元数            = 4   （a=1，😀=2，b=1）

若 charAt 的索引是 UTF-16 码元，而循环写成：
for (var i = 0; i < s.length; i++) {
    var u = s.charAt(i);   // i = 0,1,2
}
实际取到：a、😀 的高位代理项、😀 的低位代理项；b 永远不会被访问。
```

反过来，如果为了取到 `b` 把循环条件改成 `i < utf16Length`，又没有办法从 `.length`
得到 `utf16Length`（当前没有该成员），调用方只能猜或额外计算。**同一个字符串有两个
“长度”单位，是 API 层面的结构性错误**，不是文档能补救的。

同理，`s[i]` 若返回 UTF-16 码元，会与 `.length` 产生完全相同的错位。

### 6.2 越界、负索引、空串

推荐语义必须一次性定清楚，避免各后端各自实现：

- `s.codePointAt(-1)`：抛越界，**不做 Python 式“从末尾数”**；否则 `-1` 的语义会和
  `s.length - 1` 混淆，而且原生/ JVM 的负索引处理不一致。
- `s.codePointAt(s.length)`：抛越界；不能返回 0 或 `null`。
- `s.codePointAt(s.length + 1)`：抛越界；不能钳制到最后一个码点。
- 空串：任何索引都越界；实现不能因为“没有字符”而返回 0。
- 若选择返回 `int32?` 而不是抛错，则所有调用点都要处理 `null`，且 `int32?` 会改变
  表达式的类型；这属于 §7 的裁决项。

### 6.3 代理对中间位置

- 方案 A：`charAt` 在代理对中间返回孤立代理项。这个值不是 Unicode scalar value，
  不能安全地转成 `char32`/`string`；如果要求“代理对中间位置抛错”，那 `charAt` 就
  无法逐码元遍历，语义自相矛盾。
- 方案 B/C：索引是码点索引，**按定义不存在“代理对中间位置”**；但 JVM 平台字符串
  可能含未配对代理项（例如从 Java 互操作传入），此时“码点”本身是否合法需要 §7 的
  畸形输入策略。
- 方案 D：返回的单码点字符串在合法输入下不会拆代理对；畸形输入仍要按同一策略处理。

### 6.4 组合字符与“用户感知字符”

`codePointAt` 返回的是码点，不是字素簇。以下都不是“一个字符”：

- `"e" + U+0301`：2 个码点，视觉上是一个带重音的 e；
- `"👨‍👩‍👧‍👦"`：多个码点加 ZWJ；
- 旗帜 emoji：两个 regional indicator 码点；
- 肤色修饰：基础 emoji + 修饰码点。

因此：

- `s.length` / `codePointAt` 的“字符”是 Unicode 码点层面的字符；
- 需要“用户看到的一个字”时，必须用 `.graphemeCount()` 或未来的字素簇迭代/切分 API；
- 不能把 `codePointAt` 的宣传语写成“取一个字符”，否则中文/emoji 用户会误解。

### 6.5 `char` 的当前实现偏差（与本 API 直接相关）

文档把 `char` 定义为 `char32`（32 位 Unicode scalar value），但当前实现：

- `TypeMapper` 把 `char` 映射到 `kotlin.Char` / JVM 描述符 `C`（16 位）；
- `JasCharLiteral` 在翻译时调用 `expr.value.toChar()`，补充平面码点会被截断。

如果本 API 返回 `char`，那么 `"😀".codePointAt(0)` 会在返回值层面被截断，直接暴露
一个已知缺陷。因此推荐 `int32`；`char32` 的修复应另立任务，不应由本设计偷偷扩大范围。

### 6.6 与 Java/Android 同名方法的混淆

Java 的 `String.codePointAt(int index)` 的 `index` 是 UTF-16 索引。Jasper 的
`codePointAt` 若采用码点索引，二者**同名不同义**：

- 平台互操作代码不能把 Jasper 的 `i` 直接传给 Java 方法；
- 后端必须走 `offsetByCodePoints` 转换；
- 文档、IDE 提示、错误信息都应注明“Jasper 索引单位是码点”。

这是可接受的成本（语义与 `.length` 一致更重要），但必须显式写进实现注释和测试。

### 6.7 未来重新启用 `s[i]` 的兼容负担

如果本轮确定 `codePointAt` 并继续保持 `s[i]` 非法，未来再启用 `s[i]` 不会让已有
合法代码失效（原本非法的代码变成合法），但会形成第二个公开入口，并可能让用户误以为
两种写法复杂度相同。建议把“字符串不支持整数下标”作为长期决定，而不是临时状态；
若未来一定要加，必须同时提供明确的 O(n) 文档和迁移说明。

---

## 7. 需要用户裁决的问题（最多 3 条）

以下问题不替用户拍板；推荐项只是设计分析结论。

### Q1. 采用哪种主 API 形态？

- **A（推荐）**：`String.codePointAt(i: int32): int32`，索引单位 = 码点；保持 `s[i]` 非法。
- **B**：启用 `s[i]` 返回码点（`int32` 或 `char`），放弃“字符串不能整数索引”的现状。
- **C**：启用 `s[i]` 返回单码点 `string`。
- **D**：采用 `charAt(i): int32`（UTF-16 码元），并接受与 `.length` 的单位不一致。
- **E**：只提供 `codePoints()` / `chars()` 迭代，不提供随机访问。

> 说明：A 与 E 可以并存；若选 A，建议把 E 作为后续性能配套，而不是本轮必须实现。

### Q2. 越界与负索引的行为？

- **A（推荐）**：负数、`i >= s.length`、空串任意索引一律抛语言级越界错误
  （JVM 映射 `IndexOutOfBoundsException`），不返回哨兵值。
- **B**：越界/负数返回 `null`，API 返回类型改为 `int32?`，调用方必须处理可空。
- **C**：负数按 Python 风格从末尾解释（`-1` 为最后一个码点），越界仍抛错。
- **D**：钳制到 `[0, length-1]`（不推荐；会掩盖调用方的索引错误）。

### Q3. 返回值类型与非法代理项（畸形输入）策略？

- **A（推荐）**：返回 `int32`；合法字符串保证返回 Unicode scalar value；从平台边界
  传入的未配对代理项按“拒绝/抛错”处理（在字符串进入语言层时校验，或在读取时抛错），
  不静默当作合法码点。
- **B**：返回 `int32`；未配对代理项替换为 U+FFFD（替换字符），保证调用方总能拿到值，
  但会丢失“输入非法”的信息。
- **C**：返回 `char`（`char32`）；需要先修复当前 `char` 的 16 位映射与字面量截断，
  再定本 API，属更大的独立改动。

> 若 Q1 选 D（`charAt`），Q3 还需要额外定义“代理对中间位置”的行为；这正是方案 A
> 不推荐的原因之一。

---

## 8. 本任务边界

- 只新增本文件；**不修改**任何 `.g4`、Kotlin 源码、标准库、测试或构建脚本。
- 不实现 `codePointAt`，不改检查器/翻译器/后端；§5 只是未来实现任务的拆解建议。
- 不改变 `.length`、`.byteLength()`、`s[i]` 拒绝的现有行为。
- 形式化验证留待实现任务；本文件不引入可执行语义。

---

## 9. 参考

- `docs/11-语言约定.md` §11.3（计数与长度语义）、§11.5（字符串相等性与规范化）。
- `docs/03-类型系统.md` §3.2（`char` / `char32` 别名）。
- S5 第一步提交 `f64ae22`：`string.length` 码点计数、`byteLength()`、拒绝 `s[i]`。
- `jasper-compiler/src/main/kotlin/jasper/checker/TypeChecker.kt`：`string` 内建成员
  分流与 `JasArrayAccess` 拒绝逻辑。
- `jasper-compiler/src/main/kotlin/jasper/translator/JasperToIrTranslator.kt`：
  `emitStringCodePointLength` / `emitStringByteLength` 的合成调用模式。
- `jasper-compiler/src/main/kotlin/jasper/compiler/JvmIrBackend.kt`：
  `__jasper_string_length__` / `__jasper_string_byte_length__` 的字节码实现。
- `jasper-compiler/src/main/kotlin/jasper/translator/TypeMapper.kt`：`string` 与
  `char` 的 JVM 映射现状。
