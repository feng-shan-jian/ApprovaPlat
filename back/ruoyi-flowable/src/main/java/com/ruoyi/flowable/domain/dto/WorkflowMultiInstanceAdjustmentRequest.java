package com.ruoyi.flowable.domain.dto;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Positive;
import jakarta.validation.constraints.PositiveOrZero;
import jakarta.validation.constraints.Size;

/**
 * 动态多实例调整请求，操作人、活动节点、流程实例和目标 execution 均由服务端解析。
 *
 * @param taskId String，当前登录用户真实办理的活动多实例任务主键
 * @param action WorkflowMultiInstanceAdjustmentAction，固定 ADD 或 REMOVE
 * @param expectedRevision Long，客户端最后读取的服务端调整版本
 * @param comment String，必须持久化到 Flowable comment 的业务意见
 * @param userIds List&lt;Long&gt;，ADD 时待加入的正式用户主键，最多 100 人
 * @param targetTaskId String，REMOVE 时同一多实例根下的目标 sibling 任务主键
 */
public record WorkflowMultiInstanceAdjustmentRequest(
        @NotBlank(message = "任务主键不能为空")
        @Size(max = 64, message = "任务主键长度不能超过64个字符")
        String taskId,
        @NotNull(message = "调整动作不能为空")
        WorkflowMultiInstanceAdjustmentAction action,
        @NotNull(message = "调整版本不能为空")
        @PositiveOrZero(message = "调整版本不能为负数")
        Long expectedRevision,
        @NotBlank(message = "调整意见不能为空")
        @Size(max = 500, message = "调整意见长度不能超过500个字符")
        String comment,
        @Size(max = 100, message = "加签人数不能超过100人")
        List<@NotNull(message = "加签用户不能为空") @Positive(message = "加签用户主键必须为正数") Long> userIds,
        @Size(max = 64, message = "目标任务主键长度不能超过64个字符")
        String targetTaskId)
{
    /**
     * 创建调整请求并复制用户集合，防止事务执行期间请求内容被调用方修改。
     *
     * @param taskId String，当前活动任务主键
     * @param action WorkflowMultiInstanceAdjustmentAction，ADD 或 REMOVE
     * @param expectedRevision Long，预期服务端 revision
     * @param comment String，受控业务意见
     * @param userIds List&lt;Long&gt;，ADD 目标用户集合
     * @param targetTaskId String，REMOVE 目标任务主键
     * @return 无返回值，构造后 userIds 为不可修改集合
     */
    public WorkflowMultiInstanceAdjustmentRequest
    {
        userIds = userIds == null ? List.of()
                : Collections.unmodifiableList(new ArrayList<>(userIds));
    }
}
