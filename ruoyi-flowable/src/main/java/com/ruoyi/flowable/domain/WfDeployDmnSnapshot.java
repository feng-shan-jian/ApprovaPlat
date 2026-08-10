package com.ruoyi.flowable.domain;

import java.util.Date;

/**
 * 流程部署时冻结的 DMN 决策快照，对应 {@code wf_deploy_dmn_snapshot}。
 */
public class WfDeployDmnSnapshot
{
    private Long snapshotId;
    private String deployId;
    private String processKey;
    private String elementId;
    private String sourceDecisionId;
    private String decisionKey;
    private Integer decisionVersion;
    private String sourceDeploymentId;
    private String resourceName;
    private String resourceChecksum;
    private String frozenDeploymentId;
    private String frozenDecisionId;
    private String snapshotChecksum;
    private String createBy;
    private Date createTime;

    /** @return Long，快照主键。 */
    public Long getSnapshotId() { return snapshotId; }
    /** @param value Long，快照主键。@return void，无返回值。 */
    public void setSnapshotId(Long value) { snapshotId = value; }
    /** @return String，流程部署主键。 */
    public String getDeployId() { return deployId; }
    /** @param value String，流程部署主键。@return void，无返回值。 */
    public void setDeployId(String value) { deployId = value; }
    /** @return String，流程定义 key。 */
    public String getProcessKey() { return processKey; }
    /** @param value String，流程定义 key。@return void，无返回值。 */
    public void setProcessKey(String value) { processKey = value; }
    /** @return String，BusinessRuleTask 元素标识。 */
    public String getElementId() { return elementId; }
    /** @param value String，BusinessRuleTask 元素标识。@return void，无返回值。 */
    public void setElementId(String value) { elementId = value; }
    /** @return String，设计阶段选择的 DMN 决策主键。 */
    public String getSourceDecisionId() { return sourceDecisionId; }
    /** @param value String，设计阶段选择的 DMN 决策主键。@return void，无返回值。 */
    public void setSourceDecisionId(String value) { sourceDecisionId = value; }
    /** @return String，冻结决策 key。 */
    public String getDecisionKey() { return decisionKey; }
    /** @param value String，冻结决策 key。@return void，无返回值。 */
    public void setDecisionKey(String value) { decisionKey = value; }
    /** @return Integer，冻结决策版本。 */
    public Integer getDecisionVersion() { return decisionVersion; }
    /** @param value Integer，冻结决策版本。@return void，无返回值。 */
    public void setDecisionVersion(Integer value) { decisionVersion = value; }
    /** @return String，来源 DMN 部署主键。 */
    public String getSourceDeploymentId() { return sourceDeploymentId; }
    /** @param value String，来源 DMN 部署主键。@return void，无返回值。 */
    public void setSourceDeploymentId(String value) { sourceDeploymentId = value; }
    /** @return String，冻结资源名称。 */
    public String getResourceName() { return resourceName; }
    /** @param value String，冻结资源名称。@return void，无返回值。 */
    public void setResourceName(String value) { resourceName = value; }
    /** @return String，来源资源校验和。 */
    public String getResourceChecksum() { return resourceChecksum; }
    /** @param value String，来源资源校验和。@return void，无返回值。 */
    public void setResourceChecksum(String value) { resourceChecksum = value; }
    /** @return String，DMN 子部署主键。 */
    public String getFrozenDeploymentId() { return frozenDeploymentId; }
    /** @param value String，DMN 子部署主键。@return void，无返回值。 */
    public void setFrozenDeploymentId(String value) { frozenDeploymentId = value; }
    /** @return String，冻结后的 DMN 决策主键。 */
    public String getFrozenDecisionId() { return frozenDecisionId; }
    /** @param value String，冻结后的 DMN 决策主键。@return void，无返回值。 */
    public void setFrozenDecisionId(String value) { frozenDecisionId = value; }
    /** @return String，完整快照校验和。 */
    public String getSnapshotChecksum() { return snapshotChecksum; }
    /** @param value String，完整快照校验和。@return void，无返回值。 */
    public void setSnapshotChecksum(String value) { snapshotChecksum = value; }
    /** @return String，部署操作人正式用户主键。 */
    public String getCreateBy() { return createBy; }
    /** @param value String，部署操作人正式用户主键。@return void，无返回值。 */
    public void setCreateBy(String value) { createBy = value; }
    /** @return Date，创建时间。 */
    public Date getCreateTime() { return createTime; }
    /** @param value Date，创建时间。@return void，无返回值。 */
    public void setCreateTime(Date value) { createTime = value; }
}
