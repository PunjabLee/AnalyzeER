# agents/RUNBOOK.md — GraphRAG 落地里程碑台账（编排者交付）

> 编排对象：`er-model/graphrag/design-plan.md`（**v2**）+ `er-model/graphrag/design-plan-revision.md`（R-0…R-10）
> 分支：`chore/rag`，基点 `4a5485a`（已提交 subagent 定义至 `c05203a`）；不合并 `master`
> 本文件由 `rag-orchestrator` 独占写入。口径纪律：**验收门全部引用 design-plan v2 既有量化条款**；方案标 `[待确认]` 的数值原样保留，不新设阈值。
> 台账内所有计数/符号均为**编排者对源文件的 grep 实测值**（命令见 §A），非照抄约定值。

---

## 0. 编排总则

1. **串行委派**：`M0 → ⟦gate+audit⟧ → M1 → ⟦gate+audit⟧ → M2 → ⟦gate+audit⟧ → M3 → ⟦gate+audit⟧ → M4 → ⟦gate+audit⟧`。
   MVP 主干为 M0–M2（design-plan §8.1「MVP 建议」：确定性检索 + 引用级血缘先交付），M3 为分析增量，M4 后置。
2. **编排不执行**：本 agent 不写业务/解析/图谱代码，不自行触发子代理运行时；任务卡交主线程逐一委派对应执行专家。
3. **每里程碑两道复核（横切）**：`rag-eval-gate`（数量等式/黄金集/有出处率/多跳预算/社区对照/边界守恒）出具 `graphrag/reports/eval-M{n}.md`；`rag-scope-auditor`（只读）出具 `graphrag/reports/audit-M{n}.md`。
   **过门定义**：eval-gate 全项 PASS **且** scope-auditor 无 P0。任一 FAIL = 不过门 → 按该卡「回退规则」处置，不得进入下一里程碑。
4. **契约冻结**：`graphrag/spec/` 过门即冻结；下游若发现契约缺口/冲突，**不得自改上游文件**，向编排者提出 → 回退 M0 出 amendment → 重过 eval-gate。
5. **单向摄取**：`er-model/*` 与 `test_erp.sql` 是输入资产，本轮只读、不反向改写（design-plan §7.1）。
6. **超本期范围（遇此一律标注"超本期范围"，不得承诺）**：
   - ❌ 字段级**变换/ETL 数据流**血缘（`sum(order.amount)→invoice.total` 类计算链）——§2.3 / R-9。
   - ❌ **指标语义层**（KPI/计算口径/正式业务术语表）——§1.4 / R-10，`[待确认]`。
   - ❌ 一切把推断关系表述为"物理外键/权威约束"的产出：全库 **0 条 FOREIGN KEY/CONSTRAINT**（编排者实测 `grep -ciE 'FOREIGN KEY' test_erp.sql = 0`，`ADD CONSTRAINT = 0`；§1 关键结论、§5.1）。

### 0.1 状态一览

| ID | 里程碑 | 执行专家 | 状态 | 过门凭证 |
|---|---|---|---|---|
| T-00 | 前置编排：任务分解与本台账 | rag-orchestrator | **完成（本次提交）** | 本文件 |
| M0 | 范围与 Schema 契约定稿 | rag-schema-architect | ✅ **已过门并提交**（P1 修复轮**未触及 M0 产物**：`graphrag/spec/` 零改动、契约仍冻结） | `reports/eval-M0.md`（PASS，`a917ef8`）+ `reports/audit-M0.md`（无 P0）+ 交付 `7074698` |
| M1 | L0 图装载 + L1 确定性检索 | rag-knowledge-loader | ✅ **已过门并提交**（**含 P1 修复轮已过门提交**） | `reports/eval-M1.md`（PASS，`888e7c6`）+ `reports/audit-M1.md`（无 P0）+ P1 修复轮 `reports/eval-P1fix.md`（PASS，`18d383a`）；交付＝收尾提交 + `fix(rag): CodeReview P1 修复…` |
| M2 | 字段级·引用/结构级血缘 | rag-lineage-builder | ✅ **已过门并提交**（**含 P1 修复轮已过门提交**） | `reports/eval-M2.md`（PASS，`625e6a3`）+ `reports/audit-M2.md`（无 P0/P1，6 项 P2）+ P1 修复轮 `reports/eval-P1fix.md`（PASS，`18d383a`，4 P1 全闭合）；交付重生成 **499 边 / 110 队列 / 默认可见 440** |
| M3 | L2 社区/全局分析层 | rag-community-analyst | ✅ **已过门并提交**（首轮即过门，无 P1 返工） | `reports/eval-M3.md`（PASS，`0532561`）+ 交付 `graphrag/community/` 与 `graphrag/data/meta/community_*`（17 社区 / NMI 0.59049 / kept 821→477 表对 / 排除 161 / 孤立 A 表 75 / 75 passed）；遗留 3 项 P2 移交 M4（见 M3 卡） |
| M4 | 结构语义层 + 可选 NL 前端 | rag-semantic-nl-frontend | 🟡 **就绪·待委派**（上游 M3 已过门提交；M3 遗留 P2 须承接，见 M3/M4 卡） | — |
| G·A | 横切门禁 + 范围审计 | rag-eval-gate / rag-scope-auditor | 就绪（随里程碑触发） | `graphrag/reports/` |

---

## 1. 实施约定（主线程已定；全体执行专家必须遵守，避免栈不一致）

| # | 约定 | 说明与实测注记 |
|---|---|---|
| C-1 | 语言/运行时 = **Python 3**，macOS 本地可跑 | 编排者实测本机 `Python 3.14.6`；无构建系统，仓库为纯文档工程（`Agents.md` §1/§6） |
| C-2 | MVP 栈：确定性核心 | `test_erp.sql` / `er-model/*` 解析 → **内存/JSON 属性图 + SQLite FTS5**（BM25/全文）；社区检测用 `python-louvain`（Louvain）或 `networkx` `greedy_modularity_communities` |
| C-2a | ├ FTS5 可用性 | 编排者实测：Python 内置 `sqlite3` → SQLite **3.50.4**，`CREATE VIRTUAL TABLE … USING fts5` 通过；`porter unicode61` 与 `trigram` tokenizer 均可建表 |
| C-2b | └ 依赖现状 | `networkx 3.7` **已安装**；`python-louvain`（`import community`）**未安装**。M3 开工前须确认依赖安装策略（`pip install python-louvain` 或退化为 greedy_modularity）→ 记为 `[待确认]`，不得默认已具备 |
| C-3 | 向量库 / LLM / 重型图库 = **按需后置、非默认** | 依 design-plan §1.2/§4.3/R-0/R-8：LLM 抽取层在本资产冗余可省；仅在 spec 中留接口 + `[待确认]` 选型候选（§8.3-3），不得写入既定引擎 |
| C-4 | 实现代码目录 = `graphrag/` | 与文档目录 `er-model/graphrag/`、以及被排除的 `dam-app/` 物理隔离 |
| C-5 | 唯一输入 = `er-model/*` + `test_erp.sql` + `Agents.md`/`skills` | 依 `Agents.md` §0.1/§0.2；**严禁**引入/对齐 `dam-app`/`dam_meta`/`PLAN.md`（含其 `asset_urn` 等身份模型）。`table_id` 为本方案自设 `[待确认]`（§3.3-1 / R-1） |
| C-6 | 全库 0 外键 → 所有关系/血缘为**推断** | 每条边 `is_inferred=true` + 五级证据 + 置信度；回答须显式声明"逆向推断、非物理外键"（§2.3/§5.1/§5.2） |
| C-7 | 数量等式（编排者实测复核通过，见 §A） | 图内可遍历主语料 = **A 级 349**（含 OT 17）；登记全量 = **1322** = 349+853+120；入图 Issue = **05 实测 27**；05 核心关系线 = **实测 33**；`||--||`/`||--|{` 在 `01-ER图` **实测 0 出现** |
| C-8 | 分支/提交红线 | 各执行专家仅在其里程碑**过门后**提交自己负责路径到 `chore/rag`；禁合并/推送 `master`、禁 `--force`/`--no-verify`/`reset --hard`、**不自动 push**、无变更不空提交、禁 `git add -A` |
| C-9 | 临时产物 | `Agents.md` §7.5 的 `_*` gitignore 口径**仅覆盖根目录与 `er-model/`**（`gitignore` 为 `/_*`、`er-model/_*`，非全局）。`graphrag/**/_*` 不会被自动忽略：需新增忽略项者向编排者提出，由编排者单点改 `.gitignore`（见 §4 路径表） |
| C-10 | 派生数据入库策略 | 生成的图快照/SQLite 文件默认**不入库**，以"可确定性重建的脚本 + 校验报告"为交付；如需入库快照以便评审，由 M0 在 `spec/` 中定阈值 → `[待确认]`（编排者提出，勿由执行专家自行决定） |

