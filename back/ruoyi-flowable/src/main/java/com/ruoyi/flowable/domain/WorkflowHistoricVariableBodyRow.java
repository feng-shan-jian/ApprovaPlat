package com.ruoyi.flowable.domain;

/**
 * 已通过元数据和累计容量门禁后读取的 Flowable 历史变量正文。
 *
 * @param rowId String，ACT_HI_DETAIL 或 ACT_HI_VARINST 的真实主键
 * @param storedText String，行内 TEXT_ 正文；Blob 存储时为空
 * @param storedBytes byte[]，ACT_GE_BYTEARRAY.BYTES_ 正文；行内存储时为空
 */
public record WorkflowHistoricVariableBodyRow(
        String rowId,
        String storedText,
        byte[] storedBytes)
{
}
