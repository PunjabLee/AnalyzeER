# eval-M4fix · 终轮 CodeReview **P1-2 消费侧/评测侧**整改验收 + M4 P1 修复后黄金集复验

- **被测态**：分支 `chore/rag`，HEAD `4111aea`（M4 提交点）。**M3/M4 的 P1 修复与本 agent 的 eval 整改均只存在于工作区，未提交**（见 §7）。
- **验收人**：`rag-eval-gate`（`graphrag/eval/**` + `graphrag/reports/` 唯一写入者；独立复算，不采信任何自报值）。出具时间 2026-10-05。
- **契约依据**：`er-model/graphrag/design-plan.md` §4.4；`agents/RUNBOOK.md` §4（路径所有权）/§A（事实基线）；`graphrag/spec/eval-baseline.md`（M0 冻结，阈值仍 `[待确认]`）；实物 `test_erp.sql` + `er-model/{00,03,05}`。
- **本轮范围**：仅任务 1（run_golden 双源消除）＋任务 2（消费点接指纹校验，strict-on-enable dormant）＋任务 3（黄金集复跑）＋任务 4（报告勘误）。**不改 M3/M4 业务代码**（`community/**`、`nl/**`、`semantic/**`、`data/meta/**` 零写入，见 §6 无污染证明）。

## 总体判定

**✅ P1-2 消费侧/评测侧整改 PASS（本 agent 自有产物）**；黄金集回归与 §4.4 数量等式**全项实测闭合**，剩余唯一 MISS 确为 **u1-03**（UC1 召回，非本轮路径）。

不阻断但**必须移交编排者**的 3 件事：①M3/M4 P1 修复代码仍未提交（本 agent 无写入权，只提交自有路径）；②1 项新发现 P2（`community_isolated_a.json` 落盘内容陈旧，§8-P2-①）；③RUNBOOK §A 台账（测试数 103→180、枚举 490→477）与 `graphrag/community/tests/test_m3_fingerprint.py` 中引用已删除函数的注释待各 owner 更新（§8）。

---

## 0. 实测总览（全部为独立实跑，非沿用产物自报）

| 项 | 实测命令 | 实测结果 |
|---|---|---|
| 全仓测试 | `python3 -m pytest graphrag -q` | **180 passed**（8.85s） |
| ├ 存量（= M4/M3 自报口径） | `--ignore=graphrag/eval` | **167 passed** ✅ 与自报 167 一致 |
| ├ 本 agent 新增回归锁 | `pytest graphrag/eval -q` | **13 passed** |
| └ 分模块 collect-only | ingest/lineage/community/semantic/nl/eval | **20 / 36 / 31 / 80 / 0 / 13** |
| 黄金集 | `python3 -m graphrag.eval.run_golden` | 见 §3（拒答 **8/8**、Top-3 **6/7**、综合 **12/13**、幻觉 **0**、意图 **21/21**） |
| 命中向量确定性 | 同进程两次 | `HIT_VECTOR = 110111111111111111111`（逐位一致） |

## 1. 任务 1 —— `run_golden` 双源**已消除**（改法与证据）

### 1.1 缺陷复述（终轮 CodeReview P1-2 点名）
旧 `run_golden.load_kept_pairs()` 读**磁盘** `graphrag/data/meta/community_edges.jsonl` 当耦合对回查判据，而答案走 `NLRouter.l2()` **实时**计算 → 双源。上游 M1/M2 重生成、门参数改动或算法改版而忘重跑 M3 时，"实时结论 vs 陈旧基准"会产生**两类静默失真**：假 FAIL（合法耦合被记成幻觉）与更糟的假 PASS（陈旧文件里恰好还留着那条臆造边）。

### 1.2 现判据（三档全实时；磁盘那份降为对照）
新增 `graphrag/eval/kept_source.py`（本 agent 唯一写入），`run_golden` 改为 `base = kept_source.resolve(router, ...)` 供给：

