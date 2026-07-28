# ApprovaPlat Backend

该目录是 ApprovaPlat 的 Spring Boot 后端工程。

## 模块

- `ruoyi-admin`：应用入口与 Web 接口
- `ruoyi-framework`：安全、鉴权和框架配置
- `ruoyi-system`：用户、角色、菜单、部门等系统能力
- `ruoyi-flowable`：Flowable 引擎适配与工作流业务能力
- `ruoyi-quartz`：定时任务
- `ruoyi-generator`：代码生成
- `ruoyi-common`：公共模型与工具
- `sql`：初始化及升级脚本

Flowable 8 的正式 SQL 来源、校验和与执行顺序见 `sql/flowable/README.md`。全新数据库在官方表之后先执行 `8.0.0.2` 模型版本并发门禁，再建立六张 `wf_*` 业务表；已部署环境按该说明补齐模型版本唯一约束和附件配额 guard 增量，并通过三组只读校验。应用配置固定关闭 schema 自动更新。

工作流附件默认使用 50 MiB 单文件、55 MiB multipart 请求、50 GiB 全局未物理删除容量和 1 GiB 磁盘低水位。上传事务固定先锁 `wf_attachment_quota_guard.owner_user_id = 0` 全局行，再锁当前用户行；生产覆盖配置必须与反向代理和挂载卷容量一致。

## 构建

```powershell
mvn clean package -DskipTests
```

构建产物位于 `ruoyi-admin/target/ruoyi-admin.jar`。完整环境配置与启动步骤见仓库根目录 `README.md`。
