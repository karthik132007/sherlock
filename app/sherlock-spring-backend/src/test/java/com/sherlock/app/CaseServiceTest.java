package com.sherlock.app;

import com.sherlock.app.config.AppProperties;
import com.sherlock.app.model.CaseRequest;
import com.sherlock.app.model.CaseResponse;
import com.sherlock.app.model.ChatRequest;
import com.sherlock.app.model.ChatResponse;
import com.sherlock.app.model.LlmConfigRequest;
import com.sherlock.app.service.CaseService;
import com.sherlock.app.service.Neo4jGraphService;
import com.sherlock.app.service.OllamaService;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.springframework.mock.web.MockMultipartFile;

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
        OllamaService ollamaService = new OllamaService();
        caseService = new CaseService(appProperties, neo4jGraphService, ollamaService);
    }

    @Test
    void testCreateCaseAndWriteLlmConfig() {
        CaseRequest request = new CaseRequest("Operation Rose");
        request.setCaseId("operation_rose_001");

        LlmConfigRequest llmConfig = new LlmConfigRequest("openai", "gpt-4o-mini", "sk-testkey",
                "https://api.openai.com/v1", 128000, 0.1);
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
                "Rose Mathew was found in Anna Nagar apartment.".getBytes(StandardCharsets.UTF_8));

        MockMultipartFile file2 = new MockMultipartFile(
                "files",
                "witness.txt",
                "text/plain",
                "Ananya stated she saw Arjun near the apartment at 17:30.".getBytes(StandardCharsets.UTF_8));

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

    @Test
    void testChatExchangeIsPersistedAtCaseRoot() throws IOException {
        CaseRequest request = new CaseRequest("Chat Case");
        request.setCaseId("chat_case_001");
        caseService.createCase(request);

        ChatResponse response = caseService.chatWithSherlock("chat_case_001", new ChatRequest("Who is Rose?"));

        assertNotNull(response);
        Path historyFile = tempDir.resolve("chat_case_001").resolve("agent_responses.json");
        assertTrue(Files.exists(historyFile));
        Path sessionsFile = tempDir.resolve("chat_case_001").resolve("chat_sessions.json");
        assertTrue(Files.exists(sessionsFile));
        String history = Files.readString(historyFile);
        assertTrue(history.contains("Who is Rose?"));
        assertTrue(history.contains("No processed knowledge graph"));
        assertEquals(2, caseService.getChatHistory("chat_case_001").size());
    }

    @Test
    void testMultipleChatSessionsManagement() throws IOException {
        CaseRequest request = new CaseRequest("Multi Chat Case");
        request.setCaseId("multi_chat_001");
        caseService.createCase(request);

        // 1. Initial sessions should be empty
        assertEquals(0, caseService.getChatSessions("multi_chat_001").size());

        // 2. Create explicit session
        var session1 = caseService.createChatSession("multi_chat_001", "Session Alpha");
        assertNotNull(session1);
        assertEquals("Session Alpha", session1.getTitle());
        assertNotNull(session1.getSessionId());

        // 3. Send query in session 1
        ChatRequest req1 = new ChatRequest("First question in alpha");
        req1.setSessionId(session1.getSessionId());
        ChatResponse resp1 = caseService.chatWithSherlock("multi_chat_001", req1);
        assertEquals(session1.getSessionId(), resp1.getSessionId());

        // 4. Send query in brand new session (sessionId = null)
        ChatRequest req2 = new ChatRequest("Second question in beta");
        ChatResponse resp2 = caseService.chatWithSherlock("multi_chat_001", req2);
        assertNotNull(resp2.getSessionId());
        assertNotEquals(session1.getSessionId(), resp2.getSessionId());

        // 5. Verify sessions list has 2 sessions
        var sessions = caseService.getChatSessions("multi_chat_001");
        assertEquals(2, sessions.size());

        // 6. Delete session 1
        boolean deleted = caseService.deleteChatSession("multi_chat_001", session1.getSessionId());
        assertTrue(deleted);
        assertEquals(1, caseService.getChatSessions("multi_chat_001").size());
    }

    @Test
    void testGetFileContentAndWarehouse() throws IOException {
        CaseRequest request = new CaseRequest("Doc Case");
        request.setCaseId("doc_case_001");
        caseService.createCase(request);

        MockMultipartFile file = new MockMultipartFile(
                "files",
                "evidence_sample.txt",
                "text/plain",
                "Sample evidence text about the suspect.".getBytes(StandardCharsets.UTF_8));
        caseService.saveUploadedFiles("doc_case_001", List.of(file));

        String content = caseService.getFileContent("doc_case_001", "evidence_sample.txt");
        assertEquals("Sample evidence text about the suspect.", content);

        String warehouse = caseService.getWarehouseContent("doc_case_001");
        assertTrue(warehouse.contains("SOURCE_FILE: evidence_sample.txt"));
        assertTrue(warehouse.contains("Sample evidence text about the suspect."));
    }
}
