package com.ruoyi.flowable.service.process;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import java.sql.Connection;
import java.util.ArrayList;
import java.util.Date;
import java.util.List;
import java.util.Set;
import java.util.concurrent.atomic.AtomicInteger;
import org.flowable.engine.HistoryService;
import org.flowable.engine.IdentityService;
import org.flowable.engine.RuntimeService;
import org.flowable.engine.TaskService;
import org.flowable.engine.history.HistoricProcessInstance;
import org.flowable.engine.history.HistoricProcessInstanceQuery;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.transaction.support.TransactionSynchronizationManager;
import com.ruoyi.common.constant.HttpStatus;
import com.ruoyi.common.exception.ServiceException;
import com.ruoyi.flowable.domain.vo.WorkflowHistoryDeletionView;
import com.ruoyi.flowable.engine.WorkflowEngineOperations;
import com.ruoyi.flowable.engine.WorkflowExceptionTranslator;
import com.ruoyi.flowable.identity.WorkflowAuthenticationContext;
import com.ruoyi.flowable.identity.WorkflowCurrentIdentity;
import com.ruoyi.flowable.identity.WorkflowIdentityCodec;
import com.ruoyi.flowable.identity.WorkflowIdentityResolver;
import com.ruoyi.flowable.mapper.WfAttachmentMapper;
import com.ruoyi.flowable.mapper.WfControlledLoopExecutionMapper;
import com.ruoyi.flowable.mapper.WfCopyMapper;
import com.ruoyi.flowable.mapper.WfMultiInstanceRoundMapper;
import com.ruoyi.flowable.service.notification.WorkflowNotificationService;
import com.ruoyi.flowable.service.task.WorkflowMultiInstanceRoundTerminationService;
import com.ruoyi.flowable.service.task.WorkflowTaskSlaRuntimeService;
import com.ruoyi.framework.web.service.PermissionService;

/**
 * 已结束流程历史删除链中的多实例轮次精确删除与写后对账测试。
 */
class WorkflowProcessHistoryRoundDeletionTest
{
    private HistoryService historyService;
    private WfAttachmentMapper attachmentMapper;
    private WfCopyMapper copyMapper;
    private WfControlledLoopExecutionMapper controlledLoopMapper;
    private WfMultiInstanceRoundMapper roundMapper;
    private PermissionService permissionService;
    private WorkflowProcessInstanceService service;

    /**
     * 创建完整生产依赖替身，并显式建立 WorkflowEngineOperations 要求的可重复读写事务特征。
     * @return void，测试依赖或事务上下文初始化失败时测试失败
     */
    @BeforeEach
    void setUp()
    {
        TransactionSynchronizationManager.setActualTransactionActive(true);
        TransactionSynchronizationManager.setCurrentTransactionReadOnly(false);
        TransactionSynchronizationManager.setCurrentTransactionIsolationLevel(
                Connection.TRANSACTION_REPEATABLE_READ);

        historyService = mock(HistoryService.class);
        attachmentMapper = mock(WfAttachmentMapper.class);
        copyMapper = mock(WfCopyMapper.class);
        controlledLoopMapper = mock(WfControlledLoopExecutionMapper.class);
        roundMapper = mock(WfMultiInstanceRoundMapper.class);
        permissionService = mock(PermissionService.class);
        WorkflowIdentityResolver identityResolver = mock(WorkflowIdentityResolver.class);
        when(identityResolver.resolveCurrentIdentity())
                .thenReturn(new WorkflowCurrentIdentity("9", Set.of()));
        when(permissionService.hasPermi("workflow:process:remove")).thenReturn(true);

        IdentityService identityService = mock(IdentityService.class);
        WorkflowEngineOperations engineOperations = new WorkflowEngineOperations(
                new WorkflowAuthenticationContext(identityService, new WorkflowIdentityCodec()),
                new WorkflowExceptionTranslator(), identityResolver);
        service = new WorkflowProcessInstanceService(engineOperations, historyService,
                mock(RuntimeService.class), mock(TaskService.class), attachmentMapper, copyMapper,
                controlledLoopMapper, roundMapper,
                mock(WorkflowMultiInstanceRoundTerminationService.class), permissionService,
                mock(WorkflowTaskSlaRuntimeService.class),
                mock(WorkflowNotificationService.class));
    }

