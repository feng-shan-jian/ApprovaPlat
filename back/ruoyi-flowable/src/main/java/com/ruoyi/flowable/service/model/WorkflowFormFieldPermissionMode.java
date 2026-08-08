package com.ruoyi.flowable.service.model;

/**
 * 节点级表单字段权限模式。
 */
public enum WorkflowFormFieldPermissionMode
{
    /** 字段不向当前节点用户返回，也不允许客户端写入。 */
    HIDDEN,

    /** 字段允许当前节点用户查看，但不允许客户端写入。 */
    READONLY,

    /** 字段允许当前节点用户查看和写入，但可以为空。 */
    EDITABLE,

    /** 字段允许当前节点用户查看和写入，且当前节点必须有值。 */
    REQUIRED
}
