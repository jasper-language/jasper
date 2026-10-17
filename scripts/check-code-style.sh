#!/usr/bin/env bash
#
# ============================================================================
# check-code-style.sh —— Jasper 代码标准「机械护栏」检查脚本
# ============================================================================
#
# 职责（本脚本只做这些，别的都不做）：
#   1. 文件头模块说明：每个主源码 *.kt 的前 20 行内，必须有一段含中文的注释块。
#   2. 公共 API 文档：每个「公共」的 class / interface / object / fun，其紧邻上方
#      必须有含中文的 KDoc（`/** ... */`）。
#   3. 依赖方向：解析各 .kt 的 import，映射到逻辑层次，报告「下层 → 上层」的
#      非法依赖边，以及层次图里的依赖环（简单 DFS）。
#   4. 近似重复：把函数体所在的代码行做归一化后做粗粒度滑窗重复检测，列出候选。
#
# 所属层次 / 与其他模块关系：
#   本脚本属于「工程验收层」，与 jasper-build/TASKBOOK.md 第 2b 节的代码标准一一对应；
#   只读源码，不修改任何文件，可重复执行。它被 `./gradlew test` 之外的验收流程调用。
#
# 判据与局限（重要，避免误用）：
#   - 全部判据都是**启发式**，不做完整 Kotlin 语法分析：
#       * 「含中文」按 Unicode 区间 [\u4e00-\u9fff] 判断；只认汉字，不认标点。
#       * 「公共」= 没有 private/internal/protected 修饰符，且所有外层类型也都是公共的；
#         外层可见性用花括号栈近似推断，遇到跨行花括号风格可能有偏差。
#       * 「紧邻上方」允许中间夹杂注解行（`@Foo`）与空行。
#       * 依赖层次用一种**可配置的映射表**（见下方 LAYER_RULES），其中
#         `jasper.compiler.SymbolTable` 被显式归到基础「symbol 层」（原因见映射表注释）。
#       * 重复检测用滑窗，**允许误报**，只列候选不判死；请人工核对后再决定是否抽取。
#   - 退出码：文件头缺失 / 公共 API 文档缺失 / 非法依赖 / 依赖环，任一 > 0 则退出码非 0。
#     重复候选只报告，不影响退出码（因为滑窗天然会命中大量样板，需要人工判断）。
#
# 用法：
#   ./scripts/check-code-style.sh              # 全量检查
#   ./scripts/check-code-style.sh --quiet      # 只打印汇总（仍返回真实退出码）
#
# 依赖：bash、python3（用于可靠的词法剥离与图分析；缺失会明确报错）。
# ----------------------------------------------------------------------------

set -euo pipefail

# 切到仓库根目录：脚本可能被从任意 cwd 调用，所有相对路径都相对仓库根。
REPO_ROOT="$(cd "$(dirname "$0")/.." && pwd)"
cd "$REPO_ROOT"

QUIET="0"
if [[ "${1:-}" == "--quiet" ]]; then
  QUIET="1"
fi

# ---------------------------------------------------------------------------
# 主分析逻辑用 python3 实现：
#   - 需要跨行状态机剥离字符串/注释才能可靠数花括号；
#   - 需要建图做 DFS 找环。
# 用引号包住 heredoc，避免 bash 展开 Kotlin/正则里的 $。
# ---------------------------------------------------------------------------
python3 - "$REPO_ROOT" "$QUIET" <<'PYEOF'
import os
import re
import sys
import hashlib

REPO_ROOT = sys.argv[1]
QUIET = sys.argv[2] == "1"

CJK = re.compile(r"[\u4e00-\u9fff]")

# ===========================================================================
# 0. 层次模型（Layer Model）—— 依赖方向检查的唯一事实来源
# ===========================================================================
# 约定（来自 TASKBOOK §2b②b）：依赖只允许从上往下；下层永远不知道上层。
# 数值 rank 越大表示「越靠上」。一条 import 边 src -> dst：
#   * rank(src) <  rank(dst)  => 下层依赖上层，非法；
#   * rank(src) == rank(dst)  => 同层（含同包）耦合，视为允许（同层协作）；
#   * rank(src) >  rank(dst)  => 上层依赖下层，合法。
#
# 为什么把 `jasper.compiler.SymbolTable` 单独归到 symbol 层：
#   SymbolTable 被 checker 与 translator 依赖，若按包名归到 compiler（编排层），
#   就会凭空出现 compiler<->checker、compiler<->translator 的「环」。但 SymbolTable
#   本质是被各方共享的**基础符号设施**，其语义层次在 ast 之上、checker 之下。
#   本脚本因此显式声明它属于 symbol 层；这**不掩盖**其物理位置不合理的问题——
#   模块化审计会把「SymbolTable 应该独立成 lower 层包」作为待办列出（见报告）。
LAYER_RANK = {
    "ast": 0,
    "symbol": 1,        # 仅 jasper.compiler.SymbolTable
    "annotations": 2,
    "checker": 2,
    "translator": 2,
    "backend": 2,       # jasper.llvm.LlvmBackend
    "stdlib": 2,
    "compiler": 3,      # 编排层（含 compiler/archive）
}

