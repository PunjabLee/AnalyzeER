# GraphRAG M1 验收报告（rag-eval-gate 独立门禁）

- **里程碑**：M1 · L0 属性图装载 + L1 确定性检索
- **被审产物**：`graphrag/{ingest,store,search}` 代码 + `graphrag/data/{l0_graph.json,l0_edges.jsonl,l0_index.db,meta/*.json}`（均**未提交**，工作树 `chore/rag`）
- **契约依据**：`graphrag/spec/`（M0 冻结，已提交 `7074698`，含 456/12 关系线全量定稿）+ `er-model/graphrag/design-plan.md` v2 §4.4 + `agents/RUNBOOK.md` M1 门
- **验收方法**：独立 grep/wc/python 复算 + 直接读取导出产物做结构审计，**不采信 loader 自报值、不沿用 pipeline 自断言**；一切以 `er-model/*` + `test_erp.sql` 实物为准。loader 代码仅作为被测对象，源文件事实由本门禁独立正则复算。
- **总体判定**：✅ **PASS（过门）** — 六项验收门全部通过；loader 自报数字与实物**零差异**；数量等式闭合、边无悬挂、有出处率 100%、L1 端到端可用、边界守恒、两项处置裁决支持 loader 建议。

---

## 0. 结论速览

| # | 验收门（RUNBOOK M1 / §4.4） | 判定 | 本门禁独立实测 |
|---|---|:--:|---|
| 1 | 数量等式闭合 | ✅ PASS | 1322=A349+B853+C120；traversable A=349；每域=00声明；Issue=27；无PK=11；关系线 456/12（子 443/10/malformed13）；05=33 |
| 2 | 归级顺序 N-2 | ✅ PASS | 先剔 `_bak_`/`test_`→C 再前缀归 A/B；A=**349** 非 351（反证 jf 名串 334 / lcap 501 均含 bak） |
| 3 | 关系线解析 | ✅ PASS | 左可选正则匹配；cardinality/evidence/via 取描述文本；13 malformed 入图带标记、cardinality=None 不反推 |
| 4 | L1 检索可用 | ✅ PASS | UC1/UC2/UC3 端到端；默认 conf≥0.45；多跳≤3 截断可复现；默认不含 C |
| 5 | 边完整性 / 推断标注 | ✅ PASS | 悬挂边=0（实测逐边端点回查）；RELATES_TO 全 is_inferred=true；最高 comment_explicit（0 FK）；有出处率 483/483=100% |
| 6 | 合并行拆回 ⊳ DDL 交叉 | ✅ PASS | A 级逐表 column_count==Column 节点数（0 失配）；jf_sales_order=90 独立复算吻合 |
| ① | l0_index.db 入库裁决 | 支持不入库 | 二进制可确定性重建 → 建议不入库，仅源码 + JSON 快照 |
| ② | data/meta 镜像可提交裁决 | ✅ 确认可提交 | 未被 gitignore；轻量机读元数据（schema §5/R-4 核心交付） |

---

## 1. 数量等式闭合（§4.4-1）— PASS

**独立复算（本会话，非 loader）**：

| 断言 | 契约目标 | **本门禁实测** | 取证（独立命令） |
|---|---|---|---|
| DDL 表数 / 登记全量 | 1322 | **1322** | `grep -ic 'CREATE TABLE' test_erp.sql`；去重表名 `grep -ioE … \| sort -u` 亦 **1322** |
| 图内 Table 节点 | 1322 | **1322** | `l0_graph.json` labels：Table=1322 |
| A / B / C | 349/853/120 | **349/853/120** | 图 Table tiers：`{'B':853,'C':120,'A':349}`，Σ=1322 |
| 图内可遍历主语料 A(expanded) | 349 | **349** | tier==A ∧ expanded==true 计数=349 |
| 每域表数=00声明==枚举 | D01..D18+OT | **19/19 全 OK** | 独立解析 `00` §四：18+21+19+33+13+15+23+27+31+24+21+11+16+34+6+10+6+4=**332**，+OT17=**349** |
| 入图 Issue | 27（非 30） | **27** | `05` §三独立枚举：A3+B4+C4+D5+E3+F4+G4=27，唯一 ID=27；图 Issue 节点=27 |
| 无 PK 表 | 11 | **11** | 图 has_pk=false 表=11，逐名命中 `00` §8.3（9×`jf_*_import`+`jf_contract_termination`+`jf_inventory_organization`）；DDL `grep -cE '^\s*PRIMARY KEY'`=1311 → 1322−1311=11 |
| 显式外键 | 0 | **0** | `grep -ciE 'FOREIGN KEY'`=0、`ADD CONSTRAINT`=0 |
| 关系线全量（口径 ξ） | 456/12 种 | **456/12** | 双法复算一致：全文 `-o` 扫描=456/12；行结构锚定=456/12 |
| ├ 子口径 ξ′（6 字符合规） | 443/10 种 | **443/10** | 长度过滤复算=443/10 |
| └ malformed（左缺失） | 13 | **13** | `..o{`×11 + `..o|`×2，行锚点 D02/D05/D06/D08 |
| `05` 核心关系线 | 33（全 `\|\|..o{`） | **33** | 行结构锚定：33 条全 `||..o{`（0 变体） |

