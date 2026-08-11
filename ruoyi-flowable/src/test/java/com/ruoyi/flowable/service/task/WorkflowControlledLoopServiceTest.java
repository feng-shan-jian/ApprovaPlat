package com.ruoyi.flowable.service.task;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

import java.util.Date;
import java.util.List;
import java.util.Map;
import org.flowable.engine.RepositoryService;
import org.flowable.engine.RuntimeService;
import org.flowable.engine.TaskService;
import org.flowable.engine.repository.ProcessDefinition;
import org.flowable.engine.repository.ProcessDefinitionQuery;
import org.flowable.task.api.Task;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.dao.DuplicateKeyException;
import com.ruoyi.common.constant.HttpStatus;
import com.ruoyi.common.exception.ServiceException;
import com.ruoyi.flowable.domain.WfControlledLoopExecution;
import com.ruoyi.flowable.domain.WfDeployControlledLoop;
import com.ruoyi.flowable.mapper.WfControlledLoopExecutionMapper;
import com.ruoyi.flowable.service.model.WorkflowDeploymentArtifactRepository;

/**
 * 受控重复审批循环运行判断、并发冲突、上限和历史一致性测试。
 */
class WorkflowControlledLoopServiceTest
{
    private RepositoryService repositoryService;
    private RuntimeService runtimeService;
    private TaskService taskService;
    private WorkflowDeploymentArtifactRepository artifactRepository;
    private WfControlledLoopExecutionMapper executionMapper;
    private WorkflowControlledLoopService service;
    private Task task;

    /**
     * 创建流程定义、任务和数据访问替身。
     * @return 无返回值，每个测试获得独立运行状态
     */
    @BeforeEach
    void setUp()
    {
        repositoryService = mock(RepositoryService.class);
        runtimeService = mock(RuntimeService.class);
        taskService = mock(TaskService.class);
        artifactRepository = mock(WorkflowDeploymentArtifactRepository.class);
        executionMapper = mock(WfControlledLoopExecutionMapper.class);
        service = new WorkflowControlledLoopService(repositoryService, runtimeService,
                taskService, artifactRepository, executionMapper);
        ProcessDefinitionQuery query = mock(ProcessDefinitionQuery.class);
        ProcessDefinition definition = mock(ProcessDefinition.class);
        when(repositoryService.createProcessDefinitionQuery()).thenReturn(query);
        when(query.processDefinitionId("definition-1")).thenReturn(query);
        when(query.singleResult()).thenReturn(definition);
        when(definition.getKey()).thenReturn("leave");
        task = mock(Task.class);
        when(task.getId()).thenReturn("task-1");
        when(task.getProcessDefinitionId()).thenReturn("definition-1");
        when(task.getProcessInstanceId()).thenReturn("instance-1");
        when(task.getTaskDefinitionKey()).thenReturn("review");
    }

    /**
     * 验证 REPEAT 写入单轮审计、服务端保留变量和结构化 comment。
     * @return 无返回值，轮次、路由变量或审计缺失时测试失败
     */
    @Test
    void recordsRepeatOutcomeAndServerOwnedRouteVariables()
    {
        when(artifactRepository.selectControlledLoop("deployment-1", "leave", "review"))
                .thenReturn(config(4));
        when(executionMapper.selectMaxIteration("instance-1", "review")).thenReturn(0);
        when(executionMapper.insert(any())).thenReturn(1);

        service.prepareCompletion(task, "deployment-1", Map.of("decision", "redo"), "9");

        verify(executionMapper).insert(any(WfControlledLoopExecution.class));
        verify(runtimeService).setVariable("instance-1", "__route", true);
        verify(runtimeService).setVariable("instance-1", "__iteration", 1);
        verify(taskService).addComment(anyString(), anyString(),
                org.mockito.ArgumentMatchers.eq(WorkflowControlledLoopService.COMMENT_TYPE),
                org.mockito.ArgumentMatchers.contains("\"outcome\":\"REPEAT\""));
    }