| 档 | 来源 | 角色 | 本次实测 |
|---|---|---|---|
| ①**权威** | 运行期只读探针捕获 **`router.l2()` 那一次** `build_subgraph()` 的 kept 记录（patch `communities` **与** `global_analysis` 两处命名空间，`finally` 还原；`router._l2` 若已缓存则先失效，保证捕获的就是答案实际使用的那一次） | 耦合对 `∈ kept` | `captures=1`；kept 记录 **821**、无向表对 **477**、`pairs_sha256=6ccc871c…434dc4` |
| ②自洽佐证 | 不借探针**独立再算**一次 `build_subgraph()` | 必须与①**集合相等**，否则 `SameSourceViolation` fail-fast | `agree_with_live=true`，摘要同① |
| ③**独立实物出处** | eval 自行从**原始上游**重推：M1 `l0_graph.json` 的 `RELATES_TO` 邻接 ＋ M2 `lineage_edges.jsonl` 的 `REFERENCES` 表级投影，门 `conf≥0.45 ∧ 非存疑 ∧ 两端 A 级有域 ∧ 非自环 ∧ 必带 file+quote_hash`（独立实现，不复用 M3 `ingest_edges`） | 防"聚合层自洽却无出处" | `agree_with_live=true`、`live_only=0`、`grounding_only=0`；记录 RELATES_TO **385** / REFERENCES **436**；有域 A 表 **349** |
| （对照） | 磁盘 `community_edges.jsonl` | `used_for_judging=**False**`，只报 drift | 821 行 / 477 对 / `file_sha256=9d8787ed…c64511`；**drift 0/0、identical_to_live=true** |

`provenance_check()` 的 GLOBAL 分支现产生三类可区分的违例名：`coupling_not_in_live_kept`（①不认）、`coupling_ungrounded`（③无实物边）、`coupling_no_provenance`（有边但无 file+quote_hash）。`load_kept_pairs()` 已删除。

**当前数据面上旧新口径结论相同（drift=0/0）**：本轮消除的是**静默失真通道**，不是既有数字差异——此点如实登记，不夸大为"发现 N 条陈旧"。

### 1.3 反事实取证（证明风险真实存在，已由 `graphrag/eval/tests/` 锁定）
| 场景 | 旧双源判据 | 新同源判据 |
|---|---|---|
| 磁盘缺 1 条**真实**耦合（模拟忘重跑 M3） | 记 1 条**幻觉**（假 FAIL） | 违例 **0**；同时 `drift_live_only=1`、`identical_to_live=False` → **不静默** |
| 磁盘被塞 1 条**臆造**对 `jf_customer↔jf_goods` | 违例 **0**（**假 PASS**） | 报 `coupling_not_in_live_kept` + `coupling_ungrounded` |

### 1.4 JSONL 的指纹承载（不得内嵌顶层键）
`community_edges.jsonl` 每行必须是纯边记录（M3 护栏 `test_m3_fingerprint.py::test_community_edges_jsonl_not_polluted`；实测顶层键仅 `src/dst/type/confidence/evidence_level/cross_domain/provenance`，`input_fingerprint` 出现率 **0**）。故由 **sidecar** 承载：`graphrag/eval/kept_pairs_sidecar.json`（我的自有路径，**无时间戳**、`sort_keys=True` → 逐字节确定；实测两次生成 sha256 相同 = `dc3cc71269c2a32eae9da7515697cf185fb5fa44c44a87476624d7d86d15f08a`，且 `graphrag/data/meta/` 内**无** sidecar）。sidecar 只作留痕/对照，**不替代**实时重建比对（②③为更强口径）。可用 `--no-sidecar` 关闭落盘。

## 2. 任务 2 —— 消费点接指纹校验（**strict-on-enable 保持 dormant**）

`kept_source.fingerprint_gate()` 对 eval 侧消费的 4 个 M3 JSON 产物逐一调用 `semantic.fingerprint.verify_json_file` / `extract_fingerprint`：`community_result.json`、`community_global_analysis.json`、`community_profiles.json`、`community_nmi_vs_baseline.json`。

