# OT 无前缀遗留表 — 逻辑数据模型

> 数据源：`test_erp.sql`（唯一事实来源），逐表全字段核对。本组共 **17 张表**。
> 全库 **0 条显式外键约束**，"主/外键线索"与"关联"列均为**推断**，依据分级：`[COMMENT注释明示]` / `[索引佐证]` / `[字段命名]` / `[业务语义推断]`。SQL 未明示标"推断/待确认"。

---

### country（国家表）

> utf8mb4_unicode_ci；`id` **非自增**。

| 字段名 | 类型 | 可空 | 默认值 | 主/外键线索 | 字段含义 | 关联（推断） |
|---|---|---|---|---|---|---|
| id | bigint | 否 | 无 | PK（非自增） | 主键 | — |
| created_time | datetime | 是 | NULL | — | 创建时间 | — |
| updated_time | datetime | 是 | NULL | — | 更新时间 | — |
| created_by | varchar(255) | 是 | NULL | — | 创建者 | — |
| updated_by | varchar(255) | 是 | NULL | — | 更新者 | — |
| name | varchar(255) | 是 | NULL | — | 国家名称 | — |
| status | varchar(255) | 是 | '0' | 类型不一（varchar 存状态） | 状态 0-禁用 1-启用 | — |
| sort | bigint | 是 | 50 | — | 排序值 | — |
| delete_status | tinyint(1) | 是 | 0 | — | 删除状态 | — |

### sale_type（销售类型）

> utf8mb4_unicode_ci；`id` **非自增**。

| 字段名 | 类型 | 可空 | 默认值 | 主/外键线索 | 字段含义 | 关联（推断） |
|---|---|---|---|---|---|---|
| id | bigint | 否 | 无 | PK（非自增） | 主键 | — |
| created_time | datetime | 是 | NULL | — | 创建时间 | — |
| updated_time | datetime | 是 | NULL | — | 更新时间 | — |
| created_by | varchar(255) | 是 | NULL | — | 创建者 | — |
| updated_by | varchar(255) | 是 | NULL | — | 更新者 | — |
| name | varchar(255) | 否 | 无 | — | 销售类型名称 | — |
| status | varchar(255) | 否 | '0' | 类型不一（varchar 存状态） | 状态 | — |
| sort_value | bigint | 否 | 50 | 命名（应 sort） | 排序值 | — |
| delete_status | tinyint(1) | 是 | 0 | — | 删除状态 | — |

### personnel（人员表）

> utf8mb4_unicode_ci；`id` **非自增**；**无 delete_status**；含 PII 明文字段。

| 字段名 | 类型 | 可空 | 默认值 | 主/外键线索 | 字段含义 | 关联（推断） |
|---|---|---|---|---|---|---|
| id | bigint | 否 | 无 | PK（非自增） | 主键 | — |
| created_time | datetime | 是 | NULL | — | 创建时间 | — |
| updated_time | datetime | 是 | NULL | — | 更新时间 | — |
| created_by | varchar(255) | 是 | NULL | — | 创建者 | — |
| updated_by | varchar(255) | 是 | NULL | — | 更新者 | — |
| employee_no | varchar(255) | 是 | NULL | — | 工号id | — |
| name | varchar(255) | 是 | NULL | — | 姓名 | — |
| picture | varchar(255) | 是 | NULL | — | 照片 | — |
| gender | varchar(255) | 是 | NULL | — | 性别 | — |
| contact_information | varchar(255) | 是 | NULL | PII 明文 | 工作电话 | — |
| id_number | varchar(255) | 是 | NULL | **PII 明文（身份证）** | 身份证号 | — |
| position_id | bigint | 是 | NULL | FK线索 | 岗位id | → position.id `[字段命名]` |
| department_id | varchar(255) | 是 | NULL | FK线索（varchar 存 id） | 部门id | → D16 部门 `[字段命名]` |
| join_date | date | 是 | NULL | — | 入职日期 | — |
| leave_date | date | 是 | NULL | — | 离职日期 | — |
| status | varchar(255) | 是 | NULL | — | 在职状态 | — |
| is_manager | varchar(255) | 是 | NULL | — | 是否为部门负责人 | — |

### position（岗位表）

> utf8mb4_unicode_ci；`id` **非自增**。

