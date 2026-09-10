package com.vaultsphere.mongodblog.parser;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.Arguments;
import org.junit.jupiter.params.provider.MethodSource;

import java.time.OffsetDateTime;
import java.util.stream.Stream;

import static org.assertj.core.api.Assertions.assertThat;

class LegacyLogParserTest {

    private final LegacyLogParser parser = new LegacyLogParser(new QueryPatternNormalizer());

    static Stream<Arguments> slowOperations() {
        return Stream.of(
                Arguments.of(
                        "COMMAND", "command db.items command: find { find: \"items\", filter: { a: 1 } } planSummary: IXSCAN reslen:20 150ms",
                        "find", "db.items", 150L, "IXSCAN", "{\"a\":\"?\"}"),
                Arguments.of(
                        "COMMAND", "command db.items command: aggregate { aggregate: \"items\", pipeline: [ { $match: { state: \"A\" } } ], cursor: {} } planSummary: COLLSCAN 500ms",
                        "aggregate", "db.items", 500L, "COLLSCAN", "[{\"$match\":{\"state\":\"?\"}}]"),
                Arguments.of(
                        "COMMAND", "command db.items command: insert { insert: \"items\", documents: [ { a: 1 } ] } reslen:12 220ms",
                        "insert", "db.items", 220L, null, "{}"),
                Arguments.of(
                        "WRITE", "update db.items command: { q: { a: 1 }, u: { $set: { b: 2 } } } planSummary: IXSCAN 275ms",
                        "update", "db.items", 275L, "IXSCAN", "{\"a\":\"?\"}"),
                Arguments.of(
                        "WRITE", "remove db.items command: { q: { b: 1 }, limit: 1 } planSummary: COLLSCAN 650ms",
                        "remove", "db.items", 650L, "COLLSCAN", "{\"b\":\"?\"}"),
                Arguments.of(
                        "COMMAND", "command db.items command: getMore { getMore: 99, collection: \"items\" } originatingCommand: { find: \"items\", filter: { owner: \"hp\" } } planSummary: IXSCAN reslen:99 725ms",
                        "getMore", "db.items", 725L, "IXSCAN", "{\"owner\":\"?\"}"),
                Arguments.of(
                        "COMMAND", "command db.items command: findAndModify { findAndModify: \"items\", query: { state: \"A\" }, update: { $set: { state: \"B\" } } } planSummary: IXSCAN 850ms",
                        "findAndModify", "db.items", 850L, "IXSCAN", "{\"state\":\"?\"}"),
                Arguments.of(
                        "COMMAND", "command db.$cmd command: createIndexes { createIndexes: \"items\", indexes: [ { name: \"a_1\", key: { a: 1 } } ], $db: \"db\" } reslen:223 1200ms",
                        "createIndexes", "db.items", 1200L, null, "[{\"key\":{\"a\":\"?\"},\"name\":\"?\"}]")
        );
    }

    @ParameterizedTest
    @MethodSource("slowOperations")
    void parsesSupportedSlowOperations(
            String component,
            String message,
            String operation,
            String namespace,
            long duration,
            String planSummary,
            String pattern
    ) {
        String line = "2025-03-10T13:28:38.624+0800 I " + component + "  [conn49] " + message;

        ParseOutcome outcome = parser.parse(line, 9, 1);

        assertThat(outcome.status()).isEqualTo(ParseStatus.SUCCESS);
        ParsedLogEntry entry = outcome.entry().orElseThrow();
        assertThat(entry.timestampEpochMillis()).isEqualTo(OffsetDateTime.parse("2025-03-10T13:28:38.624+08:00").toInstant().toEpochMilli());
        assertThat(entry.operation()).isEqualTo(operation);
        assertThat(entry.namespace()).isEqualTo(namespace);
        assertThat(entry.durationMillis()).isEqualTo(duration);
        assertThat(entry.planSummary()).isEqualTo(planSummary);
        assertThat(entry.queryPattern()).isEqualTo(pattern);
        assertThat(entry.slowQuery()).isTrue();
        assertThat(entry.context()).isEqualTo("conn49");
    }

    @Test
    void parsesNetworkConnectionWithoutTreatingItAsSlowQuery() {
        String line = "2025-03-10T14:14:17.729+0800 I NETWORK [listener] connection accepted from 192.168.12.100:44439 #298462 (39 connections now open)";

        ParsedLogEntry entry = parser.parse(line, 1, 0).entry().orElseThrow();

        assertThat(entry.remote()).isEqualTo("192.168.12.100");
        assertThat(entry.operation()).isEqualTo("network");
        assertThat(entry.slowQuery()).isFalse();
    }

    @Test
    void detectsHeartbeatFailuresAndClassifiesEmptyAndMalformedLines() {
        String heartbeat = "2025-03-10T14:14:17.729+0800 I NETWORK [monitor] Heartbeat failed after 1000ms";

        assertThat(parser.parse(heartbeat, 1, 0).entry().orElseThrow().heartbeatFailure()).isTrue();
        assertThat(parser.parse("   ", 2, 0).status()).isEqualTo(ParseStatus.SKIPPED);
        assertThat(parser.parse("not a mongo log", 3, 0).status()).isEqualTo(ParseStatus.SKIPPED);
        assertThat(parser.parse("2025-03-10T14:14:17.729+0800 broken", 4, 0).status()).isEqualTo(ParseStatus.FAILED);
    }

    @Test
    void compositeParserRoutesStructuredAndLegacyLines() {
        CompositeLogParser composite = new CompositeLogParser(
                new StructuredLogParser(new QueryPatternNormalizer()),
                parser
        );
        String structured = "{\"t\":{\"$date\":\"2025-03-10T06:14:17.729Z\"},\"s\":\"I\",\"c\":\"NETWORK\",\"msg\":\"Listening\"}";
        String legacy = "2025-03-10T14:14:17.729+0800 I NETWORK [listener] Listening on 127.0.0.1";

        assertThat(composite.parse(structured, 1, 0).entry().orElseThrow().component()).isEqualTo("NETWORK");
        assertThat(composite.parse(legacy, 2, 0).entry().orElseThrow().component()).isEqualTo("NETWORK");
    }
}
