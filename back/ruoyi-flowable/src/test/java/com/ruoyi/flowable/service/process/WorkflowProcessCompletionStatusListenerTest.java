package com.ruoyi.flowable.service.process;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyBoolean;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.mockStatic;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import java.util.Date;
import java.util.List;
import java.util.Set;
import org.flowable.common.engine.api.FlowableException;
import org.flowable.common.engine.api.delegate.event.FlowableEngineEntityEvent;
import org.flowable.common.engine.api.delegate.event.FlowableEngineEventType;
import org.flowable.common.engine.impl.runtime.Clock;
import org.flowable.engine.impl.cfg.ProcessEngineConfigurationImpl;
import org.flowable.engine.impl.persistence.entity.ExecutionEntity;
import org.flowable.engine.impl.util.CommandContextUtil;
import org.flowable.variable.service.VariableServiceConfiguration;
import org.flowable.variable.service.impl.persistence.entity.HistoricVariableInstanceEntity;
import org.flowable.variable.service.impl.persistence.entity.HistoricVariableInstanceEntityManager;
import org.junit.jupiter.api.Test;
import org.mockito.MockedStatic;
import org.springframework.beans.factory.ObjectProvider;
import com.ruoyi.flowable.service.task.WorkflowAutomaticCopyService;

/**
 * WorkflowProcessCompletionStatusListener 的自然完成状态一致性测试。
 */
class WorkflowProcessCompletionStatusListenerTest
{
    private static final String INSTANCE_ID = "instance-1";

    /**
     * 验证监听器只处理自然完成事件并让内部失败回滚引擎命令。
     *
     * @return void，无返回值；事件范围或失败策略变化时测试失败
     */
    @Test
    void listensOnlyForProcessCompletedAndFailsTransactionOnError()
    {
        WorkflowProcessCompletionStatusListener listener =
                new WorkflowProcessCompletionStatusListener();

        assertThat(listener.getTypes()).hasSize(1);
        assertThat(listener.getTypes().iterator().next())
                .isEqualTo(FlowableEngineEventType.PROCESS_COMPLETED);
        assertThat(listener.isFailOnException()).isTrue();
    }

    /**
     * 验证流程自然结束后在当前 Flowable 命令内把正式历史变量更新为 completed。
     *
     * @return void，无返回值；历史变量值、更新时间或实体更新缺失时测试失败
     */
    @Test
    void updatesExistingHistoricStatusWhenProcessCompletes()
    {
        HistoricVariableInstanceEntity statusVariable = statusVariable("running", "string");
        try (ListenerFixture fixture = fixture(List.of(statusVariable)))
        {
            fixture.listener().onEvent(fixture.event());

            verify(statusVariable).setTextValue(WorkflowProcessCompletionStatusListener.COMPLETED_STATUS);
            verify(statusVariable).setTextValue2(null);
            verify(statusVariable).setLongValue(null);
            verify(statusVariable).setDoubleValue(null);
            verify(statusVariable).setCachedValue(WorkflowProcessCompletionStatusListener.COMPLETED_STATUS);
            verify(statusVariable).setLastUpdatedTime(fixture.completedTime());
            verify(fixture.variableManager()).update(statusVariable, false);
        }
    }

    /**
     * 验证只有自然完成实例会在状态收敛后调用同事务自动抄送服务。
     * @return void，服务未调用、提前调用或实例定义关联丢失时测试失败
     */
    @Test
    void publishesAutomaticCopyOnlyForNaturalCompletion()
    {
        @SuppressWarnings("unchecked")
        ObjectProvider<WorkflowAutomaticCopyService> provider = mock(ObjectProvider.class);
        WorkflowAutomaticCopyService automaticCopyService = mock(WorkflowAutomaticCopyService.class);
        when(provider.getObject()).thenReturn(automaticCopyService);
        HistoricVariableInstanceEntity statusVariable = statusVariable("running", "string");

        try (ListenerFixture fixture = fixture(List.of(statusVariable), provider))
        {
            fixture.listener().onEvent(fixture.event());

            verify(fixture.variableManager()).update(statusVariable, false);
            verify(automaticCopyService).onProcessCompleted(INSTANCE_ID, "definition-1");
        }
    }

