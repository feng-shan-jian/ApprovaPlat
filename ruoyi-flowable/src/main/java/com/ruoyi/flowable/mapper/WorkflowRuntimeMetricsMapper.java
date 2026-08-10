package com.ruoyi.flowable.mapper;

import com.ruoyi.flowable.runtime.WorkflowRuntimeMetricValues;

/**
 * 工作流生产监控的只读合并查询，固定周期执行且不直接服务 Prometheus 抓取请求。
 */
public interface WorkflowRuntimeMetricsMapper
{
    /**
     * 以单次数据库往返聚合运行实例、任务、六类 job 和附件容量状态。
     *
     * @return WorkflowRuntimeMetricValues，全部字段非负的当前数据库快照
     */
    WorkflowRuntimeMetricValues selectRuntimeMetricValues();
}
