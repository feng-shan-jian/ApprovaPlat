package com.ruoyi.flowable.authorization;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import java.util.Set;
import java.util.concurrent.atomic.AtomicReference;
import java.util.function.Supplier;
import org.flowable.engine.ProcessEngine;
import org.flowable.engine.ProcessEngineConfiguration;
import org.flowable.engine.RepositoryService;
import org.flowable.engine.RuntimeService;
import org.flowable.engine.TaskService;
import org.flowable.engine.runtime.ProcessInstance;
import org.flowable.task.api.Task;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import com.ruoyi.flowable.engine.WorkflowEngineOperations;
import com.ruoyi.flowable.identity.WorkflowCurrentIdentity;
import com.ruoyi.flowable.identity.WorkflowIdentityResolver;
import com.ruoyi.flowable.mapper.WfCopyMapper;
import com.ruoyi.flowable.service.task.WorkflowTaskUnclaimPolicy;
import com.ruoyi.framework.web.service.PermissionService;

/**
 * 使用真实 Flowable 8 运行时与历史表验证详情任务的取消认领能力投影。
 */
class WorkflowProcessAccessServiceIntegrationTest
{
    /** 当前测试服务端身份。 */
    private static final String CURRENT_USER_ID = "100";

    private ProcessEngine processEngine;
    private RepositoryService repositoryService;
    private RuntimeService runtimeService;
    private TaskService taskService;
    private AtomicReference<WorkflowCurrentIdentity> currentIdentity;
    private WorkflowProcessAccessService service;

    /**
     * 创建启用完整历史的真实内存引擎，并装配正式对象授权服务。
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

        WorkflowEngineOperations engineOperations = mock(WorkflowEngineOperations.class);
        when(engineOperations.read(any(Supplier.class))).thenAnswer(invocation ->
                ((Supplier<?>) invocation.getArgument(0)).get());
        currentIdentity = new AtomicReference<>(
                new WorkflowCurrentIdentity(CURRENT_USER_ID, Set.of()));
        WorkflowIdentityResolver identityResolver = mock(WorkflowIdentityResolver.class);
        when(identityResolver.resolveCurrentIdentity()).thenAnswer(
                invocation -> currentIdentity.get());

        service = new WorkflowProcessAccessService(processEngine.getHistoryService(),
                runtimeService, taskService, engineOperations, identityResolver,
                mock(WfCopyMapper.class), mock(PermissionService.class),
                new WorkflowTaskUnclaimPolicy());
    }

    /**
     * 关闭真实 Flowable 引擎，避免测试之间共享运行时与历史状态。
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
     * 验证真实 claim 任务为 true，直接指派和历史任务均为 false。
     *
     * @return void，详情快照错误开放或关闭取消认领时测试失败
     */
    @Test
    void projectsClaimedDirectAssignedAndHistoricalTasks()
    {
        deploy("claimProjection", "flowable:candidateUsers=\"100\"");
        ProcessInstance claimedInstance = start("claimProjection");
        Task claimedTask = requireTask(claimedInstance.getId());
        taskService.claim(claimedTask.getId(), CURRENT_USER_ID);

        WorkflowTaskAccessSnapshot claimedSnapshot = service.requireReadableTask(
                claimedTask.getId());
        assertThat(claimedSnapshot.unclaimAllowed()).isTrue();
        assertThat(claimedSnapshot.claimedBy()).isEqualTo(CURRENT_USER_ID);
        assertThat(claimedSnapshot.claimTime()).isNotNull();

        taskService.complete(claimedTask.getId());
        WorkflowTaskAccessSnapshot historicalSnapshot = service.requireReadableTask(
                claimedTask.getId());
        assertThat(historicalSnapshot.active()).isFalse();
        assertThat(historicalSnapshot.unclaimAllowed()).isFalse();

        deploy("assignedProjection", "flowable:assignee=\"100\"");
        Task assignedTask = requireTask(start("assignedProjection").getId());
        WorkflowTaskAccessSnapshot assignedSnapshot = service.requireReadableTask(
                assignedTask.getId());
        assertThat(assignedSnapshot.claimedBy()).isNull();
        assertThat(assignedSnapshot.claimTime()).isNull();
        assertThat(assignedSnapshot.unclaimAllowed()).isFalse();
    }