**loader 自报 vs 本门禁**：**零差异**（`data/meta/assertions.json` 40 项 target==actual 与独立实测逐条吻合；`l0_manifest.json` graph_summary/tiering/relations/issues 各项与实物一致）。

> 附：`pipeline` 断言 `rel_dangling(structural)` 恒 0 系**该字段未被 build_graph 填充**（形同虚设的自断言），本门禁**不采信**，改以逐边端点回查独立证否（见 §5）；实质结论不受影响。

---

## 2. 归级顺序 N-2（防 A 虚增 351）— PASS

**取证（独立 grep）**：
- `_bak_` 表 117 张，**全部**匹配 `_bak_<14 位时戳>$`（`grep -ioE 'CREATE TABLE .?…_bak_[0-9]{14}\`?'`=117）；`test_` 前缀 3 张 → C 级=120。
- 反证「若前缀优先」将虚增：`jf_` 名串=**334**、`lcap_` 名串=**501**；其中 `jf_*_bak_`=**2**、`lcap_*_bak_`=**51**（无前缀 bak=64）。
- loader `tiering.classify_tier` 顺序正确：`is_c_level()`（先）→ B 前缀 → A。产物实测 A=349（=334−2 jf_a=332 + ot17），**非 351**；C.bak=117、C.test=3 与 A/B 无重叠。

**结论**：N-2 铁律落地正确，A 级未被 `_bak_` 污染。

---

## 3. 关系线解析 — PASS

**取证（读 `l0_edges.jsonl` 内 RELATES_TO 独立审计）**：
- **连接符匹配**：正则 = census §3 冻结的**左基数可选**式 `(?:\|\||\|o|\}o)?(?:--|\.\.)(?:\|\||o\||o\{|\|\{)`，行结构锚定「实体 连接符 实体 : "desc"」防截尾假阳性（census §2.1 陷阱）。实测 456/12 全量口径命中。
- **cardinality / evidence / via 取描述文本、非连接符反推**：`1:1` 文本 19 次而 `||--||` 字形 0，解析器从引号内抽基数 token（`CARDINALITY_RE`），未由连接符字形反推。抽查 `jf_customer→jf_sales_order`：cardinality=`1:N`、evidence=`name_inferred`、confidence=0.575、is_inferred=true、source=`01/D01`（对齐 eval-baseline UC3 例 L171）。
- **13 malformed 入图且带标记**：RELATES_TO 中 `malformed_connector=true` = **13**，全部 `left_cardinality="missing"`、`is_inferred=true`、**cardinality=None（13/13）** → 契约 §2.1-3「走缺失队列、不得由残片反推」成立（无一条被反推补值）。

**跨域桩 / 外部引用处置（§2.1）**：
- 外部引用 `CRM_EXTERNAL_SYSTEM`/`OA_EXTERNAL_SYSTEM → jf_quality_compensation`（`01/D15` L117-118，`[注释明示] crm_complaint_code/oa_code`）**不建本库边**：external_skipped_01=**2**（实测源文件为真外部桩）✓。
- 端点非 DDL 的 4 条 RELATES_TO（`jf_product_color`/`jf_strategic_agreement`/`P_act_ru_task`，分布于 D03/D05/D07/05）**未臆建造点**，登记 `pending_confirmation`=4，实测缺失端点确不在 DDL 表集 → 属 `[待确认]` 合法豁免 ✓。

