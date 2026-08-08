package com.ruoyi.flowable.domain.dto;

import java.util.Map;
import java.util.List;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.Size;

/**
 * 正式提交流程申请草稿请求。
 *
 * @param expectedVersion long，客户端最后读取的草稿版本
 * @param businessKey String，本次提交采用的可选业务主键
 * @param variables Map&lt;String,Object&gt;，本次提交采用的完整字段值
 * @param multiInstanceUserIds Map&lt;String,List&lt;Long&gt;&gt;，本次提交采用的发起会签或或签成员
 */
public record WorkflowProcessDraftSubmitRequest(
        @Min(value = 1, message = "草稿版本必须大于0") long expectedVersion,
        @Size(max = 255, message = "流程业务主键长度不能超过255个字符")
        String businessKey,
        Map<String, Object> variables,
        Map<String, List<Long>> multiInstanceUserIds)
{
}
