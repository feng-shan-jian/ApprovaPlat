package com.ruoyi.flowable.domain.vo;

import java.util.List;

/** 设计中的节点和流程可用字段；资格由保存、部署共用的冻结表单契约决定。 */
public record WorkflowAuthorFieldCatalogView(List<Node> nodes, List<Process> processes, List<Issue> issues)
{
    public record Option(String value, String label) { }

    public record Scalar(String value, String label, String type,
            List<Option> values, boolean valueRestricted) { }

    public record Node(String processKey, String nodeKey, List<Option> userIdFields,
            List<Scalar> loopFields) { }

    public record Process(String processKey, List<Option> userIdFields,
            List<Scalar> conditionFields, List<WorkflowCallActivityVariableView> callFields) { }

    public record Issue(String processKey, String nodeKey, String message) { }
}
