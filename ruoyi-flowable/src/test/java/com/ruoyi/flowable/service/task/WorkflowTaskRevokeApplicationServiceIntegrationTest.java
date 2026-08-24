package com.ruoyi.flowable.service.task;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import java.util.Collection;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicReference;
import org.flowable.common.engine.api.delegate.event.FlowableEngineEventType;
import org.flowable.common.engine.api.delegate.event.FlowableEntityEvent;
import org.flowable.common.engine.api.delegate.event.FlowableEvent;
import org.flowable.common.engine.api.delegate.event.FlowableEventListener;
import org.flowable.engine.ProcessEngine;
import org.flowable.engine.RuntimeService;
import org.flowable.engine.TaskService;
import org.flowable.engine.runtime.ProcessInstance;
import org.flowable.task.api.Task;
import org.flowable.task.api.history.HistoricTaskInstance;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import com.ruoyi.common.constant.HttpStatus;
import com.ruoyi.common.exception.ServiceException;
import com.ruoyi.flowable.domain.dto.WorkflowProcessRevokeRequest;
import com.ruoyi.flowable.engine.WorkflowEngineOperations;
import com.ruoyi.flowable.engine.WorkflowExceptionTranslator;
import com.ruoyi.flowable.identity.WorkflowAuthenticationContext;
import com.ruoyi.flowable.identity.WorkflowCurrentIdentity;
import com.ruoyi.flowable.identity.WorkflowIdentityCodec;
import com.ruoyi.flowable.identity.WorkflowIdentityResolver;
import com.ruoyi.flowable.testsupport.WorkflowFlowableEngineTestSupport;

/** 使用真实 Flowable 8/H2 聚焦验证撤回授权、图规则、并发窗口、审计和写后对账。 */
class WorkflowTaskRevokeApplicationServiceIntegrationTest
{
    private static final String ACTOR = "200";
    private static final String OTHER = "201";

    private final AtomicReference<String> currentUser = new AtomicReference<>(ACTOR);
    private WorkflowFlowableEngineTestSupport engineSupport;
    private ProcessEngine processEngine;
    private RuntimeService runtimeService;
    private TaskService taskService;
    private WorkflowTaskRevokeApplicationService revokeService;

    /**
     * 创建独立真实引擎并装配生产撤回服务。
     * @return void，每个用例使用独立 H2 和部署
     */
    @BeforeEach
    void setUp()
    {
        WorkflowIdentityResolver identityResolver = mock(WorkflowIdentityResolver.class);
        when(identityResolver.resolveCurrentIdentity()).thenAnswer(invocation ->
                new WorkflowCurrentIdentity(currentUser.get(), Set.of()));
        engineSupport = WorkflowFlowableEngineTestSupport.start("revoke", Map.of());
        processEngine = engineSupport.processEngine();
        runtimeService = processEngine.getRuntimeService();
        taskService = processEngine.getTaskService();
        processEngine.getRepositoryService().createDeployment()
                .addString("revoke.bpmn20.xml", BPMN).deploy();
        WorkflowEngineOperations operations = engineSupport.transactionalProxy(
                new WorkflowEngineOperations(new WorkflowAuthenticationContext(
                        processEngine.getIdentityService(),
                        new WorkflowIdentityCodec()),
                        new WorkflowExceptionTranslator(), identityResolver));
        WorkflowTaskRuntimeReader runtimeReader = new WorkflowTaskRuntimeReader(
                runtimeService, taskService, processEngine.getHistoryService());
        revokeService = new WorkflowTaskRevokeApplicationService(operations,
                identityResolver, new WorkflowTaskRequestValidator(), runtimeReader,
                new WorkflowTaskBpmnReader(processEngine.getRepositoryService()),
                new WorkflowTaskMovementPolicy(),
                new WorkflowTaskActionAuditWriter(taskService),
                new WorkflowTaskConcurrencyExecutor(), runtimeService, taskService,
                processEngine.getHistoryService());
    }

    /** 关闭真实引擎。 @return void，无返回值 */
    @AfterEach
    void tearDown()
    {
        if (engineSupport != null)
        {
            engineSupport.close();
        }
    }

    /**
     * 验证正常撤回恢复来源任务，并为被关闭后继写入唯一结构化审计。
     * @return void，状态、历史和 comment 任一不一致时失败
     */
    @Test
    void revokesUntouchedSuccessorWithConsistentAuditAndState()
    {
        RevokeCase scenario = startAtSuccessor("safeRevoke");
        revokeService.revokeProcess(request(scenario));

        Task restored = onlyTask(scenario.processInstanceId(), "source");
        assertThat(restored.getAssignee()).isEqualTo(ACTOR);
        assertThat(taskService.createTaskQuery().taskId(
                scenario.successorTaskId()).singleResult()).isNull();
        assertThat(taskService.getProcessInstanceComments(
                scenario.processInstanceId(), "7"))
                .singleElement().satisfies(comment ->
                {
                    assertThat(comment.getTaskId()).isEqualTo(
                            scenario.successorTaskId());
                    assertThat(comment.getFullMessage())
                            .contains("\"action\":\"REVOKE\"")
                            .contains("\"actorUserId\":\"200\"")
                            .contains("\"sourceTaskId\":\""
                                    + scenario.sourceHistoricTaskId() + "\"");
                });
    }

