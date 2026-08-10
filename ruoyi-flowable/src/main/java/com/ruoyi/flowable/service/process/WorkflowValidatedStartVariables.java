package com.ruoyi.flowable.service.process;

import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * 部署表单校验后的发起变量与上传字段附件引用。
 *
 * @param variables Map&lt;String, Object&gt;，深度规范化且不可修改的表单变量
 * @param attachmentIdsByField Map&lt;String, List&lt;String&gt;&gt;，上传字段到临时附件 UUID 的不可变映射
 */
public record WorkflowValidatedStartVariables(
        Map<String, Object> variables,
        Map<String, List<String>> attachmentIdsByField)
{
    /**
     * 创建变量校验结果并复制两层附件引用集合。
     *
     * @param variables Map&lt;String, Object&gt;，已经由校验器深度规范化的变量
     * @param attachmentIdsByField Map&lt;String, List&lt;String&gt;&gt;，上传字段附件 UUID 映射
     * @return 无返回值，构造后两个顶层映射和附件列表均不可修改
     */
    public WorkflowValidatedStartVariables
    {
        variables = variables == null ? Map.of()
                : Collections.unmodifiableMap(new LinkedHashMap<>(variables));
        LinkedHashMap<String, List<String>> copiedReferences = new LinkedHashMap<>();
        if (attachmentIdsByField != null)
        {
            attachmentIdsByField.forEach((fieldName, attachmentIds) ->
                    copiedReferences.put(fieldName, List.copyOf(attachmentIds)));
        }
        attachmentIdsByField = Collections.unmodifiableMap(copiedReferences);
    }
}
