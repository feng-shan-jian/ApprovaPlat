package com.ruoyi.flowable.domain;

import java.time.LocalDateTime;

/**
 * 企业流程申请草稿正式持久化对象。
 *
 * @param draftId String，服务端生成的草稿 UUID
 * @param ownerUserId Long，草稿所有者正式用户主键
 * @param processDefinitionId String，创建草稿时绑定的 Flowable 定义主键
 * @param processDefinitionKey String，流程定义 key
 * @param processDefinitionVersion int，流程定义版本号
 * @param deploymentId String，流程定义所属部署主键
 * @param processName String，流程名称快照
 * @param sourceType String，部署表单来源类型
 * @param formId Long，模板来源主键，内嵌表单为空
 * @param formKey String，部署表单键
 * @param startNodeKey String，开始节点 key
 * @param formName String，部署时表单名称
 * @param nodeName String，部署时开始节点名称
 * @param snapshotCreateTime LocalDateTime，部署表单快照创建时间
 * @param formSnapshot String，不可变部署表单 JSON
 * @param formSnapshotSha256 String，部署表单关联及正文摘要
 * @param startMultiInstanceAssignments String，部署 BPMN 发起成员字段不可变 JSON 快照
 * @param formValues String，草稿字段值 JSON
 * @param multiInstanceUserIds String，按活动保存的发起会签或或签成员 JSON
 * @param businessKey String，可为空的业务主键
 * @param draftStatus WorkflowProcessDraftStatus，草稿生命周期状态
 * @param revisionNo long，乐观锁版本
 * @param submittedProcessInstanceId String，成功提交产生的唯一实例主键
 * @param submittedTime LocalDateTime，成功提交时间
 * @param deletedTime LocalDateTime，删除时间
 * @param createTime LocalDateTime，创建时间
 * @param updateTime LocalDateTime，最后更新时间
 */
public record WfProcessDraft(String draftId, Long ownerUserId,
        String processDefinitionId, String processDefinitionKey,
        int processDefinitionVersion, String deploymentId, String processName,
        String sourceType, Long formId, String formKey, String startNodeKey,
        String formName, String nodeName, LocalDateTime snapshotCreateTime,
        String formSnapshot, String formSnapshotSha256,
        String startMultiInstanceAssignments, String formValues,
        String multiInstanceUserIds,
        String businessKey, WorkflowProcessDraftStatus draftStatus, long revisionNo,
        String submittedProcessInstanceId, LocalDateTime submittedTime,
        LocalDateTime deletedTime, LocalDateTime createTime, LocalDateTime updateTime)
{
}
