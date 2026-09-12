package com.ruoyi.flowable.domain.vo;

/** 草稿归属于请求中的模型和当前身份；不返回其他设计者的任何数据。 */
public record WorkflowModelDesignDraftView(String modelId, int baseModelRevision,
        int revision, String bpmnXml, int currentModelRevision)
{
}
