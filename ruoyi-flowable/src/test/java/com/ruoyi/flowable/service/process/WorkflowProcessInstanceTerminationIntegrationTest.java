package com.ruoyi.flowable.service.process;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;

import java.util.List;
import org.flowable.engine.HistoryService;
import org.flowable.engine.ProcessEngine;
import org.flowable.engine.ProcessEngineConfiguration;
import org.flowable.engine.RepositoryService;
import org.flowable.engine.RuntimeService;
import org.flowable.engine.TaskService;
import org.flowable.engine.history.HistoricProcessInstance;
import org.flowable.engine.runtime.ProcessInstance;
import org.flowable.task.api.Task;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import com.ruoyi.flowable.service.notification.WorkflowNotificationService;

/**
 * 使用真实 Flowable 引擎验证挂起根流程及 CallActivity 子流程的终止审计链。
 */
class WorkflowProcessInstanceTerminationIntegrationTest
{
    private ProcessEngine processEngine;
    private RuntimeService runtimeService;
    private RepositoryService repositoryService;
    private HistoryService historyService;
    private TaskService taskService;
    private WorkflowProcessInstanceService processInstanceService;

    /**
     * 创建真实内存 Flowable 引擎并部署带 CallActivity 的并行流程。
     *
     * @return void，无返回值
     */
    @BeforeEach
    void setUp()
    {
        processEngine = ProcessEngineConfiguration
                .createStandaloneInMemProcessEngineConfiguration()
                .setDatabaseSchemaUpdate(ProcessEngineConfiguration.DB_SCHEMA_UPDATE_TRUE)
                .buildProcessEngine();
        runtimeService = processEngine.getRuntimeService();
        repositoryService = processEngine.getRepositoryService();
        historyService = processEngine.getHistoryService();
        taskService = processEngine.getTaskService();
        repositoryService.createDeployment()
                .addString("termination-root.bpmn20.xml", BPMN)
                .deploy();
        processInstanceService = new WorkflowProcessInstanceService(
                null, historyService, runtimeService, taskService, null, null, null, null, null,
                mock(WorkflowNotificationService.class));
    }

    /**
     * 关闭真实 Flowable 引擎，避免测试之间共享运行时表和历史表。
     *
     * @return void，无返回值
     */
    @AfterEach
    void tearDown()
    {
        if (processEngine != null)
        {
            processEngine.close();
        }
    }

    /**
     * 验证挂起树按根优先完整激活后，根和子流程活动任务均写入取消 comment，
     * 并保留 canceled 状态和历史审计。
     *
     * @return void，任一终止链事实不一致时测试失败
     */
    @Test
    void terminatesSuspendedCallActivityTreeAndAuditsEveryActiveTask()
    {
        ProcessInstance root = runtimeService.startProcessInstanceByKey("terminationRoot");
        List<ProcessInstance> tree = new java.util.ArrayList<>();
        tree.add(root);
        tree.addAll(runtimeService.createProcessInstanceQuery()
                .superProcessInstanceId(root.getId()).list());
        assertThat(tree).hasSize(2);
        List<Task> tasksBeforeSuspend = taskService.createTaskQuery()
                .processInstanceIdIn(tree.stream().map(ProcessInstance::getId).toList())
                .active().list();
        assertThat(tasksBeforeSuspend).extracting(Task::getTaskDefinitionKey)
                .containsExactlyInAnyOrder("rootTask", "childTask");

        for (ProcessInstance instance : tree)
        {
            runtimeService.suspendProcessInstanceById(instance.getId());
        }
        ProcessInstance suspendedRoot = runtimeService.createProcessInstanceQuery()
                .processInstanceId(root.getId()).singleResult();
        assertThat(suspendedRoot.isSuspended()).isTrue();

        processInstanceService.terminateRootProcessInstance(suspendedRoot, "canceled", context ->
        {
            List<Task> activeTasks = taskService.createTaskQuery()
                    .processInstanceIdIn(context.processTreeInstanceIds()).active().list();
            assertThat(activeTasks).hasSize(2);
            for (Task task : activeTasks)
            {
                taskService.addComment(task.getId(), task.getProcessInstanceId(), "6",
                        "{\"action\":\"CANCEL\"}");
            }
            return new WorkflowProcessInstanceService.RootTerminationInstruction(
                    "canceled: integration-test", null);
        });

        assertThat(runtimeService.createProcessInstanceQuery()
                .processInstanceIds(tree.stream().map(ProcessInstance::getId)
                        .collect(java.util.stream.Collectors.toSet()))
                .count()).isZero();
        HistoricProcessInstance historicRoot = historyService
                .createHistoricProcessInstanceQuery().processInstanceId(root.getId())
                .singleResult();
        assertThat(historicRoot).isNotNull();
        assertThat(historicRoot.getEndTime()).isNotNull();
        assertThat(historicRoot.getBusinessStatus()).isEqualTo("canceled");
        assertThat(historyService.createHistoricVariableInstanceQuery()
                .processInstanceId(root.getId()).variableName("processStatus")
                .singleResult().getValue()).isEqualTo("canceled");
        for (ProcessInstance instance : tree)
        {
            HistoricProcessInstance historicInstance = historyService
                    .createHistoricProcessInstanceQuery().processInstanceId(instance.getId())
                    .singleResult();
            assertThat(historicInstance).isNotNull();
            assertThat(historicInstance.getEndTime()).isNotNull();
        }
        for (Task task : tasksBeforeSuspend)
        {
            assertThat(taskService.getProcessInstanceComments(task.getProcessInstanceId(), "6"))
                    .anyMatch(comment -> task.getId().equals(comment.getTaskId()));
        }
    }

    /** 真实 Flowable 部署用的并行根流程和 CallActivity 子流程。 */
    private static final String BPMN = """
            <?xml version="1.0" encoding="UTF-8"?>
            <definitions xmlns="http://www.omg.org/spec/BPMN/20100524/MODEL"
              xmlns:flowable="http://flowable.org/bpmn" targetNamespace="termination-it">
              <process id="terminationRoot" name="terminationRoot" isExecutable="true">
                <startEvent id="start"/><parallelGateway id="fork"/>
                <userTask id="rootTask" name="Root task"/>
                <callActivity id="callChild" calledElement="terminationChild"/>
                <parallelGateway id="join"/><endEvent id="end"/>
                <sequenceFlow sourceRef="start" targetRef="fork"/>
                <sequenceFlow sourceRef="fork" targetRef="rootTask"/>
                <sequenceFlow sourceRef="fork" targetRef="callChild"/>
                <sequenceFlow sourceRef="rootTask" targetRef="join"/>
                <sequenceFlow sourceRef="callChild" targetRef="join"/>
                <sequenceFlow sourceRef="join" targetRef="end"/>
              </process>
              <process id="terminationChild" name="terminationChild" isExecutable="true">
                <startEvent id="childStart"/><userTask id="childTask" name="Child task"/>
                <endEvent id="childEnd"/>
                <sequenceFlow sourceRef="childStart" targetRef="childTask"/>
                <sequenceFlow sourceRef="childTask" targetRef="childEnd"/>
              </process>
            </definitions>
            """;
}
