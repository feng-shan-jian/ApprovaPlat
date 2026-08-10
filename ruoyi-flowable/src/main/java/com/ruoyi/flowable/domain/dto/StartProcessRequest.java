package com.ruoyi.flowable.domain.dto;

import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * 发起流程动作专用请求。
 *
 * @param processDefinitionId String，客户端选择的 Flowable 流程定义主键
 * @param businessKey String，可为空的外部业务主键
 * @param variables Map&lt;String, Object&gt;，仅包含开始表单字段的客户端变量
 * @param multiInstanceUserIds Map&lt;String,List&lt;Long&gt;&gt;，发起时按活动选择的会签或或签成员
 */
public record StartProcessRequest(
        String processDefinitionId,
        String businessKey,
        Map<String, Object> variables,
        Map<String, List<Long>> multiInstanceUserIds)
{
    /**
     * 创建发起请求并复制顶层变量映射，避免调用期间增删字段。
     *
     * @param processDefinitionId String，客户端选择的 Flowable 流程定义主键
     * @param businessKey String，可为空的外部业务主键
     * @param variables Map&lt;String, Object&gt;，开始表单变量，允许为空
     * @param multiInstanceUserIds Map&lt;String,List&lt;Long&gt;&gt;，发起时按活动选择的会签或或签成员
     * @return 无返回值，构造后 variables 和 multiInstanceUserIds 均为不可修改映射
     */
    public StartProcessRequest
    {
        variables = variables == null
                ? Map.of()
                : Collections.unmodifiableMap(new LinkedHashMap<>(variables));
        LinkedHashMap<String, List<Long>> copiedSelections = new LinkedHashMap<>();
        if (multiInstanceUserIds != null)
        {
            multiInstanceUserIds.forEach((activityId, userIds) -> copiedSelections.put(
                    activityId, userIds == null ? null : List.copyOf(userIds)));
        }
        multiInstanceUserIds = Collections.unmodifiableMap(copiedSelections);
    }

    /**
     * 兼容既有不包含发起多实例成员的 Java 调用方式。
     *
     * @param processDefinitionId String，客户端选择的 Flowable 流程定义主键
     * @param businessKey String，可为空的外部业务主键
     * @param variables Map&lt;String,Object&gt;，开始表单变量
     * @return 无返回值，成员选择使用空集合
     */
    public StartProcessRequest(String processDefinitionId, String businessKey,
            Map<String, Object> variables)
    {
        this(processDefinitionId, businessKey, variables, Map.of());
    }
}