    /**
     * 验证流程完成事件不会覆盖取消、终止或已完成的正式业务终态。
     *
     * @return void，无返回值；任一显式终态被更新时测试失败
     */
    @Test
    void preservesExplicitTerminalStatusWhenProcessCompletes()
    {
        for (String terminalStatus : List.of("canceled", "rejected", "terminated",
                WorkflowProcessCompletionStatusListener.COMPLETED_STATUS))
        {
            @SuppressWarnings("unchecked")
            ObjectProvider<WorkflowAutomaticCopyService> provider = mock(ObjectProvider.class);
            HistoricVariableInstanceEntity statusVariable =
                    statusVariable(terminalStatus, "string");
            try (ListenerFixture fixture = fixture(List.of(statusVariable), provider))
            {
                fixture.listener().onEvent(fixture.event());

                verify(fixture.variableManager(), never()).update(any(), anyBoolean());
                verify(statusVariable, never()).setTextValue(any());
                // 撤回/取消、驳回和管理员终止均不得借引擎结束事件产生流程完成抄送。
                verify(provider, never()).getObject();
            }
        }
    }

    /**
     * 验证完成事件遇到非运行、非终态值时回滚，避免静默掩盖状态损坏。
     *
     * @return void，无返回值；异常状态被覆盖或提交时测试失败
     */
    @Test
    void rejectsUnexpectedHistoricStatusValue()
    {
        HistoricVariableInstanceEntity statusVariable = statusVariable("suspended", "string");
        try (ListenerFixture fixture = fixture(List.of(statusVariable)))
        {
            assertThatThrownBy(() -> fixture.listener().onEvent(fixture.event()))
                    .isInstanceOf(FlowableException.class)
                    .hasMessageContaining("状态变量值异常");
            verify(fixture.variableManager(), never()).update(any(), anyBoolean());
        }
    }

    /**
     * 验证没有 processStatus 的存量实例仍可自然结束，不会伪造来源不明的历史变量。
     *
     * @return void，无返回值；兼容分支错误写入历史实体时测试失败
     */
    @Test
    void keepsLegacyProcessWithoutStatusVariableCompatible()
    {
        try (ListenerFixture fixture = fixture(List.of()))
        {
            fixture.listener().onEvent(fixture.event());

            verify(fixture.variableManager(), never()).update(any(), anyBoolean());
        }
    }

    /**
     * 验证损坏的非字符串状态变量会阻止自然完成事务提交。
     *
     * @return void，无返回值；错误类型被静默覆盖时测试失败
     */
    @Test
    void rejectsCorruptedStatusVariableType()
    {
        try (ListenerFixture fixture = fixture(List.of(statusVariable(null, "integer"))))
        {
            assertThatThrownBy(() -> fixture.listener().onEvent(fixture.event()))
                    .isInstanceOf(FlowableException.class)
                    .hasMessageContaining("状态变量类型异常");
            verify(fixture.variableManager(), never()).update(any(), anyBoolean());
        }
    }

    /**
     * 创建包含 Flowable 当前命令静态上下文的监听器测试夹具。
     *
     * @param statusVariables List&lt;HistoricVariableInstanceEntity&gt;，按实例查询到的历史状态变量
     * @return ListenerFixture，关闭时同步释放当前测试独占的静态 Mock
     */
    private ListenerFixture fixture(List<HistoricVariableInstanceEntity> statusVariables)
    {
        return fixture(statusVariables, null);
    }