| 字段名 | 类型 | 可空 | 默认值 | 主/外键线索 | 字段含义 | 关联（推断） |
|---|---|---|---|---|---|---|
| id | bigint | 否 | 无 | PK（非自增） | 主键 | — |
| created_time | datetime | 是 | NULL | — | 创建时间 | — |
| updated_time | datetime | 是 | NULL | — | 更新时间 | — |
| created_by | varchar(255) | 是 | NULL | — | 创建者 | — |
| updated_by | varchar(255) | 是 | NULL | — | 更新者 | — |
| name | varchar(255) | 是 | NULL | — | 岗位名称 | — |
| status | tinyint(1) | 是 | 1 | — | 状态 | — |
| department_id | varchar(255) | 是 | NULL | FK线索（varchar 存 id） | 部门id | → D16 部门 `[字段命名]` |
| delete_status | tinyint(1) | 是 | 0 | — | 删除状态 0：否 1：是 | — |

### flow_change_record（流水变更记录表）

> utf8mb4_unicode_ci；AUTO_INCREMENT=1；贸易商额度变更审计。

| 字段名 | 类型 | 可空 | 默认值 | 主/外键线索 | 字段含义 | 关联（推断） |
|---|---|---|---|---|---|---|
| id | bigint | 否 | 自增 | PK | 主键ID | — |
| related_order_id | bigint | 否 | 无 | FK线索（多态） | 关联单号ID | → D01/D04/D06 单据（按 order_type）`[COMMENT注释明示]`+`[待确认]` |
| order_type | int | 否 | 无 | 多态判别 | 订单类型（采购单/销售单/退款单/调账单等） | — |
| amount_type | int | 否 | 无 | — | 金额类型（可用/冻结/已用/总额） | — |
| initial_amount | decimal(20,6) | 否 | 0.000000 | — | 变更前金额 | — |
| final_amount | decimal(20,6) | 否 | 0.000000 | — | 变更后金额 | — |
| change_amount | decimal(20,6) | 否 | 0.000000 | 派生 | 变动金额（=final-initial） | — |
| trader_id | bigint | 否 | 无 | FK线索 | 贸易商ID | → D08 贸易商 / D03 信用额度 `[字段命名]` |
| created_time | datetime | 否 | 无 | — | 创建时间 | — |
| created_by | varchar(64) | 否 | 无 | — | 创建人 | — |
| updated_time | datetime | 否 | 无 | — | 修改时间 | — |
| updated_by | varchar(64) | 否 | 无 | — | 更新人 | — |
| delete_status | tinyint | 否 | 0 | 类型不一（非 tinyint(1)） | 删除状态 | — |

### sales_daily_stats（日销售统计表）

> utf8mb4_general_ci；`id` **非自增**；索引 idx_org_date。

| 字段名 | 类型 | 可空 | 默认值 | 主/外键线索 | 字段含义 | 关联（推断） |
|---|---|---|---|---|---|---|
| id | bigint | 否 | 无 | PK（非自增） | 主键 | — |
| created_time | datetime | 是 | NULL | — | 创建时间 | — |
| updated_time | datetime | 是 | NULL | — | 更新时间 | — |
| created_by | varchar(255) | 是 | NULL | — | 创建者 | — |
| updated_by | varchar(255) | 是 | NULL | — | 更新者 | — |
| org_id | varchar(255) | 是 | NULL | FK线索 | 组织 ID（集团/子公司/小组） | → D16 组织 `[字段命名]` |
| org_type | varchar(255) | 是 | NULL | — | 组织类型：group/company/team | — |
| stat_date | date | 是 | NULL | 索引列 | 统计日期 | — |
| real_sales | bigint | 是 | NULL | — | 实时销售（条） | — |
| cut_board_count | bigint | 是 | NULL | — | 剪板次数 | — |
| order_customer_count | bigint | 是 | NULL | — | 下单客户数 | — |
| fabric_inventory | bigint | 是 | NULL | — | 色布库存总数 | — |
| last_update_time | datetime | 是 | NULL | — | 统计截止时间 | — |

### sales_monthly_stats（月销售统计表）

> utf8mb4_general_ci；`id` **非自增**；索引 idx_org_date；结构同日表，粒度改为月。

