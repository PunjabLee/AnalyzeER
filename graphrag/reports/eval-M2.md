# eval-M2 · GraphRAG M2（字段级引用/结构级血缘）独立验收报告

- **判定**：✅ **PASS（全项过门，无 P0/P1；4 项 P2 注记不阻断）**
- **分支/HEAD**：`chore/rag` @ `f3586ce`（基点 `4a5485a`，未合 `master`）
- **被审产物**（未提交工作区）：`graphrag/lineage/`、`graphrag/data/review_queue.json`、`graphrag/data/meta/lineage_edges.jsonl`、`graphrag/data/meta/lineage_manifest.json`
- **契约依据**：`graphrag/spec/`（M0 冻结）+ `er-model/graphrag/design-plan.md` v2 §4.4/§2.3/§5.1-5.2/§6 + `agents/RUNBOOK.md` M2 验收门 1–5
- **方法**：全部独立实测——`pytest` 实跑；校验脚本 `.tmp/m2_verify.py`（gitignored）对 `lineage_edges.jsonl` **逐行原始解析 + 对 M1 快照 `l0_graph.json` 实物节点集逐边端点回查 + 自写 DDL 扫描独立复算 quote_hash**；经 `load_graph_with_lineage()` 消费复跑 UC 遍历双跑比对。**未复用 `build.py::_assertions`、未采信 builder manifest 自报数**（M1 移交技术债「`rel_dangling` 恒真死护栏」教训，`eval-M1` §5）。

---

## 1. 门禁逐项判定（阈值均实测；`[待确认]` 项保持不设标）

