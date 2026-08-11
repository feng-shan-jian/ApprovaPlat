package com.ruoyi.flowable.service.model;

import java.util.List;
import com.ruoyi.flowable.domain.WfDeployCallActivitySnapshot;
import com.ruoyi.flowable.domain.WfDeployConditionRule;
import com.ruoyi.flowable.domain.WfDeployControlledLoop;
import com.ruoyi.flowable.domain.WfDeployDmnSnapshot;
import com.ruoyi.flowable.domain.WfDeployExtensionSnapshot;
import com.ruoyi.flowable.domain.WfDeployForm;
import com.ruoyi.flowable.domain.WfDeployParticipantRule;
import com.ruoyi.flowable.domain.WfDeployTaskSla;

/**
 * 汇总一个 Flowable 流程部署拥有的全部不可变业务资源。
 *
 * @param forms List&lt;WfDeployForm&gt;，节点表单不可变快照
 * @param conditionRules List&lt;WfDeployConditionRule&gt;，网关条件不可变快照
 * @param controlledLoops List&lt;WfDeployControlledLoop&gt;，受控循环不可变快照
 * @param participantRules List&lt;WfDeployParticipantRule&gt;，参与者规则不可变快照
 * @param extensionSnapshots List&lt;WfDeployExtensionSnapshot&gt;，受控扩展不可变快照
 * @param dmnSnapshots List&lt;WfDeployDmnSnapshot&gt;，冻结 DMN 依赖快照
 * @param callActivitySnapshots List&lt;WfDeployCallActivitySnapshot&gt;，调用活动依赖快照
 * @param taskSlaSnapshots List&lt;WfDeployTaskSla&gt;，审批 SLA 不可变快照
 */
public record WorkflowDeploymentArtifacts(
        List<WfDeployForm> forms,
        List<WfDeployConditionRule> conditionRules,
        List<WfDeployControlledLoop> controlledLoops,
        List<WfDeployParticipantRule> participantRules,
        List<WfDeployExtensionSnapshot> extensionSnapshots,
        List<WfDeployDmnSnapshot> dmnSnapshots,
        List<WfDeployCallActivitySnapshot> callActivitySnapshots,
        List<WfDeployTaskSla> taskSlaSnapshots)
{
    /**
     * 规范化全部集合，避免调用方在资源持久化过程中修改列表结构。
     *
     * @return 无返回值，构造完成后全部集合均为不可变副本
     */
    public WorkflowDeploymentArtifacts
    {
        forms = immutable(forms);
        conditionRules = immutable(conditionRules);
        controlledLoops = immutable(controlledLoops);
        participantRules = immutable(participantRules);
        extensionSnapshots = immutable(extensionSnapshots);
        dmnSnapshots = immutable(dmnSnapshots);
        callActivitySnapshots = immutable(callActivitySnapshots);
        taskSlaSnapshots = immutable(taskSlaSnapshots);
    }

    /**
     * 创建不包含任何扩展业务资源的部署集合。
     *
     * @return WorkflowDeploymentArtifacts，全部资源列表为空的不可变集合
     */
    public static WorkflowDeploymentArtifacts empty()
    {
        return new WorkflowDeploymentArtifacts(List.of(), List.of(), List.of(), List.of(),
                List.of(), List.of(), List.of(), List.of());
    }

    /**
     * 将允许为空的资源集合规范为不可变列表。
     *
     * @param values List&lt;T&gt;，原始资源集合
     * @return List&lt;T&gt;，不可为空且不可修改的资源集合
     */
    private static <T> List<T> immutable(List<T> values)
    {
        return values == null ? List.of() : List.copyOf(values);
    }
}
