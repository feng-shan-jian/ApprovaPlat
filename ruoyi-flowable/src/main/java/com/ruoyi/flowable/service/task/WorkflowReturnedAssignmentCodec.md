# WorkflowReturnedAssignmentCodec

## 作用

编码和解码普通退回任务的原办理配置，保持既有变量内容、候选人顺序和历史兼容语义。输入输出均使用不可变 `ReturnedAssignmentSnapshot`。

## 边界

该组件不读写 Flowable；编码结果只在任务局部变量边界使用。非法、缺失或重复身份数据继续失败关闭。
