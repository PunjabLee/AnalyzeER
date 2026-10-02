# dam-app · 数据资产管理应用（M0+M1 批次）

按 [PLAN.md](../PLAN.md) §6 分期路线图实施，本目录为 **M0（POC 奠基）+ M1（资产目录 MVP）** 批次的可运行代码。

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
| 关系摄取（双通道） | `LogicalModelRelationParser`：03-逻辑数据模型 `FK[目标·依据]` → 408 边（五级证据+置信度+待确认） | ✅ |
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

## 关键设计口径（对齐评审结论）

- **结构 ← DDL，关系 ← ER 证据**：本库 0 外键，`DdlParser` 只摄取表/列结构；
  关系摄取自已落地于 `er-model/03-逻辑数据模型` 的 `FK[目标·依据]` 键列（M1）。
- **合并行拆回原子列**：`meta_column` 按物理 DDL 逐列存储（含审计四件套），
  `source_layer=physical`，保证列数与 DDL 严格一致。
- **asset_urn 稳定身份**：`mysql:{schema}:{table}`，跨版本 diff 与血缘以此为锚点。

## 目录结构

```
dam-app/
├── backend/                # SpringBoot（com.dam: M0 范围）
│   └── src/main/java/com/dam/{parser,ingest,domain,repository,web,config}
├── frontend/               # Vue3 + Vite + TS
└── docker-compose.yml      # MySQL 8（可选 profile）
```
