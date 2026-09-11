package com.vaultsphere.mongodblog.report;

import com.vaultsphere.mongodblog.analysis.AnalysisSummary;
import com.vaultsphere.mongodblog.analysis.DurationBucketStat;
import com.vaultsphere.mongodblog.analysis.PatternStat;
import com.vaultsphere.mongodblog.analysis.diagnostics.LogDiagnostics;
import com.vaultsphere.mongodblog.storage.TaskRepository;
import com.vaultsphere.mongodblog.task.AnalysisTask;
import org.springframework.stereotype.Service;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.Map;
import java.util.NoSuchElementException;
import java.util.regex.Pattern;

@Service
public class MarkdownReportService {
    private static final Pattern IPV4 = Pattern.compile("\\b(?:\\d{1,3}\\.){3}\\d{1,3}\\b");
    private static final Pattern MONGODB_URI = Pattern.compile("(?i)mongodb(?:\\+srv)?://\\S+");
    private static final Pattern EMAIL = Pattern.compile("[A-Za-z0-9._%+-]+@[A-Za-z0-9.-]+\\.[A-Za-z]{2,}");
    private static final Pattern SECRET = Pattern.compile("(?i)(password|passwd|token|secret)(\\s*[:=]\\s*)([^\\s,;]+)");

    private final TaskRepository repository;

    public MarkdownReportService(TaskRepository repository) {
        this.repository = repository;
    }

    public Report generate(String taskId) {
        AnalysisTask task = repository.findTask(taskId)
                .orElseThrow(() -> new NoSuchElementException("任务不存在：" + taskId));
        AnalysisSummary summary = repository.readSummary(taskId);
        LogDiagnostics diagnostics = repository.readDiagnostics(taskId).orElse(null);
        StringBuilder report = new StringBuilder(16_384);
        line(report, "# MongoDB 日志分析报告");
        line(report, "");
        line(report, "> 本报告由本地离线工具生成，适合交给 AI 进行二次分析。报告不包含完整原始日志、命令字面值、用户名或完整客户端 IP。");
        line(report, "");
        line(report, "## 任务概况");
        line(report, "");
        item(report, "任务名称", redact(task.name()));
        item(report, "日志文件数", Integer.toString(task.files().size()));
        item(report, "文件总大小", Long.toString(task.totalBytes()) + " B");
        item(report, "日志时间范围", time(summary.logStartEpochMillis()) + " ～ " + time(summary.logEndEpochMillis()));
        item(report, "日志总行数", Long.toString(summary.totalLines()));
        item(report, "慢查询数", Long.toString(summary.slowQueryCount()));
        item(report, "解析结果", "成功 " + summary.successLines() + "，部分解析 " + summary.partialLines()
                + "，失败 " + summary.failedLines() + "，跳过 " + summary.skippedLines());

        appendQuality(report, diagnostics);
        appendEvents(report, diagnostics);
        appendConnections(report, diagnostics);
        appendReplication(report, diagnostics);
        appendSlowQueries(report, summary, diagnostics);
        appendLimits(report, diagnostics);
        return new Report(fileName(task.name()), report.toString());
    }

    private void appendQuality(StringBuilder report, LogDiagnostics diagnostics) {
        line(report, "");
        line(report, "## 数据可信度");
        line(report, "");
        if (diagnostics == null) {
            line(report, "此历史任务未生成运行诊断；重新上传原日志后可获得字段覆盖、截断和事件分析。");
            return;
        }
        var quality = diagnostics.dataQuality();
        item(report, "结构化日志", Long.toString(quality.structuredLines()));
        item(report, "旧版文本日志", Long.toString(quality.legacyLines()));
        item(report, "截断日志", Long.toString(quality.truncatedLines()));
        item(report, "带标签日志", Long.toString(quality.taggedLines()));
        item(report, "文件内时间乱序", Long.toString(quality.outOfOrderLines()));
        if (!quality.serverVersions().isEmpty()) item(report, "检测到的服务器版本", joinCounts(quality.serverVersions()));
        if (!quality.services().isEmpty()) item(report, "服务角色", joinCounts(quality.services()));
        if (!diagnostics.slowQueries().fieldCoverage().isEmpty()) {
            line(report, "");
            line(report, "### 慢查询字段覆盖");
            line(report, "");
            line(report, "| 字段 | 有值样本 | 慢查询总数 |");
            line(report, "|---|---:|---:|");
            diagnostics.slowQueries().fieldCoverage().forEach((field, count) ->
                    line(report, "| " + cell(field) + " | " + count + " | " + diagnostics.slowQueries().total() + " |"));
        }
    }