---

## 2. 里程碑任务卡

### M0 · 范围与 Schema 契约定稿 — `rag-schema-architect`

- **目标与范围**：冻结输入与图契约，交付下游共享的"数据契约 + 归一映射 + 实测勘察表 + 评测构造规范 + 选型候选"。锚点：design-plan §0、§1.2、§2.1、§2.3、§3.1–§3.3、§4.1、§5.1、§7.1、§8.1(M0 行)、§8.3；修订稿 R-0/R-3a/R-3b/R-4/R-5/R-6/R-8/R-10。
- **上游依赖**：T-00（本台账）；无前置里程碑。
- **输入资产**：`er-model/00`、`er-model/01-ER图/*`（20 篇）、`er-model/03-逻辑数据模型/*`（20 篇）、`er-model/04`、`er-model/05`、`test_erp.sql`、`Agents.md`、`skills/mysql-ddl-data-modeling/`。
- **产出物路径**（`graphrag/spec/`，7 份）：`schema.md`、`header-normalization.md`、`relation-symbol-census.md`、`file-domain-map.md`、`evidence-confidence-map.md`、`eval-baseline.md`、`stack-options.md`。
- **验收门（逐条可测）**：
  1. **表头归一映射**覆盖 `03` 全部 20 文件的实测变体，且枚举数=grep 实测。编排者预检：`03` 目录实测 **10 种**表头签名，最高三种为 `|字段|类型|可空|默认|键|含义|`×103、`|字段名|类型|可空|默认值|键|含义/关联|`×60、`|字段|类型|可空|默认|主/外键线索|含义|关联|`×59；含 6 列/7 列/无"默认"列等形态（§4.1「≥5 种变体」的实测细化，R-3b）。归一目标列：字段/类型/可空/默认/键-线索/含义/关联（"关联"可内联于键列 `FK[目标·依据]`）。
  2. **关系符变体勘察表**与实测一致并**显式列出 0 出现符号**。编排者实测 `01-ER图/*` 出现次数：`||--o{` 321、`||..o{` 92、`||--o|` 11、`||..o|` 5、`|o--o{` 3、`}o--o|` 3、`}o--o{` 2、`}o..o{` 1、`}o..o|` 1；**`||--||` = 0、`||--|{` = 0**（§4.1）。⚠ 冲突项须在勘察表内裁决并留痕：`Agents.md` §4.1 文字描述"`1:1` 用 `||--||`、强制多行 `||--|{`"与实测 0 出现不一致 → **解析正则一律以实测勘察表为准，不得依 `Agents.md` 约定写法**。
  3. **文件名↔域号映射表**条目 = 实测文件数（`01-ER图` 20、`03-逻辑数据模型` 20；键为 D01–D18 + OT + B级），且与 `00` 第四节逐域表数一致（18 域小计 = 332：18+21+19+33+13+15+23+27+31+24+21+11+16+34+6+10+6+4 = 332 ✓，另 OT 17）。
  4. **五级证据→`evidence_level`→置信度区间**映射齐备（§5.1 五级 + §5.2 默认阈值 `confidence ≥ 0.45` + `unconfirmed` 默认过滤开关）；区间数值保留 `[待确认]`（§5.1 标注为初设，§8.3-1）。
  5. **schema**：6 类节点（Domain/Table/Column/Concept/Issue/EvidenceSrc）+ §3.2 全部边类型；`RELATES_TO` 四要素（cardinality/evidence_level/confidence/source_ref）+ 自关联 `direction=self` + 多态 `polymorphic/discriminant`；`Concept` 标 `layer:"structural"`；所有边 `is_inferred=true`；复合证据 `[命名推断+索引]` 取高并记 `evidence_tags`、`external_reference=true` 不建本库边（§5.1）。
  6. **EvidenceSrc** 稳定语义锚 `{file, section, table, column, quote_hash, line_hint?}`；行号标"尽力字段"并注明口径差实测：`00` 记 **26,998** 行 vs `wc -l` **28,339** 行（R-6，`Agents.md` §9.2）。
  7. **黄金集构造规范**覆盖六类查询（UC1–UC6，§1.4）+ **确定性基线测法**（R-5：先测"关键词 + 图遍历 + 社区摘要"命中率，再决定是否引入向量/LLM）；命中率目标 `Top-3 ≥ 90%` 与 P95 时延阈值原样标 `[待确认]`（§1.5）。
  8. **选型候选**：图库/向量库/LLM 仅出对比与结论 `[待确认]`，未选定引擎不得写成事实（§8.3-3；C-3）。
  9. 分层表述与 §1.2/R-0/R-8 一致：**GraphRAG 抽取层冗余可省，社区/全局层为真实增量**；`stack-options.md` 与 `schema.md` 的 MVP 落地形态须与 C-2（JSON 属性图 + SQLite FTS5）不冲突。
  10. `rag-scope-auditor`：`spec/` 全文无 `dam-app`/`dam_meta`/`PLAN.md` 依据（grep 取证）；无指标层/ETL 血缘承诺。
- **回退规则**：任一实测数字与源文件不符或存在"照抄约定未回读"→ 退 M0 重取证（§5.1 审计维度 3）；契约缺项/自相矛盾 → 不得进入 M1；勘察表与实物冲突未裁决 → 阻断（承 §4.4"任一断言失败 → 阻断入库并报告，不交付"精神）。
- **状态**：✅ **已过门并提交** — `eval-M0.md`（PASS，`a917ef8`）+ `audit-M0.md`（无 P0：P1×2、P2×5，编排者代提）+ 交付 `7074698`；`graphrag/spec/` 已冻结。
  > ⚠ **口径更新（后置实测；门文本按历史记录保真原则不改写）**：本卡门 1/2 内嵌的编排者**预检值**「03 表头 10 种」「关系符 9 类/439」已被实物证伪并 superseded。数量真源＝`spec/relation-symbol-census.md`（ξ＝**456 条/12 种字形**；ξ′ 6 字符合规 443/10；malformed 13）与 `spec/header-normalization.md`（字段表头 **11 种/292**；全签名 19 种/309）。下游**不得**据预检值建正则。

### M1 · L0 属性图装载 + L1 确定性检索 — `rag-knowledge-loader`

