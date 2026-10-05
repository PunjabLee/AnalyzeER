# eval-P1fix · GraphRAG P1 修复轮独立验收报告

- **判定**：✅ **全项 PASS → 准予过门（可安全提交并放行 M3）**
- **验收者**：`rag-eval-gate`（独立硬校验，不采信自报值；全部计数/命中以实测脚本为准）
- **分支/基点**：`chore/rag` @ HEAD `ae296d9`（工作区含 M1/M2 未提交 P1 修复；未合 `master`）
- **被审改动**：
  - M1 侧：`ingest/config.py`、`ingest/relation_parser.py`、`store/graph.py`、`store/fts.py`、`search/l1.py`
  - M2 侧：`lineage/{extractor,build,impact,__init__}.py` + 重生成 `data/meta/lineage_*`、`data/review_queue.json`
- **环境实测**：Python 3.14.6 / SQLite 3.51.0（FTS5 `trigram` 可用）/ pytest 9.1.1
- **产物指纹（跑测前取证，承 audit-M2 P2-5 时序纪律）**：
  `lineage_edges.jsonl` md5 `d0bd4ee1…` · `review_queue.json` md5 `e66b8926…` · `lineage_manifest.json` md5 `3f14efbf…` · `l0_graph.json` md5 `ee6a9e47…`
  两次 `pytest` 后哈希**逐字节一致** → 确定性可复现，无测试态污染。

---

## 1. P1 逐条闭合判定

### P1-1 · 自环记法不得降级成"本表自引用"错边 — ✅ PASS

| 断言 | 独立实测 | 判定 |
|---|---|---|
| 4 条点名错边不再以错目标出现 | `jf_basic_craft.finish_product_id`：HEAD 版指向 `jf_basic_craft.id`（自环 pk）→ 修复版**唯一 added 边**指向真实 `jf_product.id`（index_backed 0.775）。`jf_shipping_progress.income_bill_id` / `jf_merge_shipment_log.sales_order_id` / `jf_user_show_page_table.user_id`：以本表为 dst 的边 count=**0**，全部落入 `self_loop_annotation` 队列 | **PASS** |
| `src_table==dst_table` 的 REFERENCES 边 100% 带 explicit_pair | 逐行解析新 `lineage_edges.jsonl`(499)：self_ref=**4**，其中 explicit_pair=**4**，violators=**0**（HEAD 版：自环 10、无 explicit_pair 违例 6）。manifest 负向断言 `self_reference_explicit_pair_only: target=0 actual=0 ok=True` | **PASS** |
| 守恒式（无静默蒸发） | 差分 removed=16 / added=1，净 -15（514→499）。removed 逐条 in_queue 追溯：自环消解→`self_loop_annotation`(5)、存疑文档线→`doc_line_uncertain`(12)、余者归入 target_unresolved 等既有队列 | **PASS** |

证据：`extractor._resolve_self_annotation` 消解次序（①点名唯一真实他表 ②多表入队 ③显式本表列对才认自引用 ④皆无入队 `self_loop_annotation`）+ 命名第 3 路自指同规（`_explicit_self_pair`）；`by_target_col_rule` 中 explicit_pair=55（含跨表 comment 点名，自环占 4）。

### P1-2 · 裸 `[命名]/[语义]` 归级 + uncertain 一等门 — ✅ PASS

| 断言 | 独立实测 | 判定 |
|---|---|---|
| 裸标签正确归级（非静默 unconfirmed） | `config._TAG_TO_LEVEL` 新增 `("命名","name_inferred")`、`("语义","semantic_inferred")`；源文件实测 `[命名]`×**6**(D11)、`[语义]`×**2**(D10) 与注释吻合。M2 血缘侧：4 条原 `unconfirmed`(conf=0.1，引文带裸 `[命名]`、无 `[待确认]`)→ 现 `name_inferred`(0.575) 合法可见边。M1 图侧：裸 `[命名]` RELATES_TO→name_inferred×6、裸 `[语义]`→comment_explicit×2，无静默 unconfirmed | **PASS** |
| 默认可见边集不含 has_uncertain/unconfirmed/自带`[待确认]`边 | `load_graph_with_lineage()` 默认 min_conf=0.45/show_uncertain=False：loaded=440、hidden_below_min_conf=59、hidden_uncertain 生效；合并图 440 REFERENCES 违例=**0**。逐行全量扫 499 边：uncertain-in-edge-set=**0**（HEAD 版混入 14）。l1.relations_of/traverse 全 A 级默认可见违例=**0**，`show_uncertain=True` 才暴露（`jf_business_inventory_record` 0→1，可逆、不删边） | **PASS** |
| `payment_method`/`sales_type` 已入队非建边 | `jf_receivable_claim.payment_method`、`jf_sales_order_wide.sales_type`：HEAD 版各建 1 条 unconfirmed 边 → 修复版 as_edge_any_side=**0**，`review_queue.json` 各命中 1 条 `kind=doc_line_uncertain, status=[待确认]` | **PASS** |

