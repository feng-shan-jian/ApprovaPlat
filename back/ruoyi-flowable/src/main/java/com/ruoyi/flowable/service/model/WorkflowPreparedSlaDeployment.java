package com.ruoyi.flowable.service.model;

import java.util.List;
import java.util.Objects;
import com.ruoyi.flowable.domain.WfDeployTaskSla;

/**
 * 审批 SLA 部署编译结果。
 *
 * @param compiledBpmn byte[]，包含真实提醒和升级边界定时器的可执行 BPMN
 * @param snapshots List&lt;WfDeployTaskSla&gt;，尚未绑定部署主键的不可变 SLA 快照
 */
public record WorkflowPreparedSlaDeployment(byte[] compiledBpmn,
        List<WfDeployTaskSla> snapshots)
{
    /**
     * 创建不可变部署准备结果。
     * @param compiledBpmn byte[]，可执行 BPMN 字节
     * @param snapshots List&lt;WfDeployTaskSla&gt;，SLA 快照
     * @return 无返回值，数组和集合均复制后保存
     */
    public WorkflowPreparedSlaDeployment
    {
        compiledBpmn = Objects.requireNonNull(compiledBpmn, "SLA 编译 BPMN 不能为空").clone();
        snapshots = List.copyOf(Objects.requireNonNull(snapshots, "SLA 快照不能为空"));
    }

    /** @return byte[]，防止调用方修改内部编译资源的副本。 */
    @Override
    public byte[] compiledBpmn()
    {
        return compiledBpmn.clone();
    }
}
