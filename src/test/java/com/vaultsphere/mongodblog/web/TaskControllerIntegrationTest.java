package com.vaultsphere.mongodblog.web;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.mock.web.MockMultipartFile;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.MvcResult;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;

import java.nio.file.Files;
import java.nio.file.Path;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.multipart;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

@SpringBootTest
@AutoConfigureMockMvc
class TaskControllerIntegrationTest {

    @TempDir
    static Path dataDir;

    @DynamicPropertySource
    static void dataDirectory(DynamicPropertyRegistry registry) {
        registry.add("mongodblog.data-dir", () -> dataDir.toString());
    }

    @Autowired
    MockMvc mockMvc;

    @Autowired
    ObjectMapper objectMapper;

    @Test
    void uploadsLogAndQueriesCompletedResults() throws Exception {
        byte[] content = Files.readAllBytes(Path.of("src/test/resources/fixtures/structured.log"));
        MockMultipartFile file = new MockMultipartFile("files", "mongodb.log", "text/plain", content);

        MvcResult createResult = mockMvc.perform(multipart("/api/tasks").file(file).param("name", "线上慢日志"))
                .andExpect(status().isAccepted())
                .andExpect(jsonPath("$.id").isNotEmpty())
                .andReturn();
        String taskId = objectMapper.readTree(createResult.getResponse().getContentAsString()).path("id").asText();

        JsonNode task = waitForTerminalTask(taskId);
        assertThat(task.path("status").asText()).isEqualTo("COMPLETED");

        mockMvc.perform(get("/api/tasks/{id}/summary", taskId))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.slowQueryCount").value(1))
                .andExpect(jsonPath("$.durationDistribution[2].key").value("500ms_1s"))
                .andExpect(jsonPath("$.durationDistribution[2].count").value(1));

        mockMvc.perform(get("/api/tasks/{id}/slow-queries", taskId).param("page", "1").param("size", "20"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.total").value(1))
                .andExpect(jsonPath("$.content[0].durationMillis").value(742))
                .andExpect(jsonPath("$.content[0].rawLine").isString());
    }

    @Test
    void rejectsEmptyFilesAndReportsUnknownTasks() throws Exception {
        MockMultipartFile empty = new MockMultipartFile("files", "empty.log", "text/plain", new byte[0]);

        mockMvc.perform(multipart("/api/tasks").file(empty))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("INVALID_REQUEST"));
        mockMvc.perform(get("/api/tasks/missing"))
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.code").value("NOT_FOUND"));
    }

    @Test
    void validatesSlowQueryPagination() throws Exception {
        mockMvc.perform(get("/api/tasks/missing/slow-queries").param("page", "0"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("INVALID_REQUEST"));
        mockMvc.perform(get("/api/tasks/missing/slow-queries").param("size", "201"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("INVALID_REQUEST"));
    }

    private JsonNode waitForTerminalTask(String taskId) throws Exception {
        for (int attempt = 0; attempt < 100; attempt++) {
            MvcResult result = mockMvc.perform(get("/api/tasks/{id}", taskId))
                    .andExpect(status().isOk())
                    .andReturn();
            JsonNode task = objectMapper.readTree(result.getResponse().getContentAsString());
            if (task.path("status").asText().matches("COMPLETED|FAILED")) {
                return task;
            }
            Thread.sleep(20);
        }
        throw new AssertionError("任务未在两秒内完成");
    }
}