- **目标与范围**：确定性解析 `er-model/*` + `test_erp.sql` 结构事实，装载 A 级完整入图、B/C 轻量登记，建 BM25/全文 + 邻接表，交付 UC1/UC2/UC3 + 表级推断血缘遍历。锚点：§2.2、§3.1–§3.3、§4.1、§4.4、§5.2、§6(UC1–UC3/UC4 表级)、§7.1、§8.1(M1)；R-3a/R-3b/R-4/R-6。
- **上游依赖**：M0 全部 `spec/`（`schema/header-normalization/relation-symbol-census/file-domain-map/evidence-confidence-map`）+ 过门。
- **输入资产**：同 M0（`skills/.../scripts/` 仅复用普查/归域**思路**；脚本为 PS 5.1/Windows，macOS 需 `pwsh` → `[待确认]`，§4.1 注/R5）。
- **产出物路径**：`graphrag/ingest/`（解析器）、`graphrag/store/`（JSON 属性图 + SQLite FTS5 落地）、`graphrag/search/`（L1 检索与 1 跳/多跳遍历）、`graphrag/data/l0_*`、`graphrag/out/meta/`（**JSON/YAML 机读元数据导出，§7.1/R-4 前置核心交付**）。
- **验收门**：
  1. **数量等式闭合（§4.4，任一不闭合即阻断入库、不交付）**：图内可遍历主语料 = A 级 **349**（含 OT 17）；登记全量 = **1322**（A349 + B853 + C120）；每域表数 = `00` 第四节声明值；入图 **Issue = 27**（按 `### A..G` 列表项实测：A3+B4+C4+D5+E3+F4+G4）；关系边两端存在、**无悬挂边**（目标为 `[待确认]` 者除外）。
  2. **前缀归级不得武断（编排者实测口径）**：DDL `CREATE TABLE` 实测 **1322**；其中 `jf_*` 名串 **334**、`lcap_*` **501**、`N{hex}_/P{hex}_` **403**（=275 Quartz + 128 Activiti）、`test_` **3**、含 `_bak_` **117**（lcap 51 + jf 2 + 无前缀 64，`00` 终验行）。→ 分级须**先按 C 级规则（`_bak_` 时戳 / `test_` 前缀）剔除，再按前缀归 A/B**；若把 2 张 `jf_*_bak_` 或 51 张 `lcap_*_bak_` 计入 A/B 即判 FAIL。
  3. **结构事实以 `test_erp.sql` 为最终仲裁**（§2.1 冲突消解）：逐表列数与 DDL 交叉校验；`03` 审计四件套等**合并行须拆回原子列**（§3.3-2 / R7）；无 PK 表标记与 DDL 一致（实测 `PRIMARY KEY` 声明 1311 → **11 张无 PK**，`00` 第一节）。
  4. **关系解析**：正则取自 M0 `relation-symbol-census.md` 实测变体；证据以描述文本 `[证据]` 标签为准（实线/虚线不对应证据强度，§4.1 注）；跨域桩 `[跨域·Dxx]` 不重复建节点、边指向定义域规范节点（§2.2）。
  5. **L1 检索可用**：UC1 查表 / UC2 查字段（含反向"哪个表有 `sales_order_id`"）/ UC3 查关系（附 `evidence_level`+confidence+来源文件行）；默认仅召回 `confidence ≥ 0.45`（§5.2-1）；多跳默认 ≤3 跳 `[待确认]`（§6）。
  6. **机读导出**：`out/meta/` 稳定 schema 可被 M2/M3/M4 只读消费；答案侧「有出处率」100%（§1.5）——抽查断言每条 `RELATES_TO` 有 `SUPPORTED_BY`。
  7. 黄金集确定性基线首测（`spec/eval-baseline.md` 测法），命中率对齐并记录，阈值保持 `[待确认]`（§1.5）。
- **回退规则**：数量等式不闭合 → 阻断并回退 loader 修复解析（§4.4）；列数与 DDL 不符 → 回退 §3.3-2 拆行逻辑；若发现 `spec/` 契约缺口 → 走 §0.4 回退 M0 amendment 并重过 gate，**不得自改 `graphrag/spec/`**。
- **状态**：✅ **已过门并提交（含 P1 修复轮已过门提交）** — `eval-M1.md`（六门全 PASS，40 项断言全过）+ `audit-M1.md`（**无 P0 → 准予提交**，编排者代提）；交付＝`graphrag/{__init__.py,ingest/,store/,search/}` + 机读元数据 `graphrag/data/meta/{l0_manifest,assertions}.json`。全量产物 `graphrag/data/l0_{graph.json,edges.jsonl,index.db}` 依 C-10 **不入库**（`.gitignore` 已补）。
  > **P1 修复轮（M1 侧，`eval-P1fix.md` PASS）**：P1-3 中文检索 → `store/fts.py` 新增 `nodes_fts_cjk`（`tokenize='trigram'`）+ 双路合并去重 + `<3 字` CJK LIKE 兜底，并把 `except OperationalError: return []` 的**静默吞异常**改为显式 `FTSQueryError`（坏索引/非法 MATCH 真抛错；合法 0 命中仍返回 `[]`）；实测 `"销售订单"`→2 命中、`"库存"`→5 命中。P1-4 UC2 真表名 → `search/l1.py` `tables_with_column` 不再对裸列名 `.split(".")[0]`（旧 bug 恒返回列名），改取 Column.domain（build 时＝table_id），实测 `sales_order_id` semantic=**17 全为真实表名**、非表名条目=[]。P1-1 归级侧 `ingest/{config,relation_parser}.py`：裸 `[命名]`/`[语义]` 正确归级（源文件实测 `[命名]`×6(D11)/`[语义]`×2(D10)），不再静默降 `unconfirmed`。**M1 六锚守恒**：RELATES_TO 483 / A349 / Issue27 / ξ456 / Σ1322 / FK=0 全等。
  > ⚠ **移交技术债**：`rel_dangling(structural)` 系恒真死护栏（`store/graph.py:163` 初始化为 `[]` 后全链路从未填充，`assertions.py:158` / `tests/test_m1.py:117` 断言无信息量）→ M2 起「边无悬挂」须以**逐边端点回查**为准（`eval-M1` §5：9498 边、悬挂 0），不得采信该自断言。

### M2 · 字段级·引用/结构级血缘 — `rag-lineage-builder`

- **目标与范围**：在 M1 图上由 DDL `_id`/`_code`、`INDEX`/`UNIQUE` 佐证、`COMMENT` 明示生成 `REFERENCES` 列→列边 + 反向影响分析 + 待确认队列。**变换/ETL 数据流级血缘：超本期范围**（§2.3 / R-9）。锚点：§2.3、§3.2、§4.1、§5.1–§5.2、§6(UC4/UC5)、§8.1(M2)。
- **上游依赖**：M1 过门（Table/Column/RELATES_TO 已在图内）+ M0 `evidence-confidence-map.md`。
- **输入资产**：`test_erp.sql`（列/索引/注释结构事实）、`er-model/01-ER图/*`、`er-model/03-逻辑数据模型/*`、`er-model/05`（A-3/B-4 高危项）、M1 `out/meta/`。
- **产出物路径**：`graphrag/lineage/`（引用判定与 BFS/DFS 反向可达）、`graphrag/data/meta/lineage_*`（`lineage_edges.jsonl` + `lineage_manifest.json`，与 M1 `data/meta/` 既定口径延续；P2-3 裁决＝改卡不改产物名，原 `references_*` 作废）、`graphrag/out/lineage/`（血缘清单 + 影响面导出，**本地镜像，`.gitignore:18 out/` 不入库**）、`graphrag/data/review_queue.json`（待确认队列，本 agent 唯一持有者）。
- **验收门**：
  1. **引用边两端可解析**：每条 `REFERENCES` 的源列与目标列均存在于图中；悬挂列边 = 0（除目标 `[待确认]` 者，且须在队列内）。
  2. 逐边携带 `evidence_level` + `confidence`，默认过滤 `≥0.45`；全库 0 FK → 导出与回答文本显式声明"血缘为逆向推断，非物理外键；不含变换/ETL 级"（§2.3、C-6）。
  3. **高危推断处置**（§5.2-4 / §8.2 R4 / §8.3-5）：多态外键（`flow_change_record.related_order_id` 按 `order_type`）→ 多候选边共享 `unconfirmed` + `polymorphic=true, discriminant`；同名列异指向（`05` B-4）**不得**武断连到"最近"表 → 强制进队列；`external_reference=true`（D15 `crm_complaint_code` 等→外部系统，`00` 复核批①）不建本库边。
  4. **UC4 影响 / UC5 血缘**：给定表/字段返回下游清单与正向/反向路径（以 `jf_sales_order` 为枢纽样例），深度默认 ≤3 跳 `[待确认]`，超限截断可复现（§6）；按置信度加权剪枝，避免低置信短路连边（§5.2-2）。
  5. 无路径/无节点时答案须为"文档未记载/待确认"，**拒绝臆造兜底**（§6）。
