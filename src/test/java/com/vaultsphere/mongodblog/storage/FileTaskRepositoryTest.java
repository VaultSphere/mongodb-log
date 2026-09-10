package com.vaultsphere.mongodblog.storage;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.vaultsphere.mongodblog.analysis.AnalysisSummary;
import com.vaultsphere.mongodblog.analysis.SlowQueryRecord;
import com.vaultsphere.mongodblog.task.AnalysisTask;
import com.vaultsphere.mongodblog.task.TaskInputFile;
import com.vaultsphere.mongodblog.task.TaskStatus;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

class FileTaskRepositoryTest {

    @TempDir
    Path tempDir;

    @Test
    void roundTripsTaskSummaryAndJsonLinesWithoutTemporaryResidue() throws Exception {
        FileTaskRepository repository = repository();
        AnalysisTask task = task("task-1", TaskStatus.QUEUED, null);
        AnalysisSummary summary = emptySummary(2, 800);
        List<SlowQueryRecord> records = List.of(
                record("0-2", 500),
                record("0-1", 300)
        );

        repository.saveTask(task);
        repository.saveResult(task.id(), summary, records);

        assertThat(repository.findTask(task.id())).contains(task);
        assertThat(repository.listTasks()).containsExactly(task);
        assertThat(repository.readSummary(task.id())).isEqualTo(summary);
        assertThat(repository.readSlowQueries(task.id())).containsExactlyElementsOf(records);
        assertThat(repository.readSlowQuery(task.id(), "0-1")).contains(records.get(1));
        try (var paths = Files.walk(tempDir)) {
            assertThat(paths.noneMatch(path -> path.getFileName().toString().endsWith(".tmp"))).isTrue();
        }
    }

    @Test
    void marksInterruptedRunningTasksAsFailedOnStartup() {
        FileTaskRepository firstProcess = repository();
        firstProcess.saveTask(task("task-running", TaskStatus.RUNNING, null));

        FileTaskRepository restartedProcess = repository();

        AnalysisTask recovered = restartedProcess.findTask("task-running").orElseThrow();
        assertThat(recovered.status()).isEqualTo(TaskStatus.FAILED);
        assertThat(recovered.errorMessage()).isEqualTo("应用在分析过程中退出");
        assertThat(recovered.completedAtEpochMillis()).isNotNull();
    }

    private FileTaskRepository repository() {
        return new FileTaskRepository(tempDir, new ObjectMapper().findAndRegisterModules());
    }

    private AnalysisTask task(String id, TaskStatus status, String error) {
        return new AnalysisTask(
                id, "测试任务", status, 1_000, status == TaskStatus.QUEUED ? null : 1_100L,
                null, List.of(new TaskInputFile("mongo.log", "0000-mongo.log", 200)),
                200, 0, 0, error
        );
    }

    private AnalysisSummary emptySummary(long slowCount, long duration) {
        return new AnalysisSummary(
                slowCount, slowCount, 0, 0, 0, slowCount, duration, 0, false,
                List.of(), Map.of(), Map.of(), Map.of(), Map.of(), Map.of(), Map.of()
        );
    }

    private SlowQueryRecord record(String id, long duration) {
        return new SlowQueryRecord(
                id, 0, Long.parseLong(id.substring(2)), 1_000, "find", "db.items", duration,
                null, 20L, "IXSCAN", "127.0.0.1", "{}", "raw", Map.of()
        );
    }
}
