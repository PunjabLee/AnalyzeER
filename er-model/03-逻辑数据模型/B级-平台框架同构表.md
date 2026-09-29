# B 级 · 平台/框架同构表 — 逻辑数据模型（代表展开 + 归类）

> 数据源：`test_erp.sql`。B 级 853 表为高度同构的平台/框架表，此处**每结构族展开 1 个代表**并给出全字段逻辑模型，其余成员以"归类清单"点名（结构完全相同，不重复展开）。全库 0 外键，关系标 `[业务语义推断]`。

---

## 族① Quartz 定时调度器（代表集 `N0DD15FF_`；结构 ×25 套 = 275 表）

> 通用特征：utf8mb4_general_ci；复合主键 `SCHED_NAME + 业务键`；无独立自增 id；无审计列；无 delete_status。

### N0DD15FF_JOB_DETAILS（作业明细）
| 字段 | 类型 | 可空 | 默认 | 键 | 含义 |
|---|---|---|---|---|---|
| SCHED_NAME | varchar(120) | N | | PK | 调度器实例名 |
| JOB_NAME | varchar(200) | N | | PK | 作业名 |
| JOB_GROUP | varchar(200) | N | | PK | 作业组 |
| DESCRIPTION | varchar(250) | Y | | | 描述 |
| JOB_CLASS_NAME | varchar(250) | N | | | 作业实现类 |
| IS_DURABLE | tinyint(1) | N | | | 是否持久 |
| IS_NONCONCURRENT | tinyint(1) | N | | | 是否禁并发 |
| IS_UPDATE_DATA | tinyint(1) | N | | | 是否更新数据 |
| REQUESTS_RECOVERY | tinyint(1) | N | | | 是否请求恢复 |
| JOB_DATA | blob | Y | | | 作业数据(序列化) |

### N0DD15FF_TRIGGERS（触发器主表）
| 字段 | 类型 | 可空 | 默认 | 键 | 含义 |
|---|---|---|---|---|---|
| SCHED_NAME | varchar(120) | N | | PK | 调度器名 |
| TRIGGER_NAME | varchar(200) | N | | PK | 触发器名 |
| TRIGGER_GROUP | varchar(200) | N | | PK | 触发器组 |
| JOB_NAME / JOB_GROUP | varchar(200) | N | | FK线索 | 指向 JOB_DETAILS `[业务语义推断]` |
| DESCRIPTION | varchar(250) | Y | | | 描述 |
| NEXT_FIRE_TIME / PREV_FIRE_TIME | bigint | Y | | | 下次/上次触发时间(ms) |
| PRIORITY | int | Y | | | 优先级 |
| TRIGGER_STATE | varchar(16) | N | | | 状态 |
| TRIGGER_TYPE | varchar(8) | N | | | 类型(CRON/SIMPLE等) |
| START_TIME / END_TIME | bigint | N/Y | 0 | | 起止时间 |
| CALENDAR_NAME | varchar(200) | Y | | FK线索 | → CALENDARS `[业务语义推断]` |
| MISFIRE_INSTR | smallint | Y | | | 未触发指令 |
| JOB_DATA | blob | Y | | | 数据 |

### N0DD15FF_CRON_TRIGGERS / SIMPLE_TRIGGERS / SIMPROP_TRIGGERS / BLOB_TRIGGERS
> 均为 TRIGGERS 的**类型子表**，共享 PK `(SCHED_NAME,TRIGGER_NAME,TRIGGER_GROUP)`：
> - CRON：+`CRON_EXPRESSION` varchar(200) N、`TIME_ZONE_ID` varchar(80)
> - SIMPLE：+`REPEAT_COUNT`/`REPEAT_INTERVAL`/`TIMES_TRIGGERED` bigint N(0)
> - SIMPROP：+STR_PROP_1~3、INT_PROP_1~2、LONG_PROP_1~2、DEC_PROP_1~2、BOOL_PROP_1~2
> - BLOB：+`BLOB_DATA` blob

### N0DD15FF_FIRED_TRIGGERS（已触发记录）
> PK `(ENTRY_ID,SCHED_NAME)`；列：ENTRY_ID varchar(95)、TRIGGER_NAME/GROUP、INSTANCE_NAME、FIRED_TIME/SCHED_TIME bigint N、PRIORITY int N、STATE varchar(16) N、JOB_NAME/GROUP、IS_NONCONCURRENT、REQUESTS_RECOVERY。

