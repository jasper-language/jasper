# JUCS：Jasper 统一命令行标准（P1 实现状态）

本文描述 **JUCS（Jasper Unified Command Standard）** 在当前仓库中**已经实现**的部分，
以及**明确尚未实现**的部分。未实现的能力不会被写成"已支持"。

JUCS 的目标：同一语义在 Jasper 生态里只有一种标准表达；命令契约只声明一次，
即可被人类、脚本、IDE、GUI 与 AI 以一致的类型、权限与结果语义调用。

## 1. 命令格式

```text
<program> <resource> <action> [--参数 取值 | --开关 | --no-开关]...
```

示例（当前可直接运行）：

```text
jucs system info get
jucs fs file list --path /home --all
jucs fs file list --path . --sort size --order desc --limit 10 --output json
```

稳定命令 ID 是 `program.resource.action`（例如 `fs.file.list`）。
`program`、`resource`、`action` 必须是 ASCII 小写 kebab-case（`[a-z][a-z0-9]*(-[a-z0-9]+)*`，每段 ≤32 字节）。

### 参数规则

- 业务参数必须具名：单值为 `--name value`，布尔为 `--flag` / `--no-flag`。
- 解析器按契约消费取值，因此取值可以以 `-` 开头（例如 `--path --weird` 是合法的路径取值）。
- 不支持短选项、选项缩写与模糊匹配。
- 重复的单值参数被拒绝；可重复参数必须在契约里声明并保留出现顺序。
- 未知参数、位置参数、原生命令里的 `--` 一律拒绝。
- 时长必须带单位：`500ms`、`30s`、`2m`、`1h`。无单位一律视为类型错误。

### 只读元命令

`--help`、`--version`、`--schema` 在业务动作之前处理，**绝不会执行处理器**。
它们同时出现时视为冲突错误。

## 2. 官方动词

```text
list get search create update remove start stop restart status install uninstall run
```

不允许用 `show`、`info`、`rm` 等同义变体替代官方动词作为原生标准接口。

> W-2（2026-10-20）：容器 / 集合元素移除统一用 `remove`。`delete`、`clear`、`drop`
> 按各自语义使用：`clear` = 清空容器，`drop` = 释放所有权 / 资源；`delete`
> 是 Jasper 词法的保留字（已在语法中但解析器未启用），不得用作容器移除动词。

## 3. 全生态参数字典

| 参数 | 固定类型 | 语义 |
| --- | --- | --- |
| `--name` / `--id` / `--path` | string / string / path | 资源名称、稳定标识、路径 |
| `--all` / `--filter` / `--sort` / `--order` / `--limit` | bool / string / string / asc\|desc / int | 范围、过滤、排序、方向、数量上限 |
| `--destination` | path | 输出目标 |
| `--timeout` | duration | 超时（必须带单位） |
| `--dry-run` / `--force` | bool | 预览 / 声明的保护例外（都不能绕过授权） |

运行时对**所有**命令隐式提供三个参数：`--output`（`table` / `json` / `ndjson`）、
`--verbose`、`--quiet`。命令契约不得重复声明它们。

## 4. 输出

| 格式 | stdout 内容 |
| --- | --- |
| `table` | 人读表格，不承诺可解析，不含 ANSI 颜色码 |
| `json` | 恰好一个对象：`{"jucsVersion","requestId","status","data"}` |
| `ndjson` | 每个数据项一行，无信封 |

数据只写 stdout，诊断与错误只写 stderr。JSON 模式下 stdout 不会混入任何人类文案。

## 5. 错误与退出码

错误对象：`{"code","category","message","details","retryable","requestId"}`。

| 退出码 | 含义 |
| --- | --- |
| 0 | 成功 |
| 2 | 命令结构错误 |
| 3 | 类型/参数校验失败或命令不存在 |
| 4 | 资源不存在 |
| 5 | 权限拒绝 |
| 6 | 冲突 |
| 7 | 超时 |
| 8 | IO 错误 |
| 9 | 服务不可用 |
| 10 | 内部错误 |
| 11 | 部分成功 |
| 130 | 调用方取消 |

调用方判断细节必须使用结构化错误码（例如 `JUCS.ARGUMENT.UNKNOWN`），退出码只表达粗粒度类别。

## 6. 当前已注册命令

| 命令 ID | 说明 | 效果 |
| --- | --- | --- |
| `system.info.get` | 宿主操作系统与运行环境信息 | 只读 |
| `fs.file.list` | 枚举目录条目（不跟随符号链接） | 只读 |

两者的 `--help` 与 `--schema` 都由同一份命令契约生成，不存在手写的第二份定义。

## 7. 权限现状（重要）

当前只有一个**最小权限门**：只放行只读命令，任何声明为可逆修改、不可逆修改或外部影响的命令
一律拒绝，并返回 `JUCS.PERMISSION.DENIED`。

尚未实现：调用主体身份、路径作用域绑定、`dry-run` 预览与 `planId`、审计落盘、TOCTOU 复核、
幂等键与截止时间。这些能力在实现并通过测试之前不会被描述为可用。

## 8. 尚未实现

- Jasper Shell（REPL）、交互命令 lowering、Bracketed Paste 与终端状态机；
- 类型化管道 `ni<T>`、流式背压与取消；
- MCP 工具导出与结构化 AI 调用适配器；
- 签名与发布者信任链；
- `.jaso` 命令元数据（与既有模块清单衔接）；
- 把 `jasperc` 迁移为 JUCS 命令及其兼容桥。
- JAPS 应用封装与多实例（另有独立标准，目前只有前瞻设计，没有任何实现）。

## 9. 试用

```text
./gradlew :jucs:installDist
./scripts/jucs --help
./scripts/jucs fs file list --path . --output json
```

测试：

```text
./gradlew :jucs:test
```
