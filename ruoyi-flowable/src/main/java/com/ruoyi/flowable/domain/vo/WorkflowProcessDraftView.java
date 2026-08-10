package com.ruoyi.flowable.domain.vo;

import java.time.Instant;
import java.util.Map;
import java.util.List;

/**
 * 当前所有者可读取的流程申请草稿详情。
 *
 * @param draftId String，草稿 UUID
 * @param processDefinitionId String，绑定的流程定义主键
 * @param processDefinitionKey String，流程定义 key
 * @param processDefinitionVersion int，流程定义版本
 * @param deploymentId String，绑定部署主键
 * @param processName String，流程名称快照
 * @param sourceType String，表单来源类型
 * @param formId Long，模板表单主键
 * @param formKey String，表单 key
 * @param startNodeKey String，开始节点 key
 * @param formName String，表单名称快照
 * @param nodeName String，节点名称快照
 * @param snapshotCreateTime Instant，部署快照时间
 * @param formSnapshot WorkflowProcessFormView，包含关联元数据与 content 的部署表单快照
 * @param variables Map&lt;String,Object&gt;，草稿字段值
 * @param multiInstanceUserIds Map&lt;String,List&lt;Long&gt;&gt;，按活动保存的发起会签或或签成员
 * @param businessKey String，可为空的业务主键
 * @param status String，ACTIVE、SUBMITTED 或 DELETED
 * @param revisionNo long，乐观锁版本
 * @param processInstanceId String，已提交实例主键
 * @param createTime Instant，创建时间
 * @param updateTime Instant，最后更新时间
 * @param editable boolean，当前状态和定义允许继续编辑时为 true
 * @param submittable boolean，当前状态、定义和身份允许正式提交时为 true
 * @param statusReason String，稳定可用性状态码
 */
public record WorkflowProcessDraftView(String draftId, String processDefinitionId,
        String processDefinitionKey, int processDefinitionVersion, String deploymentId,
        String processName, String sourceType, Long formId, String formKey,
        String startNodeKey, String formName, String nodeName, Instant snapshotCreateTime,
        WorkflowProcessFormView formSnapshot, Map<String, Object> variables,
        Map<String, List<Long>> multiInstanceUserIds, String businessKey,
        String status, long revisionNo, String processInstanceId,
        Instant createTime, Instant updateTime, boolean editable, boolean submittable,
        String statusReason)
{
}
