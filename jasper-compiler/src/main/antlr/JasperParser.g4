parser grammar JasperParser;
/*
======================================================================
关于 // graph:: 注释（Graph Metadata Comment）的约定与用法
======================================================================

目的
- 这是一个“机器可解析 + 人可搜索”的元数据标记系统，用来辅助：
  1) 通过图论/规则依赖图对 grammar 进行“节点收缩（内联折叠）”与“节点合并（提取复用）”
  2) 保护可复用/扩展挂点（避免被自动内联/删除）
  3) 支持后续维护者快速 grep/定位某个语法节点（rule）及其角色

基本格式（固定一行，放在每条 parser rule 的上一行）
  // graph::<RuleName> <Tag...>

- <RuleName> 必须与紧随其后的 rule 名字完全一致（大小写一致）
- <Tag...> 为 0..n 个标签，使用空格分隔，标签以 #@ 开头：
  #@keep   #@hook   #@inline_ok  #@draft  ...

示例
  /*
   * 这里写中文说明：用途、语义、示例、边界条件
   *\/
  // graph::type #@keep #@core
  type : ... ;

如何搜索
- 搜索全部标注节点：
    搜索关键字：// graph::
- 搜索某个规则的标注：
    搜索关键字：// graph::<RuleName>
- 搜索某类规则（按标签）：
    搜索关键字：#@keep / #@hook / #@inline_ok / #@draft

推荐标签语义（建议只使用这一小组，保持一致性）
1) #@keep
- 绝对保留节点：禁止自动内联、禁止自动删除
- 用于“高复用概念节点 / AST 统一入口 / Checker 统一入口”
- 典型：type、expression、statement、pattern、annotation、qualifiedName、arguments

2) #@hook
- 扩展挂点：禁止自动内联、禁止自动删除
- 用于未来语法扩展希望“只改一处”的入口
- 典型：classBodyDeclaration、interfaceMemberDeclaration、primaryAtom

3) #@inline_ok
- 允许内联折叠：该节点通常是“壳节点/包装节点/单点列表节点”
- 自动化处理时可优先将其 RHS 内联到父节点后删除此 rule
- 注意：即使 #@inline_ok，若处于递归圈（SCC）或被 #@keep/#@hook 覆盖，仍应保护

4) #@draft
- 草稿/保留但当前不可达或未接入入口的节点
- 自动化处理可将其移动到单独的 draft 文件（不参与构建），或保留但不进入“收缩”流程

（可选）5) #@core
- 表示语义层/AST 的核心入口节点（通常也会同时 #@keep）
- 纯信息标签，主要给维护者阅读和脚本排序使用

图论/自动化“砍节点（收缩）”的建议流程（概念说明）
- 把每个 parser rule 看作图的一个节点 V
- A 规则 RHS 引用了 B 规则，则存在有向边 A -> B
- 从入口规则（root）出发做可达性分析，只处理可达子图
- 做 SCC（强连通分量）分析：SCC 内的递归/互递归节点默认不做自动内联
- 对满足以下条件的节点执行“内联折叠（inline contraction）”：
    - 标了 #@inline_ok
    - 不在 SCC 内
    - 没有 #@keep / #@hook
    - （可选）入度较低（例如 in-degree = 1）以避免代码重复爆炸
  内联折叠含义：
    - 将被折叠 rule 的 RHS 替换到所有引用处（必要时加括号）
    - 删除该 rule（节点）本身
  注意：折叠是“移动语法”，不是删能力；语言能力应保持不变。

“合并节点（提取复用）”的建议策略（概念说明）
- 当你发现多处存在相同/相近的 RHS 片段（例如 exprList、paramList），
  与其把壳节点内联导致重复，不如提取成新的可复用节点并标 #@keep：
    // graph::exprList #@keep
    exprList : expression (',' expression)* ','? ;
- 这样能减少重复、提升可维护性，并为未来扩展提供集中修改点。

一致性要求（非常重要）
- 每条 parser rule 必须有且仅有一条 // graph:: 行，且 RuleName 必须匹配
- 标签集合尽量收敛（不要随意发明新 tag），否则自动化脚本和团队认知会分裂
- 如果你要临时禁用某节点的自动化处理，优先加 #@keep 或 #@hook，而不是删除标注

======================================================================
*/

options { tokenVocab=JasperLexer; }

/*
======================================================================
01 源文件入口
======================================================================
从文件级入口开始，描述 package/import 与顶层项目的整体组织结构。
======================================================================
*/

/*
 * 规则：sourceFile
 * 所属分块：01 源文件入口
 * 用途：源文件入口：可选 package/import/from-import，随后是唯一顶层类型声明，最后 EOF。
 * 说明：
 *   - 该规则只描述“可解析的语法形态”；名称解析/类型推断/语义收敛等由 AST Checker 处理。
 *   - 若本规则包含多个分支，建议在 AST 层以 alt label（#...）作为具体节点类型划分依据。
 *
 * 示例：
 *   - package foo.bar;
 *   - import std.io.File;
 *   - from std.math import sin, cos as c;
 *   - class A { }
 *   - function main(a: int): int { return 0; }
 *   - i++;   // 允许顶层语句（若你希望仅允许声明，可在这里收紧）
 * 设计要点：
 *   - Jasper 强制 block：因此后续语句层不会出现 Java 式 dangling-else 歧义。
 *   - 本入口只做“可解析结构”，名称解析/重复导入/顶层语义限制放到 Checker。
 */
//graph::sourceFile #@keep #@root 说明：源文件入口：可选 package/import/from-import，随后是唯一顶层类型声明，最后 EOF。
sourceFile
    : packageDeclaration?
      importDeclaration*
      topLevelTypeDeclaration
      EOF
    ;

//graph::topLevelTypeDeclaration #@keep #@root 说明：文件级唯一顶层类型声明。
topLevelTypeDeclaration
    : normalClassDeclaration
    | enumDeclaration
    | normalInterfaceDeclaration
    | annotationTypeDeclaration
    ;

/*
 * 规则：packageDeclaration
 * 类型：声明节点
 * 作用：匹配语法形态：mods=modifier* Package name=qualifiedName ';'
 *
 * 典型写法：
 *   package foo.bar;
 *
 * 备注：
 *   - 本规则只描述可解析的语法形态；语义/名称解析/类型推断/合法性收敛在 AST Checker 中完成。
 */

//graph::packageDeclaration #@rule 说明：声明节点：packageDeclaration。
packageDeclaration
    : mods=modifier* Package name=qualifiedName ';'
    ;

/*
 * 规则：importDeclaration
 * 类型：声明节点
 * 作用：匹配语法形态：Import qualifiedName ';' #ImportSingle | Import qualifiedName '.' '*' ';' #ImportOnDemand | fromImportDeclaration #ImportFrom
 *
 * 典型写法：
 *   import std.io.File;
 *
 * 备注：
 *   - 本规则只描述可解析的语法形态；语义/名称解析/类型推断/合法性收敛在 AST Checker 中完成。
 */

//graph::importDeclaration #@rule 说明：声明节点：importDeclaration。
importDeclaration
    : Import qualifiedName ';'                          #ImportSingle
    | Import qualifiedName '.' '*' ';'                  #ImportOnDemand
    | fromImportDeclaration                             #ImportFrom
    ;


/*
======================================================================
02 顶层与导入
======================================================================
导入与文件级唯一顶层类型声明相关规则。sourceFile 会引用这些规则来构建文件级 AST。
======================================================================
*/

/*
 * 规则：fromImportDeclaration
 * 所属分块：02 顶层与导入
 * 用途：声明节点：fromImportDeclaration。
 * 说明：
 *   - 该规则只描述“可解析的语法形态”；名称解析/类型推断/语义收敛等由 AST Checker 处理。
 *   - 若本规则包含多个分支，建议在 AST 层以 alt label（#...）作为具体节点类型划分依据。
 *
 * 典型写法：
 *   from std.math import sin, cos as c;
 */
//graph::fromImportDeclaration #@rule 说明：声明节点：fromImportDeclaration。
fromImportDeclaration
    : softFrom qn=qualifiedName Import clause=importClause ';'                #FromImportQualified
    | softFrom s=STRING_LITERAL Import clause=importClause ';'               #FromImportString
    | softFrom rs=RAW_STRING_LITERAL Import clause=importClause ';'          #FromImportRawString
    | softFrom ts=TRIPLE_STRING_LITERAL Import clause=importClause ';'       #FromImportTripleString
    | softFrom rts=RAW_TRIPLE_STRING_LITERAL Import clause=importClause ';'  #FromImportRawTripleString
    ;

/*
 * 规则：importClause
 * 所属分块：02 顶层与导入
 * 用途：语法节点：importClause。
 * 说明：
 *   - 该规则只描述“可解析的语法形态”；名称解析/类型推断/语义收敛等由 AST Checker 处理。
 *   - 若本规则包含多个分支，建议在 AST 层以 alt label（#...）作为具体节点类型划分依据。
 *
 * 典型写法：
 *   import std.io.File;
 */
//graph::importClause #@rule 说明：语法节点：importClause。
importClause
    : '*'                                                 #ImportClauseAll
    | items+=importItem (',' items+=importItem)* ','?      #ImportClauseItems
    ;

/*
 * 规则：importItem
 * 所属分块：02 顶层与导入
 * 用途：语法节点：importItem。
 * 说明：
 *   - 该规则只描述“可解析的语法形态”；名称解析/类型推断/语义收敛等由 AST Checker 处理。
 *   - 若本规则包含多个分支，建议在 AST 层以 alt label（#...）作为具体节点类型划分依据。
 *
 * 典型写法：
 *   // importItem 的一种典型写法（见该规则 RHS）
 */
//graph::importItem #@rule 说明：语法节点：importItem。
importItem
    : name=Identifier (softAs alias=Identifier)?
    ;


/*
======================================================================
03 软关键字
======================================================================
软关键字统一实现与各个具体软关键字别名。软关键字仅在特定语境被消费，不占用保留字集合。
======================================================================
*/

/*
 * 规则：softThen
 * 所属分块：03 软关键字
 * 用途：软关键字：softThen，通过 Identifier+谓词匹配固定文本。
 * 说明：
 *   - 该规则只描述“可解析的语法形态”；名称解析/类型推断/语义收敛等由 AST Checker 处理。
 *   - 若本规则包含多个分支，建议在 AST 层以 alt label（#...）作为具体节点类型划分依据。
 *
 * 实现说明：
 *   - 该软关键字通过 specific softXxx rule 进行匹配，仅在被引用的位置生效。
 *
 * 典型写法：
 *   // softThen 的一种典型写法（见该规则 RHS）
 */
//graph::softThen #@rule 说明：软关键字：softThen，通过 Identifier+谓词匹配固定文本。
softThen     : Then ;

//graph::softWhere #@rule 说明：软关键字：softWhere，用于泛型 where 子句。
softWhere    : { _input.LT(1).getText().equals("where") }? Identifier ;

//graph::softIs #@rule 说明：软关键字：softIs，用于 where 约束：T is NonNull。
softIs
    : Is
    | Is
    ;

/*
 * 规则：softLabel
 * 所属分块：03 软关键字
 * 用途：软关键字：softLabel，通过 Identifier+谓词匹配固定文本。
 * 说明：
 *   - 该规则只描述“可解析的语法形态”；名称解析/类型推断/语义收敛等由 AST Checker 处理。
 *   - 若本规则包含多个分支，建议在 AST 层以 alt label（#...）作为具体节点类型划分依据。
 *
 * 实现说明：
 *   - 该软关键字通过 specific softXxx rule 进行匹配，仅在被引用的位置生效。
 *
 * 典型写法：
 *   // softLabel 的一种典型写法（见该规则 RHS）
 */
//graph::softLabel #@rule 说明：软关键字：softLabel，通过 Identifier+谓词匹配固定文本。
softLabel     : Label ;


/*
 * 规则：softFrom
 * 所属分块：03 软关键字
 * 用途：软关键字：softFrom，通过 Identifier+谓词匹配固定文本。
 * 说明：
 *   - 该规则只描述“可解析的语法形态”；名称解析/类型推断/语义收敛等由 AST Checker 处理。
 *   - 若本规则包含多个分支，建议在 AST 层以 alt label（#...）作为具体节点类型划分依据。
 *
 * 实现说明：
 *   - 该软关键字通过 specific softXxx rule 进行匹配，仅在被引用的位置生效。
 *
 * 典型写法：
 *   // softFrom 的一种典型写法（见该规则 RHS）
 */
//graph::softFrom #@rule 说明：软关键字：softFrom，通过 Identifier+谓词匹配固定文本。
softFrom     : From ;

/*
 * 规则：softAs
 * 所属分块：03 软关键字
 * 用途：软关键字：softAs，通过 Identifier+谓词匹配固定文本。
 * 说明：
 *   - 该规则只描述“可解析的语法形态”；名称解析/类型推断/语义收敛等由 AST Checker 处理。
 *   - 若本规则包含多个分支，建议在 AST 层以 alt label（#...）作为具体节点类型划分依据。
 *
 * 实现说明：
 *   - 该软关键字通过 specific softXxx rule 进行匹配，仅在被引用的位置生效。
 *
 * 典型写法：
 *   // softAs 的一种典型写法（见该规则 RHS）
 */
//graph::softAs #@rule 说明：软关键字：softAs，通过 Identifier+谓词匹配固定文本。
softAs     : As ;

/*
 * 规则：softCast
 * 所属分块：03 软关键字
 * 用途：软关键字：softCast，通过 Identifier+谓词匹配固定文本。
 * 说明：
 *   - 该规则只描述“可解析的语法形态”；名称解析/类型推断/语义收敛等由 AST Checker 处理。
 *   - 若本规则包含多个分支，建议在 AST 层以 alt label（#...）作为具体节点类型划分依据。
 *
 * 实现说明：
 *   - 该软关键字通过 specific softXxx rule 进行匹配，仅在被引用的位置生效。
 *
 * 典型写法：
 *   // softCast 的一种典型写法（见该规则 RHS）
 */
//graph::softCast #@keep #@rule 说明：软关键字：softCast，通过 Identifier+谓词匹配固定文本。
softCast     : Cast ;

/*
 * 规则：softJson
 * 所属分块：03 软关键字
 * 用途：软关键字：softJson，通过 Identifier+谓词匹配固定文本。
 * 说明：
 *   - 该规则只描述“可解析的语法形态”；名称解析/类型推断/语义收敛等由 AST Checker 处理。
 *   - 若本规则包含多个分支，建议在 AST 层以 alt label（#...）作为具体节点类型划分依据。
 *
 * 实现说明：
 *   - 该软关键字通过 specific softXxx rule 进行匹配，仅在被引用的位置生效。
 *
 * 典型写法：
 *   // softJson 的一种典型写法（见该规则 RHS）
 */
//graph::softJson #@rule 说明：软关键字：softJson，通过 Identifier+谓词匹配固定文本。
softJson     : Json ;

/*
 * 规则：softGet
 * 所属分块：03 软关键字
 * 用途：软关键字：softGet，通过 Identifier+谓词匹配固定文本。
 * 说明：
 *   - 该规则只描述“可解析的语法形态”；名称解析/类型推断/语义收敛等由 AST Checker 处理。
 *   - 若本规则包含多个分支，建议在 AST 层以 alt label（#...）作为具体节点类型划分依据。
 *
 * 实现说明：
 *   - 该软关键字通过 specific softXxx rule 进行匹配，仅在被引用的位置生效。
 *
 * 典型写法：
 *   // softGet 的一种典型写法（见该规则 RHS）
 */
//graph::softGet #@rule 说明：软关键字：softGet，通过 Identifier+谓词匹配固定文本。
softGet     : Get ;

