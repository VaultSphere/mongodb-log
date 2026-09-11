package com.vaultsphere.mongodblog.analysis.ftdc;

import com.vaultsphere.mongodblog.storage.ftdc.FtdcCatalog;
import com.vaultsphere.mongodblog.storage.ftdc.FtdcIndexReader;
import com.vaultsphere.mongodblog.storage.ftdc.FtdcIndexWriter;
import com.vaultsphere.mongodblog.storage.ftdc.FtdcTaskRepository;
import com.vaultsphere.mongodblog.task.ftdc.FtdcOperationGate;
import com.vaultsphere.mongodblog.task.ftdc.FtdcTask;
import com.vaultsphere.mongodblog.task.ftdc.FtdcTaskStatus;
import com.vaultsphere.mongodblog.web.TaskNotReadyException;
import org.springframework.stereotype.Service;

import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Comparator;
import java.util.List;
import java.util.Map;
import java.util.PriorityQueue;
import java.util.stream.Collectors;

@Service
public class FtdcSeriesService {
    private final FtdcTaskRepository repository;
    private final FtdcOperationGate gate;
    private final FtdcMetricDecoder decoder = new FtdcMetricDecoder();

    public FtdcSeriesService(FtdcTaskRepository repository, FtdcOperationGate gate) {
        this.repository = repository;
        this.gate = gate;
    }

    public FtdcSeriesResult series(String taskId, String metricId, FtdcSeriesQuery query) {
        try {
            return gate.call(() -> querySeries(taskId, metricId, query));
        } catch (RuntimeException e) {
            throw e;
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new IllegalStateException("FTDC 查询被中断", e);
        } catch (Exception e) {
            throw new IllegalStateException("FTDC 查询失败：" + e.getMessage(), e);
        }
    }

    public List<FtdcMetricGroups.Group> groups(String taskId) {
        requireCompleted(taskId);
        return FtdcMetricGroups.from(repository.readCatalog(taskId));
    }

    public FtdcGroupSeriesResult groupSeries(String taskId, String groupId, FtdcSeriesQuery query) {
        if (query.maxPoints() > 1_200) throw new IllegalArgumentException("分组查询 maxPoints 必须在 1 到 1200 之间");
        try {
            return gate.call(() -> queryGroupSeries(taskId, groupId, query));
        } catch (RuntimeException e) {
            throw e;
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new IllegalStateException("FTDC 分组查询被中断", e);
        } catch (Exception e) {
            throw new IllegalStateException("FTDC 分组查询失败：" + e.getMessage(), e);
        }
    }

    public FtdcMetricPage page(String taskId, String metricId, long offset, int limit) {
        if (offset < 0) throw new IllegalArgumentException("offset 不能小于 0");
        if (limit < 1 || limit > 1_000) throw new IllegalArgumentException("limit 必须在 1 到 1000 之间");
        try {
            return gate.call(() -> queryPage(taskId, metricId, offset, limit));
        } catch (RuntimeException e) {
            throw e;
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new IllegalStateException("FTDC 分页查询被中断", e);
        } catch (Exception e) {
            throw new IllegalStateException("FTDC 分页查询失败：" + e.getMessage(), e);
        }
    }

    public MetricContext context(String taskId, String metricId) {
        FtdcTask task = requireCompleted(taskId);
        FtdcCatalog catalog = repository.readCatalog(taskId);
        FtdcCatalog.Metric metric = catalog.requireMetric(metricId);
        Path taskDirectory = repository.taskDirectory(taskId);
        FtdcIndexReader index = new FtdcIndexReader(taskDirectory.resolve("blocks.idx"));
        index.validateSources(taskDirectory.resolve("source"));
        List<FtdcIndexReader.MetricBlockIndex> blocks = new ArrayList<>(index.metricBlocks(metric.path()));
        blocks.sort(Comparator.comparingLong(FtdcIndexReader.MetricBlockIndex::startEpochMillis)
                .thenComparingInt(FtdcIndexReader.MetricBlockIndex::fileId)
                .thenComparingInt(FtdcIndexReader.MetricBlockIndex::blockOrdinal));
        return new MetricContext(task, catalog, metric, taskDirectory.resolve("source"), index.files(), List.copyOf(blocks));
    }