### N0DD15FF_CALENDARS / PAUSED_TRIGGER_GRPS / SCHEDULER_STATE / LOCKS
> - CALENDARS：PK(SCHED_NAME,CALENDAR_NAME)；CALENDAR blob
> - PAUSED_TRIGGER_GRPS：PK(SCHED_NAME,TRIGGER_GROUP)
> - SCHEDULER_STATE：PK(INSTANCE_NAME,SCHED_NAME)；LAST_CHECKIN_TIME/CHECKIN_INTERVAL bigint N
> - LOCKS：PK(LOCK_NAME,SCHED_NAME)；LOCK_NAME varchar(40)

> **归类（其余 24 套，结构同上）**：N0F09730、N12BC091、N1771E1D、N1D46B8D、N25D595D、N397699E、N463F970、N493CDC0、N71F6208、N7A50C89、N8276087、N834A5F1、N85F6F8C、N89C265B、N90A13E0、NA486690、NABEB919、NAC7F355、NCCF9189、ND4D105D、ND907DDB、NE4600CF、NEF90500、NF0342B4（各 11 表）。

---

## 族② Activiti/Flowable 工作流引擎（代表集 `P12BC091_`；结构 ×4 套 = 128 表）

> 通用特征：utf8mb4_unicode_ci；主键 `ID_` varchar(64)（或 NAME_ 等）；`REV_` int 乐观锁；`TENANT_ID_` 多租户；列名大写 `_` 后缀；索引名带集前缀 `P12BC091_IDX_*`；**官方 FK 已被剥离**。

### P12BC091_ACT_GE_PROPERTY（通用-版本属性）
| 字段 | 类型 | 可空 | 键 | 含义 |
|---|---|---|---|---|
| NAME_ | varchar(64) | N | PK | 属性名(schema.version 等) |
| VALUE_ | varchar(300) | Y | | 属性值 |
| REV_ | int | Y | | 修订号 |

### P12BC091_ACT_GE_BYTEARRAY（通用-字节资源）
> ID_ varchar(64) PK、REV_、NAME_、DEPLOYMENT_ID_（FK线索→RE_DEPLOYMENT `[业务语义推断]`）、BYTES_ longblob、GENERATED_ tinyint。

### P12BC091_ACT_RE_DEPLOYMENT（仓库-部署）
> ID_ PK、NAME_、CATEGORY_、KEY_、TENANT_ID_、DEPLOY_TIME_ timestamp、DERIVED_FROM_、DERIVED_FROM_ROOT_、PARENT_DEPLOYMENT_ID_、ENGINE_VERSION_。

### P12BC091_ACT_RE_PROCDEF（仓库-流程定义）
> ID_ PK、REV_、CATEGORY_、NAME_、KEY_、VERSION_ int N、DEPLOYMENT_ID_（FK线索）、MANAGER_NAME_、RESOURCE_NAME_、DGRM_RESOURCE_NAME_、DESCRIPTION_、HAS_START_FORM_KEY_、HAS_GRAPHICAL_NOTATION_、SUSPENSION_STATE_、TENANT_ID_、ENGINE_VERSION_、DERIVED_FROM_、DERIVED_FROM_ROOT_、DERIVED_VERSION_。

### P12BC091_ACT_RU_EXECUTION（运行时-执行实例）
> ID_ PK、REV_、PROC_INST_ID_、BUSINESS_KEY_、PARENT_ID_、PROC_DEF_ID_（FK线索）、SUPER_EXEC_、ROOT_PROC_INST_ID_、ACT_ID_、IS_ACTIVE_/IS_CONCURRENT_/IS_SCOPE_/IS_EVENT_SCOPE_/IS_MI_ROOT_/IS_COUNT_ENABLED_ tinyint、SUSPENSION_STATE_/CACHED_ENT_STATE_ int、TENANT_ID_、NAME_、START_ACT_ID_、START_TIME_、START_USER_ID_、LOCK_TIME_、LOCK_OWNER_、EVT_SUBSCR_COUNT_/TASK_COUNT_/JOB_COUNT_/TIMER_JOB_COUNT_/SUSP_JOB_COUNT_/DEADLETTER_JOB_COUNT_/EXTERNAL_WORKER_JOB_COUNT_/VAR_COUNT_/ID_LINK_COUNT_ int、CALLBACK_ID_/CALLBACK_TYPE_/REFERENCE_ID_/REFERENCE_TYPE_/PROPAGATED_STAGE_INST_ID_。

