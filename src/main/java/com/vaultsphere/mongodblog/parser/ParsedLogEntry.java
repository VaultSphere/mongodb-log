package com.vaultsphere.mongodblog.parser;

import java.util.Map;

public record ParsedLogEntry(
        long lineNumber,
        int fileIndex,
        long timestampEpochMillis,
        String severity,
        String component,
        Integer messageId,
        String context,
        String message,
        String namespace,
        String operation,
        Long durationMillis,
        Long cpuNanos,
        Long responseLength,
        String planSummary,
        String remote,
        String queryPattern,
        String rawLine,
        Map<String, Object> attributes,
        boolean slowQuery,
        boolean heartbeatFailure
) {
}

