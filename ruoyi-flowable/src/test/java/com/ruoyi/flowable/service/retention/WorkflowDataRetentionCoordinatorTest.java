package com.ruoyi.flowable.service.retention;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.time.Clock;
import java.time.Instant;
import java.time.LocalDateTime;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import org.junit.jupiter.api.Test;
import com.ruoyi.flowable.runtime.WorkflowDataRetentionMetrics;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;

class WorkflowDataRetentionCoordinatorTest
{
    private static final Clock CLOCK = Clock.fixed(Instant.parse("2026-01-01T00:00:00Z"), ZoneOffset.UTC);

    @Test
    void rejectsMissingAndDuplicateDomainOwners()
    {
        var registry = new SimpleMeterRegistry();
        try
        {
            var metrics = new WorkflowDataRetentionMetrics(registry);
            var cleaners = cleaners(new ArrayList<>(), List.of());
            assertThatThrownBy(() -> new WorkflowDataRetentionCoordinator(cleaners.subList(1, cleaners.size()), metrics, CLOCK))
                    .isInstanceOf(IllegalStateException.class).hasMessageContaining("缺少消费者");
            var duplicate = new ArrayList<>(cleaners);
            duplicate.add(cleaners.get(0));
            assertThatThrownBy(() -> new WorkflowDataRetentionCoordinator(duplicate, metrics, CLOCK))
                    .isInstanceOf(IllegalStateException.class).hasMessageContaining("重复配置");
        }
        finally { registry.close(); }
    }

    @Test
    void visitsAllDomainsAfterFailuresThenAggregatesAndCountsOnlyCommittedResults()
    {
        var registry = new SimpleMeterRegistry();
        try
        {
            var visited = new ArrayList<WorkflowDataRetentionDomain>();
            var failed = List.of(WorkflowDataRetentionDomain.RUNTIME_EVENT, WorkflowDataRetentionDomain.COPY);
            var coordinator = new WorkflowDataRetentionCoordinator(cleaners(visited, failed), new WorkflowDataRetentionMetrics(registry), CLOCK);
            assertThatThrownBy(coordinator::runScheduledBatch).isInstanceOfSatisfying(IllegalStateException.class, failure ->
                    assertThat(failure.getSuppressed()).hasSize(2));
            assertThat(visited).containsExactly(WorkflowDataRetentionDomain.values());
            assertThat(registry.get("workflow.data.retention.executions").tag("result", "failed").counter().count()).isOne();
            assertThat(registry.get("workflow.data.retention.executions").tag("result", "completed").counter().count()).isZero();
            for (var domain : WorkflowDataRetentionDomain.values())
            {
                assertThat(registry.get("workflow.data.retention.items").tags("domain", domain.metricTag(), "result", "deleted").counter().count())
                        .isEqualTo(failed.contains(domain) ? 0 : 2);
                assertThat(registry.get("workflow.data.retention.items").tags("domain", domain.metricTag(), "result", "failed").counter().count())
                        .isEqualTo(failed.contains(domain) ? 1 : 0);
            }
        }
        finally { registry.close(); }
    }

    @Test
    void successfulRoundUsesOneTimeAndPublishesAgeAndCompletion()
    {
        var registry = new SimpleMeterRegistry();
        try
        {
            var visited = new ArrayList<WorkflowDataRetentionDomain>();
            new WorkflowDataRetentionCoordinator(cleaners(visited, List.of()), new WorkflowDataRetentionMetrics(registry), CLOCK).runScheduledBatch();
            assertThat(visited).hasSize(12);
            assertThat(registry.get("workflow.data.retention.executions").tag("result", "completed").counter().count()).isOne();
            assertThat(registry.get("workflow.data.retention.oldest.age.seconds").tag("domain", "copy").gauge().value()).isEqualTo(3600);
        }
        finally { registry.close(); }
    }

    private List<WorkflowDataRetentionCleaner> cleaners(List<WorkflowDataRetentionDomain> visited, List<WorkflowDataRetentionDomain> failed)
    {
        return Arrays.stream(WorkflowDataRetentionDomain.values()).<WorkflowDataRetentionCleaner>map(domain -> new WorkflowDataRetentionCleaner()
        {
            public WorkflowDataRetentionDomain domain() { return domain; }
            public WorkflowDataRetentionBatchResult cleanBatch(LocalDateTime time)
            {
                assertThat(time).isEqualTo(LocalDateTime.now(CLOCK));
                visited.add(domain);
                if (failed.contains(domain)) throw new IllegalStateException(domain.name());
                return new WorkflowDataRetentionBatchResult(domain, 2, 2, 2, 0, time.minusHours(1));
            }
        }).toList();
    }
}
