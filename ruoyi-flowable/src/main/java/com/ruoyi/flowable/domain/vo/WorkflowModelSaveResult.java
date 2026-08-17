package com.ruoyi.flowable.domain.vo;

/**
 * 流程模型保存结果。
 *
 * @param modelId String，真实保存成功的 Flowable 模型主键
 * @param version Integer，真实保存成功的模型版本号
 * @param bpmnSha256 String，服务端规范化 BPMN XML 的 SHA-256 摘要
 */
public record WorkflowModelSaveResult(String modelId, Integer version, String bpmnSha256)
{
}
