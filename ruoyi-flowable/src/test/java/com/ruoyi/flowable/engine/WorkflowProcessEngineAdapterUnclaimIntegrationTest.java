package com.ruoyi.flowable.engine;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.catchThrowable;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;

import java.util.List;
import java.util.Set;
import java.util.concurrent.atomic.AtomicReference;
import java.util.function.Function;
import org.flowable.engine.ProcessEngine;
import org.flowable.engine.ProcessEngineConfiguration;
import org.flowable.engine.RepositoryService;
import org.flowable.engine.RuntimeService;
import org.flowable.engine.TaskService;
import org.flowable.engine.task.Comment;
import org.flowable.task.api.Task;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import com.ruoyi.common.constant.HttpStatus;
import com.ruoyi.common.exception.ServiceException;
import com.ruoyi.flowable.identity.WorkflowCurrentIdentity;
import com.ruoyi.flowable.identity.WorkflowIdentityResolver;
import com.ruoyi.flowable.service.notification.WorkflowNotificationService;
import com.ruoyi.flowable.service.task.WorkflowTaskUnclaimPolicy;

/**
 * 使用真实 Flowable 8 任务、实例、审计 comment 验证取消认领最终写入门禁。
 */
class WorkflowProcessEngineAdapterUnclaimIntegrationTest
{
    /** 真实认领用户，也是默认服务端当前身份。 */
    private static final String CLAIM_USER_ID = "100";

    private ProcessEngine processEngine;
    private RepositoryService repositoryService;
    private RuntimeService runtimeService;
    private TaskService taskService;
    private AtomicReference<WorkflowCurrentIdentity> currentIdentity;
    private WorkflowNotificationService notificationService;
    private WorkflowProcessEngineAdapter adapter;

    /**
     * 创建真实内存 Flowable 引擎，并以同步身份回调装配待测适配器。
     *
     * @return void，无返回值；每个测试使用独立 H2 数据库
     */
    @BeforeEach
    @SuppressWarnings({ "rawtypes", "unchecked" })
    void setUp()
    {
        processEngine = ProcessEngineConfiguration
                .createStandaloneInMemProcessEngineConfiguration()
                .setDatabaseSchemaUpdate(ProcessEngineConfiguration.DB_SCHEMA_UPDATE_TRUE)
                .setHistory("full")
                .buildProcessEngine();
        repositoryService = processEngine.getRepositoryService();
        runtimeService = processEngine.getRuntimeService();
        taskService = processEngine.getTaskService();

        currentIdentity = new AtomicReference<>(
                new WorkflowCurrentIdentity(CLAIM_USER_ID, Set.of()));
        WorkflowEngineOperations engineOperations = mock(WorkflowEngineOperations.class);
        doAnswer(invocation -> ((Function<WorkflowCurrentIdentity, ?>)
                invocation.getArgument(0)).apply(currentIdentity.get()))
                .when(engineOperations).writeAsCurrentUser(any(Function.class));
        WorkflowIdentityResolver identityResolver = mock(WorkflowIdentityResolver.class);
        notificationService = mock(WorkflowNotificationService.class);
        adapter = new WorkflowProcessEngineAdapter(repositoryService, runtimeService,
                taskService, engineOperations, identityResolver, notificationService,
                new WorkflowTaskUnclaimPolicy());
    }

    /**
     * 关闭真实 Flowable 引擎，避免测试之间共享运行时和历史状态。
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
     * 验证真实 claim 任务可以取消认领，并保持原审计 comment 与事件行为。
     *
     * @return void，状态、审计或 TASK_UNCLAIMED 事件任一缺失时测试失败
     */
    @Test
    void unclaimsClaimedTaskAndKeepsAuditAndEvent()
    {
        Task task = startTask("successfulUnclaim", "flowable:candidateUsers=\"100\"");
        taskService.claim(task.getId(), CLAIM_USER_ID);
        Task claimedTask = taskService.createTaskQuery().taskId(task.getId()).singleResult();
        assertThat(claimedTask.getClaimedBy()).isEqualTo(CLAIM_USER_ID);
        assertThat(claimedTask.getClaimTime()).isNotNull();

        adapter.unclaimTaskForCurrentUser(task.getId());

        Task unclaimedTask = taskService.createTaskQuery().taskId(task.getId()).singleResult();
        assertThat(unclaimedTask.getAssignee()).isNull();
        assertThat(unclaimedTask.getClaimedBy()).isNull();
        assertThat(unclaimedTask.getClaimTime()).isNull();
        List<Comment> comments = taskService.getTaskComments(task.getId(), "1");
        assertThat(comments).singleElement().satisfies(comment ->
        {
            assertThat(comment.getType()).isEqualTo("1");
            assertThat(comment.getFullMessage()).contains("\"action\":\"UNCLAIM\"")
                    .contains("\"actorUserId\":\"100\"")
                    .contains("用户取消认领任务");
        });
        verify(notificationService).onStableTaskAction("TASK_UNCLAIMED", task.getId());
    }

