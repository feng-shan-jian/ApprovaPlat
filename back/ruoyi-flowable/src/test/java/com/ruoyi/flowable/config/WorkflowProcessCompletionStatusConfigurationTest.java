package com.ruoyi.flowable.config;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import java.util.List;
import org.flowable.common.engine.api.delegate.event.FlowableEventListener;
import org.flowable.spring.SpringProcessEngineConfiguration;
import org.flowable.spring.boot.ProcessEngineConfigurationConfigurer;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import com.ruoyi.flowable.service.process.WorkflowProcessCompletionStatusListener;

/**
 * WorkflowProcessCompletionStatusConfiguration 的监听器注册测试。
 */
class WorkflowProcessCompletionStatusConfigurationTest
{
    /**
     * 验证配置器保留已有监听器并追加自然完成状态监听器。
     *
     * @return void，无返回值；覆盖已有监听器或漏注册状态监听器时测试失败
     */
    @Test
    void appendsCompletionListenerWithoutReplacingExistingListeners()
    {
        WorkflowProcessCompletionStatusConfiguration configuration =
                new WorkflowProcessCompletionStatusConfiguration();
        WorkflowProcessCompletionStatusListener completionListener =
                configuration.workflowProcessCompletionStatusListener();
        FlowableEventListener existingListener = mock(FlowableEventListener.class);
        SpringProcessEngineConfiguration engineConfiguration =
                mock(SpringProcessEngineConfiguration.class);
        when(engineConfiguration.getEventListeners()).thenReturn(List.of(existingListener));

        ProcessEngineConfigurationConfigurer configurer = configuration
                .workflowProcessCompletionStatusConfigurer(completionListener);
        configurer.configure(engineConfiguration);

        @SuppressWarnings("unchecked")
        ArgumentCaptor<List<FlowableEventListener>> listeners = ArgumentCaptor.forClass(List.class);
        verify(engineConfiguration).setEventListeners(listeners.capture());
        assertThat(listeners.getValue()).containsExactly(existingListener, completionListener);
    }
}
