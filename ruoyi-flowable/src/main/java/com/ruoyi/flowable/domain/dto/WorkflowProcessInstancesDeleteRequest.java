package com.ruoyi.flowable.domain.dto;

import java.util.List;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotEmpty;
import jakarta.validation.constraints.Size;

/**
 * 已结束流程实例批量删除请求。
 *
 * @param instanceIds List&lt;String&gt;，最多一百个 Flowable 流程实例主键
 */
public record WorkflowProcessInstancesDeleteRequest(
        @NotEmpty(message = "流程实例主键不能为空")
        @Size(max = 100, message = "单次最多删除100个流程实例")
        List<@NotBlank(message = "流程实例主键不能为空")
                @Size(max = 64, message = "流程实例主键过长") String> instanceIds)
{
}
