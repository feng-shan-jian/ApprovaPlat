package com.ruoyi.flowable.runtime;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.*;
import com.ruoyi.flowable.config.WorkflowRuntimeProperties;
import com.ruoyi.flowable.config.WorkflowAttachmentProperties;
import org.junit.jupiter.api.Test;
import org.springframework.mock.env.MockEnvironment;

class WorkflowTimerReadinessTest
{
    @Test void matchingDisabledFlagsDoNotReportTimersReadyAndRequiredEnvironmentFailsClosed()
    {
        var metrics = mock(WorkflowRuntimeMetrics.class);
        when(metrics.readHealthSnapshot()).thenReturn(new WorkflowRuntimeHealthSnapshot(true, 0, Long.MAX_VALUE, false, false));
        var environment = new MockEnvironment();
        var health = new WorkflowRuntimeHealthIndicator(environment, metrics, new WorkflowRuntimeProperties(), new WorkflowAttachmentProperties());
        assertThat(health.health().getDetails()).containsEntry("timerExecutionReady", false);
        environment.setProperty("flowable.runtime.require-async-executor", "true");
        assertThat(health.health().getStatus().getCode()).isEqualTo("DOWN");
        environment.setProperty("flowable.async-executor-activate", "true");
        when(metrics.readHealthSnapshot()).thenReturn(new WorkflowRuntimeHealthSnapshot(true, 0, Long.MAX_VALUE, true, false));
        assertThat(health.health().getStatus().getCode()).isEqualTo("UP");
        assertThat(health.health().getDetails()).containsEntry("timerExecutionReady", true);
        when(metrics.readHealthSnapshot()).thenReturn(new WorkflowRuntimeHealthSnapshot(true, 600000, Long.MAX_VALUE, true, false));
        assertThat(health.health().getDetails()).containsEntry("timerExecutionReady", false);
    }
}
