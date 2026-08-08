package com.ruoyi.flowable.domain.vo;

import java.time.Instant;
import java.util.List;

/**
 * 从 {@code wf_deploy_form} 原样读取的不可变部署表单快照视图。
 *
 * @param definitionId String，流程定义主键
 * @param deploymentId String，部署主键
 * @param processInstanceId String，详情场景的实例主键，首次发起时为空
 * @param sourceType String，TEMPLATE 或 EMBEDDED
 * @param formId Long，快照来源表单主键
 * @param formKey String，BPMN 表单键
 * @param nodeKey String，BPMN 开始节点主键
 * @param formName String，部署时表单名称
 * @param nodeName String，部署时节点名称
 * @param content String，部署时固化且不回连当前模板的表单 JSON
 * @param snapshotTime Instant，部署快照创建时间
 * @param startMultiInstanceAssignments List&lt;WorkflowStartMultiInstanceAssignmentView&gt;，发起页必须填写的会签或或签成员字段
 */
public record WorkflowProcessFormView(
        String definitionId,
        String deploymentId,
        String processInstanceId,
        String sourceType,
        Long formId,
        String formKey,
        String nodeKey,
        String formName,
        String nodeName,
        String content,
        Instant snapshotTime,
        List<WorkflowStartMultiInstanceAssignmentView> startMultiInstanceAssignments)
{
    /**
     * 创建不可变开始表单视图并复制发起成员字段。
     *
     * @param definitionId String，流程定义主键。
     * @param deploymentId String，部署主键。
     * @param processInstanceId String，详情场景的实例主键，首次发起时为空。
     * @param sourceType String，TEMPLATE 或 EMBEDDED。
     * @param formId Long，快照来源表单主键。
     * @param formKey String，BPMN 表单键。
     * @param nodeKey String，BPMN 开始节点主键。
     * @param formName String，部署时表单名称。
     * @param nodeName String，部署时节点名称。
     * @param content String，部署时固化的表单 JSON。
     * @param snapshotTime Instant，部署快照创建时间。
     * @param startMultiInstanceAssignments List，发起页必须填写的会签或或签成员字段。
     * @return 无返回值；空字段列表按空集合处理。
     */
    public WorkflowProcessFormView
    {
        startMultiInstanceAssignments = startMultiInstanceAssignments == null
                ? List.of() : List.copyOf(startMultiInstanceAssignments);
    }

    /**
     * 兼容既有正式模板视图构造调用。
     *
     * @param definitionId String，流程定义主键
     * @param deploymentId String，部署主键
     * @param processInstanceId String，可为空的实例主键
     * @param formId Long，正式表单主键
     * @param formKey String，BPMN 表单键
     * @param nodeKey String，开始节点主键
     * @param formName String，表单名称
     * @param nodeName String，节点名称
     * @param content String，部署快照 JSON
     * @param snapshotTime Instant，快照时间
     * @return 无返回值，构造 TEMPLATE 来源视图
     */
    public WorkflowProcessFormView(String definitionId, String deploymentId,
            String processInstanceId, Long formId, String formKey, String nodeKey,
            String formName, String nodeName, String content, Instant snapshotTime)
    {
        this(definitionId, deploymentId, processInstanceId, "TEMPLATE", formId,
                formKey, nodeKey, formName, nodeName, content, snapshotTime, List.of());
    }

    /**
     * 兼容未投影发起成员字段的既有完整来源构造调用。
     *
     * @param definitionId String，流程定义主键
     * @param deploymentId String，部署主键
     * @param processInstanceId String，可为空的实例主键
     * @param sourceType String，TEMPLATE 或 EMBEDDED
     * @param formId Long，正式表单主键
     * @param formKey String，BPMN 表单键
     * @param nodeKey String，开始节点主键
     * @param formName String，表单名称
     * @param nodeName String，节点名称
     * @param content String，部署快照 JSON
     * @param snapshotTime Instant，快照时间
     * @return 无返回值，发起成员字段使用空集合
     */
    public WorkflowProcessFormView(String definitionId, String deploymentId,
            String processInstanceId, String sourceType, Long formId, String formKey,
            String nodeKey, String formName, String nodeName, String content,
            Instant snapshotTime)
    {
        this(definitionId, deploymentId, processInstanceId, sourceType, formId,
                formKey, nodeKey, formName, nodeName, content, snapshotTime, List.of());
    }
}