---

## 4. L1 检索质量（端到端可用）— PASS（阈值保持 [待确认]）

**取证（用持久化 `l0_index.db` + `l0_graph.json` 跑 loader `L1Search`+`FTSIndex`）**：
- **UC1 查表**：`find_tables("trader quota")` → `jf_trader_quota / _process_apply / _cash_quota_record / _credit_quota_record`（全 A 级，命中 eval-baseline UC1 期望 `jf_trader_quota`/`jf_trader_credit_quota_record`，D08）；**默认不含 C**（返回集 tier≠C）。
- **UC2 查字段**：`columns_of("jf_sales_order")` column_count=**90**（=独立 DDL 复算）；反向 `tables_with_column("sales_order_id")` exact_count=**17**（跨域命中）。
- **UC3 查关系**：`relations_of("jf_customer")` 默认 min_conf=0.45 → 返回 49 条、**<0.45 边=0**；放宽至 0.0 → 59 条（10 条低置信 semantic/unconfirmed 被默认过滤，正是假血缘防线）；每条附 cardinality/evidence_level/confidence/source_file 四要素 + 「逆向推断、非物理外键」disclaimer。
- **默认 confidence≥0.45**：`config.CONFIDENCE_DEFAULT_MIN=0.45`；`unconfirmed`(0.10)/`semantic_inferred`(0.325) 默认过滤 ✓（对齐 evidence-confidence-map §1.1）。
- **多跳 ≤3 截断可复现**：`traverse("jf_sales_order","both",max_hops=3)` → paths 中最大 hop=**3**（无 >3）、reached=186、`truncated_at_budget=true`、`frontier_edges_beyond_budget`>0（超限前沿被登记，可复现），遍历仅触及 tier={'A'}（B/C 影子不入遍历）✓。

**检索质量阈值纪律**：M0 `eval-baseline.md` §2.2/头注「Top-3≥90% / P95」为 design-plan §1.5 **初设 [待确认]**，本门禁**不判达标**、只记录可用性。黄金集题目 + harness（`graphrag/eval/golden/`）归 `rag-eval-gate`（本人）持有，M1 阶段尚未建集 → 命中率数值待题集落地后补测，**阈值保持 [待确认]**（与 eval-M0 §8-3 处置一致，非缺陷）。

---

## 5. 边完整性 + 推断标注（有出处率 100%）— PASS

**逐边独立审计（读导出产物，不采信 loader）**：
- **悬挂边=0**：全部 9498 条边 src & dst 均在节点集内（`dangling edges: 0`）。RELATES_TO 483 条两端均为存在的 `table:` 节点（非表端点=0）。
- **业务关系边全 is_inferred=true**：RELATES_TO 483/483 `is_inferred=true`（全库 0 FK）。结构边 BELONGS_TO_DOMAIN/IS_COLUMN_OF/HAS_ISSUE `is_inferred=false`（schema §2 权威登记，符合契约，非业务推断边）。
- **最高证据 comment_explicit（0 FK）**：RELATES_TO evidence 分布 `{name_inferred:273, index_backed:107, semantic_inferred:57, comment_explicit:31, unconfirmed:15}`，**无 FK/explicit 级**，全落五级内；confidence ≤0.95（上限锁，永不宣称物理约束）✓。
- **有出处率 100%**：483/483 RELATES_TO 均挂 `evidence_ref → EvidenceSrc` 且 SUPPORTED_BY 覆盖缺失=0；EvidenceSrc 节点 file **483/483 全部以 `er-model/` 开头**（无 test_erp.sql 主出处、无外部源），带 quote_hash 稳定锚。
- **端点守恒**：456(01 ξ) −2(external) −3(pending) +33(05) −1(pending) = **483** 建边（与 per-domain 分布逐项自洽：D03/D05/D07 各−1、D15 −2 external、05 −1 pending）。
- **DERIVED_FROM 两档**：113 = 5 `is_inferred=false`(显式 §3.1×2+§二×3) + 106 naming `is_inferred=true` + 2 copy1；命名派生分类 115=106 建边+9 悬挂（源表不可解析，登记不造边，schema §2.2）✓。

