# M0 · 属性图 Schema 契约（冻结口径）

> 唯一写入者：`rag-schema-architect`（M0）。过 `rag-eval-gate`+`rag-scope-auditor` 双门后冻结；下游只读。
> 锚点：`design-plan.md` §3.1/§3.2/§3.3、§2.2、§2.3、§1.4、§5.1；`design-plan-revision.md` R-1/R-4/R-6/R-9/R-10。
> 输入边界：仅 `er-model/*` + `test_erp.sql` + `Agents.md`/`skills`（`Agents.md` §0）。**不引用、不对齐 `dam-app`/`dam_meta`/`PLAN.md`**（含其 `asset_urn` 等外部身份模型；`table_id` 为本方案**自设**，见 §身份 [待确认]）。

---

## 0. 建模前提（实测锚定，非约定）

| 前提 | 实测值 | 取证 | 落点 |
|---|---|---|---|
| 全库显式外键 | **0**（`FOREIGN KEY` 0、`ADD CONSTRAINT` 0） | `grep -ciE 'FOREIGN KEY' test_erp.sql`=0、`grep -ciE 'ADD CONSTRAINT' test_erp.sql`=0 | 所有关系边 `is_inferred=true`；最高证据只到 `comment_explicit` |
| DDL 表数 | **1322** | `grep -ic 'CREATE TABLE' test_erp.sql` | 数量等式（见 eval-baseline） |
| 无 PK 表 | **11**（`PRIMARY KEY` 声明 1311 → 1322−1311） | `grep -ciE 'PRIMARY KEY' test_erp.sql`=1311 | `Table.has_pk=false`，对齐 `00` §一/§8.3 |
| 主语料 | A 级 **349**（jf 332 + OT 17） | `00` §四/§五 | 完整入图、可遍历 |

> 关系为**逆向推断、非物理外键**：每条 `RELATES_TO`/`REFERENCES` 边及任何回答文本，必须显式声明此点（承 §5、C-6）。

---

## 1. 节点标签（Node Labels）

采用**属性图（Property Graph）**。MVP 落地形态为「内存/JSON 属性图 + SQLite FTS5 索引」（`RUNBOOK` C-2；不预设重型图库，见 `stack-options.md`）。

### 1.1 Domain（业务域 / 语义分区）
```
Domain {
  domain_id   : "D01".."D18" | "OT" | "B-FAMILY"   // 权威键；以 Dxx 编号为准（见 file-domain-map.md）
  name        : string                              // 展示名（00§四 与文件名文字有差，键不受影响）
  table_count : int                                 // = 00§四 声明值（实测=枚举数，见校验）
  tier        : "A"                                 // D01–D18/OT 均属 A 级
  kind        : "business-domain" | "leftover-ot" | "family"   // OT/族为特殊社区
}
```
- 天然对应社区检测分区（§3.3-3）；B 级按族、OT 单列特殊社区。

### 1.2 Table（表 / 实体）
```
Table {
  table_id        : string   // 本方案自设稳定唯一身份 [待确认]（R-1：非 asset_urn，不外部对齐）
  name            : string   // 原样物理名，含固化拼写（如 catregory / datat_dict）
  domain          : ref → Domain.domain_id
  tier            : "A" | "B" | "C"
  prefix_family   : "jf" | "lcap" | "N{hex}" | "P{hex}" | "none" | "bak" | "test"
  pk_columns      : [string]  // 可空数组
  has_pk          : bool      // 11 张 jf_*_import/jf_contract_termination/jf_inventory_organization = false
  column_count    : int       // 与 test_erp.sql DDL 交叉校验（§3.3-2）
  expanded        : bool      // A 级 true；B 级代表 true / 归约成员 false；C 级影子 false
  representative_of : ref?     // B 级归约成员指向族代表（影子节点）
  family          : string?   // B 级：quartz | activiti | lcap
  member_count    : int?      // B 级族规模
  suspected_legacy: bool      // OT 内 entity1/entity12345/seq_10/sheet1/temp = true（§2.2）
  note            : string?
}
```
- **影子节点**（§3.1 注）：B 级非展开成员、C 级快照/测试表均 `expanded=false`；C 级 `tier="C"`，另带 `source_table`（经 `DERIVED_FROM`）。默认检索排除、可 `include_c=true` 显式开关（§2.2）。

