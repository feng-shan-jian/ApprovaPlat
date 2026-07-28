package com.ruoyi.flowable.domain.dto;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Positive;
import jakarta.validation.constraints.Size;

/**
 * 完成委派任务请求，办理人和流程实例均由服务端从登录态与任务状态解析。
 *
 * @param taskId String，处于 PENDING 委派状态的 Flowable 活动任务主键
 * @param comment String，受托人的真实办理意见
 * @param copyUserIds List&lt;Long&gt;，可选抄送接收用户主键，最多 100 人
 */
public record WorkflowTaskResolveRequest(
        @NotBlank(message = "任务主键不能为空")
        @Size(max = 64, message = "任务主键长度不能超过64个字符")
        String taskId,
        @NotBlank(message = "委派办结意见不能为空")
        @Size(max = 500, message = "委派办结意见长度不能超过500个字符")
        String comment,
        @Size(max = 100, message = "抄送人数不能超过100人")
        List<@NotNull(message = "抄送用户不能为空") @Positive(message = "抄送用户主键必须为正数") Long> copyUserIds)
{
    /**
     * 创建委派办结请求并复制抄送用户集合，确保事务钩子使用不可变请求快照。
     *
     * @param taskId String，待办结委派的活动任务主键
     * @param comment String，受托人的真实办理意见
     * @param copyUserIds List&lt;Long&gt;，可选抄送接收用户主键
     * @return 无返回值，构造后 copyUserIds 为不可修改集合
     */
    public WorkflowTaskResolveRequest
    {
        copyUserIds = copyUserIds == null ? List.of()
                : Collections.unmodifiableList(new ArrayList<>(copyUserIds));
    }
}