| 机制 | 默认态（本次实测） | 激活方式 |
|---|---|---|
| **基准比对** `fingerprint_check`（dormant） | 4 产物均 `status=observed_no_baseline`、`baseline_declared=false`，观测 digest `43e78f082c25bc7528c3cb10d9f480298890eca7baee4d055883042ea1b55919`；`expected_baseline_declared=false` → **放行留痕，不判脏** | `export GRAPHRAG_EXPECTED_INPUT_FINGERPRINT=<digest>` 后自动转强制：一致→`ok`；不一致或字段缺失→`InputFingerprintMismatch`，**中断评测**（`run_golden` 进程 `returncode≠0`，stderr 含该异常名，已实测） |
| **陈旧门** `freshness`（**恒开**，不需基准） | 4 产物均 `fresh`：拿产物**声明的输入清单**（`graphrag/data/l0_graph.json` + `graphrag/data/meta/lineage_edges.jsonl`）逐文件独立重算 sha256 聚合比对内嵌 digest | 无需激活。声明输入被改动 → `_recompute_input_digest` 返回 `DRIFT:{file}` → 判 `stale_*`（已实测） |

- 实测注入**正确** digest → 全 `ok`；注入 `STALE-BASELINE` → 由 `NLRouter.l2()` 消费点抛 `InputFingerprintMismatch`、`python -m graphrag.eval.run_golden` 退出码 1。**本 agent 未擅自打开该门**（红线：渐进上线态由编排者/门禁决定）。
- **诚实登记 freshness 的局限**：M3 产物声明的输入清单**不含** `community_edges.jsonl` 自身，故"手工只改 edges JSONL"不会被 freshness 抓到——该路径由 ①②③实时同源 + drift 对照兜住（磁盘那份已不作判据，改动它不影响评测结论，只会让 drift 可见）。
- `community_isolated_a.json` 虽名为 `community_*`，但**不在 eval 消费路径**（`nl_router` 全程实时计算、不读 meta 文件），故未纳入门清单；其陈旧问题单列 §8-P2-①。

## 3. 任务 3 —— 黄金集回归复跑（M4 P1 修复后，与修复前对比）

| 指标 | 修复前（`eval-M4.md`，HEAD `fd65ee0`+M4 提交态） | **本次实测** | 变化 |
|---|---|---|---|
| 拒答正确率（8 题：6 边界 + 2 超范围） | 7/8 = 87.5%（MISS u6-03） | **8/8 = 100.0%** | ✅ **u6-03 已翻转**：实测 `refused=True, n_src=0`（旧为泄漏 1 条 Domain stub） |
| Top-3 严格命中率（metric=top3，n=7） | 6/7 = 85.7% | **6/7 = 85.7%** | 持平（唯一 MISS 仍是 u1-03） |
| 综合命中率（top3+reachable+coverage，n=13） | 12/13 = 92.3% | **12/13 = 92.3%** | 持平 |
| 幻觉（无出处/绕门/无据耦合） | 0 | **0** | 持平（判据由磁盘换为同源，结论不变） |
| 意图分类（端到端，不注入 intent） | 21/21 | **21/21**（`mismatches=[]`） | 持平 |
| `HIT_VECTOR` | `110111111111111101111` | **`110111111111111111111`** | 第 18 位 0→1（u6-03） |
| 分 UC（`per_uc_hit`，**仅非边界题**） | UC1 2/3、UC2 2/2、UC3 2/2、UC4 1/1、UC5 1/1、UC6 2/2、GLOBAL 2/2 | **逐项不变**（UC1 66.7%、其余 100%） | **无变化**——`per_uc_hit` 不入 `refused/oos_refused` 题分母（UC6 共 3 题，u6-03 属拒答口径），故 u6-03 翻转**只**反映在拒答率与 `HIT_VECTOR` |
| 题目规模 | 21 题 | 21 题 | 未动黄金集题目（本 agent 有权但本轮**未改题**，避免自挪达标线） |

**唯一 MISS 定性**：`[r["id"] for r in rows if not r["hit"]] == ["u1-03"]` —— 实测**仅剩 u1-03**（"哪些表存发票" → gold `jf_invoice` 未进 Top-3，实得 `jf_customer_invoice_information / jf_invoice_application / jf_invoice_application_log`）。属 manifest 已登记的 **UC1 召回探针**（R-5 语义/字形），**不在本轮 P1-2 路径**（本轮不动检索排序实现）。

