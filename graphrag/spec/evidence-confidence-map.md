# M0 · 五级证据 ↔ evidence_level ↔ 置信度 ↔ 检索策略

> 唯一写入者：M0。锚点：`design-plan.md` §5.1、§5.2、§1.5；`00` §六、`05` 开头五级体系；R-1 表（§5.2 确认队列自设）。
> **数值纪律**：§5.1 置信区间为 design-plan **初设**、`§8.3-1` 标 **[待确认]** → 本表**原样保留 [待确认]**，不新设/不擅自拍板达标线（承 `RUNBOOK` 口径纪律）。仅 `confidence ≥ 0.45`、多跳 ≤3 为 design-plan 既有量化条款（0.45 见 §5.2-1；≤3 见 §6，其本身亦标 [待确认]）。

---

## 1. 五级证据映射（强度降序）

| er-model 标注（文本 `[证据]`） | 图谱 `evidence_level` | 置信度区间 **[待确认]** | 检索默认策略 |
|---|---|---|---|
| `[注释明示]`（COMMENT 点名目标表） | `comment_explicit` | **0.85–0.95** [待确认] | 强，正常召回 |
| `[索引佐证]`（`_id`/`_code` 建 INDEX/UNIQUE 且指向 PK） | `index_backed` | **0.7–0.85** [待确认] | 强，正常召回 |
| `[命名推断]` / `[字段命名]`（`xxx_id` 命名） | `name_inferred` | **0.45–0.7** [待确认] | 中，标注「推断」 |
| `[语义推断]` / `[业务语义推断]`（仅业务语义） | `semantic_inferred` | **0.2–0.45** [待确认] | 弱，降权 + 警示 |
| `[待确认]`（目标不存在/指向不明） | `unconfirmed` | **0.0–0.2** [待确认] | **默认过滤**，`show_uncertain=true` 才显示 |

- **无 `[显式外键]` 级**：全库 0 FK（`grep -ciE 'FOREIGN KEY' test_erp.sql`=0，本文实测）→ 本表**最高只到 `comment_explicit`**，任何边 `is_inferred=true`，`confidence` 上限锁 0.95（不给 1.0，永不宣称物理约束）。
- 区间端点重合处（0.45、0.7、0.85）以 **左闭** 归入较高档由实现约定，但**默认过滤阈值 0.45 的判定 = `confidence ≥ 0.45`**（design-plan §5.2-1 原样）。

### 1.1 检索默认阈值与开关（承 §5.2）

| 项 | 值 | 出处/性质 |
|---|---|---|
| 默认召回下限 | `confidence ≥ 0.45`（即 `name_inferred` 及以上默认放行；`semantic_inferred`/`unconfirmed` 默认过滤） | §5.2-1（既有量化条款） |
| `unconfirmed` 显隐开关 | `show_uncertain`（默认 false） | §5.1 |
| C 级影子节点显隐 | `include_c`（默认 false） | §2.2 |
| 多跳预算默认上限 | `≤ 3 跳`，超限截断可复现 | §6，**本身标 [待确认]** |
| 排序 | 多跳按边置信度加权、优先高证据，防低置信「短路连边」假血缘 | §5.2-2 |

---

## 2. 复合证据与特殊处置（承 schema.md §2.1 / §5.1 注）

| 场景 | 判别 | evidence_level 处置 | 备注 |
|---|---|---|---|
| `[命名推断+索引]` | 同行并存两标签（`01` 实测该复合串 **91** 次） | 取较高档 `index_backed` + `evidence_tags=["name","index"]` | 不重复计边 |
| `[注释明示→外部系统]`（如 D15 `crm_complaint_code`/`oa_code`） | COMMENT 明示但目标为外部系统 | `external_reference=true`，**不建本库边** | 避免虚假关系；`00` 复核批① |
| 多态外键（`flow_change_record.related_order_id` 按 `order_type`） | 一列多目标 | 多候选边共享 `unconfirmed` + `polymorphic=true, discriminant="order_type"`，全进待确认队列 | `05` A-3 |
| 同名列异指向 | 同名字段指向不同表 | **不得**武断连「最近」表 → 强制待确认队列 | `05` B-4 |
| 库内双候选·无佐证 | 人员字段指向 `jf_personnel`/OT `personnel`/`lcap_user` | 维持 `[语义推断·待确认]`，**不得升级**（无注释/索引佐证） | `00` 复核批② |

- **人工确认闭环**：`unconfirmed`/`semantic_inferred` 入「待确认队列」（本方案自设，非外部平台）；人工确认可上调 `evidence_level`。高危默认进队列：`05` B-4 同名异指向、A-3 多态、G-4 迁移遗留、E-3 无 PK 业务表（§8.3-5）。

---

## 3. 实测证据标签分布（接地，非达标依据）

> 对 `er-model/01-ER图/*` 描述文本内标签 `grep -rhoF` 计数。**注意**：同一行可并存多标签（复合），此为 substring 出现次数，跨**全量 456 条关系线**（census 口径 ξ，含 13 条 `malformed_connector=true` 左缺失线——其 `[证据]` 标签照常计入），**非按边去重**；解析器按「一行取最高档 + 记 evidence_tags」归边，最终每边一个 `evidence_level`。

| 标签串 | substring 出现 | 归入 evidence_level |
|---|---:|---|
| `注释明示` | 92 | comment_explicit |
| `索引佐证` | 95 | index_backed |
| `命名推断`（含复合 `命名推断+索引` 91） | 391 | name_inferred（复合取 index_backed） |
| `字段命名` | 86 | name_inferred |
| `语义推断` | 102 | semantic_inferred |
| `待确认` | 73 | unconfirmed |
| `外部系统` | 7 | （external_reference，不建本库边） |

- 解读：`name_inferred` 家族（命名/字段命名）为关系主来源，恰落在 0.45 阈值带 → **默认阈值会把绝大多数命名推断关系放行**，符合 §5.2-1；`semantic_inferred`+`unconfirmed` 合计约占带边缘，默认被过滤/降权，正是「假血缘」防线（R1）。
- `05` 全局子图标签多为 `[字段命名/索引佐证]`（虚线 `||..o{`，实测 33 条），解析同走本映射。

---

## 4. 与置信度无关、但常被误并入的两点（划界）

1. **实线/虚线 ≠ 证据强度**（`design-plan` §4.1 注）：`01` 实线仍见 `[语义推断]`；故 `evidence_level` 只认文本标签，不认连接符线型（详见 `relation-symbol-census.md` §5）。
2. **基数 ≠ 置信度**：`1:1`/`1:N`/`N:M` 是关系结构属性（从描述文本解析），与 `confidence` 正交，不得因基数强弱调证据级。

---

### 附：实测出处
- 区间数值：`design-plan.md` §5.1（初设 [待确认]）、§8.3-1。
- 阈值 0.45 / 多跳 3：`design-plan.md` §5.2-1 / §6。
- 标签分布：本会话对 `er-model/01-ER图/*` `grep -rhoF` 复算。
- 特殊处置：`00` §六/复核批①②、`05` A-3/B-4、`design-plan.md` §5.1/§5.2。