    /**
     * 创建可核验自动抄送调用的 Flowable 当前命令静态上下文测试夹具。
     * @param statusVariables List&lt;HistoricVariableInstanceEntity&gt;，按实例查询到的历史状态变量
     * @param automaticCopyServiceProvider ObjectProvider&lt;WorkflowAutomaticCopyService&gt;，自然完成后延迟解析的服务提供器，可为空
     * @return ListenerFixture，关闭时同步释放当前测试独占的静态 Mock
     */
    private ListenerFixture fixture(List<HistoricVariableInstanceEntity> statusVariables,
            ObjectProvider<WorkflowAutomaticCopyService> automaticCopyServiceProvider)
    {
        WorkflowProcessCompletionStatusListener listener =
                new WorkflowProcessCompletionStatusListener(automaticCopyServiceProvider);
        FlowableEngineEntityEvent event = mock(FlowableEngineEntityEvent.class);
        ExecutionEntity processInstance = mock(ExecutionEntity.class);
        ProcessEngineConfigurationImpl engineConfiguration =
                mock(ProcessEngineConfigurationImpl.class);
        VariableServiceConfiguration variableConfiguration =
                mock(VariableServiceConfiguration.class);
        HistoricVariableInstanceEntityManager variableManager =
                mock(HistoricVariableInstanceEntityManager.class);
        Clock clock = mock(Clock.class);
        Date completedTime = new Date(1_721_987_200_000L);

        when(event.getType()).thenReturn(FlowableEngineEventType.PROCESS_COMPLETED);
        when(event.getEntity()).thenReturn(processInstance);
        when(processInstance.isProcessInstanceType()).thenReturn(true);
        when(processInstance.getId()).thenReturn(INSTANCE_ID);
        when(processInstance.getProcessDefinitionId()).thenReturn("definition-1");
        when(engineConfiguration.getVariableServiceConfiguration())
                .thenReturn(variableConfiguration);
        when(engineConfiguration.getClock()).thenReturn(clock);
        when(variableConfiguration.getHistoricVariableInstanceEntityManager())
                .thenReturn(variableManager);
        when(variableManager.findHistoricalVariableInstancesByProcessInstanceId(
                INSTANCE_ID, Set.of(WorkflowProcessStartService.PROCESS_STATUS_VARIABLE)))
                .thenReturn(statusVariables);
        when(clock.getCurrentTime()).thenReturn(completedTime);

        MockedStatic<CommandContextUtil> commandContext = mockStatic(CommandContextUtil.class);
        commandContext.when(CommandContextUtil::getProcessEngineConfiguration)
                .thenReturn(engineConfiguration);
        return new ListenerFixture(listener, event, variableManager, completedTime,
                commandContext);
    }

    /**
     * 创建指定当前值和 Flowable 类型名的历史变量实体 Mock。
     *
     * @param value String，历史变量当前文本值，允许为空
     * @param variableType String，Flowable 历史变量类型名
     * @return HistoricVariableInstanceEntity，可供监听器更新并验证的实体 Mock
     */
    private HistoricVariableInstanceEntity statusVariable(String value, String variableType)
    {
        HistoricVariableInstanceEntity statusVariable =
                mock(HistoricVariableInstanceEntity.class);
        when(statusVariable.getTextValue()).thenReturn(value);
        when(statusVariable.getVariableTypeName()).thenReturn(variableType);
        return statusVariable;
    }

    /**
     * 封装单次监听器测试使用的事件、历史实体和静态命令上下文。
     *
     * @param listener WorkflowProcessCompletionStatusListener，被测监听器
     * @param event FlowableEngineEntityEvent，自然完成事件
     * @param variableManager HistoricVariableInstanceEntityManager，Flowable 历史变量实体管理器
     * @param completedTime Date，引擎时钟提供的完成时间
     * @param commandContext MockedStatic&lt;CommandContextUtil&gt;，当前测试独占的静态 Mock
     */
    private record ListenerFixture(
            WorkflowProcessCompletionStatusListener listener,
            FlowableEngineEntityEvent event,
            HistoricVariableInstanceEntityManager variableManager,
            Date completedTime,
            MockedStatic<CommandContextUtil> commandContext) implements AutoCloseable
    {
        /**
         * 关闭当前测试独占的 Flowable 静态命令上下文 Mock。
         *
         * @return void，无返回值
         */
        @Override
        public void close()
        {
            commandContext.close();
        }
    }
}
