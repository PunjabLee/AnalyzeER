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

## M3-1 交付内容（血缘关系通道① · ER 证据叠加，`mvn test` 51/51）

按 M3 起步决策“**先通道①再画布**”，落实 PLAN D1（ER 证据通道为 M3 首个子任务）：

| 模块 | 内容 | 状态 |
|---|---|---|
| 通道①解析 | `ErDiagramRelationParser`：解析 `01-ER图/*.md` 的 **Mermaid `erDiagram` 关系行**（两端为真实表名+基数符号+`[证据]`标注+`alias.col`），机读可解且不臆造（相比稀疏、中文实体名的“关系要点与证据摘录”表更完整、可对齐目录） | ✅ |
| 通道①叠加 | `ErEvidenceIngestionService`：严格校验两端表均存在且子表真实拥有 FK 列（镜像 B-1）才入库；**ER 优先**将 `cardinality` 与更强五级证据叠加到②已有边（origin 不变以保 413 基准），目录内新边以 `origin=ER证据摘录` 新增 | ✅ |
| 新列 | `meta_relation.cardinality`（`1:1`/`1:N`）——通道②无法提供、通道①独有 | ✅ |
| 强不变式 | `ErEvidenceIntegrityTest`：每条 ER 边两端目录可解/子表含列/基数与证据枚举合法/1:1与注释明示证据已带入 | ✅ |
| 启动接入 | `StartupIngestor` 在②之后、以 `findByOrigin("ER证据摘录")` 为空作幂等守卫叠加① | ✅ |

- 实测（H2 与真实 MySQL 8 一致）：`ErReport{matched=194, cardinalityFilled=194, evidenceUpgraded=5, newEdges=30, skipped=232}` → 总边 413② + 30① = **443**（218 个 1:N + 6 个 1:1 + 219 个无基数）；skipped=232 均为端点不在目录/子表不拥列/纯散文行（零臆造故舍弃）。
- 不 drop 就地升级复验：ddl-auto 自动为既有 `meta_relation` 补 `cardinality` 列，②因 count>0 不重建、①因无 ER 边而叠加。
- 通道①的消费方（血缘画布与确认工作台）属后续 M3 批次（递归 CTE 血缘查询 → 确认闭环 → X6 画布连线编辑）。

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
