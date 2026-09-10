package com.vaultsphere.mongodblog.task;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.vaultsphere.mongodblog.analysis.AnalysisSummary;
import com.vaultsphere.mongodblog.parser.CompositeLogParser;
import com.vaultsphere.mongodblog.parser.LegacyLogParser;
import com.vaultsphere.mongodblog.parser.QueryPatternNormalizer;
import com.vaultsphere.mongodblog.parser.StructuredLogParser;
import com.vaultsphere.mongodblog.storage.FileTaskRepository;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.zip.GZIPOutputStream;

import static org.assertj.core.api.Assertions.assertThat;

class TaskRunnerTest {

    @TempDir
    Path dataDir;

    @Test
    void streamsPlainAndGzipFilesAndCleansWorkDirectory() throws Exception {
        FileTaskRepository repository = repository();
        Path work = Files.createDirectories(dataDir.resolve("work/task-ok"));
        String structured = Files.readString(Path.of("src/test/resources/fixtures/structured.log"));
        String legacy = Files.readString(Path.of("src/test/resources/fixtures/legacy.log")).lines().findFirst().orElseThrow();
        Path plain = work.resolve("0000-structured.log");
        Path gzip = work.resolve("0001-legacy.log.gz");
        Files.writeString(plain, structured.stripTrailing() + System.lineSeparator(), StandardCharsets.UTF_8);
        writeGzip(gzip, legacy + System.lineSeparator());
        AnalysisTask task = task("task-ok", List.of(
                new TaskInputFile("structured.log", plain.getFileName().toString(), Files.size(plain)),
                new TaskInputFile("legacy.log.gz", gzip.getFileName().toString(), Files.size(gzip))
        ));
        repository.saveTask(task);

        runner(repository).run(task.id());

        AnalysisTask completed = repository.findTask(task.id()).orElseThrow();
        AnalysisSummary summary = repository.readSummary(task.id());
        assertThat(completed.status()).isEqualTo(TaskStatus.COMPLETED);
        assertThat(completed.processedLines()).isEqualTo(2);
        assertThat(summary.slowQueryCount()).isEqualTo(2);
        assertThat(repository.readSlowQueries(task.id()))
                .extracting(record -> record.durationMillis())
                .containsExactly(742L, 325L);
        assertThat(summary.durationDistribution()).extracting(stat -> stat.count())
                .containsExactly(0L, 1L, 1L, 0L, 0L, 0L, 0L, 0L);
        assertThat(work).doesNotExist();
    }

    @Test
    void marksDamagedGzipAsFailedAndStillCleansWorkDirectory() throws Exception {
        FileTaskRepository repository = repository();
        Path work = Files.createDirectories(dataDir.resolve("work/task-bad"));
        Path gzip = work.resolve("0000-bad.log.gz");
        Files.writeString(gzip, "not-gzip", StandardCharsets.UTF_8);
        AnalysisTask task = task("task-bad", List.of(
                new TaskInputFile("bad.log.gz", gzip.getFileName().toString(), Files.size(gzip))
        ));
        repository.saveTask(task);

        runner(repository).run(task.id());

        AnalysisTask failed = repository.findTask(task.id()).orElseThrow();
        assertThat(failed.status()).isEqualTo(TaskStatus.FAILED);
        assertThat(failed.errorMessage()).contains("GZIP");
        assertThat(work).doesNotExist();
    }

    private TaskRunner runner(FileTaskRepository repository) {
        QueryPatternNormalizer normalizer = new QueryPatternNormalizer();
        return new TaskRunner(
                dataDir,
                repository,
                new CompositeLogParser(
                        new StructuredLogParser(normalizer),
                        new LegacyLogParser(normalizer)
                )
        );
    }

    private FileTaskRepository repository() {
        return new FileTaskRepository(dataDir, new ObjectMapper().findAndRegisterModules());
    }

    private AnalysisTask task(String id, List<TaskInputFile> files) {
        long totalBytes = files.stream().mapToLong(TaskInputFile::sizeBytes).sum();
        return new AnalysisTask(id, id, TaskStatus.QUEUED, 1_000, null, null, files, totalBytes, 0, 0, null);
    }

    private void writeGzip(Path path, String text) throws IOException {
        try (GZIPOutputStream output = new GZIPOutputStream(Files.newOutputStream(path))) {
            output.write(text.getBytes(StandardCharsets.UTF_8));
        }
    }
}
