package com.ruoyi.flowable.runtime;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.clearInvocations;
import static org.mockito.Mockito.doReturn;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;
import java.time.Duration;
import java.util.stream.Stream;
import org.flowable.job.service.JobServiceConfiguration;
import org.flowable.job.service.impl.asyncexecutor.AsyncExecutor;
import org.flowable.spring.SpringProcessEngineConfiguration;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.Arguments;
import org.junit.jupiter.params.provider.MethodSource;
import org.springframework.boot.health.contributor.Health;
import org.springframework.mock.env.MockEnvironment;
import com.ruoyi.flowable.config.WorkflowAttachmentProperties;
import com.ruoyi.flowable.config.WorkflowRuntimeProperties;
import com.ruoyi.flowable.mapper.WorkflowRuntimeMetricsMapper;
import com.ruoyi.flowable.service.attachment.WorkflowAttachmentStorage;

/** Health consumes one immutable snapshot; collection remains outside the request path. */
class WorkflowRuntimeHealthContractTest
{
    @ParameterizedTest(name = "{0}")
    @MethodSource("healthCases")
    void reportsStableReasonsThenRecovers(String name, WorkflowRuntimeHealthSnapshot snapshot,
            boolean expectedAsync, boolean expectedHistory, boolean requiredAsync, String reason)
    {
        var metrics = mock(WorkflowRuntimeMetrics.class);
        var env = new MockEnvironment()
                .withProperty("flowable.async-executor-activate", Boolean.toString(expectedAsync))
                .withProperty("flowable.async-history-executor-activate", Boolean.toString(expectedHistory))
                .withProperty("flowable.runtime.require-async-executor", Boolean.toString(requiredAsync));
        var runtime = new WorkflowRuntimeProperties();
        runtime.setMetricsSnapshotMaxAge(Duration.ofMinutes(1));
        var attachments = new WorkflowAttachmentProperties();
        attachments.setMinFreeBytes(100);
        var indicator = new WorkflowRuntimeHealthIndicator(env, metrics, runtime, attachments);
        when(metrics.readHealthSnapshot()).thenReturn(snapshot);

        Health health = indicator.health();
        assertThat(health.getStatus().getCode()).isEqualTo(reason == null ? "UP" : "DOWN");
        assertThat(health.getDetails().get("reason")).isEqualTo(reason);
        assertThat(health.getDetails()).containsEntry("metricsSnapshotAvailable", snapshot.available())
                .containsEntry("metricsSnapshotAgeMillis", snapshot.ageMillis());
        if (!snapshot.available())
        {
            assertThat(health.getDetails()).doesNotContainKeys("attachmentUsableBytes", "asyncExecutorActive");
        }
        if (reason != null)
        {
            env.setProperty("flowable.async-executor-activate", "true");
            when(metrics.readHealthSnapshot()).thenReturn(new WorkflowRuntimeHealthSnapshot(true, 0, 100, true, expectedHistory));
            Health recovered = indicator.health();
            assertThat(recovered.getStatus().getCode()).isEqualTo("UP");
            assertThat(recovered.getDetails()).doesNotContainKey("reason").containsEntry("timerExecutionReady", true);
        }
    }

    static Stream<Arguments> healthCases()
    {
        return Stream.of(
                Arguments.of("missing takes precedence", new WorkflowRuntimeHealthSnapshot(false, -1, 0, false, false), true, true, true, "workflow_runtime_snapshot_unavailable"),
                Arguments.of("stale takes precedence", new WorkflowRuntimeHealthSnapshot(true, 60_001, 0, false, false), true, true, true, "workflow_runtime_snapshot_stale"),
                Arguments.of("async unexpectedly stopped", new WorkflowRuntimeHealthSnapshot(true, 1, 100, false, false), true, false, false, "async_executor_state_mismatch"),
                Arguments.of("async unexpectedly started", new WorkflowRuntimeHealthSnapshot(true, 1, 100, true, false), false, false, false, "async_executor_state_mismatch"),
                Arguments.of("history state drift", new WorkflowRuntimeHealthSnapshot(true, 1, 100, false, false), false, true, false, "async_history_executor_state_mismatch"),
                Arguments.of("storage low watermark", new WorkflowRuntimeHealthSnapshot(true, 1, 99, false, false), false, false, false, "attachment_storage_low_watermark"),
                Arguments.of("ordinary async required", new WorkflowRuntimeHealthSnapshot(true, 1, 100, false, true), false, true, true, "async_executor_required_but_unavailable"),
                Arguments.of("age and free space boundaries", new WorkflowRuntimeHealthSnapshot(true, 60_000, 100, false, false), false, false, false, null),
                Arguments.of("both executors healthy", new WorkflowRuntimeHealthSnapshot(true, 0, 101, true, true), true, true, true, null));
    }

