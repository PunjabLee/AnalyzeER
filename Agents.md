# Agents.md — AnalyzeER 工程说明

> 本文档基于对工程内实际文件（`test_erp.sql`、`er-model/**`、`skills/**`、`.qoder/**`、`.gitignore`、Git 历史等）的逐文件阅读生成，不含未经核实的推测。信息缺口在文末「待确认项」显式列出。

---

## 1. 项目概览

**AnalyzeER 是一个「数据库 DDL 逆向数据建模」的文档工程**，而非可运行的应用程序（无源码、无构建、无运行时依赖）。

- **唯一事实来源（输入）**：根目录的 `test_erp.sql` —— 一份 MySQL 纺织外贸 ERP 的建表脚本（DDL 导出，UTF-8 / CRLF 行尾，约 2.1 MB、28,000+ 行，实测 `CREATE TABLE` **1322** 张表）。
- **核心产出（输出）**：`er-model/` 目录下按业务域组织的数据模型交付物集（Mermaid ER 图 + 逐表全字段逻辑数据模型 + 分级清单 + 跨域总览 + 问题清单）。
- **方法沉淀**：`skills/mysql-ddl-data-modeling/`（及其镜像 `.qoder/skills/`）将本项目的逆向建模流程固化为一个可分发的 **Agent Skill**（九阶段工作流 + 模板集 + PowerShell 实测脚本）。

关键结论：全库 **0 条显式 `FOREIGN KEY` / `CONSTRAINT` 约束**，所有表间关系均为**逆向推断关系**，须按证据分级标注。

Git：`master` 分支，采用 `feat/fix/docs/chore(域号): 说明（n 表，累计 m/1322）` 的批次化提交规范，按业务域一批一提交。

---

## 2. 目录结构与模块职责

| 路径 | 类型 | 职责 |
|---|---|---|
| `test_erp.sql` | 输入数据源 | 唯一事实来源；1322 张 `CREATE TABLE`。首表为 Quartz 框架表（`N0DD15FF_BLOB_TRIGGERS` 等），业务表以 `jf_` 前缀为主 |
| `er-model/` | 核心产出 | 数据模型交付物集（详见第 3 节） |
| `skills/mysql-ddl-data-modeling/` | 方法资产（已纳入版本库） | Agent Skill：`SKILL.md`（工作流）+ `templates.md`（模板 T0–T8）+ `scripts/`（3 个 `.ps1` 实测脚本） |
| `.qoder/skills/mysql-ddl-data-modeling/` | Skill 运行时镜像 | 与 `skills/` 内容**完全一致**（`diff` 校验无差异）；`.qoder/` 已被 `.gitignore` 排除，供 Qoder 加载 |
| `_tree.txt` | 辅助 | `er-model/` 目录清单快照（Windows 反斜杠路径风格） |
| `AnalyzeER.iml` | IDE 配置 | IntelliJ `GENERAL_MODULE`，无语言/构建配置，仅内容根目录（印证"纯文档工程"定位） |
| `.idea/` | IDE 配置 | JetBrains 工程配置（`misc.xml` 等），已 gitignore |
| `.gitignore` | 配置 | 排除临时工作文件 `_*`/`er-model/_*`、IDE 目录、`.qoder/` |
| `Agents.md` | 说明 | 本文件 |

---

## 3. `er-model/` 交付物结构（数据模型产出）

```
er-model/
├── 00-总览与分组清单.md          # 事实基线 + 新旧差异 + A/B/C 分级 + 18 域分组 + 数量校验 + 交付进度 + 终验
├── 01-ER图/Dxx-<域名>.md          # 每业务域一张 Mermaid erDiagram + 关系证据摘录表（共 18 域 + B级 + OT）
├── 03-逻辑数据模型/Dxx-<域名>.md  # 每业务域逐表全字段 Markdown 表格（固定列：字段|类型|可空|默认|键|含义|关联）
├── 04-C级备份与测试表清单.md      # C 级 120 表逐表点名 + 排除建模理由
└── 05-跨域核心关系总览.md         # 全局图(5 张子图) + 核心实体三分类 + 模型问题清单(A–G 共 30 条) + 总体研判
```

