package com.ruoyi.flowable;

import static org.assertj.core.api.Assertions.assertThat;

import java.nio.charset.StandardCharsets;
import java.util.UUID;
import org.flowable.engine.ProcessEngine;
import org.flowable.engine.RepositoryService;
import org.flowable.engine.repository.Deployment;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import com.ruoyi.RuoYiApplication;
import com.ruoyi.flowable.mapper.WfDeployExtensionSnapshotMapper;
import com.ruoyi.flowable.service.model.WorkflowBpmnDocument;
import com.ruoyi.flowable.service.model.WorkflowBpmnService;
import com.ruoyi.flowable.service.model.WorkflowExtensionDeploymentService;
import com.ruoyi.flowable.service.model.WorkflowPreparedExtensionDeployment;

/**
 * 使用真实 MySQL 和 Flowable 8 验证 SendTask 受控编译、快照冻结和运行结果。
 */
@SpringBootTest(classes = RuoYiApplication.class,
        webEnvironment = SpringBootTest.WebEnvironment.MOCK,
        properties = {
            "spring.datasource.druid.master.url=${FLOWABLE_IT_JDBC_URL}",
            "spring.datasource.druid.master.username=${FLOWABLE_IT_USERNAME}",
            "spring.datasource.druid.master.password=${FLOWABLE_IT_PASSWORD}",
            "spring.data.redis.database=${FLOWABLE_IT_REDIS_DATABASE:15}",
            "token.secret=d29ya2Zsb3ctc2VuZC10YXNrLWl0LXRva2VuLXNlY3JldC13b3JrZmxvdy1zZW5kLXRhc2staXQtdG9rZW4tc2VjcmV0",
            "flowable.database-schema-update=false",
            "flowable.async-executor-activate=false",
            "flowable.async-history-executor-activate=false",
            "spring.quartz.auto-startup=false"
        })
class WorkflowSendTaskMySqlIT
{
    @Autowired
    private ProcessEngine processEngine;

    @Autowired
    private WorkflowBpmnService bpmnService;

    @Autowired
    private WorkflowExtensionDeploymentService extensionDeploymentService;

    @Autowired
    private WfDeployExtensionSnapshotMapper snapshotMapper;

    /**
     * 验证作者 SendTask 经正式编译链冻结后，由固定调度器真实执行内置 Java 扩展。
     * @return void，编译类型、快照持久化、引擎执行或清理任一不一致时测试失败
     */
    @Test
    void compilesPersistsAndExecutesControlledSendTask()
    {
        RepositoryService repositoryService = processEngine.getRepositoryService();
        String runId = UUID.randomUUID().toString().replace("-", "");
        String processKey = "workflowSendTask" + runId;
        byte[] authorBytes = authorBpmn(processKey);
        Deployment deployment = null;
        String processInstanceId = null;
        try
        {
            WorkflowBpmnDocument author = bpmnService.validateForSave(authorBytes);
            WorkflowPreparedExtensionDeployment prepared =
                    extensionDeploymentService.prepare(author, "flowable-it");
            String compiledXml = new String(prepared.compiledBpmn(), StandardCharsets.UTF_8);
            assertThat(compiledXml).contains("<serviceTask")
                    .contains("workflowExtensionDelegate")
                    .doesNotContain("<sendTask")
                    .doesNotContain("approvaExtensionConfig");
            bpmnService.validateCompiledDeployment(prepared.compiledBpmn());

            deployment = repositoryService.createDeployment().name(processKey)
                    .addBytes(processKey + ".bpmn20.xml", prepared.compiledBpmn()).deploy();
            extensionDeploymentService.persist(deployment.getId(), prepared);
            assertThat(snapshotMapper.selectRuntimeSnapshot(
                    deployment.getId(), processKey, "notify")).isNotNull();

            var instance = processEngine.getRuntimeService()
                    .startProcessInstanceByKey(processKey);
            processInstanceId = instance.getId();
            assertThat(processEngine.getRuntimeService().createProcessInstanceQuery()
                    .processInstanceId(processInstanceId).count()).isZero();
            assertThat(processEngine.getHistoryService().createHistoricVariableInstanceQuery()
                    .processInstanceId(processInstanceId).variableName("sendTaskExecuted")
                    .singleResult().getValue()).isEqualTo(true);
        }
        finally
        {
            if (deployment != null)
            {
                snapshotMapper.deleteByDeploymentId(deployment.getId());
                if (repositoryService.createDeploymentQuery()
                        .deploymentId(deployment.getId()).count() == 1L)
                {
                    repositoryService.deleteDeployment(deployment.getId(), true);
                }
            }
            if (processInstanceId != null)
            {
                assertThat(processEngine.getRuntimeService().createProcessInstanceQuery()
                        .processInstanceId(processInstanceId).count()).isZero();
            }
        }
    }

    /**
     * 构造含正式表单引用和受控 SendTask 作者字段的 BPMN 文档。
     * @param processKey String，本轮唯一流程定义 key
     * @return byte[]，UTF-8 作者 BPMN 原始资源
     */
    private byte[] authorBpmn(String processKey)
    {
        String xml = """
                <?xml version="1.0" encoding="UTF-8"?>
                <definitions xmlns="http://www.omg.org/spec/BPMN/20100524/MODEL"
                  xmlns:flowable="http://flowable.org/bpmn"
                  targetNamespace="urn:approvaplat:send-task-it">
                  <process id="%s" isExecutable="true">
                    <startEvent id="start" flowable:formKey="key_1"/>
                    <sequenceFlow id="toNotify" sourceRef="start" targetRef="notify"/>
                    <sendTask id="notify" name="发送受控通知">
                      <extensionElements>
                        <flowable:field name="approvaExtensionKey">
                          <flowable:string>approva.set-variable</flowable:string>
                        </flowable:field>
                        <flowable:field name="approvaExtensionConfig">
                          <flowable:string><![CDATA[{"targetVariable":"sendTaskExecuted","value":true}]]></flowable:string>
                        </flowable:field>
                      </extensionElements>
                    </sendTask>
                    <sequenceFlow id="toEnd" sourceRef="notify" targetRef="end"/>
                    <endEvent id="end"/>
                  </process>
                </definitions>
                """.formatted(processKey);
        return xml.getBytes(StandardCharsets.UTF_8);
    }
}
