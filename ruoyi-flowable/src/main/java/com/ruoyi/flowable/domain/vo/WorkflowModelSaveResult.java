package com.ruoyi.flowable.domain.vo;

/**
 * 流程模型保存结果。
 *
 * @param modelId String，真实保存成功的 Flowable 模型主键
 * @param version Integer，真实保存成功的模型版本号
 * @param revision Integer，保存完成后的 Flowable 模型修订号
 */
public record WorkflowModelSaveResult(String modelId, Integer version, Integer revision)
{
}