> 注意：目录中**没有 `02-` 前缀文件**（编号从 `01` 跳到 `03`）。B 级平台/框架同构表直接放在 `01-ER图/` 与 `03-逻辑数据模型/` 下的 `B级-平台框架同构表.md`，而非独立的 `02-` 目录。是否为刻意编号或历史遗留，见待确认项。

### 3.1 表覆盖分级口径（A/B/C，`00-总览` 第三节）

| 级别 | 范围 | 表数 | 处理深度 |
|---|---|---|---|
| **A** | `jf_` 真实业务表 332 + 无前缀业务/遗留表 17 | **349** | 逐表全字段 ER + 逻辑模型 |
| **B** | `lcap_` 平台表 450 + Quartz 275 + Activiti/Flowable(`p{hex}_`) 128 | **853** | 按结构族各展开 1 代表，其余归类点名 |
| **C** | `*_bak_<时间戳>` 备份表 117 + `test_` 测试表 3 | **120** | 清单点名 + 排除理由 |
| 合计 | | **1322** | 349 + 853 + 120 = 1322 ✓ |

### 3.2 A 级业务域（18 个 + OT，`00-总览` 第四节，含逐表点名）

D01 销售订单(18)、D02 备货与报价(21)、D03 合同与信用额度(19)、D04 应收核销与收款(33)、D05 发票与税务(13)、D06 费用与结算(15)、D07 退换货与回修(23)、D08 客户与贸易商(27)、D09 商品与产品主数据(31)、D10 库存与仓储(24)、D11 优惠券与权益(21)、D12 返利·对账·账单(11)、D13 销售目标与统计(16)、D14 商品属性与基础字典(34)、D15 质量管理(6)、D16 系统与协作配置(10)、D17 物流与发货(6)、D18 项目与打样(4) = `jf_` 前缀 **332**；另 OT 无前缀遗留 **17**，合计 A 级 **349**。

---

## 4. 数据模型核心结构（业务域与关键实体）

这是一套 **"低代码平台（`lcap_` / 明道云类）+ 多引擎（Quartz 调度 + Activiti/Flowable 工作流）+ 纺织外贸 ERP 业务（`jf_`）"三层叠加**的库。业务表约 332 张，被 900+ 张平台/引擎/备份同构表放大至 1322。

**核心实体枢纽**（`05-跨域核心关系总览.md`）：以 `jf_sales_order`（销售订单）为全库交易枢纽。

- **主数据骨干**：`jf_trader`（贸易商）→ `jf_customer`（客户）→ `jf_customer_account`；`jf_product`（SPU）→ `jf_goods`（SKU）；`jf_company` / `jf_brand` / `*_category`。
- **交易主干**：`jf_customer/jf_trader → jf_contract_information → jf_sales_order → jf_sales_order_detail`，下游挂 `jf_invoice`、`jf_receivable_claim`、`jf_sales_return`、`jf_shipping_progress`。
- **资金链路**：`jf_receivable_claim → jf_receivable_write_off → jf_collection_bill → jf_income_bill → jf_invoice`；额度链路 `jf_trader_quota → jf_customer_credit_line_use_log`。
- **支撑域**：备货报价（D02）、质量（D15）、返利对账（D12）、库存批次（D10）、字典（D14，被广泛引用）。
- **平台耦合（B 级）**：`lcap_user` RBAC、Activiti `P_act_ru_task` 工作流→OA 审批（`jf_sales_order_oa_audit`）、Quartz 定时任务。

### 4.1 关系证据分级（全库 0 外键，逐条标注，`00-总览` 第六节）

强度降序：`[显式外键]`（本库为 0，不出现） > `[注释明示]`（字段 COMMENT 点名目标表，最强）> `[索引佐证]`（`_id`/`_code` 列建有 INDEX/UNIQUE）> `[命名推断]/[字段命名]` > `[语义推断]` > `[待确认]`。ER 关系线格式：`A ||--o{ B : "1:N [依据] a.col -> b.id"`（`1:1` 用 `||--||`，强制多行 `||--|{`）。

