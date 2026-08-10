package com.ruoyi.flowable.domain;

/**
 * 流程申请草稿持久化状态。
 */
public enum WorkflowProcessDraftStatus
{
    /** 草稿仍可由所有者编辑或提交。 */
    ACTIVE,
    /** 草稿已经成功创建唯一流程实例。 */
    SUBMITTED,
    /** 草稿已经由所有者删除。 */
    DELETED
}