- **回退规则**：悬挂列边或两端不可解析 → 阻断回退 M2 生成逻辑；若产物出现变换/ETL 血缘 → 判边界越位（P0），删除并标注超范围；若需真实数据流血缘 → 停止该需求，回报"须放宽输入到 BI/ETL/作业日志后重估"（§8.3-7）。
- **状态**：✅ **已过门并提交（含 P1 修复轮已过门提交）** — 首轮 `eval-M2.md`（PASS，`625e6a3`）+ `audit-M2.md`（**无 P0/P1 → 准予提交**，编排者代提，6 项 P2）；P1 修复轮 `eval-P1fix.md`（**PASS，`18d383a`**，4 个 P1 全闭合）。交付＝`graphrag/lineage/` + `graphrag/data/meta/lineage_{edges.jsonl,manifest.json}` + `graphrag/data/review_queue.json`。
  **数量真值（编排者 2026-10-05 逐边差分实测；首轮 514/97/446 已 superseded，下游不得回抄）**：`lineage_edges.jsonl` **references_total = 499**（`wc -l` 实测）；**默认可见集 = 440**（`confidence≥0.45` 且 `!has_uncertain`，即 M3 社区输入集）；低置信隐藏 **59**；`has_uncertain`/`unconfirmed` 边 = **0**；**待确认队列 items = 110**（by_kind Σ=110）。锚守恒：RELATES_TO 483 / A349 / Issue27 / ξ456 / Σ1322 / FK=0 全等；`pytest graphrag` = **56 passed**。`l0_*`、`out/lineage/`、`__pycache__/*.pyc`、`.tmp/` 依 C-10 **不入库**。
  > **P1 修复边数变化的实测口径（`(src,dst)` 差分旧版 `ae296d9` ↔ 新版）**：**removed=16 / added=1，净 −15（514→499）**；默认可见集 **−11 / +5 → 446→440**；队列 **97→110（净 +13）**。16 条 removed 的构成与承接（逐条可追溯、**无静默蒸发**）：
  > ① **自环错边 6 条**（旧版 `src_table==dst_table` 共 10 条、仅 4 条带 `explicit_pair` → 违例 6 条全部撤销并入队 `self_loop_annotation`；新版自环 4 条 100% 带 explicit_pair，负向断言 `self_reference_explicit_pair_only` ok=true）。其中 `jf_basic_craft.finish_product_id` 由错指本表 `id` **改指真实 `jf_product.id`**（index_backed 0.775）＝那唯一 1 条 added。
  > ② **存疑边 2 条**（`unconfirmed` conf=0.1：`jf_receivable_claim.payment_method`、`jf_sales_order_wide.sales_type`）→ 入队 `doc_line_uncertain` 不建边。
  > ③ **其余 8 条**（conf 0.325 `semantic_inferred`×3、0.575 `name_inferred`/`explicit_pair`×4、0.775 `index_backed`×1）随命名链/自环/存疑门重算改判入队，新版**均不再以其为 src 建边**（实测该 8 条 src 在新版出边=0）。
  > ⚠ **口径纠偏**：任务口头表述「6 自环 + 11 存疑、15 条转队列」与实测不符 → 实为 **6 自环 + 2 存疑 + 8 改判 = 16 条撤销、另 1 条改指真实目标 → 净 −15**；**11 是「默认可见集的移除数」**（＝conf≥0.45 的撤销条数），非存疑边数。另旧版 6 条 `unconfirmed` 中 4 条系裸 `[命名]` 被静默降档，修复后正确归级 `name_inferred`(0.575) **升级为合法可见边**（＝可见集 +5 的来源之一），非删除。队列 +13 中含 4 条 `same_name_divergent` 被更高优先级门重分类（`eval-P1fix` §2 注记；跨里程碑 kind 计数不可直接对齐 → P2 建议 manifest 增 `reclassified_from` 留痕）。
  > 队列 110 构成（by_kind 实测）：target_unresolved 51 / external_reference 21 / doc_line_uncertain 12 / m1_carryover_derived_dangling 9 / same_name_divergent 5 / self_loop_annotation 5 / m1_carryover_pending_relates_to 4 / polymorphic_fk 2 / same_name_or_typo_ambiguity 1。
  > **P2 技术债移交（不阻断）**：P2-1 `comment_explicit` 混 DDL直证19+文档继承9（建议拆子信号）、P2-2 `_rank<CAP` 死分支、P2-4 `same_name_divergent`(9) 缺 `doc_ref`、P2-5 `build(write=True)` 测试覆写产物（取证须在跑测前）、P2-6 `Agents.md`「30 条」陈旧（05 实=27）；P2-3 命名漂移已裁决（改卡不改产物名）。
  > 边界重申：变换/ETL 数据流级血缘＝**超本期范围**（§2.3/R-9），不得以"血缘"名义扩张；待确认队列须承接 M1 移交的 4 pending + 2 external + 9 derived 悬挂。

### M3 · L2 GraphRAG 社区/全局分析层 — `rag-community-analyst`

- **目标与范围**：在社区检测 + 分层社区摘要 + 全局 map-reduce 上取得确定性遍历给不出的增量：全局枢纽/域间耦合/跨域传导、涌现跨域簇、UC6 按域分析（§1.2 L2、R-0/R-8）。
- **上游依赖**：M1 图 + M2 `REFERENCES` 边（供置信过滤）+ M0 `eval-baseline.md`、`stack-options.md`。
- **输入资产**：M1/M2 图与导出、`er-model/00` 第四节（18 域手工基线）、`er-model/05`（三分类/子图/B-4）。
- **产出物路径**：`graphrag/community/`（社区检测 + 分层摘要）、`graphrag/out/community/`（社区摘要、全局问答报告、涌现簇-18 域对照表；其自身不确定项写 `out/community/uncertain.md`，**不改 M2 的 `review_queue.json`**）。
- **验收门**：
  1. **社区检测输入边仅限 `confidence ≥ 0.45`**（§8.1 M3 行、§5.2-1）：须给出可复算断言——进入社区算法的边集合中低置信边数 = 0，并记录被排除边计数。
  2. **涌现簇 vs `00` 手工 18 域基线对照**：量化一致/差异（对齐率、NMI 或簇映射表等），差异簇须可回溯解释（度数中心性隐藏 hub、`05` B-4 同名异指向造成的假耦合团）；否则降权或入待确认。**涌现簇不得覆盖 18 域的权威分组**（§8.1 M3、`rag-community-analyst` 约束）。
  3. 分层社区摘要 + 全局 map-reduce 结论中每个实体/关系/路径可回溯图节点与 `er-model` 出处；LLM 摘要**不得新建确定性来源没有的表/字段/关系**，无法定位者强制 `[待确认]`（§4.2 / R6）。
  4. UC6 按域问答达 `spec/eval-baseline.md` 定义的黄金集基线（阈值 `[待确认]`）、可回溯。
  5. 依赖与选型：Louvain/Leiden 实现（C-2b 实测 `python-louvain` 未安装 / `networkx 3.7` 已装）须在交付中写明实际所用；GDS 类图库能力缺口标 `[待确认]`，不得预设。
- **回退规则**：未做置信过滤即跑社区 → 判不过门重跑；涌现簇无解释 → 降级为"对照观察"而非发现物；全局摘要出现无出处实体 → 回退 §4.2 硬约束重做；社区层若把结论建立在 `dam_*`/外部平台 → P0 阻断。
- **状态**：✅ **已过门并提交** — `eval-M3.md`（**PASS，`0532561`**：六项硬门以原始 `l0_graph.json`/`lineage_edges.jsonl` 独立复算闭合、与自报零差异、无 P1 返工）。交付＝`graphrag/community/`（`communities/community_summary/global_analysis/nmi_vs_baseline/summarizer` + `tests/test_m3_community.py` 19 项）+ `graphrag/data/meta/community_{result,edges,profiles,nmi_vs_baseline,global_analysis}.{json,jsonl}`；`out/community/`、`l0_*`、pyc、`.tmp/` 依 C-10 **不入库**。
  **实测取值（编排者回读产物，非照抄）**：社区数＝**17**（`networkx.greedy_modularity_communities`，weight=Σconf、resolution=1.0；`python-louvain`/`igraph`/`leidenalg`/`sklearn` 均未装且未 `pip install`）；**NMI(arithmetic)=0.590490**（geometric 0.592354 / max 0.547115），档位 `low(<0.6)`，**19 拆 / 9 混**；融合图 **274 节点 / 477 加权表对**；**入算法 kept=821**（两端 A 级实物、conf≥0.45、非存疑非自环，泄漏 0）；**排除合计 161**＝REL 低置 **64** + REF 低置 **59** + 存疑 **7** + 跨级(B 端) **13** + 自环 **18**（REF 4 条带 explicit_pair + REL 14）；`isolated_a_tables`＝**75**（入图 274 + 孤立 75 = 349 ✓）；`community_role=analysis_view_only`、`authoritative_domain_source=00 §四`（**不覆盖 18 域权威分组**）；LLM 摘要 `summarizer.ENABLED=False`，两入口 `raise NotImplementedError`（**stub 禁用、未调 LLM**）。`pytest graphrag`＝**75 passed**（编排者独立复跑）；锚守恒 RELATES_TO 483 / REFERENCES 499-440-59 / A349 / Issue27 / ξ456 / Σ1322 / FK=0 全等。
  > **M3 遗留 P2（非阻断，移交 M4 承接；不重过 M3 门）**：① **P2-A** 75 张孤立 A 表**名单未落盘**——现仅存计数 75 与 UC6 每域 `isolated_tables` 分布（`_export()` 过滤了 `isolated_a_list`）；建议补 `graphrag/data/meta/community_isolated_a.json`（可确定性复算）。② **P2-C** 黄金集 `graphrag/eval/golden/` **仍未落地**（唯一写入者＝`rag-eval-gate`）→ UC6 无基线可报；**M4 过门前由 eval-gate 落地题目集并补测**，阈值保持 `[待确认]`、**只报命中率不判达标**（§1.5 / 移交要点 4）。③ **P2-B** 9 个混合簇现为量化线索+模板句，建议补 `out/community/uncertain.md` 将其统一标注**「对照观察（非发现物）」**或逐簇给个性化论证（NMI low 档下不得表述为权威发现）。
