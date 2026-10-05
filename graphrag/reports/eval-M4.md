# eval-M4 · GraphRAG M4（L3 结构语义层 + 规则式 NL 前端）独立验收报告

> ## ⚠ 勘误通告（2026-10-05，P1 修复轮后复跑）
> 本报告**正文数字系 HEAD `fd65ee0`→`4111aea` 提交态的实测**，其后 M3/M4 完成终轮 CodeReview P1 修复（枚举口径、u6-03 拒答、`input_fingerprint` 嵌入），**eval 侧完成 P1-2 消费侧双源整改**。以下项已在**当前工作区**复跑并被取代，正文保留原值并就地标注「勘误」以免历史失真：
>
> | 项 | 原值（本报告） | **现值（勘误）** |
> |---|---|---|
> | 拒答正确率 | 7/8 = 87.5%（MISS u6-03） | **8/8 = 100%**（u6-03 已翻转：`refused=True, sources=[]`） |
> | `HIT_VECTOR` | `110111111111111101111` | **`110111111111111111111`** |
> | 内联枚举 | 490 | **477 accepted + 0 needs_review + 13 declined = 490**（旧锚闭合；值-义对 1264，截断违例 0） |
> | 全局耦合对回查判据 | M3 磁盘 `community_edges.jsonl` kept 集 | **与 `NLRouter.l2()` 实时同源三档基准**（磁盘那份降为 drift 对照，`used_for_judging=False`）＝P1-2 双源消除 |
> | 时延 P95（单跳/多跳·全局） | 58.4ms / 374.2ms | 口径变更：**逐题不含** l2 一次性预热；现 `multi_hop_global≈2.2ms`、单跳预热后 2.4–2.8ms，一次性开销另记 `l2_warmup_ms≈427.9–438.3ms` / `harness_total_ms≈539.1–631.7ms` |
> | 全仓测试数 | 103 / M4 28 | **180**（= 存量 **167** + 本 agent eval **13**；M3/M4 各自新增指纹测试已含在 167 内） |
> | 总体判定 | 全项 PASS、准予提交 | 结论方向不变；**但 M3/M4 P1 修复代码与 eval 整改至今仍未提交（工作区态）**，且新增 4 项 P2 移交 → 详见 **`eval-M4fix.md`** |
>
> **P1-2 消费侧整改与全量复验的完整证据、数量等式实测、遗留清单见 [`eval-M4fix.md`](./eval-M4fix.md)。**

- **里程碑**：M4 · `graphrag/semantic/` + `graphrag/nl/` + `graphrag/data/meta/semantic_*` + `community_isolated_a.json`（未提交态，分支 `chore/rag`，基线 HEAD `fd65ee0` = M3）
- **验收人**：`rag-eval-gate`（独立复算，不采信自报；出具时间 2026-10-05）
- **契约依据**：`er-model/graphrag/design-plan.md` §4.4/§5.2/§6/§1.4(R-10)/§4.3(R-9)、`agents/RUNBOOK.md` M4 卡、`graphrag/spec/eval-baseline.md`（M0 冻结）、`er-model/{00,03,05}` + `test_erp.sql` 实物
- **总体判定**：✅ **全项 PASS，过门**（附 2 项 P2 非阻断改进项，均已被黄金集诚实标注/拒答覆盖）→ **准予提交，MVP + 增值层（L0/L1/L2/L3）全部收口**　【勘误】其中 P2-①（u6-03 拒答泄漏）**已由 M4 侧修复并复验为 8/8**；另新增 4 项 P2 移交，见 `eval-M4fix.md` §8。
- **本轮附带交付**：首次落地 `graphrag/eval/golden/`（本 agent 唯一写入），出 **UC1–UC6 + 全局 + 超范围守卫** Top-3 命中率基线（阈值一律 `[待确认]`，只报实测）。

---

## 0. 测试实跑（不采信自报）