证据：`extractor._doc_uncertain_reason`（`[待确认]` 标记 / `has_uncertain` / 未识别标签→unconfirmed）+ `_queue_uncertain`（入队不建边、`uncertain_cols` 阻断命名链复活）+ `_add_edge` 兜底门 `rejected_uncertain_edge`；`build._edge_uncertain` 查询门（历史边隐藏不删）。

### P1-3 · 中文 FTS 命中 + 坏索引/非法查询显式抛错 — ✅ PASS

对交付库 `data/l0_index.db`（只读 `rebuild=False`）实跑：

| 断言 | 独立实测 | 判定 |
|---|---|---|
| 中文查询确有命中 | `"销售订单"`→hits=**2**（`jf_sales_order_cus`/`jf_sales_order_wide`，trigram 路 bm25≈-8.2）；`"库存"`→hits=**5**（<3 字 CJK LIKE 兜底路，bm25=None 排序末位） | **PASS** |
| 合法但 0 命中 → 返回 `[]` 不抛 | `"zzz不存在的查询qqq"`→ 正常返回 `[]`，无异常 | **PASS** |
| 坏索引/非法查询 → 真抛错（非静默 `[]`） | DROP `nodes_fts_cjk` 后查 `"销售订单"`→ **`FTSQueryError`**（table=nodes_fts_cjk）；非法 MATCH `'AND OR AND OR'`→ **`FTSQueryError`**。旧 `except OperationalError: return []` 已删 | **PASS** |

证据：`fts.py` 新增 `nodes_fts_cjk`(tokenize='trigram') + 双路合并去重(`_run_match`/`_run_like`/`_eff`) + `FTSQueryError` 包装；`build()` 幂等（先 DELETE 三表）。

### P1-4 · `tables_with_column` 返回真实表名 — ✅ PASS

| 断言 | 独立实测 | 判定 |
|---|---|---|
| semantic_tables 为真实表名（非列名） | `tables_with_column("sales_order_id")`：semantic=**17** 全部命中 `table:` 节点，非表名条目=**[]**（旧 bug `.split(".")[0]` 对裸列名恒等列名）。exact_tables == 图真值(列→表 17)=**True** | **PASS** |

证据：`search/l1.py` 改取 FTS 行 `domain` 字段（build 时 Column.domain=table_id）；`fts.build()` Column 分支 `domain=node["table_id"]`。

---

## 2. 回归 / 守恒（§4.4 独立复算，禁沿用自报）

| 锚 | 独立实测值 | 判定 |
|---|---|---|
| RELATES_TO | l0_graph.json 边类型计数 = **483**；合并后 m1_edge_intact=483 | **守恒** |
| 图内可遍历主语料 A | Table by tier = **A349** / B853 / C120，Σ=**1322**；18 域小计=**332**、OT=**17** → 349 | **守恒** |
| Issue 节点 | **27**（l0_graph `Issue` 计数；`05` 源文件 `-**A-G**` grep=27） | **守恒** |
| 关系线 ξ | 左可选正则全文扫 + 行结构锚定**双法复算** = **456 / 12 种**（两法一致；含 malformed 13） | **守恒** |
| 全量登记 | 1322 = 349+853+120 = **闭合** | **守恒** |
| FK | `FOREIGN KEY`=**0**、`ADD CONSTRAINT`=**0**、Concept 节点=**0** | **守恒** |
| references_total | 逐行解析 = **499**（=514−16+1） | **属实** |
| queue | 逐条 = **110**（by_kind Σ=110：doc_line_uncertain12+self_loop_annotation5+same_name_divergent5+…） | **属实** |
| 全仓 pytest | graphrag/ **56 passed**；仓库根 **56 passed**（与自报一致） | **属实** |