    /**
     * 清理线程事务特征，避免影响同一 JVM 中的其他测试。
     * @return void，清理后当前线程不再标记为活动事务
     */
    @AfterEach
    void tearDown()
    {
        TransactionSynchronizationManager.clear();
    }

    /**
     * 验证根和 CallActivity 子流程轮次先精确删除，再删 Flowable 历史并执行零残留复核。
     * @return void，轮次数量、顺序、实例集合或写后复核不一致时测试失败
     */
    @Test
    void deletesExactRoundsBeforeFlowableHistoryAndVerifiesNoResidual()
    {
        HistoricProcessInstance root = finishedHistory("root-1", null);
        HistoricProcessInstance child = finishedHistory("child-1", "root-1");
        stubDeletionQueries(root, List.of(child), 0L);
        stubOtherBusinessRows();
        List<String> lifecycle = new ArrayList<>();
        AtomicInteger roundCountCalls = new AtomicInteger();
        when(roundMapper.countByProcessInstanceIds(any())).thenAnswer(invocation ->
        {
            lifecycle.add(roundCountCalls.getAndIncrement() == 0
                    ? "round-count-before" : "round-count-after");
            return roundCountCalls.get() == 1 ? 2L : 0L;
        });
        when(roundMapper.deleteByProcessInstanceIds(any())).thenAnswer(invocation ->
        {
            lifecycle.add("round-delete");
            return 2;
        });
        org.mockito.Mockito.doAnswer(invocation ->
        {
            lifecycle.add("flowable-history-delete");
            return null;
        }).when(historyService).deleteHistoricProcessInstance("root-1");

        WorkflowHistoryDeletionView result = service.deleteCompletedHistory(List.of("root-1"));

        assertThat(result).isEqualTo(new WorkflowHistoryDeletionView(1, 2, 0));
        assertThat(lifecycle).containsExactly("round-count-before", "round-delete",
                "flowable-history-delete", "round-count-after");
        verify(roundMapper).deleteByProcessInstanceIds(eq(Set.of("root-1", "child-1")));
        verify(historyService, never()).deleteHistoricProcessInstance("child-1");
    }

    /**
     * 验证轮次预检数量与实际删除数量漂移时返回 409，且不会开始删除 Flowable 历史。
     * @return void，数量漂移被忽略或引擎历史已写入时测试失败
     */
    @Test
    void rejectsRoundDeleteCountDriftBeforeFlowableHistory()
    {
        HistoricProcessInstance root = finishedHistory("root-1", null);
        stubDeletionQueries(root, List.of(), 0L);
        stubOtherBusinessRows();
        when(roundMapper.countByProcessInstanceIds(any())).thenReturn(2L);
        when(roundMapper.deleteByProcessInstanceIds(any())).thenReturn(1);

        assertConflict(() -> service.deleteCompletedHistory(List.of("root-1")),
                "多实例轮次记录已发生变化");

        verify(historyService, never()).deleteHistoricProcessInstance(anyString());
    }

    /**
     * 验证 Flowable 历史删除后轮次复核仍有残留时返回 409，使外层事务整体回滚。
     * @return void，轮次残留未关闭历史删除请求时测试失败
     */
    @Test
    void rejectsHistoryDeleteWhenRoundResidualRemains()
    {
        HistoricProcessInstance root = finishedHistory("root-1", null);
        stubDeletionQueries(root, List.of(), 0L);
        stubOtherBusinessRows();
        when(roundMapper.countByProcessInstanceIds(any())).thenReturn(1L, 1L);
        when(roundMapper.deleteByProcessInstanceIds(any())).thenReturn(1);

        assertConflict(() -> service.deleteCompletedHistory(List.of("root-1")),
                "流程历史删除结果不完整");

        verify(historyService).deleteHistoricProcessInstance("root-1");
    }

    /**
     * 配置抄送和受控循环在预检、删除、复核阶段均无记录。
     * @return void，后续删除测试只聚焦多实例轮次链路
     */
    private void stubOtherBusinessRows()
    {
        when(attachmentMapper.countBoundByProcessInstanceIds(any())).thenReturn(0L);
        when(copyMapper.countActiveByInstanceIds(any())).thenReturn(0L, 0L);
        when(copyMapper.logicalDeleteByInstanceIds(any(), eq("9"))).thenReturn(0);
        when(controlledLoopMapper.countByProcessInstanceIds(any())).thenReturn(0L, 0L);
        when(controlledLoopMapper.deleteByProcessInstanceIds(any())).thenReturn(0);
    }

