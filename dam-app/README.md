# dam-app · 数据资产管理应用（M0 批次）

按 [PLAN.md](../PLAN.md) §6 分期路线图实施，本目录为 **M0（POC 奠基）** 批次的可运行代码。

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

## 关键设计口径（对齐评审结论）

- **结构 ← DDL，关系 ← ER 证据**：本库 0 外键，`DdlParser` 只摄取表/列结构；
  关系摄取自 `er-model/01-ER图` 证据摘录表在 M1 落地。
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
