package com.ruoyi.flowable.service.model;

import java.util.List;
import com.ruoyi.flowable.domain.WfDeployConditionRule;

/**
 * 条件分支编译后的执行 BPMN 和待持久化快照。
 *
 * @param compiledBpmn byte[]，只包含固定路由表达式的可执行 BPMN
 * @param snapshots List&lt;WfDeployConditionRule&gt;，完整不可变条件快照
 */
public record WorkflowPreparedConditionDeployment(byte[] compiledBpmn,
        List<WfDeployConditionRule> snapshots)
{
    /**
     * 防御复制编译资源和快照集合。
     * @return 无返回值，构造结果不可被调用方原地修改
     */
    public WorkflowPreparedConditionDeployment
    {
        compiledBpmn = compiledBpmn == null ? null : compiledBpmn.clone();
        snapshots = snapshots == null ? List.of() : List.copyOf(snapshots);
    }

    /** @return byte[]，编译 BPMN 的副本。 */
    @Override
    public byte[] compiledBpmn()
    {
        return compiledBpmn == null ? null : compiledBpmn.clone();
    }
}
