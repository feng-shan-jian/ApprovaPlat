package com.ruoyi.flowable.domain.dto;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;

/**
 * 撤回本人已完成任务的专用请求。
 *
 * @param processInstanceId String，任务所属流程实例主键
 * @param taskId String，当前用户此前完成的历史任务主键
 * @param comment String，撤回原因
 */
public record WorkflowProcessRevokeRequest(
        @NotBlank(message = "流程实例主键不能为空")
        @Size(max = 64, message = "流程实例主键长度不能超过64个字符")
        String processInstanceId,
        @NotBlank(message = "历史任务主键不能为空")
        @Size(max = 64, message = "历史任务主键长度不能超过64个字符")
        String taskId,
        @NotBlank(message = "撤回原因不能为空")
        @Size(max = 500, message = "撤回原因长度不能超过500个字符")
        String comment)
{
}