    public void forEachPoint(MetricContext context, Long start, Long end, PointConsumer consumer) throws Exception {
        Map<Integer, List<FtdcIndexReader.MetricBlockIndex>> blocksByFile = context.blocks().stream()
                .filter(block -> start == null || block.endEpochMillis() >= start)
                .filter(block -> end == null || block.startEpochMillis() <= end)
                .collect(Collectors.groupingBy(FtdcIndexReader.MetricBlockIndex::fileId));
        PriorityQueue<FilePointIterator> queue = new PriorityQueue<>(Comparator
                .comparingLong(FilePointIterator::timestamp)
                .thenComparingInt(FilePointIterator::fileId));
        for (Map.Entry<Integer, List<FtdcIndexReader.MetricBlockIndex>> entry : blocksByFile.entrySet()) {
            List<FtdcIndexReader.MetricBlockIndex> blocks = new ArrayList<>(entry.getValue());
            blocks.sort(Comparator.comparingLong(FtdcIndexReader.MetricBlockIndex::startEpochMillis)
                    .thenComparingInt(FtdcIndexReader.MetricBlockIndex::blockOrdinal));
            FilePointIterator iterator = new FilePointIterator(entry.getKey(), blocks, context, start, end);
            if (iterator.advance()) queue.add(iterator);
        }

        long lastTimestamp = Long.MIN_VALUE;
        while (!queue.isEmpty()) {
            long timestamp = queue.peek().timestamp();
            int selectedFileId = Integer.MAX_VALUE;
            long selectedValue = 0;
            while (!queue.isEmpty() && queue.peek().timestamp() == timestamp) {
                FilePointIterator iterator = queue.poll();
                if (iterator.fileId() < selectedFileId) {
                    selectedFileId = iterator.fileId();
                    selectedValue = iterator.value();
                }
                if (iterator.advance()) queue.add(iterator);
            }
            if (timestamp <= lastTimestamp) continue;
            consumer.accept(timestamp, selectedValue);
            lastTimestamp = timestamp;
        }
    }

    private FtdcSeriesResult querySeries(String taskId, String metricId, FtdcSeriesQuery query) throws Exception {
        MetricContext context = context(taskId, metricId);
        long start = query.start() == null ? context.catalog().startEpochMillis() : query.start();
        long end = query.end() == null ? context.catalog().endEpochMillis() : query.end();
        if (start > end) throw new IllegalArgumentException("查询时间范围无数据");
        long width = Math.max(1, ceilDiv(safeSpan(start, end), query.maxPoints()));
        long[] timestamps = new long[query.maxPoints()];
        long[] values = new long[query.maxPoints()];
        boolean[] present = new boolean[query.maxPoints()];
        forEachPoint(context, start, end, (timestamp, value) -> {
            long rawBucket = (timestamp - start) / width;
            int bucket = (int) Math.min(query.maxPoints() - 1L, Math.max(0, rawBucket));
            timestamps[bucket] = timestamp;
            values[bucket] = value;
            present[bucket] = true;
        });
        List<Long> outputTimes = new ArrayList<>();
        List<Long> outputValues = new ArrayList<>();
        Long previous = null;
        for (int i = 0; i < present.length; i++) {
            if (!present[i]) continue;
            outputTimes.add(timestamps[i]);
            if (query.view() == FtdcSeriesQuery.View.DELTA) {
                outputValues.add(previous == null ? null : values[i] - previous);
                previous = values[i];
            } else {
                outputValues.add(values[i]);
            }
        }
        return new FtdcSeriesResult(metricId, context.metric().path(), query.view().name().toLowerCase(),
                outputTimes, outputValues);
    }

