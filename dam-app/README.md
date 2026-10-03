# dam-app · 数据资产管理应用（M0+M1+M2+M3-1 批次）

按 [PLAN.md](../PLAN.md) §6 分期路线图实施，本目录为 **M0（POC 奠基）+ M1（资产目录 MVP）+ M2（术语·三级模型·版本·轻量拖拽）+ M3-1（血缘关系通道① · ER 证据叠加）** 批次的可运行代码。

## M0 交付内容

| 模块 | 内容 | 状态 |
|---|---|---|
| 后端骨架 | SpringBoot 3.2 / Java 17 / Maven，`dam_meta` JPA 实体（meta_source / meta_asset / meta_column） | ✅ |
| DDL 解析器 | `DdlParser`：块级扫描，严格镜像 `skills/.../ddl_census.ps1` 计数逻辑 | ✅ |
| 摄取服务 | `DdlIngestionService`：解析 `test_erp.sql` → 结构化元数据入库（含 asset_urn / 前缀族 / 物理列展开） | ✅ |
| REST API | `/api/assets`（列表/检索/计数/按名详情）、`/api/ingest/ddl`（触发摄取） | ✅ |
| 前端切片 | Vue3 + TS + Vite：资产目录列表 + 表详情（物理列 7 列视图），`/api` 代理到 8080 | ✅ |
| 本地环境 | docker-compose（MySQL 8，`mysql` profile 用）；默认 `h2` profile 零外部依赖 | ✅ |

## M0 退出标准（已验证）

- 解析表数 = census 基准 **1322**（`DdlParserTest` 断言，`mvn test` 4/4 通过）；
- `jf_sales_order` 物理列数 = **90**（与 er-model 文档一致，单测 + API 实测双验证）；
- 应用可运行：启动自动摄取，`GET /api/assets/count` 返回 1322，中文注释解析正常。

## 快速开始

### 后端（默认 H2，无需数据库）

```bash
cd dam-app/backend
export DAM_DDL_PATH=../../test_erp.sql   # 可选；缺省按仓库相对路径自动定位
mvn spring-boot:run                       # :8080，启动即自动摄取
mvn test                                  # M0 退出标准自测
```

使用真实 MySQL：`docker compose -f dam-app/docker-compose.yml up -d`，再以
`--spring.profiles.active=mysql` 启动后端。

### 前端

```bash
cd dam-app/frontend
pnpm install
pnpm dev        # :5173，/api 代理到 :8080
pnpm build      # vue-tsc 类型检查 + 产物构建
```

## M1 交付内容（资产目录 MVP）

| 模块 | 内容 | 状态 |
|---|---|---|
| 分级标签摄取 | `ErModelCensusParser`+`GradingAssigner`：00-总览 域清单 → A/B/C=349/853/120、18 域+OT 入 `meta_domain` | ✅ |
| 关系摄取（通道②·基线） | `LogicalModelRelationParser`：03-逻辑数据模型 `FK[目标·依据]` → 413 边（五级证据+置信度+待确认；含 `M1RelationIntegrityTest` "源表必含该列"强不变式守卫）。ER 证据第二通道（①）已于 **M3-1** 落地，见下方 | ✅ |
| 治理属性 | 认证/敏感级/废弃/Owner/Steward：`PATCH /api/assets/{id}/governance` | ✅ |
| 检索与导出 | 域/分级 facets 钻取；`/api/export/json`、`/api/export/yaml`（schema=dam-meta/1，按 asset_urn 可 diff） | ✅ |
| 字典（M2 初版） | `dict_standard_field/dict_code_value/dict_naming_rule` CRUD + 种子 | ✅ |
| 结构类 dq | `dq_rule/dq_issue` + `POST /api/dq/scan`：noPK=11、驼峰列=7、字符集漂移=0（对齐 00-总览8.3/05 清单） | ✅ |
| 基础 RBAC+审计 | JWT 登录（admin/steward/viewer）+ 审计日志（`/api/audit`，ADMIN） | ✅ |
| 前端 | vue-router 六页：目录钻取/表详情(关系+治理)/字典/质量/审计/登录 | ✅ |

## M1 退出标准（已验证，`mvn test` 23/23）

- 1322 表头信息全入库 + A 级全列；A/B/C 与 18 域标签和 er-model 文档完全一致（`M1ExitCriteriaTest`）；
- 按 `asset_urn` 可 diff：JSON/YAML 导出含全量资产+列+关系；
- 可按域钻取检索：facets + `/api/assets?domain=&grading=`；
- POC 账号口令：`dam.security.poc-password`（默认 change-me-POC，身份源接法 [待确认]）。

## M2 交付内容（术语 + 三级模型 + 版本 + 轻量拖拽）