/*
 * 规则：softSet
 * 所属分块：03 软关键字
 * 用途：软关键字：softSet，通过 Identifier+谓词匹配固定文本。
 * 说明：
 *   - 该规则只描述“可解析的语法形态”；名称解析/类型推断/语义收敛等由 AST Checker 处理。
 *   - 若本规则包含多个分支，建议在 AST 层以 alt label（#...）作为具体节点类型划分依据。
 *
 * 实现说明：
 *   - 该软关键字通过 specific softXxx rule 进行匹配，仅在被引用的位置生效。
 *
 * 典型写法：
 *   // softSet 的一种典型写法（见该规则 RHS）
 */
//graph::softSet #@rule 说明：软关键字：softSet，通过 Identifier+谓词匹配固定文本。
softSet     : Set ;

/*
 * 规则：softOr
 * 所属分块：03 软关键字
 * 用途：软关键字：softOr，通过 Identifier+谓词匹配固定文本。
 * 说明：
 *   - 该规则只描述“可解析的语法形态”；名称解析/类型推断/语义收敛等由 AST Checker 处理。
 *   - 若本规则包含多个分支，建议在 AST 层以 alt label（#...）作为具体节点类型划分依据。
 *
 * 实现说明：
 *   - 该软关键字通过 specific softXxx rule 进行匹配，仅在被引用的位置生效。
 *
 * 典型写法：
 *   // softOr 的一种典型写法（见该规则 RHS）
 */
//graph::softOr #@rule 说明：软关键字：softOr，通过 Identifier+谓词匹配固定文本。
softOr     : Or ;

/*
 * 规则：softAnd
 * 所属分块：03 软关键字
 * 用途：软关键字：softAnd，通过 Identifier+谓词匹配固定文本。
 * 说明：
 *   - 该规则只描述“可解析的语法形态”；名称解析/类型推断/语义收敛等由 AST Checker 处理。
 *   - 若本规则包含多个分支，建议在 AST 层以 alt label（#...）作为具体节点类型划分依据。
 *
 * 实现说明：
 *   - 该软关键字通过 specific softXxx rule 进行匹配，仅在被引用的位置生效。
 *
 * 典型写法：
 *   // softAnd 的一种典型写法（见该规则 RHS）
 */
//graph::softAnd #@rule 说明：软关键字：softAnd，通过 Identifier+谓词匹配固定文本。
softAnd     : And ;

/*
 * 规则：softNot
 * 所属分块：03 软关键字
 * 用途：软关键字：softNot，通过 Identifier+谓词匹配固定文本。
 * 说明：
 *   - 该规则只描述“可解析的语法形态”；名称解析/类型推断/语义收敛等由 AST Checker 处理。
 *   - 若本规则包含多个分支，建议在 AST 层以 alt label（#...）作为具体节点类型划分依据。
 *
 * 实现说明：
 *   - 该软关键字通过 specific softXxx rule 进行匹配，仅在被引用的位置生效。
 *
 * 典型写法：
 *   // softNot 的一种典型写法（见该规则 RHS）
 */
//graph::softNot #@rule 说明：软关键字：softNot，通过 Identifier+谓词匹配固定文本。
softNot     : Not ;

/*
======================================================================
04 名称与限定名
======================================================================
限定名（qualifiedName）等名称结构规则。语义层面（包/类型/变量解析）交由 Checker。
======================================================================
*/

/*
 * 规则：qualifiedName
 * 所属分块：04 名称与限定名
 * 用途：限定名：Identifier ( "." Identifier )*。
 * 说明：
 *   - 该规则只描述“可解析的语法形态”；名称解析/类型推断/语义收敛等由 AST Checker 处理。
 *   - 若本规则包含多个分支，建议在 AST 层以 alt label（#...）作为具体节点类型划分依据。
 *
 * 典型写法：
 *   foo.bar.Baz
 */
//graph::qualifiedName #@keep #@core 说明：限定名：Identifier ( "." Identifier )*。
qualifiedName
    : Identifier ('.' Identifier)*
    ;

/*
======================================================================
05 字面量与初始化
======================================================================
数值/字符串/布尔/null 以及 dict/json 等复合字面量；同时包含数组初始化器等结构。
======================================================================
*/

/*
 * 规则：literal
 * 所属分块：05 字面量与初始化
 * 用途：字面量入口：数字/字符串/布尔/null，以及 dict/json 字面量。
 * 说明：
 *   - 该规则只描述"可解析的语法形态"；名称解析/类型推断/语义收敛等由 AST Checker 处理。
 *   - 若本规则包含多个分支，建议在 AST 层以 alt label（#...）作为具体节点类型划分依据。
 *
 * 典型写法：
 *   123
 */
//graph::literal #@rule 说明：字面量入口：数字/字符串/布尔/null，以及 dict/json 字面量。
literal
    : DEC_LITERAL                   #DecimalLiteral
    | HEX_LITERAL                   #HexadecimalLiteral
    | OCT_LITERAL                   #OctalLiteral
    | BIN_LITERAL                   #BinaryLiteral
    | FLOAT_LITERAL                 #FloatLiteral
    | HEX_FLOAT_LITERAL             #HexFloatLiteral

    | STRING_LITERAL                #StringLiteral
    | RAW_STRING_LITERAL            #RawStringLiteral
    | REGEX_LITERAL                 #RegexLiteral
    | FORMAT_STRING_LITERAL         #FormatStringLiteral
    | BYTE_STRING_LITERAL           #ByteStringLiteral
    | UNICODE_STRING_LITERAL        #UnicodeStringLiteral

    | TRIPLE_STRING_LITERAL         #TripleStringLiteral
    | RAW_TRIPLE_STRING_LITERAL     #RawTripleStringLiteral
    | FORMAT_TRIPLE_STRING_LITERAL  #FormatTripleStringLiteral

    | True                          #TrueLiteral
    | False                         #FalseLiteral
    | Null                          #NullLiteral
    | dictLiteral                   #LiteralDict
    | jsonLiteral                   #LiteralJson
    ;

/*
 * 规则：dictLiteral
 * 所属分块：05 字面量与初始化
 * 用途：dict 字面量：dict{ k: v, ... }，key/value 允许任意表达式，允许空与尾逗号。
 * 说明：
 *   - 该规则只描述“可解析的语法形态”；名称解析/类型推断/语义收敛等由 AST Checker 处理。
 *   - 若本规则包含多个分支，建议在 AST 层以 alt label（#...）作为具体节点类型划分依据。
 *
 * 典型写法：
 *   dict{ "a": 1, "b": 2 }
 */
//graph::dictLiteral #@rule 说明：dict 字面量：dict{ k: v, ... }，key/value 允许任意表达式，允许空与尾逗号。
dictLiteral
    : Dict '{' (dictEntry (',' dictEntry)* ','?)? '}'
    ;

/*
 * 规则：dictEntry
 * 所属分块：05 字面量与初始化
 * 用途：dict 条目：key:value；抽出后更 AST 友好（避免按下标配对）。
 * 说明：
 *   - 该规则只描述“可解析的语法形态”；名称解析/类型推断/语义收敛等由 AST Checker 处理。
 *   - 若本规则包含多个分支，建议在 AST 层以 alt label（#...）作为具体节点类型划分依据。
 *
 * AST 友好：
 *   - dictEntry 抽出后，dictLiteral 内部是 entry 的列表，避免 AST 侧按 expression 下标两两配对。
 *
 * 典型写法：
 *   "a": 1
 */
//graph::dictEntry #@keep #@rule 说明：dict 条目：key:value；抽出后更 AST 友好（避免按下标配对）。
dictEntry
    : key=expression ':' value=expression
    ;

/*
 * 规则：jsonLiteral
 * 所属分块：05 字面量与初始化
 * 用途：json 字面量：json{ k: v, ... }，key/value 允许任意表达式，
 *       语义层保证结果为严格 JSON（key 运行时必须为 string，value 必须为 JSON 合法类型）。
 * 说明：
 *   - 该规则只描述"可解析的语法形态"；名称解析/类型推断/语义收敛等由 AST Checker 处理。
 *   - 若本规则包含多个分支，建议在 AST 层以 alt label（#...）作为具体节点类型划分依据。
 *
 * 典型写法：
 *   json{ "a": 1, "b": true, "c": null }
 *   json{ keyExpr(): valExpr() }
 */
//graph::jsonLiteral #@rule 说明：json 字面量：json{ k: v, ... }，key/value 允许任意表达式。
jsonLiteral
    : softJson '{' (jsonEntry (',' jsonEntry)* ','?)? '}'
    ;

/*
 * 规则：jsonEntry
 * 所属分块：05 字面量与初始化
 * 用途：json 条目：expression : expression；与 dictEntry 语法一致，语义层做 JSON 合规校验。
 * 说明：
 *   - 该规则只描述"可解析的语法形态"；名称解析/类型推断/语义收敛等由 AST Checker 处理。
 *   - 若本规则包含多个分支，建议在 AST 层以 alt label（#...）作为具体节点类型划分依据。
 *
 * AST 友好：
 *   - jsonEntry 抽出后，jsonLiteral 内部是 entry 的列表，避免 AST 侧按 token/下标配对。
 *
 * 典型写法：
 *   "a": 1
 *   obj.getKey(): obj.getValue()
 */
//graph::jsonEntry #@keep #@rule 说明：json 条目：expression : expression；抽出后更 AST 友好。
jsonEntry
    : key=expression ':' value=expression
    ;

/*
 * 规则：jsonValue
 * 所属分块：05 字面量与初始化
 * 用途：json 值：字符串/数字/布尔/null/json 对象/json 数组。
 * 说明：
 *   - 该规则只描述“可解析的语法形态”；名称解析/类型推断/语义收敛等由 AST Checker 处理。
 *   - 若本规则包含多个分支，建议在 AST 层以 alt label（#...）作为具体节点类型划分依据。
 *
 * 典型写法：
 *   // jsonValue 的一种典型写法（见该规则 RHS）
 */
//graph::jsonValue #@rule 说明：json 值：字符串/数字/布尔/null/json 对象/json 数组。
jsonValue
    : STRING_LITERAL                                                             #JsonString
    | (DEC_LITERAL | HEX_LITERAL | OCT_LITERAL | BIN_LITERAL)                     #JsonNumber
    | True                                                                       #JsonTrue
    | False                                                                      #JsonFalse
    | Null                                                                       #JsonNull
    | jsonLiteral                                                                #JsonObject
    | '[' (jsonValue (',' jsonValue)* ','?)? ']'                                 #JsonArray
    ;


/*
 * 规则：arrayInitializer
 * 所属分块：05 字面量与初始化
 * 用途：语法节点：arrayInitializer。
 * 说明：
 *   - 该规则只描述“可解析的语法形态”；名称解析/类型推断/语义收敛等由 AST Checker 处理。
 *   - 若本规则包含多个分支，建议在 AST 层以 alt label（#...）作为具体节点类型划分依据。
 *
 * 典型写法：
 *   // arrayInitializer 的一种典型写法（见该规则 RHS）
 */
//graph::arrayInitializer #@rule 说明：语法节点：arrayInitializer。
arrayInitializer
    : '[' ( variableInitializer (',' variableInitializer)* ','? )? ','? ']'
    ;

/*
======================================================================
06 类型系统
======================================================================
Jasper 类型表达式（含前缀指针/引用、后缀数组/可空等）与泛型参数、通配符等结构。
======================================================================
*/

/*
 * 规则：primitiveType
 * 所属分块：06 类型系统
 * 用途：类型相关节点：primitiveType。
 * 说明：
 *   - 该规则只描述“可解析的语法形态”；名称解析/类型推断/语义收敛等由 AST Checker 处理。
 *   - 若本规则包含多个分支，建议在 AST 层以 alt label（#...）作为具体节点类型划分依据。
 *
 * 典型写法：
 *   // primitiveType 的一种典型写法（见该规则 RHS）
 */
//graph::primitiveType #@rule 说明：类型相关节点：primitiveType。
primitiveType
    : annotation* (Byte | Int8 | Int16 | Int32 | Int | Int64 | Int128 | Char | Char8 | Char16 | Char32 | UInt | UInt8 | UInt16 | UInt32 | UInt64 | UInt128 | Float | Float32 | Float64 | Float128 | Bool)
    ;

/*
 * 规则：typeExpr
 * 所属分块：06 类型系统
 * 用途：语法节点：typeExpr。
 * 说明：
 *   - 该规则只描述"可解析的语法形态"；名称解析/类型推断/语义收敛等由 AST Checker 处理。
 *   - 若本规则包含多个分支，建议在 AST 层以 alt label（#...）作为具体节点类型划分依据。
 *
 * 典型写法：
 *   List<Int32>[]?
 */
//graph::typeExpr #@keep #@core 说明：语法节点：typeExpr。
typeExpr
    : typeAtom typeSuffix* typeQualifier*                         #TypeExpression
    ;


/*
 * 规则：typePrefix
 * 所属分块：06 类型系统
 * 用途：类型前缀：`*` 表示指针类型。
 * 说明：
 *   - 该规则只描述“可解析的语法形态”；名称解析/类型推断/语义收敛等由 AST Checker 处理。
 *   - 若本规则包含多个分支，建议在 AST 层以 alt label（#...）作为具体节点类型划分依据。
 *
 * 典型写法：
 *   List<Int32>[]?
 */
//graph::typePrefix #@rule 说明：类型前缀：`*` 表示指针类型。
typePrefix
    : typeStarRun                                                 #TypePrefixAsterisk
    ;

/*
 * 规则：typeStarRun
 * 所属分块：06 类型系统
 * 用途：星号链：支持 '*'（以及若 lexer 有定义则支持 '**' 单 token）。
 * 说明：
 *   - 该规则只描述“可解析的语法形态”；名称解析/类型推断/语义收敛等由 AST Checker 处理。
 *   - 若本规则包含多个分支，建议在 AST 层以 alt label（#...）作为具体节点类型划分依据。
 *
 * 典型写法：
 *   List<Int32>[]?
 */
//graph::typeStarRun #@rule 说明：星号链：支持 '*'（以及若 lexer 有定义则支持 '**' 单 token）。
typeStarRun
    : ('*' | '**')+                                               #TypeAsteriskSequenceNode
    ;



/*
 * 规则：typeAtom
 * 所属分块：06 类型系统
 * 用途：语法节点：typeAtom（可选 `*` 前缀，再接类型本体）。
 * 说明：
 *   - 该规则只描述“可解析的语法形态”；名称解析/类型推断/语义收敛等由 AST Checker 处理。
 *   - 若本规则包含多个分支，建议在 AST 层以 alt label（#...）作为具体节点类型划分依据。
 *
 * 典型写法：
 *   List<Int32>[]?
 */
//graph::typeAtom #@rule 说明：语法节点：typeAtom（可选 `*` 前缀，再接类型本体）。
typeAtom
    : typePrefix? typeAtomBase                                    #TypeAtomNode
    ;

/*
 * 规则：typeAtomBase
 * 所属分块：06 类型系统
 * 用途：类型本体（不含前缀间接符与软修饰符）。
 * 说明：
 *   - 该规则只描述“可解析的语法形态”；名称解析/类型推断/语义收敛等由 AST Checker 处理。
 *   - 若本规则包含多个分支，建议在 AST 层以 alt label（#...）作为具体节点类型划分依据。
 *
 * 典型写法：
 *   List<Int32>[]?
 */
//graph::typeAtomBase #@rule 说明：类型本体（不含前缀间接符与软修饰符）。
typeAtomBase
    : primitiveType                                               #TypeAtomNodePrimitive
    | String                                                      #TypeAtomNodeString
    | Bytes                                                       #TypeAtomNodeBytes
    | Regex                                                       #TypeAtomNodeRegex
    | Any                                                         #TypeAtomNodeAny
    | Void                                                        #TypeAtomNodeUnit
    | '(' typeExpr (',' typeExpr)+ ','? ')'                        #TypeAtomNodeTuple
    | '(' typeExpr ')'                                            #TypeAtomNodeGroup
    | annotation* Identifier typeArguments?                        #TypeAtomNodeIdentifier
    ;