    /**
     * 验证直接指派、委派和认领身份漂移继续返回稳定 409。
     *
     * @return void，任一非法认领来源被写入端接受时测试失败
     */
    @Test
    void rejectsDirectAssignmentDelegationAndClaimDrift()
    {
        Task directTask = startTask("directAssignment", "flowable:assignee=\"100\"");
        assertStatus(HttpStatus.CONFLICT,
                () -> adapter.unclaimTaskForCurrentUser(directTask.getId()));

        Task delegatedTask = startTask("delegatedAssignment", "flowable:assignee=\"100\"");
        taskService.delegateTask(delegatedTask.getId(), "200");
        currentIdentity.set(new WorkflowCurrentIdentity("200", Set.of()));
        assertStatus(HttpStatus.CONFLICT,
                () -> adapter.unclaimTaskForCurrentUser(delegatedTask.getId()));

        Task driftedTask = startTask("claimDrift", "flowable:candidateUsers=\"100\"");
        taskService.claim(driftedTask.getId(), CLAIM_USER_ID);
        taskService.setAssignee(driftedTask.getId(), "200");
        assertStatus(HttpStatus.CONFLICT,
                () -> adapter.unclaimTaskForCurrentUser(driftedTask.getId()));
        verifyNoInteractions(notificationService);
    }

    /**
     * 验证非当前 assignee 返回 403，任务不存在返回 404。
     *
     * @return void，原有身份与对象不存在异常合同发生漂移时测试失败
     */
    @Test
    void keepsForbiddenAndNotFoundContracts()
    {
        Task task = startTask("foreignAssignee", "flowable:candidateUsers=\"100\"");
        taskService.claim(task.getId(), CLAIM_USER_ID);
        currentIdentity.set(new WorkflowCurrentIdentity("200", Set.of()));

        assertStatus(HttpStatus.FORBIDDEN,
                () -> adapter.unclaimTaskForCurrentUser(task.getId()));
        assertStatus(HttpStatus.NOT_FOUND,
                () -> adapter.unclaimTaskForCurrentUser("missing-task"));
        verifyNoInteractions(notificationService);
    }

    /**
     * 验证流程或任务挂起时写入端重新查询并返回 409。
     *
     * @return void，挂起状态绕过最终活动态门禁时测试失败
     */
    @Test
    void rejectsSuspendedProcessOrTask()
    {
        Task processSuspendedTask = startTask(
                "processSuspended", "flowable:candidateUsers=\"100\"");
        taskService.claim(processSuspendedTask.getId(), CLAIM_USER_ID);
        runtimeService.suspendProcessInstanceById(processSuspendedTask.getProcessInstanceId());
        assertStatus(HttpStatus.CONFLICT,
                () -> adapter.unclaimTaskForCurrentUser(processSuspendedTask.getId()));

        Task taskSuspended = startTask(
                "taskSuspended", "flowable:candidateUsers=\"100\"");
        taskService.claim(taskSuspended.getId(), CLAIM_USER_ID);
        taskService.suspendTask(taskSuspended.getId(), CLAIM_USER_ID);
        assertStatus(HttpStatus.CONFLICT,
                () -> adapter.unclaimTaskForCurrentUser(taskSuspended.getId()));
        verifyNoInteractions(notificationService);
    }

    /**
     * 启动一个带受控任务分配属性的单任务流程并返回唯一任务。
     *
     * @param processKey String，唯一流程定义标识
     * @param assignment String，用户任务的 Flowable assignee 或 candidateUsers 属性
     * @return Task，真实运行时任务
     */
    private Task startTask(String processKey, String assignment)
    {
        repositoryService.createDeployment()
                .addString(processKey + ".bpmn20.xml", bpmn(processKey, assignment))
                .deploy();
        String processInstanceId = runtimeService.startProcessInstanceByKey(processKey).getId();
        Task task = taskService.createTaskQuery()
                .processInstanceId(processInstanceId).singleResult();
        assertThat(task).isNotNull();
        return task;
    }

    /**
     * 断言适配器抛出指定稳定 HTTP 业务码。
     *
     * @param expectedStatus int，期望的 HTTP 业务状态码
     * @param action Runnable，待执行的取消认领命令
     * @return void，未抛异常或状态码不一致时测试失败
     */
    private void assertStatus(int expectedStatus, Runnable action)
    {
        Throwable failure = catchThrowable(action::run);
        assertThat(failure).isInstanceOf(ServiceException.class);
        assertThat(((ServiceException) failure).getCode()).isEqualTo(expectedStatus);
    }

    /**
     * 生成可由 Flowable 8 部署执行的单任务 BPMN。
     *
     * @param processKey String，流程定义标识
     * @param assignment String，用户任务分配属性
     * @return String，UTF-8 BPMN XML
     */
    private String bpmn(String processKey, String assignment)
    {
        return """
                <?xml version="1.0" encoding="UTF-8"?>
                <definitions xmlns="http://www.omg.org/spec/BPMN/20100524/MODEL"
                  xmlns:flowable="http://flowable.org/bpmn"
                  targetNamespace="urn:test:unclaim-adapter">
                  <process id="%s" name="取消认领写入" isExecutable="true">
                    <startEvent id="start"/>
                    <userTask id="approval" name="审批" %s/>
                    <endEvent id="end"/>
                    <sequenceFlow id="flow1" sourceRef="start" targetRef="approval"/>
                    <sequenceFlow id="flow2" sourceRef="approval" targetRef="end"/>
                  </process>
                </definitions>
                """.formatted(processKey, assignment);
    }
}