- **M3 移交要点（P1 修复轮后由编排者登记；委派时须逐条落到交付与验收）**：
  1. **依赖选型定调＝不引入 `python-louvain`**：依 C-2b/N-3 实测（`import community` 无、`networkx 3.7` 有），本期社区检测**只用 `networkx.algorithms.community.greedy_modularity_communities`**；交付须在 manifest 写明**实际所用算法与参数**，Louvain/Leiden 仍留作 `spec/stack-options.md` 的 `[待确认]` 候选——**不得**默认已具备、**不得**自行 `pip install` 新依赖（如需引入：回 M0 出 amendment 并重过 eval-gate）。
  2. **社区输入边集合（硬门，可复算）**：仅允许 M2 **默认可见集 440 条 `REFERENCES`**（等价 `load_graph_with_lineage()` 默认参 `{min_conf:0.45, show_uncertain:False}`）进入社区算法；须自证「进入算法的低置信边数 = **0**、存疑/队列项 = **0**」并记录**被排除计数**（59 低置信 + 队列 110 项不参与）；M1 既有边（RELATES_TO 483 / IS_COLUMN_OF 6428 等）按 M1 口径只读合并，M3 不改写上游产物、**不改 M2 的 `review_queue.json`**（自身不确定项写 `out/community/uncertain.md`）。
  3. **涌现簇对照纪律**：与 `er-model/00` 第四节 **18 域手工基线**（小计 332 + OT 17）做 **NMI 或簇↔域映射表**对照并量化一致/差异；差异簇须可回溯解释（度数中心性隐藏 hub、`05` B-4 同名异指向造成的假耦合团），无解释者**降级为「对照观察」而非发现物**并入 `uncertain.md`；**涌现簇一律不得覆盖/替换 18 域的权威分组**（§8.1 M3 + `rag-community-analyst` 约束）。
  4. **黄金集首轮只报基线、不判达标**：`spec/eval-baseline.md` 只定了构造规范，题目集 `graphrag/eval/golden/` 尚未落地（唯一写入者＝`rag-eval-gate`，执行专家只读）→ UC6 首轮**只记录实测命中率**，阈值保持 `[待确认]`（§1.5），**禁**擅自设标后宣称「达标」。

### M4 · 结构语义层 + 可选 NL 编排前端（L3）— `rag-semantic-nl-frontend`

- **目标与范围**：结构语义（域=语义分区、`05` 三分类=实体类型、字段含义←DDL COMMENT/`03` 含义列、D14 字典=枚举语义）+ 可选 AgenticRAG NL→结构化查询翻译。**指标语义层（KPI/术语表）：超本期范围 `[待确认]`**（§1.4 / R-10）。
- **上游依赖**：M1 检索 + M2 引用血缘 + M3 社区/全局（NL 前端仅编排这三层）+ M0 `spec/schema.md`（`Concept.layer="structural"`）。
- **输入资产**：`er-model/05` 第二节三分类种子、`er-model/03`（含 D14 商品属性与基础字典域）、`test_erp.sql` COMMENT、M1 `out/meta/`。
- **产出物路径**：`graphrag/semantic/`（语义索引与 `REALIZED_BY` 绑定）、`graphrag/nl/`（可选 NL 编排）、`graphrag/out/semantic/`。
- **验收门**：
  1. 语义元素全部限定四类来源（§1.4/R-10），每项可回溯 `05` 三分类 / DDL COMMENT / 命名，无法定位者 `[待确认]`；`REALIZED_BY` 仅用种子 + 可回溯者，不得新建来源外实体（§3.2、§4.2）。
  2. **指标语义层边界**：交付文本显式声明"本期为结构语义层，不含 KPI/计算口径/正式术语表"，遇此类问题回答"需放宽输入，超本期范围 `[待确认]`"（§8.3-7）。
  3. **NL 前端为可选层**：六类查询（UC1–UC6）NL 入口必须落到 L1/L2 的**结构化调用**，不绕过置信过滤直连低置信边、不生成未 grounding 关系（§1.3-4、§6 拒绝臆造兜底、R-5）；默认由 L1/L2 直接作答。
  4. 黄金集端到端回归（M0 构造规范 + eval-gate 持有的题目）：Top-3 命中对齐 `[待确认]` 阈值并记录实测值；关系类回答有出处率 100%（§1.5）。
  5. 时延实测并记录：单跳 P95 / 多跳 P95 对照 §1.5 阈值（`<2s`/`<8s`，方案标 `[待确认]` → 报告须注明"阈值待业务方确认"）。
- **回退规则**：NL 层臆造关系/绕过过滤 → 收回 NL 层，仅交付结构化 API（R-5 定位降格）；语义绑定无出处 → 降级 `[待确认]`；若被要求做指标层/ETL 血缘 → 判边界越位并停止该项，标注超范围。
- **状态**：🟡 **就绪·待委派** — 上游 M3 已过门提交（`eval-M3` PASS，`0532561`）；`graphrag/community/` + `data/meta/community_*` 可作为 L2 只读输入。**须承接 M3 遗留 3 项 P2**（见 M3 卡）：P2-A 补 `community_isolated_a.json`；P2-B 混合簇降级「对照观察（非发现物）」；P2-C `graphrag/eval/golden/` 由 eval-gate 落地题目集后补测 UC6 实测命中率（阈值 `[待确认]`、只报不判达标）。

---

## 3. 横切复核：每里程碑后两道门

### 3.1 `rag-eval-gate`（门禁，产出 `graphrag/reports/eval-M{n}.md`，自行提交该报告）

| 校验项 | 依据（design-plan v2） | 触发里程碑 | FAIL 处置 |
|---|---|---|---|
| 数量等式：可遍历主语料=A349（含 OT17）/ 登记全量 1322=349+853+120 / 每域表数=`00` 声明值 / Issue=27 / 边无悬挂（`[待确认]` 目标除外）/ 前缀归级含 `_bak_` 剔除 | §4.4、§2.2、§8.1(M1)、`Agents.md` §7.3 | M1（M2–M4 回归复跑） | 阻断入库，不交付；退 loader |
| 有出处率：关系类回答 100% 可回溯 `er-model/*` 文件+节/行；无证据断言全部显式标注 | §1.5、§1.3-1、§3.3-4、R-6 | M1/M2/M3/M4 | 不过门；补 `SUPPORTED_BY` |
| 检索质量：黄金集 Top-3 命中率对齐 M0 `eval-baseline.md`（阈值 `[待确认]`，不擅自设标） | §1.5、§8.1(M3/M4)、R-5 | M1 基线 / M3 / M4 | 记实测值；未定阈值不得判"达标" |
| 多跳预算：血缘/影响默认 ≤3 跳 `[待确认]`，超限截断可复现 | §6、§8.1(M2) | M2/M4 | 重跑遍历器 |
| 社区对照：输入边均 `confidence ≥ 0.45`（**M3 实际输入集＝默认可见 440 边**；低置信 59 / 存疑队列项入算法须 = 0，并记被排除计数）；涌现簇 vs 18 域差异有解释且**不覆盖权威分组** | §8.1(M3)、§5.2、R-0 | M3 | 无过滤即不过门 |
| 范围边界：未混入变换/ETL 级血缘、未混入指标语义层；向量/LLM 未被当默认底座 | §2.3、§1.4、§4.3、R-9/R-10 | 全部 | P0 阻断 |
| 引用完整性：`REFERENCES` 两端可解析；多态/同名异指向/external 处置正确 | §3.2、§5.2-4、§8.2 R4 | M2 | 回退队列化 |

