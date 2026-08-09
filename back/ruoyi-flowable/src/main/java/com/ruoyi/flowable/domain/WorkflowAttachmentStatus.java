package com.ruoyi.flowable.domain;

/**
 * 工作流附件生命周期状态。
 */
public enum WorkflowAttachmentStatus
{
    /** 已上传但尚未绑定流程对象，只有所有者可操作。 */
    TEMP,
    /** 已绑定当前用户的正式申请草稿，只有草稿所有者可读取和迁移。 */
    DRAFT,
    /** 已在业务事务中绑定真实流程实例，可按流程对象权限读取。 */
    BOUND,
    /** 临时有效期已结束，私有文件等待或已经清理。 */
    EXPIRED,
    /** 所有者主动删除的未绑定附件，私有文件等待或已经清理。 */
    DELETED
}