**阈值纪律**：Top-3 `≥90%`、P95 `<2s/<8s`、综合/拒答达标线一律 `[待确认]`（M0 未定数值）→ 本报告**只报实测，不判达标**。

## 4. 时延口径变更（如实披露，非改进项）

P1-2 要求同源基准在评测**循环之前**就绪（探针必须包住 `l2()` 的那一次计算），故逐题时延不再含 M3 全局分析的一次性开销：

| 字段 | 修复前（eval-M4） | 本次 | 说明 |
|---|---|---|---|
| 单跳 P95 | 58.4ms | **60.8ms**（冷启动）/ 预热后 2.4–2.8ms | 见下行 caveat |
| 多跳·全局 P95 | 374.2ms | **2.2ms** | l2 已预热 |
| `l2_warmup_ms`（新增披露） | — | **427.86–438.25ms**（两次运行） | 一次性开销，从逐题时延中**移出** |
| `harness_total_ms`（新增披露） | — | **539.05–631.67ms** | 端到端整体 |

⚠ 两点不得美化：①本块三个字段均为**观测值**，跨运行随缓存冷热浮动（故单跳 P95 在 60.8ms 与 2.8ms 间摆动，命中向量与判定项不受影响）；②与 58.4/374.2ms 的历史数字**口径不可直接比较**。阈值仍 `[待确认]`。

## 5. §4.4 数量等式与锚守恒（独立复算，全部 ✅）

| 等式/锚 | 产物声称 | 独立实测（原始源） |
|---|---|---|
| 登记全量 = 1322 | A349 + B853 + C120 | `l0_graph.json` Table 节点 **1322**，tier 计数 **A349 / B853 / C120** ✅ |
| 图内可遍历主语料 = A 级 349 | 274 入图 + 75 孤立 | `build_subgraph()` 实时复算：nodes **274** + isolated **75** = **349**；sidecar `a_domain_tables=349` ✅ |
| 每域表数 = `00` 第四节 | 20 域 | Domain 节点 **20**、`BELONGS_TO_DOMAIN` 1202 ✅ |
| 入图 Issue 节点 = `05` 实测 27 | 27 | 图 `Issue` 节点 **27** == `05-跨域核心关系总览.md` 的 `^\s*[-*]\s+\*\*[A-G]-\d+` 列表项 **27**（A3+B4+C4+D5+E3+F4+G4）✅ |
| 源 DDL 锚 | 1322 / 0 FK / 11 无 PK | `test_erp.sql`：`CREATE TABLE` **1322**、`FOREIGN KEY` **0**、`PRIMARY KEY` **1311**（=1322−11）✅ |
| 结构边 | 483 / 483 / 11 | `RELATES_TO` **483**、`SUPPORTED_BY` **483**、`HAS_ISSUE` **11**、`Column` **6428** ✅ |
| M2 引用边 | 499 | `lineage_edges.jsonl` 499 行全为 `REFERENCES`；`review_queue.json` total **110**（M2） ✅ |
| M3 kept | 821 / 477 | 实时 **821** 记录 / **477** 表对，磁盘那份 drift **0/0** ✅ |
| M4 枚举 | 490 → **477** | accepted **477** + needs_review **0** + declined **13** = **490**（旧锚闭合） ✅ |
| 字段含义 | 6428=6331+97 | 逐列复算 **6428** = 有 COMMENT **6331** + 无 **97**；97 条 `meaning=null` 且 `source.status` 全标 `[待确认]`（未标 **0**） ✅ |
| 概念/叠加层 | 36 / 67 | `semantic_concept_graph.json` counts：concepts **36**（=33 located + 3 pending）、`REALIZED_BY` **67**、`SUPPORTED_BY` 67、evidence 节点 36；67 条目标表逐条 ∈ 图 `Table` 节点 → **悬挂 0** ✅ |

**关系/引用边两端存在性**：无悬挂边（除 `[待确认]` 者）；3 个 pending 概念（`jf_warehouse`/`颜色`/`jf_bloc`）经图查询确认**无同名表**故未绑 → 无臆造。