---

## 6. 合并行拆回 ⊳ DDL 交叉校验 — PASS

- **图内逐表回读**：A 级 `Table.column_count == 该表 Column 节点数`，失配=**0**。
- **独立 DDL 复算**：`jf_sales_order`=90、`jf_customer`=75（自写 DDL 列计数脚本，与图 column_count 一致）。
- **审计四件套展开**：`audit_expanded_tables=95`、`audit_columns_present_in_ddl=95`（95/95 表 `created_by/updated_by` 等合并行拆回后命中 DDL）。
- **诚实残差登记**：文档↔DDL 命名差 `name_mismatch=41`/`shorthand=3`/`shared_prefix=4`、`atomic_matched=3525` → 结构事实以 `test_erp.sql` 为最终仲裁（Column 节点直取 DDL），文档残差仅作信号，符合 schema §0/§1.3 冲突消解。

---

## 7. 范围边界守恒 — PASS

- **代码/产物无越位**：`grep -rniE 'dam[-_]app|dam_meta|PLAN\.md|asset_urn'` 于 `ingest/store/search` = **0 命中**（未引入外部身份模型）。
- **未混入变换/ETL 血缘**：无 `sum(...)→...` 计算链；仅 `RELATES_TO`(表级引用)+`DERIVED_FROM`(命名派生)，字段级 `REFERENCES` 属 M2 未建（诚实标注）。
- **未混入指标语义层**：无 KPI/术语表；`Concept` 节点本期未建（`honest_unfinished` 明示 M4 种子），无 `REALIZED_BY`。
- **未预设重型图库/向量/LLM**：栈=Python3 + SQLite3.50 FTS5(porter)/BM25 + 内存/JSON 属性图（C-2），无向量底座。
- **社区对照（M3）**：N/A，M1 无社区检测；输入边 `confidence≥0.45` 过滤已在 L1 遍历内生效（供 M3 复用）。

---

## 8. 两项 loader 处置裁决

### ① `graphrag/data/l0_index.db`（2.6MB 二进制）是否入库 → **裁决：建议不入库**
- **依据**：C-10「派生数据入库策略：图快照/SQLite 默认**不入库**，以可确定性重建脚本+校验报告为交付」；`l0_index.db` 由 `pipeline._export` 以 `FTSIndex(rebuild=True)` **确定性重建**（无 LLM/随机），入库徒增历史膨胀。
- **连带**：`l0_graph.json`(5.6MB)/`l0_edges.jsonl`(1.6MB) 同为可重建全量 dump，同按 C-10 **默认不入库**；契约级机读元数据（`data/meta/*.json`，schema §5/R-4 核心交付，合计仅 ~20KB）**入库**。
- **回退指引**：`.gitignore` 现**未**忽略 `graphrag/data/l0_*`（若 loader `git add graphrag/data/` 会误纳三件大产物）。loader **无 `.gitignore` 写权**（§4 单点归 orchestrator）→ ①**禁止 `git add graphrag/data/` 整目录**，仅 `git add graphrag/data/meta/`；②向 **rag-orchestrator** 提请新增忽略项 `graphrag/data/l0_*`（含 `l0_index.db`）。**此项非阻断**（提交前处置即可），但为提交硬指引。

### ② `graphrag/out/` 被 `.gitignore:18 out/` 忽略、已镜像至 `data/meta` → **裁决：确认机读元数据可提交**
- **依据**：`git check-ignore` 实测 `graphrag/out/meta/l0_manifest.json` 被 `out/` 命中忽略；`graphrag/data/meta/*.json` **未被忽略**（check-ignore 无输出）。`pipeline._export` 同时写 `out/meta`（规范路径）与 `data/meta`（可提交镜像），内容逐字节一致（本门禁两处 JSON 均已读取核验为同构）。
- **结论**：将 `graphrag/data/meta/{l0_manifest.json,assertions.json}` 作为**可提交的机读元数据核心交付**（schema §5 / R-4）成立且必要；`out/` 为本地规范落点、不入 git 无碍。**支持 loader 处置**。

