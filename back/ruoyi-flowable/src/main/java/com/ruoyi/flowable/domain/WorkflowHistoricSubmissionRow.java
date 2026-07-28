package com.ruoyi.flowable.domain;

import java.util.Date;

/**
 * 从 Flowable 8 历史明细表读取的内部表单提交快照元数据行。
 *
 * @param detailId String，ACT_HI_DETAIL 历史详情主键
 * @param processInstanceId String，快照所属流程实例主键
 * @param activityInstanceId String，快照关联历史活动实例主键；允许为空
 * @param taskId String，任务提交关联主键；开始提交为空
 * @param variableName String，必须为服务端固定内部快照变量名
 * @param revision Integer，历史变量更新版本
 * @param variableTypeName String，仅允许 string 或 longString
 * @param submittedAt Date，历史变量更新真实写入时间
 * @param detailType String，ACT_HI_DETAIL.TYPE_ 历史明细类型
 * @param byteArrayId String，ACT_HI_DETAIL.BYTEARRAY_ID_ 关联主键；文本存储时为空
 * @param textPresent Integer，TEXT_ 是否非空，固定为 0 或 1
 * @param text2Present Integer，TEXT2_ 是否非空，固定为 0 或 1
 * @param textBytes Long，TEXT_ 的真实 UTF-8 字节数；TEXT_ 为空时为空
 * @param byteArrayPresent Integer，BYTEARRAY_ID_ 是否关联到真实 ACT_GE_BYTEARRAY 行
 * @param byteArrayBodyPresent Integer，关联字节数组的 BYTES_ 是否非空
 * @param storedBytes Long，关联字节数组正文的真实字节数；正文为空时为空
 */
public record WorkflowHistoricSubmissionRow(
        String detailId,
        String processInstanceId,
        String activityInstanceId,
        String taskId,
        String variableName,
        Integer revision,
        String variableTypeName,
        Date submittedAt,
        String detailType,
        String byteArrayId,
        Integer textPresent,
        Integer text2Present,
        Long textBytes,
        Integer byteArrayPresent,
        Integer byteArrayBodyPresent,
        Long storedBytes)
{
}