| 项 | 自报 | 实测 | 结论 |
|---|---|---|---|
| 全仓 `python3 -m pytest graphrag` | 103 | **103 passed**（2.97s） | ✅ 一致　【勘误·现态】**180 passed**（8.85s）＝存量 167 + eval 13 |
| M4（`semantic`+`nl`） | 28 | **28 passed**（0.85s） | ✅ 一致　【勘误·现态】`semantic` **80**（含 M4 指纹测试）、`nl` 0（NL 断言内嵌 semantic 测试） |

环境实测：Python 3.14.6 / pytest 9.1.1；无 venv、系统解释器直用。

## 1. 黄金集落地（本 agent 唯一写入， discharge M3 P2-C）

- 产物：`graphrag/eval/golden/{manifest.json, golden_set.jsonl}` + `graphrag/eval/run_golden.py` + `graphrag/eval/__init__.py`。
- 规模：**21 题**，分布 `UC1:4 / UC2:3 / UC3:3 / UC4:2 / UC5:2 / UC6:3 / GLOBAL:2 / OOS:2`；每类 UC 均含 ≥1 边界/负例（refused）。
- 构造纪律：gold 全部取自 `er-model/{00,05,03}` + `test_erp.sql` 实物、逐题带 `source` 行号锚，**不臆造**；`manifest.thresholds` 与 `gates_fixed` 一律 `[待确认]`（只记实测不判达标）。
- 经 `NLRouter().answer(query)` **端到端**跑测（不强制注入 intent，测真实 NL→意图→结构化）。
- ⚠ **自纠**：manifest `item_count_by_uc.total` 初写 20，与逐项之和 21 不符；已改 21（本 agent 自有产物问题，非 M4 被测产物缺陷）。

### 1.1 Top-3 命中率基线实测（`python3 -m graphrag.eval.run_golden`）

| 指标 | 实测 | 阈值 | 说明 |
|---|---|---|---|
| **Top-3 严格命中率**（主口径，metric=top3） | **6/7 = 85.7%** | `≥90% [待确认]` | UC1×3 + UC3×2 + GLOBAL×2　【勘误·现态】不变（6/7） |
| 综合命中率（top3+reachable+coverage 全非边界题） | **12/13 = 92.3%** | `[待确认]` | 唯一 MISS = u1-03（已登记召回探针）　【勘误·现态】不变，且**仍为唯一 MISS** |
| 拒答正确率（边界 + 超范围） | **7/8 = 87.5%** | `[待确认]` | 唯一 MISS = u6-03（见 §3.4，安全拒答但泄漏 1 出处）　【勘误·现态】**8/8 = 100%**，u6-03 已修复（`refused=True ∧ sources=[]`） |
| **幻觉（无出处/绕门关系）** | **0** | 期望=0 | UC3/4/5 逐条回查 M1 图邻接 + quote_hash + conf≥0.45；全局耦合回查 M3 kept 边　【勘误·现态】仍 **0**，但全局耦合判据改为**与 `l2()` 同源**（P1-2，见 §3.5） |
| 意图分类 | **21/21** | — | 端到端意图判定全对　【勘误·现态】不变（`mismatches=[]`） |
| 时延 P95（单跳 / 多跳·全局） | **58.4ms / 374.2ms** | `<2s / <8s [待确认]` | 远低于阈值　【勘误·现态】**口径变更**：逐题时延不含 l2 一次性预热（`multi_hop_global≈2.2ms`；一次性开销改由 `l2_warmup_ms`≈427.9–438.3ms 披露）→ 与左值不可直接比较 |

**分 UC**：UC1 2/3（u1-03 探针 MISS）、UC2 2/2、UC3 2/2、UC4 1/1、UC5 1/1、UC6 2/2、GLOBAL 2/2。
【勘误·现态】数值**全部不变**。须澄清口径（原报告此处易被误读）：`per_uc_hit` **只统计非边界题**（metric ∈ top3/reachable/coverage），`refused/oos_refused` 题单列于拒答率——UC6 共 3 题，u6-03（refused）不入分母，故 n=2 而非 3。因此 u6-03 由 MISS→HIT 只体现在**拒答率 7/8→8/8** 与 `HIT_VECTOR`，**不会**改动分 UC 数字。

