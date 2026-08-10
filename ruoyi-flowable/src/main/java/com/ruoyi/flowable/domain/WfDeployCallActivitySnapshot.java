package com.ruoyi.flowable.domain;

import java.util.Date;

/**
 * 调用活动部署时冻结的依赖与变量映射快照，对应 {@code wf_deploy_call_activity}。
 */
public class WfDeployCallActivitySnapshot
{
    private Long snapshotId;
    private String deployId;
    private String processKey;
    private String elementId;
    private String versionPolicy;
    private String targetDefinitionId;
    private String targetProcessKey;
    private String targetProcessName;
    private Integer targetVersion;
    private String targetDeploymentId;
    private Boolean inheritVariables;
    private Boolean inheritBusinessKey;
    private Boolean localScopeForOutput;
    private String propagationPolicy;
    private String inputMappingsJson;
    private String outputMappingsJson;
    private String snapshotChecksum;
    private String createBy;
    private Date createTime;

    /** @return Long，快照主键。 */
    public Long getSnapshotId() { return snapshotId; }
    /** @param value Long，快照主键。@return void，无返回值。 */
    public void setSnapshotId(Long value) { snapshotId = value; }
    /** @return String，父流程部署主键。 */
    public String getDeployId() { return deployId; }
    /** @param value String，父流程部署主键。@return void，无返回值。 */
    public void setDeployId(String value) { deployId = value; }
    /** @return String，父流程 key。 */
    public String getProcessKey() { return processKey; }
    /** @param value String，父流程 key。@return void，无返回值。 */
    public void setProcessKey(String value) { processKey = value; }
    /** @return String，CallActivity 元素标识。 */
    public String getElementId() { return elementId; }
    /** @param value String，CallActivity 元素标识。@return void，无返回值。 */
    public void setElementId(String value) { elementId = value; }
    /** @return String，LATEST_ACTIVE 或 FIXED。 */
    public String getVersionPolicy() { return versionPolicy; }
    /** @param value String，版本策略。@return void，无返回值。 */
    public void setVersionPolicy(String value) { versionPolicy = value; }
    /** @return String，冻结子流程定义主键。 */
    public String getTargetDefinitionId() { return targetDefinitionId; }
    /** @param value String，冻结子流程定义主键。@return void，无返回值。 */
    public void setTargetDefinitionId(String value) { targetDefinitionId = value; }
    /** @return String，冻结子流程 key。 */
    public String getTargetProcessKey() { return targetProcessKey; }
    /** @param value String，冻结子流程 key。@return void，无返回值。 */
    public void setTargetProcessKey(String value) { targetProcessKey = value; }
    /** @return String，冻结子流程名称。 */
    public String getTargetProcessName() { return targetProcessName; }
    /** @param value String，冻结子流程名称。@return void，无返回值。 */
    public void setTargetProcessName(String value) { targetProcessName = value; }
    /** @return Integer，冻结子流程版本。 */
    public Integer getTargetVersion() { return targetVersion; }
    /** @param value Integer，冻结子流程版本。@return void，无返回值。 */
    public void setTargetVersion(Integer value) { targetVersion = value; }
    /** @return String，冻结子流程部署主键。 */
    public String getTargetDeploymentId() { return targetDeploymentId; }
    /** @param value String，冻结子流程部署主键。@return void，无返回值。 */
    public void setTargetDeploymentId(String value) { targetDeploymentId = value; }
    /** @return Boolean，是否继承父流程全部变量。 */
    public Boolean getInheritVariables() { return inheritVariables; }
    /** @param value Boolean，是否继承父流程全部变量。@return void，无返回值。 */
    public void setInheritVariables(Boolean value) { inheritVariables = value; }
    /** @return Boolean，是否继承父业务键。 */
    public Boolean getInheritBusinessKey() { return inheritBusinessKey; }
    /** @param value Boolean，是否继承父业务键。@return void，无返回值。 */
    public void setInheritBusinessKey(Boolean value) { inheritBusinessKey = value; }
    /** @return Boolean，输出是否写入调用 execution 局部作用域。 */
    public Boolean getLocalScopeForOutput() { return localScopeForOutput; }
    /** @param value Boolean，输出是否使用局部作用域。@return void，无返回值。 */
    public void setLocalScopeForOutput(Boolean value) { localScopeForOutput = value; }
    /** @return String，取消和终止传播策略。 */
    public String getPropagationPolicy() { return propagationPolicy; }
    /** @param value String，取消和终止传播策略。@return void，无返回值。 */
    public void setPropagationPolicy(String value) { propagationPolicy = value; }
    /** @return String，规范输入映射 JSON。 */
    public String getInputMappingsJson() { return inputMappingsJson; }
    /** @param value String，规范输入映射 JSON。@return void，无返回值。 */
    public void setInputMappingsJson(String value) { inputMappingsJson = value; }
    /** @return String，规范输出映射 JSON。 */
    public String getOutputMappingsJson() { return outputMappingsJson; }
    /** @param value String，规范输出映射 JSON。@return void，无返回值。 */
    public void setOutputMappingsJson(String value) { outputMappingsJson = value; }
    /** @return String，快照 SHA-256。 */
    public String getSnapshotChecksum() { return snapshotChecksum; }
    /** @param value String，快照 SHA-256。@return void，无返回值。 */
    public void setSnapshotChecksum(String value) { snapshotChecksum = value; }
    /** @return String，部署操作人主键。 */
    public String getCreateBy() { return createBy; }
    /** @param value String，部署操作人主键。@return void，无返回值。 */
    public void setCreateBy(String value) { createBy = value; }
    /** @return Date，快照创建时间。 */
    public Date getCreateTime() { return createTime; }
    /** @param value Date，快照创建时间。@return void，无返回值。 */
    public void setCreateTime(Date value) { createTime = value; }
}