/*
 * 规则：typeQualifier
 * 所属分块：06 类型系统
 * 用途：类型后缀限定符：? 表示可空类型。
 * 说明：
 *   - 该规则只描述“可解析的语法形态”；名称解析/类型推断/语义收敛等由 AST Checker 处理。
 *   - 若本规则包含多个分支，建议在 AST 层以 alt label（#...）作为具体节点类型划分依据。
 *
 * 典型写法：
 *   List<Int32>[]?
 */
//graph::typeQualifier #@rule 说明：类型后缀限定符：? 表示可空类型。
typeQualifier
    : '?'                                                        #TypeQualNullable
    ;

/*
 * 规则：typeSuffix
 * 所属分块：06 类型系统
 * 用途：语法节点：typeSuffix。
 * 说明：
 *   - 该规则只描述“可解析的语法形态”；名称解析/类型推断/语义收敛等由 AST Checker 处理。
 *   - 若本规则包含多个分支，建议在 AST 层以 alt label（#...）作为具体节点类型划分依据。
 *
 * 典型写法：
 *   List<Int32>[]?
 */
//graph::typeSuffix #@rule 说明：语法节点：typeSuffix。
typeSuffix
    : '.' annotation* Identifier typeArguments?                    #TypeSuffixDot
    | annotation* '[' ']'                                          #TypeSuffixArray
    ;

/*
 * 规则：typePostfix
 * 所属分块：06 类型系统
 * 用途：语法节点：typePostfix。
 * 说明：
 *   - 该规则只描述“可解析的语法形态”；名称解析/类型推断/语义收敛等由 AST Checker 处理。
 *   - 若本规则包含多个分支，建议在 AST 层以 alt label（#...）作为具体节点类型划分依据。
 *
 * 典型写法：
 *   List<Int32>[]?
 */
//graph::typePostfix #@rule 说明：语法节点：typePostfix。
typePostfix
    : typeAtom typeSuffix* typeQualifier*                         //#TypePostfix
    ;

/*
 * 规则：typeArguments
 * 所属分块：06 类型系统
 * 用途：语法节点：typeArguments。
 * 说明：
 *   - 该规则只描述“可解析的语法形态”；名称解析/类型推断/语义收敛等由 AST Checker 处理。
 *   - 若本规则包含多个分支，建议在 AST 层以 alt label（#...）作为具体节点类型划分依据。
 *
 * 典型写法：
 *   List<Int32>[]?
 */
//graph::typeArguments #@keep #@container 说明：语法节点：typeArguments。
typeArguments
    : '<' typeArgument (',' typeArgument)* ','? '>'               // #TypeArguments
    ;

/*
 * 规则：typeArgumentsOrDiamond
 * 所属分块：06 类型系统
 * 用途：语法节点：typeArgumentsOrDiamond。
 * 说明：
 *   - 该规则只描述“可解析的语法形态”；名称解析/类型推断/语义收敛等由 AST Checker 处理。
 *   - 若本规则包含多个分支，建议在 AST 层以 alt label（#...）作为具体节点类型划分依据。
 *
 * 典型写法：
 *   List<Int32>[]?
 */
//graph::typeArgumentsOrDiamond #@rule 说明：语法节点：typeArgumentsOrDiamond。
typeArgumentsOrDiamond
    : typeArguments
    | '<' '>'
    ;

/*
 * 规则：typeArgument
 * 所属分块：06 类型系统
 * 用途：语法节点：typeArgument。
 * 说明：
 *   - 该规则只描述“可解析的语法形态”；名称解析/类型推断/语义收敛等由 AST Checker 处理。
 *   - 若本规则包含多个分支，建议在 AST 层以 alt label（#...）作为具体节点类型划分依据。
 *
 * 典型写法：
 *   List<Int32>[]?
 */
//graph::typeArgument #@rule 说明：语法节点：typeArgument。
typeArgument
    : typeExpr                                                     #TypeArgumentType
    | wildcard                                                     #TypeArgumentWildcard
    ;

/*
 * 规则：typeParameters
 * 所属分块：06 类型系统
 * 用途：语法节点：typeParameters。
 * 说明：
 *   - 该规则只描述“可解析的语法形态”；名称解析/类型推断/语义收敛等由 AST Checker 处理。
 *   - 若本规则包含多个分支，建议在 AST 层以 alt label（#...）作为具体节点类型划分依据。
 *
 * 典型写法：
 *   List<Int32>[]?
 */
//graph::typeParameters #@rule 说明：语法节点：typeParameters。
typeParameters
    : '<' typeParameter (',' typeParameter)* ','? '>'
    ;

/*
 * 规则：typeParameter
 * 所属分块：06 类型系统
 * 用途：语法节点：typeParameter。
 * 说明：
 *   - 该规则只描述“可解析的语法形态”；名称解析/类型推断/语义收敛等由 AST Checker 处理。
 *   - 若本规则包含多个分支，建议在 AST 层以 alt label（#...）作为具体节点类型划分依据。
 *
 * 典型写法：
 *   List<Int32>[]?
 */
//graph::typeParameter #@rule 说明：语法节点：typeParameter。
typeParameter
    : modifier* Identifier typeBound?                               #TypeParam
    ;

/*
 * 规则：typeBound
 * 所属分块：06 类型系统
 * 用途：语法节点：typeBound。
 * 说明：
 *   - 该规则只描述“可解析的语法形态”；名称解析/类型推断/语义收敛等由 AST Checker 处理。
 *   - 若本规则包含多个分支，建议在 AST 层以 alt label（#...）作为具体节点类型划分依据。
 *
 * 典型写法：
 *   List<Int32>[]?
 */
//graph::typeBound #@rule 说明：语法节点：typeBound。
typeBound
    : (Extends | Super) typeExpr                                      #TypeBoundAlternative
    ;

/*
 * 规则：whereClause
 * 所属分块：06 类型系统
 * 用途：泛型约束：where T is NonNull, U is NonNull ...
 * 说明：
 *   - 本阶段仅引入最小语法形态：where <TypeParamName> is <ConstraintName>
 *   - 约束名本阶段仅在语义层支持 NonNull（语法层仍然按 Identifier 解析）。
 */
//graph::whereClause #@keep #@hook 说明：泛型 where 子句。
whereClause
    : softWhere whereConstraint (',' whereConstraint)*
    ;

//graph::whereConstraint #@rule 说明：where 子句单条约束：T is NonNull。
whereConstraint
    : name=Identifier (Is | softIs) constraint=Identifier
    ;

/*
 * 规则：wildcard
 * 所属分块：06 类型系统
 * 用途：语法节点：wildcard。
 * 说明：
 *   - 该规则只描述“可解析的语法形态”；名称解析/类型推断/语义收敛等由 AST Checker 处理。
 *   - 若本规则包含多个分支，建议在 AST 层以 alt label（#...）作为具体节点类型划分依据。
 *
 * 典型写法：
 *   // wildcard 的一种典型写法（见该规则 RHS）
 */
//graph::wildcard #@rule 说明：语法节点：wildcard。
wildcard
    : Any ((Extends | Super) typeExpr)?                             #TypeWildcard
    ;


/*
======================================================================
07 修饰符与注解
======================================================================
modifier 与注解调用语法（含参数形式）。注解类型本身的声明放在“类型/成员声明”分块。
======================================================================
*/

/*
 * 规则：modifier
 * 所属分块：07 修饰符与注解
 * 用途：修饰符入口：注解/装饰器/访问控制/并发/原子性等关键字。
 * 说明：
 *   - 该规则只描述“可解析的语法形态”；名称解析/类型推断/语义收敛等由 AST Checker 处理。
 *   - 若本规则包含多个分支，建议在 AST 层以 alt label（#...）作为具体节点类型划分依据。
 *
 * 典型写法：
 *   lock (mu) { critical(); }
 */
//graph::modifier #@keep #@core 说明：修饰符入口：注解/装饰器/访问控制/并发/原子性等关键字。
modifier
    : annotation
    | Public
    | Protected
    | Private
    | Abstract
    | Static
    | Final
    | Default
    | Defer
    | Lock
    | Atomic
    ;

/*
 * 规则：annotation
 * 所属分块：07 修饰符与注解
 * 用途：@X 注解/装饰器统一语法：@X ≡ @X()；@X(...) 的括号内为“实参列表”（位置参数、命名参数、展开、嵌套 @）。
 * 说明：
 *   - 该规则只描述“可解析的语法形态”；名称解析/类型推断/语义收敛等由 AST Checker 处理。
 *   - 若本规则包含多个分支，建议在 AST 层以 alt label（#...）作为具体节点类型划分依据。
 *
 * 典型写法：
 *   @Test(timeout: 1000)
 */
//graph::annotation #@keep #@core 说明：@X 注解/装饰器统一语法：@X ≡ @X()；@X(...) 的括号内为“实参列表”（位置参数、命名参数、展开、嵌套 @）。
annotation
    : '@' qualifiedName annotationArguments?                                   #AnnotationUseNode
    ;

/*
 * 规则：annotationArguments
 * 所属分块：07 修饰符与注解
 * 用途：注解/装饰器的调用括号：(...)。
 * 说明：
 *   - 该规则只描述“可解析的语法形态”；名称解析/类型推断/语义收敛等由 AST Checker 处理。
 *   - 若本规则包含多个分支，建议在 AST 层以 alt label（#...）作为具体节点类型划分依据。
 *
 * 典型写法：
 *   @Test(timeout: 1000)
 */
//graph::annotationArguments #@rule 说明：注解/装饰器的调用括号：(...)。
annotationArguments
    : '(' annotationArgumentList? ')'                                          #AnnotationArgumentsNode
    ;

/*
 * 规则：annotationArgumentList
 * 所属分块：07 修饰符与注解
 * 用途：实参列表：支持位置参数、命名参数（name = expr）、展开（...expr）；约束：位置参数必须在前，命名参数在后。
 * 说明：
 *   - 该规则只描述“可解析的语法形态”；名称解析/类型推断/语义收敛等由 AST Checker 处理。
 *   - 若本规则包含多个分支，建议在 AST 层以 alt label（#...）作为具体节点类型划分依据。
 *
 * 典型写法：
 *   (@A(1, 2, name = 3, ...rest))
 */
//graph::annotationArgumentList #@rule 说明：实参列表：支持位置参数、命名参数（name = expr）、展开（...expr）；约束：位置参数必须在前，命名参数在后。
annotationArgumentList
    : annotationPositionalArgument (',' annotationPositionalArgument)*
      (',' annotationNamedArgument (',' annotationNamedArgument)*)? ','?      #AnnotationArgumentsMixedNode
    | annotationNamedArgument (',' annotationNamedArgument)* ','?             #AnnotationArgumentsNamedNode
    ;

/*
 * 规则：annotationPositionalArgument
 * 所属分块：07 修饰符与注解
 * 用途：位置实参：value 或 ...value。
 * 说明：
 *   - 该规则只描述“可解析的语法形态”；名称解析/类型推断/语义收敛等由 AST Checker 处理。
 *   - 若本规则包含多个分支，建议在 AST 层以 alt label（#...）作为具体节点类型划分依据。
 *
 * 典型写法：
 *   @Test(timeout: 1000)
 */
//graph::annotationPositionalArgument #@rule 说明：位置实参：value 或 ...value。
annotationPositionalArgument
    : annotationSpreadArgument                                                 #AnnotationArgumentSpreadPositionalNode
    | annotationArgumentValue                                                  #AnnotationArgumentPositionalNode
    ;

/*
 * 规则：annotationNamedArgument
 * 所属分块：07 修饰符与注解
 * 用途：命名实参：name = value。
 * 说明：
 *   - 该规则只描述“可解析的语法形态”；名称解析/类型推断/语义收敛等由 AST Checker 处理。
 *   - 若本规则包含多个分支，建议在 AST 层以 alt label（#...）作为具体节点类型划分依据。
 *
 * 典型写法：
 *   @Test(timeout: 1000)
 */
//graph::annotationNamedArgument #@rule 说明：命名实参：name = value。
annotationNamedArgument
    : Identifier '=' annotationArgumentValue                                   #AnnotationArgumentNamedNode
    ;

/*
 * 规则：annotationSpreadArgument
 * 所属分块：07 修饰符与注解
 * 用途：展开/转发：...expr。
 * 说明：
 *   - 该规则只描述“可解析的语法形态”；名称解析/类型推断/语义收敛等由 AST Checker 处理。
 *   - 若本规则包含多个分支，建议在 AST 层以 alt label（#...）作为具体节点类型划分依据。
 *
 * 典型写法：
 *   @Test(timeout: 1000)
 */
//graph::annotationSpreadArgument #@rule 说明：展开/转发：...expr。
annotationSpreadArgument
    : '...' expression                                                         #AnnotationArgumentSpreadNode
    ;

/*
 * 规则：annotationArgumentValue
 * 所属分块：07 修饰符与注解
 * 用途：实参值：允许 expression，或嵌套 @（annotation）。
 * 说明：
 *   - 该规则只描述“可解析的语法形态”；名称解析/类型推断/语义收敛等由 AST Checker 处理。
 *   - 若本规则包含多个分支，建议在 AST 层以 alt label（#...）作为具体节点类型划分依据。
 *
 * 典型写法：
 *   @Test(timeout: 1000)
 */
//graph::annotationArgumentValue #@rule 说明：实参值：允许 expression，或嵌套 @（annotation）。
annotationArgumentValue
    : annotation                                                               #AnnotationArgumentValueAtNode
    | expression                                                               #AnnotationArgumentValueExpressionNode
    ;

/*
 * 规则：elementValue
 * 所属分块：07 修饰符与注解
 * 用途：语法节点：elementValue。
 * 说明：
 *   - 该规则只描述“可解析的语法形态”；名称解析/类型推断/语义收敛等由 AST Checker 处理。
 *   - 若本规则包含多个分支，建议在 AST 层以 alt label（#...）作为具体节点类型划分依据。
 *
 * 典型写法：
 *   // elementValue 的一种典型写法（见该规则 RHS）
 */
//graph::elementValue #@keep #@core 说明：语法节点：elementValue。
elementValue
    : expression
    | annotation
    | '{' ( elementValue (',' elementValue)* ','? )? ','? '}'
    ;

/*
 * 规则：defaultValue
 * 所属分块：07 修饰符与注解
 * 用途：语法节点：defaultValue。
 * 说明：
 *   - 该规则只描述“可解析的语法形态”；名称解析/类型推断/语义收敛等由 AST Checker 处理。
 *   - 若本规则包含多个分支，建议在 AST 层以 alt label（#...）作为具体节点类型划分依据。
 *
 * 典型写法：
 *   // defaultValue 的一种典型写法（见该规则 RHS）
 */
//graph::defaultValue #@rule 说明：语法节点：defaultValue。
defaultValue
    : Default elementValue
    ;

/*
======================================================================
08 类型/成员声明
======================================================================
class/interface/enum/annotation-type 等声明，以及成员（字段/属性/方法/构造/析构）结构。
======================================================================
*/

/*
 * 规则：normalClassDeclaration
 * 所属分块：08 类型/成员声明
 * 用途：声明节点：normalClassDeclaration。
 * 说明：
 *   - 该规则只描述“可解析的语法形态”；名称解析/类型推断/语义收敛等由 AST Checker 处理。
 *   - 若本规则包含多个分支，建议在 AST 层以 alt label（#...）作为具体节点类型划分依据。
 *
 * 典型写法：
 *   class A { var x: int = 1; }
 */
//graph::normalClassDeclaration #@rule 说明：声明节点：normalClassDeclaration。
normalClassDeclaration
    : mods=modifier* Class name=Identifier tparams=typeParameters? sc=superclass? sis=superinterfaces? body=classBody
    ;


/*
 * 规则：superclass
 * 所属分块：08 类型/成员声明
 * 用途：语法节点：superclass。
 * 说明：
 *   - 该规则只描述“可解析的语法形态”；名称解析/类型推断/语义收敛等由 AST Checker 处理。
 *   - 若本规则包含多个分支，建议在 AST 层以 alt label（#...）作为具体节点类型划分依据。
 *
 * 典型写法：
 *   // superclass 的一种典型写法（见该规则 RHS）
 */
