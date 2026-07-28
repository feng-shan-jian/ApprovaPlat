package com.ruoyi.flowable.domain.dto;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Positive;
import jakarta.validation.constraints.Size;

/**
 * 将当前活动任务退回合法历史节点的专用请求。
 *
 * @param taskId String，待退回的活动任务主键
 * @param targetKey String，服务端可退节点列表中的 BPMN 节点主键
 * @param comment String，退回原因
 * @param copyUserIds List&lt;Long&gt;，可选抄送接收用户主键，最多 100 人
 */
public record WorkflowTaskReturnRequest(
        @NotBlank(message = "任务主键不能为空")
        @Size(max = 64, message = "任务主键长度不能超过64个字符")
        String taskId,
        @NotBlank(message = "退回节点不能为空")
        @Size(max = 255, message = "退回节点长度不能超过255个字符")
        String targetKey,
        @NotBlank(message = "退回原因不能为空")
        @Size(max = 500, message = "退回原因长度不能超过500个字符")
        String comment,
        @Size(max = 100, message = "抄送人数不能超过100人")
        List<@NotNull(message = "抄送用户不能为空") @Positive(message = "抄送用户主键必须为正数") Long> copyUserIds)
{
    /**
     * 创建退回任务请求并复制抄送用户集合，防止状态校验期间集合内容被修改。
     *
     * @param taskId String，待退回的活动任务主键
     * @param targetKey String，服务端确认过的 BPMN 目标节点主键
     * @param comment String，退回原因
     * @param copyUserIds List&lt;Long&gt;，可选抄送接收用户主键
     * @return 无返回值，构造后 copyUserIds 为不可修改集合
     */
    public WorkflowTaskReturnRequest
    {
        copyUserIds = copyUserIds == null ? List.of()
                : Collections.unmodifiableList(new ArrayList<>(copyUserIds));
    }

    /**
     * 兼容原有未提交抄送人的 Java 调用方。
     *
     * @param taskId String，待退回的活动任务主键
     * @param targetKey String，服务端确认过的 BPMN 目标节点主键
     * @param comment String，退回原因
     * @return 无返回值，抄送用户使用空集合
     */
    public WorkflowTaskReturnRequest(String taskId, String targetKey, String comment)
    {
        this(taskId, targetKey, comment, List.of());
    }
}
