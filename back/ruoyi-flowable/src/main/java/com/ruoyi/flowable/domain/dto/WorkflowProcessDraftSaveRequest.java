package com.ruoyi.flowable.domain.dto;

import java.util.Map;
import java.util.List;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.Size;

/**
 * 以乐观锁保存流程申请草稿请求。
 *
 * @param expectedVersion long，客户端最后读取的草稿版本
 * @param businessKey String，可为空的业务主键
 * @param variables Map&lt;String,Object&gt;，允许缺少正式必填项的草稿字段值
 * @param multiInstanceUserIds Map&lt;String,List&lt;Long&gt;&gt;，按活动保存的发起会签或或签成员
 */
public record WorkflowProcessDraftSaveRequest(
        @Min(value = 1, message = "草稿版本必须大于0") long expectedVersion,
        @Size(max = 255, message = "流程业务主键长度不能超过255个字符")
        String businessKey,
        Map<String, Object> variables,
        Map<String, List<Long>> multiInstanceUserIds)
{
}
