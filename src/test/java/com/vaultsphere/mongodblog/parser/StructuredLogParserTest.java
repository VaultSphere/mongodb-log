package com.vaultsphere.mongodblog.parser;

import org.junit.jupiter.api.Test;

import java.time.Instant;

import static org.assertj.core.api.Assertions.assertThat;

class StructuredLogParserTest {

    private static final String SLOW_QUERY = """
            {"t":{"$date":"2024-06-01T10:15:30.123+00:00"},"s":"I","c":"COMMAND","id":51803,"ctx":"conn12","msg":"Slow query","attr":{"type":"command","ns":"sales.orders","command":{"find":"orders","filter":{"status":"OPEN","amount":{"$gt":100}},"$db":"sales"},"planSummary":"IXSCAN { status: 1 }","durationMillis":742,"cpuNanos":2100000,"reslen":5120,"remote":"10.0.0.8:41712"}}
            """.trim();

    private final StructuredLogParser parser = new StructuredLogParser(new QueryPatternNormalizer());

    @Test
    void parsesStructuredSlowQueryFields() {
        ParseOutcome outcome = parser.parse(SLOW_QUERY, 27, 2);

        assertThat(outcome.status()).isEqualTo(ParseStatus.SUCCESS);
        ParsedLogEntry entry = outcome.entry().orElseThrow();
        assertThat(entry.timestampEpochMillis()).isEqualTo(Instant.parse("2024-06-01T10:15:30.123Z").toEpochMilli());
        assertThat(entry.severity()).isEqualTo("I");
        assertThat(entry.component()).isEqualTo("COMMAND");
        assertThat(entry.messageId()).isEqualTo(51803);
        assertThat(entry.context()).isEqualTo("conn12");
        assertThat(entry.message()).isEqualTo("Slow query");
        assertThat(entry.namespace()).isEqualTo("sales.orders");
        assertThat(entry.operation()).isEqualTo("find");
        assertThat(entry.durationMillis()).isEqualTo(742L);
        assertThat(entry.cpuNanos()).isEqualTo(2_100_000L);
        assertThat(entry.responseLength()).isEqualTo(5_120L);
        assertThat(entry.planSummary()).isEqualTo("IXSCAN { status: 1 }");
        assertThat(entry.remote()).isEqualTo("10.0.0.8:41712");
        assertThat(entry.queryPattern()).isEqualTo("{\"amount\":{\"$gt\":\"?\"},\"status\":\"?\"}");
        assertThat(entry.slowQuery()).isTrue();
        assertThat(entry.fileIndex()).isEqualTo(2);
        assertThat(entry.lineNumber()).isEqualTo(27);
        assertThat(entry.rawLine()).isEqualTo(SLOW_QUERY);
    }

    @Test
    void acceptsBomAndWhitespaceWithoutChangingRawLine() {
        String line = "\uFEFF  " + SLOW_QUERY + "  ";

        ParseOutcome outcome = parser.parse(line, 1, 0);

        assertThat(outcome.status()).isEqualTo(ParseStatus.SUCCESS);
        assertThat(outcome.entry().orElseThrow().rawLine()).isEqualTo(line);
    }

    @Test
    void acceptsMissingOptionalSlowQueryFields() {
        String line = """
                {"t":{"$date":"2024-06-01T10:15:30Z"},"s":"I","c":"COMMAND","id":1,"ctx":"conn1","msg":"Slow query","attr":{"ns":"db.items","durationMillis":120}}
                """.trim();

        ParseOutcome outcome = parser.parse(line, 3, 0);

        assertThat(outcome.status()).isEqualTo(ParseStatus.SUCCESS);
        ParsedLogEntry entry = outcome.entry().orElseThrow();
        assertThat(entry.operation()).isEqualTo("command");
        assertThat(entry.queryPattern()).isEqualTo("{}");
        assertThat(entry.cpuNanos()).isNull();
        assertThat(entry.responseLength()).isNull();
    }

    @Test
    void reportsDamagedStructuredJson() {
        ParseOutcome outcome = parser.parse("{\"t\": {\"$date\": \"2024-06-01T10:15:30Z\"}", 4, 0);

        assertThat(outcome.status()).isEqualTo(ParseStatus.FAILED);
        assertThat(outcome.entry()).isEmpty();
        assertThat(outcome.errorCode()).isEqualTo("INVALID_STRUCTURED_JSON");
        assertThat(outcome.errorMessage()).isNotBlank();
    }
}
