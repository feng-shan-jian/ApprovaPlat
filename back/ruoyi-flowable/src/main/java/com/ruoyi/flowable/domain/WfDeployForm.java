package com.ruoyi.flowable.domain;

import com.ruoyi.common.core.domain.BaseEntity;

/**
 * 部署时固化的节点表单快照，对应业务表 {@code wf_deploy_form}。
 */
public class WfDeployForm extends BaseEntity
{
    private static final long serialVersionUID = 1L;

    /** Flowable 部署主键，沿用旧字段名 deployId。 */
    private String deployId;

    /** 快照来源表单主键。 */
    private Long formId;

    /** BPMN 中配置的表单键。 */
    private String formKey;

    /** BPMN 节点键。 */
    private String nodeKey;

    /** 部署时的表单名称。 */
    private String formName;

    /** 部署时的节点名称。 */
    private String nodeName;

    /** 部署时固化的不可变表单 JSON。 */
    private String content;

    /** 逻辑删除标志：0 表示有效，2 表示已删除。 */
    private String delFlag;

    /**
     * 获取部署主键。
     * @return String，Flowable 部署主键
     */
    public String getDeployId()
    {
        return deployId;
    }

    /**
     * 设置部署主键。
     * @param deployId String，Flowable 部署主键
     * @return void，无返回值
     */
    public void setDeployId(String deployId)
    {
        this.deployId = deployId;
    }

    /**
     * 获取来源表单主键。
     * @return Long，来源表单主键
     */
    public Long getFormId()
    {
        return formId;
    }

    /**
     * 设置来源表单主键。
     * @param formId Long，来源表单主键
     * @return void，无返回值
     */
    public void setFormId(Long formId)
    {
        this.formId = formId;
    }

    /**
     * 获取表单键。
     * @return String，BPMN 表单键
     */
    public String getFormKey()
    {
        return formKey;
    }

    /**
     * 设置表单键。
     * @param formKey String，BPMN 表单键
     * @return void，无返回值
     */
    public void setFormKey(String formKey)
    {
        this.formKey = formKey;
    }

    /**
     * 获取节点键。
     * @return String，BPMN 节点键
     */
    public String getNodeKey()
    {
        return nodeKey;
    }

    /**
     * 设置节点键。
     * @param nodeKey String，BPMN 节点键
     * @return void，无返回值
     */
    public void setNodeKey(String nodeKey)
    {
        this.nodeKey = nodeKey;
    }

    /**
     * 获取快照表单名称。
     * @return String，部署时表单名称
     */
    public String getFormName()
    {
        return formName;
    }

    /**
     * 设置快照表单名称。
     * @param formName String，部署时表单名称
     * @return void，无返回值
     */
    public void setFormName(String formName)
    {
        this.formName = formName;
    }

    /**
     * 获取快照节点名称。
     * @return String，部署时节点名称
     */
    public String getNodeName()
    {
        return nodeName;
    }

    /**
     * 设置快照节点名称。
     * @param nodeName String，部署时节点名称
     * @return void，无返回值
     */
    public void setNodeName(String nodeName)
    {
        this.nodeName = nodeName;
    }

    /**
     * 获取不可变表单快照。
     * @return String，部署时固化的表单 JSON
     */
    public String getContent()
    {
        return content;
    }

    /**
     * 设置不可变表单快照。
     * @param content String，部署时固化的表单 JSON
     * @return void，无返回值
     */
    public void setContent(String content)
    {
        this.content = content;
    }

    /**
     * 获取逻辑删除标志。
     * @return String，0 表示有效，2 表示已删除
     */
    public String getDelFlag()
    {
        return delFlag;
    }

    /**
     * 设置逻辑删除标志。
     * @param delFlag String，逻辑删除标志
     * @return void，无返回值
     */
    public void setDelFlag(String delFlag)
    {
        this.delFlag = delFlag;
    }
}