### P12BC091_ACT_RU_TASK（运行时-任务）/ RU_VARIABLE（流程变量）
> - RU_TASK：ID_ PK、REV_、EXECUTION_ID_/PROC_INST_ID_/PROC_DEF_ID_（FK线索）、NAME_、TASK_DEF_KEY_、FORM_KEY_、ASSIGNEE_、CREATE_TIME_、CLAIM_TIME_、DELEGATION_、PRIORITY_、DUE_DATE_、CATEGORY_、SUSPENSION_STATE_、TENANT_ID_、NAME_/FORM_KEY_、IS_COUNT_ENABLED_/VAR_COUNT_/ID_LINK_COUNT_/SUB_TASK_COUNT_。
> - RU_VARIABLE：ID_ PK、REV_、TYPE_、NAME_、EXECUTION_ID_/PROC_INST_ID_/TASK_ID_（FK线索）、BYTEARRAY_ID_、DOUBLE_/LONG_ numeric、TEXT_/TEXT2_ var。

### P12BC091_ACT_HI_PROCINST（历史-流程实例）
> ID_ PK、REV_、PROC_INST_ID_（UNIQUE）、BUSINESS_KEY_、PROC_DEF_ID_、START_TIME_/END_TIME_ N/Y、DURATION_ bigint、START_USER_ID_、START_ACT_ID_/END_ACT_ID_、SUPER_PROCESS_INSTANCE_ID_、DELETE_REASON_、TENANT_ID_、NAME_、CALLBACK_ID_/TYPE_、REFERENCE_ID_/TYPE_、PROPAGATED_STAGE_INST_ID_。

### 其余子系统代表（同族，字段从略——见归类点名）
> - **RU 作业族**：ACT_RU_JOB / TIMER_JOB / SUSPENDED_JOB / DEADLETTER_JOB / EXTERNAL_JOB / HISTORY_JOB（结构近似：ID_/REV_/TYPE_/EXECUTION_ID_/PROC_DEF_ID_/HANDLER_TYPE_/HANDLER_CFG_/DUEDATE_/RETRIES_/TENANT_ID_ 等）。
> - **RU 其他**：ACT_RU_IDENTITYLINK、ACT_RU_EVENT_SUBSCR、ACT_RU_ACTINST、ACT_RU_ENTITYLINK。
> - **HI 历史族**：ACT_HI_TASKINST、ACT_HI_ACTINST、ACT_HI_VARINST、ACT_HI_IDENTIFYLINK、ACT_HI_DETAIL、ACT_HI_COMMENT、ACT_HI_ATTACHMENT、ACT_HI_TSK_LOG、ACT_HI_ENTITYLINK。
> - **RE**：ACT_RE_MODEL、ACT_PROCDEF_INFO。
> - **EVT**：ACT_EVT_LOG（LOG_NR_ bigint AI PK、TYPE_、PROC_*_、TIME_STAMP_、USER_ID_、DATA_ longblob、LOCK_*、IS_PROCESSED_）。
> - **FLW**：FLW_RU_BATCH、FLW_RU_BATCH_PART。

> **归类（其余 3 套，结构同上）**：P1D46B8D、P71F6208、PEF90500（各 32 表）。

---

## 族③ lcap 低代码平台 RBAC+元数据（基线 21 表 × ~25 应用副本 = 450 表）

> 通用特征：`id` bigint 非自增 PK；审计列 created_time/updated_time/created_by/updated_by(varchar255)；多数无 delete_status；表 COMMENT 声明"默认字段不允许改动，可新增自定义字段"（低代码平台生成）。

### lcap_user（用户/人员）
> id PK、created_time、updated_time、user_id varchar N(UNIQUE userIdIndex)、password（**明文注释"建议加密"**）、phone、email、display_name、status varchar('Normal')、source varchar N('Normal')、direct_leader_id、account_no、dept_id、position_id bigint、employee_no、name varchar N、gender tinyint、picture、is_manager tinyint、join_date/leave_date date、employed_status tinyint、user_name varchar N、account_status tinyint、is_merchandiser tinyint、agency_id、enterprise_we_chat_id、delete_status tinyint(1)(0)、created_by、updated_by、id_number（**身份证明文**）、center_id bigint（所属中心/贸易商）、company_id bigint（关联公司）。

### lcap_role（角色）
> id PK、created_time、updated_time、created_by、updated_by、uuid、name varchar N、description、role_status tinyint(1)(1)、editable tinyint(1)(1)、delete_status tinyint(1) NULL。字符集 general_ci。**注：表 COMMENT 误写为"用户与角色关联实体"**（见 P-B-05）。

### lcap_permission（权限）
> id PK、审计4、uuid、name varchar N、description。

### lcap_resource（资源）
> id PK、审计4、uuid、name varchar N(资源路径如/test/api)、description、type、client_type、appId（驼峰命名，见 P-B-03）、source（注释含 `\r\n` 控制符）。