## 6. 有出处率 + 无污染（实跑复核）

- **有出处率 100%**：accepted 477 条 `source.file+quote_hash` 缺失 **0**、`raw_comment` 逐字 ∈ `test_erp.sql` 缺失 **0**；declined 13 条出处缺失 **0** 且全部显式 `[待确认]`（未标 **0**）；code_tables **29** / kv **4** 出处缺失 **0**；6331 条有 COMMENT 的字段含义 `quote_hash` 缺失 **0**。
- **无多位码截断**：1264 个值-义对（477 列）逐条要求"前后非数字 + 完整数字串"→ 截断违例 **0**；值键 >2 位 **0**；正则重放不一致 **0**。
- **无污染**：全量 pytest（180）+ `run_golden` 跑完后 `git status --porcelain graphrag/data` 与本轮开工前**同一组**（6 个 `M` + 1 个 `??`，均为 M3/M4 既有改动），说明 eval 侧执行**未写任何业务产物**；sidecar 内容跨进程逐字节一致。
- **范围边界**：本轮新增文件仅 `graphrag/eval/{kept_source.py, kept_pairs_sidecar.json, tests/}`；`semantic_layer_scope=structural`、`out_of_scope` 4 项声明在位；产物未混入变换/ETL 级血缘与指标语义层（`asset_urn`/`ETL` 仅出现在排除声明语境）。

## 7. 未提交确认（待编排者处置）

工作区实测（`git status --porcelain`，HEAD 仍 `4111aea`）：

- **他人路径（本 agent 无权、未提交）**：`graphrag/community/{communities,community_summary,global_analysis,nmi_vs_baseline}.py`、`graphrag/nl/nl_router.py`、`graphrag/semantic/{__init__,m3_p2_handoff,semantic_layer}.py`、`graphrag/semantic/tests/test_m4_semantic_nl.py`（均 `M`）；新增未跟踪 `graphrag/community/source_fingerprint.py`、`graphrag/semantic/fingerprint.py`、`graphrag/{community/tests/test_m3_fingerprint.py, semantic/tests/test_m4_fingerprint.py}`；`graphrag/data/meta/` 6 个 `M` + `semantic_concept_graph.json` `??`。
- **本 agent 自有（本轮提交）**：`graphrag/eval/run_golden.py`（M）、`graphrag/eval/kept_source.py`（??）、`graphrag/eval/kept_pairs_sidecar.json`（??）、`graphrag/eval/tests/{__init__.py,test_p1_2_consumption.py}`（??）、`graphrag/reports/eval-M4.md`（勘误）、`graphrag/reports/eval-M4fix.md`（本报告）。
- ⇒ **M3/M4 业务代码 + 其 P1 修复至今仍未入库**，须由 rag-orchestrator/相应 owner 走 `feat(rag)`/`fix(rag)` 提交；本 agent 仅提交自有路径。

## 8. 遗留问题清单（不阻断本轮，均需他人路径处置）

