package com.vaultsphere.mongodblog.parser;

import org.bson.Document;

import java.time.Instant;
import java.time.OffsetDateTime;
import java.util.Date;
import java.util.List;
import java.util.Map;

public final class StructuredLogParser implements LogParser {
    private static final List<String> COMMAND_OPERATIONS = List.of(
            "find", "aggregate", "insert", "update", "delete", "getMore",
            "findAndModify", "createIndexes", "addShard", "replSetInitiate", "createUser", "moveChunk"
    );

    private final QueryPatternNormalizer patternNormalizer;

    public StructuredLogParser(QueryPatternNormalizer patternNormalizer) {
        this.patternNormalizer = patternNormalizer;
    }

    @Override
    public ParseOutcome parse(String line, long lineNumber, int fileIndex) {
        if (line == null || stripBom(line).trim().isEmpty()) {
            return ParseOutcome.skipped("EMPTY_LINE", "空行");
        }
        String normalizedLine = stripBom(line).trim();
        if (!normalizedLine.startsWith("{")) {
            return ParseOutcome.skipped("NOT_STRUCTURED", "不是结构化 JSON 日志");
        }

        Document root;
        try {
            root = Document.parse(normalizedLine);
        } catch (RuntimeException e) {
            return ParseOutcome.failed("INVALID_STRUCTURED_JSON", conciseMessage(e));
        }

        Long timestamp = timestamp(root.get("t"));
        if (timestamp == null) {
            return ParseOutcome.failed("MISSING_TIMESTAMP", "结构化日志缺少有效的 t.$date");
        }

        Document attr = asDocument(root.get("attr"));
        Document command = attr == null ? null : asDocument(attr.get("command"));
        Document originatingCommand = attr == null ? null : asDocument(attr.get("originatingCommand"));
        String message = stringValue(root.get("msg"));
        Long duration = numberValue(attr, "durationMillis");
        boolean slowQuery = "Slow query".equalsIgnoreCase(message) || duration != null;
        String operation = operation(command, attr);

        try {
            ParsedLogEntry entry = new ParsedLogEntry(
                    lineNumber,
                    fileIndex,
                    timestamp,
                    stringValue(root.get("s")),
                    stringValue(root.get("c")),
                    integerValue(root.get("id")),
                    stringValue(root.get("ctx")),
                    message,
                    attr == null ? null : stringValue(attr.get("ns")),
                    operation,
                    duration,
                    numberValue(attr, "cpuNanos"),
                    numberValue(attr, "reslen"),
                    attr == null ? null : stringValue(attr.get("planSummary")),
                    remote(attr),
                    patternNormalizer.normalize(patternSource(operation, command, originatingCommand)),
                    line,
                    attr == null ? Map.of() : attr,
                    slowQuery,
                    message != null && message.contains("Heartbeat failed")
            );
            return ParseOutcome.success(entry);
        } catch (RuntimeException e) {
            ParsedLogEntry entry = new ParsedLogEntry(
                    lineNumber, fileIndex, timestamp, stringValue(root.get("s")), stringValue(root.get("c")),
                    integerValue(root.get("id")), stringValue(root.get("ctx")), message,
                    attr == null ? null : stringValue(attr.get("ns")), operation, duration,
                    numberValue(attr, "cpuNanos"), numberValue(attr, "reslen"),
                    attr == null ? null : stringValue(attr.get("planSummary")), remote(attr), "{}", line,
                    attr == null ? Map.of() : attr, slowQuery,
                    message != null && message.contains("Heartbeat failed")
            );
            return ParseOutcome.partial(entry, "PATTERN_NORMALIZATION_FAILED", conciseMessage(e));
        }
    }

    private Object patternSource(String operation, Document command, Document originatingCommand) {
        if (command == null) {
            return null;
        }
        return switch (operation) {
            case "find" -> command.get("filter");
            case "aggregate" -> command.get("pipeline");
            case "delete" -> command.get("deletes");
            case "findAndModify" -> command.get("query");
            case "getMore" -> originatingCommand == null ? null : originatingCommand.get("filter");
            case "createIndexes" -> command.get("indexes");
            default -> command.get("q");
        };
    }

    private String operation(Document command, Document attr) {
        if (command != null) {
            for (String candidate : COMMAND_OPERATIONS) {
                if (command.containsKey(candidate)) {
                    return candidate;
                }
            }
        }
        String type = attr == null ? null : stringValue(attr.get("type"));
        return type == null || type.isBlank() ? "command" : type;
    }

    private String remote(Document attr) {
        if (attr == null) {
            return null;
        }
        String remote = stringValue(attr.get("remote"));
        return remote == null ? stringValue(attr.get("client")) : remote;
    }

    private Long timestamp(Object value) {
        if (value instanceof Date date) {
            return date.getTime();
        }
        if (value instanceof Instant instant) {
            return instant.toEpochMilli();
        }
        if (value instanceof Document document) {
            return timestamp(document.get("$date"));
        }
        if (value instanceof Map<?, ?> map) {
            return timestamp(map.get("$date"));
        }
        if (value instanceof Number number) {
            return number.longValue();
        }
        if (value instanceof String text) {
            try {
                return Instant.parse(text).toEpochMilli();
            } catch (RuntimeException ignored) {
                try {
                    return OffsetDateTime.parse(text).toInstant().toEpochMilli();
                } catch (RuntimeException invalidTimestamp) {
                    return null;
                }
            }
        }
        return null;
    }

    private Document asDocument(Object value) {
        if (value instanceof Document document) {
            return document;
        }
        if (value instanceof Map<?, ?> map) {
            Document document = new Document();
            map.forEach((key, item) -> document.put(String.valueOf(key), item));
            return document;
        }
        return null;
    }

    private Long numberValue(Document source, String key) {
        if (source == null) {
            return null;
        }
        Object value = source.get(key);
        if (value instanceof Number number) {
            return number.longValue();
        }
        if (value instanceof String text) {
            try {
                return Long.parseLong(text);
            } catch (NumberFormatException ignored) {
                return null;
            }
        }
        return null;
    }

    private Integer integerValue(Object value) {
        return value instanceof Number number ? number.intValue() : null;
    }

    private String stringValue(Object value) {
        return value == null ? null : String.valueOf(value);
    }

    private String stripBom(String line) {
        return line.startsWith("\uFEFF") ? line.substring(1) : line;
    }

    private String conciseMessage(RuntimeException error) {
        String message = error.getMessage();
        return message == null || message.isBlank() ? error.getClass().getSimpleName() : message;
    }
}
