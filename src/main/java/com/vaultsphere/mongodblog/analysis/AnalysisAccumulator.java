package com.vaultsphere.mongodblog.analysis;

import com.vaultsphere.mongodblog.parser.ParseOutcome;
import com.vaultsphere.mongodblog.parser.ParseStatus;
import com.vaultsphere.mongodblog.parser.ParsedLogEntry;

import java.util.Collections;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

public final class AnalysisAccumulator {
    private final TopSlowQueryCollector topSlowQueries;
    private final DurationDistribution durationDistribution = new DurationDistribution();
    private final Map<String, MutableAggregate> operations = new LinkedHashMap<>();
    private final Map<String, MutableAggregate> namespaces = new LinkedHashMap<>();
    private final Map<String, MutableAggregate> patterns = new LinkedHashMap<>();
    private final Map<String, MutableAggregate> plans = new LinkedHashMap<>();
    private final Map<String, MutableAggregate> remotes = new LinkedHashMap<>();
    private final Map<String, MutableAggregate> cpuByOperationNamespace = new LinkedHashMap<>();
    private long totalLines;
    private long successLines;
    private long partialLines;
    private long skippedLines;
    private long failedLines;
    private long slowQueryCount;
    private long totalSlowDurationMillis;
    private long heartbeatFailures;
    private boolean cpuAvailable;

    public AnalysisAccumulator(int topCapacity) {
        this.topSlowQueries = new TopSlowQueryCollector(topCapacity);
    }

    public void accept(ParseOutcome outcome) {
        totalLines++;
        incrementStatus(outcome.status());
        outcome.entry().ifPresent(entry -> {
            if (entry.heartbeatFailure()) {
                heartbeatFailures++;
            }
            if (entry.slowQuery() && entry.durationMillis() != null) {
                acceptSlowQuery(entry);
            }
        });
    }

    public AnalysisSummary finish() {
        return new AnalysisSummary(
                totalLines, successLines, partialLines, skippedLines, failedLines,
                slowQueryCount, totalSlowDurationMillis, heartbeatFailures, cpuAvailable,
                durationDistribution.snapshot(),
                snapshot(operations, Integer.MAX_VALUE),
                snapshot(namespaces, 20),
                snapshot(patterns, 50),
                snapshot(plans, 10),
                snapshot(remotes, 20),
                snapshot(cpuByOperationNamespace, Integer.MAX_VALUE)
        );
    }

    public List<SlowQueryRecord> topSlowQueries() {
        return topSlowQueries.sorted();
    }

    private void incrementStatus(ParseStatus status) {
        switch (status) {
            case SUCCESS -> successLines++;
            case PARTIAL -> partialLines++;
            case SKIPPED -> skippedLines++;
            case FAILED -> failedLines++;
        }
    }

    private void acceptSlowQuery(ParsedLogEntry entry) {
        long duration = entry.durationMillis();
        slowQueryCount++;
        totalSlowDurationMillis += duration;
        durationDistribution.add(duration);

        add(operations, valueOrUnknown(entry.operation()), entry);
        add(namespaces, valueOrUnknown(entry.namespace()), entry);
        add(patterns, valueOrUnknown(entry.queryPattern()), entry);
        add(plans, valueOrUnknown(entry.planSummary()), entry);
        add(remotes, valueOrUnknown(entry.remote()), entry);
        if (entry.cpuNanos() != null) {
            cpuAvailable = true;
            add(cpuByOperationNamespace,
                    valueOrUnknown(entry.operation()) + "|" + valueOrUnknown(entry.namespace()), entry);
        }

        topSlowQueries.offer(new SlowQueryRecord(
                entry.fileIndex() + "-" + entry.lineNumber(),
                entry.fileIndex(), entry.lineNumber(), entry.timestampEpochMillis(),
                entry.operation(), entry.namespace(), duration, entry.cpuNanos(), entry.responseLength(),
                entry.planSummary(), entry.remote(), entry.queryPattern(), entry.rawLine(), entry.attributes()
        ));
    }

    private void add(Map<String, MutableAggregate> target, String key, ParsedLogEntry entry) {
        target.computeIfAbsent(key, ignored -> new MutableAggregate()).add(entry);
    }

    private Map<String, AggregateStat> snapshot(Map<String, MutableAggregate> source, int limit) {
        Map<String, AggregateStat> result = new LinkedHashMap<>();
        source.entrySet().stream()
                .sorted(Map.Entry.<String, MutableAggregate>comparingByValue(
                        Comparator.comparingLong(MutableAggregate::totalDurationMillis).reversed())
                        .thenComparing(Map.Entry.comparingByKey()))
                .limit(limit)
                .forEach(entry -> result.put(entry.getKey(), entry.getValue().snapshot()));
        return Collections.unmodifiableMap(result);
    }

    private String valueOrUnknown(String value) {
        return value == null || value.isBlank() ? "unknown" : value;
    }

    private static final class MutableAggregate {
        private long count;
        private long totalDurationMillis;
        private long minDurationMillis = Long.MAX_VALUE;
        private long maxDurationMillis;
        private long totalResponseBytes;
        private long totalCpuNanos;

        private void add(ParsedLogEntry entry) {
            long duration = entry.durationMillis();
            count++;
            totalDurationMillis += duration;
            minDurationMillis = Math.min(minDurationMillis, duration);
            maxDurationMillis = Math.max(maxDurationMillis, duration);
            totalResponseBytes += entry.responseLength() == null ? 0 : entry.responseLength();
            totalCpuNanos += entry.cpuNanos() == null ? 0 : entry.cpuNanos();
        }

        private long totalDurationMillis() {
            return totalDurationMillis;
        }

        private AggregateStat snapshot() {
            return new AggregateStat(
                    count,
                    totalDurationMillis,
                    count == 0 ? 0 : totalDurationMillis * 1.0 / count,
                    count == 0 ? 0 : minDurationMillis,
                    maxDurationMillis,
                    totalResponseBytes,
                    totalCpuNanos
            );
        }
    }
}
