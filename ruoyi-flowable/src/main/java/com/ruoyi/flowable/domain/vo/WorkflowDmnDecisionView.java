package com.ruoyi.flowable.domain.vo;

import java.util.Date;

/**
 * Flowable 官方 DMN 决策管理视图。
 * @param decisionId String，精确版本主键
 * @param decisionKey String，稳定决策 key
 * @param decisionName String，显示名称
 * @param version int，官方不可回退版本
 * @param category String，分类
 * @param decisionType String，决策类型
 * @param deploymentId String，DMN 部署主键
 * @param resourceName String，DMN XML 资源名
 * @param deploymentTime Date，部署时间
 */
public record WorkflowDmnDecisionView(String decisionId, String decisionKey,
        String decisionName, int version, String category, String decisionType,
        String deploymentId, String resourceName, Date deploymentTime)
{
}