---

## 9. 与 loader 自报的差异

**无。** 本门禁对以下全部关键值做了独立复算，与 `l0_manifest.json`/`assertions.json`/pipeline 自断言**逐项吻合**：

| 维度 | loader 自报 | 本门禁独立实测 | 差 |
|---|---|---|---|
| 1322 / A349 / B853 / C120 / 无PK11 / Issue27 | ✓ | ✓ | 0 |
| 每域=00声明（19/19） | ✓ | ✓ | 0 |
| 关系线 456/12、子 443/10、malformed13、05=33 | ✓ | ✓（双法复算） | 0 |
| RELATES_TO 483 / SUPPORTED_BY 483 / EvidenceSrc 483 | ✓ | ✓ | 0 |
| DERIVED_FROM 5+106+2 / 命名派生 115(106+9) | ✓ | ✓ | 0 |
| Column 6428 / IS_COLUMN_OF 6428 / 逐表回读 0 失配 | ✓ | ✓ | 0 |
| pending4 / external2 诚实登记 | ✓ | ✓（源文件回读为真） | 0 |
| `python -m pytest` | 16 passed | **16 passed**（仓库根 + graphrag/ 两法） | 0 |

---

## 10. 过门决定与后续

- **决定**：M1 六项验收门 **全项 PASS，无 P0 → 准予过门**。数量等式闭合、有出处率 100%、边界守恒、N-2 归级正确、L1 端到端可用、多跳预算满足。
- **提交指引（给 loader，非本报告阻断项）**：
  1. 业务产物提交按 §4 路径表 `feat(rag): M1 图装载与 L1 检索（A级349, 483 边）`，仅 `graphrag/ingest store search data/meta`；
  2. **不提交** `data/l0_index.db`、`l0_graph.json`、`l0_edges.jsonl`（可重建，C-10）；提 orchestrator 补 `.gitignore: graphrag/data/l0_*`；
  3. `graphrag/data/meta/*.json` 作为机读元数据核心交付提交。
- **回退建议**：无（不存在数量等式不闭合 / 阈值顶替 / 悬挂边 / 边界越位的 FAIL 项）。
- **移交 M2**：`data/meta` 机读导出 + `l0_graph.json`（本地图）可被 `rag-lineage-builder` 只读消费；`REFERENCES` 字段级血缘与待确认队列（4 pending + external + 9 derived 悬挂）由 M2 承接；黄金集 `graphrag/eval/golden/`（本人持有）须在 M2 前落地以补 Top-3 命中率基线记录（阈值仍 [待确认]）。

---

### 附：本门禁独立取证命令（可复算）
- 源事实：`grep -ic 'CREATE TABLE'`、`grep -ciE 'FOREIGN KEY|ADD CONSTRAINT|PRIMARY KEY'`、`grep -ioE 'CREATE TABLE .?[a-z0-9_]*_bak_[0-9]{14}'`、`jf_/lcap_` 名串反证。
- 关系线：自写双法（全文 `-o` 左可选正则 + 行结构锚定）→ 456/12、子 443/10、malformed 13；05 行锚定=33；Issue `^[ \t]*-[ \t]+\*\*[A-G]-\d+\*\*`=27（唯一 ID 27）。
- 域数：独立解析 `00` §四 `### Dxx（N）` vs `、` 枚举，19/19 match，Σ332+OT17=349。
- 图审计：`python` 直读 `l0_graph.json` 逐边端点回查（悬挂=0）、RELATES_TO evidence/is_inferred/confidence 分布、SUPPORTED_BY 覆盖、per-domain、DERIVED_FROM 两档、Column 回读。
- DDL 列数：自写 `CREATE TABLE` 块列计数脚本（排除 PK/INDEX/UNIQUE 行）↔ 图 column_count。
- L1：持久化 `l0_index.db`(rebuild=False) + loader `L1Search` 跑 UC1/UC2/UC3/traverse；阈值 [待确认] 不判达标。
- 边界：`grep -rniE 'dam-app|dam_meta|PLAN.md|asset_urn|KPI|ETL'` 代码零实质命中；`git check-ignore` 判 out/ 与 data/meta。
