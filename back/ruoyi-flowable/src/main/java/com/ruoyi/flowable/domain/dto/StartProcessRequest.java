package com.ruoyi.flowable.domain.dto;

import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.Map;

/**
 * 发起流程动作专用请求。
 *
 * @param processDefinitionId String，客户端选择的 Flowable 流程定义主键
 * @param businessKey String，可为空的外部业务主键
 * @param variables Map&lt;String, Object&gt;，仅包含开始表单字段的客户端变量
 */
public record StartProcessRequest(
        String processDefinitionId,
        String businessKey,
        Map<String, Object> variables)
{
    /**
     * 创建发起请求并复制顶层变量映射，避免调用期间增删字段。
     *
     * @param processDefinitionId String，客户端选择的 Flowable 流程定义主键
     * @param businessKey String，可为空的外部业务主键
     * @param variables Map&lt;String, Object&gt;，开始表单变量，允许为空
     * @return 无返回值，构造后 variables 为不可修改的有序映射
     */
    public StartProcessRequest
    {
        variables = variables == null
                ? Map.of()
                : Collections.unmodifiableMap(new LinkedHashMap<>(variables));
    }
}