//graph::superclass #@rule 说明：语法节点：superclass。
superclass
    : Extends typePostfix
    ;

/*
 * 规则：superinterfaces
 * 所属分块：08 类型/成员声明
 * 用途：语法节点：superinterfaces。
 * 说明：
 *   - 该规则只描述“可解析的语法形态”；名称解析/类型推断/语义收敛等由 AST Checker 处理。
 *   - 若本规则包含多个分支，建议在 AST 层以 alt label（#...）作为具体节点类型划分依据。
 *
 * 典型写法：
 *   // superinterfaces 的一种典型写法（见该规则 RHS）
 */
//graph::superinterfaces #@rule 说明：语法节点：superinterfaces。
superinterfaces
    : Implements typePostfix (',' typePostfix)* ','?
    ;

/*
 * 规则：classBody
 * 所属分块：08 类型/成员声明
 * 用途：语法节点：classBody。
 * 说明：
 *   - 该规则只描述“可解析的语法形态”；名称解析/类型推断/语义收敛等由 AST Checker 处理。
 *   - 若本规则包含多个分支，建议在 AST 层以 alt label（#...）作为具体节点类型划分依据。
 *
 * 典型写法：
 *   // classBody 的一种典型写法（见该规则 RHS）
 */
//graph::classBody #@rule 说明：语法节点：classBody。
classBody
    : '{' classBodyDeclaration* '}'
    ;

/*
 * 规则：classBodyDeclaration
 * 所属分块：08 类型/成员声明
 * 用途：类体成员入口）。
 * 说明：
 *   - 该规则只描述“可解析的语法形态”；名称解析/类型推断/语义收敛等由 AST Checker 处理。
 *   - 若本规则包含多个分支，建议在 AST 层以 alt label（#...）作为具体节点类型划分依据。
 *
 * 典型写法：
 *   enum Color { Red, Green, Blue }
 */
//graph::classBodyDeclaration #@keep #@hook 说明：类体成员入口）。
classBodyDeclaration
    : decl=fieldDeclaration                                   #MemberField
    | decl=propertyDeclaration                                #MemberProperty
    | decl=constructorDeclaration                              #MemberConstructor
    | decl=methodDeclaration                                   #MemberMethod
    | decl=normalClassDeclaration                               #MemberClass
    | decl=enumDeclaration                                      #MemberEnum
    | decl=normalInterfaceDeclaration                           #MemberInterface
    | decl=annotationTypeDeclaration                            #MemberAnnotationType
    | ';'                                                      #MemberEmpty
    ;


/*
 * 规则：fieldDeclaration
 * 所属分块：08 类型/成员声明
 * 用途：声明节点：fieldDeclaration（Var/Const + name(:type)? + initializer?）。
 * 说明：
 *   - 该规则只描述"可解析的语法形态"；名称解析/类型推断/语义收敛等由 AST Checker 处理。
 *   - 若本规则包含多个分支，建议在 AST 层以 alt label（#...）作为具体节点类型划分依据。
 *
 * 典型写法：
 *   private var count: int = 0;
 */
//graph::fieldDeclaration #@rule 说明：声明节点：fieldDeclaration（Var/Const + name(:type)? + initializer?）。
fieldDeclaration
    : mods=modifier* kind=(Var | Const)
      tail=variableDeclarationTail
    ;


/*
 * 规则：propertyDeclaration
 * 所属分块：08 类型/成员声明
 * 用途：声明节点：propertyDeclaration。
 * 说明：
 *   - 该规则只描述"可解析的语法形态"；名称解析/类型推断/语义收敛等由 AST Checker 处理。
 *   - 若本规则包含多个分支，建议在 AST 层以 alt label（#...）作为具体节点类型划分依据。
 *
 * 典型写法：
 *   var name: String { get; set; };
 */
//graph::propertyDeclaration #@rule 说明：声明节点：propertyDeclaration。
propertyDeclaration
    : mods=modifier* header=propertyHeader body=propertyBody ';'          #PropertyDeclarationFull
    ;

/*
 * 规则：propertyHeader
 * 类型：语法节点
 *
 * 典型写法：
 *   // propertyHeader 的一种典型写法（见该规则 RHS）
 *
 * 备注：
 *   - 本规则只描述可解析的语法形态；语义/名称解析/类型推断/合法性收敛在 AST Checker 中完成。
 */

//graph::propertyHeader #@rule 说明：property 声明头：kind + binding。
propertyHeader
    : kind=(Var | Const) bindingRef=binding
    ;

/*
 * 规则：propertyBody
 * 类型：语法节点
 * 作用：匹配语法形态：'{' propertyAccessorDecl+ '}' 或 '{' softGet ';'? '}'
 *
 * 典型写法：
 *   // propertyBody 的一种典型写法（见该规则 RHS）
 *
 * 备注：
 *   - 本规则只描述可解析的语法形态；语义/名称解析/类型推断/合法性收敛在 AST Checker 中完成。
 */

//graph::propertyBody #@rule 说明：property 主体：getter/setter 声明列表。
propertyBody
    : '{' propertyAccessorDecl+ '}'      #PropertyBodyNode
    ;


/*
 * 规则：propertyAccessorDecl
 * 类型：语法节点
 * 作用：匹配语法形态：Identifier ';'? （get 或 set）
 *
 * 典型写法：
 *   // propertyAccessorDecl 的一种典型写法（见该规则 RHS）
 *
 * 备注：
 *   - 本规则只描述可解析的语法形态；语义/名称解析/类型推断/合法性收敛在 AST Checker 中完成。
 */

//graph::propertyAccessorDecl #@rule 说明：property 访问器声明：get 或 set。
propertyAccessorDecl
    : softGet ';'?                                      #PropertyAccessorGet
    | softSet ';'?                                      #PropertyAccessorSet
    ;


/*
 * 规则：methodDeclaration
 * 所属分块：08 类型/成员声明
 * 用途：声明节点：methodDeclaration。
 * 说明：
 *   - 该规则只描述“可解析的语法形态”；名称解析/类型推断/语义收敛等由 AST Checker 处理。
 *   - 若本规则包含多个分支，建议在 AST 层以 alt label（#...）作为具体节点类型划分依据。
 *
 * 设计要点：
 *   - 本语言主风格为 **function-style**：`function name(...): Ret { ... }`。
 *   - 早期曾存在 Java-style（返回类型在前）的 methodDeclaration 分支，但为保持语言一致性，
 *     已在 v0.0.17 起删除（仅保留 function-style）。
 *
 * 典型写法：
 *   function add(a: int, b: int): int { return a + b; }
 */
//graph::methodDeclaration #@rule 说明：声明节点：methodDeclaration。
methodDeclaration
    : mods=modifier* Function name=Identifier tparams=typeParameters? '(' plist=functionParameterList? ')'
      ret=functionReturnType? thr=throws_? where=whereClause? body=methodBody                 #MethodDeclarationFunctionStyle
    ;

/*
 * 规则：functionParameterList
 * 所属分块：09 参数、变量与绑定
 * 用途：function-style 方法声明的统一形参列表。
 *
 * 说明：
 * - 该列表只服务于 function-style（`function f(...)`）方法声明。
 * - v0.0.19 起：实例方法使用 **隐式 this（传统 OO）**。
 *   - 用户在方法签名里不写 this。
 *   - 规则：在 class 内声明的非 static 方法自动带一个隐式 receiver（this）。
 *   - 编译器内部可将 receiver 作为隐藏参数/slot0 处理，但不暴露在语法层。
 * - varargs 形参仍使用 `name: T...` 形式（ellipsis 放在 type 之后）。
 */
//graph::functionParameterList #@rule 说明：function-style 形参列表（统一参数模型；最后一个参数可带 varargs）。
functionParameterList
    : params+=functionParameter (',' params+=functionParameter)* ','?
    ;

//graph::functionParameter #@rule 说明：统一函数形参：modifier* + bindingCore + 可选 varargs。
functionParameter
    : mods=modifier* bind=binding varargs='...'?
    ;


/*
 * 规则：methodBody
 * 所属分块：08 类型/成员声明
 * 用途：语法节点：methodBody。
 * 说明：
 *   - 该规则只描述“可解析的语法形态”；名称解析/类型推断/语义收敛等由 AST Checker 处理。
 *   - 若本规则包含多个分支，建议在 AST 层以 alt label（#...）作为具体节点类型划分依据。
 *
 * 典型写法：
 *   // methodBody 的一种典型写法（见该规则 RHS）
 */
//graph::methodBody #@rule 说明：语法节点：methodBody。
methodBody
    : block      #MethodBodyBlock
    | ';'        #MethodBodyEmpty
    ;


/*
 * 规则：constructorDeclaration
 * 所属分块：08 类型/成员声明
 * 用途：声明节点：constructorDeclaration。
 * 说明：
 *   - 该规则只描述“可解析的语法形态”；名称解析/类型推断/语义收敛等由 AST Checker 处理。
 *   - 若本规则包含多个分支，建议在 AST 层以 alt label（#...）作为具体节点类型划分依据。
 *
 * 典型写法：
 *   public constructor(x: int) { this.x = x; }
 */
//graph::constructorDeclaration #@rule 说明：声明节点：constructorDeclaration。
constructorDeclaration
    : mods=modifier* header=constructorHeader body=constructorBody
    ;

/*
 * 规则：constructorHeader
 * 类型：语法节点
 *
 * 典型写法：
 *   // constructorHeader 的一种典型写法（见该规则 RHS）
 *
 * 备注：
 *   - 本规则只描述可解析的语法形态；语义/名称解析/类型推断/合法性收敛在 AST Checker 中完成。
 */

//graph::constructorHeader #@inline_ok #@rule 说明：constructor 声明头：Constructor(params) + throws?。
constructorHeader
    : Constructor '(' plist=functionParameterList? ')'
      thr=throws_?
    ;


/*
 * 规则：constructorBody
 * 所属分块：08 类型/成员声明
 * 用途：语法节点：constructorBody。
 * 说明：
 *   - 该规则只描述“可解析的语法形态”；名称解析/类型推断/语义收敛等由 AST Checker 处理。
 *   - 若本规则包含多个分支，建议在 AST 层以 alt label（#...）作为具体节点类型划分依据。
 *
 * 典型写法：
 *   // constructorBody 的一种典型写法（见该规则 RHS）
 */
//graph::constructorBody #@rule 说明：语法节点：constructorBody。
constructorBody
    : '{' explicitConstructorInvocation? blockStatement* '}'
    ;

/*
 * 规则：explicitConstructorInvocation
 * 所属分块：08 类型/成员声明
 * 用途：语法节点：explicitConstructorInvocation。
 * 说明：
 *   - 该规则只描述“可解析的语法形态”；名称解析/类型推断/语义收敛等由 AST Checker 处理。
 *   - 若本规则包含多个分支，建议在 AST 层以 alt label（#...）作为具体节点类型划分依据。
 *
 * 典型写法：
 *   // explicitConstructorInvocation 的一种典型写法（见该规则 RHS）
 */
//graph::explicitConstructorInvocation #@rule 说明：语法节点：explicitConstructorInvocation。
explicitConstructorInvocation
    : typeArguments? This arguments ';'
    | typeArguments? Super arguments ';'
    | qualifiedName '.' typeArguments? Super arguments ';'
    | primary '.' typeArguments? Super arguments ';'
    ;


/*
 * 规则：enumDeclaration
 * 所属分块：08 类型/成员声明
 * 用途：声明节点：enumDeclaration。
 * 说明：
 *   - 该规则只描述“可解析的语法形态”；名称解析/类型推断/语义收敛等由 AST Checker 处理。
 *   - 若本规则包含多个分支，建议在 AST 层以 alt label（#...）作为具体节点类型划分依据。
 *
 * 典型写法：
 *   enum Color { Red, Green, Blue }
 */
//graph::enumDeclaration #@rule 说明：声明节点：enumDeclaration。
enumDeclaration
    : mods=modifier* Enum name=Identifier sis=superinterfaces? body=enumBody
    ;


/*
 * 规则：enumBody
 * 所属分块：08 类型/成员声明
 * 用途：语法节点：enumBody。
 * 说明：
 *   - 该规则只描述“可解析的语法形态”；名称解析/类型推断/语义收敛等由 AST Checker 处理。
 *   - 若本规则包含多个分支，建议在 AST 层以 alt label（#...）作为具体节点类型划分依据。
 *
 * 典型写法：
 *   // enumBody 的一种典型写法（见该规则 RHS）
 */
//graph::enumBody #@rule 说明：语法节点：enumBody。
enumBody
    : '{' (enumConstant (',' enumConstant)* ','?)? ','? enumBodyDeclarations? '}'
    ;

/*
 * 规则：enumConstant
 * 所属分块：08 类型/成员声明
 * 用途：语法节点：enumConstant。
 * 说明：
 *   - 该规则只描述“可解析的语法形态”；名称解析/类型推断/语义收敛等由 AST Checker 处理。
 *   - 若本规则包含多个分支，建议在 AST 层以 alt label（#...）作为具体节点类型划分依据。
 *
 * 典型写法：
 *   // enumConstant 的一种典型写法（见该规则 RHS）
 */
//graph::enumConstant #@rule 说明：语法节点：enumConstant。
enumConstant
    : modifier* Identifier arguments? classBody?
    ;

/*
 * 规则：enumBodyDeclarations
 * 所属分块：08 类型/成员声明
 * 用途：声明节点：enumBodyDeclarations。
 * 说明：
 *   - 该规则只描述“可解析的语法形态”；名称解析/类型推断/语义收敛等由 AST Checker 处理。
 *   - 若本规则包含多个分支，建议在 AST 层以 alt label（#...）作为具体节点类型划分依据。
 *
 * 典型写法：
 *   // enumBodyDeclarations 的一种典型写法（见该规则 RHS）
 */
//graph::enumBodyDeclarations #@rule 说明：声明节点：enumBodyDeclarations。
enumBodyDeclarations
    : ';' classBodyDeclaration*
    ;

/*
 * 规则：normalInterfaceDeclaration
 * 所属分块：08 类型/成员声明
 * 用途：声明节点：normalInterfaceDeclaration。
 * 说明：
 *   - 该规则只描述“可解析的语法形态”；名称解析/类型推断/语义收敛等由 AST Checker 处理。
 *   - 若本规则包含多个分支，建议在 AST 层以 alt label（#...）作为具体节点类型划分依据。
 *
 * 典型写法：
 *   interface I { function f(x: int): int; }
 */
//graph::normalInterfaceDeclaration #@rule 说明：声明节点：normalInterfaceDeclaration。
normalInterfaceDeclaration
    : mods=modifier* Interface name=Identifier tparams=typeParameters? exts=extendsInterfaces? body=interfaceBody
    ;


/*
 * 规则：extendsInterfaces
 * 所属分块：08 类型/成员声明
 * 用途：语法节点：extendsInterfaces。
 * 说明：
 *   - 该规则只描述“可解析的语法形态”；名称解析/类型推断/语义收敛等由 AST Checker 处理。
 *   - 若本规则包含多个分支，建议在 AST 层以 alt label（#...）作为具体节点类型划分依据。
 *
 * 典型写法：
 *   // extendsInterfaces 的一种典型写法（见该规则 RHS）
 */
//graph::extendsInterfaces #@rule 说明：语法节点：extendsInterfaces。
extendsInterfaces
    : Extends typePostfix (',' typePostfix)* ','?
    ;

/*
 * 规则：interfaceBody
 * 所属分块：08 类型/成员声明
 * 用途：语法节点：interfaceBody。
 * 说明：
 *   - 该规则只描述“可解析的语法形态”；名称解析/类型推断/语义收敛等由 AST Checker 处理。
 *   - 若本规则包含多个分支，建议在 AST 层以 alt label（#...）作为具体节点类型划分依据。
 *
 * 典型写法：
 *   // interfaceBody 的一种典型写法（见该规则 RHS）
 */