硬规则：**任一 FAIL → 整体判"不过门"**，回退建议给编排者；所有计数以 grep/脚本实测，禁沿用产物自报值。

### 3.2 `rag-scope-auditor`（只读审计，产出 `graphrag/reports/audit-M{n}.md`，**不提交**，由编排者代为提交）

| 维度 | 判级 | 要点 |
|---|---|---|
| Scope violation | **P0** | 产物/代码/配置/结论引用或对齐 `dam-app`/`dam_meta`/`PLAN.md`（含 `asset_urn`、M5.4/M8.1 类外部模型） |
| 输入越界 | P0/P1 | 使用 `er-model/*` + `test_erp.sql` + `Agents.md`/`skills` 之外的输入源 |
| 取证真实性 | **P0/P1** | 计数/符号变体/条数须与源文件实测一致（基准见 §A：Issue=27、05 关系线=33、`||--||`/`||--|{`=0、CREATE TABLE=1322、FK=0、行数 26998↔28339；**关系线全量以 `spec/relation-symbol-census.md` ξ=456 为准**；行数差定因＝空行 1341，**非 CRLF**）；有无"照抄约定未回读" |
| 推断标注 | P0/P1 | 关系/血缘逐条带五级证据+置信度并声明"推断、非物理外键"；0 外键不得被表述为约束 |
| 边界守恒 | P1 | ETL 血缘/指标层混入而未标注 |
| 分支纪律 | P1 | 是否在 `chore/rag`、基点 `4a5485a`，有无擅自合并 `master` |

**P0 存在即不过门**（即使 eval-gate 全 PASS）。

---

## 4. 负责路径与提交范围（单一写入者，避免多 agent 抢同一文件）

| 路径 | 唯一写入者 | 其他人权限 | 提交者 / 提交信息 |
|---|---|---|---|
| `agents/RUNBOOK.md`（本文件） | rag-orchestrator | 只读 | orchestrator：`docs(rag): RUNBOOK …` |
| `agents/*.md`（subagent 定义） | orchestrator（经人工确认） | 只读 | orchestrator：`chore(rag): …` |
| `.gitignore`、`graphrag/README.md`（目录索引，可选） | **orchestrator（单点）** | 只读；需新增忽略项向编排者提出（C-9） | orchestrator |
| `er-model/**`、`test_erp.sql`、`Agents.md`、`skills/**` | 无人（输入资产，本轮不改写） | **全体只读**（§7.1 单向摄取） | 不适用 |
| `graphrag/spec/**` | rag-schema-architect | 全体只读（契约） | loader 自提：`docs(rag): M0 契约与映射定稿（n 份）` |
| `graphrag/ingest/**`、`graphrag/store/**`、`graphrag/search/**` | rag-knowledge-loader | 只读 | loader 自提：`feat(rag): M1 图装载与 L1 检索（A级349，n 边）` |
| `graphrag/data/l0_*`、`graphrag/out/meta/**` | rag-knowledge-loader | 只读 | 同上（入库策略见 C-10） |
| `graphrag/lineage/**`、`graphrag/data/meta/lineage_*`、`graphrag/data/review_queue.json`（`graphrag/out/lineage/**` 为本地镜像，不入库） | rag-lineage-builder | 只读（队列只追加，由本 agent 持有） | lineage 自提：`feat(rag): M2 字段级引用血缘（n 边，含待确认队列）`；P1 修复轮＝`fix(rag): CodeReview P1 修复（自环/存疑门/检索/UC2/多跳）`（本轮由编排者按台账时序代为收尾提交） |
| `graphrag/community/**`、`graphrag/out/community/**` | rag-community-analyst | 只读 | community 自提：`feat(rag): M3 社区/全局分析层（n 社区）` |
| `graphrag/semantic/**`、`graphrag/nl/**`、`graphrag/out/semantic/**` | rag-semantic-nl-frontend | 只读 | frontend 自提：`feat(rag): M4 结构语义层与 NL 编排前端` |
| `graphrag/eval/**`（评测 harness + `golden/` 题目） | **rag-eval-gate** | 执行专家**只读**（禁改题目与阈值，避免"考生自己出题"） | eval-gate 自提（仅其报告/harness） |
| `graphrag/reports/eval-M{n}.md` | rag-eval-gate | 只读 | eval-gate 自提：`test(rag): M{n} 验收报告（PASS/FAIL）` |
| `graphrag/reports/audit-M{n}.md` | rag-scope-auditor 生成文本（**不执行提交**） | 只读 | **orchestrator 代提**：`docs(rag): M{n} 范围与取证审计结论` |

冲突规避规则：
1. 同一文件不得双写；跨里程碑复用＝**读上游产物、写自己目录**。
2. 执行专家不得 `git add -A`、不得提交他人的路径、不得提交被 gitignore 的 `_*` 中间产物（C-9）。
3. 需改上游契约/题目/阈值 → 提编排者 → 回退对应里程碑出修订并重过 eval-gate → 台账更新状态后再提交。
4. 提交时序：执行专家自提交付 → eval-gate 自提报告 → orchestrator 提审计与台账状态；三者均只在**过门后**发生（T-00 台账本身除外，无需过门）。

---

## 5. 风险登记（源自 design-plan §8.2，叠加本次实测新增项）

| ID | 风险 | 归属里程碑 | 缓解（方案内既有） |
|---|---|---|---|
| R1 | 0 外键下低证据边被当事实 → 假血缘 | M1/M2 | 置信阈值 + grounding + 拒绝臆造兜底（§5/§6） |
| R2 | Markdown 脆弱依赖 | M0/M1 | 机读 JSON/YAML 导出前置为核心交付（§7.1/R-4） |
| R3 | 1322 表规模下 B/C 节点爆炸淹没查询 | M1 | 族归约 + 影子节点默认排除（§2.2） |
| R4 | 同名异指向 / 多态外键误连 | M2 | 强制待确认队列 + `discriminant` 分支（§5.2-4） |
| R5 | `.ps1` 面向 Win/PS5.1，本机 macOS | M1 | `pwsh` 或跨平台重写 `[待确认]`；C-1 已定 Python 为主干 → **脚本仅复用思路** |
| R6 | LLM 幻觉新建实体/关系 | M3/M4 | §4.2 硬约束 + 回填出处 |
| R7 | `03` 合并行未拆 → 列数与 DDL 不符 | M1 | 拆回原子列 + `test_erp.sql` 列数交叉校验（§3.3-2） |
| R8 | 外部平台被误引为依据 | 全部 | §0/§7.3 + scope-auditor P0 |
| **N-1（实测新增）** | `Agents.md` §4.1 描述的 `||--||`/`||--|{` 在 `01-ER图` 实测 **0 出现**；`Agents.md` §4.2 称 `03` 为"固定列"，实测 **10 种表头签名**；`Agents.md` §4.3 与 `00` 称 A–G "30 条"，实测 `05` **27 条** | M0 | M0 勘察/归一/计数表以实测为准并在 `spec/` 内留"与 `Agents.md`/`00` 口径差"说明（R-3a）；解析器禁依文档约定写法。**注意 `Agents.md` §9.2 的 26,998↔28,339 行数口径差同样需在 EvidenceSrc 口径中声明**（R-6） |
| **N-2（实测新增）** | `jf_*`/`lcap_*` 前缀名串与 A/B 级表数不等（实测 334/501 vs 声明 332/450），差值全为 `_bak_` 备份表（2/51） | M1 | 数量等式断言前先按 C 级规则剔除；否则 A 级会被虚增为 351 |
| **N-3（实测新增）** | `python-louvain` 未安装（`networkx 3.7` 已装） | M3 | 依赖安装策略 `[待确认]`；或退化 `greedy_modularity_communities` 并在交付中写明实际所用 |
| **N-4（实测新增）** | `graphrag/**/_*` 未被 `.gitignore` 覆盖（`/_*` 为根锚定） | 全部 | C-9：`.gitignore` 单点由编排者维护；派生物入库策略见 C-10 |
| **N-5（流程）** | 多 agent 并发写 `graphrag/` 共享文件 | 全部 | §4 单一写入者 + 读上游写自己 |

