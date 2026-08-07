package com.ruoyi.flowable.service.model;

/**
 * 部署表单快照的正式来源类型。
 */
public enum WorkflowFormSourceType
{
    /** 引用 {@code wf_form} 的正式表单模板。 */
    TEMPLATE,

    /** BPMN StartEvent 或 UserTask 中的 Flowable FormData。 */
    EMBEDDED;

    /** 内嵌表单在部署快照中使用的稳定表单键。 */
    public static final String EMBEDDED_FORM_KEY = "embedded";

    /**
     * 校验来源类型与来源表单主键是否一致。
     *
     * @param sourceType String，数据库或提交快照中的来源类型
     * @param formId Long，模板来源主键；内嵌来源必须为空
     * @return boolean，来源类型和主键组合合法时返回 true
     */
    public static boolean isConsistent(String sourceType, Long formId)
    {
        if (TEMPLATE.name().equals(sourceType))
        {
            return formId != null && formId > 0;
        }
        return EMBEDDED.name().equals(sourceType) && formId == null;
    }
}
