# eval-M3 · GraphRAG M3（L2 社区/全局分析层）独立验收报告

- **里程碑**：M3 · `graphrag/community/` + `graphrag/data/meta/community_*`（未提交态，分支 `chore/rag`）
- **验收人**：`rag-eval-gate`（独立复算，不采信自报；本报告出具时间 2026-10-05）
- **契约依据**：`er-model/graphrag/design-plan.md` §4.4/§5.2/§8.1(M3)、`agents/RUNBOOK.md` M3 卡 + 移交要点 1–4、`graphrag/spec/`（M0 冻结，本轮零改动，`git diff HEAD` 空）
- **底座**：M1（RELATES_TO 483 / A349 / Issue27 / Σ1322）+ M2 含 P1 修复轮（REFERENCES 499 / 默认可见 440 / 队列 110）
- **总体判定**：✅ **全项 PASS，过门**（附 3 项 P2 非阻断改进项）→ **准予提交，可转 M4**

---

## 0. 测试实跑（不采信自报）

| 项 | 自报 | 实测 | 结论 |
|---|---|---|---|
| 全仓 `pytest graphrag` | 75 | **75 passed**（`ingest/tests` 20 + `lineage/tests` 36 + `community/tests` 19） | ✅ 一致 |
| M3 模块测试 | 19 | **19 passed**（0.55s） | ✅ 一致 |

环境实测：Python 3.14.6 / networkx 3.7 / pytest 9.1.1。

---

## 1. 入算法低置信/存疑边 = 0（RUNBOOK M3 硬门 ①）——**PASS**

**方法**：不经 builder 代码，直接从原始文件 `graphrag/data/l0_graph.json`（RELATES_TO 483）与 `graphrag/data/meta/lineage_edges.jsonl`（REFERENCES 499）按合同语义（§5.2-1 + M3 卡：conf→存疑→跨级→自环 顺序硬门）独立重放分类流水线（脚本 `_m3_gate.py`，根目录 `_*` gitignore 临时产物）。

| 校验点 | 自报 | 独立复算 | 结论 |
|---|---|---|---|
| RELATES_TO 低置信排除（conf<0.45） | 64 | **64**（=semantic_inferred 57 + unconfirmed 7，conf 均 ≤0.325） | ✅ |
| REFERENCES 低置信排除 | 59 | **59**（499−440，独立可见集=440 复算吻合 M2 锚） | ✅ |
| 存疑排除（conf≥0.45 但带 `has_uncertain`/`unconfirmed`/原文「待确认」） | 7 | **7**（全在 REL 侧；REF=0——M2 P1-2 起存疑只入队不建边） | ✅ |
| 跨级（B 端点）排除 | 13 | **13**（REL 侧；REF=0） | ✅ |
| 自环排除 | 18 | **18** = REL 14 + REF 4（REF 4 条均带 `explicit_pair`，与 P1-1 修复口径一致） | ✅ |
| **入算法边集**（`community_edges.jsonl` 821 行）| leak=0 | **违例 0**：逐条复核全部 conf≥0.45、非存疑、非 unconfirmed、非自环、两端均 A 级有域表 | ✅ |
| kept 集合全字段比对 | 821 | 独立重放所得 821 条 (src,dst,type,conf,evidence,file,quote_hash) 七元组集合 **==** 产物集合（逐条相等） | ✅ |
| 存疑排除是否留痕 | — | `community_result.json.ingestion.excluded.uncertain_excluded_sample` 7 条，抽 1 条（`bd8a6ef4ede4ae58`，`jf_customer→jf_quality_compensation` 0.575）回查 l0 原始边：`has_uncertain=true` 且 raw_desc 含「待确认」，属实 | ✅ |

**与 M2 移交口径对齐**：喂入 REFERENCES 基线 = 默认可见 440（非旧 446）；产物 `edge_gate` 六字段独立复算全等（499/440/59/0）。

## 2. 不新建来源外实体（§4.2/R6 + M3 硬门 ③）——**PASS**

