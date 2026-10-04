# GraphRAG M1 · 范围与取证审计（精简存档）

- **里程碑**：M1 · L0 属性图装载 + L1 确定性检索
- **被审产物**：`graphrag/{__init__.py,ingest/,store/,search/}` 源码 + 机读元数据 `graphrag/data/meta/{l0_manifest.json,assertions.json}`；本地图产物 `graphrag/data/l0_*`（工作树，不入库）
- **审计执行**：`rag-scope-auditor`（只读，不提交）；**本文件为编排者按 §4 路径表代提的精简存档**
- **分支**：`chore/rag`（基点 `4a5485a`，未合 `master`）；HEAD 以 `git log -1` 现查为准（不在台账写死）
- **配套门禁**：`rag-eval-gate` 报告 `graphrag/reports/eval-M1.md` → **✅ PASS**（提交 `888e7c6`）
- **总体判定**：**无 P0 → M1 准予提交**

---

## 1. P0：**无**

| 维度 | 结论 | 取证（编排者本次实测） |
|---|---|---|
| 外部模型守恒 | 通过 | `grep -rniE 'dam[-_]app\|dam_meta\|PLAN\.md\|asset_urn' graphrag/{ingest,store,search} --include='*.py'` = **0 命中**；未引入外部身份模型 |
| 输入边界 | 通过 | 语料仅 `er-model/*` + `test_erp.sql`；EvidenceSrc 的 `file` 483/483 以 `er-model/` 开头（`eval-M1` §5） |
| 边界守恒 | 通过 | `etl`/`KPI`/`术语表` 代码内 **0 命中**；`sum(…)` 命中 15 处经逐条判读**全为 Python 内建聚合**，非计算链血缘；`REFERENCES` 仅 1 处且为"字段级血缘（M2）本期未建"的**诚实缺席标注**（`pipeline.py:195`） |
| 推断标注 | 通过 | RELATES_TO 483/483 `is_inferred=true`；证据分布 `{name_inferred:273, index_backed:107, semantic_inferred:57, comment_explicit:31, unconfirmed:15}` **无 FK/explicit 级**，confidence ≤0.95 上限锁；回答附"逆向推断、非物理外键"disclaimer |
| 数量等式 | 通过 | 见 §4 独立复算 |
| 分支纪律 | 通过 | `chore/rag`，未合 `master`，无 `--force`/`--no-verify`，提交后未 push |

---

## 2. P1（1 项，本次收尾已处置）

### P1-1 · 临时/派生产物存在**被整目录 `git add` 误纳**的入库风险
- **风险事实（审计本次实测工作树）**：
  | 对象 | 规模 | 提交前 `.gitignore` 状态 | 应否入库 |
  |---|---|---|---|
  | `graphrag/data/l0_graph.json` | 5.6MB | **未忽略** | ❌ 可重建（C-10） |
  | `graphrag/data/l0_edges.jsonl` | 1.6MB | **未忽略** | ❌ 可重建 |
  | `graphrag/data/l0_index.db` | 2.6MB（二进制） | **未忽略** | ❌ 可重建（`eval-M1` §8-① 裁决） |
  | `graphrag/**/__pycache__/*.pyc` | 21 个 | **未忽略** | ❌ 字节码缓存 |
  | `.tmp/`（取证/门禁临时脚本 7 份） | — | **未忽略**（`/_*` 仅根锚定，`graphrag/**/_*` 亦不覆盖，见 C-9/N-4） | ❌ 会话临时物 |
  | `graphrag/data/meta/*.json` | ~18KB | 未忽略 | ✅ **契约级机读元数据**（schema §5/R-4 核心交付） |
  - 若 loader 执行 `git add graphrag/data/`（整目录），将误纳 3 件共 ~9.8MB 可重建产物 + SQLite 二进制，**不可逆地永久留在历史**。
  - 注：既有 `.gitignore:18 out/` 已把规范落点 `graphrag/out/meta/` 忽略（`git check-ignore` 证实）→ loader 以 `data/meta` 作可提交镜像，`eval-M1` §8-② 已裁决该处置成立。
- **处置（本编排者单点，§4 路径表：`.gitignore` 唯一写入者）**：
  1. `.gitignore` 补 6 项：`__pycache__/`、`*.pyc`、`/.tmp/`、`graphrag/data/l0_graph.json`、`graphrag/data/l0_edges.jsonl`、`graphrag/data/l0_index.db`；既有项**不动**。
  2. 提交采用**逐路径 `git add`**，禁 `git add -A` / 禁 `git add graphrag/` / 禁 `git add graphrag/data/`；提交前以 `git status -s` + `git check-ignore` 双证 l0_*/pyc/.tmp 已被忽略且未入暂存。
  3. 已同步在 M2–M4 提交指引中沿用"逐路径 + 先查 check-ignore"纪律。
- **级别**：P1（流程/仓库健康，非产物正确性）；**非阻断**，提交前处置即可（与 `eval-M1` §8-① 一致）。

---

## 3. P2（4 项，非阻断；技术债登记）

