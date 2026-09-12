package com.ruoyi.flowable.service.model;

import java.util.List;
import com.ruoyi.common.constant.HttpStatus;
import com.ruoyi.common.exception.ServiceException;
import com.ruoyi.flowable.domain.vo.WorkflowBpmnValidationIssue;

/** 保留安全的逐元素校验诊断，供预检查、保存和部署共用。 */
public final class WorkflowBpmnValidationException extends RuntimeException
{
    private final List<WorkflowBpmnValidationIssue> issues;

    public WorkflowBpmnValidationException(List<WorkflowBpmnValidationIssue> issues)
    {
        super(issues.get(0).message());
        this.issues = List.copyOf(issues);
    }

    public List<WorkflowBpmnValidationIssue> issues() { return issues; }

    public ServiceException asServiceException()
    {
        ServiceException exception = new ServiceException(getMessage(), HttpStatus.BAD_REQUEST)
                .setSubCode(issues.get(0).code());
        exception.initCause(this);
        return exception;
    }
}