    /**
     * 配置单根删除图、直接子流程和 Flowable 写后历史残留计数。
     * @param root HistoricProcessInstance，客户端请求的已结束根历史
     * @param children List&lt;HistoricProcessInstance&gt;，根的直接子流程历史
     * @param remainingHistory long，删除后的 Flowable 历史残留数
     * @return void，HistoryService 后续按生产调用顺序返回独立查询对象
     */
    private void stubDeletionQueries(HistoricProcessInstance root,
            List<HistoricProcessInstance> children, long remainingHistory)
    {
        List<HistoricProcessInstanceQuery> queries = new ArrayList<>();
        queries.add(queryReturning(root));
        queries.add(queryListing(children));
        for (int index = 0; index < children.size(); index++)
        {
            queries.add(queryListing(List.of()));
        }
        queries.add(queryCounting(remainingHistory));
        when(historyService.createHistoricProcessInstanceQuery()).thenReturn(
                queries.get(0), queries.subList(1, queries.size()).toArray(
                        HistoricProcessInstanceQuery[]::new));
    }

    /**
     * 创建返回指定历史实例的链式查询。
     * @param instance HistoricProcessInstance，查询结果
     * @return HistoricProcessInstanceQuery，支持 processInstanceId/singleResult
     */
    private HistoricProcessInstanceQuery queryReturning(HistoricProcessInstance instance)
    {
        HistoricProcessInstanceQuery query = mock(HistoricProcessInstanceQuery.class);
        when(query.processInstanceId(anyString())).thenReturn(query);
        when(query.singleResult()).thenReturn(instance);
        return query;
    }

    /**
     * 创建返回直接子流程列表的链式查询。
     * @param instances List&lt;HistoricProcessInstance&gt;，直接子流程历史
     * @return HistoricProcessInstanceQuery，支持 superProcessInstanceId/listPage
     */
    private HistoricProcessInstanceQuery queryListing(List<HistoricProcessInstance> instances)
    {
        HistoricProcessInstanceQuery query = mock(HistoricProcessInstanceQuery.class);
        when(query.superProcessInstanceId(anyString())).thenReturn(query);
        when(query.listPage(anyInt(), anyInt())).thenReturn(instances);
        return query;
    }

    /**
     * 创建返回历史残留数量的链式查询。
     * @param count long，写后历史残留数
     * @return HistoricProcessInstanceQuery，支持 processInstanceIds/count
     */
    private HistoricProcessInstanceQuery queryCounting(long count)
    {
        HistoricProcessInstanceQuery query = mock(HistoricProcessInstanceQuery.class);
        when(query.processInstanceIds(any())).thenReturn(query);
        when(query.count()).thenReturn(count);
        return query;
    }

    /**
     * 创建已结束的 Flowable 历史实例替身。
     * @param instanceId String，流程实例主键
     * @param superProcessInstanceId String，可为空的父流程实例主键
     * @return HistoricProcessInstance，结束时间非空的历史实例
     */
    private HistoricProcessInstance finishedHistory(String instanceId,
            String superProcessInstanceId)
    {
        HistoricProcessInstance instance = mock(HistoricProcessInstance.class);
        when(instance.getId()).thenReturn(instanceId);
        when(instance.getSuperProcessInstanceId()).thenReturn(superProcessInstanceId);
        when(instance.getEndTime()).thenReturn(new Date());
        return instance;
    }

    /**
     * 断言删除链返回稳定 HTTP 409 业务冲突。
     * @param action Runnable，待执行历史删除动作
     * @param messagePart String，预期业务提示片段
     * @return void，异常类型、状态码或提示不一致时测试失败
     */
    private void assertConflict(Runnable action, String messagePart)
    {
        assertThatThrownBy(action::run)
                .isInstanceOf(ServiceException.class)
                .hasMessageContaining(messagePart)
                .satisfies(failure -> assertThat(((ServiceException) failure).getCode())
                        .isEqualTo(HttpStatus.CONFLICT));
    }
}