### 1.2 复现性（确定性）

两次 `run_golden` 逐 item 命中向量 **完全一致**：`HIT_VECTOR = 110111111111111101111`；排除时延字段后全量 diff 为空 → 检索/遍历无随机性、可复现。
【勘误·现态】`HIT_VECTOR = 110111111111111111111`（第 18 位 0→1，对应 u6-03 翻转）；确定性结论不变（同进程两次逐位一致）。

## 2. 数量等式与锚守恒（§4.4）——**PASS**

| 等式 | 产物声称 | 独立复算（原始源） | 结论 |
|---|---|---|---|
| 字段全量 = A 级可遍历 | 6428 | `field_meanings` 349 表逐列计 = **6428** | ✅ |
| 有/无 COMMENT | 6331 / 97 | 计数 **6331 / 97**（6428=6331+97） | ✅ |
| 内联枚举 | 490 | `inline_enums_from_comment` = **490**，每条 `raw_comment` 逐字 ∈ `test_erp.sql`（MISSING=**0**），source 缺失=**0** | ✅　【勘误·现态】P1-1 改口径后 = **477 accepted + 0 needs_review + 13 declined**（Σ 仍 490）；1264 值-义对逐字回溯 MISSING **0**、出处缺失 **0**、多位码截断违例 **0** |
| 码表 / KV 字典 | 29 / 4 | **29 / 4**，每条带 `source.quote_hash`（缺=0） | ✅ |
| 概念（实体三分类） | 36 | `concepts` = **36** = located **33** + pending **3** | ✅ |
| REALIZED_BY 边 | 67 | 目标物理表逐条 ∈ 图 `table:*` 节点，**悬挂=0** | ✅ |
| 图内主语料守恒 | 274+75=349 | `build_subgraph()` 权威复算 nodes=**274**、isolated=**75**、和=**349** | ✅ |
| 混合簇降级 | 9 | `n_mixed_communities`=**9**，逐簇标「对照观察(非发现物)」（9/9） | ✅ |

【勘误·现态补测】其余锚在当前工作区仍逐项闭合：Table 1322 = A349+B853+C120、`test_erp.sql` `CREATE TABLE` 1322 / `FOREIGN KEY` 0 / `PRIMARY KEY` 1311、`Issue` 节点 27 == `05` A–G 列表项 27、`RELATES_TO` 483 / `SUPPORTED_BY` 483 / `HAS_ISSUE` 11、M2 `lineage_edges.jsonl` 499（`REFERENCES`）/ `review_queue` 110、M3 kept 821 记录 / 477 表对。全表见 `eval-M4fix.md` §5。

## 3. M4 诚实性独立校验——**PASS**

### 3.1 语义元素 100% 挂 quote_hash 或 `[待确认]`
- domains 20 / code 29 / kv 4 / inline 490 / concept 36 / field 6428 逐类抽检：出处（`source.file`+`quote_hash`）挂载率 **100%**；无证据者一律 `[待确认]`（97 无 COMMENT 列）。【勘误·现态】inline 改 **477**（+13 declined 审计，declined 全部带出处并标 `[待确认]`）；97 无 COMMENT 列的 `[待确认]` 标记位实测在 `source.status`（非 `meaning`），未标 **0**。
- `honest_boundaries` 5 条声明齐备（字段仅取 COMMENT、REALIZED_BY 仅绑命中实物、码表值域不硬编码、内联枚举逐字回溯、指标超范围未实现）。

