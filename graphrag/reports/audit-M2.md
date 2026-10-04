# audit-M2 · GraphRAG M2（字段级引用/结构级血缘）范围与取证审计

- **判定**：✅ **无 P0 / 无 P1 → 准予提交**（6 项 P2 观察，不阻断）
- **审计者**：`rag-scope-auditor`（只读，不执行提交）；本报告由 `rag-orchestrator` 代为提交（§4 分工）。
- **分支/基点**：`chore/rag` @ 基点 `4a5485a`，未合 `master`。
- **被审产物**：`graphrag/lineage/`、`graphrag/data/review_queue.json`、`graphrag/data/meta/lineage_edges.jsonl`、`graphrag/data/meta/lineage_manifest.json`。
- **上游凭证**：`reports/eval-M2.md`（PASS，`625e6a3`）；M1 锚守恒（RELATES_TO 483 / A349 / Issue27 / ξ=456）。

## 1. 审计维度结论（对齐 scope-auditor 六维）

| 维度 | 判级 | 结论 |
|---|---|---|
| Scope violation（dam-app/dam_meta/PLAN.md） | 无 | 产物/代码/结论文本零引用外部平台或 `asset_urn` 身份模型；`table_id` 沿用本方案自设 `[待确认]`。 |
| 输入越界 | 无 | 输入限定 `test_erp.sql` + `er-model/01,03,05` + M1 `data/meta/` 只读回载；无 `er-model/*`+`test_erp.sql`+`Agents.md`/`skills` 之外来源。 |
| 取证真实性 | 无 P0/P1 | 编排者独立实测：`lineage_edges.jsonl` **514** 行、`references_total=514`、`review_queue.items=**97**`（抽取 84 = polymorphic 2+external 21+target_unresolved 51+typo 1+same_name_divergent 9；+M1 承接 13 = pending 4+derived 9）；`default_visible_ge_0.45=**446**`（514−62 semantic−6 unconfirmed）；`comment_explicit=28`；Issue=**27**（05 实测，非 `Agents.md`「30」）；DDL `FOREIGN KEY=0`/`ADD CONSTRAINT=0` 复核。与 manifest/eval-M2 逐项吻合，**计数非照抄约定**。 |
| 推断标注 | 无 P0/P1 | 514/514 `is_inferred=true`、`confidence≤0.95`（实测 max 0.90）、无 explicit_fk/物理约束级；disclaimer 100% 携带「逆向推断、非物理外键；不含变换/ETL 数据流级」。 |
| 边界守恒 | 无 P1 | 新增边型集={REFERENCES, SUPPORTED_BY}，无 Transform/Job/Flow/Metric；产物枚举 grep `transform\|etl\|kpi\|metric` 零命中（仅排除声明文本）；变换/ETL 血缘与指标语义层显式标 **超本期范围**（§2.3/R-9、§1.4/R-10）。 |
| 分支纪律 | 无 | 在 `chore/rag`、基点 `4a5485a`，无 `master` 合并；逐路径提交，`l0_*`/`*.pyc`/`out/`/`.tmp` 已 `git check-ignore` 确认不入提交。 |

## 2. P2 观察（不阻断，移交编排者裁量/据实登记）

1. **P2-1 证据信号混装**：`comment_explicit` 单级 28 条实为两路混合——**DDL COMMENT 直证 19** + **文档继承（er-model 关系线/`[证据]` 注释）9**。同 confidence（0.9）下混用会掩盖"直证 vs 继承"强度差。建议拆子信号（如 `comment_ddl` / `comment_doc`）以便分级与审计。
2. **P2-2 死分支**：`extractor.py` 中 `_rank < CAP` 判定在既有赋值路径下恒不触发（无实际截断/降权效果），属可读性/维护性噪声。建议下一迭代清理或补注其设计意图，避免误以为生效。
3. **P2-3 命名漂移（已裁决）**：M2 交付实际写入 `graphrag/data/meta/lineage_*`（延续 M1 `data/meta/` 既定口径），与 RUNBOOK M2 卡及 §4 单一写入者表声明的 `graphrag/data/references_*` 不一致。**编排者裁决＝改卡不改产物名**：RUNBOOK 已把 `references_*` 更正为 `data/meta/lineage_*`，产物名保持不动（manifest `outputs.committed` 已留痕）。
4. **P2-4 队列留痕缺结构化出处**：`same_name_divergent` 9 条（对应 `05` B-4 同名异指向）仅有 `reason` 文句，缺 `doc_ref` 结构化字段（如 `er-model/05 §B-4`），回查需人读原文。非断言侧不违"有出处率 100%"（断言=边，已证），建议补 `doc_ref` 统一程序化回查。
5. **P2-5 取证时序约束**：`build(write=True)` 的测试用例会**覆写 `data/meta/lineage_*` 与 `review_queue.json` 真实产物**。审计/取证须在跑测**之前**完成，或测试改用隔离输出目录，否则快照计数会被测试态污染。本轮已按"跑测前取证"执行。
6. **P2-6 上游口径陈旧**：`Agents.md` §4.3 与 `00` 复核批称 A–G 质量问题「30 条」，实测 `05` 列表项 **27 条**（A3+B4+C4+D5+E3+F4+G4）。M2 承接的 Issue 计数与队列基数以实测 **27** 为准；「30」为陈旧口径，登记为 `Agents.md` 与实物的口径差（承 N-1）。

## 3. 放行结论

M2 **无 P0/P1，准予提交**。P2-3 已由编排者在 RUNBOOK 落裁决（改卡不改产物名）；P2-1/2/4/5/6 作为技术债/口径注记移交，随 M3 交接要点登记，不阻断 M2 收口。
