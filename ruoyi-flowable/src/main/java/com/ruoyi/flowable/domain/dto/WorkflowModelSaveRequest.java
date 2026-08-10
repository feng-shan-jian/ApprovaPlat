package com.ruoyi.flowable.domain.dto;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Size;

/**
 * 保存流程设计请求，元数据修改必须使用独立模型修改入口。
 *
 * @param requestId String，本次用户保存意图的 UUID 幂等键
 * @param modelId String，Flowable 模型主键
 * @param bpmnXml String，BPMN 2.0 XML 正文
 * @param newVersion Boolean，兼容旧客户端的显式新版本标志；已部署或历史版本会自动另存新版本
 */
public record WorkflowModelSaveRequest(
        @NotBlank(message = "保存请求主键不能为空")
        @Pattern(
                regexp = "(?i)^[0-9a-f]{8}-[0-9a-f]{4}-[1-5][0-9a-f]{3}-[89ab][0-9a-f]{3}-[0-9a-f]{12}$",
                message = "保存请求主键必须为 UUID")
        String requestId,
        @NotBlank(message = "模型主键不能为空")
        @Size(max = 64, message = "模型主键长度不能超过64个字符")
        String modelId,
        @NotBlank(message = "BPMN XML 不能为空")
        String bpmnXml,
        Boolean newVersion)
{
}
