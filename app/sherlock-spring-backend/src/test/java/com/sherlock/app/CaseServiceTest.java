package com.sherlock.app;

import com.sherlock.app.config.AppProperties;
import com.sherlock.app.model.CaseRequest;
import com.sherlock.app.model.CaseResponse;
import com.sherlock.app.model.LlmConfigRequest;
import com.sherlock.app.service.CaseService;
import com.sherlock.app.service.Neo4jGraphService;
import com.sherlock.app.service.OllamaService;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.springframework.mock.web.MockMultipartFile;

import java.io.File;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

class CaseServiceTest {

    @TempDir
    Path tempDir;

    private CaseService caseService;
    private AppProperties appProperties;

    @BeforeEach
    void setUp() {
        appProperties = new AppProperties();
        appProperties.setBaseDirectory(tempDir.toString());
        appProperties.getNeo4j().setEnabled(false);
        Neo4jGraphService neo4jGraphService = new Neo4jGraphService(appProperties);
        OllamaService ollamaService = new OllamaService(appProperties);
        caseService = new CaseService(appProperties, neo4jGraphService, ollamaService);
    }

    @Test
    void testCreateCaseAndWriteLlmConfig() {
        CaseRequest request = new CaseRequest("Operation Rose");
        request.setCaseId("operation_rose_001");

        LlmConfigRequest llmConfig = new LlmConfigRequest("openai", "gpt-4o-mini", "sk-testkey", "https://api.openai.com/v1", 128000, 0.1);
        request.setLlmConfig(llmConfig);

        CaseResponse response = caseService.createCase(request);
        assertNotNull(response);
        assertEquals("operation_rose_001", response.getCaseId());

        Path caseDir = tempDir.resolve("operation_rose_001");
        assertTrue(Files.exists(caseDir));
        assertTrue(Files.exists(caseDir.resolve("data")));
        assertTrue(Files.exists(caseDir.resolve("processed")));
        assertTrue(Files.exists(caseDir.resolve("metadata.json")));
        assertTrue(Files.exists(caseDir.resolve("llm.json")));
    }

    @Test
    void testSaveUploadedFilesAndBuildWarehouse() throws IOException {
        CaseRequest request = new CaseRequest("Test Case");
        request.setCaseId("test_case_001");
        caseService.createCase(request);

        MockMultipartFile file1 = new MockMultipartFile(
                "files",
                "fir.txt",
                "text/plain",
                "Rose Mathew was found in Anna Nagar apartment.".getBytes(StandardCharsets.UTF_8)
        );

        MockMultipartFile file2 = new MockMultipartFile(
                "files",
                "witness.txt",
                "text/plain",
                "Ananya stated she saw Arjun near the apartment at 17:30.".getBytes(StandardCharsets.UTF_8)
        );

        CaseResponse response = caseService.saveUploadedFiles("test_case_001", List.of(file1, file2));
        assertNotNull(response);
        assertEquals(2, response.getFileCount());

        Path warehouseFile = tempDir.resolve("test_case_001").resolve("warehouse.txt");
        assertTrue(Files.exists(warehouseFile));

        String warehouseContent = Files.readString(warehouseFile);
        assertTrue(warehouseContent.contains("SOURCE_FILE: fir.txt"));
        assertTrue(warehouseContent.contains("END_SOURCE: fir.txt"));
        assertTrue(warehouseContent.contains("SOURCE_FILE: witness.txt"));
        assertTrue(warehouseContent.contains("END_SOURCE: witness.txt"));
        assertTrue(warehouseContent.contains("Rose Mathew was found"));
        assertTrue(warehouseContent.contains("Ananya stated she saw Arjun"));
    }
}