---

## 6. 变更日志

| 日期 | 变更 | 提交 |
|---|---|---|
| 2026-10-04 | T-00 首版：M0–M4 任务卡、横切双门、路径分工、实施约定 C-1…C-10、实测基线 §A、风险 R1–R8+N-1…N-5 | 本次（`docs(rag): RUNBOOK 里程碑分解与契约台账`） |
| 2026-10-05 | **M0 过门**：`eval-M0`（PASS）+ `audit-M0`（无 P0；P1-1 census 漏 13 malformed、P1-2 行数定因非 CRLF；P2×5）；`spec/` 冻结 | `a917ef8`（报告）/ `7074698`（交付） |
| 2026-10-05 | **M1 过门**：`eval-M1`（六门全 PASS）+ `audit-M1`（无 P0；P1-1 派生/临时产物入库风险、P2×4） | `888e7c6`（报告）/ 本次收尾（交付） |
| 2026-10-05 | **M1 收尾（编排者单点）**：① `.gitignore` 补 `__pycache__/`、`*.pyc`、`/.tmp/`、`graphrag/data/l0_{graph.json,edges.jsonl,index.db}`（C-10 可重建产物不入库；既有项不动）；② §A 勘误 → ξ **456/12**（ξ′ 443/10、malformed 13）、03 表头 **11/292**，并移除写死 HEAD SHA；③ 代提 `reports/audit-M0.md`、`reports/audit-M1.md` 精简存档；④ §0.1 与 M0/M1/M2 卡状态更新，**M2 转「就绪·待委派」** | 本次提交 |
| 2026-10-05 | **M2 过门+收尾（编排者单点）**：`eval-M2`（PASS，`625e6a3`）+ `audit-M2`（无 P0/P1，6 项 P2）；P2-3 命名漂移裁决＝**改卡不改产物名**（`data/references_*`→`data/meta/lineage_*`，M2 卡/§4 同步更正，`out/lineage/` 标本地镜像）；代提 `reports/audit-M2.md`；§0.1 M2→已过门并提交、M3→就绪·待委派 | `ae296d9`（交付）/ `625e6a3`（报告） |
| 2026-10-05 | **CodeReview P1 修复轮收尾（编排者单点）**：① `eval-P1fix`（**PASS**，4 个 P1 全闭合、M1 六锚守恒、两次跑测逐字节确定）由 eval-gate 自提 → `18d383a`；② 编排者按 §4 路径逐路径提交 M1/M2 侧修复代码与重生成产物（`ingest/{config,relation_parser}.py`、`store/{graph,fts}.py`、`search/l1.py`、`lineage/*`、`data/meta/lineage_*`、`data/review_queue.json`）＝`fix(rag): CodeReview P1 修复…`，**56 passed**；③ 台账数值**就地勘误 514→499 边 / 97→110 队列 / 446→440 默认可见**（门文本按历史保真原则以 superseded 注记处理），§0.1 M0–M2 标注「含 P1 修复轮已过门提交」（M0＝P1 未触及 `spec/`），§A 补 A-3/A-4 产物与测试基线；④ **M3 移交要点入台账**（不引入 python-louvain→`greedy_modularity_communities`、社区输入边=440 硬门、涌现簇不覆盖 18 域、黄金集首轮只报基线） | 本次提交 |
| 2026-10-05 | **M3 过门+收尾提交（编排者单点）**：`eval-M3`（**PASS，`0532561`**，六硬门原始数据独立复算、零差异、无 P1 返工）；逐路径提交 `graphrag/community/` + `graphrag/data/meta/community_*`（17 社区 / NMI 0.59049 / kept 821→477 表对 / 排除 64+59+7+13+18=161 / 孤立 A 表 75 / `pytest`=75 passed）；`out/community/`、`l0_*`、pyc、`.tmp/` 依 C-10 不入库（`git add --dry-run` 已核）；§0.1 与 M3/M4 卡状态更新，**M4 转「就绪·待委派」**，登记 M3 遗留 3 项 P2（P2-A 名单落盘 / P2-B 混合簇降级 / P2-C 黄金集）移交 M4 | 本次提交 |

---

## A. 编排者实测事实基线（供 eval-gate / scope-auditor 复算；数值不随约定变更）

> ⚠ **M1/M2 复算以 `graphrag/spec/relation-symbol-census.md`（口径 ξ = **456 条 / 12 种字形**）与 `graphrag/spec/header-normalization.md`（字段表头 **11 种 / 292**）为准，勿回抄本节旧值。**
> 本节 A-1/A-2 已于 2026-10-05 勘误：原记「9 种/439」「10 种/291」系编排者早期取证遗漏 `}o..||`×4 与 `03/D15` L14 表头；census 前版又曾以 `awk length==6` 把子口径 443 充当全量、漏 13 条 malformed —— 均经 `eval-M0` §7/§8-1 与 `audit-M0` P1-1/P2-2 实物证伪并已在 M0 定稿落实。台账不写死 HEAD SHA，一律 `git log -1` 现查。
> 说明：**514 / 97 / 446 三个旧值从未进入本节事实基线**（仅出现在 M2/M3 卡状态行，已随 P1 修复轮就地勘误为 **499 / 110 / 440**）；P1 修复后的产物与测试计数以下表 **A-3 / A-4** 行为基准。