| 模块 | 内容 | 状态 |
|---|---|---|
| 业务术语（M3） | `glossary_term/glossary_term_ref` CRUD + M:N 引用（表/列级），`/api/glossary`；示例术语 DRAFT 种子不臆造口径（R4） | ✅ |
| 三级模型（M4） | `model_bom/model_ldm/model_pdm/model_mapping`；`CoreEntityCatalogParser` 解析 `05` §二 三分类，`ModelService` 按**真实目录名精确校验**构建 BOM↔LDM↔PDM（仅存在表成节点，未解析按原文保留·零臆造），`/api/model` | ✅ |
| 版本快照与 diff（M4/R7·D3） | `meta_version/meta_version_item`；`VersionService` 按稳定 `asset_urn` 冻结**集合签名**（逐列 `name\|TYPE\|NULLABLE` 排序后拼接，与列序无关），diff 产 ADDED/DROPPED/RETAINED/CHANGED + 列级增删改；快照基线剔除 DROPPED 墓碑、`version_no` 命名唯一约束+冲突重试、`signature_algo` 迁移标签，`/api/versions` | ✅ |
| 轻量拖拽（M9.1/9.2） | 表详情列拖拽排序（`PATCH /api/assets/{id}/columns/order`）；业务术语页资产拖拽绑定（原生 HTML5 DnD） | ✅ |
| 身份源收敛（D5） | `sys_user` 取代内存用户为权威身份源，BCrypt；`UserDetailsService` 为生产 IAM 适配器位；owner/steward 存在性校验→400 | ✅ |
| 前端 | 新增 业务术语/三级模型 两页与导航（vue-router 共 8 页） | ✅ |

## M2 退出标准（已验证，`mvn test` 49/49 + 真实 MySQL 8 冒烟）

- **术语↔`jf_trader` 可绑定（含拖拽）**：绑定 + `by-asset` 反查在 MySQL 实测通过；反查按 `termId` 去重（表级+列级双绑定不重复）；
- **三级映射可视化**：`jf_trader`（MASTER·贸易商）→ LDM → PDM `mysql:test_erp:jf_trader`（24 列）`resolved=true`，映射依据留痕；实测 BOM=36 / LDM=PDM=映射=66 / 未解析=3；
- **版本快照可比对**：v1 FULL（ADDED 1322）、v2 INCREMENT（RETAINED 1322）+ 列级 delta，MySQL 全链路复验；
- 冒烟修复：`meta_version_item.signature` 由裸 `@Lob`（MySQL 建 TINYTEXT 截断宽表签名）改为显式 `longtext`。

## M2 专家评审整改（提交 `fb6bd0d` + 本批次，`mvn test` 49/49）

对 M2 版本 diff / 术语反查的 7 项评审发现（S2×2、S3×5）全部落地：
- **幻影 DROPPED**：diff 基线剔除上一版 `DROPPED` 墓碑 → 已删表不再逐版重复计入，删除后同结构重建正确归为 ADDED；
- **集合签名**：`signatureOf` 由按 ordinal 拼接改为按列名排序拼接 → 纯列拖拽重排不再误判 CHANGED（兑现"独立于行序"契约）；
- **迁移哨兵**：新增 `meta_version.signature_algo` 标签，就地升级后首个快照检测到旧基线口径不可比 → 自动重打 FULL（实测 v3 FULL/ADDED 1322、无 1295 幻影 CHANGED），不再跨算法比对；
- **并发重号**：`version_no` 由无名 `@Column(unique=true)` 改为**命名唯一约束** `uk_meta_version_no`（令 `ddl-auto=update` 可判存在、避免静默 drop/recreate），`createSnapshot` 仅对 version_no 冲突重试（判因+日志+退避），`doSnapshot` 用 `REQUIRES_NEW` 保证每次尝试独立事务；
- **术语反查去重**：`by-asset` 先按 `termId` 去重再装载，消除 `distinct()` 在缺 `equals/hashCode` 时失效导致的重复项。
- ⚠️ 运维注记：既有 MySQL 库若历史遗留无名唯一索引，命名约束会并存（均生效、无害）；如需彻底清理可 `DROP TABLE meta_version, meta_version_item;` 由应用重建。集合签名口径变更对**未重打基线**的旧快照会触发一次 FULL 重钉（哨兵自动处理）。

> 手工兜底 DDL（`ddl-auto=update` 未补建命名约束时）：`ALTER TABLE meta_version ADD CONSTRAINT uk_meta_version_no UNIQUE (version_no);`

## M3-1 交付内容（血缘关系通道① · ER 证据叠加，`mvn test` 67/67）

按 M3 起步决策“**先通道①再画布**”，落实 PLAN D1（ER 证据通道为 M3 首个子任务）：