### 1.3 Column（字段）
```
Column {
  column_id  : string        // = table_id + "." + name（自设）
  table_id   : ref → Table
  name       : string
  data_type  : string        // bigint / varchar(n) / decimal(p,s) …（结构事实以 DDL 为准）
  nullable   : bool          // 逻辑模型「可空」列 N=NOT NULL → nullable=false
  default    : any | null
  key_role   : "PK" | "UK" | "FK" | "none"   // 键列线索；FK 目标经关系边表达，非物理外键
  semantic   : string        // 字段含义（DDL COMMENT / 03 含义列）
  charset    : string?       // 按需（DDL 校验）
  collation  : string?
}
```
- **合并行还原**（§3.3-2 / R7）：逻辑模型对「审计四件套」等做合并行（如 `03/D01` L82/L156/L170），入图**必须拆回原子列**，否则 `column_count` 与 DDL 不符。

### 1.4 Concept（业务概念 / 结构语义）
```
Concept {
  term     : string          // 如 "贸易商"、"销售订单"
  category : "master" | "transactional" | "config"   // 来自 05 第二节核心实体三分类
  layer    : "structural"    // ★ 固定为结构语义；指标/术语(KPI)语义层不在本期（R-10）
}
```
- 边界：`Concept` **仅**承载结构语义（域=分区 / 三分类=实体类型 / 字段含义 / D14 枚举）。**不得**建模 KPI、计算口径、正式业务术语表——`er-model`/DDL 中不存在、需外部 BI 源，超本期范围（§1.4/R-10）。

### 1.5 Issue（模型质量问题）
```
Issue {
  issue_id : "A-1".."G-4"     // 05 第三节列表项编号
  category : "A".."G"
  title    : string
  severity : string?          // 05 若未标 → [待确认]
  scope    : [ref]            // 关联 Table/Column（若可定位）
}
```
- **入图 Issue 节点数 = `05` 实测枚举 27**（A3+B4+C4+D5+E3+F4+G4=27，`grep -cE '^[[:space:]]*-[[:space:]]+\*\*[A-G]-[0-9]+' 05`）。
- **口径差登记**：`00` §七第 5 批与 `Agents.md` §4.3 均标 **30 条**，实测 **27**。契约以实测 27 为准，禁止解析器把 27/30 差静默吞掉（见 `relation-symbol-census.md` §冲突、`eval-baseline.md` 数量等式）。

### 1.6 EvidenceSrc（证据来源 · 稳定语义锚）
```
EvidenceSrc {
  file       : string   // 主出处：er-model/<相对路径>（一等锚，非 test_erp.sql）
  section    : string?  // 章节/小节标题 或 "### D01"
  table      : string?  // 出处所指向的表名（逻辑模型/ER 上下文）
  column     : string?  // 指向的列（字段级证据）
  quote_hash : string   // 关系/证据原文片段的规范化哈希（稳定身份，抗行号漂移）
  line_hint  : int?     // 尽力字段（best-effort），非稳定，见下方口径差
}
```
- **行号口径差·定因（审计 P1-2 修正，前版「CRLF/编码致计数差」误判撤回）**：`wc -l test_erp.sql` 全量 = **28,339**；`00` §一记 **26,998** = **非空行数**（实测 `grep -cvE '^$' test_erp.sql` = 26,998）；差 = **1,341 条空行**（28,339−26,998，实测 `grep -cE '^$'` = 1,341）；文件编码 **UTF-8、行尾 LF、CR 计数 = 0**（实测 `grep -c $'\r'`=0，`file` 判 UTF-8 text）——**与 CRLF/编码无关**，纯为「全量行数 vs 非空行数」两口径。→ 建议向 `Agents.md` §9.2 / `00` §一回写此定因（M0 只读边界，不代改）。故 `line_hint` **降为尽力字段**，稳定锚以 `{file, section, quote_hash}` 为准；跨版本定位**不得**依赖行号唯一性。
- 独立成节点的目的（§3.3-4）：使「答案可回溯到文件/节/引用片段」成为图上可查询关系（经 `SUPPORTED_BY`）。