### lcap_user_role_mapping（用户-角色）
> id PK、审计4、user_id varchar N、role_id bigint N、user_name、source、delete_status tinyint(1)(0)。

### lcap_role_per_mapping（角色-权限）
> id PK、审计4、role_id bigint N、permission_id bigint N。

### lcap_per_res_mapping（权限-资源）
> id PK、审计4、permission_id bigint N、resource_id bigint N。

### lcap_data_permission（数据权限）
> id PK、审计4、resource_name、resource_type、row_rule_type、relation、role_id bigint（UNIQUE dataPermissionIndex: resource_name+resource_type+role_id）。

### lcap_row_rule_item（行权限规则）
> id PK、审计4、data_permission_id bigint（IDX）、property_name、comparison、`values` text（**保留字列名**）、values_type。

### lcap_column_rule（列权限）
> id PK、审计4、data_permission_id bigint（IDX）、property_name、column_rule_type。

### lcap_department（部门）
> id PK、created/updated/created_by/updated_by(general_ci)、delete_status、name、dept_id varchar(UNIQUE deptIdIndex)、parent_dept_id、is_company、**trader_id bigint**（→D08）、company_id bigint、settlement_unit_id bigint（→D06）。

### lcap_user_dept_mapping（用户-部门）
> id PK、审计4、user_id、dept_id、is_dept_leader bigint(0)。

### lcap_user_manager_mapping（用户-业务经理）
> id PK、审计4(general_ci)、user_id varchar N（被管理方）、manager_user_id varchar N（管理方）、delete_status tinyint(1)(0)。

### lcap_user_trader_mapping（用户-贸易商）
> id PK、updated_by、user_id varchar N、trader_id bigint N、trader、user_name、source、created_time、updated_time、created_by、delete_status tinyint N(0)。（注：字段顺序异常，created 系列位于表中后部）

### lcap_session_token（会话令牌）
> id PK、审计4、user_id varchar N、token varchar(4000) N、is_valid tinyint N、expire_time datetime N（IDX userValidIndex: user_id+is_valid）。

### lcap_third_identity（三方身份）
> id PK、审计4、open_id varchar N、identity_id bigint N、name N、phone、email、user_id varchar N、state N、extentions_info text（拼写 extension）（UNIQUE user_id+open_id+identity_id）。

### lcap_identity_source_config（身份源配置，SSO/OAuth/LDAP/微信）
> id PK、审计4、state N、icon N、name N、app_id N、**app_secret N（密钥明文）**、success_url、type N、login_enable、center_login_url、token_url/method、user_url/method、center_logout_url、logout_callback_url、cas_ticket_url、expire bigint(86400)、redirect_url、agent_id、token/user_header/body_map text、user_id_res、user_name_res、code N(UNIQUE)、sso_url、wechat_token、wechat_msg_method/secret、ldap_link、ldap_bind_d_n、**ldap_bind_d_n_pwd（密码明文）**、lcap_user_d_n、lcap_query。

### lcap_app_config（应用配置）
> id PK、审计4、login_role_id bigint N、login_identity_type varchar N('Single')、setting_switch tinyint(1)(0)、login_page_config('Default')、customize_login_url、show_photo_url。

### lcap_app_cache（应用缓存）
> id PK、审计4、`key` varchar(UNIQUE uni_key)（**保留字**）、value text、expiration bigint。

### lcap_entity_meta（实体元信息）
> id PK、审计4、entity_name（IDX）、table_name、entity_description、properties text。

### lcap_logic_meta（逻辑元信息）
> id PK、审计4、logic_name（IDX）、return_shape、properties text、logic_description、is_simple tinyint(1)(0)。

> **归类（应用副本）**：上述 21 基线结构按应用 hex6 后缀各复制一份，观测到 29 个后缀：0dd15f、0f0973、108445、12bc09、1771e1、1d46b8、1dc951、397699、54ee2d、71f620、766876、7900ef、827608、85ab3f、85f6f8、9df960、a48669、b4be47、bd3549、ca9cf8、cad193、ccf918、d4d105、d907dd、e4600c、e7686b、ef9050、f0342b、f3ce15（`lcap_*_{suffix}`）。另含变体基线 `lcap_row_rule_item_e7686b1` 等少量 7 位后缀副本，与个别 `*_1`/`*_bak_日期` 副本（后者归 C 级）。

---

## B 级数量校验

| 族 | 代表结构数 | 套数/副本数 | 小计 |
|---|---|---|---|
| Quartz | 11 | 25 | 275 |
| Activiti/Flowable | 32 | 4 | 128 |
| lcap 平台 | ~21 | ×~25 应用（含变体/基线混合） | 450 |
| **合计** | | | **853** ✓ |
