package com.ruoyi.flowable.service.model;

import java.util.List;
import java.util.Objects;
import org.flowable.bpmn.model.BpmnModel;

/**
 * 已通过安全和业务规则校验的 BPMN 文档。
 *
 * @param bpmnModel BpmnModel，Flowable 8 公共 BPMN 模型
 * @param bpmnXml String，严格 UTF-8 解码后的 BPMN XML
 * @param formReferences List&lt;WorkflowBpmnFormReference&gt;，开始节点和用户任务表单引用
 */
public record WorkflowBpmnDocument(BpmnModel bpmnModel, String bpmnXml,
        List<WorkflowBpmnFormReference> formReferences)
{
    /**
     * 创建已校验 BPMN 文档并复制表单引用集合。
     *
     * @param bpmnModel BpmnModel，Flowable 8 公共 BPMN 模型
     * @param bpmnXml String，严格 UTF-8 解码后的 BPMN XML
     * @param formReferences List&lt;WorkflowBpmnFormReference&gt;，节点表单引用
     * @return 无返回值，构造后得到受控 BPMN 文档
     */
    public WorkflowBpmnDocument
    {
        Objects.requireNonNull(bpmnModel, "BPMN 模型不能为空");
        Objects.requireNonNull(bpmnXml, "BPMN XML 不能为空");
        formReferences = List.copyOf(Objects.requireNonNull(formReferences, "表单引用不能为空"));
    }
}