| 事实 | 实测值 | 取证命令（口径） | 方案锚点 |
|---|---|---|---|
| 登记全量 / DDL 表数 | **1322** | `grep -ic 'CREATE TABLE' test_erp.sql` | §4.4、`00` §五 |
| 显式外键 | **0**（`FOREIGN KEY` 0、`ADD CONSTRAINT` 0） | `grep -ciE 'FOREIGN KEY' test_erp.sql`、`grep -ciE 'ADD CONSTRAINT' test_erp.sql` | `Agents.md` §1、§5.1 |
| 分级合计 | A **349**(=332+OT 17) / B **853**(=450+275+128) / C **120**(=117+3) = 1322 | `00` §三/§五/§八 | §2.2、§4.4 |
| 前缀名串（含备份重叠） | `jf_` **334**、`lcap_` **501**、`N/P{hex}_` **403**、`test_` **3**、含 `_bak_` **117**；无前缀(含 bak) 81 | 表名抽取后按前缀计数 | N-2 风险 |
| 18 域小计 | 18+21+19+33+13+15+23+27+31+24+21+11+16+34+6+10+6+4 = **332**，+OT 17 = 349 | `00` §四（逐域清单） | M1 每域断言 |
| A–G 质量问题 | **27**（A3+B4+C4+D5+E3+F4+G4）；上游口径差：`00` 第 5 批与 `Agents.md` §4.3 均标 **30** | `grep -cE '^[[:space:]]*-[[:space:]]+\*\*[A-G]-[0-9]+' 'er-model/05-跨域核心关系总览.md'` | R-3a、§4.4（`00`/`Agents.md` 标 30 → 口径差） |
| 05 核心关系线 | **33**，且符号类全为 `||..o{`（虚线，0 条 `||--o{`） | `grep -oF '\|\|..o{' 'er-model/05-跨域核心关系总览.md' \| wc -l` | §2.1、R-0 |
| 01-ER图 关系符分布 | **口径 ξ 全量 = 12 种字形 / 456 条**；子口径 ξ′「6 字符合规」= **10 种 / 443**；非规范缺左基数 malformed = **13**（`..o{`×11 + `..o|`×2）；`\|\|--\|\|`、`\|\|--\|{` 等 0 出现 | 以 census §3 **左基数可选正则**全文扫描 + 行结构锚定**双法复算**（非逐符号 grep 6 字符口径） | §4.1；真源 `spec/relation-symbol-census.md` |
| 03 表头变体 | **字段表头 11 种 / 292 张字段表**（旧值 10 种/291 漏 `03/D15` L14 复核批表头）；另非字段辅助表头 8 种/17 行 → 全签名 **19 种 / 309 行** | 表头行＝次行为分隔线；cell `strip()` 归一后 join 去重计数 | §4.1、R-3b；真源 `spec/header-normalization.md` |
| 输入文件数 | `01-ER图` 20 篇、`03-逻辑数据模型` 20 篇（D01–D18 + OT + B级）；无 `02-` 目录 | `ls \| wc -l` | §2.1、`Agents.md` §3（`02-` 缺失见 §9.1） |
| 行数口径差 | `00` 记 **26,998** vs `wc -l` **28,339**；**定因＝空行**：非空行 `grep -cv '^[[:space:]]*$'` = **26,998**、空行 **1,341**（28,339−1,341=26,998 精确闭合）；CR 字节 **=0**、`file -I` = utf-8 → **非 CRLF/编码差**（`Agents.md` L156 的 CRLF 疑因经实测证伪） | `wc -l`、`grep -cv '^[[:space:]]*$'`、`tr -cd '\r' \| wc -c`、`file -I` | R-6（`line_hint` 降为尽力字段）；`audit-M0` P1-2 |
| 无 PK 表 | `PRIMARY KEY` 实测 **1311** → **11 张无 PK**（= 1322 − 1311，与 `00` §一/§八 声明一致） | `grep -ciE 'PRIMARY KEY' test_erp.sql` | M1 断言、§8.3-5（E-3） |
| 运行时可用性 | Python **3.14.6**；SQLite **3.50.4**，FTS5 `porter`/`trigram` 建表通过；`networkx 3.7` 有、`community`(python-louvain) 无 | `python3 --version`；`:memory:` 建 `fts5` 虚拟表；`import` 探测 | C-1/C-2/C-2b/N-3 |
| Git | 分支 `chore/rag`，基点 `4a5485a`（未合 `master`）；**HEAD 不在台账写死**（快照必腐化）→ **以 `git log -1` 现查为准** | `git branch --show-current` / `git log -1 --oneline` | §0 分支纪律 |
| **A-3 · M2 血缘产物基线（P1 修复后）** | `references_total`＝**499**；默认可见（`confidence≥0.45` 且 `!has_uncertain`）＝**440**＝M3 社区输入集；低置信隐藏 **59**；`has_uncertain`/`unconfirmed` 边＝**0**；自环边 **4**（100% 带 `explicit_pair`；修复前 10 条/违例 6）；队列 items＝**110**（by_kind Σ=110）；`max(confidence)`＝**0.90**（≤0.95 上限） | `wc -l < graphrag/data/meta/lineage_edges.jsonl`；逐行 JSON 解析按门计数；`python3 -c "import json;print(len(json.load(open('graphrag/data/review_queue.json'))['items']))"` | §5.2-1、M2/M3 卡、`eval-P1fix` §1/§2 |
| A-4 · 测试基线 | `python3 -m pytest graphrag -q` ＝ **75 passed**（M3 后；`ingest/tests` 20 + `lineage/tests` 36 + `community/tests` 19；P1 修复轮为 56 passed，M3 增量测试后升至 75） | 同命令 | C-8 过门前置、`eval-P1fix` §2、`eval-M3` §0 |
| **A-5 · M3 社区产物基线** | `community_result.json`：`community_count`＝**17**；`ingestion.kept_edges`＝**821**；`graph_stats`＝节点 **274** / 加权表对 **477** / `isolated_a_tables` **75**（274+75=349）；`edge_gate`＝REF 499/440/59/0；排除合计 **161**（REL below-conf 64 + REF below-conf 59 + uncertain 7 + non_A 13 + self_loop 18=REF4+REL14）；`nmi.nmi_arithmetic`＝**0.590490**（geometric 0.592354 / max 0.547115，档 `low<0.6`，19 拆/9 混）；`community_role`＝`analysis_view_only`；算法＝`networkx.greedy_modularity_communities`（weight=Σconf, resolution=1.0）；LLM `summarizer.ENABLED=False` | 逐字段 JSON 解析 + `wc -l < graphrag/data/meta/community_edges.jsonl`(=821) + 独立 NMI 手写实现交叉验算 | §8.1(M3)、§5.2-1、`eval-M3` §1/§3 |

### A-1 · `er-model/01-ER图/*` 关系连接符实测计数

```text
── 口径 ξ · 全量（左基数标记可选正则；真源 spec/relation-symbol-census.md §2）──
||--o{   321   ┐
||..o{    92   │
||--o|    11   │
||..o|     5   │   6 字符合规「子口径 ξ′」= 10 种 / 443 条
}o..||     4   │   （= 456 − 13；旧 §A「9 种 / 439」即漏 `}o..||` ×4）
|o--o{     3   │
}o--o|     3   │
}o--o{     2   │
}o..o{     1   │
}o..o|     1   ┘
..o{      11   ⚠ 非规范：缺左基数标记 → 装载 malformed_connector=true
..o|       2   ⚠ 同上（malformed 合计 13；行锚点 D02+1 / D05+1 / D06+2 / D08+9）
──────────────────────────────────────────────
Σ = 456 条 / 12 种字形      （ξ′ 443/10 ＋ malformed 13/2）
0 出现：||--|| = 0、||--|{ = 0 …（全 0 组合清单见 census §3）
  ← 禁依 Agents.md §4.1「1:1 用 ||--||」「强制多行 ||--|{」写法建正则（C-β）
（取证：编排者本次以 census §3 冻结的左可选正则对 01-ER图/*（20 篇）全文扫描，
 与「实体 连接符 实体 :」行结构锚定法复算一致；裸模式 \.\.o[\{|] 会截出假阳性，禁用）
```

### A-2 · `er-model/03-逻辑数据模型/*` 表头签名实测计数

```text
── 字段定义表头：11 种 / 292 张字段表（编排者本次复算）──
103  |字段|类型|可空|默认|键|含义|
 60  |字段名|类型|可空|默认值|键|含义/关联|
 59  |字段|类型|可空|默认|主/外键线索|含义|关联|
 27  |字段名|类型|可空|默认值|主/外键线索|字段含义|关联（推断）|
 14  |字段|类型|可空|默认值|键|含义|
 12  |字段名|类型|可空|默认值|键|含义|
  6  |字段|类型|可空|默认值|主/外键线索|字段含义（COMMENT原文）|与其他表的关联|
  5  |字段|类型|可空|键|含义|                        ← 5 列形态（无「默认」列；前版曾误记 6 列）
  4  |字段|类型|可空|默认|线索|含义|
  1  |字段|类型|可空|默认|线索|含义|关联|
  1  |#|字段（任务书=DDL 列名）|DDL 行号|类型|可空|默认值|键/关联结论（本批复核后）|  ← 03/D15 L14；旧 §A「10 种/291」的漏计项
Σ = 103+60+59+27+14+12+6+5+4+1+1 = 292 ✓
（6/7 列并存 + 无「默认」列形态 +「关联」三态：独立列 / 内联 FK[目标·依据] / 斜杠合列 → 归一映射须全覆盖）

── 非字段辅助表头：8 种 / 17 行（不得喂列解析器）──
编号|问题类型|说明 ×7； 编号|问题类别|描述|涉及表/字段 ×3； 本域.字段|目标域·表|依据 ×2；
族|代表结构数|套数/副本数|小计 ×1； 关键差异字段|类型|含义 ×1； 分组|字段（均 int NOT NULL DEFAULT 0，除首列）×1；
分组|字段|键/含义 ×1； 表|中文含义|name 含义 ×1
全签名合计 = 11+8 = 19 种 / 292+17 = 309 行 ✓（真源 spec/header-normalization.md §3/§4）
```

> 引用本表任一手写数字前，请重新执行对应命令复算；若与源文件不符，以源文件为准并回报差异。
