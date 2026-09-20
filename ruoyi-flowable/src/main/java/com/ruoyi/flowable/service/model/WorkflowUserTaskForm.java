package com.ruoyi.flowable.service.model;

import java.util.List;
import org.flowable.bpmn.model.ExtensionAttribute;
import org.flowable.bpmn.model.UserTask;
import org.springframework.util.StringUtils;

/** 用户任务表单在 BPMN 中的稳定键和变量作用域解释。 */
public final class WorkflowUserTaskForm
{
    private WorkflowUserTaskForm()
    {
    }

    /** 正式模板优先于字段权限描述；无模板时以是否存在内嵌字段决定表单键。 */
    public static String formKey(UserTask task)
    {
        if (StringUtils.hasText(task.getFormKey()))
        {
            return task.getFormKey();
        }
        return task.getFormProperties() != null && !task.getFormProperties().isEmpty()
                ? WorkflowFormSourceType.EMBEDDED_FORM_KEY : null;
    }

    /** 保持 Flowable 扩展的既有语义：只解释首个属性，不 trim，true 忽略大小写。 */
    public static boolean isTaskLocal(UserTask task)
    {
        List<ExtensionAttribute> attributes = task.getAttributes() == null
                ? null : task.getAttributes().get("localScope");
        if (attributes == null || attributes.isEmpty() || attributes.get(0) == null)
        {
            return false;
        }
        String value = attributes.get(0).getValue();
        return "true".equalsIgnoreCase(value) || "1".equals(value);
    }
}