| # | 事项 | 实测证据 | 处置建议 |
|---|---|---|---|
| P2-1 | **`rel_dangling(structural)` 为恒真死护栏** | `store/graph.py:163` 初始化 `"rel_dangling": []` 后**全链路从未 append**（`pipeline.py:156` 仅填 derived 的 `dangling` 键）；`assertions.py:158` 却以 `len(build_report["rel_dangling"])` 断言恒 0，`tests/test_m1.py:117` 同样断言其长度为 0 → **该护栏不产生任何信息量** | `eval-M1` §1 附注已声明**不采信**该自断言，改以"逐边端点回查 9498 条边、悬挂=0"独立证否，**实质结论不受影响**。M2 起须把悬挂检测接到真实遍历（或删该断言），避免"绿而无用"的假阳性安全感 |
| P2-2 | `agents/RUNBOOK.md` §A 快照**已过时** | 旧值：关系线 9 种/439、03 表头 10 种/291，且 §A Git 行写死 HEAD SHA | **本次收尾已勘误**：§A 改为 ξ **456 条/12 种字形**（子口径 6 字符合规 **443/10**、malformed **13**）、03 表头 **11 种/292**（全签名 19/309）、HEAD 改"`git log -1` 现查为准"，并加注"M1/M2 复算以 `spec/relation-symbol-census.md` ξ=456 为准，**勿回抄旧 §A**"（呼应 `eval-M0` §8-1 对编排者的提醒） |
| P2-3 | `derived_parser.py` 的 `_COPY1_CANDIDATES` **硬编码** 2 张 `*_copy1` 表名 | `derived_parser.py:27-28` 写死 `jf_sales_order_copy1` / `jf_statement_fee_category_copy1`，`assertions.py:173` 亦以 target=2 断言 | **可接受**：建边前后均有 DDL 表集门控（`if c in tables` 且 `if src in tables`，L139-141），源表不存在则**静默不建** → 不会臆造边；但语料新增 `*_copy1` 时不会自动覆盖。建议 M2+ 改为由 `_copy1` 后缀规则驱动，硬编码清单降级为白名单校验 |
| P2-4 | `search/l1.py` 提供 `domain_summary()`（标 `uc:"UC6"`）需判是否越 M3 界 | L154-173 实现为**纯结构化聚合**：沿 `BELONGS_TO_DOMAIN` 入边取成员表（name/tier/column_count）、累加 `RELATES_TO` 出边计数、枚举 `HAS_ISSUE` 的 issue_id，附 0-FK disclaimer | **未越界**：无社区检测、无分层摘要、无 map-reduce/LLM，不产出涌现簇或全局结论 → 属 L1 结构化预览，UC6 的 L2/全局分析仍归 M3。已在 M3 卡提醒：社区/全局增量不得由 L1 计数冒充 |

---

## 4. eval-gate 已判项 · 编排者独立复算（全吻合）

| 判项 | eval-gate | 编排者本次复算 | 差 |
|---|---|---|---|
| 登记全量 = `CREATE TABLE` | 1322 | 1322 | 0 |
| A/B/C 分级（先剔 `_bak_`/`test_`） | 349/853/120 | 349/853/120（Σ1322，**非 A=351**） | 0 |
| 18 域Σ + OT | 332 + 17 = 349 | 18+21+19+33+13+15+23+27+31+24+21+11+16+34+6+10+6+4=**332**，+OT17=**349** | 0 |
| 入图 Issue | 27（非 30） | 27 | 0 |
| 关系线全量 ξ / 子口径 ξ′ / malformed | 456/12 · 443/10 · 13 | **456/12 · 443/10 · 13**（左可选正则全文扫描，逐字形分布与 census 一致） | 0 |
| 05 核心关系线 | 33（全 `\|\|..o{`） | 33 | 0 |
| `03` 字段表头 | 11 种 / 292 | **11 种 / 292**；全签名 19 种 / 309 行（11 字段 + 8 辅助/17） | 0 |
| 显式外键 / 无 PK 表 | 0 / 11 | `FOREIGN KEY`=0、`ADD CONSTRAINT`=0；`PRIMARY KEY`=1311 → 11 | 0 |
| 行数口径差 | 26,998 ↔ 28,339 | 28,339 − **空行 1,341** = **26,998**（非空行）；CR=**0**、utf-8 → 定因为空行口径（**非 CRLF**，详见 `audit-M0` P1-2） | 0 |
| 断言闭合 | "40 项 target==actual" | `assertions.json`：**all_pass=true / passed=40 / failed=0**（叶子断言 40 项）。**口径注记**：40 项中 **37 项为 `target==actual` 等式**，另 **3 项为非等式型**（`rel_01_edges_after_external` target `"<= 456"`，actual 454；`header_doc_name_mismatch` 与 `rel_pending_confirmation` 为 `info` 型信号）→ 提交信息所称"40 等式闭合"应读作"**40 项断言全过**"，非 40 个等式 | 0（表述已澄清） |

---

## 5. 审计结论

1. **无 P0** → **M1 准予提交**；P1-1 由本次收尾以 `.gitignore` + 逐路径 add 处置完毕，提交清单见 `git show --stat`。
2. P2-1（死护栏）为唯一**实质性技术债**：M2 的门禁须以逐边端点回查替代该恒真断言，否则"边无悬挂"在自动化断言层面形同虚设。
3. P2-2 已闭环（台账勘误随本次提交入库）；下游一律以 `graphrag/spec/` 为数量真源。
4. **移交 M2 前置条件已满足**：`data/meta` 机读导出 + 本地图 `l0_graph.json` 可只读消费；M2 承接 `REFERENCES` 字段级引用血缘、待确认队列（**4 pending + 2 external + 9 derived 悬挂**）；黄金集 `graphrag/eval/golden/` 须由 `rag-eval-gate` 在 M2 前落地以补 Top-3 命中率基线记录（**阈值保持 `[待确认]`**）。
5. **M2 边界纪律重申**：变换/ETL 数据流级血缘与指标语义层为**超本期范围**，不得因"血缘"名义扩张。