| 字段名 | 类型 | 可空 | 默认值 | 主/外键线索 | 字段含义 | 关联（推断） |
|---|---|---|---|---|---|---|
| id | bigint | 否 | 无 | PK（非自增） | 主键 | — |
| created_time | datetime | 是 | NULL | — | 创建时间 | — |
| updated_time | datetime | 是 | NULL | — | 更新时间 | — |
| created_by | varchar(255) | 是 | NULL | — | 创建者 | — |
| updated_by | varchar(255) | 是 | NULL | — | 更新者 | — |
| org_id | varchar(255) | 是 | NULL | FK线索 | 组织 ID | → D16 组织 `[字段命名]` |
| org_type | varchar(255) | 是 | NULL | — | 组织类型：group/company/team | — |
| stat_month | varchar(255) | 是 | NULL | 索引列（varchar 存月） | 统计月份 | — |
| real_sales | bigint | 是 | NULL | — | 实时销售（条） | — |
| cut_board_count | bigint | 是 | NULL | — | 剪板次数 | — |
| order_customer_count | bigint | 是 | NULL | — | 下单客户数 | — |
| fabric_inventory | bigint | 是 | NULL | — | 色布库存总数 | — |
| last_update_time | datetime | 是 | NULL | — | 统计截止时间 | — |

### sys_dict_type（字典类型表）

> utf8mb4_general_ci；AUTO_INCREMENT=2.1e18（雪花量级）。

| 字段名 | 类型 | 可空 | 默认值 | 主/外键线索 | 字段含义 | 关联（推断） |
|---|---|---|---|---|---|---|
| id | bigint | 否 | 自增 | PK | id | — |
| name | varchar(255) | 是 | NULL | — | 字典类型名称（如：特殊工艺） | — |
| type | varchar(100) | 否 | 无 | UK线索·索引 uk_type | 字典类型编码（如 jf_special_craft） | → sys_dict_data.type `[字段命名/索引佐证]` |
| status | tinyint(1) | 否 | 1 | — | 状态 | — |
| sort | int | 是 | 50 | — | 排序值 | — |
| created_time | datetime | 是 | NULL | — | 创建时间 | — |
| created_by | varchar(255) | 是 | NULL | — | 创建者 | — |
| updated_time | datetime | 是 | NULL | — | 更新时间 | — |
| updated_by | varchar(255) | 是 | NULL | — | 更新者 | — |
| delete_status | tinyint(1) | 否 | 0 | — | 是否已删除 | — |
| remark | varchar(100) | 是 | NULL | — | 备注 | — |

### sys_dict_data（字典数据表）

> utf8mb4_general_ci；AUTO_INCREMENT=2.1e18（雪花量级）；索引 idx_type_sort、sys_dict_data_type_IDX。

| 字段名 | 类型 | 可空 | 默认值 | 主/外键线索 | 字段含义 | 关联（推断） |
|---|---|---|---|---|---|---|
| id | bigint | 否 | 自增 | PK | id | — |
| type | varchar(100) | 否 | 无 | FK线索·索引 | 字典类型编码（关联 sys_dict_type.type） | → sys_dict_type.type `[COMMENT注释明示/索引佐证]` |
| name | varchar(255) | 是 | NULL | — | 字典名称/标签 | — |
| value | varchar(255) | 是 | NULL | — | 字典键值 | — |
| status | tinyint(1) | 否 | 1 | — | 状态 | — |
| sort | int | 是 | 50 | — | 排序值 | — |
| created_time | datetime | 是 | NULL | — | 创建时间 | — |
| created_by | varchar(255) | 是 | NULL | — | 创建者 | — |
| updated_time | datetime | 是 | NULL | — | 更新时间 | — |
| updated_by | varchar(255) | 是 | NULL | — | 更新者 | — |
| delete_status | tinyint(1) | 否 | 0 | — | 是否已删除 | — |
| remark | varchar(100) | 是 | NULL | — | 备注 | — |

### node_app（节点应用表）

> utf8mb4_general_ci；`id` **非自增**；UNIQUE uni_app_id_node_app。平台低代码应用注册。

| 字段名 | 类型 | 可空 | 默认值 | 主/外键线索 | 字段含义 | 关联（推断） |
|---|---|---|---|---|---|---|
| id | bigint | 否 | 无 | PK（非自增） | id | — |
| created_time | datetime | 是 | NULL | — | 创建时间 | — |
| updated_time | datetime | 是 | NULL | — | 更新时间 | — |
| created_by | varchar(255) | 是 | NULL | — | 创建者 | — |
| updated_by | varchar(255) | 是 | NULL | — | 更新者 | — |
| app_id | varchar(255) | 是 | NULL | UK（varchar 存 id） | 应用id | — |
| app_name | varchar(255) | 是 | NULL | — | 应用名称 | — |
| app_link_url | varchar(255) | 是 | NULL | — | 访问链接 | — |
| app_icon | varchar(255) | 是 | NULL | — | 应用图标 | — |
| entry | varchar(255) | 是 | NULL | — | 地址 | — |
| active_rule | varchar(255) | 是 | NULL | — | 路径 | — |

