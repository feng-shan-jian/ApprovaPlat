package com.ruoyi.flowable.domain.dto;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;

/**
 * 保存流程设计请求，元数据修改必须使用独立模型修改入口。
 *
 * @param modelId String，Flowable 模型主键
 * @param bpmnXml String，BPMN 2.0 XML 正文
 * @param newVersion Boolean，是否创建新的模型版本
 */
public record WorkflowModelSaveRequest(
        @NotBlank(message = "模型主键不能为空")
        @Size(max = 64, message = "模型主键长度不能超过64个字符")
        String modelId,
        @NotBlank(message = "BPMN XML 不能为空")
        String bpmnXml,
        Boolean newVersion)
{
}
