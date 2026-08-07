package com.ruoyi.flowable.domain;

import com.ruoyi.common.core.domain.BaseEntity;

/**
 * BPMN 受控扩展目录，对应 {@code wf_bpmn_extension}。
 */
public class WfBpmnExtension extends BaseEntity
{
    private static final long serialVersionUID = 1L;

    /** 扩展目录主键。 */
    private Long extensionId;

    /** 设计器和 BPMN 使用的稳定业务键。 */
    private String extensionKey;

    /** 用户可见扩展名称。 */
    private String extensionName;

    /** 扩展类型；当前可执行闭环支持 JAVA。 */
    private String extensionType;

    /** 目录状态：ENABLED 或 DISABLED。 */
    private String status;

    /**
     * 获取扩展主键。
     * @return Long，扩展目录主键
     */
    public Long getExtensionId()
    {
        return extensionId;
    }

    /**
     * 设置扩展主键。
     * @param extensionId Long，扩展目录主键
     * @return void，无返回值
     */
    public void setExtensionId(Long extensionId)
    {
        this.extensionId = extensionId;
    }

    /**
     * 获取稳定扩展键。
     * @return String，扩展业务键
     */
    public String getExtensionKey()
    {
        return extensionKey;
    }

    /**
     * 设置稳定扩展键。
     * @param extensionKey String，扩展业务键
     * @return void，无返回值
     */
    public void setExtensionKey(String extensionKey)
    {
        this.extensionKey = extensionKey;
    }

    /**
     * 获取扩展名称。
     * @return String，用户可见名称
     */
    public String getExtensionName()
    {
        return extensionName;
    }

    /**
     * 设置扩展名称。
     * @param extensionName String，用户可见名称
     * @return void，无返回值
     */
    public void setExtensionName(String extensionName)
    {
        this.extensionName = extensionName;
    }

    /**
     * 获取扩展类型。
     * @return String，扩展类型编码
     */
    public String getExtensionType()
    {
        return extensionType;
    }

    /**
     * 设置扩展类型。
     * @param extensionType String，扩展类型编码
     * @return void，无返回值
     */
    public void setExtensionType(String extensionType)
    {
        this.extensionType = extensionType;
    }

    /**
     * 获取目录状态。
     * @return String，ENABLED 或 DISABLED
     */
    public String getStatus()
    {
        return status;
    }

    /**
     * 设置目录状态。
     * @param status String，ENABLED 或 DISABLED
     * @return void，无返回值
     */
    public void setStatus(String status)
    {
        this.status = status;
    }
}