| # | 验收门（RUNBOOK M2 / 任务书） | 判定 | 独立实测值（非自报） |
|---|---|---|---|
| 0 | pytest 全仓：M2 用例含且 M1 不破 | **PASS** | `python3 -m pytest` = **37 passed**（`ingest/tests/test_m1.py` 15 + `test_loader.py` 1 + **M2 21**，单独跑 21 passed）；`git diff HEAD --name-only`=0，`ingest/store/search` M1 代码零改动 |
| 1 | **REFERENCES 两端存在，悬挂=0**（逐边回查） | **PASS** | 514 边逐边对 `l0_graph.json` 实物节点集回查：**dangling=0**（src∉图=0，dst∉图=0）；消费入口合并图 `adjacency_skipped_dangling` 语义不复用，改自算，结果一致 |
| 2 | `is_inferred=true` / 五级 / ≤0.95 / 0 FK 无约束级 / 有出处 100% | **PASS** | is_inferred **514/514=true**；evidence_level 实测 `comment_explicit 28 / index_backed 116 / name_inferred 302 / semantic_inferred 62 / unconfirmed 6`（=自报），无 `explicit_fk/physical_fk/constraint` 级；confidence **max=0.90 ≤0.95**、无 1.0；每级恒值落 design-plan 初设带内（0.9/0.775/0.575/0.325/0.1，越带=0）；**有出处率 100%（深度复算）**：① 174 条 DDL 边——自写 `CREATE TABLE` 块扫描定位列定义行，`sha1[:16](压空白行文本)` **逐条重算 hash 全匹配**，line_hint 与物理行号 0 错配，锚点行 0 缺失；② 340 条文档边——复用 M1 EvidenceSrc 哈希（`evidence.py` 设计），quote_hash **340/340 均命中 M1 图节点集**，且对应物理行回查 100% 含真实关系线/quote、file 存在率 100%；evidence 四要素（file/table/column/quote_hash）缺失=0，`supporting_evidence` 缺失=0，line_hint 覆盖 **514/514**（尽力字段全齐） |
| 3 | 多态/同名异指向**入队而非强建边** | **PASS** | `flow_change_record.related_order_id`：队列 `polymorphic_fk`，`discriminant=order_type`、status `[待确认]`，**出边=0**；`jf_trader.category_id`（双真实表）：队列 `same_name_or_typo_ambiguity`，**出边=0**；第 2 条多态 `jf_trial_cut_customer_mapping.other_id` 亦在队；队列 97 项 (table,column) 与 514 边 src 列**交集=0**（无武断连通）；`same_name_divergent` 9 条（05 B-4）为留痕项，其同名列已建边的 dst **全部 ⊆ 各自 resolved_targets（越界=0）**，逐条带上下文证据（comment/doc/index），未连「最近」表 |
| 4 | 外部引用（crm/oa 码）**不建本库边** | **PASS** | 边 `external_reference=true` = **0**；src/dst 列名含 `crm`/`oa` 码的本库边 = **0**；队列 `external_reference` = **21** 条（D15 `crm_complaint_code` 等），全部 `[待确认]` 不建边 |
| 5 | **M1 锚守恒**（RELATES_TO 483 / A349 / Issue27 / ξ=456） | **PASS** | `l0_graph.json` 实物计数：RELATES_TO=**483**、Table 节点=1322（tier **A349/B853/C120**）、Issue=**27**、全边 9498、SUPPORTED_BY=483——与 `eval-M1` 基线全等；`01-ER图` census §3 冻结正则复扫 ξ=**456**；DDL 复测 `FOREIGN KEY`=**0**、`ADD CONSTRAINT`=**0**、`PRIMARY KEY`=**1311**；合并后 RELATES_TO 不变，SUPPORTED_BY 483→997（**+514 仅增不减**）；`spec/`、M1 代码/meta `git diff` 零改写 |
| 6 | **边界守恒**：无变换/ETL 数据流级血缘、无指标层 | **PASS** | M2 新增边型集合={**REFERENCES, SUPPORTED_BY**}（无 Transform/Job/Flow/Metric 型）；新增节点 label={**EvidenceSrc:99**}（无指标/术语节点）；514 边 `lineage_scope` 全量=`reference_or_structure_only`；产物 JSON 枚举值 grep `transform|etl|aggregat|kpi|metric|dashboard` **零命中**（仅 manifest/队列 disclaimer 中的**排除声明**文本）；`sum(...)` 命中均为 Python 内建函数与表名 `flow_change_record`，非计算链；答案侧 disclaimer 100% 携带「逆向推断、非物理外键；不含变换/ETL」 |
| 7 | **UC4/UC5**：`jf_sales_order` 上/下游可复现、conf≥0.45、≤3 跳、truncated 可复现 | **PASS** | `load_graph_with_lineage()` 实跑：`jf_sales_order.id` 下游 reached **17 列/17 表/17 边**、`customer_id` 上游 **1**、表枢纽 pivot seed **2**→reached **20**、profile outgoing **7**——与 manifest 自报全等且**双跑 deep-equal 一致**（确定性）；默认参数实测 `CONFIDENCE_DEFAULT_MIN=0.45`、`MAX_HOPS=3`（`ingest/config.py:37-38`）；默认阈值下路径边 conf≥0.575、路径加权分≥0.45（0.45 以下=0 条）；阈值敏感性：min_conf=0 时 pivot 下游 27 边 vs 默认 20 边（低置信 7 条被正确过滤，含 `customer_code→jf_customer.id` semantic 0.325）；**截断可复现**：全图列级下游实际最深层=2 跳（表枢纽扫描 `jf_proofing_number`），默认预算下 `truncated_at_budget=0` **属实**；合成预算 `max_hops=0` 对 `jf_customer.id` 得 `truncated=61` 双跑一致、`max_hops=1` 预算硬约束生效（reached=61=hop3 值）→ 截断逻辑可用，非死计数 |
| 8 | 提交口径：可提交/忽略面正确 | **PASS** | `git check-ignore`：`data/meta/lineage_edges.jsonl`、`lineage_manifest.json`、`data/review_queue.json`、`lineage/*.py` **均未被忽略（可提交）**；`l0_graph.json`/`l0_edges.jsonl`/`l0_index.db`、`__pycache__/*.pyc`、`/.tmp/`、`graphrag/out/**`（含 `out/lineage/` 本地镜像）**全部命中忽略规则，不会被卷入提交**；`git status` 仅 4 项预期未跟踪产物，无杂散脏文件 |

---

