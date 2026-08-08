package com.ruoyi.flowable.domain.vo;

/**
 * 草稿正式提交结果。
 *
 * @param draftId String，草稿 UUID
 * @param processInstanceId String，首次提交创建且重复提交复用的实例主键
 * @param processDefinitionId String，实际启动的流程定义主键
 * @param revisionNo long，提交后的草稿版本
 */
public record WorkflowProcessDraftSubmitView(String draftId, String processInstanceId,
        String processDefinitionId, long revisionNo)
{
}
