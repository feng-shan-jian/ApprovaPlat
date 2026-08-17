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

Flowable 8 的正式 SQL 来源、执行顺序和未来迁移规则见 `docs/database/workflow-baseline.md`。首个正式版本只支持全新数据库安装：依次建立 Common、Process、History、DMN、模型版本并发门禁、27 张 `wf_*` 业务表和 99 条菜单记录，最终形成 94 张正式表。8 类不可变部署快照由 Flowable 业务制品子部署保存为 9 个版本化 JSON 资源，普通审批、SLA 与 BPMN 事件共享统一通知模型。开发期迁移不属于发布资产；首个基线发布后的结构变化才新增不可改写的正式增量。应用配置固定关闭 schema 自动更新。

工作流附件默认使用 50 MiB 单文件、55 MiB multipart 请求、每用户 100 个/512 MiB 临时附件配额和 1 GiB 磁盘低水位。上传事务只锁当前用户的 `wf_attachment_quota_guard` 行，不同用户可以并行；清理使用数据库 token/lease 领取，生产覆盖配置必须与反向代理和挂载卷容量一致。

## 构建

```powershell
mvn clean package -DskipTests
```

构建产物位于 `ruoyi-admin/target/ruoyi-admin.jar`。完整环境配置与启动步骤见仓库根目录 `README.md`。