---

## 2. 边类型（Relationships）

> 通则：**所有关系边 `is_inferred=true`**（全库 0 FK）；证据级枚举与置信度区间见 `evidence-confidence-map.md`；`evidence_level` 最高只到 `comment_explicit`。

```
(table)-[:BELONGS_TO_DOMAIN {is_inferred:false}]->(domain)     // 域归属为 00§四 权威登记，非推断
(column)-[:IS_COLUMN_OF     {is_inferred:false}]->(table)      // 结构事实，DDL 可交叉校验
(table)-[:RELATES_TO {
    cardinality    : "1:1" | "1:N" | "N:1" | "N:M",           // 取自关系线描述文本（见 §2.1）
    direction      : "parent" | "child" | "self",             // 自关联 direction:"self"
    via_column     : string,                                  // 如 sales_order_id / exchange_order_no
    evidence_level : comment_explicit|index_backed|name_inferred|semantic_inferred|unconfirmed,
    confidence     : float 0.0–1.0,
    is_inferred    : true,
    cross_domain   : bool,                                    // 跨域桩引用置 true
    polymorphic    : bool?,                                   // 多态外键
    discriminant   : string?,                                 // 判别列，如 order_type
    evidence_tags  : [string]?,                               // 复合证据，如 ["name","index"]
    external_reference : bool?,                               // 指向外部系统 → 不建本库边
    source_ref     : → EvidenceSrc
}]->(table)                                                     // ★核心边：关系+基数+证据+置信 四要素
(column)-[:REFERENCES {evidence_level, confidence, is_inferred:true}]->(column)  // 字段级引用/结构级血缘（M2）
(concept)-[:REALIZED_BY {is_inferred:true}]->(table)            // 概念→实现表（M4 种子+可回溯）
(table)-[:HAS_ISSUE]->(issue)                                  // scope 关联
(<any>)-[:SUPPORTED_BY]->(evidenceSrc)                         // 每条边/断言可回溯
(table)-[:DERIVED_FROM {                                          // C 级 _bak_/test_ → 源表；copy1 → 主表
    is_inferred        : bool,   // ★ 115 条命名派生 = **true**（审计 P2-2 修正，前版统标 false 撤回）；仅显式登记 5 条 = false
    naming_rule_derived: bool,   // true = 由 `_bak_<14位时戳>`/`_{6hex}` 命名规则剥离派生；显式 5 条 = false
    evidence_level     : "comment_explicit",  // 04 文档登记＋命名约定；无 FK，上限 comment_explicit
    source_ref         : → EvidenceSrc        // 04 §3.1/§二（显式 5）或 §3.2/§3.3 点名清单（派生 115）
}]->(table)
(table)-[:SAME_FAMILY_AS {family, member_count}]->(table)       // B 级同构族关系（00§8.1 前缀分布）
```

### 2.1 `RELATES_TO` 语义细则（本 M0 冻结）

