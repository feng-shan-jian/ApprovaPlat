package com.ruoyi.flowable.domain;

import java.util.Date;

/**
 * 排他或包容网关条件分支的不可变部署快照，保存在 Flowable 业务制品资源
 * {@code approvaplat/conditions-v1.json}。
 */
public class WfDeployConditionRule
{
    private Long ruleId;
    private String deployId;
    private String processKey;
    private String gatewayId;
    private String gatewayType;
    private String gatewayToken;
    private String flowId;
    private String flowName;
    private String flowToken;
    private Boolean defaultFlow;
    private String ruleJson;
    private String celConfigJson;
    private String snapshotChecksum;
    private String createBy;
    private Date createTime;

    /** @return Long，快照主键。 */
    public Long getRuleId() { return ruleId; }
    /** @param value Long，快照主键。 @return void，无返回值。 */
    public void setRuleId(Long value) { ruleId = value; }
    /** @return String，Flowable 部署主键。 */
    public String getDeployId() { return deployId; }
    /** @param value String，Flowable 部署主键。 @return void，无返回值。 */
    public void setDeployId(String value) { deployId = value; }
    /** @return String，可执行流程标识。 */
    public String getProcessKey() { return processKey; }
    /** @param value String，可执行流程标识。 @return void，无返回值。 */
    public void setProcessKey(String value) { processKey = value; }
    /** @return String，网关 BPMN 标识。 */
    public String getGatewayId() { return gatewayId; }
    /** @param value String，网关 BPMN 标识。 @return void，无返回值。 */
    public void setGatewayId(String value) { gatewayId = value; }
    /** @return String，EXCLUSIVE 或 INCLUSIVE。 */
    public String getGatewayType() { return gatewayType; }
    /** @param value String，网关类型。 @return void，无返回值。 */
    public void setGatewayType(String value) { gatewayType = value; }
    /** @return String，固定表达式使用的网关摘要令牌。 */
    public String getGatewayToken() { return gatewayToken; }
    /** @param value String，网关摘要令牌。 @return void，无返回值。 */
    public void setGatewayToken(String value) { gatewayToken = value; }
    /** @return String，顺序流 BPMN 标识。 */
    public String getFlowId() { return flowId; }
    /** @param value String，顺序流 BPMN 标识。 @return void，无返回值。 */
    public void setFlowId(String value) { flowId = value; }
    /** @return String，部署时分支名称。 */
    public String getFlowName() { return flowName; }
    /** @param value String，部署时分支名称。 @return void，无返回值。 */
    public void setFlowName(String value) { flowName = value; }
    /** @return String，固定表达式使用的分支摘要令牌。 */
    public String getFlowToken() { return flowToken; }
    /** @param value String，分支摘要令牌。 @return void，无返回值。 */
    public void setFlowToken(String value) { flowToken = value; }
    /** @return Boolean，是否为唯一默认分支。 */
    public Boolean getDefaultFlow() { return defaultFlow; }
    /** @param value Boolean，默认分支标志。 @return void，无返回值。 */
    public void setDefaultFlow(Boolean value) { defaultFlow = value; }
    /** @return String，规范化的受控规则 JSON。 */
    public String getRuleJson() { return ruleJson; }
    /** @param value String，规范化规则 JSON。 @return void，无返回值。 */
    public void setRuleJson(String value) { ruleJson = value; }
    /** @return String，规范化 CEL 配置；默认分支为空。 */
    public String getCelConfigJson() { return celConfigJson; }
    /** @param value String，规范化 CEL 配置。 @return void，无返回值。 */
    public void setCelConfigJson(String value) { celConfigJson = value; }
    /** @return String，快照完整性 SHA-256。 */
    public String getSnapshotChecksum() { return snapshotChecksum; }
    /** @param value String，快照完整性 SHA-256。 @return void，无返回值。 */
    public void setSnapshotChecksum(String value) { snapshotChecksum = value; }
    /** @return String，部署操作人正式用户主键。 */
    public String getCreateBy() { return createBy; }
    /** @param value String，部署操作人正式用户主键。 @return void，无返回值。 */
    public void setCreateBy(String value) { createBy = value; }
    /** @return Date，快照创建时间副本。 */
    public Date getCreateTime() { return copy(createTime); }
    /** @param value Date，快照创建时间。 @return void，无返回值。 */
    public void setCreateTime(Date value) { createTime = copy(value); }

    /**
     * 防御复制可变时间对象。
     * @param value Date，允许为空的时间
     * @return Date，时间副本或 null
     */
    private Date copy(Date value)
    {
        return value == null ? null : new Date(value.getTime());
    }
}
