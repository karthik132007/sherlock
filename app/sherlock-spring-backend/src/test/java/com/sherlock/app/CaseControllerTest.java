package com.sherlock.app;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.sherlock.app.config.AppProperties;
import com.sherlock.app.controller.CaseController;
import com.sherlock.app.model.CaseRequest;
import com.sherlock.app.model.LlmConfigRequest;
import com.sherlock.app.service.CaseService;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.springframework.http.MediaType;
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
        CaseService caseService = new CaseService(appProperties);
        CaseController caseController = new CaseController(caseService);
        mockMvc = MockMvcBuilders.standaloneSetup(caseController).build();
        objectMapper = new ObjectMapper();
    }

    @Test
    void testCreateCaseEndpoint() throws Exception {
        CaseRequest request = new CaseRequest("Operation Test");
        request.setCaseId("operation_test_100");
        request.setLlmConfig(new LlmConfigRequest("openai", "gpt-4o-mini", "sk-123", "", 128000, 0.1));

        mockMvc.perform(post("/api/cases")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(request)))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.caseId").value("operation_test_100"))
                .andExpect(jsonPath("$.caseName").value("Operation Test"));
    }

    @Test
    void testListCasesEndpoint() throws Exception {
        mockMvc.perform(get("/api/cases"))
                .andExpect(status().isOk());
    }
}
