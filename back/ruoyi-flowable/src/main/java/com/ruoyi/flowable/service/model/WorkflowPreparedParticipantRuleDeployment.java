package com.ruoyi.flowable.service.model;

import java.util.Arrays;
import java.util.List;
import com.ruoyi.flowable.domain.WfDeployParticipantRule;

/**
 * 参与者规则编译后的可执行 BPMN 与待绑定部署主键的快照。
 * @param compiledBpmn byte[]，已移除作者规则属性的执行资源
 * @param snapshots List&lt;WfDeployParticipantRule&gt;，待持久化规则快照
 */
public record WorkflowPreparedParticipantRuleDeployment(byte[] compiledBpmn,
        List<WfDeployParticipantRule> snapshots)
{
    public WorkflowPreparedParticipantRuleDeployment
    {
        compiledBpmn = Arrays.copyOf(compiledBpmn, compiledBpmn.length);
        snapshots = List.copyOf(snapshots);
    }

    /** @return byte[]，可安全交给下一编译阶段的独立副本。 */
    @Override
    public byte[] compiledBpmn() { return Arrays.copyOf(compiledBpmn, compiledBpmn.length); }
}