//graph::interfaceBody #@rule 说明：语法节点：interfaceBody。
interfaceBody
    : '{' interfaceMemberDeclaration* '}'
    ;

/*
 * 规则：interfaceMemberDeclaration
 * 所属分块：08 类型/成员声明
 * 用途：接口成员入口（扩展挂点）。
 * 说明：
 *   - 该规则只描述“可解析的语法形态”；名称解析/类型推断/语义收敛等由 AST Checker 处理。
 *   - 若本规则包含多个分支，建议在 AST 层以 alt label（#...）作为具体节点类型划分依据。
 *
 * 典型写法：
 *   enum Color { Red, Green, Blue }
 */
//graph::interfaceMemberDeclaration #@keep #@hook 说明：接口成员入口（扩展挂点）。
interfaceMemberDeclaration
    : decl=constantDeclaration                                 #InterfaceMemberConst
    | decl=interfacePropertyDeclaration                         #InterfaceMemberProperty
    | decl=methodDeclaration                                    #InterfaceMemberMethod
    | decl=interfaceMethodDeclaration                           #InterfaceMemberAbstractMethod
    | decl=normalClassDeclaration                               #InterfaceMemberClass
    | decl=enumDeclaration                                      #InterfaceMemberEnum
    | decl=normalInterfaceDeclaration                           #InterfaceMemberInterface
    | decl=annotationTypeDeclaration                            #InterfaceMemberAnnotationType
    | ';'                                                       #InterfaceMemberEmpty
    ;


/*
 * 规则：interfacePropertyDeclaration
 * 所属分块：08 类型/成员声明
 * 用途：声明节点：interfacePropertyDeclaration。
 * 说明：
 *   - 该规则只描述"可解析的语法形态"；名称解析/类型推断/语义收敛等由 AST Checker 处理。
 *   - 若本规则包含多个分支，建议在 AST 层以 alt label（#...）作为具体节点类型划分依据。
 *
 * 典型写法：
 *   // interfacePropertyDeclaration 的一种典型写法（见该规则 RHS）
 */
//graph::interfacePropertyDeclaration #@rule 说明：声明节点：interfacePropertyDeclaration。
interfacePropertyDeclaration
    : header=propertyHeader body=propertyBody ';'
    ;


/*
 * 规则：constantDeclaration
 * 所属分块：08 类型/成员声明
 * 用途：声明节点：constantDeclaration。
 * 说明：
 *   - 该规则只描述"可解析的语法形态"；名称解析/类型推断/语义收敛等由 AST Checker 处理。
 *   - 若本规则包含多个分支，建议在 AST 层以 alt label（#...）作为具体节点类型划分依据。
 *
 * 典型写法：
 *   // constantDeclaration 的一种典型写法（见该规则 RHS）
 */
//graph::constantDeclaration #@rule 说明：声明节点：constantDeclaration。
constantDeclaration
    : mods=modifier* Const
      tail=variableDeclarationTail
    ;


/*
 * 规则：interfaceMethodDeclaration
 * 所属分块：08 类型/成员声明
 * 用途：声明节点：interfaceMethodDeclaration。
 * 说明：
 *   - 该规则只描述"可解析的语法形态"；名称解析/类型推断/语义收敛等由 AST Checker 处理。
 *   - 若本规则包含多个分支，建议在 AST 层以 alt label（#...）作为具体节点类型划分依据。
 *
 * 典型写法：
 *   // interfaceMethodDeclaration 的一种典型写法（见该规则 RHS）
 */
//graph::interfaceMethodDeclaration #@rule 说明：声明节点：interfaceMethodDeclaration。
interfaceMethodDeclaration
    : mods=modifier* Function name=Identifier tparams=typeParameters? '(' plist=functionParameterList? ')'
      ret=functionReturnType? thr=throws_? where=whereClause? ';'                 #InterfaceMethodDeclarationFunctionStyle
    ;

/*
 * 规则：annotationTypeDeclaration
 * 所属分块：08 类型/成员声明
 * 用途：声明节点：annotationTypeDeclaration。
 * 说明：
 *   - 该规则只描述“可解析的语法形态”；名称解析/类型推断/语义收敛等由 AST Checker 处理。
 *   - 若本规则包含多个分支，建议在 AST 层以 alt label（#...）作为具体节点类型划分依据。
 *
 * 典型写法：
 *   @Test(timeout: 1000)
 */
//graph::annotationTypeDeclaration #@rule 说明：声明节点：annotationTypeDeclaration。
annotationTypeDeclaration
    : mods=modifier* '@' Interface name=Identifier body=annotationTypeBody
    ;


/*
 * 规则：annotationTypeBody
 * 所属分块：08 类型/成员声明
 * 用途：类型相关节点：annotationTypeBody。
 * 说明：
 *   - 该规则只描述“可解析的语法形态”；名称解析/类型推断/语义收敛等由 AST Checker 处理。
 *   - 若本规则包含多个分支，建议在 AST 层以 alt label（#...）作为具体节点类型划分依据。
 *
 * 典型写法：
 *   @Test(timeout: 1000)
 */
//graph::annotationTypeBody #@rule 说明：类型相关节点：annotationTypeBody。
annotationTypeBody
    : '{' annotationTypeMemberDeclaration* '}'
    ;

/*
 * 规则：annotationTypeMemberDeclaration
 * 所属分块：08 类型/成员声明
 * 用途：声明节点：annotationTypeMemberDeclaration。
 * 说明：
 *   - 该规则只描述“可解析的语法形态”；名称解析/类型推断/语义收敛等由 AST Checker 处理。
 *   - 若本规则包含多个分支，建议在 AST 层以 alt label（#...）作为具体节点类型划分依据。
 *
 * 典型写法：
 *   @Test(timeout: 1000)
 */
//graph::annotationTypeMemberDeclaration #@rule 说明：声明节点：annotationTypeMemberDeclaration。
annotationTypeMemberDeclaration
    : annotationTypeElementDeclaration
    | constantDeclaration
    | (normalClassDeclaration | enumDeclaration)
    | (normalInterfaceDeclaration | annotationTypeDeclaration)
    | ';'
    ;

/*
 * 规则：annotationTypeElementDeclaration
 * 所属分块：08 类型/成员声明
 * 用途：声明节点：annotationTypeElementDeclaration。
 * 说明：
 *   - 该规则只描述“可解析的语法形态”；名称解析/类型推断/语义收敛等由 AST Checker 处理。
 *   - 若本规则包含多个分支，建议在 AST 层以 alt label（#...）作为具体节点类型划分依据。
 *
 * 典型写法：
 *   List<Int32>[]?
 */
//graph::typeOrPrimitive #@inline_ok #@rule 说明：类型相关辅助节点：typeOrPrimitive（primitiveType 或 typeExpr）。
typeOrPrimitive
    : primitiveType   #TypeOrPrimitivePrimitive
    | typeExpr         #TypeOrPrimitiveType
    ;


/*
 * 规则：annotationTypeElementDeclaration
 * 类型：声明节点
 * 作用：匹配语法形态：mods=modifier* ty=typeOrPrimitive name=Identifier '(' ')' ('[' ']')* defaultValue? ';'
 *
 * 典型写法：
 *   @Test(timeout: 1000)
 *
 * 备注：
 *   - 本规则只描述可解析的语法形态；语义/名称解析/类型推断/合法性收敛在 AST Checker 中完成。
 */

//graph::annotationTypeElementDeclaration #@rule 说明：声明节点：annotationTypeElementDeclaration。
annotationTypeElementDeclaration
    : mods=modifier* ty=typeOrPrimitive name=Identifier '(' ')' ('[' ']')* defaultValue? ';'
    ;

/*
======================================================================
09 参数、变量与绑定
======================================================================
函数/方法参数、局部/顶层变量声明，以及统一绑定核心 binding 等“带类型绑定”结构。
======================================================================
*/

/*
 * 规则：functionReturnType
 * 所属分块：09 参数、变量与绑定
 * 用途：类型相关节点：functionReturnType。
 * 说明：
 *   - 该规则只描述“可解析的语法形态”；名称解析/类型推断/语义收敛等由 AST Checker 处理。
 *   - 若本规则包含多个分支，建议在 AST 层以 alt label（#...）作为具体节点类型划分依据。
 *
 * 典型写法：
 *   // functionReturnType 的一种典型写法（见该规则 RHS）
 */
//graph::functionReturnType #@rule 说明：类型相关节点：functionReturnType。
functionReturnType
    : ':' typeExpr
    ;


/*
 * 规则：throws_
 * 所属分块：09 参数、变量与绑定
 * 用途：语法节点：throws_。
 * 说明：
 *   - 该规则只描述“可解析的语法形态”；名称解析/类型推断/语义收敛等由 AST Checker 处理。
 *   - 若本规则包含多个分支，建议在 AST 层以 alt label（#...）作为具体节点类型划分依据。
 *
 * 典型写法：
 *   throw err;
 */
//graph::throws_ #@rule 说明：语法节点：throws_。
throws_
    : Throws ( exceptionType (',' exceptionType)* ','? )
    ;

/*
 * 规则：exceptionType
 * 所属分块：09 参数、变量与绑定
 * 用途：类型相关节点：exceptionType。
 * 说明：
 *   - 该规则只描述“可解析的语法形态”；名称解析/类型推断/语义收敛等由 AST Checker 处理。
 *   - 若本规则包含多个分支，建议在 AST 层以 alt label（#...）作为具体节点类型划分依据。
 *
 * 典型写法：
 *   // exceptionType 的一种典型写法（见该规则 RHS）
 */
//graph::exceptionType #@rule 说明：类型相关节点：exceptionType。
exceptionType
    : typePostfix
    | typeExpr
    ;

/*
 * 规则：localVariableDeclarationStatement
 * 所属分块：09 参数、变量与绑定
 * 用途：语句节点：localVariableDeclarationStatement。
 * 说明：
 *   - 该规则只描述"可解析的语法形态"；名称解析/类型推断/语义收敛等由 AST Checker 处理。
 *   - 若本规则包含多个分支，建议在 AST 层以 alt label（#...）作为具体节点类型划分依据。
 *
 * 典型写法：
 *   // localVariableDeclarationStatement 的一种典型写法（见该规则 RHS）
 */
//graph::localVariableDeclarationStatement #@rule 说明：语句节点：localVariableDeclarationStatement。
localVariableDeclarationStatement
    : mods=modifier* kind=(Var | Const)
      tail=variableDeclarationTail
    ;


/*
 * 规则：variableDeclarationTail
 * 所属分块：09 参数、变量与绑定
 * 用途：统一变量声明尾部：绑定列表 + 分号。
 */
//graph::variableDeclarationTail #@keep #@rule 说明：统一变量声明尾部：变量绑定列表 + 分号。
variableDeclarationTail
    : decls+=variableBinding (',' decls+=variableBinding)* ','? ';'
    ;

/*
 * 规则：binding
 * 所属分块：09 参数、变量与绑定
 * 用途：语法节点：binding。
 * 说明：
 *   - 该规则只描述"可解析的语法形态"；名称解析/类型推断/语义收敛等由 AST Checker 处理。
 *   - 若本规则包含多个分支，建议在 AST 层以 alt label（#...）作为具体节点类型划分依据。
 *
 * 典型写法：
 *   x: int = 1
 */
//graph::variableBinding #@rule 说明：变量声明专用绑定：binding + 可选初始化表达式。
variableBinding
    : core=binding (assign='=' init=expression)?
    ;

//graph::binding #@rule 说明：统一绑定核心：可选类型的名字绑定，供参数、property、pattern、catch、resource 等复用。
binding
    : name=Identifier (':' typeRef=typeExpr)?
    ;


/*
 * 规则：variableInitializer
 * 所属分块：09 参数、变量与绑定
 * 用途：语法节点：variableInitializer。
 * 说明：
 *   - 该规则只描述“可解析的语法形态”；名称解析/类型推断/语义收敛等由 AST Checker 处理。
 *   - 若本规则包含多个分支，建议在 AST 层以 alt label（#...）作为具体节点类型划分依据。
 *
 * 典型写法：
 *   // variableInitializer 的一种典型写法（见该规则 RHS）
 */
//graph::variableInitializer #@rule 说明：语法节点：variableInitializer。
variableInitializer
    : expression                                                            #VariableInitializerExpression
    | arrayInitializer                                                      #VariableInitializerArray
    ;


/*
======================================================================
10 模式匹配与 pattern
======================================================================
pattern 系列规则（or/and/not/primary）与绑定模式，用于 for-in 赋值模式等。
======================================================================
*/

/*
 * 规则：pattern
 * 所属分块：10 模式匹配与 pattern
 * 用途：模式匹配入口：按 or/and/not/paren 优先级组织。
 * 说明：
 *   - 该规则只描述“可解析的语法形态”；名称解析/类型推断/语义收敛等由 AST Checker 处理。
 *   - 若本规则包含多个分支，建议在 AST 层以 alt label（#...）作为具体节点类型划分依据。
 *
 * 典型写法：
 *   // pattern 的一种典型写法（见该规则 RHS）
 */
//graph::pattern #@keep #@core 说明：模式匹配入口：按 or/and/not/paren 优先级组织。
pattern
    : patternOr                                     #PatternRoot
    ;

/*
 * 规则：patternOr
 * 所属分块：10 模式匹配与 pattern
 * 用途：语法节点：patternOr。
 * 说明：
 *   - 该规则只描述“可解析的语法形态”；名称解析/类型推断/语义收敛等由 AST Checker 处理。
 *   - 若本规则包含多个分支，建议在 AST 层以 alt label（#...）作为具体节点类型划分依据。
 *
 * 典型写法：
 *   // patternOr 的一种典型写法（见该规则 RHS）
 */
//graph::patternOr #@rule 说明：语法节点：patternOr。
patternOr
    : patternAnd (softOr patternAnd)*               #PatternOrChain
    ;

/*
 * 规则：patternAnd
 * 所属分块：10 模式匹配与 pattern
 * 用途：语法节点：patternAnd。
 * 说明：
 *   - 该规则只描述“可解析的语法形态”；名称解析/类型推断/语义收敛等由 AST Checker 处理。
 *   - 若本规则包含多个分支，建议在 AST 层以 alt label（#...）作为具体节点类型划分依据。
 *
 * 典型写法：
 *   // patternAnd 的一种典型写法（见该规则 RHS）
 */
//graph::patternAnd #@rule 说明：语法节点：patternAnd。
patternAnd
    : patternUnary (softAnd patternUnary)*          #PatternAndChain
    ;

/*
 * 规则：patternUnary
 * 所属分块：10 模式匹配与 pattern
 * 用途：语法节点：patternUnary。
 * 说明：
 *   - 该规则只描述“可解析的语法形态”；名称解析/类型推断/语义收敛等由 AST Checker 处理。
 *   - 若本规则包含多个分支，建议在 AST 层以 alt label（#...）作为具体节点类型划分依据。
 *
 * 典型写法：
 *   // patternUnary 的一种典型写法（见该规则 RHS）
 */
//graph::patternUnary #@rule 说明：语法节点：patternUnary。
patternUnary
    : softNot patternUnary                          #PatternUnaryNot
    | patternPrimary                                #PatternUnaryBase
    ;

/*
 * 规则：patternPrimary
 * 所属分块：10 模式匹配与 pattern
 * 用途：语法节点：patternPrimary。
 * 说明：
 *   - 该规则只描述“可解析的语法形态”；名称解析/类型推断/语义收敛等由 AST Checker 处理。
 *   - 若本规则包含多个分支，建议在 AST 层以 alt label（#...）作为具体节点类型划分依据。
 *
 * 典型写法：
 *   // patternPrimary 的一种典型写法（见该规则 RHS）
 */