# import 全限定名 -> 层次。返回 None 表示「不属于受检层次」（例如 ANTLR 生成的 parser）。
def layer_of_import(fqn):
    fqn = fqn.strip()
    if fqn.endswith(".*"):
        fqn = fqn[:-2]
    if fqn == "jasper.compiler.SymbolTable":
        return "symbol"
    if fqn.startswith("jasper.ast"):
        return "ast"
    if fqn.startswith("jasper.checker"):
        return "checker"
    if fqn.startswith("jasper.translator"):
        return "translator"
    if fqn.startswith("jasper.annotations"):
        return "annotations"
    if fqn.startswith("jasper.stdlib"):
        return "stdlib"
    if fqn.startswith("jasper.llvm"):
        return "backend"
    if fqn.startswith("jasper.compiler"):
        return "compiler"
    # jasper.parser.* 是 ANTLR 生成物，不参与分层；java./kotlin./org. 是外部依赖。
    return None

# 源文件路径 -> 层次。
def layer_of_file(path):
    p = path.replace("\\", "/")
    base = os.path.basename(p)
    if base == "SymbolTable.kt":
        return "symbol"
    if "/jasper/ast/" in p:
        return "ast"
    if "/jasper/checker/" in p:
        return "checker"
    if "/jasper/translator/" in p:
        return "translator"
    if "/jasper/annotations/" in p:
        return "annotations"
    if "/jasper/stdlib/" in p:
        return "stdlib"
    if "/jasper/llvm/" in p:
        return "backend"
    if "/jasper/compiler/" in p:
        return "compiler"
    return None

# ===========================================================================
# 1. 收集主源码文件（排除 build/ 与 ANTLR 生成物）
# ===========================================================================
def collect_main_sources():
    out = []
    for base in ("jasper-compiler/src/main/kotlin", "jasper-llvm/src/main/kotlin"):
        root = os.path.join(REPO_ROOT, base)
        if not os.path.isdir(root):
            continue
        for dirpath, dirnames, filenames in os.walk(root):
            if "build" in dirpath.replace("\\", "/").split("/"):
                continue
            for fn in filenames:
                if fn.endswith(".kt"):
                    out.append(os.path.relpath(os.path.join(dirpath, fn), REPO_ROOT))
    return sorted(out)

# ===========================================================================
# 2. 词法剥离：把字符串/字符/注释内容替换为空格，保留代码与花括号
#    为什么需要：数花括号、判断声明关键字时，必须忽略字符串里的 `{` 与注释里的文字。
#    跨行状态（块注释、原始字符串）需要在行间传递。
# ===========================================================================
def clean_lines(raw_lines):
    clean = []
    state = "code"      # code | block | raw
    for line in raw_lines:
        out = []
        i = 0
        n = len(line)
        while i < n:
            c = line[i]
            if state == "block":
                if c == "*" and i + 1 < n and line[i + 1] == "/":
                    state = "code"
                    out.append("  ")
                    i += 2
                else:
                    out.append(" ")
                    i += 1
                continue
            if state == "raw":
                if line[i:i + 3] == '"""':
                    state = "code"
                    out.append("   ")
                    i += 3
                else:
                    out.append(" ")
                    i += 1
                continue
            # state == code
            if c == "/" and i + 1 < n and line[i + 1] == "/":
                out.append(" " * (n - i))
                break
            if c == "/" and i + 1 < n and line[i + 1] == "*":
                state = "block"
                out.append("  ")
                i += 2
                continue
            if line[i:i + 3] == '"""':
                state = "raw"
                out.append("   ")
                i += 3
                continue
            if c == '"' or c == "'":
                quote = c
                out.append(" ")
                i += 1
                while i < n:
                    if line[i] == "\\":
                        out.append("  ")
                        i += 2
                        continue
                    if line[i] == quote:
                        out.append(" ")
                        i += 1
                        break
                    out.append(" ")
                    i += 1
                continue
            out.append(c)
            i += 1
        clean.append("".join(out))
    return clean

