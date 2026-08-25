package com.ruoyi.flowable.service.task;

import tools.jackson.databind.ObjectMapper;
import tools.jackson.databind.json.JsonMapper;
import org.springframework.stereotype.Component;
import org.springframework.util.StringUtils;
import com.ruoyi.common.constant.HttpStatus;
import com.ruoyi.common.exception.ServiceException;

/**
 * 普通退回办理配置的受控 JSON 编码与校验解码边界。
 */
@Component
public class WorkflowReturnedAssignmentCodec
{
    private static final ObjectMapper MAPPER = JsonMapper.shared();

    /**
     * 编码普通退回办理配置。
     *
     * @param assignment ReturnedAssignmentSnapshot，服务端冻结的原办理配置
     * @return String，可写入任务局部变量的受控 JSON
     */
    public String encode(ReturnedAssignmentSnapshot assignment)
    {
        try
        {
            return MAPPER.writeValueAsString(assignment);
        }
        catch (RuntimeException exception)
        {
            throw dataError(exception);
        }
    }

    /**
     * 解码并校验普通退回办理配置。
     *
     * @param encoded String，任务局部变量中的受控 JSON
     * @return ReturnedAssignmentSnapshot，字段完整的不可变办理配置
     */
    public ReturnedAssignmentSnapshot decode(String encoded)
    {
        try
        {
            ReturnedAssignmentSnapshot snapshot = MAPPER.readValue(
                    encoded, ReturnedAssignmentSnapshot.class);
            if (snapshot == null || (!StringUtils.hasText(snapshot.assignee())
                    && snapshot.candidateUserIds().isEmpty()
                    && snapshot.candidateGroupIds().isEmpty()))
            {
                throw conflict();
            }
            return snapshot;
        }
        catch (ServiceException exception)
        {
            throw exception;
        }
        catch (RuntimeException exception)
        {
            throw dataError(exception);
        }
    }

    /**
     * 创建稳定状态冲突错误。
     *
     * @return ServiceException，既有 HTTP 409 错误
     */
    private ServiceException conflict()
    {
        return new ServiceException("工作流状态已发生变化，请刷新后重试", HttpStatus.CONFLICT);
    }

    /**
     * 创建保留底层原因的稳定关联数据错误。
     *
     * @param cause Throwable，JSON 编解码原始异常
     * @return ServiceException，既有 HTTP 500 错误
     */
    private ServiceException dataError(Throwable cause)
    {
        ServiceException failure = new ServiceException(
                "工作流对象关联数据异常", HttpStatus.ERROR);
        failure.initCause(cause);
        return failure;
    }
}
