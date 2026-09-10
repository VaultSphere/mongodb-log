package com.vaultsphere.mongodblog.analysis;

import com.vaultsphere.mongodblog.parser.ParseOutcome;
import com.vaultsphere.mongodblog.parser.ParsedLogEntry;
import org.junit.jupiter.api.Test;

import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

class AnalysisAccumulatorTest {

    @Test
    void aggregatesEverySlowQueryWhileLimitingOnlyDetails() {
        AnalysisAccumulator accumulator = new AnalysisAccumulator(2);

        accumulator.accept(ParseOutcome.success(entry(1, "find", "db.a", 100L, 10L, 20L, "p1", "IXSCAN", "10.0.0.1", false)));
        accumulator.accept(ParseOutcome.partial(entry(2, "find", "db.a", 200L, 20L, 30L, "p1", "IXSCAN", "10.0.0.1", false), "PARTIAL", "partial"));
        accumulator.accept(ParseOutcome.success(entry(3, "update", "db.b", 500L, null, 40L, "p2", "COLLSCAN", "10.0.0.2", false)));
        accumulator.accept(ParseOutcome.success(entry(4, "network", null, null, null, null, "{}", null, null, true)));
        accumulator.accept(ParseOutcome.skipped("EMPTY_LINE", "empty"));
        accumulator.accept(ParseOutcome.failed("INVALID", "bad"));

        AnalysisSummary summary = accumulator.finish();

        assertThat(summary.totalLines()).isEqualTo(6);
        assertThat(summary.successLines()).isEqualTo(3);
        assertThat(summary.partialLines()).isEqualTo(1);
        assertThat(summary.skippedLines()).isEqualTo(1);
        assertThat(summary.failedLines()).isEqualTo(1);
        assertThat(summary.slowQueryCount()).isEqualTo(3);
        assertThat(summary.totalSlowDurationMillis()).isEqualTo(800);
        assertThat(summary.heartbeatFailures()).isEqualTo(1);
        assertThat(summary.cpuAvailable()).isTrue();
        assertThat(summary.operations().get("find").count()).isEqualTo(2);
        assertThat(summary.operations().get("find").totalDurationMillis()).isEqualTo(300);
        assertThat(summary.namespaces().get("db.a").totalResponseBytes()).isEqualTo(50);
        assertThat(summary.patterns().get("p1").count()).isEqualTo(2);
        assertThat(summary.plans().get("IXSCAN").count()).isEqualTo(2);
        assertThat(summary.remotes().get("10.0.0.1").count()).isEqualTo(2);
        assertThat(summary.cpuByOperationNamespace().get("find|db.a").totalCpuNanos()).isEqualTo(30);
        assertThat(summary.durationDistribution()).extracting(DurationBucketStat::count)
                .containsExactly(0L, 2L, 1L, 0L, 0L, 0L, 0L, 0L);
        assertThat(accumulator.topSlowQueries()).extracting(SlowQueryRecord::durationMillis)
                .containsExactly(500L, 200L);
    }

    private ParsedLogEntry entry(
            long line,
            String operation,
            String namespace,
            Long duration,
            Long cpu,
            Long responseLength,
            String pattern,
            String plan,
            String remote,
            boolean heartbeat
    ) {
        return new ParsedLogEntry(
                line, 0, 1_000 + line, "I", "COMMAND", null, "conn", "message",
                namespace, operation, duration, cpu, responseLength, plan, remote, pattern,
                "raw-" + line, Map.of(), duration != null, heartbeat
        );
    }
}