# ===========================================================================
# 3. 公共声明识别（含外层可见性）
# ===========================================================================
# 修饰符白名单：出现在 class/interface/object/fun 之前的都算修饰符。
MODIFIERS = (
    r"(?:public|private|protected|internal|expect|actual|final|open|abstract|sealed|"
    r"const|external|override|lateinit|tailrec|vararg|noinline|crossinline|inline|"
    r"reified|infix|operator|suspend|data|inner|enum|annotation|value)"
)
TYPE_DECL_RE = re.compile(
    r"^\s*(?:" + MODIFIERS + r"\s+)*(class|interface|object)\b"
)
# `fun interface Foo` 属于接口声明；单独识别。
FUN_IFACE_RE = re.compile(r"^\s*(?:" + MODIFIERS + r"\s+)*fun\s+interface\b")
FUN_DECL_RE = re.compile(r"^\s*(?:" + MODIFIERS + r"\s+)*fun\s+")
PRIVATE_MOD_RE = re.compile(r"\b(private|protected|internal)\b")
ANNOTATION_LINE_RE = re.compile(r"^\s*@")


def declaration_name(stripped, kind):
    """从声明行里尽量取出名字，仅用于报告可读性。"""
    if kind in ("class", "interface", "object"):
        m = re.search(r"\b(?:class|interface|object)\s+([A-Za-z_][A-Za-z0-9_]*)", stripped)
        return m.group(1) if m else "?"
    m = re.search(r"\bfun\s+(?:<[^>]*>\s*)?(?:[A-Za-z_][A-Za-z0-9_.<>?]*\.)?([A-Za-z_][A-Za-z0-9_]*)", stripped)
    return m.group(1) if m else "?"


def find_public_declarations(path, raw_lines, clean):
    """
    返回 [(lineno, kind, name, enclosing_private)]：
      lineno 为 1-based；kind ∈ {class,interface,object,fun}。
    外层可见性用花括号深度栈推断（近似）。
    """
    results = []
    depth = 0
    # 栈元素 (body_enter_depth, enclosing_private_before_push, self_private)
    stack = []

    def enclosing_private():
        return any(e[2] for e in stack)   # 任一活动外层为 private/internal

    i = 0
    n = len(raw_lines)
    while i < n:
        line = raw_lines[i]
        cl = clean[i]
        stripped = line.strip()

        # 当前行开始前的有效深度（用于判定外层可见性）
        active = [e for e in stack if e[0] <= depth]
        enc_priv = any(e[2] for e in active)

        kind = None
        prefix = ""
        if FUN_IFACE_RE.match(stripped):
            kind = "interface"
            prefix = stripped[:striped.index("fun")]
        else:
            m = TYPE_DECL_RE.match(stripped)
            if m:
                kind = m.group(1)
                # 可见性只看声明关键字**之前**的前缀；不能把构造器参数里的
                # `private val x` 误当成“这个类私有”（JvmIrBackend/JvmBackend 就是这种）。
                kw = re.search(r"\b(?:class|interface|object)\b", stripped)
                prefix = stripped[:kw.start()] if kw else stripped
            elif FUN_DECL_RE.match(stripped):
                kind = "fun"
                kw = re.search(r"(?<![\w.])fun\b", stripped)
                prefix = stripped[:kw.start()] if kw else stripped

        if kind is not None:
            self_priv = bool(PRIVATE_MOD_RE.search(prefix))
            # 只登记「自身非私有且所有外层类型也非私有」的声明为公共 API。
            # 但类型声明无论私有与否都要压栈：私有外层类型里的成员要按私有一并排除。
            if not self_priv and not enc_priv:
                results.append((i + 1, kind, declaration_name(stripped, kind), enc_priv))
            if kind in ("class", "interface", "object"):
                # body_enter_depth：花括号所在深度；无 body 的类型（object X : Y()）
                # 会在处理完本行后因 depth 不变而被立即弹栈，不会污染后续兄弟声明。
                body_enter = depth + 1
                stack.append((body_enter, enc_priv, self_priv))

        # 更新花括号深度
        depth += cl.count("{") - cl.count("}")
        # 弹出已闭合的类型
        while stack and stack[-1][0] > depth:
            stack.pop()

        i += 1
    return results


