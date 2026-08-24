# WorkflowReturnedApplicationProtocol

## 作用

`WorkflowReturnedApplicationProtocol` 是退回申请生命周期的唯一稳定协议常量来源。它保存 `returned` 业务状态、申请人任务局部变量、原办理配置局部变量，以及受控 `RETURN`/`RESUBMIT` 迁移通知标记的真实历史名称。

## 约束

- 只定义稳定名称，不读取或修改 Flowable、数据库与 Spring Bean。
- `WorkflowTaskLifecycleService`、整组迁移、详情、通知和任务审计共同引用该协议，生产服务之间不因常量形成反向依赖。
- 变量名和标记值保持既有历史兼容语义；不得新增旧别名或兼容分支。
