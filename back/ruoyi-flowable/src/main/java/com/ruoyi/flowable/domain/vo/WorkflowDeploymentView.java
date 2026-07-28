package com.ruoyi.flowable.domain.vo;

import java.util.Date;

/**
 * 流程定义及所属部署的不可变视图。
 *
 * @param definitionId String，流程定义主键
 * @param processName String，流程定义名称
 * @param processKey String，流程定义标识
 * @param category String，工作流分类编码
 * @param version Integer，流程定义版本
 * @param formId Long，部署快照中的流程表单主键
 * @param formName String，部署快照中的流程表单名称
 * @param deploymentId String，流程部署主键
 * @param suspended Boolean，流程定义是否挂起
 * @param deploymentTime Date，流程部署时间
 */
public record WorkflowDeploymentView(String definitionId, String processName, String processKey,
        String category, Integer version, Long formId, String formName, String deploymentId,
        Boolean suspended, Date deploymentTime)
{
    /**
     * 创建部署视图并复制可变时间对象。
     *
     * @param definitionId String，流程定义主键
     * @param processName String，流程定义名称
     * @param processKey String，流程定义标识
     * @param category String，工作流分类编码
     * @param version Integer，流程定义版本
     * @param formId Long，部署快照中的流程表单主键
     * @param formName String，部署快照中的流程表单名称
     * @param deploymentId String，流程部署主键
     * @param suspended Boolean，流程定义是否挂起
     * @param deploymentTime Date，流程部署时间
     * @return 无返回值，构造后得到不可变部署视图
     */
    public WorkflowDeploymentView
    {
        deploymentTime = copyDate(deploymentTime);
    }

    /**
     * 返回部署时间副本，防止调用方修改视图内部状态。
     *
     * @return Date，部署时间副本
     */
    @Override
    public Date deploymentTime()
    {
        return copyDate(deploymentTime);
    }

    /**
     * 复制可变 Date 对象。
     *
     * @param value Date，待复制时间，允许为空
     * @return Date，时间副本或 null
     */
    private static Date copyDate(Date value)
    {
        return value == null ? null : new Date(value.getTime());
    }
}