### sidebar_node（侧边栏节点）

> utf8mb4_general_ci；`id` **非自增**；删除字段为 **del_flag**（异于常规）；pid 自关联树。

| 字段名 | 类型 | 可空 | 默认值 | 主/外键线索 | 字段含义 | 关联（推断） |
|---|---|---|---|---|---|---|
| id | bigint | 否 | 无 | PK（非自增） | 主键 | — |
| created_time | datetime | 是 | NULL | — | 创建时间 | — |
| updated_time | datetime | 是 | NULL | — | 更新时间 | — |
| created_by | varchar(255) | 是 | NULL | — | 创建者 | — |
| updated_by | varchar(255) | 是 | NULL | — | 更新者 | — |
| value | varchar(255) | 是 | NULL | — | 值 | — |
| value_path | varchar(255) | 是 | NULL | 反范式路径 | 到当前级 value 路径 | — |
| name | varchar(255) | 是 | NULL | — | 模块名称 | — |
| name_path | varchar(255) | 是 | NULL | 反范式路径 | 到当前级模块名称路径 | — |
| url | varchar(255) | 是 | NULL | — | 链接 | — |
| img | varchar(255) | 是 | NULL | — | 图标 | — |
| level | tinyint(1) | 是 | NULL | — | 级别 | — |
| hove_img | varchar(255) | 是 | NULL | 拼写(hover) | 选中图标-仅3级 | — |
| pid | bigint | 是 | 0 | 自关联FK线索 | 父级id | → sidebar_node.id `[字段命名]` |
| del_flag | tinyint(1) | 是 | 0 | **命名不一致（应 delete_status）** | 删除标记 | — |
| sort | bigint | 是 | 1 | — | 顺序 | — |
| type | bigint | 是 | 0 | — | 类型(0系统内;1系统外;2导入系统内) | — |
| enable | tinyint(1) | 是 | 1 | — | 是否启用 | — |

### structure_table（数据结构共享表）

> utf8mb4_general_ci；`id` **非自增**；**v1~v50 泛化列**（text/bigint/varchar 混合），EAV 反模式，无业务语义。

| 字段名 | 类型 | 可空 | 默认值 | 主/外键线索 | 字段含义 | 关联（推断） |
|---|---|---|---|---|---|---|
| id | bigint | 否 | 无 | PK（非自增） | 主键 | — |
| created_time | datetime | 是 | NULL | — | 创建时间 | — |
| updated_time | datetime | 是 | NULL | — | 更新时间 | — |
| created_by | varchar(255) | 是 | NULL | — | 创建者 | — |
| updated_by | varchar(255) | 是 | NULL | — | 更新者 | — |
| v1~v12 | text | 是 | NULL | — | 泛化文本槽位（无字段注释） | — |
| v13、v14 | bigint | 是 | NULL | — | 泛化数值槽位 | — |
| v15~v17 | text | 是 | NULL | — | 泛化文本槽位 | — |
| v18 | bigint | 是 | NULL | — | 泛化数值槽位 | — |
| v19 | text | 是 | NULL | — | 泛化文本槽位 | — |
| v20、v22~v25 | bigint | 是 | NULL | — | 泛化数值槽位 | — |
| v21 | text | 是 | NULL | — | 泛化文本槽位 | — |
| v26~v30 | text | 是 | NULL | — | 泛化文本槽位 | — |
| v31、v32 | bigint | 是 | NULL | — | 泛化数值槽位 | — |
| v33~v39 | text | 是 | NULL | — | 泛化文本槽位 | — |
| v40 | bigint | 是 | NULL | — | 泛化数值槽位 | — |
| v41 | varchar(255) | 是 | NULL | — | 泛化字符槽位 | — |
| v42 | text | 是 | NULL | — | 泛化文本槽位 | — |
| v43 | bigint | 是 | NULL | — | 泛化数值槽位 | — |
| v44 | varchar(255) | 是 | NULL | — | 泛化字符槽位 | — |
| v45 | text | 是 | NULL | — | 泛化文本槽位 | — |
| v46 | varchar(255) | 是 | NULL | — | 泛化字符槽位 | — |
| v47 | bigint | 是 | NULL | — | 泛化数值槽位 | — |
| v48、v49 | text | 是 | NULL | — | 泛化文本槽位 | — |
| v50 | varchar(255) | 是 | NULL | — | 泛化字符槽位 | — |

### seq_10（数字序列辅助表）

> utf8mb4_general_ci；无表注释、无审计字段；仅 1 列，平台生成序列用。