    private void appendEvents(StringBuilder report, LogDiagnostics diagnostics) {
        line(report, "");
        line(report, "## 异常事件");
        line(report, "");
        if (diagnostics == null) {
            line(report, "运行诊断不可用。");
            return;
        }
        item(report, "严重级别分布", joinCounts(diagnostics.severityCounts()));
        item(report, "组件分布", joinCounts(diagnostics.componentCounts()));
        if (diagnostics.abnormalEvents().isEmpty()) {
            line(report, "");
            line(report, "日志中未识别到 Fatal、Error 或 Warning 事件。");
            return;
        }
        line(report, "");
        line(report, "| 级别 | 组件 | ID | 事件 | 次数 | 首次 | 最后 |");
        line(report, "|---|---|---:|---|---:|---|---|");
        diagnostics.abnormalEvents().forEach(event -> line(report,
                "| " + cell(event.severity()) + " | " + cell(event.component()) + " | "
                        + (event.messageId() == null ? "-" : event.messageId()) + " | " + cell(redact(event.message()))
                        + " | " + event.count() + " | " + time(event.firstEpochMillis()) + " | "
                        + time(event.lastEpochMillis()) + " |"));
    }

    private void appendConnections(StringBuilder report, LogDiagnostics diagnostics) {
        line(report, "");
        line(report, "## 连接与客户端");
        line(report, "");
        if (diagnostics == null) {
            line(report, "运行诊断不可用。");
            return;
        }
        var connection = diagnostics.connections();
        item(report, "建立／结束连接", connection.accepted() + "／" + connection.ended());
        item(report, "认证成功／未认证连接／重新认证警告", connection.authenticationSucceeded() + "／"
                + connection.notAuthenticating() + "／" + connection.reauthenticationWarnings());
        if (connection.connectionCountSamples() > 0) {
            item(report, "打开连接数", "最小 " + connection.connectionCountMin() + "，最大 "
                    + connection.connectionCountMax() + "，平均 " + decimal(connection.connectionCountAverage())
                    + "，样本 " + connection.connectionCountSamples());
        }
        if (!connection.applications().isEmpty()) item(report, "客户端应用 Top", joinCounts(connection.applications()));
        if (!connection.drivers().isEmpty()) item(report, "Driver Top", joinCounts(connection.drivers()));
        if (connection.topValuesApproximate()) line(report, "\n客户端 Top 统计使用有界重频算法，显示值为近似计数。");
    }

    private void appendReplication(StringBuilder report, LogDiagnostics diagnostics) {
        line(report, "");
        line(report, "## 复制集与网络时间线");
        line(report, "");
        if (diagnostics == null || diagnostics.replicationEvents().isEmpty()) {
            line(report, diagnostics == null ? "运行诊断不可用。" : "未识别到相关事件。");
            return;
        }
        line(report, "| 类型 | 组件 | ID | 事件 | 次数 | 首次 | 最后 |");
        line(report, "|---|---|---:|---|---:|---|---|");
        diagnostics.replicationEvents().forEach(event -> line(report,
                "| " + cell(event.label()) + " | " + cell(event.component()) + " | "
                        + (event.messageId() == null ? "-" : event.messageId()) + " | " + cell(redact(event.message()))
                        + " | " + event.count() + " | " + time(event.firstEpochMillis()) + " | "
                        + time(event.lastEpochMillis()) + " |"));
    }

    private void appendSlowQueries(StringBuilder report, AnalysisSummary summary, LogDiagnostics diagnostics) {
        line(report, "");
        line(report, "## 慢查询分析");
        line(report, "");
        item(report, "慢查询总数", Long.toString(summary.slowQueryCount()));
        item(report, "慢查询总耗时", summary.totalSlowDurationMillis() + " ms");
        if (summary.durationDistribution() != null && !summary.durationDistribution().isEmpty()) {
            line(report, "");
            line(report, "### 耗时分布");
            line(report, "");
            line(report, "| 区间 | 数量 | 占比 | 平均耗时 |");
            line(report, "|---|---:|---:|---:|");
            for (DurationBucketStat bucket : summary.durationDistribution()) {
                line(report, "| " + cell(bucket.label()) + " | " + bucket.count() + " | "
                        + decimal(bucket.percentage()) + "% | " + decimal(bucket.averageDurationMillis()) + " ms |");
            }
        }
        if (diagnostics != null) {
            var slow = diagnostics.slowQueries();
            line(report, "");
            line(report, "### 效率线索");
            line(report, "");
            item(report, "COLLSCAN", Long.toString(slow.collscanCount()));
            item(report, "扫描文档／返回比不低于 100", Long.toString(slow.highDocumentScanRatioCount()));
            item(report, "扫描索引键／返回比不低于 100", Long.toString(slow.highIndexScanRatioCount()));
            item(report, "磁盘读取耗时占比不低于 50％", Long.toString(slow.storageDominantCount()));
            item(report, "查询规划耗时占比不低于 50％", Long.toString(slow.planningDominantCount()));
            item(report, "写关注／Flow Control／锁等待", slow.writeConcernWaitCount() + "／"
                    + slow.flowControlWaitCount() + "／" + slow.lockWaitCount());
            item(report, "分片响应／鉴权缓存／执行队列／Oplog 提交等待", slow.remoteOpWaitCount() + "／"
                    + slow.authorizationWaitCount() + "／" + slow.queueWaitCount() + "／" + slow.oplogSlotWaitCount());
            item(report, "额外排序／使用临时磁盘", slow.hasSortStageCount() + "／" + slow.usedDiskCount());
            item(report, "查询执行落盘", Long.toString(slow.spillCount()));
            item(report, "不同查询形状", Long.toString(slow.distinctShapeCount()));
            if (!slow.insights().isEmpty()) {
                line(report, "");
                line(report, "### 重点慢查询线索");
                line(report, "");
                line(report, "| 集合 | 操作 | 耗时 | 计划 | 文档／返回 | 索引键／返回 | 线索 | 规范化查询模式 |");
                line(report, "|---|---|---:|---|---:|---:|---|---|");
                slow.insights().forEach(insight -> line(report,
                        "| " + cell(insight.namespace()) + " | " + cell(insight.operation()) + " | "
                                + insight.durationMillis() + " ms | " + cell(insight.planSummary()) + " | "
                                + decimal(insight.documentsPerReturned()) + " | " + decimal(insight.keysPerReturned())
                                + " | " + cell(String.join("、", insight.reasons())) + " | "
                                + cell(insight.queryPattern()) + " |"));
            }
        }
        appendPatterns(report, summary);
    }

