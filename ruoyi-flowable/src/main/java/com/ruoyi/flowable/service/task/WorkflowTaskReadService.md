# WorkflowTaskReadService

## 作用

`WorkflowTaskReadService` 提供两个只读能力：任务参与者可见的安全流程变量，以及实例参与者可见的 PNG 流程图。两个入口都先执行对象级授权，不能只依赖 Controller 权限码。

## 安全变量投影

变量入口先通过 `WorkflowProcessAccessService.requireReadableTask` 校验活动或历史任务参与关系，再调用 `WorkflowProcessDetailService`。详情服务只从部署时固化的 `approvaplat/forms-v1.json` 资源提取字段白名单，并拒绝 Java 序列化对象、二进制值、自定义变量类型、过深或过大的 JSON。

本服务按流程表单时间线合并这些安全 `JsonNode`，当前任务表单最后合并。同名当前字段覆盖历史字段。`initiator`、`processStatus`、多实例计数、跳过标志及未在部署 schema 中声明的变量不会进入响应。

## PNG 流程图

流程图入口先通过 `WorkflowProcessAccessService.requireReadableInstance` 校验发起人、参与人、当前办理/候选人、历史办理人、抄送人或超级管理员关系。通过授权后校验正式部署包含 BPMN DI 图形坐标，再读取历史活动，分别高亮活动节点和 `sequenceFlow`，并调用 Flowable 8 `ProcessDiagramGenerator` 公共 API。缺失 BPMN DI 或生成器抛出的未受控运行时异常都会转换为稳定的服务端业务错误，不向接口泄漏底层 NPE。

生成结果最大为十 MiB，并必须包含标准 PNG 文件签名。Controller 应固定返回 `image/png` 且禁用缓存，不直接暴露 BPMN 文件路径或服务端异常堆栈。
