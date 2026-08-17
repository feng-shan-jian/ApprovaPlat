package com.ruoyi.flowable.domain.dto;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;
import jakarta.validation.constraints.Pattern;

/**
 * 保存流程设计请求，元数据修改必须使用独立模型修改入口。
 *
 * @param modelId String，Flowable 模型主键
 * @param bpmnXml String，BPMN 2.0 XML 正文
 * @param expectedBpmnSha256 String，设计页加载时取得的服务端 BPMN 内容摘要
 * @param newVersion Boolean，显式新版本标志；已部署或历史版本会自动另存新版本
 */
public record WorkflowModelSaveRequest(
        @NotBlank(message = "模型主键不能为空")
        @Size(max = 64, message = "模型主键长度不能超过64个字符")
        String modelId,
        @NotBlank(message = "BPMN XML 不能为空")
        String bpmnXml,
        @NotBlank(message = "模型基线摘要不能为空")
        @Pattern(regexp = "^[0-9a-f]{64}$", message = "模型基线摘要必须为小写 SHA-256")
        String expectedBpmnSha256,
        Boolean newVersion)
{
}