//graph::patternPrimary #@rule 说明：语法节点：patternPrimary。
patternPrimary
    : primaryPattern                                #PatternPrimaryAtom
    | '(' pattern ')'                               #PatternPrimaryParen
    ;

/*
 * 规则：primaryPattern
 * 所属分块：10 模式匹配与 pattern
 * 用途：语法节点：primaryPattern。
 * 说明：
 *   - 该规则只描述“可解析的语法形态”；名称解析/类型推断/语义收敛等由 AST Checker 处理。
 *   - 若本规则包含多个分支，建议在 AST 层以 alt label（#...）作为具体节点类型划分依据。
 *
 * 典型写法：
 *   // primaryPattern 的一种典型写法（见该规则 RHS）
 */
//graph::primaryPattern #@rule 说明：语法节点：primaryPattern（限制为原子模式；不直接吞任意 expression）。
primaryPattern
    : Underscore
    | literal
    | qualifiedName
    ;

/*
 * 规则：bindingPattern
 * 所属分块：10 模式匹配与 pattern
 * 用途：语法节点：bindingPattern。
 * 说明：
 *   - 该规则只描述“可解析的语法形态”；名称解析/类型推断/语义收敛等由 AST Checker 处理。
 *   - 若本规则包含多个分支，建议在 AST 层以 alt label（#...）作为具体节点类型划分依据。
 *
 * 典型写法：
 *   x: int
 */
//graph::bindingPattern #@rule 说明：语法节点：bindingPattern。
bindingPattern
    : bindingAtom
    | bindingTuple
    ;

/*
 * 规则：bindingPatternNoType
 * 所属分块：10 模式匹配与 pattern
 * 用途：类型相关节点：bindingPatternNoType。
 * 说明：
 *   - 该规则只描述“可解析的语法形态”；名称解析/类型推断/语义收敛等由 AST Checker 处理。
 *   - 若本规则包含多个分支，建议在 AST 层以 alt label（#...）作为具体节点类型划分依据。
 *
 * 典型写法：
 *   x: int
 */
//graph::bindingPatternNoType #@rule 说明：类型相关节点：bindingPatternNoType。
bindingPatternNoType
    : bindingNoTypeAtom
    | bindingNoTypeTuple
    ;

/*
 * 规则：bindingAtom
 * 所属分块：10 模式匹配与 pattern
 * 用途：语法节点：bindingAtom。
 * 说明：
 *   - 该规则只描述“可解析的语法形态”；名称解析/类型推断/语义收敛等由 AST Checker 处理。
 *   - 若本规则包含多个分支，建议在 AST 层以 alt label（#...）作为具体节点类型划分依据。
 *
 * 典型写法：
 *   x: int
 */
//graph::bindingAtom #@rule 说明：语法节点：bindingAtom。
bindingAtom
    : Underscore
    | binding
    ;

/*
 * 规则：bindingTuple
 * 所属分块：10 模式匹配与 pattern
 * 用途：语法节点：bindingTuple。
 * 说明：
 *   - 该规则只描述“可解析的语法形态”；名称解析/类型推断/语义收敛等由 AST Checker 处理。
 *   - 若本规则包含多个分支，建议在 AST 层以 alt label（#...）作为具体节点类型划分依据。
 *
 * 典型写法：
 *   x: int
 */
//graph::bindingTuple #@rule 说明：语法节点：bindingTuple。
bindingTuple
    : '(' bindingPattern (',' bindingPattern)+ ','? ')'
    ;

/*
 * 规则：bindingNoTypeAtom
 * 所属分块：10 模式匹配与 pattern
 * 用途：类型相关节点：bindingNoTypeAtom。
 * 说明：
 *   - 该规则只描述“可解析的语法形态”；名称解析/类型推断/语义收敛等由 AST Checker 处理。
 *   - 若本规则包含多个分支，建议在 AST 层以 alt label（#...）作为具体节点类型划分依据。
 *
 * 典型写法：
 *   x: int
 */
//graph::bindingNoTypeAtom #@rule 说明：类型相关节点：bindingNoTypeAtom。
bindingNoTypeAtom
    : Identifier
    | Underscore
    ;

/*
 * 规则：bindingNoTypeTuple
 * 所属分块：10 模式匹配与 pattern
 * 用途：类型相关节点：bindingNoTypeTuple。
 * 说明：
 *   - 该规则只描述“可解析的语法形态”；名称解析/类型推断/语义收敛等由 AST Checker 处理。
 *   - 若本规则包含多个分支，建议在 AST 层以 alt label（#...）作为具体节点类型划分依据。
 *
 * 典型写法：
 *   x: int
 */
//graph::bindingNoTypeTuple #@rule 说明：类型相关节点：bindingNoTypeTuple。
bindingNoTypeTuple
    : '(' bindingPatternNoType (',' bindingPatternNoType)+ ','? ')'
    ;


/*
======================================================================
11 表达式
======================================================================
表达式全家桶：primary 链式、unary/binary 优先级、lambda、cast-as、await/go 等。
======================================================================
*/

/*
 * 规则：exprList
 * 所属分块：11 表达式
 * 用途：列表结构节点：exprList。
 * 说明：
 *   - 该规则只描述“可解析的语法形态”；名称解析/类型推断/语义收敛等由 AST Checker 处理。
 *   - 若本规则包含多个分支，建议在 AST 层以 alt label（#...）作为具体节点类型划分依据。
 *
 * 典型写法：
 *   a, b, c
 */
//graph::exprList #@keep #@container 说明：列表结构节点：exprList。
exprList
    : expression (',' expression)* ','?
    ;

/*
 * 规则：arguments
 * 所属分块：11 表达式
 * 用途：调用参数：括号包裹的可选表达式列表，允许尾逗号。
 * 说明：
 *   - 该规则只描述“可解析的语法形态”；名称解析/类型推断/语义收敛等由 AST Checker 处理。
 *   - 若本规则包含多个分支，建议在 AST 层以 alt label（#...）作为具体节点类型划分依据。
 *
 * 典型写法：
 *   (a, b, c)
 */
//graph::arguments #@keep #@container 说明：调用参数：括号包裹的可选表达式列表，允许尾逗号。
arguments
    : '(' exprList? ')'
    ;

/*
 * 规则：primary
 * 所属分块：11 表达式
 * 用途：Primary 表达式：atom + 后缀链（调用/成员/索引/new-after-dot），并支持方法引用。
 * 说明：
 *   - 该规则只描述“可解析的语法形态”；名称解析/类型推断/语义收敛等由 AST Checker 处理。
 *   - 若本规则包含多个分支，建议在 AST 层以 alt label（#...）作为具体节点类型划分依据。
 *
 * 设计要点：
 *   - primary 被拆为：primaryAtom + primarySuffix*，这样链式调用/点访问/索引/after-dot new
 *     都会表现为“同一个基底 + 一串后缀”，AST 构建时可线性折叠成 Call/Dot/Index/New 节点链。
 *
 * 典型写法：
 *   // primary 的一种典型写法（见该规则 RHS）
 */
//graph::primary #@keep #@core 说明：Primary 表达式：atom + 后缀链（调用/成员/索引/new-after-dot），并支持方法引用。
primary
    : methodReference                                 #PrimaryMethodReference
    | primaryAtom primarySuffix*                       #PrimaryChain
    ;

/*
 * 规则：primarySuffix
 * 所属分块：11 表达式
 * 用途：Primary 后缀：调用/成员/索引/new-after-dot；拆分后更 AST 友好。
 * 说明：
 *   - 该规则只描述“可解析的语法形态”；名称解析/类型推断/语义收敛等由 AST Checker 处理。
 *   - 若本规则包含多个分支，建议在 AST 层以 alt label（#...）作为具体节点类型划分依据。
 *
 * AST 友好：
 *   - 每个后缀都有独立的 alt label（PrimarySuffixCall/PrimarySuffixDot/PrimarySuffixIndex/PrimarySuffixNewAfterDot），
 *     Visitor/Listener 可直接按节点类型构建，不需要在 ( ... )* 循环里做形状判断。
 *
 * 典型写法：
 *   // primarySuffix 的一种典型写法（见该规则 RHS）
 */
//graph::primarySuffix #@keep #@hook 说明：Primary 后缀：调用/成员/索引/new-after-dot；拆分后更 AST 友好。
primarySuffix
    : arguments                                        #PrimarySuffixCall
    | '.' typeArguments? Identifier arguments?          #PrimarySuffixDot
    | SAFE_DOT typeArguments? Identifier arguments?     #PrimarySuffixSafeDot
    | '!'                                              #PrimarySuffixNotNullAssert
    | '[' expression ']'                               #PrimarySuffixIndex
    | classInstanceCreationExpressionAfterDot           #PrimarySuffixNewAfterDot
    ;

/*
 * 规则：primaryAtom
 * 所属分块：11 表达式
 * 用途：Primary 原子：字面量/this/super/name/new/数组创建/括号等。
 * 说明：
 *   - 该规则只描述“可解析的语法形态”；名称解析/类型推断/语义收敛等由 AST Checker 处理。
 *   - 若本规则包含多个分支，建议在 AST 层以 alt label（#...）作为具体节点类型划分依据。
 *
 * 典型写法：
 *   // primaryAtom 的一种典型写法（见该规则 RHS）
 */
//graph::primaryAtom #@keep #@hook 说明：Primary 原子：字面量/this/super/name/new/数组创建/括号等。
primaryAtom
    : literal                                         #PrimaryAtomLiteral
    | qualifiedName ('[' ']')* '.' Class                    #PrimaryAtomClassLiteral
    | primitiveType ('[' ']')* '.' Class          #PrimaryAtomPrimaryClassLiteral
    | Void '.' Class                                   #PrimaryAtomUnitClassLiteral
    | This                                             #PrimaryAtomThis
    | qualifiedName '.' This                                #PrimaryAtomTypeThis
    | '(' expression (',' expression)+ ','? ')'         #PrimaryAtomTuple
    | '(' expression ')'                               #PrimaryAtomParen
    | classInstanceCreationExpression                  #PrimaryAtomNew
    | arrayCreationExpression                          #PrimaryAtomArrayCreation
    | qualifiedName                                   #PrimaryAtomName
    | Super                                            #PrimaryAtomSuper
    | qualifiedName '.' Super                               #PrimaryAtomTypeSuper
    ;


/*
 * 规则：methodReference
 * 所属分块：11 表达式
 * 用途：语法节点：methodReference。
 * 说明：
 *   - 该规则只描述“可解析的语法形态”；名称解析/类型推断/语义收敛等由 AST Checker 处理。
 *   - 若本规则包含多个分支，建议在 AST 层以 alt label（#...）作为具体节点类型划分依据。
 *
 * 典型写法：
 *   // methodReference 的一种典型写法（见该规则 RHS）
 */
//graph::methodReference #@rule 说明：语法节点：methodReference。
methodReference
    : qualifiedName COLON_COLON typeArguments? Identifier              #MethodReferenceExpressionName
    | typeExpr COLON_COLON typeArguments? Identifier               #MethodReferenceType
    | primaryAtom
      (
          arguments
        | '.' typeArguments? Identifier arguments?
        | '[' expression ']'
        | classInstanceCreationExpressionAfterDot
      )*
      COLON_COLON typeArguments? Identifier                             #MethodReferencePrimaryChain
    | Super COLON_COLON typeArguments? Identifier                       #MethodReferenceSuper
    | qualifiedName '.' Super COLON_COLON typeArguments? Identifier          #MethodReferenceTypeSuper
    | typePostfix COLON_COLON typeArguments? New                          #ConstructorReferenceClass
    | typeExpr COLON_COLON New                                         #ConstructorReferenceArray
    ;

/*
 * 规则：classInstanceCreationExpression
 * 所属分块：11 表达式
 * 用途：表达式节点：classInstanceCreationExpression。
 * 说明：
 *   - 该规则只描述“可解析的语法形态”；名称解析/类型推断/语义收敛等由 AST Checker 处理。
 *   - 若本规则包含多个分支，建议在 AST 层以 alt label（#...）作为具体节点类型划分依据。
 *
 * 典型写法：
 *   // classInstanceCreationExpression 的一种典型写法（见该规则 RHS）
 */
//graph::classInstanceCreationExpression #@rule 说明：表达式节点：classInstanceCreationExpression。
classInstanceCreationExpression
    : New typeArguments? annotation* Identifier ('.' annotation* Identifier)*
      typeArgumentsOrDiamond? arguments classBody?      #NewUnqualified
    ;

/*
 * 规则：classInstanceCreationExpressionAfterDot
 * 所属分块：11 表达式
 * 用途：语法节点：classInstanceCreationExpressionAfterDot。
 * 说明：
 *   - 该规则只描述“可解析的语法形态”；名称解析/类型推断/语义收敛等由 AST Checker 处理。
 *   - 若本规则包含多个分支，建议在 AST 层以 alt label（#...）作为具体节点类型划分依据。
 *
 * 典型写法：
 *   // classInstanceCreationExpressionAfterDot 的一种典型写法（见该规则 RHS）
 */
//graph::classInstanceCreationExpressionAfterDot #@rule 说明：语法节点：classInstanceCreationExpressionAfterDot。
classInstanceCreationExpressionAfterDot
    : '.' New typeArguments? annotation* Identifier ('.' annotation* Identifier)*
      typeArgumentsOrDiamond? arguments classBody?      #NewAfterDot
    ;

/*
 * 规则：arrayCreationExpression
 * 所属分块：11 表达式
 * 用途：表达式节点：arrayCreationExpression。
 * 说明：
 *   - 该规则只描述“可解析的语法形态”；名称解析/类型推断/语义收敛等由 AST Checker 处理。
 *   - 若本规则包含多个分支，建议在 AST 层以 alt label（#...）作为具体节点类型划分依据。
 *
 * 典型写法：
 *   // arrayCreationExpression 的一种典型写法（见该规则 RHS）
 */
//graph::arrayCreationExpression #@rule 说明：表达式节点：arrayCreationExpression。
arrayCreationExpression
    : New primitiveType dimExprs dims?                 #ArrayNewPrimitiveDimExprs
    | New typePostfix  dimExprs dims?                  #ArrayNewRefDimExprs
    | New primitiveType dims arrayInitializer          #ArrayInitPrimitive
    | New typePostfix  dims arrayInitializer           #ArrayInitRef
    ;


/*
 * 规则：dimExprs
 * 所属分块：11 表达式
 * 用途：语法节点：dimExprs。
 * 说明：
 *   - 该规则只描述“可解析的语法形态”；名称解析/类型推断/语义收敛等由 AST Checker 处理。
 *   - 若本规则包含多个分支，建议在 AST 层以 alt label（#...）作为具体节点类型划分依据。
 *
 * 典型写法：
 *   // dimExprs 的一种典型写法（见该规则 RHS）
 */
//graph::dimExprs #@rule 说明：语法节点：dimExprs。
dimExprs
    : dimExpr dimExpr*
    ;

/*
 * 规则：dims
 * 类型：语法节点
 * 作用：匹配语法形态：('[' ']')+
 *
 * 典型写法：
 *   // dims 的一种典型写法（见该规则 RHS）
 *
 * 备注：
 *   - 本规则只描述可解析的语法形态；语义/名称解析/类型推断/合法性收敛在 AST Checker 中完成。
 */

//graph::dims #@inline_ok #@rule 说明：数组维度后缀：('[' ']')+。
dims
    : ('[' ']')+
    ;


/*
 * 规则：dimExpr
 * 所属分块：11 表达式
 * 用途：语法节点：dimExpr。
 * 说明：
 *   - 该规则只描述“可解析的语法形态”；名称解析/类型推断/语义收敛等由 AST Checker 处理。
 *   - 若本规则包含多个分支，建议在 AST 层以 alt label（#...）作为具体节点类型划分依据。
 *
 * 典型写法：
 *   // dimExpr 的一种典型写法（见该规则 RHS）
 */
//graph::dimExpr #@rule 说明：语法节点：dimExpr。
dimExpr
    : annotation* '[' expression ']'
    ;