    /**
     * 验证达到最大轮次仍选择整改时在任何审计、变量和 comment 写入前返回 409。
     * @return 无返回值，上限请求产生任一业务副作用时测试失败
     */
    @Test
    void rejectsRepeatAtMaximumIterationWithZeroSideEffects()
    {
        when(artifactRepository.selectControlledLoop("deployment-1", "leave", "review"))
                .thenReturn(config(3));
        when(executionMapper.selectMaxIteration("instance-1", "review")).thenReturn(2);

        assertThatThrownBy(() -> service.prepareCompletion(task, "deployment-1",
                Map.of("decision", "redo"), "9"))
                .isInstanceOfSatisfying(ServiceException.class, exception ->
                {
                    assertThat(exception.getCode()).isEqualTo(HttpStatus.CONFLICT);
                    assertThat(exception.getSubCode()).isEqualTo(
                            WorkflowControlledLoopService.LIMIT_REACHED_SUB_CODE);
                });

        verify(executionMapper, never()).insert(any());
        verifyNoInteractions(runtimeService, taskService);
    }

    /**
     * 验证唯一约束竞争被转换为稳定 409 且不会继续写路由变量或 comment。
     * @return 无返回值，并发重复完成泄漏为 500 或产生后续副作用时测试失败
     */
    @Test
    void convertsConcurrentUniqueConstraintToConflict()
    {
        when(artifactRepository.selectControlledLoop("deployment-1", "leave", "review"))
                .thenReturn(config(4));
        when(executionMapper.selectMaxIteration("instance-1", "review")).thenReturn(0);
        when(executionMapper.insert(any())).thenThrow(new DuplicateKeyException("duplicate"));

        assertThatThrownBy(() -> service.prepareCompletion(task, "deployment-1",
                Map.of("decision", "approved"), "9"))
                .isInstanceOfSatisfying(ServiceException.class, exception ->
                {
                    assertThat(exception.getCode()).isEqualTo(HttpStatus.CONFLICT);
                    assertThat(exception.getSubCode())
                            .isEqualTo("CONTROLLED_LOOP_CONCURRENT_CONFLICT");
                });

        verifyNoInteractions(runtimeService, taskService);
    }

    /**
     * 验证详情投影对不连续轮次和 EXIT 后追加记录失败关闭。
     * @return 无返回值，损坏审计被当作正常轨迹返回时测试失败
     */
    @Test
    void rejectsCorruptedRoundHistory()
    {
        WfDeployControlledLoop config = config(4);
        config.setActivityName("复核");
        when(artifactRepository.selectControlledLoops("deployment-1", "leave"))
                .thenReturn(List.of(config));
        WfControlledLoopExecution first = execution(1, "EXIT");
        WfControlledLoopExecution second = execution(2, "REPEAT");
        when(executionMapper.selectByProcessInstanceId("instance-1"))
                .thenReturn(List.of(first, second));

        assertThatThrownBy(() -> service.buildStates(
                "deployment-1", "leave", "instance-1", null))
                .isInstanceOfSatisfying(ServiceException.class, exception ->
                        assertThat(exception.getCode()).isEqualTo(HttpStatus.ERROR));
    }

    /**
     * 创建完整部署循环快照。
     * @param maxIterations int，允许完成的最大轮次
     * @return WfDeployControlledLoop，固定 review 节点配置
     */
    private WfDeployControlledLoop config(int maxIterations)
    {
        WfDeployControlledLoop config = new WfDeployControlledLoop();
        config.setDeployId("deployment-1");
        config.setProcessKey("leave");
        config.setActivityId("review");
        config.setActivityName("复核");
        config.setDecisionVariable("decision");
        config.setRepeatValue("redo");
        config.setExitValue("approved");
        config.setMaxIterations(maxIterations);
        config.setRouteVariable("__route");
        config.setIterationVariable("__iteration");
        return config;
    }

    /**
     * 创建单轮正式运行审计。
     * @param iteration int，从 1 开始的轮次
     * @param outcome String，REPEAT 或 EXIT
     * @return WfControlledLoopExecution，关系字段完整的测试记录
     */
    private WfControlledLoopExecution execution(int iteration, String outcome)
    {
        WfControlledLoopExecution execution = new WfControlledLoopExecution();
        execution.setActivityId("review");
        execution.setTaskId("task-" + iteration);
        execution.setIterationNo(iteration);
        execution.setActorUserId("9");
        execution.setDecisionValue("EXIT".equals(outcome) ? "approved" : "redo");
        execution.setOutcome(outcome);
        execution.setCreateTime(new Date());
        return execution;
    }
}