⚠ **队列口径注记（非缺陷）**：HEAD 版 4 条 `same_name_divergent`（`goods_code`/`income_bill_id`/`inventory_org`/`sales_order_id`）因新增自环/存疑门**优先级更高**，被重分类为 `doc_line_uncertain`/`self_loop_annotation`；已核实余者作 src 的边均为**其它表**的正常命名解析（非该 divergent 项），**无一被静默连成边** → 队列净增 97→110 合理。

---

## 3. 多跳策略 — ✅ PASS

| 断言 | 独立实测 | 判定 |
|---|---|---|
| 默认 min_conf=0.45 | `load_graph_with_lineage` 默认参 `{min_conf:0.45, show_uncertain:False}`；`reference_traverse`/`l1.traverse` 默认 0.45 | **PASS** |
| 准入硬门 = 逐边 conf≥0.45 | 真实数据 5 种子×2 方向：逐边 conf<0.45 违例=**0**；合成加一条 0.3 低置信边 → 混入遍历=**0**（正确拒入） | **PASS** |
| 连乘仅作排序（不误杀长链） | 合成 3 跳链逐边 0.5：path_score=0.5→0.25→0.125（<0.45）**hop3 仍准入**（旧实现把连乘当准入门会砍掉 hop≥2）→ 证明修复到位。真实数据以 hop1 为主（path_score=边 conf≥0.45），故真实"准入 path_score<0.45"=**0** 属正常（非深链），违例=0 | **PASS** |
| ≤3 跳 / 超限截断可复现 | MAX_HOPS=3；`l1.traverse` `frontier_edges_beyond_budget` 记录、`truncated_at_budget` 有效；两次 `reference_traverse` records **完全相等**（确定性） | **PASS** |

---

## 4. Scope / 边界守恒 — ✅ PASS

- 代码/产物 grep `dam-app|dam_meta|PLAN.md|asset_urn`：**零命中**。
- `transform|etl|kpi|metric|Concept` 仅出现在 manifest `scope.excludes`（超本期范围声明），无实际越界实体。
- 无 `graphrag/community`、`semantic`、`nl` 目录 → **未越界建 Concept/社区/M4**。
- 新增边型集 = {REFERENCES, SUPPORTED_BY, EvidenceSrc}，无 Transform/Job/Flow/Metric。
- 逐边 `is_inferred=true`、`confidence≤0.95`（实测 max 0.90）、disclaimer 100% 携带"逆向推断、非物理外键；不含变换/ETL 级"。

---

## 5. P2 观察（不阻断，移交编排者）

1. **manifest 断言字段命名**：`checks[].{target,actual,ok}`（非 `expected`）；本会话取证脚本初次按 `expected` 取值致假 FAIL，改用 `target` 后 19/19 全 `ok=True`。属文档/工具键名注记，产物无缺陷。
2. **队列 divergent 重分类**：见 §2 注记，4 条 divergent 被更高优先级门吸收，语义正确但 kind 迁移使跨里程碑 kind 计数不可直接对齐 → 建议 manifest 增 `reclassified_from` 留痕便于纵向审计。

---

## 6. 放行结论

- **4 个 P1 全部真闭合**（独立实测：自环违例 0、边集存疑 0、中文命中 2/5、坏索引真抛错、`tables_with_column` 17 真表名）。
- **无回归、无新风险**：M1 六锚（483/349/27/456/1322/FK0）全守恒；references_total=499、queue=110、pytest=56 属实；两次跑测逐字节确定。
- **多跳/边界合规**：逐边硬门 + 连乘仅排序（长链不误杀）；无 ETL/指标/外部平台越界。

> **结论：准予过门。M1/M2 侧 P1 修复可安全提交；M3（社区/全局层）可放行**——进入社区算法的**默认可见 REFERENCES=440**（`confidence≥0.45`、低置信/存疑边已被 `load_graph_with_lineage` 默认门排除，入算法违例=0），满足 RUNBOOK M3 卡门 1 前置。
>
> **提交纪律提醒**（交执行专家/编排者）：本会话为取证**两次跑测**，`data/meta/lineage_*`、`review_queue.json` 已重生成且与提交前 md5 一致（无污染）；`l0_*` 依 C-10 不入库。业务代码/产物由对应执行专家自提，本报告仅由本 agent 提交 `graphrag/reports/eval-P1fix.md`。