| 级别 | 项 | 实测证据 | 建议 |
|---|---|---|---|
| **P2-①** | `graphrag/data/meta/community_isolated_a.json` **落盘内容陈旧**：其 `fingerprint_check.status` 仍为 `absent_upstream_not_embedded`（称 M3 未嵌入），而当前 `community_result.json` 已嵌 `input_fingerprint=43e78f082c25…`；内存重算 `isolated_a_tables(write=False)` 现返回 `observed_no_baseline` + 该 digest | 该文件本身**未嵌** `input_fingerprint`（仅嵌 `fingerprint_check`） | 由 semantic owner 重跑 `python -m graphrag.semantic.m3_p2_handoff`（并考虑给该产物也嵌 `input_fingerprint`），否则"陈旧产物自述上游陈旧"会误导后续读者。本 agent 无写入权，**未改** |
| **P2-②** | `graphrag/community/tests/test_m3_fingerprint.py` 注释仍引用**已被删除**的 `run_golden.load_kept_pairs()` | 该函数本轮已从 `run_golden.py` 移除（双源消除） | 由 community owner 改写注释为 `graphrag/eval/kept_source.py`；测试本体不受影响（180 全绿） |
| **P2-③** | RUNBOOK §A 台账过期：测试数（M4 记录 103 → 现 **180 = 167 存量 + 13 eval**）、枚举（490 → **477+0+13**）、拒答（7/8 → **8/8**） | 本报告 §0/§3/§5 实测值 | 由编排者更新 `agents/RUNBOOK.md`（本 agent 不写） |
| **P2-④** | `declined` 13 条中 **2 条**为**同列混排**（`免收订金:1=是,0否`）：`1=是` 合码后分隔、`0否` 不合 → 现口径按**整列原子**判定故全列降级；其余 11 条系前导冒号 / 码后无分隔 / >2 位码（如 `0001=库存不足`） | 477/0/13；无"码后带分隔且 1~2 位却被误降"的纯误杀 | 属**保守可机读**口径（非缺陷）。若业务方要更高风险换取更多值域，可改为逐对判定（收益≈个位数），须 owner 决策并同步 `05`/`03` 文档口径 |
| 探针脆弱性（已缓解） | ①档探针依赖 `l2()→build_subgraph()` 调用链；`graphrag/community/__init__.py` 的 `from .global_analysis import global_analysis` 用**同名函数遮蔽子模块属性**，直接 `from ..community import global_analysis` 会静默 0 捕获 | 已改 `importlib.import_module()` 取模块对象 + `callable()` 守卫；0 捕获时抛 `SameSourceViolation`（不静默退回磁盘） | 建议 M3 owner 消除同名遮蔽（改名或 `__all__` 明示），否则任何"按属性名打补丁"的第三方都会踩同一坑 |

## 9. 独立复现命令

```
python3 -m pytest graphrag -q                      # 180 passed（= 167 存量 + 13 eval）
python3 -m pytest graphrag --ignore=graphrag/eval -q   # 167 passed（与 M4 自报口径对齐）
python3 -m pytest graphrag/eval -q                  # 13 passed（P1-2 同源/门/回归锁）
python3 -m graphrag.eval.run_golden                 # 逐题表 + 同源基线 + HIT_VECTOR
python3 -m graphrag.eval.run_golden -q --no-sidecar # 仅 HIT_VECTOR（确定性校验）
GRAPHRAG_EXPECTED_INPUT_FINGERPRINT=43e78f082c25bc7528c3cb10d9f480298890eca7baee4d055883042ea1b55919 \
  python3 -m graphrag.eval.run_golden -q            # 激活态：全 ok（strict-on-enable 生效）
GRAPHRAG_EXPECTED_INPUT_FINGERPRINT=STALE \
  python3 -m graphrag.eval.run_golden -q; echo $?   # 激活态：InputFingerprintMismatch，退出码 1
```

## 10. 判定

| 校验维度 | 结论 |
|---|---|
| §4.4 数量等式（1322 / A349 / 27 Issue / 483 / 821·477 / 477+0+13 / 6428=6331+97 / 36 / 67 悬挂 0） | **PASS**（全部独立复算闭合，无近似值） |
| 有出处率（关系类 100% 可回溯；无证据者显式 `[待确认]`） | **PASS**（缺失 0；97 + 13 + 3 pending 全显式标注） |
| 检索质量（黄金集，M4 P1 修复后） | **实测达标线仍 `[待确认]`**；数值较修复前**不退步且拒答补齐至 8/8**，唯一 MISS 仅 u1-03 |
| 多跳预算（≤3、超限截断可复现） | **PASS**（`max_hops=100` 钳制到 3；reachable 口径逐题带 hop 断言） |
| 社区对照（输入边 `conf≥0.45`；涌现簇 vs 18 域差异可解释） | **PASS**（承 eval-M3；本轮复核 kept 门与 9 混合簇降级未被 P1 修复破坏） |
| 范围边界（无 ETL 级血缘 / 指标语义层混入） | **PASS** |
| **P1-2 消费侧整改（本轮任务）** | **PASS**（双源消除 + 三档同源 + sidecar + dormant 指纹门 + 恒开 freshness） |

**→ 本 agent 自有产物过门；整体交付仍待编排者完成 §7 的业务代码入库与 §8 的 4 项 P2 处置。**