def has_chinese_kdoc(raw_lines, decl_lineno):
    """
    判定 decl_lineno（1-based）紧邻上方是否存在含中文的 KDoc。
    规则：从上一行向上，跳过空行与注解行；命中的第一个有效行必须是 `*/` 结尾的注释，
    再向上找到配对的 `/**`，检查整块是否含中文。
    """
    idx = decl_lineno - 2
    while idx >= 0:
        s = raw_lines[idx].strip()
        if s == "" or s.startswith("@"):
            idx -= 1
            continue
        break
    if idx < 0:
        return False
    # 命中的有效行必须属于注释（以 */ 结尾，或整行是 /** ... */）
    if not (raw_lines[idx].strip().endswith("*/")):
        return False
    # 向上找 /** ；同时收集块内文本
    block = []
    j = idx
    found_open = False
    while j >= 0:
        block.append(raw_lines[j])
        if "/**" in raw_lines[j]:
            found_open = True
            break
        j -= 1
    if not found_open:
        return False
    return any(CJK.search(b) for b in block)


def header_has_chinese_comment(raw_lines):
    """文件头 20 行内是否存在含中文的注释行（// 或 /* ... */ 或 * 续行）。"""
    for line in raw_lines[:20]:
        s = line.strip()
        if not CJK.search(line):
            continue
        if s.startswith("//") or s.startswith("/*") or s.startswith("*"):
            return True
    return False

# ===========================================================================
# 4. 近似重复检测（粗粒度滑窗）
# ===========================================================================
WINDOW = 6  # 连续 6 行归一化代码作为一个窗口


def normalize_code_line(line):
    s = line.strip()
    if s == "":
        return None
    if s.startswith("//"):
        return None
    # 去掉行尾注释（仅处理 // ，字符串内 // 极少；启发式可接受）
    s = re.sub(r"\s+", " ", s)
    # 丢弃纯结构行（括号、花括号、逗号等），它们到处都是，不构成信息
    if re.fullmatch(r"[{}()\[\];,:]*", s):
        return None
    if len(s) < 4:
        return None
    return s


def near_duplicates(files_data):
    """
    返回 (候选块列表, 共享窗口数)。
    候选块 = 同一文件内连续被「重复窗口」覆盖的行区间。
    """
    all_windows = {}   # key -> [(path, start_lineno)]
    per_file_norm = {} # path -> [(orig_lineno, norm)]
    for path, raw_lines in files_data.items():
        seq = []
        for ln, line in enumerate(raw_lines, start=1):
            nrm = normalize_code_line(line)
            if nrm is not None:
                seq.append((ln, nrm))
        per_file_norm[path] = seq
        for k in range(len(seq) - WINDOW + 1):
            key = hashlib.md5(
                "\n".join(seq[k + t][1] for t in range(WINDOW)).encode("utf-8")
            ).hexdigest()
            all_windows.setdefault(key, []).append((path, seq[k][0]))

    dup_keys = {k: v for k, v in all_windows.items() if len(v) >= 2}
    shared_window_count = len(dup_keys)

    # 标记每个文件里被重复窗口覆盖的归一化行下标，再合并成块
    dup_cover = {}  # path -> set(norm index)
    key_for_cover = {}  # (path, norm idx) -> representative key
    for key, occ in dup_keys.items():
        for path, _start in occ:
            seq = per_file_norm[path]
            # 找到 start 对应的下标
            for idx, (ln, _n) in enumerate(seq):
                if ln == _start:
                    for t in range(WINDOW):
                        if idx + t < len(seq):
                            dup_cover.setdefault(path, set()).add(idx + t)
                            key_for_cover[(path, idx + t)] = key
                    break

    blocks = []
    for path, cover in dup_cover.items():
        seq = per_file_norm[path]
        idxs = sorted(cover)
        start = idxs[0]
        prev = idxs[0]
        for cur in idxs[1:] + [None]:
            if cur is None or cur != prev + 1:
                a = seq[start][0]
                b = seq[prev][0]
                rep_key = key_for_cover.get((path, start), "")
                peers = []
                if rep_key in dup_keys:
                    peers = [f"{p}:{l}" for p, l in dup_keys[rep_key][:4]]
                blocks.append((path, a, b, peers))
                if cur is not None:
                    start = cur
            prev = cur
    blocks.sort()
    return blocks, shared_window_count

