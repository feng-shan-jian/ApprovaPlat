package com.ruoyi.flowable;

import static org.assertj.core.api.Assertions.assertThat;

import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.UUID;
import org.flowable.engine.ProcessEngine;
import org.flowable.engine.RepositoryService;
import org.flowable.engine.repository.Deployment;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import com.ruoyi.RuoYiApplication;
import com.ruoyi.flowable.service.model.WorkflowBpmnDocument;
import com.ruoyi.flowable.service.model.WorkflowBpmnService;
import com.ruoyi.flowable.service.model.WorkflowDeploymentArtifactRepository;
import com.ruoyi.flowable.service.model.WorkflowDeploymentArtifacts;
import com.ruoyi.flowable.service.model.WorkflowExtensionDeploymentService;
import com.ruoyi.flowable.service.model.WorkflowPreparedExtensionDeployment;

/**
 * 使用真实 MySQL 和 Flowable 8 验证受控执行监听器、任务监听器和系统审计监听器共存。
 */
@SpringBootTest(classes = RuoYiApplication.class,
        webEnvironment = SpringBootTest.WebEnvironment.MOCK,
        properties = {
            "spring.datasource.druid.master.url=${FLOWABLE_IT_JDBC_URL}",
            "spring.datasource.druid.master.username=${FLOWABLE_IT_USERNAME}",
            "spring.datasource.druid.master.password=${FLOWABLE_IT_PASSWORD}",
            "spring.data.redis.database=${FLOWABLE_IT_REDIS_DATABASE:15}",
            "token.secret=eHh4eHh4eHh4eHh4eHh4eHh4eHh4eHh4eHh4eHh4eHh4eHh4eHh4eHh4eHh4eHh4eHh4eHh4eHh4eHh4eHh4eA==",
            "flowable.database-schema-update=false",
            "flowable.async-executor-activate=false",
            "flowable.async-history-executor-activate=false",
            "spring.quartz.auto-startup=false"
        })
class WorkflowBusinessListenerMySqlIT
{
    @Autowired
    private ProcessEngine processEngine;

    @Autowired
    private WorkflowBpmnService bpmnService;

    @Autowired
    private WorkflowExtensionDeploymentService extensionDeploymentService;

    @Autowired
    private WorkflowDeploymentArtifactRepository artifactRepository;

    /**
     * 通过正式编译、快照持久化和引擎启动验证两类业务监听器真实执行。
     * @return void，监听器快照、变量副作用、系统监听器或清理任一不一致时测试失败
     */
    @Test
    void freezesAndExecutesControlledBusinessListeners()
    {
        RepositoryService repositoryService = processEngine.getRepositoryService();
        String runId = UUID.randomUUID().toString().replace("-", "");
        String processKey = "workflowBusinessListener" + runId;
        Deployment deployment = null;
        String processInstanceId = null;
        try
        {
            WorkflowBpmnDocument author = bpmnService.validateForSave(authorBpmn(processKey));
            WorkflowPreparedExtensionDeployment prepared =
                    extensionDeploymentService.prepare(author, "flowable-it");
            assertThat(prepared.snapshots()).hasSize(3)
                    .allSatisfy(snapshot -> assertThat(snapshot.getElementId())
                            .startsWith("listener_"));
            bpmnService.validateCompiledDeployment(prepared.compiledBpmn());

            deployment = repositoryService.createDeployment().name(processKey)
                    .addBytes(processKey + ".bpmn20.xml", prepared.compiledBpmn()).deploy();
            artifactRepository.persist(deployment.getId(), new WorkflowDeploymentArtifacts(
                    List.of(), List.of(), List.of(), List.of(),
                    extensionDeploymentService.snapshotsForDeployment(
                            deployment.getId(), prepared),
                    List.of(), List.of(), List.of()));
            assertThat(artifactRepository.selectExtensionSnapshots(deployment.getId())).hasSize(3);

            var instance = processEngine.getRuntimeService().startProcessInstanceByKey(processKey);
            processInstanceId = instance.getId();
            assertThat(processEngine.getRuntimeService().getVariable(
                    processInstanceId, "processStarted")).isEqualTo(true);
            assertThat(processEngine.getRuntimeService().getVariable(
                    processInstanceId, "activityEntered")).isEqualTo(true);
            assertThat(processEngine.getRuntimeService().getVariable(
                    processInstanceId, "taskCreated")).isEqualTo(true);
            var task = processEngine.getTaskService().createTaskQuery()
                    .processInstanceId(processInstanceId).singleResult();
            assertThat(task).isNotNull();
            assertThat(processEngine.getTaskService().getProcessInstanceComments(processInstanceId))
                    .isNotEmpty();
            processEngine.getIdentityService().setAuthenticatedUserId("1");
            try
            {
                processEngine.getTaskService().complete(task.getId());
            }
            finally
            {
                processEngine.getIdentityService().setAuthenticatedUserId(null);
            }
            assertThat(processEngine.getRuntimeService().createProcessInstanceQuery()
                    .processInstanceId(processInstanceId).count()).isZero();
        }
        finally
        {
            if (deployment != null)
            {
                artifactRepository.delete(deployment.getId());
                if (repositoryService.createDeploymentQuery()
                        .deploymentId(deployment.getId()).count() == 1L)
                {
                    repositoryService.deleteDeployment(deployment.getId(), true);
                }
            }
        }
    }

