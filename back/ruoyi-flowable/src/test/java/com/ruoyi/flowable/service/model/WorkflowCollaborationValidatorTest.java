package com.ruoyi.flowable.service.model;

import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.util.List;
import org.flowable.bpmn.model.BpmnModel;
import org.flowable.bpmn.model.MessageFlow;
import org.flowable.bpmn.model.Pool;
import org.flowable.bpmn.model.Process;
import org.flowable.bpmn.model.ReceiveTask;
import org.flowable.bpmn.model.SendTask;
import org.flowable.bpmn.model.FieldExtension;
import org.junit.jupiter.api.Test;
import com.ruoyi.flowable.extension.WorkflowCollaborationOutboxHandler;
import com.ruoyi.flowable.extension.WorkflowExtensionBpmnContract;

/** 协作图部署门禁的纯模型契约测试。 */
class WorkflowCollaborationValidatorTest
{
    @Test
    void acceptsTwoExecutablePoolsAndCrossProcessMessageFlow()
    {
        BpmnModel model = validModel();
        assertThatCode(() -> WorkflowCollaborationValidator.validate(model)).doesNotThrowAnyException();
    }

    @Test
    void rejectsMessageFlowWithoutRealMessageWaitingTarget()
    {
        BpmnModel model = validModel();
        MessageFlow flow = model.getMessageFlow("flow-1");
        flow.setTargetRef("missing");
        assertThatThrownBy(() -> WorkflowCollaborationValidator.validate(model))
                .hasMessageContaining("真实节点");
    }

    private BpmnModel validModel()
    {
        BpmnModel model = new BpmnModel();
        Process source = new Process();
        source.setId("source");
        source.setExecutable(true);
        SendTask send = new SendTask();
        send.setId("send");
        FieldExtension extensionKey = new FieldExtension();
        extensionKey.setFieldName(WorkflowExtensionBpmnContract.EXTENSION_KEY_FIELD);
        extensionKey.setStringValue(WorkflowCollaborationOutboxHandler.EXTENSION_KEY);
        FieldExtension extensionConfig = new FieldExtension();
        extensionConfig.setFieldName(WorkflowExtensionBpmnContract.EXTENSION_CONFIG_FIELD);
        extensionConfig.setStringValue("{\"endpointKey\":\"partner\",\"path\":\"/workflow/runtime-event/collaboration/message\",\"messageName\":\"approval.requested\",\"targetProcessDefinitionKey\":\"target\",\"variableNames\":[],\"maxAttempts\":5}");
        send.setFieldExtensions(List.of(extensionKey, extensionConfig));
        source.addFlowElement(send);
        Process target = new Process();
        target.setId("target");
        target.setExecutable(true);
        ReceiveTask receive = new ReceiveTask();
        receive.setId("receive");
        target.addFlowElement(receive);
        model.addProcess(source);
        model.addProcess(target);
        Pool sourcePool = new Pool();
        sourcePool.setId("pool-source");
        sourcePool.setProcessRef("source");
        Pool targetPool = new Pool();
        targetPool.setId("pool-target");
        targetPool.setProcessRef("target");
        model.setPools(List.of(sourcePool, targetPool));
        MessageFlow flow = new MessageFlow("send", "receive");
        flow.setId("flow-1");
        flow.setName("approval.requested");
        model.addMessageFlow(flow);
        return model;
    }
}
