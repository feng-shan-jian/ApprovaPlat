# WorkflowReturnedApplicationProtocol

## 作用

`WorkflowReturnedApplicationProtocol` 是退回申请生命周期的唯一稳定协议常量来源。它保存 `returned` 业务状态、申请人任务局部变量、原办理配置局部变量，以及受控 `RETURN`/`RESUBMIT` 迁移通知标记的真实历史名称。

## 约束

- 该类型仅定义稳定协议名称，Flowable、数据库与 Spring Bean 操作由应用服务承担。
- `WorkflowTaskLifecycleService`、整组迁移、详情、通知和任务审计共同引用该协议，生产服务之间不因常量形成反向依赖。
- 变量名和标记值沿用唯一正式协议；本次切换删除所有别名和额外兼容分支。