/*
 * 规则：expression
 * 所属分块：11 表达式
 * 用途：表达式入口：lambda 或 assignmentExpression。
 * 说明：
 *   - 该规则只描述"可解析的语法形态"；名称解析/类型推断/语义收敛等由 AST Checker 处理。
 *   - 若本规则包含多个分支，建议在 AST 层以 alt label（#...）作为具体节点类型划分依据。
 *
 * 典型写法：
 *   // expression 的一种典型写法（见该规则 RHS）
 */
//graph::expression #@keep #@core 说明：表达式入口：统一的表达式入口。
expression
    : lambdaExpression                                  #ExprLambda
    | assignmentExpression                              #ExprAssignment
    ;


/*
 * 规则：lambdaExpression
 * 所属分块：11 表达式
 * 用途：表达式节点：lambdaExpression。
 * 说明：
 *   - 该规则只描述“可解析的语法形态”；名称解析/类型推断/语义收敛等由 AST Checker 处理。
 *   - 若本规则包含多个分支，建议在 AST 层以 alt label（#...）作为具体节点类型划分依据。
 *
 * 典型写法：
 *   (x, y) => x + y
 */
//graph::lambdaExpression #@rule 说明：表达式节点：lambdaExpression。
lambdaExpression
    : Lambda lambdaParameters block
    ;

/*
 * 规则：lambdaParameters
 * 所属分块：11 表达式
 * 用途：语法节点：lambdaParameters（优先匹配"推断形参列表"，避免被类型形参分支抢占）。
 * 说明：
 *   - 该规则只描述"可解析的语法形态"；名称解析/类型推断/语义收敛等由 AST Checker 处理。
 *   - 若本规则包含多个分支，建议在 AST 层以 alt label（#...）作为具体节点类型划分依据。
 *
 * 典型写法：
 *   (x, y) => x + y
 */
//graph::lambdaParameters #@rule 说明：语法节点：lambdaParameters（优先匹配"推断形参列表"，避免被类型形参分支抢占）。
lambdaParameters
    : '(' ( Identifier (',' Identifier)* ','? )? ')'                 #LambdaParamsInferredList
    | '(' plist=functionParameterList? ')'                           #LambdaParamsTypedList
    ;



/*
 * 规则：conditionalExpression
 * 所属分块：11 表达式
 * 用途：表达式节点：conditionalExpression。
 * 说明：
 *   - 该规则只描述"可解析的语法形态"；名称解析/类型推断/语义收敛等由 AST Checker 处理。
 *   - 若本规则包含多个分支，建议在 AST 层以 alt label（#...）作为具体节点类型划分依据。
 *
 * 典型写法：
 *   // conditionalExpression 的一种典型写法（见该规则 RHS）
 */
//graph::conditionalExpression #@rule 说明：表达式节点：conditionalExpression。
//graph::assignmentExpression #@rule 说明：赋值表达式层：assignment 或 conditionalExpression。
assignmentExpression
    : assignment                                        #AssignExprAssignment
    | conditionalExpression                             #AssignExprConditional
    ;

conditionalExpression
    : nullFallbackExpression ('?' expression ':' conditionalExpression)?    #CondExpr
    ;

//graph::nullFallbackExpression #@rule 说明：语法糖：a?.b else c  ≡ (a?.b) ?? c
nullFallbackExpression
    : nullCoalesceExpression (Else nullCoalesceExpression)?                 #NullFallbackExpr
    ;

//graph::nullCoalesceExpression #@rule 说明：空合并（??），优先级介于 binary 与 conditional 之间。
nullCoalesceExpression
    : binaryExpression (NULL_COALESCE binaryExpression)*                    #NullCoalesceExpr
    ;

/*
 * 规则：binaryExpression
 * 所属分块：11 表达式
 * 用途：二元表达式：用直接左递归实现优先级链，并保留可扩展插入点。
 * 说明：
 *   - 该规则只描述"可解析的语法形态"；名称解析/类型推断/语义收敛等由 AST Checker 处理。
 *   - 若本规则包含多个分支，建议在 AST 层以 alt label（#...）作为具体节点类型划分依据。
 *
 * 全角支持：
 *   - 位或（|）同时支持半角 PIPE 与全角 FULLWIDTH_PIPE（｜），以适配输入法/全角环境。
 *
 * 典型写法：
 *   // binaryExpression 的一种典型写法（见该规则 RHS）
 */
//graph::binaryExpression #@keep #@core 说明：二元表达式：用直接左递归实现优先级链，并保留可扩展插入点。
binaryExpression
    : unaryExpression                                                  #BinaryUnary
    | binaryExpression op=(STAR | SLASH | PERCENT) unaryExpression      #BinaryMultiplication
    | binaryExpression op=(PLUS | MINUS) unaryExpression                #BinaryAddition
    | binaryExpression op=(LSHIFT | RSHIFT | URSHIFT) unaryExpression   #BinaryShift
    | binaryExpression op=(LT | GT | LE | GE) unaryExpression           #BinaryRelational
    | binaryExpression op=(EQUAL_EQUAL | NOT_EQUAL) unaryExpression     #BinaryEquality
    | binaryExpression op=AMP unaryExpression                           #BinaryBitAnd
    | binaryExpression op=CARET unaryExpression                         #BinaryBitExclusiveOr
    | binaryExpression op=(PIPE | FULLWIDTH_PIPE) unaryExpression       #BinaryBitOr
    | binaryExpression op=(And | AND_AND) unaryExpression               #BinaryAnd
    | binaryExpression op=(Or | OR_OR) unaryExpression                  #BinaryOr
    ;

/*
 * 规则：unaryExpression
 * 所属分块：11 表达式
 * 用途：一元表达式层。保留优先级层，不把一元运算扁平摊回 expression 顶层。
 */
//graph::unaryExpression #@keep #@core 说明：一元表达式层。保留优先级层，不把一元运算扁平摊回 expression 顶层。
unaryExpression
    : '+' unaryExpression                               #UnaryPlus
    | '-' unaryExpression                               #UnaryMinus
    | '*' unaryExpression                               #UnaryDeref
    | '&' unaryExpression                               #UnaryAddressOf
    | '~' unaryExpression                               #UnaryBitNot
    | (Not | '!') unaryExpression                       #UnaryLogicalNot
    | awaitExpression                                   #UnaryAwait
    | goExpression                                      #UnaryGo
    | castExpression                                    #UnaryCast
    | primary                                           #UnaryPrimary
    ;

/*
 * 规则：awaitExpression
 * 所属分块：11 表达式
 * 用途：表达式节点：awaitExpression。
 * 说明：
 *   - 该规则只描述“可解析的语法形态”；名称解析/类型推断/语义收敛等由 AST Checker 处理。
 *   - 若本规则包含多个分支，建议在 AST 层以 alt label（#...）作为具体节点类型划分依据。
 *
 * 典型写法：
 *   await task;
 */
//graph::awaitExpression #@rule 说明：表达式节点：awaitExpression。
awaitExpression
    : Await unaryExpression
    ;

/*
 * 规则：goExpression
 * 所属分块：11 表达式
 * 用途：表达式节点：goExpression。
 * 说明：
 *   - 该规则只描述“可解析的语法形态”；名称解析/类型推断/语义收敛等由 AST Checker 处理。
 *   - 若本规则包含多个分支，建议在 AST 层以 alt label（#...）作为具体节点类型划分依据。
 *
 * 典型写法：
 *   go foo();
 */
//graph::goExpression #@rule 说明：表达式节点：goExpression。
goExpression
    : Go goTarget
    ;

/*
 * 规则：castExpression
 * 所属分块：11 表达式
 * 用途：表达式节点：castExpression。
 * 说明：
 *   - 该规则只描述“可解析的语法形态”；名称解析/类型推断/语义收敛等由 AST Checker 处理。
 *   - 若本规则包含多个分支，建议在 AST 层以 alt label（#...）作为具体节点类型划分依据。
 *
 * 典型写法：
 *   // castExpression 的一种典型写法（见该规则 RHS）
 */
//graph::castExpression #@rule 说明：统一转换表达式：括号 cast 与 cast ... as ... 共用一族节点。
castExpression
    : '(' primitiveType ')' unaryExpression                                             #CastPrimitive
    | '(' typeExpr ')' (unaryExpression | lambdaExpression)                          #CastTypeExpr
    | softCast expr=unaryExpression softAs ty=typePostfix                                #CastAs
    ;



/*
 * 规则：assignment
 * 所属分块：11 表达式
 * 用途：语法节点：assignment。
 * 说明：
 *   - 该规则只描述"可解析的语法形态"；名称解析/类型推断/语义收敛等由 AST Checker 处理。
 *   - 若本规则包含多个分支，建议在 AST 层以 alt label（#...）作为具体节点类型划分依据。
 *
 * 典型写法：
 *   // assignment 的一种典型写法（见该规则 RHS）
 */
//graph::assignment #@rule 说明：统一赋值表达式：单一 assignmentTarget + operator + rhs。
assignment
    : target=assignmentTarget op=assignmentOperator rhs=expression    #AssignmentExpr
    ;

//graph::assignmentTarget #@rule 说明：统一可赋值目标：限定名或带至少一个后缀的 primary 链。
assignmentTarget
    : qualifiedName                                                   #AssignmentExprTargetQualifiedName
    | primaryAtom primarySuffix+                                      #AssignmentExprTargetPrimaryChain
    ;


/*
 * 规则：assignmentOperator
 * 所属分块：11 表达式
 * 用途：语法节点：assignmentOperator。
 * 说明：
 *   - 该规则只描述“可解析的语法形态”；名称解析/类型推断/语义收敛等由 AST Checker 处理。
 *   - 若本规则包含多个分支，建议在 AST 层以 alt label（#...）作为具体节点类型划分依据。
 *
 * 典型写法：
 *   // assignmentOperator 的一种典型写法（见该规则 RHS）
 */
//graph::assignmentOperator #@rule 说明：语法节点：assignmentOperator。
assignmentOperator
    : '='
    | '*='
    | '/='
    | '%='
    | '+='
    | '-='
    | '<<='
    | '>>='
    | '>>>='
    | '&='
    | '^='
    | '|='
    ;

/*
======================================================================
12 语句与控制流
======================================================================
block/statement 与控制流结构（if/for/while/switch/try/lock/defer/go...）。Jasper 强制 block。
======================================================================
*/

/*
 * 规则：block
 * 所属分块：12 语句与控制流
 * 用途：语法节点：block。
 * 说明：
 *   - 该规则只描述“可解析的语法形态”；名称解析/类型推断/语义收敛等由 AST Checker 处理。
 *   - 若本规则包含多个分支，建议在 AST 层以 alt label（#...）作为具体节点类型划分依据。
 *
 * 典型写法：
 *   // block 的一种典型写法（见该规则 RHS）
 */
//graph::block #@rule 说明：语法节点：block。
block
    : '{' blockStatement* '}'
    ;

/*
 * 规则：blockStatement
 * 所属分块：12 语句与控制流
 * 用途：语句节点：blockStatement。
 * 说明：
 *   - 该规则只描述“可解析的语法形态”；名称解析/类型推断/语义收敛等由 AST Checker 处理。
 *   - 若本规则包含多个分支，建议在 AST 层以 alt label（#...）作为具体节点类型划分依据。
 *
 * 典型写法：
 *   // blockStatement 的一种典型写法（见该规则 RHS）
 */
//graph::blockStatement #@rule 说明：语句节点：blockStatement。
blockStatement
    : localVariableDeclarationStatement   #BlockStatementLocalVariable
    | (normalClassDeclaration | enumDeclaration)                          #BlockStatementClassDeclaration
    | statement                                 #BlockStatementStatement
    ;

/*
 * 规则：statement
 * 所属分块：12 语句与控制流
 * 用途：语句入口：控制流/标签/循环/延迟等。
 * 说明：
 *   - 该规则只描述"可解析的语法形态"；名称解析/类型推断/语义收敛等由 AST Checker 处理。
 *   - 若本规则包含多个分支，建议在 AST 层以 alt label（#...）作为具体节点类型划分依据。
 *
 * 设计要点：
 *   - Jasper 语法层"永远强制 block"：if/while/do/for 的主体都要求 block。
 *     这样语法树更稳定，且完全消除 dangling-else 类歧义。
 *
 * 典型写法：
 *   if (ok) { doWork(); }
 */
//graph::statement #@keep #@core 说明：语句入口：控制流/标签/循环/延迟等。
statement
    : block                #StatementBlock
    | ';'                  #StatementEmpty
    | expression ';'       #StatementExpressionStatement
    | yieldStatement        #StatementYield
    | assertStatement       #StatementAssert
    | switchStatement        #StatementSwitch
    | doStatement           #StatementDo
    | breakStatement        #StatementBreak
    | continueStatement     #StatementContinue
    | returnStatement       #StatementReturn
    | lockStatement         #StatementLock
    | throwStatement        #StatementThrow
    | tryStatement          #StatementTry
    | labeledStatement      #StatementLabeled
    | ifStatement           #StatementIf
    | whileStatement        #StatementWhile
    | forStatement          #StatementFor
    | deferStatement        #StatementDefer
    ;

/*
 * 规则：deferStatement
 * 所属分块：12 语句与控制流
 * 用途：语句节点：deferStatement。
 * 说明：
 *   - 该规则只描述“可解析的语法形态”；名称解析/类型推断/语义收敛等由 AST Checker 处理。
 *   - 若本规则包含多个分支，建议在 AST 层以 alt label（#...）作为具体节点类型划分依据。
 *
 * 典型写法：
 *   defer { close(); }
 */
//graph::deferStatement #@rule 说明：语句节点：deferStatement。
deferStatement
    : Defer statement
    ;

/*
 * 规则：loopThenElse
 * 所属分块：12 语句与控制流
 * 用途：语法节点：loopThenElse。
 * 说明：
 *   - 该规则只描述“可解析的语法形态”；名称解析/类型推断/语义收敛等由 AST Checker 处理。
 *   - 若本规则包含多个分支，建议在 AST 层以 alt label（#...）作为具体节点类型划分依据。
 *
 * 典型写法：
 *   // loopThenElse 的一种典型写法（见该规则 RHS）
 */
//graph::loopThenElse #@rule 说明：语法节点：loopThenElse。
loopThenElse
    : softThen block (Else block)?
    ;

/*
 * 规则：loopStatement
 * 所属分块：12 语句与控制流
 * 用途：语句节点：loopStatement。
 * 说明：
 *   - 该规则只描述“可解析的语法形态”；名称解析/类型推断/语义收敛等由 AST Checker 处理。
 *   - 若本规则包含多个分支，建议在 AST 层以 alt label（#...）作为具体节点类型划分依据。
 *
 * 典型写法：
 *   // loopStatement 的一种典型写法（见该规则 RHS）
 */
//graph::loopStatement #@rule 说明：语句节点：loopStatement。
loopStatement
    : whileStatement
    | doStatement
    | forStatement
    ;

/*
 * 规则：labeledStatement
 * 所属分块：12 语句与控制流
 * 用途：语句节点：labeledStatement。
 * 说明：
 *   - 该规则只描述“可解析的语法形态”；名称解析/类型推断/语义收敛等由 AST Checker 处理。
 *   - 若本规则包含多个分支，建议在 AST 层以 alt label（#...）作为具体节点类型划分依据。
 *
 * 典型写法：
 *   // labeledStatement 的一种典型写法（见该规则 RHS）
 */
//graph::labeledStatement #@rule 说明：语句节点：labeledStatement。
labeledStatement
    : softLabel label=Identifier ':' loopStatement
    ;


/*
 * 规则：yieldStatement
 * 所属分块：12 语句与控制流
 * 用途：语句节点：yieldStatement（yield / yield*）。
 * 说明：
 *   - 该规则只描述“可解析的语法形态”；名称解析/类型推断/语义收敛等由 AST Checker 处理。
 *   - 若本规则包含多个分支，建议在 AST 层以 alt label（#...）作为具体节点类型划分依据。
 *
 * 典型写法：
 *   yield x;
 */