    /**
     * 验证非来源任务真实完成人不能撤回。
     * @return void，命令必须返回 403 且后继保持活动
     */
    @Test
    void rejectsUserWhoDidNotCompleteSourceTask()
    {
        RevokeCase scenario = startAtSuccessor("safeRevoke");
        currentUser.set(OTHER);
        assertThatThrownBy(() -> revokeService.revokeProcess(request(scenario)))
                .isInstanceOfSatisfying(ServiceException.class,
                        failure -> assertThat(failure.getCode())
                                .isEqualTo(HttpStatus.FORBIDDEN));
        assertThat(taskService.createTaskQuery().taskId(
                scenario.successorTaskId()).singleResult()).isNotNull();
    }

    /**
     * 验证后续任务已经办理时撤回失败关闭。
     * @return void，命令必须返回 409 且流程保持自然结束
     */
    @Test
    void rejectsWhenSuccessorHasAlreadyCompleted()
    {
        RevokeCase scenario = startAtSuccessor("safeRevoke");
        taskService.complete(scenario.successorTaskId(), "300");
        assertThatThrownBy(() -> revokeService.revokeProcess(request(scenario)))
                .isInstanceOfSatisfying(ServiceException.class,
                        failure -> assertThat(failure.getCode())
                                .isEqualTo(HttpStatus.CONFLICT));
        assertThat(runtimeService.createProcessInstanceQuery()
                .processInstanceId(scenario.processInstanceId()).singleResult()).isNull();
    }

    /**
     * 验证带边界事件的后继属于不安全 BPMN，能力和命令都拒绝。
     * @return void，能力为 false 且命令返回 409
     */
    @Test
    void rejectsUnsafeBpmnStructure()
    {
        RevokeCase scenario = startAtSuccessor("unsafeRevoke");
        assertThat(revokeService.isProcessRevocable(scenario.processInstanceId(),
                scenario.sourceHistoricTaskId())).isFalse();
        assertThatThrownBy(() -> revokeService.revokeProcess(request(scenario)))
                .isInstanceOfSatisfying(ServiceException.class,
                        failure -> assertThat(failure.getCode())
                                .isEqualTo(HttpStatus.CONFLICT));
    }

    /**
     * 在后继写锁触发的 ENTITY_UPDATED 事件中完成后继，验证锁后重核验和事务回滚。
     * @return void，竞争完成、撤回审计和迁移都不得提交
     */
    @Test
    void rollsBackWhenSuccessorChangesDuringLockRevalidation()
    {
        RevokeCase scenario = startAtSuccessor("safeRevoke");
        AtomicBoolean injectOnce = new AtomicBoolean(true);
        processEngine.getProcessEngineConfiguration().getEventDispatcher()
                .addEventListener(new LockStateChangeProbe(taskService,
                        scenario.successorTaskId(), injectOnce),
                        FlowableEngineEventType.ENTITY_UPDATED);

        assertThatThrownBy(() -> revokeService.revokeProcess(request(scenario)))
                .isInstanceOfSatisfying(ServiceException.class,
                        failure -> assertThat(failure.getCode())
                                .isEqualTo(HttpStatus.CONFLICT));
        assertThat(onlyTask(scenario.processInstanceId(), "successor").getId())
                .isEqualTo(scenario.successorTaskId());
        assertThat(taskService.getProcessInstanceComments(
                scenario.processInstanceId())).isEmpty();
    }

    /**
     * 验证只读能力与正常撤回的全部前置条件一致且无写副作用。
     * @return void，能力为 true 且任务、历史、comment 均不变化
     */
    @Test
    void capabilityUsesSameRulesWithoutWrites()
    {
        RevokeCase scenario = startAtSuccessor("safeRevoke");
        assertThat(revokeService.isProcessRevocable(scenario.processInstanceId(),
                scenario.sourceHistoricTaskId())).isTrue();
        assertThat(onlyTask(scenario.processInstanceId(), "successor").getId())
                .isEqualTo(scenario.successorTaskId());
        assertThat(taskService.getProcessInstanceComments(
                scenario.processInstanceId())).isEmpty();
    }

