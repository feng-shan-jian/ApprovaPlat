package com.ruoyi.flowable.domain.dto;

import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.Map;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;

/**
 * 发起人修改退回申请后重新提交的专用请求。
 *
 * @param taskId String，退回后由发起人独占的活动任务主键
 * @param variables Map&lt;String, Object&gt;，按原部署开始表单提交的完整业务变量
 */
public record WorkflowApplicationResubmitRequest(
        @NotBlank(message = "任务主键不能为空")
        @Size(max = 64, message = "任务主键长度不能超过64个字符")
        String taskId,
        Map<String, Object> variables)
{
    /**
     * 创建重新提交请求并复制顶层变量，避免校验期间被调用方修改。
     *
     * @param taskId String，退回修改任务主键
     * @param variables Map&lt;String, Object&gt;，原开始表单业务变量
     * @return 无返回值，构造后 variables 为不可修改副本
     */
    public WorkflowApplicationResubmitRequest
    {
        variables = variables == null ? Map.of()
                : Collections.unmodifiableMap(new LinkedHashMap<>(variables));
    }
}