//graph::yieldStatement #@rule 说明：语句节点：yieldStatement（yield / yield*）。
yieldStatement
    : Yield '*'? expression ';'
    ;

/*
 * 规则：ifStatement
 * 所属分块：12 语句与控制流
 * 用途：语句节点：ifStatement。
 * 说明：
 *   - 该规则只描述“可解析的语法形态”；名称解析/类型推断/语义收敛等由 AST Checker 处理。
 *   - 若本规则包含多个分支，建议在 AST 层以 alt label（#...）作为具体节点类型划分依据。
 *
 * 典型写法：
 *   if (ok) { doWork(); }
 */
//graph::ifStatement #@rule 说明：语句节点：ifStatement。
ifStatement
    : If '(' expression ')' block (Else (ifStatement | block))?
    ;

/*
 * 规则：assertStatement
 * 所属分块：12 语句与控制流
 * 用途：语句节点：assertStatement。
 * 说明：
 *   - 该规则只描述“可解析的语法形态”；名称解析/类型推断/语义收敛等由 AST Checker 处理。
 *   - 若本规则包含多个分支，建议在 AST 层以 alt label（#...）作为具体节点类型划分依据。
 *
 * 典型写法：
 *   // assertStatement 的一种典型写法（见该规则 RHS）
 */
//graph::assertStatement #@rule 说明：语句节点：assertStatement。
assertStatement
    : Assert expression ';'                      #AssertSimple
    | Assert expression ':' expression ';'       #AssertWithMessage
    ;

/*
 * 规则：switchStatement
 * 所属分块：12 语句与控制流
 * 用途：语句节点：switchStatement。
 * 说明：
 *   - 该规则只描述"可解析的语法形态"；名称解析/类型推断/语义收敛等由 AST Checker 处理。
 *   - 若本规则包含多个分支，建议在 AST 层以 alt label（#...）作为具体节点类型划分依据。
 *
 * 典型写法：
 *   switch (x) { case 1 => { } default => { } }
 */
//graph::switchStatement #@rule 说明：语句节点：switchStatement。
switchStatement
    : Switch '(' expression ')' '{' switchCaseClause* defaultClause '}'
    ;

/*
 * 规则：caseClause
 * 所属分块：12 语句与控制流
 * 用途：语法节点：caseClause。
 * 说明：
 *   - 该规则只描述"可解析的语法形态"；名称解析/类型推断/语义收敛等由 AST Checker 处理。
 *   - 若本规则包含多个分支，建议在 AST 层以 alt label（#...）作为具体节点类型划分依据。
 *
 * 设计要点：
 *   - caseClause 区分 case expression => block 与 default => block（不含贯穿 fall-through）。
 *   - expression 的优先级分层在 expression* 系列规则中表达，Checker 再做语义收敛。
 *
 * 典型写法：
 *   switch (x) { case 1 => { } default => { } }
 */
//graph::switchCaseClause #@rule 说明：语法节点：switchCaseClause。
switchCaseClause
    : Case expression FAT_ARROW block
    ;

//graph::defaultClause #@rule 说明：switch default 分支，必须位于最后。
defaultClause
    : Default FAT_ARROW block
    ;


/*
 * 规则：whileStatement
 * 所属分块：12 语句与控制流
 * 用途：语句节点：whileStatement。
 * 说明：
 *   - 该规则只描述“可解析的语法形态”；名称解析/类型推断/语义收敛等由 AST Checker 处理。
 *   - 若本规则包含多个分支，建议在 AST 层以 alt label（#...）作为具体节点类型划分依据。
 *
 * 典型写法：
 *   while (i < 10) { i++; }
 */
//graph::whileStatement #@rule 说明：语句节点：whileStatement。
whileStatement
    : While '(' expression ')' block loopThenElse?
    ;

/*
 * 规则：doStatement
 * 所属分块：12 语句与控制流
 * 用途：语句节点：doStatement。
 * 说明：
 *   - 该规则只描述“可解析的语法形态”；名称解析/类型推断/语义收敛等由 AST Checker 处理。
 *   - 若本规则包含多个分支，建议在 AST 层以 alt label（#...）作为具体节点类型划分依据。
 *
 * 典型写法：
 *   do { i++; } while (i < 10);
 */
//graph::doStatement #@rule 说明：语句节点：doStatement。
doStatement
    : Do block While '(' expression ')' ';' loopThenElse?
    ;

/*
 * 规则：forStatement
 * 所属分块：12 语句与控制流
 * 用途：语句节点：forStatement。
 * 说明：
 *   - 该规则只描述“可解析的语法形态”；名称解析/类型推断/语义收敛等由 AST Checker 处理。
 *   - 若本规则包含多个分支，建议在 AST 层以 alt label（#...）作为具体节点类型划分依据。
 *
 * 典型写法：
 *   // forStatement 的一种典型写法（见该规则 RHS）
 */
//graph::forStatement #@rule 说明：语句节点：forStatement。
forStatement
    : For '(' forControl ')' block loopThenElse?
    ;

/*
 * 规则：forClassicControl
 * 所属分块：12 语句与控制流
 * 用途：语法节点：forClassicControl。
 * 说明：
 *   - 该规则只描述“可解析的语法形态”；名称解析/类型推断/语义收敛等由 AST Checker 处理。
 *   - 若本规则包含多个分支，建议在 AST 层以 alt label（#...）作为具体节点类型划分依据。
 */

/*
 * 规则：forClassicInit
 * 所属分块：12 语句与控制流
 * 用途：语法节点：forClassicInit（classic for 的 init 部分）。
 *
 * 典型写法：
 *   for (var i = 0; i < n; i++) { sum += i; }
 */
//graph::forClassicInit #@inline_ok #@rule 说明：语法节点：forClassicInit（classic for 的 init 部分）。
forClassicInit
    : kind=(Var | Const) decls+=variableBinding (',' decls+=variableBinding)* ','?     #ForClassicInitDecl
    | exprs+=expression (',' exprs+=expression)* ','?                      #ForClassicInitExprs
    ;

/*
 * 规则：forClassicUpdate
 * 所属分块：12 语句与控制流
 * 用途：语法节点：forClassicUpdate（classic for 的 update 部分）。
 *
 * 典型写法：
 *   // forClassicUpdate 的一种典型写法（见该规则 RHS）
 */
//graph::forClassicUpdate #@inline_ok #@rule 说明：语法节点：forClassicUpdate（classic for 的 update 部分）。
forClassicUpdate
    : exprs+=expression (',' exprs+=expression)* ','?                      #ForClassicUpdateExprs
    ;

/*
 * 规则：forClassicControl
 * 类型：语法节点
 * 作用：匹配语法形态：init=forClassicInit? ';' cond=expression? ';' update=forClassicUpdate?
 *
 * 典型写法：
 *   for (var i = 0; i < n; i++) { sum += i; }
 *
 * 备注：
 *   - 本规则只描述可解析的语法形态；语义/名称解析/类型推断/合法性收敛在 AST Checker 中完成。
 */

//graph::forClassicControl #@rule 说明：语法节点：forClassicControl。
forClassicControl
    : init=forClassicInit? ';' cond=expression? ';' update=forClassicUpdate?
    ;

/*
 * 规则：breakStatement
 * 所属分块：12 语句与控制流
 * 用途：语句节点：breakStatement。
 * 说明：
 *   - 该规则只描述“可解析的语法形态”；名称解析/类型推断/语义收敛等由 AST Checker 处理。
 *   - 若本规则包含多个分支，建议在 AST 层以 alt label（#...）作为具体节点类型划分依据。
 *
 * 典型写法：
 *   break;
 */
//graph::breakStatement #@rule 说明：语句节点：breakStatement。
breakStatement
    : Break Identifier? ';'                     // #BreakStatement
    ;

/*
 * 规则：continueStatement
 * 所属分块：12 语句与控制流
 * 用途：语句节点：continueStatement。
 * 说明：
 *   - 该规则只描述“可解析的语法形态”；名称解析/类型推断/语义收敛等由 AST Checker 处理。
 *   - 若本规则包含多个分支，建议在 AST 层以 alt label（#...）作为具体节点类型划分依据。
 *
 * 典型写法：
 *   continue;
 */
//graph::continueStatement #@rule 说明：语句节点：continueStatement。
continueStatement
    : Continue Identifier? ';'                   //#ContinueStatement
    ;

/*
 * 规则：returnStatement
 * 所属分块：12 语句与控制流
 * 用途：语句节点：returnStatement（支持多值 return：expr (',' expr)*）。
 * 说明：
 *   - 该规则只描述“可解析的语法形态”；名称解析/类型推断/语义收敛等由 AST Checker 处理。
 *   - 若本规则包含多个分支，建议在 AST 层以 alt label（#...）作为具体节点类型划分依据。
 *
 * 典型写法：
 *   return value;
 */
//graph::returnStatement #@rule 说明：语句节点：returnStatement（支持多值 return：expr (',' expr)*）。
returnStatement
    : Return exprList? ';'                    // #ReturnStatement
    ;

/*
 * 规则：throwStatement
 * 所属分块：12 语句与控制流
 * 用途：语句节点：throwStatement。
 * 说明：
 *   - 该规则只描述“可解析的语法形态”；名称解析/类型推断/语义收敛等由 AST Checker 处理。
 *   - 若本规则包含多个分支，建议在 AST 层以 alt label（#...）作为具体节点类型划分依据。
 *
 * 典型写法：
 *   throw err;
 */
//graph::throwStatement #@rule 说明：语句节点：throwStatement。
throwStatement
    : Throw expression ';'                       //#ThrowStatement
    ;

/*
 * 规则：lockStatement
 * 所属分块：12 语句与控制流
 * 用途：语句节点：lockStatement。
 * 说明：
 *   - 该规则只描述“可解析的语法形态”；名称解析/类型推断/语义收敛等由 AST Checker 处理。
 *   - 若本规则包含多个分支，建议在 AST 层以 alt label（#...）作为具体节点类型划分依据。
 *
 * 典型写法：
 *   lock (mu) { critical(); }
 */
//graph::lockStatement #@rule 说明：语句节点：lockStatement。
lockStatement
    : Lock '(' expression ')' block
    ;

/*
 * 规则：tryStatement
 * 所属分块：12 语句与控制流
 * 用途：语句节点：tryStatement（catch 绑定支持可选类型：catch(e) 或 catch(e: Type)）。
 * 说明：
 *   - 该规则只描述“可解析的语法形态”；名称解析/类型推断/语义收敛等由 AST Checker 处理。
 *   - 若本规则包含多个分支，建议在 AST 层以 alt label（#...）作为具体节点类型划分依据。
 *
 * 典型写法：
 *   try { f(); } catch(e) { log(e); }
 */
//graph::tryStatement #@keep #@core 说明：语句节点：tryStatement（catch 绑定支持可选类型：catch(e) 或 catch(e: Type)）。
tryStatement
    : Try block catchClause+                                                  #TryCatches
    | Try block catchClause* finallyClause                                    #TryFinally
    | Try '(' resource (';' resource)* ';'? ')' block
          catchClause*
          finallyClause?                                                      #TryWithResources
    ;

/*
 * 规则：catchClause
 * 类型：语法节点
 *
 * 典型写法：
 *   // catchClause 的一种典型写法（见该规则 RHS）
 *
 * 备注：
 *   - 本规则只描述可解析的语法形态；语义/名称解析/类型推断/合法性收敛在 AST Checker 中完成。
 */

//graph::catchClause #@inline_ok #@rule 说明：try 语句辅助节点：catchClause。
catchClause
    : Catch '(' mods=modifier* bind=binding ')' body=block
    ;

/*
 * 规则：finallyClause
 * 类型：语法节点
 * 作用：匹配语法形态：Finally block
 *
 * 典型写法：
 *   // finallyClause 的一种典型写法（见该规则 RHS）
 *
 * 备注：
 *   - 本规则只描述可解析的语法形态；语义/名称解析/类型推断/合法性收敛在 AST Checker 中完成。
 */

//graph::finallyClause #@inline_ok #@rule 说明：try 语句辅助节点：finallyClause。
finallyClause
    : Finally block
    ;


/*
 * 规则：resource
 * 所属分块：12 语句与控制流
 * 用途：语法节点：resource。
 * 说明：
 *   - 该规则只描述“可解析的语法形态”；名称解析/类型推断/语义收敛等由 AST Checker 处理。
 *   - 若本规则包含多个分支，建议在 AST 层以 alt label（#...）作为具体节点类型划分依据。
 *
 * 典型写法：
 *   var r: File = open("a.txt")
 */
//graph::resource #@rule 说明：语法节点：resource。
resource
    : mods=modifier* kind=(Var | Const)? bind=binding '=' init=expression #ResourceDecl
    ;


/*
 * 规则：goTarget
 * 所属分块：12 语句与控制流
 * 用途：语法节点：goTarget。
 * 说明：
 *   - 该规则只描述“可解析的语法形态”；名称解析/类型推断/语义收敛等由 AST Checker 处理。
 *   - 若本规则包含多个分支，建议在 AST 层以 alt label（#...）作为具体节点类型划分依据。
 *
 * 典型写法：
 *   go foo();
 */
//graph::goTarget #@rule 说明：语法节点：goTarget。
goTarget
    : primary                                        #GoTargetPrimary
    | lambdaExpression                               #GoTargetLambda
    | block                                          #GoTargetBlock
    | '(' expression ')'                             #GoTargetParen
    ;

/*
 * 规则：forControl
 * 所属分块：12 语句与控制流
 * 用途：语法节点：forControl。
 * 说明：
 *   - 该规则只描述“可解析的语法形态”；名称解析/类型推断/语义收敛等由 AST Checker 处理。
 *   - 若本规则包含多个分支，建议在 AST 层以 alt label（#...）作为具体节点类型划分依据。
 *
 * 典型写法：
 *   // forControl 的一种典型写法（见该规则 RHS）
 */
//graph::forControl #@rule 说明：语法节点：forControl。
forControl
    : forInControl
    | forClassicControl
    ;

/*
 * 规则：forInControl
 * 所属分块：12 语句与控制流
 * 用途：语法节点：forInControl。
 * 说明：
 *   - 该规则只描述“可解析的语法形态”；名称解析/类型推断/语义收敛等由 AST Checker 处理。
 *   - 若本规则包含多个分支，建议在 AST 层以 alt label（#...）作为具体节点类型划分依据。
 *
 * 典型写法：
 *   for (x in xs) { print(x); }
 */
//graph::forInControl #@rule 说明：语法节点：forInControl。
forInControl
    : forInBinding In expression
    ;

/*
 * 规则：forInBinding
 * 所属分块：12 语句与控制流
 * 用途：语法节点：forInBinding。
 * 说明：
 *   - 该规则只描述“可解析的语法形态”；名称解析/类型推断/语义收敛等由 AST Checker 处理。
 *   - 若本规则包含多个分支，建议在 AST 层以 alt label（#...）作为具体节点类型划分依据。
 *
 * 典型写法：
 *   x: int
 */
//graph::forInBinding #@rule 说明：语法节点：forInBinding。
forInBinding
    : forInDeclBinding
    | ( bindingPatternNoType (',' bindingPatternNoType)* ','? )
    ;

/*
 * 规则：forInDeclBinding
 * 所属分块：12 语句与控制流
 * 用途：语法节点：forInDeclBinding。
 * 说明：
 *   - 该规则只描述“可解析的语法形态”；名称解析/类型推断/语义收敛等由 AST Checker 处理。
 *   - 若本规则包含多个分支，建议在 AST 层以 alt label（#...）作为具体节点类型划分依据。
 *
 * 典型写法：
 *   x: int
 */
//graph::forInDeclBinding #@rule 说明：语法节点：forInDeclBinding。
forInDeclBinding
    : modifier* (Var | Const) ( bindingPattern (',' bindingPattern)* ','? )
    ;