    private FtdcGroupSeriesResult queryGroupSeries(String taskId, String groupId, FtdcSeriesQuery query) throws Exception {
        requireCompleted(taskId);
        FtdcCatalog catalog = repository.readCatalog(taskId);
        FtdcMetricGroups.Group group = FtdcMetricGroups.require(catalog, groupId);
        if (group.metricCount() > FtdcMetricGroups.MAX_METRICS_PER_GROUP) {
            throw new IllegalArgumentException("FTDC 指标组超过 200 个指标：" + group.name());
        }
        Path taskDirectory = repository.taskDirectory(taskId);
        FtdcIndexReader index = new FtdcIndexReader(taskDirectory.resolve("blocks.idx"));
        Path sourceDirectory = taskDirectory.resolve("source");
        index.validateSources(sourceDirectory);
        List<FtdcIndexReader.GroupMetricBlockIndex> blocks = new ArrayList<>(index.groupMetricBlocks(
                group.metrics().stream().map(FtdcCatalog.Metric::path).toList()));
        blocks.sort(Comparator.comparingLong(FtdcIndexReader.GroupMetricBlockIndex::startEpochMillis)
                .thenComparingInt(FtdcIndexReader.GroupMetricBlockIndex::fileId)
                .thenComparingInt(FtdcIndexReader.GroupMetricBlockIndex::blockOrdinal));
        long start = query.start() == null ? catalog.startEpochMillis() : query.start();
        long end = query.end() == null ? catalog.endEpochMillis() : query.end();
        if (start > end) throw new IllegalArgumentException("查询时间范围无数据");
        int metricCount = group.metricCount();
        int maxPoints = query.maxPoints();
        long width = Math.max(1, ceilDiv(safeSpan(start, end), maxPoints));
        long[][] bucketTimes = new long[metricCount][maxPoints];
        long[][] bucketValues = new long[metricCount][maxPoints];
        boolean[][] bucketPresent = new boolean[metricCount][maxPoints];
        GroupContext context = new GroupContext(sourceDirectory, index.files(), List.copyOf(blocks), metricCount);
        forEachGroupPoint(context, start, end, (timestamp, values, present) -> {
            long rawBucket = (timestamp - start) / width;
            int bucket = (int) Math.min(maxPoints - 1L, Math.max(0, rawBucket));
            for (int metricIndex = 0; metricIndex < metricCount; metricIndex++) {
                if (!present[metricIndex]) continue;
                bucketTimes[metricIndex][bucket] = timestamp;
                bucketValues[metricIndex][bucket] = values[metricIndex];
                bucketPresent[metricIndex][bucket] = true;
            }
        });
        List<FtdcSeriesResult> output = new ArrayList<>(metricCount);
        for (int metricIndex = 0; metricIndex < metricCount; metricIndex++) {
            List<Long> times = new ArrayList<>();
            List<Long> values = new ArrayList<>();
            Long previous = null;
            for (int bucket = 0; bucket < maxPoints; bucket++) {
                if (!bucketPresent[metricIndex][bucket]) continue;
                times.add(bucketTimes[metricIndex][bucket]);
                long value = bucketValues[metricIndex][bucket];
                if (query.view() == FtdcSeriesQuery.View.DELTA) {
                    values.add(previous == null ? null : value - previous);
                    previous = value;
                } else {
                    values.add(value);
                }
            }
            FtdcCatalog.Metric metric = group.metrics().get(metricIndex);
            output.add(new FtdcSeriesResult(metric.metricId(), metric.path(), query.view().name().toLowerCase(), times, values));
        }
        return new FtdcGroupSeriesResult(group.groupId(), group.name(), query.view().name().toLowerCase(), output);
    }

    private void forEachGroupPoint(GroupContext context, Long start, Long end, GroupPointConsumer consumer) throws Exception {
        Map<Integer, List<FtdcIndexReader.GroupMetricBlockIndex>> blocksByFile = context.blocks().stream()
                .filter(block -> start == null || block.endEpochMillis() >= start)
                .filter(block -> end == null || block.startEpochMillis() <= end)
                .collect(Collectors.groupingBy(FtdcIndexReader.GroupMetricBlockIndex::fileId));
        PriorityQueue<GroupFilePointIterator> queue = new PriorityQueue<>(Comparator
                .comparingLong(GroupFilePointIterator::timestamp)
                .thenComparingInt(GroupFilePointIterator::fileId));
        for (Map.Entry<Integer, List<FtdcIndexReader.GroupMetricBlockIndex>> entry : blocksByFile.entrySet()) {
            List<FtdcIndexReader.GroupMetricBlockIndex> blocks = new ArrayList<>(entry.getValue());
            blocks.sort(Comparator.comparingLong(FtdcIndexReader.GroupMetricBlockIndex::startEpochMillis)
                    .thenComparingInt(FtdcIndexReader.GroupMetricBlockIndex::blockOrdinal));
            GroupFilePointIterator iterator = new GroupFilePointIterator(entry.getKey(), blocks, context, start, end);
            if (iterator.advance()) queue.add(iterator);
        }
        long[] values = new long[context.metricCount()];
        boolean[] present = new boolean[context.metricCount()];
        int[] selectedFiles = new int[context.metricCount()];
        long lastTimestamp = Long.MIN_VALUE;
        while (!queue.isEmpty()) {
            long timestamp = queue.peek().timestamp();
            Arrays.fill(present, false);
            Arrays.fill(selectedFiles, Integer.MAX_VALUE);
            while (!queue.isEmpty() && queue.peek().timestamp() == timestamp) {
                GroupFilePointIterator iterator = queue.poll();
                for (int column = 0; column < iterator.metricIndexes().length; column++) {
                    int metricIndex = iterator.metricIndexes()[column];
                    if (iterator.fileId() < selectedFiles[metricIndex]) {
                        selectedFiles[metricIndex] = iterator.fileId();
                        values[metricIndex] = iterator.value(column);
                        present[metricIndex] = true;
                    }
                }
                if (iterator.advance()) queue.add(iterator);
            }
            if (timestamp <= lastTimestamp) continue;
            consumer.accept(timestamp, values, present);
            lastTimestamp = timestamp;
        }
    }

