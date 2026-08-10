package com.ruoyi.flowable.service.model;

import java.util.List;
import java.util.Objects;

/**
 * DMN 编译后的 BPMN 资源与待冻结决策来源。
 * @param compiledBpmn byte[]，BusinessRuleTask 已转换为官方 DMN ServiceTask 的资源
 * @param sources List&lt;DecisionSource&gt;，每个业务规则任务选择的精确决策和资源
 */
public record WorkflowPreparedDmnDeployment(byte[] compiledBpmn, List<DecisionSource> sources)
{
    /**
     * 防御性复制编译资源和来源集合。
     * @param compiledBpmn byte[]，编译后的 BPMN
     * @param sources List&lt;DecisionSource&gt;，精确决策来源
     * @return void，构造后得到不可变准备结果
     */
    public WorkflowPreparedDmnDeployment
    {
        compiledBpmn = Objects.requireNonNull(compiledBpmn, "编译 BPMN 不能为空").clone();
        sources = List.copyOf(Objects.requireNonNull(sources, "DMN 来源不能为空"));
    }

    /** @return byte[]，编译 BPMN 的独立副本。 */
    @Override
    public byte[] compiledBpmn() { return compiledBpmn.clone(); }

    /**
     * 一个 BusinessRuleTask 的冻结输入。
     * @param processKey String，流程 key
     * @param elementId String，元素 id
     * @param sourceDecisionId String，设计选择的精确 decision id
     * @param decisionKey String，稳定决策 key
     * @param decisionVersion int，来源版本
     * @param sourceDeploymentId String，来源 DMN 部署
     * @param resourceName String，来源资源名
     * @param resourceChecksum String，来源 XML 摘要
     * @param resourceBytes byte[]，来源 XML 字节
     */
    public record DecisionSource(String processKey, String elementId,
            String sourceDecisionId, String decisionKey, int decisionVersion,
            String sourceDeploymentId, String resourceName, String resourceChecksum,
            byte[] resourceBytes)
    {
        /**
         * 防御性复制 DMN XML。
         * @param processKey String，流程 key
         * @param elementId String，元素 id
         * @param sourceDecisionId String，来源 decision id
         * @param decisionKey String，决策 key
         * @param decisionVersion int，来源版本
         * @param sourceDeploymentId String，来源部署
         * @param resourceName String，资源名
         * @param resourceChecksum String，资源摘要
         * @param resourceBytes byte[]，来源 XML
         * @return void，构造后保存独立副本
         */
        public DecisionSource
        {
            resourceBytes = Objects.requireNonNull(resourceBytes, "DMN XML 不能为空").clone();
        }

        /** @return byte[]，DMN XML 的独立副本。 */
        @Override
        public byte[] resourceBytes() { return resourceBytes.clone(); }

        /** @return String，跨元素复用同一来源资源的稳定分组键。 */
        public String resourceGroupKey() { return sourceDeploymentId + "\u0000" + resourceName; }
    }
}
