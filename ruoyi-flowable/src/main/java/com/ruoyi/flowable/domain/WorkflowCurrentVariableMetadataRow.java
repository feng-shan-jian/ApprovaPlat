package com.ruoyi.flowable.domain;

/**
 * 活动表单当前值对应的 Flowable 历史变量存储元数据。
 *
 * @param variableId String，ACT_HI_VARINST 主键
 * @param processInstanceId String，变量所属流程实例主键
 * @param executionId String，变量所属执行实例主键
 * @param taskId String，任务局部变量所属任务主键；流程变量为空
 * @param subScopeId String，子作用域主键；流程根变量必须为空
 * @param variableName String，部署表单 schema 白名单内的变量名
 * @param variableTypeName String，Flowable 变量类型名
 * @param byteArrayId String，ACT_HI_VARINST.BYTEARRAY_ID_ 关联主键
 * @param textPresent Integer，TEXT_ 是否非空，固定为 0 或 1
 * @param text2Present Integer，TEXT2_ 是否非空，固定为 0 或 1
 * @param textBytes Long，TEXT_ 的真实 UTF-8 字节数；TEXT_ 为空时为空
 * @param byteArrayPresent Integer，BYTEARRAY_ID_ 是否关联到真实 ACT_GE_BYTEARRAY 行
 * @param byteArrayBodyPresent Integer，关联字节数组的 BYTES_ 是否非空
 * @param storedBytes Long，关联字节数组正文的真实字节数；正文为空时为空
 */
public record WorkflowCurrentVariableMetadataRow(
        String variableId,
        String processInstanceId,
        String executionId,
        String taskId,
        String subScopeId,
        String variableName,
        String variableTypeName,
        String byteArrayId,
        Integer textPresent,
        Integer text2Present,
        Long textBytes,
        Integer byteArrayPresent,
        Integer byteArrayBodyPresent,
        Long storedBytes)
{
}