    private void appendPatterns(StringBuilder report, AnalysisSummary summary) {
        if (summary.patternStats() == null || summary.patternStats().isEmpty()) return;
        line(report, "");
        line(report, "### 查询模式 Top 50");
        line(report, "");
        line(report, "| 集合 | 操作 | 次数 | 平均耗时 | 最大耗时 | 执行计划 | 规范化查询模式 |");
        line(report, "|---|---|---:|---:|---:|---|---|");
        for (PatternStat pattern : summary.patternStats()) {
            line(report, "| " + cell(pattern.namespace()) + " | " + cell(pattern.operation()) + " | "
                    + pattern.count() + " | " + decimal(pattern.averageDurationMillis()) + " ms | "
                    + pattern.maxDurationMillis() + " ms | " + cell(pattern.planSummary()) + " | "
                    + cell(pattern.pattern()) + " |");
        }
    }

    private void appendLimits(StringBuilder report, LogDiagnostics diagnostics) {
        line(report, "");
        line(report, "## 分析边界与 AI 使用说明");
        line(report, "");
        line(report, "- 本报告只反映所上传日志中的可见事件；慢查询数量受 `slowms`、`slowOpSampleRate`、日志级别和过滤条件影响。");
        line(report, "- COLLSCAN、扫描比、等待时间和时间相关事件是排查线索，不等同于已确定根因。");
        line(report, "- 日志不能提供完整索引清单、实时复制延迟、缓存命中率或磁盘吞吐；这些信息应结合 FTDC 或在线状态核对。");
        if (diagnostics != null && diagnostics.dataQuality().truncatedLines() > 0) {
            line(report, "- 存在截断日志，命令或属性可能不完整。");
        }
        line(report, "- 建议 AI 先按时间关联异常、复制集、连接峰值和慢查询，再给出需要人工或在线命令验证的假设。");
    }

    private String joinCounts(Map<String, Long> counts) {
        return counts.entrySet().stream().map(entry -> redact(entry.getKey()) + "=" + entry.getValue())
                .reduce((left, right) -> left + "，" + right).orElse("无");
    }

    private String cell(Object value) {
        if (value == null) return "-";
        return redact(String.valueOf(value)).replace("|", "\\|").replace("\r", " ").replace("\n", " ");
    }

    private String redact(String value) {
        if (value == null) return "";
        String redacted = MONGODB_URI.matcher(value).replaceAll("[MongoDB URI]");
        redacted = IPV4.matcher(redacted).replaceAll("[IP]");
        redacted = EMAIL.matcher(redacted).replaceAll("[EMAIL]");
        return SECRET.matcher(redacted).replaceAll("$1$2[REDACTED]");
    }

    private String decimal(Number value) {
        if (value == null) return "-";
        return BigDecimal.valueOf(value.doubleValue()).setScale(3, java.math.RoundingMode.HALF_UP)
                .stripTrailingZeros().toPlainString();
    }

    private String time(Long epochMillis) {
        return epochMillis == null ? "日志未提供" : Instant.ofEpochMilli(epochMillis).toString();
    }

    private void item(StringBuilder report, String label, String value) {
        line(report, "- " + label + "：" + value);
    }

    private void line(StringBuilder report, String line) {
        report.append(line).append('\n');
    }

    private String fileName(String taskName) {
        String safe = taskName == null ? "mongodb-log" : taskName
                .replaceAll("[\\\\/:*?\"<>|\\r\\n]", "_").trim();
        if (safe.isBlank()) safe = "mongodb-log";
        if (safe.length() > 80) safe = safe.substring(0, 80);
        return safe + "-mongodb-analysis.md";
    }

    public record Report(String fileName, String content) {
    }
}
