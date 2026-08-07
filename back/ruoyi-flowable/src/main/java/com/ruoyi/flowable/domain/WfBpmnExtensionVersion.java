package com.ruoyi.flowable.domain;

import java.util.Date;

/**
 * BPMN 扩展不可变版本，对应 {@code wf_bpmn_extension_version}。
 */
public class WfBpmnExtensionVersion
{
    /** 扩展版本主键。 */
    private Long versionId;

    /** 所属扩展目录主键。 */
    private Long extensionId;

    /** 单扩展内从 1 连续递增的版本号。 */
    private Integer versionNo;

    /** 服务端已安装 Java 处理器稳定键。 */
    private String implementationKey;

    /** 服务端处理器提供的配置 Schema JSON。 */
    private String configSchema;

    /** 版本定义的 SHA-256 校验和。 */
    private String checksum;

    /** 创建人正式用户主键字符串。 */
    private String createBy;

    /** 创建时间。 */
    private Date createTime;

    /**
     * 获取版本主键。
     * @return Long，版本主键
     */
    public Long getVersionId() { return versionId; }

    /**
     * 设置版本主键。
     * @param versionId Long，版本主键
     * @return void，无返回值
     */
    public void setVersionId(Long versionId) { this.versionId = versionId; }

    /**
     * 获取扩展主键。
     * @return Long，扩展目录主键
     */
    public Long getExtensionId() { return extensionId; }

    /**
     * 设置扩展主键。
     * @param extensionId Long，扩展目录主键
     * @return void，无返回值
     */
    public void setExtensionId(Long extensionId) { this.extensionId = extensionId; }

    /**
     * 获取版本号。
     * @return Integer，版本号
     */
    public Integer getVersionNo() { return versionNo; }

    /**
     * 设置版本号。
     * @param versionNo Integer，版本号
     * @return void，无返回值
     */
    public void setVersionNo(Integer versionNo) { this.versionNo = versionNo; }

    /**
     * 获取处理器稳定键。
     * @return String，已安装处理器稳定键
     */
    public String getImplementationKey() { return implementationKey; }

    /**
     * 设置处理器稳定键。
     * @param implementationKey String，已安装处理器稳定键
     * @return void，无返回值
     */
    public void setImplementationKey(String implementationKey) { this.implementationKey = implementationKey; }

    /**
     * 获取配置 Schema。
     * @return String，JSON Schema 文本
     */
    public String getConfigSchema() { return configSchema; }

    /**
     * 设置配置 Schema。
     * @param configSchema String，JSON Schema 文本
     * @return void，无返回值
     */
    public void setConfigSchema(String configSchema) { this.configSchema = configSchema; }

    /**
     * 获取版本校验和。
     * @return String，SHA-256 小写十六进制
     */
    public String getChecksum() { return checksum; }

    /**
     * 设置版本校验和。
     * @param checksum String，SHA-256 小写十六进制
     * @return void，无返回值
     */
    public void setChecksum(String checksum) { this.checksum = checksum; }

    /**
     * 获取创建人。
     * @return String，正式用户主键字符串
     */
    public String getCreateBy() { return createBy; }

    /**
     * 设置创建人。
     * @param createBy String，正式用户主键字符串
     * @return void，无返回值
     */
    public void setCreateBy(String createBy) { this.createBy = createBy; }

    /**
     * 获取创建时间。
     * @return Date，创建时间
     */
    public Date getCreateTime() { return createTime; }

    /**
     * 设置创建时间。
     * @param createTime Date，创建时间
     * @return void，无返回值
     */
    public void setCreateTime(Date createTime) { this.createTime = createTime; }
}