| 模块 | 内容 | 状态 |
|---|---|---|
| 通道①解析 | `ErDiagramRelationParser`：解析 `01-ER图/*.md` 的 **Mermaid `erDiagram` 关系行**（两端为真实表名+基数符号+`[证据]`标注+`alias.col`）。**关系符号左右 crow-foot 标记判定多端与基数**（共 12 种符号 → `ONE_TO_MANY`/`MANY_TO_ONE`/`ONE_TO_ONE`/`MANY_TO_MANY`），不再盲定“右恒为子表”；**两道信任闸门**——符号与标签自述基数冲突（如 `||--o{` 却写 `"N:N"`）降级 `AMBIGUOUS`不建不消解、单侧缺标记（`A ..o{ B`）归 `UNSUPPORTED_SYMBOL`（计数不静默丢） | ✅ |
| 通道①叠加 | `ErEvidenceIngestionService`：严格校验两端表均存在且子表真实拥有 FK 列（镜像 B-1）才入库；**ER 优先**将 `cardinality` 与更强证据叠加到②已有边（origin 不变以保 413 基准），新边以 `origin=ER证据摘录` 新增。**消解键 `(from_asset_id, from_column)`**：精确目标→enrich；②散文未解析目标→**双闸门消解**（需同时满足：ER 行无自证否认词如“非id/无 id/多值/多单号串/待确认/名称关联”且该位置全语料唯一父候选），否则只写候选不钉 `to_asset_id`；②已指向另一具体目标→标冲突。**永不覆盖②的 `target_raw`**（只追记 `basis_raw`）；M:N/歧义 1:1/同列反向均不新建 | ✅ |
| 新列 | `meta_relation.cardinality`（`1:1`/`1:N`）——通道②无法提供、通道①独有（`N:M` 不物化为单方向边、故不落库）；`conflict_flag`（S3-1）——②与 ER 指向不同具体目标或多父候选时的**可过滤标记**，独立于 `confirm_status` 三态不污染确认台 | ✅ |
| 强不变式 | `ErEvidenceIntegrityTest`：每条 ER 边两端目录可解/子表含列/基数与证据枚举合法/**无同列名反向对（S1）/无 M:N 单边/新建 ER 边全为 1:N/含『未消解』注记的边 `to_asset_id` 必为 NULL（S1-1）/基数与目标列规模下限**；`ErDiagramRelationParserTest` 锁定 12 种符号分类/交叉降级/裸 id 不当候选；`ErCorpusSymbolCensusTest`（S3-4）对全 20 篇逐行做六 Kind 分布快照对账、断言零静默丢 | ✅ |
| 启动接入 | `StartupIngestor` 在②之后、以 `!existsByOrigin(ORIGIN_ER)` 作幂等守卫叠加①（`ORIGIN_ER` 为服务公有常量） | ✅ |

- 实测（H2 与真实 MySQL 8 逐位一致）：`ErReport{matched=183, cardinalityFilled=175, evidenceUpgraded=5, newEdges=74, resolvedProse=5, deferredDenial=1, deferredMulti=5, conflicts=0, skippedEndpoint=11, skippedNoColumn=32, skippedNotOwned=8, skippedMulti=3, skippedAmbiguous=5, skippedUnsupported=13, cardinalityClash=126, skippedReverse=1}` → 总边 413② + 74① = **487**（②经 ER 叠加补基数、①新增 74 全为 1:N）；**同列名反向对 = 0、全 487 边子表均真实含 FK 列**（零臆造）。
- 评审整改（针对首轮整改 `081f6d2` 的第二轮专家评审）：**S1（新发现·目标消解臆造）**——首轮修好了方向，但 `resolvedProse` 用单条 ER 行将②散文边钉到具体父表（部分行自述 N:N/多单号串/非id）→ 改为双闸门（否认词 + 多父候选预扫）且永不覆盖 `target_raw`；符号↔标签基数零交叉→新增交叉校验降级 AMBIGUOUS。**S2**——单侧缺标记的 13 行静默丢弃→UNSUPPORTED_SYMBOL 计数；`ALIAS_COL` 放宽后裸 `id` 可被子表 PK 平凡满足→拆为“FK 候选排除裸 id、目标列允许 id”；无 alias 前缀的真实边（如 `jf_statement.customer_reconciliation_id`）被丢→无 alias 兑底提 `*_id/_code`。`mvn test` 67/67（新增 6 例）。上一版 `matched=290/new=14/总427` 口径因本轮交叉降级与消解收紧而变（新增边由无 alias 兑底救回真实边而变多），已以本行数据为准。
- 接口：`StartupIngestor` 自启链式跑②→①；`POST /api/ingest/relations` 全重建②后重叠加①；**`POST /api/ingest/er-evidence` 仅重叠加①不碰②**（避免 `deleteAllInBatch` 重置人工确认状态）。
- 不 drop 就地升级复验：ddl-auto 自动为既有 `meta_relation` 补 `cardinality`/`conflict_flag` 列，②因 count>0 不重建、①因无 ER 边而叠加。
- **S3 增强**（本轮，评审团遗留项）：`conflict_flag` 独立可过滤列（多父候选/目标冲突边精确标记，实测全新摄取 5 个多父候选事件去重落在 2 条散文边、`confirm_status` 保持待确认）；`ErReport` 补口径 javadoc（`resolvedProse ⊆ matched`、`conflicts/deferred*` 按 ER 行计非按去重边）；前端表详情关系区接 `cardinality` 与 `conflict_flag`（⚠ 目标冲突待消歧）并更正“全部来自逻辑模型 FK 列”为“② FK 列 + ① ER 叠加”；语料级快照对账测试。`mvn test` 68/68（新增 1 例），前端 `npm run build` 通过；全新 MySQL8 冒烟 `ErReport` 逐位一致、② 413/总 487/conflict_flag=2、① 74 全 1:N（无 ER 反向；3 对反向全为②基线固有合法双向互引、不同 FK 列）。
- 通道①的消费方：血缘查询已于 **M3-2** 落地（见下）；X6 画布连线编辑与确认工作台属后续 M9.3/M5 批次。

### M3-2 血缘查询（递归 CTE 即时查询）

| 项 | 实现 | 状态 |
|---|---|---|
| 血缘服务 | `LineageService.trace(root, dir, depth)`：基于 `meta_relation` 有向边 `child(from)→parent(to)`（child 拥有 FK 列）；**DOWNSTREAM**（沿 `to→from`·谁引用我·影响分析）/ **UPSTREAM**（沿 `from→to`·我引用谁·数据来源）双向递归 | ✅ |
| 存储取舍 | **MySQL 递归 CTE 即时查询**（锁定决策③）：不建 `meta_lineage_path` 闭包表，子图按需计算、关系一改即生效；闭包表作规模触顶后的预留增强 | ✅ |
| 防环 | CTE 携带逗号 `path` + `LOCATE` 剪枝已访问节点 + 深度钳 `[1,10]`（语料含**合法 2-环**，如 `jf_customer ↔ jf_contract_quota_customer`）；同节点多路径去重取最小深度 | ✅ |
| 双方言 | 经 JDBC 探针锁定跨 H2(`MODE=MySQL`)/MySQL8 兼容：① CTE **须显式列名列表** `lin(node,depth,…)`（H2 必需、MySQL 容）；② anchor **不可用 `CAST(NULL AS BIGINT)`**（MySQL 拒），改以 `:root` 保列类型一致 | ✅ |
| 接口 | `GET /api/lineage?asset=<表名>&dir=downstream\|upstream&depth=6`（`/api/**` GET 免登录）；dir 非法→400、未知表→404 | ✅ |
| 零臆造 | 节点/边端点均解析自目录（不产合成节点）；`LineageQueryTest`（4 例）断言枢纽双向可达、`distinct==nodeCount`（防环）、边端点闭合子图（零臆造）、深度钳制 | ✅ |

- 实测（H2 与真实 MySQL 8 逐位一致）：`jf_sales_order` 枢纽 **downstream 41 节点/48 边**（深度分布 0:1/1:20/2:6/3:6/4:8，如 `jf_pay_request`/`jf_inventory_frozen_record`/`jf_receivable_*`）、**upstream 15 节点/17 边**（`jf_customer`/`jf_trader`/`jf_settlement_unit`/`jf_brand`…）；`danglingEdges=0`、`truncated=false`（达成退出标准 G3：枢纽正/反向可追溯 + 影响分析）。`mvn test` **72/72**（新增 `LineageQueryTest` 4 例）。
- 注：CTE 返回行数（MySQL 探针 73）> 唯一节点数（41）——因多路径汇聚，service 按最小深度去重为唯一节点集。

## 关键设计口径（对齐评审结论）

- **结构 ← DDL，关系 ← ER 证据**：本库 0 外键，`DdlParser` 只摄取表/列结构；
  关系摄取自已落地于 `er-model/03-逻辑数据模型` 的 `FK[目标·依据]` 键列（M1）。
- **合并行拆回原子列**：`meta_column` 按物理 DDL 逐列存储（含审计四件套），
  `source_layer=physical`，保证列数与 DDL 严格一致。
- **asset_urn 稳定身份**：`mysql:{schema}:{table}`，跨版本 diff 与血缘以此为锚点。

## 目录结构

```
dam-app/
├── backend/                # SpringBoot（com.dam）
│   └── src/main/java/com/dam/{parser,ingest,domain,repository,web,config,security,dict,dq,export,version,model,audit}
├── frontend/               # Vue3 + Vite + TS
└── docker-compose.yml      # MySQL 8（可选 profile）
```