### 3.2 概念 realized_by / pending 抽验（36 概念）
- **33 located**：`binding_detail` 全部带 source（缺引用=0），67 目标表 **0 悬挂**（全部命中图内真实表）。
- **3 pending**（`jf_warehouse` / `颜色` / `jf_bloc`）：其候选标识符经图查询确认**不存在同名表**（`exists_in_graph=False`），故正确 `[待确认]` 未绑——**未绑不存在的表，无臆造**。

### 3.3 97 无 COMMENT 列确标 `[待确认]`
- 按 `CREATE TABLE` 块精确定位每条 (table,column)：97 条 **全部**在其 DDL 定义行 **确无 `COMMENT`**（误标=0），且 `has_meaning=False` 全部标 `[待确认]`（未标=0）——无臆造含义。

### 3.4 指标语义层 / KPI / 术语表确未实现（OUT_OF_SCOPE）
- `out_of_scope` 4 项显式声明：①指标/KPI 定义 ②计算口径/派生血缘 ③glossary 术语表 ④LLM 语义合成，状态均「超本期范围 / [待确认]」，**未实现**。
- NL 端对指标查询拒答：`oos-01`（GMV 口径）、`oos-02`（导出 glossary）→ `intent=out_of_scope` 且 `refused=True`（2/2 命中）。

### 3.5 NL 只调 L1/L2、不绕门、不生成无出处关系
- 静态：`graphrag/nl`、`graphrag/semantic` grep `import requests/httpx/openai/urllib` = **NONE**。
- 依赖面：`NLRouter` 仅组合 `self.l1.*`（find_tables/columns_of/tables_with_column/relations_of/traverse/domain_summary）+ `self.l2()`（全局分析），无直连 SQL、无绕行通道。
- 默认门：`answer(min_confidence=CONFIDENCE_DEFAULT_MIN=0.45, show_uncertain=False, max_hops=MAX_HOPS=3)`；实测 `max_hops=100` → **钳制到 3**；黄金集 UC3/4/5 返回边 **0 条** conf<0.45 或带存疑（绕门=0）。
- 无出处关系：抽样边回查 M1 图邻接 + quote_hash，UC3/4/5 违例 **0**；GLOBAL 耦合样本表对回查 M3 `community_edges.jsonl` kept 集，违例 **0**。
  【勘误·现态 / P1-2 双源消除】GLOBAL 耦合**不再以磁盘 `community_edges.jsonl` 为判据**（旧法＝"实时算的耦合对 比 可能陈旧的磁盘 kept 集"，会静默产生假 FAIL 或假 PASS）。现由 `graphrag/eval/kept_source.py` 供给三档**实时同源**基准：①与 `router.l2()` **同一次** `build_subgraph()` 捕获（判据）②独立重算（自洽佐证）③eval 自行从原始 M1 `l0_graph.json` RELATES_TO + M2 `lineage_edges.jsonl` REFERENCES 重推的**独立实物出处**集；磁盘那份 `used_for_judging=False`、只报 drift（本次 drift 0/0），其摘要与门状态由 `graphrag/eval/kept_pairs_sidecar.json` 承载（JSONL 不内嵌顶层键）。消费点同时挂 `semantic.fingerprint.verify_json_file`（**strict-on-enable dormant**：默认 `observed_no_baseline` 放行留痕；注入 `GRAPHRAG_EXPECTED_INPUT_FINGERPRINT` 后缺失/不一致即 `InputFingerprintMismatch` 中断评测）+ **恒开** freshness 陈旧门。详见 `eval-M4fix.md` §1–§2。

### 3.6 LLM stub 默认禁用
- `llm_synthesis.py`：`ENABLED=False`，`synthesize/compose_multi` 两入口直接 `raise NotImplementedError`；产物无自由文本生成结论。

