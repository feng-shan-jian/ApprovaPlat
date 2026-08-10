package com.ruoyi.flowable.domain.dto;

import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Positive;
import jakarta.validation.constraints.PositiveOrZero;
import jakarta.validation.constraints.Size;

/**
 * 完成当前任务的专用请求。
 *
 * @param taskId String，待完成的活动任务主键
 * @param comment String，审批意见
 * @param variables Map&lt;String, Object&gt;，当前部署任务表单声明的字段值
 * @param copyUserIds List&lt;Long&gt;，可选抄送接收用户主键，最多 100 人
 * @param nextUserIds List&lt;Long&gt;，可选动态下一办理人主键，仅支持唯一直接后继用户任务
 * @param expectedRevision Long，动态多实例当前服务端 revision；普通任务必须为空
 */
public record WorkflowTaskCompleteRequest(
        @NotBlank(message = "任务主键不能为空")
        @Size(max = 64, message = "任务主键长度不能超过64个字符")
        String taskId,
        @NotBlank(message = "审批意见不能为空")
        @Size(max = 500, message = "审批意见长度不能超过500个字符")
        String comment,
        Map<String, Object> variables,
        @Size(max = 100, message = "抄送人数不能超过100人")
        List<@NotNull(message = "抄送用户不能为空") @Positive(message = "抄送用户主键必须为正数") Long> copyUserIds,
        @Size(max = 100, message = "下一办理人数不能超过100人")
        List<@NotNull(message = "下一办理人不能为空") @Positive(message = "下一办理人主键必须为正数") Long> nextUserIds,
        @PositiveOrZero(message = "动态多实例版本不能小于0")
        @Max(value = Integer.MAX_VALUE, message = "动态多实例版本不能超过2147483647")
        Long expectedRevision)
{
    /**
     * 创建完成任务请求并复制顶层变量映射，防止校验期间被调用方增删字段。
     *
     * @param taskId String，待完成的活动任务主键
     * @param comment String，审批意见
     * @param variables Map&lt;String, Object&gt;，当前任务表单变量，允许为空
     * @param copyUserIds List&lt;Long&gt;，可选抄送用户主键
     * @param nextUserIds List&lt;Long&gt;，可选动态下一办理人主键
     * @param expectedRevision Long，动态多实例当前服务端 revision；普通任务为空
     * @return 无返回值，构造后变量和用户集合均为不可修改副本
     */
    public WorkflowTaskCompleteRequest
    {
        variables = variables == null
                ? Map.of()
                : Collections.unmodifiableMap(new LinkedHashMap<>(variables));
        copyUserIds = immutableList(copyUserIds);
        nextUserIds = immutableList(nextUserIds);
    }

    /**
     * 兼容原有仅提交任务、意见和变量的 Java 调用方。
     *
     * @param taskId String，待完成的活动任务主键
     * @param comment String，审批意见
     * @param variables Map&lt;String, Object&gt;，当前任务表单变量
     * @return 无返回值，抄送人与动态下一办理人均使用空集合
     */
    public WorkflowTaskCompleteRequest(String taskId, String comment,
            Map<String, Object> variables)
    {
        this(taskId, comment, variables, List.of(), List.of(), null);
    }

    /**
     * 兼容已接入抄送但未指定动态下一办理人的 Java 调用方。
     *
     * @param taskId String，待完成的活动任务主键
     * @param comment String，审批意见
     * @param variables Map&lt;String, Object&gt;，当前任务表单变量
     * @param copyUserIds List&lt;Long&gt;，可选抄送接收用户主键
     * @return 无返回值，动态下一办理人使用空集合
     */
    public WorkflowTaskCompleteRequest(String taskId, String comment,
            Map<String, Object> variables, List<Long> copyUserIds)
    {
        this(taskId, comment, variables, copyUserIds, List.of(), null);
    }

    /**
     * 兼容已接入抄送和动态下一办理人、但当前任务不是动态多实例的 Java 调用方。
     *
     * @param taskId String，待完成的活动任务主键
     * @param comment String，审批意见
     * @param variables Map&lt;String, Object&gt;，当前任务表单变量
     * @param copyUserIds List&lt;Long&gt;，可选抄送接收用户主键
     * @param nextUserIds List&lt;Long&gt;，可选动态下一办理人主键
     * @return 无返回值，动态多实例 expectedRevision 使用 null
     */
    public WorkflowTaskCompleteRequest(String taskId, String comment,
            Map<String, Object> variables, List<Long> copyUserIds,
            List<Long> nextUserIds)
    {
        this(taskId, comment, variables, copyUserIds, nextUserIds, null);
    }

    /**
     * 复制可选用户主键集合，同时保留 null 元素交给 Bean Validation 返回稳定的 400 响应。
     *
     * @param values List&lt;Long&gt;，客户端提交的可选用户主键集合
     * @return List&lt;Long&gt;，不可修改的集合副本；null 输入转换为空集合
     */
    private static List<Long> immutableList(List<Long> values)
    {
        return values == null ? List.of()
                : Collections.unmodifiableList(new ArrayList<>(values));
    }
}
