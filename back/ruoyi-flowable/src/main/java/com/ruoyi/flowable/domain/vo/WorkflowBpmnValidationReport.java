package com.ruoyi.flowable.domain.vo;

import java.util.List;

/**
 * BPMN 服务端编译校验结果。
 *
 * @param valid boolean，true 表示已通过作者 XML 保存安全门禁
 * @param issues List&lt;WorkflowBpmnValidationIssue&gt;，部署警告或保存错误的不可变诊断列表
 */
public record WorkflowBpmnValidationReport(boolean valid,
        List<WorkflowBpmnValidationIssue> issues)
{
    /**
     * 固化诊断列表，避免 Controller 返回后被调用方修改。
     * @param valid boolean，校验是否通过
     * @param issues List&lt;WorkflowBpmnValidationIssue&gt;，诊断列表
     * @return 无返回值，构造后得到不可变报告
     */
    public WorkflowBpmnValidationReport
    {
        issues = issues == null ? List.of() : List.copyOf(issues);
    }
}