### 4.2 逻辑数据模型字段表列约定

`| 字段 | 类型 | 可空 | 默认值 | 主/外键线索 | 字段含义 | 与其他表的关联 |`，可空列 `N`=NOT NULL、`Y`=可空，键列写作 `PK` / `PK(非自增)` / `UK` / `FK[目标表·依据]` / `[待确认]`。

### 4.3 已识别的模型质量问题（`05` 文件 A–G 七类，共 30 条）

A 引用完整性（0 外键、多态外键无判别约束）、B 冗余与反范式（id+文本双写快照、超宽表月度横向展开、同名列异指向高危）、C 命名一致性（五套前缀并存、拼写错误固化入库如 `catregory`/`datat_dict`/`developmen_approval`）、D 类型与取值（三套字符集混用、主键策略不一、外键列类型宽度不一）、E 审计与逻辑删除（`delete_status` vs `del_flag` 不一致、11 张无主键表）、F 语义与归属、G 数据治理与安全（PII 明文凭据、备份/测试表混居生产、按应用复制物理表致膨胀）。

---

## 5. 脚本与工具链（`skills/.../scripts/`）

三个 **PowerShell** 脚本，服务于 Skill 工作流的实测取证（非应用逻辑）：

| 脚本 | 阶段 | 作用 | 产出 |
|---|---|---|---|
| `ddl_census.ps1` | 阶段 1 普查 | 宽松正则统计 `CREATE TABLE` 去重数、FK/CONSTRAINT/PK 计数、逐表列数、无主键/无注释表、前缀分布 | `_line_map.txt`、`_col_counts.txt`、`_nopk_tables.txt`、`_prefix_dist.txt` |
| `compare_ddl_sources.ps1` | 阶段 2 差异 | 新旧 SQL 对比新增/删除/保留集合 + 保留表列数变更取证 + 集合恒等式校验 | `_diff_added/dropped/retained/changed.txt` |
| `group_by_domain.ps1` | 阶段 4 分组 | **有序正则首匹配**自动归域 + 零遗漏校验（规则需按当次表清单改写） | `_grouped.txt` |

调用示例：
```
powershell -NoProfile -ExecutionPolicy Bypass -File scripts/ddl_census.ps1 -SqlFile <ddl.sql> [-OutDir <dir>]
```

---

## 6. 运行方式与依赖

- **本仓库无可执行应用程序**：没有 `package.json` / `pom.xml` / `requirements.txt` 等依赖清单，无编译或启动命令。"运行"仅指对 `test_erp.sql` 执行建模工作流并产出/更新 `er-model/` 文档。
- **重跑脚本依赖**：Windows + PowerShell 5.1（`SKILL.md`「注意事项」明确针对 PS 5.1 环境；`.ps1` 脚本注释强制纯 ASCII 以避免 GBK 解码吞换行）。用户当前 OS 为 macOS，直接运行这些 `.ps1` 需 PowerShell Core（`pwsh`），且脚本内 Windows 风格路径需注意兼容。
- **查看产出**：`er-model/*.md` 中的 Mermaid 图需支持 Mermaid 渲染的 Markdown 查看器（IDE 插件 / 兼容平台）；`analyzer` 类工具不参与。

---

## 7. 开发/维护注意事项

