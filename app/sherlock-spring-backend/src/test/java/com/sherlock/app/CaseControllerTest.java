package com.sherlock.app;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.sherlock.app.config.AppProperties;
import com.sherlock.app.controller.CaseController;
import com.sherlock.app.model.CaseRequest;
import com.sherlock.app.model.LlmConfigRequest;
import com.sherlock.app.service.CaseService;
import com.sherlock.app.service.Neo4jGraphService;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;

import java.nio.file.Path;

import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

class CaseControllerTest {

    @TempDir
    Path tempDir;

    private MockMvc mockMvc;
    private ObjectMapper objectMapper;

    @BeforeEach
    void setUp() {
        AppProperties appProperties = new AppProperties();
        appProperties.setBaseDirectory(tempDir.toString());
        appProperties.getNeo4j().setEnabled(false);
        Neo4jGraphService neo4jGraphService = new Neo4jGraphService(appProperties);
        CaseService caseService = new CaseService(appProperties, neo4jGraphService);
        CaseController caseController = new CaseController(caseService);
        mockMvc = MockMvcBuilders.standaloneSetup(caseController).build();
        objectMapper = new ObjectMapper();
    }

    @SuppressWarnings("null")
    @Test
    void testCreateCaseEndpoint() throws Exception {
        CaseRequest request = new CaseRequest("Operation Test");
        request.setCaseId("operation_test_100");
        request.setLlmConfig(new LlmConfigRequest("openai", "gpt-4o-mini", "sk-123", "", 128000, 0.1));

        mockMvc.perform(post("/api/cases")
                .contentType("application/json")
                .content(objectMapper.writeValueAsString(request)))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.caseId").value("operation_test_100"))
                .andExpect(jsonPath("$.caseName").value("Operation Test"));
    }

    @Test
    void testListCasesEndpoint() throws Exception {
        mockMvc.perform(get("/api/cases"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$").isArray());
    }

    @Test
    void testGetContradictionsEndpoint() throws Exception {
        CaseRequest request = new CaseRequest("Operation Test 2");
        request.setCaseId("operation_test_200");
        mockMvc.perform(post("/api/cases")
                .contentType("application/json")
                .content(objectMapper.writeValueAsString(request)))
                .andExpect(status().isCreated());

        mockMvc.perform(get("/api/cases/operation_test_200/contradictions"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.project").value("operation_test_200"))
                .andExpect(jsonPath("$.totalContradictions").value(0));

        mockMvc.perform(get("/api/cases/operation_test_200/contradictions/status"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.caseId").value("operation_test_200"));
    }

    @Test
    void testChatSessionsEndpoints() throws Exception {
        CaseRequest request = new CaseRequest("Operation Test 3");
        request.setCaseId("operation_test_300");
        mockMvc.perform(post("/api/cases")
                .contentType("application/json")
                .content(objectMapper.writeValueAsString(request)))
                .andExpect(status().isCreated());

        // List sessions - initially empty
        mockMvc.perform(get("/api/cases/operation_test_300/chat/sessions"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$").isArray());

        // Create new session
        mockMvc.perform(post("/api/cases/operation_test_300/chat/sessions")
                .contentType("application/json")
                .content(objectMapper.writeValueAsString(java.util.Map.of("title", "Alpha Session"))))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.title").value("Alpha Session"))
                .andExpect(jsonPath("$.sessionId").exists());
    }

    @Test
    void testGetOpinionEndpoint() throws Exception {
        CaseRequest request = new CaseRequest("Operation Test 4");
        request.setCaseId("operation_test_400");
        mockMvc.perform(post("/api/cases")
                .contentType("application/json")
                .content(objectMapper.writeValueAsString(request)))
                .andExpect(status().isCreated());

        mockMvc.perform(get("/api/cases/operation_test_400/opinion"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.case_id").value("operation_test_400"))
                .andExpect(jsonPath("$.executive_summary").exists());

        mockMvc.perform(get("/api/cases/operation_test_400/opinion/status"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.caseId").value("operation_test_400"));
    }
}