- **四要素必载**：`cardinality` + `evidence_level` + `confidence` + `source_ref`，缺一即契约违例。
- **基数取自描述文本、非连接符**（实测）：`01` 关系线 `"…: \"1:1 …\""` 文本标 `1:1` 出现 **19** 次，而连接符 `||--||` 实测 **0** 次；`N:1` 128、`1:N` 232、`N:M` 3。→ 解析器 **cardinality 必须从 `"<基数> …"` 描述抽取**，不得由连接符字形反推（见 `relation-symbol-census.md`）。
- **证据以 `[证据]` 标签为准**（§4.1 注）：实线/虚线不严格对应证据强度（`01/D01` 实线仍见 `[语义推断]`）。`evidence_level` 由描述文本标签映射（`evidence-confidence-map.md`）。
- **自关联**：`jf_sales_order ||--o{ jf_sales_order : "换货自关联 … exchange_order_no -> order_no"`（`01/D01` L176）→ `direction:"self"`，两端同一 `table_id`。
- **多态外键**：`flow_change_record.related_order_id` 按 `order_type` 指向多表（`05` A-3）→ 多候选边共享 `unconfirmed` 级、置 `polymorphic=true, discriminant:"order_type"`，全部进待确认队列（M2）。
- **跨域桩**：带 `[跨域·Dxx]` 的引用（如 D01 中 `jf_customer`）**不重复建节点**，边指向定义域规范节点，`cross_domain=true`（§2.2）。
- **复合证据**：`[命名推断+索引]`（`01` 实测该复合标签 91 次）→ 取较高档（`index_backed`）并记 `evidence_tags=["name","index"]`。
- **外部引用**：`[注释明示→外部系统]`（如 D15 `crm_complaint_code`，`00` 复核批①）→ `external_reference=true` 且**不建本库边**（避免虚假关系）。

### 2.2 `DERIVED_FROM` 源表映射细则（本 M0 冻结 · 审计 P2-2 修订）

C 级 120 张 → 源表边分**两档取证**（`04-C级备份与测试表清单.md` 实测回读）：

| 档 | 数量 | 出处（精确到节） | is_inferred | naming_rule_derived | evidence_level |
|---|---:|---|---|---|---|
| **显式登记** | **5** | `04` **§3.1**「源表」列 2 行（`jf_application_nature_bak_20251122110927`→`jf_application_nature`(D14)、`jf_judgment_conclusion_bak_…`→`jf_judgment_conclusion`(D15)）＋ `04` **§二**测试表 3 张（排除理由列点名源表：`test_jf_sales_order`/`_cus`/`_detail`） | **false**（文档表格直接登记，非推断） | false | `comment_explicit` |
| **命名规则派生** | **115** | `04` §3.2（lcap 51 张）＋§3.3（无前缀 64 张）仅点名表名（§3.3 带 `→(Dxx)` **域指针**但**无源表名映射列**，实测表头 `| # | 表名（源表→域） |`）；源表 = 剥离 `_bak_<14位时戳>` 与 `_{6hex}`（应用副本段）派生 | **true** | **true** | `comment_explicit`（04 登记＋命名约定；无 FK 不上探） |

- **措辞修正**：前版本文件注释「04§三映射，非推断」**不精确**——`04` §三仅 §3.1 有「源表」映射列（2 行），§3.2/§3.3 为点名清单；显式映射限定为 **§3.1/§二**（共 5 条），其余 115 条为命名派生 → `is_inferred=true`。
- **`copy1 → 主表` 边**（A 级保留 2 张：`jf_sales_order_copy1`、`jf_statement_fee_category_copy1`，`04` §一-4/§六 点名）：同理属 `_copy1` 命名规则派生 → `is_inferred=true, naming_rule_derived=true, evidence_level=comment_explicit`。
- 派生目标源表须存在性校验（M1 装载断言：剥名后命中 A 级/基线表；未命中 → 悬挂队列，不得造边）。

---

## 3. A/B/C 分级在图中的处理（承 §2.2/R-4）