- **边级回溯**：`community_edges.jsonl` 821 条逐条以 `(type, src表, dst表, quote_hash)` 反查 M1 l0 边集 / M2 lineage 原始边集，**无回溯 = 0**；出处文件 100% ∈ {`er-model/*.md`, `test_erp.sql}`（允许来源，`Agents.md` §0），违例 0。
- **节点级回溯**：社区成员/画像成员/枢纽 Top10/耦合样本表对全部为 M1 实物 A 级 Table 节点（`kept 端点均为 M1 实物表`、`画像成员⊆A级实物表`、`枢纽 TopN 全为图内实物表`、`耦合表对全部为实物边` 四项脚本断言全过）；枢纽结论逐条带 `quote_hash` 样本边出处。
- **LLM 面**：`summarizer.py` 为 stub——`ENABLED=False`，两入口直接 `raise NotImplementedError`；全 `community/` 代码 grep 无 `openai/anthropic/requests/urllib/http`，产物 JSON 无任何自由文本生成；确定性 map-reduce 结论（`global_analysis.py`）为纯结构化字段。未偷偷调 LLM ✅。
- **B-4 假耦合防线**：REF 侧 `polymorphic=true` 边 = 0 条入 kept（多态候选在 M2 即为 unconfirmed 低置信，被 conf 门拦截）；`external_reference` 边未在输入集出现。

## 3. NMI 与 00 权威分组守恒（M3 硬门 ②）——**PASS**

**独立复算**（sklearn 本机不存在，用第二套独立手写实现交叉验算，非复用 builder 函数）：

| 指标 | 产物自报 | 独立复算 | 结论 |
|---|---|---|---|
| 社区数（greedy_modularity, weight=Σconf, resolution=1.0） | 17 | **17**；规模分布 `{2:3, 3:3, 5:1, 6:2, 11:1, 16:1, 29:1, 32:1, 34:2, 42:1, 44:1}` 全等 | ✅ |
| 分区成员 | — | 独立重跑 17 簇与产物**逐成员排序后逐一对齐** | ✅ |
| 融合图 | 274 节点 / 477 表对 | **274 / 477** | ✅ |
| NMI(arithmetic, 274 节点, 17 簇 vs 19 组) | 0.59049 | **0.590490**（diff<1e-6）；geometric 0.592354、max 0.547115 同步交叉验算一致 | ✅ |
| 对照节点集守恒 | 274+75=349 | **入图 274 + 孤立 75 = 349** | ✅ |

**00 分组未被改写**：
- `00-总览与分组清单.md` §四/第 2 批行解析得 19 组声明 `D01..D18+OT(17)`，与图节点 `domain` 实测计数**逐域全等**（D14:34, D04:33, D09:31, D08:27, D10:24, D07:23, D02:21, D11:21, D03:19, D01:18, OT:17, D13:16, D06:15, D05:13, D12:11, D16:10, D15:6, D17:6, D18:4），Σ=349 ✅；
- 全部产物标 `community_role=analysis_view_only`、`authoritative_domain_source=00 §四`；l0/M2 上游文件 `git diff HEAD` 零改动；NMI 模块仅读 domain 不改写；verdict 诚实标注 `low(<0.6)` 档，差异簇（19 拆 / 9 混，跨域边 262 条）附加权度/betweenness/桥接表量化线索与「不覆盖 00 域归属」声明。
- ⚠ **P2-A（非阻断）**：**75 张孤立 A 表名单未落盘**——`_export()` 将 `isolated_a_list` 从 `community_result.json` 过滤，仅存计数 75 与 UC6 每域 `isolated_tables` 分布；名单可确定性复算（本次已复算吻合），但「单列名单」建议补写入产物（如 `community_isolated_a.json`）。

## 4. 依赖红线与算法实证（M3 硬门 ⑤ / 移交要点 1）——**PASS**

- 实测环境：`python-louvain(community)`/`igraph`/`leidenalg`/`sklearn` **均未安装**（importlib find_spec 全 absent）→ 未 `pip install` 新依赖；`graphrag/` 无 `requirements/pyproject/Pipfile` 新增。
- 实际所用 = **`networkx.algorithms.community.greedy_modularity_communities(weight='weight', resolution=1.0)`**，manifest/`__init__`/代码 import 三处一致声明；Louvain/Leiden 仍留 `stack-options.md` [待确认] 候选——符合 C-2b/N-3 与移交要点 1。
- community 模块 import 面：stdlib + networkx + 包内相对导入，无越界。

## 5. 确定性可复现——**PASS**

- 3 次 fresh 进程全量跑（社区 + NMI + 全局分析 + 画像）输出 JSON **字节级一致**（sha256 `2243e4…c232442` ×3）；`detect_communities` 结果排序规则（min 成员名）+ 稳定打破键（表名）消除序数歧义；betweenness normalized 无随机采样。
- 落盘产物与 fresh 跑逐字段相等（`community_result/nmi/global/profiles` 四文件 == 内存重跑，非陈旧快照）；`data/meta` 与 `out/community` 镜像 `cmp` 全等。
- `greedy_modularity_communities.__module__` 前缀 = networkx（测试 + 本报告双重确认）。

## 6. 数量等式与锚守恒（§4.4 全链复核）——**PASS**

图内可遍历主语料 A349（含 OT17）=349 ✅；登记全量 1322 = 349+853+120 ✅；每域表数 = `00` §四声明 ✅；入图 Issue=27（=05 实测）✅；RELATES_TO=483、REFERENCES=499/440/59、队列=110 与 M2 P1 修复后真值全等 ✅；悬挂边：kept 821 两端均实物节点，0 悬挂 ✅；`FK=0` 免责声明在各产物头部保持（「逆向推断、非物理外键」）。

## 7. 范围边界——**PASS**

- 社区产物 grep `transform/etl/metric/kpi/指标/计算口径` = 0 混入；输入 REFERENCES 全部 `lineage_scope=reference_or_structure_only`；未承诺指标语义层。
- UC6 首轮**未出现任何「命中率/达标/Top-3」宣称**（产物与代码 grep 均 0），阈值保持 `[待确认]`（对齐 `eval-baseline.md` §2.2）——符合移交要点 4。⚠ **P2-C**：黄金集 `graphrag/eval/golden/` 仍未落地（唯一写入者=本 agent），UC6 实测命中率无基线可报 → **M4 过门前由 eval-gate 落地题目集并补测**。
- ⚠ **P2-B**：`out/community/uncertain.md` 未产出——M3 自身无新队列项（7 条存疑已记录于 `uncertain_excluded_sample` 且未动 M2 队列，合规），但 NMI=low 档下 9 个混合社区的「解释」目前为量化线索 + 模板句，建议补一份声明文件把混合簇统一标注为**「对照观察（非发现物）」**或逐簇给出个性化论证。
- 信息项 P2-D：M3 测试 `test_exclusion_counts_registered` 硬编码自报数（64/59/7/13/14/4）——本次独立复算**全等**，但其性质是回归锁而非独立证明，保留即可。

---

## 8. 与 builder 自报差异清单

| 项 | 自报 | 独立实测 | 差异 |
|---|---|---|---|
| pytest 全仓 / M3 | 75 / 19 | 75 / 19 | 无 |
| 社区数 / NMI(arith) | 17 / 0.59 | 17 / 0.590490 | 无 |
| 排除计数 REL64/REF59/存疑7/跨级13/自环18 | 同 | 64/59/7/13/14+4=18 | 无 |
| kept 821 / 入图 274 / 表对 477 / 孤立 75 | 同 | 821/274/477/75 | 无 |
| 入算法低置信/存疑 | 0 | 0（独立逐边复核+集合级全等） | 无 |

## 9. 判定与回退建议

**✅ PASS——过门。** 六项硬门（置信硬门、不新建实体、NMI 对照守恒、依赖红线、确定性、边界守恒）全部以原始数据独立复算闭合，与自报零差异。
**准予 M3 提交**（`graphrag/community/` + `graphrag/data/meta/community_*`；`out/community/`、`l0_*` 等依 C-10/`.gitignore` 不入库），**可委派 M4**。
非阻断改进（M4 交付一并处置，无需重过 M3 门）：P2-A 孤立 75 表名单落盘；P2-B `uncertain.md`/混合簇降级声明；P2-C 黄金集落地后补 UC6 实测命中率（阈值仍 `[待确认]`）。

**独立复现命令**：`cd graphrag && python3 -m pytest -q`；`PYTHONPATH=. python3 _m3_gate.py`（根目录 gitignore 临时脚本，本报告的实测证据载体）。