### 3.7 M3 P2 承接属实
- `community_isolated_a.json`：`isolated_a_count=75`、`in_graph_nodes=274`、`coverage_equation="274+75==349"`、`m3_crosscheck_count=75`；75 张全为有效 A 级实物表（非存在=0）。
- **权威源交叉**：调 `graphrag/community/communities.build_subgraph()`（即本文件声明的 `recompute_source`）独立复算 → isolated=**75**、nodes=**274**，且已提交名单集合 == 重算集合（**diff=0**）。
- ⚠ 说明：验收初以「tier==A」粗筛得 79，差异根因为产物口径系「A 级**且已赋 domain**」（`n.get("domain")` 过滤 4 张无域 A 表）；采用**同一权威定义**后精确吻合，产物无缺陷（不采信任一口径差）。
- 9 混合簇统一降级「对照观察(非发现物)」，`false_coupling_note` 声明低置信/存疑/同名异指假耦合已在双硬门剔除、入算法=0。
- 【勘误·现态新增 P2】该文件的 `fingerprint_check.status` 落盘值仍为 `absent_upstream_not_embedded`，而当前 `community_result.json` 已嵌 `input_fingerprint`（内存重算现为 `observed_no_baseline` + digest `43e78f082c25…`）⇒ **落盘内容陈旧**，需 owner 重跑 `python -m graphrag.semantic.m3_p2_handoff`（`eval-M4fix.md` §8-P2-①）。计数本身（75/274/349）不受影响。

## 4. 范围边界——**PASS**
- 全 M4 产物/代码 grep `dam-app` / `dam_meta` / 独立 `PLAN.md` = **0**（早期命中系 `design-plan.md` 子串误报，属允许参照）；`asset_urn` / `ETL` / 变换级血缘仅出现在**排除声明**（§4.3/R-9）语境，无混入。
- `semantic_layer_scope=structural`、`scope_statement` 明确「不含指标语义层」；未越界承诺生成式/指标能力。

## 5. 与 builder 自报差异清单

| 项 | 自报 | 独立实测 | 差异 |
|---|---|---|---|
| pytest 全仓 / M4 | 103 / 28 | 103 / 28 | 无　【勘误·现态】180 / semantic 80 |
| 概念 / located / pending | 36 / 33 / 3 | 36 / 33 / 3（+ 67 realized_by 0 悬挂） | 无 |
| 字段 6428 = 6331 + 97 | 同 | 6428 = 6331 + 97（97 DDL 精确定位确无 COMMENT） | 无 |
| 内联枚举 490 逐字回溯 | 490 | 490，raw_comment ∈ DDL（MISSING=0） | 无　【勘误·现态】477(+0,+13)，1264 值-义对 MISSING=0/截断=0 |
| isolated 75 / 274 / 349 | 75 / 274 / 349 | 权威 `build_subgraph()` 复算 75 / 274 / 349，集合 diff=0 | 无 |
| 混合簇 9 全降级 | 9 | 9（9/9 标对照观察） | 无 |
| **检索质量基线** | 无（builder 无黄金集） | **本 agent 首落 21 题**：Top-3 85.7% / 综合 92.3% / 拒答 87.5% / 幻觉 0 | **增量**：绿灯单测 ≠ 检索质量已证，黄金集方暴露真实缺口　【勘误·现态】拒答 **100%**，其余持平 |

**核心差异结论**：M4 被测产物全部自报值经原始 DDL/图/M3 权威源独立复算**零差异、无臆造**；builder 的 28 项单测为回归锁（自一致），而检索质量此前无基线——本 agent 首落黄金集补齐，并揭示 2 处单测未覆盖的真实缺口（u1-03 召回、u6-03 拒答泄漏）。【勘误·现态】后者已闭合为 **1 处**（仅 u1-03 召回）。

