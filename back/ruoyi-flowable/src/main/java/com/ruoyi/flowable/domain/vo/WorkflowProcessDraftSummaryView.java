package com.ruoyi.flowable.domain.vo;

import java.time.Instant;

/**
 * 当前用户草稿列表行。
 *
 * @param draftId String，草稿 UUID
 * @param processName String，流程名称快照
 * @param processDefinitionKey String，流程定义 key
 * @param processDefinitionVersion int，流程定义版本
 * @param status String，草稿状态
 * @param revisionNo long，乐观锁版本
 * @param businessKey String，可为空的业务主键
 * @param createTime Instant，创建时间
 * @param updateTime Instant，最后更新时间
 * @param editable boolean，当前允许继续编辑时为 true
 * @param submittable boolean，当前允许正式提交时为 true
 * @param statusReason String，稳定可用性状态码
 */
public record WorkflowProcessDraftSummaryView(String draftId, String processName,
        String processDefinitionKey, int processDefinitionVersion, String status,
        long revisionNo, String businessKey, Instant createTime, Instant updateTime,
        boolean editable, boolean submittable, String statusReason)
{
}