| 字段名 | 类型 | 可空 | 默认值 | 主/外键线索 | 字段含义 | 关联（推断） |
|---|---|---|---|---|---|---|
| n | int | 否 | 无 | PK | 序列数字 | — |

### entity1（空壳测试表）

> utf8mb4_general_ci；无表注释、无 delete_status；开发遗留。

| 字段名 | 类型 | 可空 | 默认值 | 主/外键线索 | 字段含义 | 关联（推断） |
|---|---|---|---|---|---|---|
| id | bigint | 否 | 无 | PK（非自增） | 主键 | — |
| created_time | datetime | 是 | NULL | — | 创建时间 | — |
| updated_time | datetime | 是 | NULL | — | 更新时间 | — |
| created_by | varchar(255) | 是 | NULL | — | 创建者 | — |
| updated_by | varchar(255) | 是 | NULL | — | 更新者 | — |
| title | varchar(255) | 是 | NULL | 无注释 | title | — |

### entity12345（空壳测试表）

> utf8mb4_general_ci；无表注释、无 delete_status；开发遗留。

| 字段名 | 类型 | 可空 | 默认值 | 主/外键线索 | 字段含义 | 关联（推断） |
|---|---|---|---|---|---|---|
| id | bigint | 否 | 无 | PK（非自增） | 主键 | — |
| created_time | datetime | 是 | NULL | — | 创建时间 | — |
| updated_time | datetime | 是 | NULL | — | 更新时间 | — |
| created_by | varchar(255) | 是 | NULL | — | 创建者 | — |
| updated_by | varchar(255) | 是 | NULL | — | 更新者 | — |
| name | varchar(255) | 是 | NULL | 无注释 | name | — |
| type | varchar(255) | 是 | NULL | 无注释 | type | — |

### sheet1（Excel 导入临时表）

> utf8mb4_general_ci；表名 Sheet1（导入源工作表名残留）；property1~5 泛化列；无 delete_status。

| 字段名 | 类型 | 可空 | 默认值 | 主/外键线索 | 字段含义 | 关联（推断） |
|---|---|---|---|---|---|---|
| id | bigint | 否 | 无 | PK（非自增） | id | — |
| property1 | varchar(255) | 是 | NULL | 泛化列名 | 员工姓名 | — |
| property2 | bigint | 是 | NULL | 泛化列名 | 年龄 | — |
| property3 | varchar(255) | 是 | NULL | 泛化列名 | 手机号 | — |
| property4 | varchar(255) | 是 | NULL | 泛化列名 | 邮箱 | — |
| property5 | varchar(255) | 是 | NULL | 泛化列名 | 最高学历 | — |

### temp（临时表）

> utf8mb4_general_ci；表注释 'temp'；无审计/删除字段；id 为 **int**（非 bigint）。

| 字段名 | 类型 | 可空 | 默认值 | 主/外键线索 | 字段含义 | 关联（推断） |
|---|---|---|---|---|---|---|
| id | int | 否 | 无 | PK（int 非 bigint，非自增） | id | — |
| color_id | int | 是 | NULL | FK线索（int） | 颜色id | → D14 颜色字典 `[字段命名]` |

---

## 建模问题清单（本组·汇总）

- **P-OT-01 表归属不明**：17 表混杂业务配置/平台框架/测试遗留，无 `jf_` 前缀，应与业务表隔离归档。
- **P-OT-02 测试/空壳表混入正式库**：entity1/entity12345/sheet1/temp/seq_10 为开发或导入临时产物，无生产价值。
- **P-OT-03 PII 明文风险**：personnel.id_number/contact_information 明文存储。
- **P-OT-04 varchar 存 id**：personnel/position.department_id、node_app.app_id 用 varchar。
- **P-OT-05 逻辑删除字段不一致**：sidebar_node 用 del_flag；personnel/position/entity*/sheet1/temp/seq_10 无删除字段。
- **P-OT-06 status 类型不一**：country/sale_type.status 用 varchar('0')。
- **P-OT-07 泛化列 EAV 反模式**：structure_table v1~v50、sheet1 property1~5 语义丧失。
- **P-OT-08 主键策略不一**：多数 id 非自增；sys_dict_* AUTO_INCREMENT 达雪花量级（2.1e18）；temp.id 为 int。
- **P-OT-09 字符集混用**：unicode_ci 与 general_ci 并存。
- **P-OT-10 多态外键**：flow_change_record.related_order_id 依 order_type 指向不同单据表 `[COMMENT注释明示]`+`[待确认]`。
