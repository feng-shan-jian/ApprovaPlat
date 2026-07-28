package com.ruoyi.flowable.domain.dto;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Positive;
import jakarta.validation.constraints.Size;

/**
 * 委派任务请求，操作人和流程实例均由服务端从登录态与任务状态解析。
 *
 * @param taskId String，待委派的 Flowable 活动任务主键
 * @param userId Long，目标受托人的若依用户主键
 * @param comment String，委派业务意见
 * @param copyUserIds List&lt;Long&gt;，可选抄送接收用户主键，最多 100 人
 */
public record WorkflowTaskDelegateRequest(
        @NotBlank(message = "任务主键不能为空")
        @Size(max = 64, message = "任务主键长度不能超过64个字符")
        String taskId,
        @NotNull(message = "目标用户不能为空")
        @Positive(message = "目标用户主键必须为正数")
        Long userId,
        @NotBlank(message = "委派意见不能为空")
        @Size(max = 500, message = "委派意见长度不能超过500个字符")
        String comment,
        @Size(max = 100, message = "抄送人数不能超过100人")
        List<@NotNull(message = "抄送用户不能为空") @Positive(message = "抄送用户主键必须为正数") Long> copyUserIds)
{
    /**
     * 创建委派任务请求并复制抄送用户集合，确保事务钩子使用不可变请求快照。
     *
     * @param taskId String，待委派的活动任务主键
     * @param userId Long，目标受托用户主键
     * @param comment String，委派业务意见
     * @param copyUserIds List&lt;Long&gt;，可选抄送接收用户主键
     * @return 无返回值，构造后 copyUserIds 为不可修改集合
     */
    public WorkflowTaskDelegateRequest
    {
        copyUserIds = copyUserIds == null ? List.of()
                : Collections.unmodifiableList(new ArrayList<>(copyUserIds));
    }

    /**
     * 兼容原有未提交抄送人的 Java 调用方。
     *
     * @param taskId String，待委派的活动任务主键
     * @param userId Long，目标受托用户主键
     * @param comment String，委派业务意见
     * @return 无返回值，抄送用户使用空集合
     */
    public WorkflowTaskDelegateRequest(String taskId, Long userId, String comment)
    {
        this(taskId, userId, comment, List.of());
    }
}
