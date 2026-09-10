package com.vaultsphere.mongodblog.analysis;

import java.util.List;
import java.util.Map;

public record AnalysisSummary(
        long totalLines,
        long successLines,
        long partialLines,
        long skippedLines,
        long failedLines,
        long slowQueryCount,
        long totalSlowDurationMillis,
        long heartbeatFailures,
        boolean cpuAvailable,
        List<DurationBucketStat> durationDistribution,
        Map<String, AggregateStat> operations,
        Map<String, AggregateStat> namespaces,
        Map<String, AggregateStat> patterns,
        Map<String, AggregateStat> plans,
        Map<String, AggregateStat> remotes,
        Map<String, AggregateStat> cpuByOperationNamespace
) {
}

