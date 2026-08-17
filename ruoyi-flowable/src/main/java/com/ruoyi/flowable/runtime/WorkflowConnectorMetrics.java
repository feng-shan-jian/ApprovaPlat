package com.ruoyi.flowable.runtime;

import java.time.Duration;
import java.util.Map;
import io.micrometer.core.instrument.Counter;
import io.micrometer.core.instrument.MeterRegistry;
import io.micrometer.core.instrument.Timer;
import org.springframework.stereotype.Component;

/**
 * HTTP/SQL 连接器单次 Flowable Job 尝试的低基数指标。
 */
@Component
public class WorkflowConnectorMetrics
{
    private static final String HTTP = "http";
    private static final String SQL = "sql";
    private static final String SUCCESS = "success";
    private static final String FAILURE = "failure";

    private final Map<String, Counter> counters;
    private final Map<String, Timer> timers;

    /**
     * 注册固定连接器类型和结果标签，禁止实例、端点、SQL 或错误正文形成高基数标签。
     * @param registry MeterRegistry，应用统一 Micrometer 注册表
     * @return void，构造后可直接记录连接器尝试
     */
    public WorkflowConnectorMetrics(MeterRegistry registry)
    {
        counters = Map.of(
                key(HTTP, SUCCESS), counter(registry, HTTP, SUCCESS),
                key(HTTP, FAILURE), counter(registry, HTTP, FAILURE),
                key(SQL, SUCCESS), counter(registry, SQL, SUCCESS),
                key(SQL, FAILURE), counter(registry, SQL, FAILURE));
        timers = Map.of(
                key(HTTP, SUCCESS), timer(registry, HTTP, SUCCESS),
                key(HTTP, FAILURE), timer(registry, HTTP, FAILURE),
                key(SQL, SUCCESS), timer(registry, SQL, SUCCESS),
                key(SQL, FAILURE), timer(registry, SQL, FAILURE));
    }

    /**
     * 记录一次连接器尝试及耗时，失败重试由 Flowable Job 自身再次调用本方法累计。
     * @param connectorType String，只允许 HTTP 或 SQL
     * @param success boolean，本次尝试是否成功完成
     * @param durationMs long，本次尝试非负耗时毫秒
     * @return void，未知类型视为服务端编程错误
     */
    public void record(String connectorType, boolean success, long durationMs)
    {
        String type = normalizeType(connectorType);
        String result = success ? SUCCESS : FAILURE;
        String key = key(type, result);
        counters.get(key).increment();
        timers.get(key).record(Duration.ofMillis(Math.max(0L, durationMs)));
    }

    /**
     * 规范连接器类型，保持指标标签集合固定。
     * @param connectorType String，业务连接器类型
     * @return String，小写固定标签值
     */
    private String normalizeType(String connectorType)
    {
        if (connectorType == null)
        {
            throw new IllegalArgumentException("连接器类型不能为空");
        }
        return switch (connectorType.toUpperCase(java.util.Locale.ROOT))
        {
            case "HTTP" -> HTTP;
            case "SQL" -> SQL;
            default -> throw new IllegalArgumentException("未知连接器类型");
        };
    }

    /**
     * 创建连接器结果计数器。
     * @param registry MeterRegistry，统一注册表
     * @param type String，固定连接器类型
     * @param result String，固定执行结果
     * @return Counter，低基数计数器
     */
    private Counter counter(MeterRegistry registry, String type, String result)
    {
        return Counter.builder("workflow.connector.attempts")
                .description("Flowable connector job attempts")
                .tag("type", type)
                .tag("result", result)
                .register(registry);
    }

    /**
     * 创建连接器耗时计时器并发布延迟分位直方图。
     * @param registry MeterRegistry，统一注册表
     * @param type String，固定连接器类型
     * @param result String，固定执行结果
     * @return Timer，连接器单次尝试耗时计时器
     */
    private Timer timer(MeterRegistry registry, String type, String result)
    {
        return Timer.builder("workflow.connector.duration")
                .description("Flowable connector job attempt duration")
                .tag("type", type)
                .tag("result", result)
                .publishPercentileHistogram()
                .register(registry);
    }

    /**
     * 生成固定类型与结果组合键。
     * @param type String，连接器类型
     * @param result String，执行结果
     * @return String，内部 Map 键
     */
    private String key(String type, String result)
    {
        return type + ':' + result;
    }
}