| 级 | 实测表数 | 建节点策略 | 默认检索 |
|---|---|---|---|
| A | 349（jf 332 + OT 17） | 表+字段+关系+域+证据 **完整入图**（`expanded=true`） | **参与**遍历/血缘/影响 |
| B | 853（lcap 450 + Quartz 275 + Activiti 128） | 每结构族 1 代表展开 + `SAME_FAMILY_AS`/`member_count`；成员 `expanded=false` 影子 | 降权 / 可开关（默认不逐一建字段节点） |
| C | 120（_bak_ 117 + test_ 3） | 影子节点（表名 + `tier=C` + `source_table` + `DERIVED_FROM` + 排除理由） | 默认排除，`include_c=true` 才入 |

- **前缀归级铁律**（`RUNBOOK` M1 §2 / 风险 N-2）：先按 C 级规则（`_bak_<时戳>`、`test_` 前缀）剔除，再按前缀归 A/B。若把 2 张 `jf_*_bak_` 或 51 张 `lcap_*_bak_` 计入 A/B → A 级虚增为 351，判 FAIL。

---

## 4. 身份、去重与边界守恒（M0 决策）

1. **`table_id` 自设稳定唯一**（§3.3-1）：用于跨版本 diff 与去重；同名跨域引用汇聚同一节点。是否外部对齐 → **[待确认]**（本方案不与 `dam_meta`/`asset_urn` 对齐，R-1）。
2. **域键 = Dxx 编号**：域名文字差异不影响键（`file-domain-map.md`）。
3. **边界硬约束**（不得越位，违者 P0）：
   - 不含**字段级变换/ETL 数据流**血缘（`sum(order.amount)→invoice.total` 类）——超本期范围（§2.3/R-9）。
   - 不含**指标语义层**（KPI/计算口径/正式术语表）——超本期范围（§1.4/R-10）。
   - 不得把任何推断关系表述为"物理外键/权威约束"（全库 0 FK）。
4. **有出处率 100%**：每条 `RELATES_TO`/`REFERENCES` 边必须挂 `SUPPORTED_BY → EvidenceSrc`（§1.5，M1/M2 校验）。

---

## 5. 与检索/血缘层的接口承诺（下游只读消费）

- **机读导出**（§7.1/R-4，前置核心交付）：本 schema 的可序列化投影为稳定 **JSON/YAML** 结构，落 `graphrag/out/meta/`；MVP 物理形态 = 内存/JSON 属性图 + SQLite FTS5（`stack-options.md`），不绑定特定图库。
- `evidence_level`/`confidence` 语义与默认过滤阈值（`confidence ≥ 0.45`、`unconfirmed` 默认隐藏）见 `evidence-confidence-map.md`；多跳默认 ≤3 `[待确认]`。
- 复合证据、多态、外部引用、跨域桩的字段语义（本文件 §2.1）为 M1 解析器与 M2 血缘器的**契约级依据**，不得各自另立。

---

### 附：本文件实测出处一览（可回溯）
- 0 FK / 1322 / 1311 / 28,339：`test_erp.sql`（grep/wc，本文 §0）；行数定因：非空行 26,998（`grep -cvE '^$'`）＋空行 1,341＝28,339，CR=0/UTF-8/LF（本文 §1.6，审计 P1-2）。
- 节点/边形态：`design-plan.md` §3.1–§3.3；修订 `R-1/R-4/R-6/R-9/R-10`。
- 基数文本 vs 连接符、复合标签计数：`er-model/01-ER图/*`（grep，见 `relation-symbol-census.md` / `evidence-confidence-map.md`）；关系线全量 **456/12 种**（左基数可选正则，6 字符合规子口径 443/10）及 13 条 malformed 行锚点：census §2/§2.1/§4 C-ζ。
- `DERIVED_FROM` 5 显式/115 派生：`er-model/04-C级备份与测试表清单.md` §二/§3.1/§3.2/§3.3/§一-4/§六 回读（本文 §2.2，审计 P2-2）。
- Issue 27：`er-model/05-跨域核心关系总览.md` §三（grep）。
- 自关联样例：`01/D01-销售订单域.md` L176；跨域桩：L179 `[跨域D09]`。