    /**
     * 验证流程挂起、任务挂起和认领身份漂移均让详情能力失败关闭。
     *
     * @return void，任一运行时非法状态被投影为 true 时测试失败
     */
    @Test
    void failsClosedForSuspensionAndClaimIdentityDrift()
    {
        deploy("suspendedProjection", "flowable:candidateUsers=\"100\"");
        ProcessInstance suspendedInstance = start("suspendedProjection");
        Task suspendedTask = requireTask(suspendedInstance.getId());
        taskService.claim(suspendedTask.getId(), CURRENT_USER_ID);

        runtimeService.suspendProcessInstanceById(suspendedInstance.getId());
        assertThat(service.requireReadableTask(suspendedTask.getId()).unclaimAllowed())
                .isFalse();
        runtimeService.activateProcessInstanceById(suspendedInstance.getId());

        taskService.suspendTask(suspendedTask.getId(), CURRENT_USER_ID);
        assertThat(service.requireReadableTask(suspendedTask.getId()).unclaimAllowed())
                .isFalse();
        taskService.activateTask(suspendedTask.getId(), CURRENT_USER_ID);

        // setAssignee 不会同步改写 claimedBy；该真实漂移必须由共享规则拒绝。
        taskService.setAssignee(suspendedTask.getId(), "200");
        currentIdentity.set(new WorkflowCurrentIdentity("200", Set.of()));
        WorkflowTaskAccessSnapshot driftedSnapshot = service.requireReadableTask(
                suspendedTask.getId());
        assertThat(driftedSnapshot.claimedBy()).isEqualTo(CURRENT_USER_ID);
        assertThat(driftedSnapshot.assignee()).isEqualTo("200");
        assertThat(driftedSnapshot.unclaimAllowed()).isFalse();
    }

    /**
     * 验证真实 owner/delegation 关系关闭详情取消认领能力。
     *
     * @return void，委派任务被错误投影为可取消认领时测试失败
     */
    @Test
    void rejectsDelegatedTaskSnapshot()
    {
        deploy("delegatedProjection", "flowable:assignee=\"100\"");
        Task task = requireTask(start("delegatedProjection").getId());
        taskService.delegateTask(task.getId(), "200");
        currentIdentity.set(new WorkflowCurrentIdentity("200", Set.of()));

        WorkflowTaskAccessSnapshot snapshot = service.requireReadableTask(task.getId());
        assertThat(snapshot.owner()).isEqualTo(CURRENT_USER_ID);
        assertThat(snapshot.delegationState()).isEqualTo("PENDING");
        assertThat(snapshot.unclaimAllowed()).isFalse();
    }

    /**
     * 部署一个带受控任务分配属性的单任务流程。
     *
     * @param processKey String，唯一流程定义标识
     * @param assignment String，用户任务的 Flowable assignee 或 candidateUsers 属性
     * @return void，无返回值；部署失败时测试直接失败
     */
    private void deploy(String processKey, String assignment)
    {
        repositoryService.createDeployment()
                .addString(processKey + ".bpmn20.xml", bpmn(processKey, assignment))
                .deploy();
    }

    /**
     * 以当前用户作为历史发起人启动真实流程实例。
     *
     * @param processKey String，已部署的流程定义标识
     * @return ProcessInstance，真实运行时流程实例
     */
    private ProcessInstance start(String processKey)
    {
        processEngine.getIdentityService().setAuthenticatedUserId(CURRENT_USER_ID);
        try
        {
            return runtimeService.startProcessInstanceByKey(processKey);
        }
        finally
        {
            processEngine.getIdentityService().setAuthenticatedUserId(null);
        }
    }

    /**
     * 查询流程实例的唯一运行时任务。
     *
     * @param processInstanceId String，真实运行时流程实例主键
     * @return Task，实例中的唯一任务
     */
    private Task requireTask(String processInstanceId)
    {
        Task task = taskService.createTaskQuery()
                .processInstanceId(processInstanceId).singleResult();
        assertThat(task).isNotNull();
        return task;
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
                  targetNamespace="urn:test:unclaim-access">
                  <process id="%s" name="取消认领详情投影" isExecutable="true">
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
