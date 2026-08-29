package com.sherlock.app.model;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import com.fasterxml.jackson.annotation.JsonProperty;
import jakarta.validation.constraints.NotBlank;

@JsonIgnoreProperties(ignoreUnknown = true)
public class CaseRequest {

    @NotBlank(message = "Case name is required")
    private String caseName;

    private String caseId;

    @JsonProperty("llmConfig")
    private LlmConfigRequest llmConfig;

    public CaseRequest() {}

    public CaseRequest(String caseName) {
        this.caseName = caseName;
    }

    public String getCaseName() {
        return caseName;
    }

    public void setCaseName(String caseName) {
        this.caseName = caseName;
    }

    public String getCaseId() {
        return caseId;
    }

    public void setCaseId(String caseId) {
        this.caseId = caseId;
    }

    public LlmConfigRequest getLlmConfig() {
        return llmConfig;
    }

    public void setLlmConfig(LlmConfigRequest llmConfig) {
        this.llmConfig = llmConfig;
    }
}