## 2. 「514 边 / 97 队列」是否属实

**属实。** 独立计数：`lineage_edges.jsonl` 有效行 **514**（合并图内 REFERENCES 亦 514，`load_graph_with_lineage` meta 回报 514）；`review_queue.json` `items` 实长 **97** = 抽取器 84（polymorphic_fk 2 + external 21 + target_unresolved 51 + typo_ambiguity 1 + same_name_divergent 9）+ M1 承接 carryover 13（pending 4 + derived 9）；by_kind 实测与 manifest **逐项全等**。默认可见口径：`confidence≥0.45` = **446**（514−62 semantic−6 unconfirmed=446，与自报一致）。

## 3. 与 builder 自报的差异

**数量零差异**（15 项 manifest 断言/统计经独立复算全部吻合，含 anchors_conserved 与 uc_samples）。两处**口径注记**（非差异，见 §5 P2）：`self_references=10` 实物为**同表列→列引用** 10 条（如 `jf_company.sup_id→jf_company.id`），src==dst 的严格自环=0；340 条文档边 JSON 内 `quote` 展示文本 ≠ 哈希对象（哈希对象为 M1 四元组拼接串）。

## 4. 结论与放行

M2 **准予过门**，可进入提交环节（`feat(rag): M2 …` 由 lineage-builder 自提；RUNBOOK 状态位由编排者更新）。M3 可直接只读消费 `load_graph_with_lineage()`（输入边置信过滤口径 446 条默认可见已验证）。

## 5. P2 注记（不阻断，移交编排者裁量）

1. **P2-1** `manifest.self_references` 建议改名 `same_table_references` 并注口径「同表列→列，非 src==dst 自环」，防下游误读。
2. **P2-2** 340 条文档边 `evidence_src.quote` 为 M1 关系线**描述子串展示**，而 `quote_hash` 沿用 M1 `left|conn|right|desc` 四元组哈希（`evidence.py` 设计、避免双源分叉）——建议在 manifest `calibration` 增注该哈希口径，防后续审计者以 `hash(quote)` 复核时误判失配（本次已按 M1 节点回查 340/340 闭合）。
3. **P2-3** 交付路径 `data/meta/lineage_*` 与 RUNBOOK M2 卡声明的 `data/references_*` 命名有偏移（沿袭 M1 实际交付 `data/meta/` 的同一迁移口径，manifest `outputs.committed` 已留痕）——请编排者在 RUNBOOK 状态节确认命名迁移，避免 §4 单一写入者路径表与实际漂移。
4. **P2-4** 队列 9 条 `same_name_divergent` 留痕项无 `evidence.quote_hash` 对象（仅 reason 引 `05` B-4 文句）；其为「非断言的复核登记」不违有出处率口径（断言侧=边，100% 已证），建议补 `doc_ref:"er-model/05 §B-4"` 结构化字段统一回查。

**多跳/检索阈值纪律**：`≤3 跳`、`Top-3 ≥90%`、P95 时延等 design-plan 标 `[待确认]` 项一律**保持 [待确认]**，本报告只记录实测（列级下游实际最深 2 跳；黄金集非 M2 门，`graphrag/eval/golden/` 仍未落地，承 RUNBOOK M2 状态② 由 eval-gate 前置——不据此判 FAIL）。

---

### 附：关键复算命令（可重放）
```bash
cd graphrag && python3 -m pytest -q                       # 37 passed
python3 .tmp/m2_verify.py                                 # 本报告全部逐边实测（脚本 gitignored）
cat er-model/01-ER图/*.md | grep -oE '(\|\||\|o|\}o)?(--|\.\.)(\|\||o\||o\{|\|\{)' | awk '{s+=$1}END{print s}'   # ξ=456
grep -ciE 'FOREIGN KEY' test_erp.sql                      # 0
git check-ignore graphrag/data/meta/lineage_edges.jsonl graphrag/data/review_queue.json ; echo $?  # 1=可提交
```

> 验收人：rag-eval-gate（独立硬校验，不采信自报）。本报告为 M2 过门可回溯凭证。
