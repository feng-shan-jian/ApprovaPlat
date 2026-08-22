package com.ruoyi.flowable.domain.dto;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Positive;
import jakarta.validation.constraints.Size;

/**
 * 驳回并终止当前流程的专用请求。
 *
 * @param taskId String，待驳回的活动任务主键
 * @param comment String，驳回原因
 * @param copyUserIds List&lt;Long&gt;，可选抄送接收用户主键，最多 100 人
 */
public record WorkflowTaskRejectRequest(
        @NotBlank(message = "任务主键不能为空")
        @Size(max = 64, message = "任务主键长度不能超过64个字符")
        String taskId,
        @NotBlank(message = "驳回原因不能为空")
        @Size(max = 500, message = "驳回原因长度不能超过500个字符")
        String comment,
        @Size(max = 100, message = "抄送人数不能超过100人")
        List<@NotNull(message = "抄送用户不能为空") @Positive(message = "抄送用户主键必须为正数") Long> copyUserIds)
{
    /**
     * 创建驳回任务请求并复制抄送用户集合，防止校验及执行期间被调用方修改。
     *
     * @param taskId String，待驳回的活动任务主键
     * @param comment String，驳回原因
     * @param copyUserIds List&lt;Long&gt;，可选抄送接收用户主键
     * @return 无返回值，构造后 copyUserIds 为不可修改集合
     */
    public WorkflowTaskRejectRequest
    {
        copyUserIds = copyUserIds == null ? List.of()
                : Collections.unmodifiableList(new ArrayList<>(copyUserIds));
    }

}
