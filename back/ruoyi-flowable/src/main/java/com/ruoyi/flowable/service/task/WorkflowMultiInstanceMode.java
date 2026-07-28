package com.ruoyi.flowable.service.task;

/**
 * 受控动态多实例的完成模式。
 */
public enum WorkflowMultiInstanceMode
{
    /** 全部正式成员完成后才离开节点。 */
    ALL,

    /** 任一正式成员完成后即可离开节点。 */
    ANY
}