    @Test
    void dependencyExceptionsDoNotExposeTheirBodyInHealthDetails()
    {
        var metrics = mock(WorkflowRuntimeMetrics.class);
        when(metrics.readHealthSnapshot()).thenThrow(new IllegalStateException("password=private/path"));
        Health health = new WorkflowRuntimeHealthIndicator(new MockEnvironment(), metrics,
                new WorkflowRuntimeProperties(), new WorkflowAttachmentProperties()).health();
        assertThat(health.getStatus().getCode()).isEqualTo("DOWN");
        assertThat(health.getDetails()).containsOnlyKeys("reason")
                .containsEntry("reason", "workflow_runtime_snapshot_unavailable");
    }

    @Test
    void actualMetricsCacheIsTheOnlyRequestTimeInputAndFailedRefreshPreservesLastSuccess()
    {
        var mapper = mock(WorkflowRuntimeMetricsMapper.class);
        var storage = mock(WorkflowAttachmentStorage.class);
        var engine = mock(SpringProcessEngineConfiguration.class);
        var jobs = mock(JobServiceConfiguration.class);
        var executor = mock(AsyncExecutor.class);
        when(engine.getJobServiceConfiguration()).thenReturn(jobs);
        when(jobs.getAsyncExecutor()).thenReturn(executor);
        when(executor.isActive()).thenReturn(true);
        when(storage.usableSpace()).thenReturn(200L);
        when(mapper.selectRuntimeMetricValues()).thenReturn(new WorkflowRuntimeMetricValues(
                1, 2, 3, 4, 5, 6, 7, 8, 9, 10, 11, 12, 13, 14, 15));
        var metrics = new WorkflowRuntimeMetrics(mapper, engine, storage);
        var attachments = new WorkflowAttachmentProperties();
        attachments.setMinFreeBytes(100);
        var indicator = new WorkflowRuntimeHealthIndicator(new MockEnvironment()
                .withProperty("flowable.async-executor-activate", "true"), metrics,
                new WorkflowRuntimeProperties(), attachments);

        clearInvocations(mapper, storage, engine, jobs, executor);
        assertThat(indicator.health().getDetails()).containsEntry("reason", "workflow_runtime_snapshot_unavailable");
        verifyNoInteractions(mapper, storage, engine, jobs, executor);

        metrics.scheduleSnapshotRefresh();
        clearInvocations(mapper, storage, engine, jobs, executor);
        Health first = indicator.health();
        assertThat(first.getStatus().getCode()).isEqualTo("UP");
        assertThat(first.getDetails()).containsEntry("attachmentUsableBytes", 200L).containsEntry("asyncExecutorActive", true);
        verifyNoInteractions(mapper, storage, engine, jobs, executor);

        when(mapper.selectRuntimeMetricValues()).thenThrow(new IllegalStateException("offline"));
        when(storage.usableSpace()).thenReturn(0L);
        when(executor.isActive()).thenReturn(false);
        metrics.scheduleSnapshotRefresh();
        clearInvocations(mapper, storage, engine, jobs, executor);
        assertThat(indicator.health().getDetails()).containsEntry("attachmentUsableBytes", 200L)
                .containsEntry("asyncExecutorActive", true);
        verifyNoInteractions(mapper, storage, engine, jobs, executor);

        doReturn(new WorkflowRuntimeMetricValues(
                0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0))
                .when(mapper).selectRuntimeMetricValues();
        when(storage.usableSpace()).thenReturn(100L);
        when(executor.isActive()).thenReturn(true);
        metrics.scheduleSnapshotRefresh();
        clearInvocations(mapper, storage, engine, jobs, executor);
        assertThat(indicator.health().getStatus().getCode()).isEqualTo("UP");
        assertThat(indicator.health().getDetails()).containsEntry("attachmentUsableBytes", 100L);
        verifyNoInteractions(mapper, storage, engine, jobs, executor);
    }
}