1. **唯一事实来源不可绕过**：任何模型结论必须能回溯到 `test_erp.sql` 具体行；SQL 未明示的关系/含义一律标 `[推断]` 或 `[待确认]`，**禁止臆造**。
2. **禁止沿用旧结论**：数据源变更后相关产物须重生成；旧版 `all-defaultDS-mysql.sql`（169 表）结论已作废，仅作差异参照。
3. **数量口径硬校验链**：`表总数 = A + B + C = Σ各域分组（桩不计入）= 已输出表数`；任阶段不成立先修复再交付。当前终验：1322 = 349 + 853 + 120，未输出/遗漏 = 0。
4. **分组脚本防贪婪匹配**：用 `^jf_product$|^jf_product_` 而非裸 `^jf_product`（否则误吞 `jf_production_scale`）；特例规则置于贪婪规则之前。
5. **临时文件治理**：所有 `_*` 中间产物（`_line_map.txt` 等）已 gitignore，终验后应清理；`Read` 工具返回的 `行号→` 前缀属元数据，**严禁写入交付文件**。
6. **大文件读取**：`test_erp.sql` 为 UTF-8 + CRLF、2.1 MB，检索/计数以内置 Grep/脚本独立通道复核为准，按 `_line_map.txt` 行号分块精读。
7. **交付批次化**：按域一批一提交，提交信息含累计覆盖数；Skill 目录（`skills/`）为通用 Agent Skills 格式，可移植分发（Qoder / Claude Code / Codex）。
8. **Skill 双副本同步**：修改 Skill 时需同步 `skills/` 与 `.qoder/skills/` 两份（当前一致，`.qoder/` 未纳入版本库）。

---

## 8. 关键文件速查

- 事实基线与全量校验：[er-model/00-总览与分组清单.md](file:///Users/punjab/Documents/Workspace/AnalyzeER/er-model/00-总览与分组清单.md)
- 跨域总览与问题清单：[er-model/05-跨域核心关系总览.md](file:///Users/punjab/Documents/Workspace/AnalyzeER/er-model/05-跨域核心关系总览.md)
- 交易枢纽样例：[er-model/01-ER图/D01-销售订单域.md](file:///Users/punjab/Documents/Workspace/AnalyzeER/er-model/01-ER图/D01-销售订单域.md) 与 [er-model/03-逻辑数据模型/D01-销售订单域.md](file:///Users/punjab/Documents/Workspace/AnalyzeER/er-model/03-逻辑数据模型/D01-销售订单域.md)
- 方法论：[skills/mysql-ddl-data-modeling/SKILL.md](file:///Users/punjab/Documents/Workspace/AnalyzeER/skills/mysql-ddl-data-modeling/SKILL.md)、[templates.md](file:///Users/punjab/Documents/Workspace/AnalyzeER/skills/mysql-ddl-data-modeling/templates.md)

---

## 9. 待确认项（需人工核实）

1. **`02-` 编号缺失**：`er-model/` 目录从 `01-` 跳到 `03-`，无独立 `02-` 目录。是刻意编排还是历史遗留？排查建议：核对 `SKILL.md` 交付物结构定义与实际目录，确认 B 级文件归类是否符合预期。
2. **行数口径不一致**：`00-总览` 记 `test_erp.sql` 为 **26,998** 行，而 `wc -l` 实测 **28,339** 行（CRLF/编码差异可能导致行计数不同）。排查建议：统一以脚本（`Get-Content` 数组索引）为行号基准并复核差异来源，必要时在 `00-总览` 注明来源口径。
3. **旧数据源文件缺失**：文档多次对比 `all-defaultDS-mysql.sql`（169 表），但该文件不在仓库内。无法在当前工程复现阶段 2 的新旧差异取证。排查建议：确认旧 SQL 是否应纳入版本库或仅外部参照。
4. **路径来源为 Windows**：`00-总览` 引用 `D:\GitSourceCode\AnalyzeER\test_erp.sql`，脚本面向 PS 5.1/Windows；当前工程在 macOS。排查建议：确认是否需要跨平台运行脚本（改用 `pwsh` 并核对路径/编码兼容性）。
5. **`[推断]`/`[待确认]` 关系待落库核实**：全库 0 外键，所有关系为命名/注释/索引推断。真实关联与基数需结合应用层代码或生产数据验证；多态外键（如 `flow_change_record.related_order_id`）与"同名列异指向"高危项应优先人工确认。
6. **无主键表与业务风险**：11 张无 PK 表（含正式业务表 `jf_contract_termination`、`jf_inventory_organization`）是否为导出丢失或真实设计缺陷？排查建议：与生产 schema 比对确认。

> 说明：以上第 3、4 项属外部输入/环境事实，仓库内文件无法直接证实，标注为待人工确认；其余项可通过对 `test_erp.sql` 与 `er-model/` 产物的进一步逐域精读核验。