## 6. 两处 MISS 定性（均诚实、非阻断）
1. **u1-03**（哪些表存发票 → `jf_invoice` 未进 Top-3）：manifest `known_recall_probes` **已登记**为真实召回缺口候选（R-5 语义/字形），非系统错误、非臆造；`jf_invoice` 在图中存在且可经关系路径命中（UC3/UC4 题证）。【现态复确认】仍为**唯一 MISS**。
2. **u6-03**（D99 幻影域）：`exists=False` → 正确 `refused=True`、**未臆造任何表/结论**（安全）；惟 `sources` 仍带 1 条 `Domain` 出处 stub（指向 `er-model/00 §四`），与「refused ⟹ sources 为空」契约不符 → 计 P2 卫生项。　【勘误·现态】**已修复**：实测 `refused=True ∧ sources=[]`（`n_src=0`），拒答 8/8。

## 7. P2 非阻断改进项（交付后择期处置，不拦 M4 过门）
- **P2-① 拒答泄漏**：NL `community_rollup` 对不存在域拒答时应清空 `sources`（stub 出处不应外泄），严格化「refused=True ⟹ answer & sources 皆空」。【勘误·现态】**已修复并复验**（u6-03 → HIT）。
- **P2-② 召回缺口**：UC1 BM25/trigram 对语义类查询（如「发票」→`jf_invoice`）Top-3 召回待加强（R-5），可在阈值 `[待确认]` 定档后与业务方决定是否需要同义词/域先验加权。【现态】仍开放（唯一 MISS）。
- 【新增·移交】P1-2 消费侧整改后又暴露 4 项他人路径事项（`community_isolated_a.json` 落盘陈旧、`test_m3_fingerprint.py` 注释引用已删函数、RUNBOOK §A 台账过期、declined 整列原子口径的可机读保守性），逐条见 `eval-M4fix.md` §8。

## 8. 判定与放行建议

**✅ PASS——过门，准予提交。**
- 数量等式全部闭合（6428=6331+97 / 36=33+3 / 67 realized_by 0 悬挂 / 274+75=349 / 混合 9 全降级）；
- 诚实性硬门全过（语义元素 100% 出处或 `[待确认]`、97 无臆造含义、490 逐字回溯、指标层确未实现、NL 只调 L1/L2 不绕门、0 幻觉、LLM stub 禁用、M3 P2 承接经权威源复算吻合）；
- 范围边界无越界（无 dam-app/dam_meta/PLAN.md/asset_urn/ETL 混入）；
- 黄金集 Top-3/综合/拒答命中率均为 `[待确认]` 阈值下的实测基线（不判达标），确定性可复现。
- 两处 MISS 与两项 P2 均为**已登记的诚实边界/卫生问题**，不违反任何硬门（无臆造、无绕门、无幻觉）→ **不阻断**。

【勘误·现态判定】上列结论方向不变，但须补两条纪律性事实：①**490 → 477(+0 review,+13 declined)** 后诚实性口径更严（违例仍 0）；②0 幻觉的**判据**已由"磁盘 kept 集"换成"与 `l2()` 实时同源 + 独立实物出处"（P1-2），旧判据在陈旧/臆造场景下会静默失真，新判据已在 `graphrag/eval/tests/` 用反例锁死。另：**M3/M4 的 P1 修复与本 eval 整改截至本报告出具时均未提交**（HEAD 仍 `4111aea`），"准予提交"≠"已提交"，入库由相应 owner 处置。

**放行**：MVP（L0 图谱底座 + L1 确定性检索）+ 增值层（L2 社区/全局 + L3 结构语义 + 规则式 NL）至此 **全部收口**。本 agent 仅提交自身黄金集与本报告；M4 业务代码/产物（`semantic/`、`nl/`、`data/meta/semantic_*`、`community_isolated_a.json`）的入库由执行专家/编排方按其纪律处置。

**独立复现命令**：
```
python3 -m pytest graphrag -q                                   # 103　【勘误·现态 180】
python3 -m pytest graphrag/semantic graphrag/nl -q              # 28　 【勘误·现态 semantic 80】
python3 -m graphrag.eval.run_golden                             # Top-3 基线 + HIT_VECTOR（两次一致）
python3 -m pytest graphrag/eval -q                              # 【新增】P1-2 同源/指纹门回归锁 13
```