    /**
     * 构造含固定业务监听器字段和三条系统审计监听器的作者 BPMN。
     * @param processKey String，本轮唯一流程定义 key
     * @return byte[]，UTF-8 作者 BPMN 原始资源
     */
    private byte[] authorBpmn(String processKey)
    {
        String xml = """
                <?xml version="1.0" encoding="UTF-8"?>
                <definitions xmlns="http://www.omg.org/spec/BPMN/20100524/MODEL"
                  xmlns:flowable="http://flowable.org/bpmn"
                  targetNamespace="urn:approvaplat:business-listener-it">
                  <process id="%s" isExecutable="true">
                    <extensionElements>
                      <flowable:executionListener event="start" delegateExpression="${workflowBusinessListener}">
                        <flowable:field name="approvaExtensionKey" stringValue="approva.set-variable"/>
                        <flowable:field name="approvaExtensionConfig">
                          <flowable:string><![CDATA[{"targetVariable":"processStarted","value":true}]]></flowable:string>
                        </flowable:field>
                      </flowable:executionListener>
                    </extensionElements>
                    <startEvent id="start" flowable:formKey="key_1"/>
                    <sequenceFlow id="toApprove" sourceRef="start" targetRef="approve"/>
                    <userTask id="approve" name="审批" flowable:assignee="1">
                      <extensionElements>
                        <flowable:executionListener event="start" delegateExpression="${workflowBusinessListener}">
                          <flowable:field name="approvaExtensionKey" stringValue="approva.set-variable"/>
                          <flowable:field name="approvaExtensionConfig">
                            <flowable:string><![CDATA[{"targetVariable":"activityEntered","value":true}]]></flowable:string>
                          </flowable:field>
                        </flowable:executionListener>
                        <flowable:taskListener event="create" delegateExpression="${workflowBusinessListener}">
                          <flowable:field name="approvaExtensionKey" stringValue="approva.set-variable"/>
                          <flowable:field name="approvaExtensionConfig">
                            <flowable:string><![CDATA[{"targetVariable":"taskCreated","value":true}]]></flowable:string>
                          </flowable:field>
                        </flowable:taskListener>
                        <flowable:taskListener event="create" delegateExpression="${userTaskListener}"/>
                        <flowable:taskListener event="assignment" delegateExpression="${userTaskListener}"/>
                        <flowable:taskListener event="complete" delegateExpression="${userTaskListener}"/>
                      </extensionElements>
                    </userTask>
                    <sequenceFlow id="toEnd" sourceRef="approve" targetRef="end"/>
                    <endEvent id="end"/>
                  </process>
                </definitions>
                """.formatted(processKey);
        return xml.getBytes(StandardCharsets.UTF_8);
    }
}
