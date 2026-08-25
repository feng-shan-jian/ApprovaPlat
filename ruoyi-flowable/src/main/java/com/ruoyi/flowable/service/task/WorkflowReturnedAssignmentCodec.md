# WorkflowReturnedAssignmentCodec

## 作用

编码和解码普通退回任务的原办理配置，保持已持久化变量内容和候选人顺序。输入输出均使用不可变 `ReturnedAssignmentSnapshot`。

## 边界

该组件是纯编解码器，Flowable 读写由调用应用服务承担；编码结果仅在任务局部变量边界使用。非法、缺失或重复身份数据返回稳定错误。
