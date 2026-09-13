# OSS 存储定位与配置编辑契约

对象台账以 `config_id + object_key` 定位远端内容。只要配置被任意状态的台账引用（包括删除中、删除失败和已删除），服务端拒绝修改 `endpoint`、`region`、`bucketName`、`pathStyle`，返回 409。切换存储位置应新增并启用另一配置；历史对象仍通过原配置读取、删除。

AccessKey/SecretKey 可轮换，SecretKey 留空继续保留原值。配置名称、备注、对象前缀、公开域名和访问策略仍可编辑；完整对象键、访问策略和公开 URL 已在上传时记录，修改这些默认值不改写历史对象。读取、删除使用原位置与该配置的最新凭据。

配置更新与上传入账锁定同一配置行。位置修改后的引用检查使用 MySQL 当前读，覆盖已有事务快照的情况。上传的网络 PUT 在锁外执行，成功后才在短事务内锁定配置、比较上传位置并写入台账：

- 上传先入账：配置更新看见引用并返回 409，历史位置保持不变。
- 位置更新先提交：上传返回 409，不写入台账，并使用本次上传时的原配置与对象键补偿删除，绝不转向新存储桶。
- 上传期间仅轮换凭据或修改上传默认值：位置未变化，允许入账，保留本次上传的对象键、策略和 URL 快照。

补偿沿用已有尽力删除行为，失败附加 suppressed 异常。正常对象删除仍允许远端 404 进入 DELETED，但请求目标必须是固定的原位置。接口、DTO、表结构及删除状态机不变，无需数据迁移；本约束不具备恢复此前已被错误改写的存储位置的历史信息。

正式回归：`ruoyi-flowable/src/test/java/com/ruoyi/system/service/integration/SysOssServiceMySqlIT.java`，使用真实 Spring 事务及 MySQL 隔离库，通过受控 HttpClient 覆盖定位、凭据、两个上传交错和补偿失败。执行 `mvn -pl ruoyi-flowable -am -Pworkflow-mysql-it -Dit.test=SysOssServiceMySqlIT verify`；提供三个 `WORKFLOW_MYSQL_TEST_*` 环境变量，唯一允许库为 `approvaplat_it`。受控 HTTP 响应不代表真实 S3 或浏览器验收。

隔离库须事先由初始化账号执行 `sql/integration/8.0.0__sms_oss.sql` 中 OSS 两表的正式 DDL。测试账号仅需读写权限，测试启动只读核验库名及哨兵表；用例按自身配置主键清理合成数据，不创建或删除表，也不修改已有启用配置。
