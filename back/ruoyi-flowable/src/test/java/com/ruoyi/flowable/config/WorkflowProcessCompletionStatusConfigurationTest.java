package com.ruoyi.flowable.config;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import java.util.List;
import org.flowable.common.engine.api.delegate.event.FlowableEventListener;
import org.flowable.spring.SpringProcessEngineConfiguration;
import org.flowable.spring.boot.ProcessEngineConfigurationConfigurer;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.beans.factory.ObjectProvider;
import com.ruoyi.flowable.service.notification.WorkflowNotificationService;
import com.ruoyi.flowable.service.process.WorkflowProcessCompletionStatusListener;
import com.ruoyi.flowable.service.task.WorkflowAutomaticCopyService;

/**
 * WorkflowProcessCompletionStatusConfiguration 的监听器注册测试。
 */
class WorkflowProcessCompletionStatusConfigurationTest
{
    /**
     * 验证配置器保留已有监听器、追加自然完成状态监听器，且引擎初始化阶段不解析自动抄送服务。
     *
     * @return void，无返回值；覆盖已有监听器、漏注册监听器或提前创建引擎服务依赖时测试失败
     */
    @Test
    void appendsCompletionListenerWithoutReplacingExistingListeners()
    {
        WorkflowProcessCompletionStatusConfiguration configuration =
                new WorkflowProcessCompletionStatusConfiguration();
        @SuppressWarnings("unchecked")
        ObjectProvider<WorkflowAutomaticCopyService> automaticCopyServiceProvider =
                mock(ObjectProvider.class);
        @SuppressWarnings("unchecked")
        ObjectProvider<WorkflowNotificationService> notificationServiceProvider =
                mock(ObjectProvider.class);
        configuration.setNotificationServiceProvider(notificationServiceProvider);
        WorkflowProcessCompletionStatusListener completionListener =
                configuration.workflowProcessCompletionStatusListener(
                        automaticCopyServiceProvider);
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
        verify(automaticCopyServiceProvider, never()).getObject();
    }
}