    private FtdcMetricPage queryPage(String taskId, String metricId, long offset, int limit) throws Exception {
        MetricContext context = context(taskId, metricId);
        List<Long> timestamps = new ArrayList<>(limit);
        List<Long> values = new ArrayList<>(limit);
        long[] index = {0};
        forEachPoint(context, null, null, (timestamp, value) -> {
            if (index[0] >= offset && timestamps.size() < limit) {
                timestamps.add(timestamp);
                values.add(value);
            }
            index[0]++;
        });
        return new FtdcMetricPage(offset, limit, index[0], timestamps, values);
    }

    private FtdcTask requireCompleted(String id) {
        FtdcTask task = repository.findTask(id).orElseThrow(() -> new java.util.NoSuchElementException("FTDC 任务不存在：" + id));
        if (task.status() != FtdcTaskStatus.COMPLETED) throw new TaskNotReadyException("FTDC 任务尚未完成：" + id);
        return task;
    }

    private long safeSpan(long start, long end) {
        long difference = end - start;
        return difference < 0 || difference == Long.MAX_VALUE ? Long.MAX_VALUE : difference + 1;
    }

    private long ceilDiv(long value, long divisor) {
        return value / divisor + (value % divisor == 0 ? 0 : 1);
    }

    @FunctionalInterface
    public interface PointConsumer {
        void accept(long timestamp, long value) throws Exception;
    }

    @FunctionalInterface
    private interface GroupPointConsumer {
        void accept(long timestamp, long[] values, boolean[] present) throws Exception;
    }

    public record MetricContext(FtdcTask task, FtdcCatalog catalog, FtdcCatalog.Metric metric,
                                Path sourceDirectory, List<FtdcIndexWriter.SourceFile> files,
                                List<FtdcIndexReader.MetricBlockIndex> blocks) {
    }

    private record GroupContext(Path sourceDirectory, List<FtdcIndexWriter.SourceFile> files,
                                List<FtdcIndexReader.GroupMetricBlockIndex> blocks, int metricCount) {
    }

    private final class FilePointIterator {
        private final int fileId;
        private final List<FtdcIndexReader.MetricBlockIndex> blocks;
        private final MetricContext context;
        private final Long start;
        private final Long end;
        private int blockIndex;
        private int pointIndex;
        private FtdcMetricDecoder.DecodedBlock decoded;
        private long timestamp;
        private long value;

        private FilePointIterator(int fileId, List<FtdcIndexReader.MetricBlockIndex> blocks,
                                  MetricContext context, Long start, Long end) {
            this.fileId = fileId;
            this.blocks = blocks;
            this.context = context;
            this.start = start;
            this.end = end;
        }

        private boolean advance() throws Exception {
            while (true) {
                if (Thread.currentThread().isInterrupted()) throw new InterruptedException();
                if (decoded != null && pointIndex < decoded.timestamps().length) {
                    timestamp = decoded.timestamps()[pointIndex];
                    value = decoded.values()[pointIndex];
                    pointIndex++;
                    if (start != null && timestamp < start) continue;
                    if (end != null && timestamp > end) continue;
                    return true;
                }
                if (blockIndex >= blocks.size()) return false;
                decoded = decoder.decode(context.sourceDirectory(), context.files(), blocks.get(blockIndex++));
                pointIndex = 0;
            }
        }

        private int fileId() {
            return fileId;
        }

        private long timestamp() {
            return timestamp;
        }

        private long value() {
            return value;
        }
    }

    private final class GroupFilePointIterator {
        private final int fileId;
        private final List<FtdcIndexReader.GroupMetricBlockIndex> blocks;
        private final GroupContext context;
        private final Long start;
        private final Long end;
        private int blockIndex;
        private int pointIndex;
        private int currentPoint;
        private FtdcMetricDecoder.GroupDecodedBlock decoded;
        private long timestamp;

        private GroupFilePointIterator(int fileId, List<FtdcIndexReader.GroupMetricBlockIndex> blocks,
                                       GroupContext context, Long start, Long end) {
            this.fileId = fileId;
            this.blocks = blocks;
            this.context = context;
            this.start = start;
            this.end = end;
        }

        private boolean advance() throws Exception {
            while (true) {
                if (Thread.currentThread().isInterrupted()) throw new InterruptedException();
                if (decoded != null && pointIndex < decoded.timestamps().length) {
                    currentPoint = pointIndex;
                    timestamp = decoded.timestamps()[pointIndex++];
                    if (start != null && timestamp < start) continue;
                    if (end != null && timestamp > end) continue;
                    return true;
                }
                if (blockIndex >= blocks.size()) return false;
                decoded = decoder.decodeGroup(context.sourceDirectory(), context.files(), blocks.get(blockIndex++));
                pointIndex = 0;
            }
        }

        private int[] metricIndexes() {
            return decoded.metricIndexes();
        }

        private long value(int column) {
            return decoded.values()[column][currentPoint];
        }

        private int fileId() {
            return fileId;
        }

        private long timestamp() {
            return timestamp;
        }
    }
}
