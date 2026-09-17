# 生成器详情查询与编辑回归（L03 / BS-039）

本项只清理生成器详情重复读取。`GenController.getInfo` 仍返回 `data.info`、`data.rows`、`data.tables`，`rows` 直接使用本次 `selectGenTableById` 得到的 `columns`。权限、请求路径、保存服务、独立 `/tool/gen/column/{tableId}` 和代码生成入口不变；不调整不存在记录的既有处理策略。

## 映射与兼容边界

| 消费者 | 保留的行为 / 必要补齐 |
| --- | --- |
| `selectGenTableById` → 详情、预览 | 当前表列按 `c.sort` 排序；补齐来自 `c` 的 `tableId` 和 `createBy/createTime/updateBy/updateTime`，使 `rows` 保留原独立查询的全部字段。`info.columns` 因复用完整列投影而获得此前未投影的列审计值。 |
| `selectGenTableByName` → 下载、自定义生成、同步、子表装载 | 保留真实入口和相同列排序，使用同一完整联表列投影。列审计值由此前的空值补齐。 |
| `selectGenTableAll` → 子表目录 | 保留每张表的列集合及原 `order by c.sort`；多表相同列名、相同排序值仍按各自 `tableId/columnId` 关联。列审计同样补齐。原未投影的 `genType/genPath` 仍为空。 |
| `selectGenTableList`、`selectDbTableList`、`selectDbTableListByNames` | 继续使用原 `GenTableResult`，不扩大查询，也不改变这些非联表消费者的原映射。数据库目录的创建/更新时间仍来自 `information_schema.tables`。 |
| `GenTableColumnMapper.selectGenTableColumnListByTableId` | 保留 Mapper、Service 与独立列接口；只删除详情中的这一次调用。 |

联表专用的 `GenTableWithColumnsResult` 继承原表映射，覆盖 `columns` 的映射为 `columnPrefix="c_"`。所有列字段有 `c_` 别名，`notNullColumn="column_id"` 使 LEFT JOIN 无匹配列时得到空集合。父表 ID 或审计值不能创建虚假列，列审计也不会填入父表。三个联表 SELECT 原来没有投影父表审计字段，本项保留其空值；列表中的父表审计字段仍原样返回。不新增表审计投影，不改变同排序值之间原本未指定的次序。

列公开字段包括 ID/归属、列名/描述/物理类型、Java 类型/属性、主键/自增/必填/插入/编辑/列表/查询标志、查询方式、显示控件、字典、排序、四个审计字段，以及实体派生的布尔属性和 `capJavaField`。继承的 `remark` 仍为空，空 `params` 与 `searchValue` 的 Jackson 排除行为不变。没有数据库结构、数据格式或前端协议迁移。

全仓核对后删除仅有定义的 XML `selectTableByName`；不删除真实的 `selectGenTableByName`。修改只涉及一个 Controller 方法与一个 Mapper XML，没有新增应用层、缓存或校验器。

## 正式测试

测试放在已依赖 generator、H2、MySQL 驱动和 Spring Test 的 `ruoyi-admin`，不改变模块依赖与 Maven 门禁。

`GenTableDetailTest` 默认执行 8 项：

1. 完整 `info/rows/tables` JSON 字段、独立夹具期望、插入顺序与排序顺序不同、按名称查询；SQL 记录辅助断言详情只有当前表和目录两次查询。
2. 有父表 ID 和审计值但没有列，详情、目录、按名称查询均为空列集合。
3. 多表及父子相同列名、相同排序值、不同列审计作者；子表外键候选列完整且归属正确。
4. 列审计全空时不继承父表值；既有独立列接口仍执行自己的查询并返回总数和列字段。该接口的既有签名通过同名 `tableId` 请求参数绑定，测试同时传递路径和请求参数，不在本项修改其绑定方式。
5. 生成列表筛选及父表审计、生成方式/路径投影。
6. 普通表编辑、保存、重新读取、Velocity 预览和 ZIP 下载。
7. 树表相同链路，验证树字段选项、TreeEntity、树节点键与父键。
8. 主子表相同链路，从目录选择子表，验证外键、子表 Domain/Mapper/Vue。

默认测试从正式 `sql/ry_20260417.sql` 提取两张生成器表的 DDL 到独占 H2 内存库，只为 MySQL `sysdate()` 提供 H2 时钟函数适配，生产 SQL 原样执行。保存通过生产事务代理，MVC 使用真实 Controller 和 JSON 消息转换，Mapper 不使用 mock 返回值。夹具明确给定字段、类型、排序、审计和选项；断言也检查具体生成的属性、查询语句和页面绑定，而非只比较两个被测输出。生成 ZIP 仅在内存中解包，不调用覆盖本地目录的生成路径。

`GenTableDetailMySqlIT` 在显式 `workflow-mysql-it` 下复用以上 8 项，并增加 1 项真实 `information_schema` 目录与按名称导入读取（合计 9 项）。只连接显式提供的 `approvaplat_it` MySQL 8 / InnoDB 基线；缺失环境或结构即失败。生成器夹具按本用例拥有的 ID 删除并核实为零，目录测试创建的唯一物理表在 finally 删除并核实不存在。

验证命令：

```powershell
# 默认正式构建，包含 8 项 H2 生成器测试
mvn -B -ntp clean verify

# 仅在任务私有进程中注入 WORKFLOW_MYSQL_TEST_URL/USERNAME/PASSWORD 后执行
# 同时运行既有 MySQL 轮次 Mapper IT，满足 flowable 模块的匹配测试门禁
mvn -B -ntp -Pworkflow-mysql-it '-Dit.test=GenTableDetailMySqlIT,WfMultiInstanceRoundMapperMySqlIT' verify
```

MySQL 须预先按 `docker/mysql/init.sh` 顺序加载全部 14 份正式基线。本项没有降低 `failIfNoTests` 或以 skip 代替执行。

2026-09-18 在起始 HEAD `b7a40ac6eabcd3b296a71a1b4f2f188107a9d1f3` 的隔离 worktree 实测：默认构建 97 份 XML / 680 项；MySQL 构建再次执行默认 680 项，另执行 2 份 Failsafe XML / 13 项（本项 9、既有轮次 Mapper 4）。逐 XML 与 testcase 核对，失败/错误/跳过均为 0。使用任务私有 MySQL 8.4.9、端口 33345、正式 14 份基线 / 103 张表；测试环境仅注入 Maven 子进程。结束后核实 `gen_table`、`gen_table_column`、临时目录物理表和轮次夹具均为零。

## 人工核对与证据边界

已核对 `editTable.vue`：读取 `rows` 编辑和排序、将其作为 `columns` 保存、从 `info` 组装树/菜单/详情页参数；已核对 `genInfoForm.vue`：树字段选项读取 `info.columns`，子表外键选项读取 `tables[].columns`。调用结构无需前端修改。

测试覆盖 Mapper、服务事务、MockMvc JSON 和真实模板渲染。未执行浏览器、完整登录/权限过滤器/HTTP 服务链、自定义磁盘路径生成或生成项目的编译运行；这些不计为通过。接口权限注解未修改。无前端改动，不要求全前端重复构建。

恢复只需同时恢复 Controller 和 Mapper 的该项变更；无数据迁移或持久化格式切换。恢复旧查询会恢复详情第三次查询及旧联表缺失的列审计，不影响已保存生成器配置。
