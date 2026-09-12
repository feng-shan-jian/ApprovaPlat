package com.ruoyi.flowable.domain.vo;
import java.util.List;

/** 检查摘要仅用于识别过期结果，不是部署授权票据。 */
public record WorkflowModelPreflightView(String authorChecksum, int modelRevision, String compiledResourceChecksum,
        boolean valid, List<WorkflowBpmnValidationIssue> issues)
{
    public WorkflowModelPreflightView { issues = List.copyOf(issues); }
}