# ===========================================================================
# 5. 主流程
# ===========================================================================
def main():
    files = collect_main_sources()

    files_data = {}
    clean_data = {}
    for path in files:
        with open(os.path.join(REPO_ROOT, path), "r", encoding="utf-8") as f:
            raw = f.read().split("\n")
        files_data[path] = raw
        clean_data[path] = clean_lines(raw)

    # ---- 1. 文件头 ----
    missing_headers = []
    for path in files:
        if not header_has_chinese_comment(files_data[path]):
            missing_headers.append(path)

    # ---- 2. 公共 API KDoc ----
    total_public = 0
    missing_kdoc = []
    for path in files:
        decls = find_public_declarations(path, files_data[path], clean_data[path])
        for (lineno, kind, name, enc_priv) in decls:
            if enc_priv:
                continue  # 位于 private/internal 外层类型里的成员不属于公共 API
            total_public += 1
            if not has_chinese_kdoc(files_data[path], lineno):
                missing_kdoc.append((path, lineno, kind, name))
    documented = total_public - len(missing_kdoc)
    coverage = (documented / total_public * 100.0) if total_public else 100.0

    # ---- 3. 依赖方向 ----
    import_re = re.compile(r"^\s*import\s+([A-Za-z0-9_.]+)(?:\.\*)?\s*$")
    edges = []  # (src_layer, dst_layer, path, lineno)
    for path in files:
        src = layer_of_file(path)
        if src is None:
            continue
        for lineno, line in enumerate(files_data[path], start=1):
            m = import_re.match(line)
            if not m:
                continue
            dst = layer_of_import(m.group(1))
            if dst is None or dst == src:
                continue
            edges.append((src, dst, path, lineno))

    illegal = [e for e in edges if LAYER_RANK[e[0]] < LAYER_RANK[e[1]]]

    # 环检测：层次图上的 DFS
    graph = {}
    for s, d, _p, _l in edges:
        graph.setdefault(s, set()).add(d)
    cycles = []
    seen_cycles = set()

    def dfs(node, stack, onstack):
        for nxt in sorted(graph.get(node, ())):
            if nxt in onstack:
                cyc = stack[stack.index(nxt):] + [nxt]
                key = tuple(cyc)
                if key not in seen_cycles:
                    seen_cycles.add(key)
                    cycles.append(cyc)
            elif nxt not in stack:
                dfs(nxt, stack + [nxt], onstack | {nxt})

    for start in sorted(graph):
        dfs(start, [start], {start})

    # ---- 4. 近似重复 ----
    dup_blocks, shared_windows = near_duplicates(files_data)

    # ---- 输出 ----
    def p(*a):
        if not QUIET:
            print(*a)

    print("== Jasper 代码风格检查（启发式） ==")
    p("")
    p(f"[1] 文件头模块说明（中文）")
    p(f"    主源码文件: {len(files)}    合规: {len(files) - len(missing_headers)}    缺失: {len(missing_headers)}")
    for path in missing_headers:
        p(f"      缺失  {path}")
    p("")
    p(f"[2] 公共声明中文 KDoc")
    p(f"    公共声明总数: {total_public}    有中文 KDoc: {documented}    覆盖率: {coverage:.1f}%    缺失: {len(missing_kdoc)}")
    for (path, lineno, kind, name) in missing_kdoc:
        p(f"      缺失  {path}:{lineno}  {kind} {name}")
    p("")
    p(f"[3] 依赖方向")
    p(f"    内部依赖边: {len(edges)}    非法依赖边(下层→上层): {len(illegal)}    依赖环: {len(cycles)}")
    for (s, d, path, lineno) in illegal:
        p(f"      非法  {s} -> {d}  ({path}:{lineno})")
    for cyc in cycles:
        p(f"      环    {' -> '.join(cyc)}")
    p("")
    p(f"[4] 近似重复（滑窗 {WINDOW} 行，允许误报）")
    p(f"    共享窗口数: {shared_windows}    重复候选块: {len(dup_blocks)}")
    for (path, a, b, peers) in dup_blocks[:40]:
        peer_s = ("  同源: " + ", ".join(peers[:3])) if peers else ""
        p(f"      {path}:{a}-{b}{peer_s}")
    if len(dup_blocks) > 40:
        p(f"      ...（其余 {len(dup_blocks) - 40} 块省略）")
    p("")
    print("== 汇总 ==")
    print(f"  文件头缺失: {len(missing_headers)}")
    print(f"  公共 API 文档缺失: {len(missing_kdoc)}  覆盖率 {coverage:.1f}%")
    print(f"  非法依赖: {len(illegal)}   依赖环: {len(cycles)}")
    print(f"  重复候选块: {len(dup_blocks)}（仅报告，不影响退出码）")

    failed = bool(missing_headers) or bool(missing_kdoc) or bool(illegal) or bool(cycles)
    sys.exit(1 if failed else 0)


main()
PYEOF