    /**
     * 启动流程并以正式 completedBy 完成来源任务。
     * @param processKey String，安全或不安全流程定义 key
     * @return RevokeCase，实例、来源历史任务和活动后继主键
     */
    private RevokeCase startAtSuccessor(String processKey)
    {
        // BPMN XML 的 id 在同一 definitions 中必须全局唯一，因此不安全流程使用独立节点 key。
        String sourceActivityId = "unsafeRevoke".equals(processKey) ? "unsafeSource" : "source";
        String successorActivityId = "unsafeRevoke".equals(processKey) ? "unsafeSuccessor" : "successor";
        processEngine.getIdentityService().setAuthenticatedUserId("100");
        ProcessInstance instance;
        try
        {
            instance = runtimeService.startProcessInstanceByKey(processKey);
        }
        finally
        {
            processEngine.getIdentityService().setAuthenticatedUserId(null);
        }
        Task source = onlyTask(instance.getId(), sourceActivityId);
        taskService.complete(source.getId(), ACTOR);
        Task successor = onlyTask(instance.getId(), successorActivityId);
        HistoricTaskInstance historic = processEngine.getHistoryService()
                .createHistoricTaskInstanceQuery().taskId(source.getId())
                .finished().singleResult();
        assertThat(historic.getCompletedBy()).isEqualTo(ACTOR);
        return new RevokeCase(instance.getId(), source.getId(), successor.getId());
    }

    /**
     * 查询实例唯一活动任务并校验节点。
     * @param processInstanceId String，流程实例主键
     * @param activityId String，预期活动节点
     * @return Task，唯一真实活动任务
     */
    private Task onlyTask(String processInstanceId, String activityId)
    {
        return taskService.createTaskQuery().processInstanceId(processInstanceId)
                .taskDefinitionKey(activityId).active().singleResult();
    }

    /** @param scenario RevokeCase，撤回场景 @return WorkflowProcessRevokeRequest，正式请求 */
    private WorkflowProcessRevokeRequest request(RevokeCase scenario)
    {
        return new WorkflowProcessRevokeRequest(scenario.processInstanceId(),
                scenario.sourceHistoricTaskId(), "撤回并修改");
    }

    /** 撤回测试所需的不可变业务主键。 */
    private record RevokeCase(String processInstanceId,
            String sourceHistoricTaskId, String successorTaskId)
    {
    }

    /** 在真实 Flowable 锁事件中一次性完成后继任务的窄探针。 */
    private record LockStateChangeProbe(TaskService taskService, String taskId,
            AtomicBoolean injectOnce) implements FlowableEventListener
    {
        /** @param event FlowableEvent，真实实体更新事件 @return void，无返回值 */
        @Override
        public void onEvent(FlowableEvent event)
        {
            if (event instanceof FlowableEntityEvent entityEvent
                    && entityEvent.getEntity() instanceof Task task
                    && taskId.equals(task.getId())
                    && injectOnce.compareAndSet(true, false))
            {
                // 在同一事务的写锁之后制造后继已办理状态，重核验必须拒绝并整体回滚。
                taskService.complete(taskId, "lock-race-user");
            }
        }

        /** @return boolean，探针异常必须回滚命令 */
        @Override public boolean isFailOnException() { return true; }
        /** @return boolean，当前命令内同步执行 */
        @Override public boolean isFireOnTransactionLifecycleEvent() { return false; }
        /** @return String，不绑定事务生命周期阶段 */
        @Override public String getOnTransaction() { return null; }
        /** @return Collection，探针只监听实体更新 */
        @Override public Collection<FlowableEngineEventType> getTypes()
        {
            return Set.of(FlowableEngineEventType.ENTITY_UPDATED);
        }
    }

    private static final String BPMN = """
            <?xml version="1.0" encoding="UTF-8"?>
            <definitions xmlns="http://www.omg.org/spec/BPMN/20100524/MODEL"
              xmlns:xsi="http://www.w3.org/2001/XMLSchema-instance"
              xmlns:flowable="http://flowable.org/bpmn" targetNamespace="revoke">
              <process id="safeRevoke" isExecutable="true">
                <startEvent id="start"/><sequenceFlow id="s1" sourceRef="start" targetRef="source"/>
                <userTask id="source" flowable:assignee="200"/><sequenceFlow id="s2" sourceRef="source" targetRef="successor"/>
                <userTask id="successor" flowable:assignee="300"/><sequenceFlow id="s3" sourceRef="successor" targetRef="end"/>
                <endEvent id="end"/>
              </process>
              <process id="unsafeRevoke" isExecutable="true">
                <startEvent id="uStart"/><sequenceFlow id="u1" sourceRef="uStart" targetRef="unsafeSource"/>
                <userTask id="unsafeSource" flowable:assignee="200"/><sequenceFlow id="u2" sourceRef="unsafeSource" targetRef="unsafeSuccessor"/>
                <userTask id="unsafeSuccessor" flowable:assignee="300"/><boundaryEvent id="timer" attachedToRef="unsafeSuccessor"><timerEventDefinition><timeDuration>PT1H</timeDuration></timerEventDefinition></boundaryEvent>
                <sequenceFlow id="u3" sourceRef="unsafeSuccessor" targetRef="uEnd"/><endEvent id="uEnd"/>
              </process>
            </definitions>
            """;
}
