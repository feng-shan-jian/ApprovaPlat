package com.ruoyi.flowable.service.process;

import java.time.Instant;
import java.util.Locale;
import java.util.Set;
import org.springframework.util.StringUtils;

/**
 * 将 Flowable 历史实例的引擎状态和服务端业务状态归一为稳定 API 状态。
 */
final class WorkflowProcessStatusNormalizer
{
    /** 由服务端动作明确写入且优先级高于引擎完成状态的终态。 */
    private static final Set<String> BUSINESS_TERMINAL_STATES = Set.of(
            "canceled", "rejected", "terminated");

    /** 对外接口允许直接回显的稳定状态集合。 */
    private static final Set<String> STABLE_STATES = Set.of(
            "running", "returned", "suspended", "completed", "canceled", "rejected", "terminated");

    /**
     * 禁止实例化状态规范器，所有状态转换均通过无状态静态方法完成。
     *
     * <p>该私有构造函数无入参、无返回值。</p>
     */
    private WorkflowProcessStatusNormalizer()
    {
    }

    /**
     * 按业务终态、引擎完成、活动引擎状态、其他稳定状态和历史兜底的顺序解析流程状态。
     *
     * @param businessStatus String，服务端通过 Flowable businessStatus 写入的业务状态，允许为空
     * @param engineState String，Flowable HistoricProcessInstance.state，允许为空
     * @param endTime Instant，流程结束时间，运行实例为空
     * @param deleteReason String，历史删除或终止原因，允许为空
     * @return String，running、returned、suspended、completed、canceled、rejected、terminated 或兼容的小写引擎状态
     */
    static String normalize(String businessStatus, String engineState,
            Instant endTime, String deleteReason)
    {
        String normalizedBusinessStatus = normalizeText(businessStatus);
        String normalizedEngineState = normalizeText(engineState);

        // 取消、驳回和终止均可能让引擎呈现 completed/terminated，必须以服务端业务终态为准。
        if (normalizedBusinessStatus != null
                && BUSINESS_TERMINAL_STATES.contains(normalizedBusinessStatus))
        {
            return normalizedBusinessStatus;
        }
        // 重提会把 businessStatus 恢复为 running；流程结束后引擎终态和结束时间必须覆盖该活动态快照。
        if ("completed".equals(normalizedEngineState) && endTime != null)
        {
            return "completed";
        }
        // returned 是仍有活动任务的业务暂停态，必须优先于引擎仍报告的 running。
        if ("returned".equals(normalizedBusinessStatus))
        {
            return normalizedBusinessStatus;
        }
        // 活动实例是否挂起只能由引擎实时状态决定，不能被可能滞后的业务状态覆盖。
        if ("running".equals(normalizedEngineState)
                || "suspended".equals(normalizedEngineState))
        {
            return normalizedEngineState;
        }
        if (normalizedBusinessStatus != null && STABLE_STATES.contains(normalizedBusinessStatus))
        {
            return normalizedBusinessStatus;
        }
        if (StringUtils.hasText(normalizedEngineState))
        {
            return normalizedEngineState;
        }
        if (endTime == null)
        {
            return "running";
        }
        return StringUtils.hasText(deleteReason) ? "canceled" : "completed";
    }

    /**
     * 规范 Flowable 状态文本并统一英式 cancelled 拼写。
     *
     * @param value String，可为空的业务状态或引擎状态
     * @return String，去除空白的小写状态；空值返回 null
     */
    private static String normalizeText(String value)
    {
        if (!StringUtils.hasText(value))
        {
            return null;
        }
        String normalized = value.trim().toLowerCase(Locale.ROOT);
        return "cancelled".equals(normalized) ? "canceled" : normalized;
    }
}
