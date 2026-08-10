package com.ruoyi.flowable.domain.vo;

import java.time.Instant;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Objects;
import tools.jackson.databind.JsonNode;

/**
 * 已执行节点的不可变部署表单快照及字段白名单值视图。
 *
 * @param activityId String，本次执行对应的 BPMN 活动主键
 * @param taskId String，用户任务主键，开始节点为空
 * @param sourceType String，TEMPLATE 或 EMBEDDED
 * @param formId Long，快照来源表单主键
 * @param formKey String，BPMN 表单键
 * @param nodeKey String，快照节点主键
 * @param formName String，部署时表单名称
 * @param nodeName String，部署时节点名称
 * @param content String，部署时固化的表单 JSON
 * @param taskLocal boolean，字段值是否来自该任务的局部变量
 * @param values Map&lt;String, JsonNode&gt;，仅包含表单 schema 声明字段的安全 JSON 值
 * @param snapshotTime Instant，快照创建时间，允许为空
 */
public record WorkflowProcessFormSnapshotView(
        String activityId,
        String taskId,
        String sourceType,
        Long formId,
        String formKey,
        String nodeKey,
        String formName,
        String nodeName,
        String content,
        boolean taskLocal,
        Map<String, JsonNode> values,
        Instant snapshotTime)
{
    /**
     * 兼容既有正式模板表单快照构造调用。
     *
     * @param activityId String，BPMN 活动主键
     * @param taskId String，可为空的任务主键
     * @param formId Long，正式表单主键
     * @param formKey String，BPMN 表单键
     * @param nodeKey String，节点主键
     * @param formName String，表单名称
     * @param nodeName String，节点名称
     * @param content String，部署快照 JSON
     * @param taskLocal boolean，是否使用任务局部变量
     * @param values Map&lt;String, JsonNode&gt;，安全字段值
     * @param snapshotTime Instant，可为空的提交时间
     * @return 无返回值，构造 TEMPLATE 来源快照视图
     */
    public WorkflowProcessFormSnapshotView(String activityId, String taskId, Long formId,
            String formKey, String nodeKey, String formName, String nodeName, String content,
            boolean taskLocal, Map<String, JsonNode> values, Instant snapshotTime)
    {
        this(activityId, taskId, "TEMPLATE", formId, formKey, nodeKey, formName,
                nodeName, content, taskLocal, values, snapshotTime);
    }

    /**
     * 创建表单快照视图并深复制字段值节点。
     *
     * @param activityId String，本次执行对应的 BPMN 活动主键
     * @param taskId String，用户任务主键，开始节点为空
     * @param sourceType String，TEMPLATE 或 EMBEDDED
     * @param formId Long，快照来源表单主键
     * @param formKey String，BPMN 表单键
     * @param nodeKey String，快照节点主键
     * @param formName String，部署时表单名称
     * @param nodeName String，部署时节点名称
     * @param content String，部署时固化的表单 JSON
     * @param taskLocal boolean，字段值是否来自该任务的局部变量
     * @param values Map&lt;String, JsonNode&gt;，已经过类型、深度和大小门禁的字段值
     * @param snapshotTime Instant，快照创建时间，允许为空
     * @return 无返回值，构造后字段值不可由调用方替换
     */
    public WorkflowProcessFormSnapshotView
    {
        Objects.requireNonNull(values, "表单字段值不能为空");
        Map<String, JsonNode> copiedValues = new LinkedHashMap<>();
        values.forEach((key, value) -> copiedValues.put(key,
                value == null ? null : value.deepCopy()));
        values = Collections.unmodifiableMap(copiedValues);
    }
}
