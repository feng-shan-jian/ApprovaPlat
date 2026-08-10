package com.ruoyi.flowable.domain;

import java.util.Date;

/**
 * 部署时冻结的 BPMN 扩展执行快照，对应 {@code wf_deploy_extension_snapshot}。
 */
public class WfDeployExtensionSnapshot
{
    /** 快照主键。 */
    private Long snapshotId;
    /** Flowable 部署主键。 */
    private String deployId;
    /** BPMN 可执行流程标识。 */
    private String processKey;
    /** BPMN 活动元素标识。 */
    private String elementId;
    /** 扩展稳定键。 */
    private String extensionKey;
    /** 冻结版本主键。 */
    private Long extensionVersionId;
    /** 冻结版本号。 */
    private Integer versionNo;
    /** 扩展类型。 */
    private String extensionType;
    /** 已安装处理器稳定键。 */
    private String implementationKey;
    /** 规范化后的节点配置 JSON。 */
    private String configJson;
    /** 版本定义校验和。 */
    private String versionChecksum;
    /** 完整执行快照校验和。 */
    private String snapshotChecksum;
    /** 部署操作人正式用户主键。 */
    private String createBy;
    /** 创建时间。 */
    private Date createTime;

    /** @return Long，快照主键。 */
    public Long getSnapshotId() { return snapshotId; }
    /** @param snapshotId Long，快照主键；@return void，无返回值。 */
    public void setSnapshotId(Long snapshotId) { this.snapshotId = snapshotId; }
    /** @return String，Flowable 部署主键。 */
    public String getDeployId() { return deployId; }
    /** @param deployId String，Flowable 部署主键；@return void，无返回值。 */
    public void setDeployId(String deployId) { this.deployId = deployId; }
    /** @return String，BPMN 可执行流程标识。 */
    public String getProcessKey() { return processKey; }
    /** @param processKey String，BPMN 可执行流程标识；@return void，无返回值。 */
    public void setProcessKey(String processKey) { this.processKey = processKey; }
    /** @return String，BPMN 活动元素标识。 */
    public String getElementId() { return elementId; }
    /** @param elementId String，BPMN 活动元素标识；@return void，无返回值。 */
    public void setElementId(String elementId) { this.elementId = elementId; }
    /** @return String，扩展稳定键。 */
    public String getExtensionKey() { return extensionKey; }
    /** @param extensionKey String，扩展稳定键；@return void，无返回值。 */
    public void setExtensionKey(String extensionKey) { this.extensionKey = extensionKey; }
    /** @return Long，冻结版本主键。 */
    public Long getExtensionVersionId() { return extensionVersionId; }
    /** @param extensionVersionId Long，冻结版本主键；@return void，无返回值。 */
    public void setExtensionVersionId(Long extensionVersionId) { this.extensionVersionId = extensionVersionId; }
    /** @return Integer，冻结版本号。 */
    public Integer getVersionNo() { return versionNo; }
    /** @param versionNo Integer，冻结版本号；@return void，无返回值。 */
    public void setVersionNo(Integer versionNo) { this.versionNo = versionNo; }
    /** @return String，扩展类型。 */
    public String getExtensionType() { return extensionType; }
    /** @param extensionType String，扩展类型；@return void，无返回值。 */
    public void setExtensionType(String extensionType) { this.extensionType = extensionType; }
    /** @return String，已安装处理器稳定键。 */
    public String getImplementationKey() { return implementationKey; }
    /** @param implementationKey String，已安装处理器稳定键；@return void，无返回值。 */
    public void setImplementationKey(String implementationKey) { this.implementationKey = implementationKey; }
    /** @return String，规范化节点配置 JSON。 */
    public String getConfigJson() { return configJson; }
    /** @param configJson String，规范化节点配置 JSON；@return void，无返回值。 */
    public void setConfigJson(String configJson) { this.configJson = configJson; }
    /** @return String，版本定义校验和。 */
    public String getVersionChecksum() { return versionChecksum; }
    /** @param versionChecksum String，版本定义校验和；@return void，无返回值。 */
    public void setVersionChecksum(String versionChecksum) { this.versionChecksum = versionChecksum; }
    /** @return String，完整快照校验和。 */
    public String getSnapshotChecksum() { return snapshotChecksum; }
    /** @param snapshotChecksum String，完整快照校验和；@return void，无返回值。 */
    public void setSnapshotChecksum(String snapshotChecksum) { this.snapshotChecksum = snapshotChecksum; }
    /** @return String，部署操作人正式用户主键。 */
    public String getCreateBy() { return createBy; }
    /** @param createBy String，部署操作人正式用户主键；@return void，无返回值。 */
    public void setCreateBy(String createBy) { this.createBy = createBy; }
    /** @return Date，快照创建时间。 */
    public Date getCreateTime() { return createTime; }
    /** @param createTime Date，快照创建时间；@return void，无返回值。 */
    public void setCreateTime(Date createTime) { this.createTime = createTime; }
}
