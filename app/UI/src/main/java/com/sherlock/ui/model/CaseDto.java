package com.sherlock.ui.model;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import com.fasterxml.jackson.annotation.JsonProperty;

import java.util.ArrayList;
import java.util.List;

@JsonIgnoreProperties(ignoreUnknown = true)
public class CaseDto {
    private String caseId;
    private String caseName;
    private String caseDirectory;
    private String dataDirectory;
    private String status;
    private int fileCount;
    private List<String> files = new ArrayList<>();
    private LlmConfigDto llmConfig;

    public CaseDto() {}

    public String getCaseId() { return caseId; }
    public void setCaseId(String caseId) { this.caseId = caseId; }

    public String getCaseName() { return caseName; }
    public void setCaseName(String caseName) { this.caseName = caseName; }

    public String getCaseDirectory() { return caseDirectory; }
    public void setCaseDirectory(String caseDirectory) { this.caseDirectory = caseDirectory; }

    public String getDataDirectory() { return dataDirectory; }
    public void setDataDirectory(String dataDirectory) { this.dataDirectory = dataDirectory; }

    public String getStatus() { return status; }
    public void setStatus(String status) { this.status = status; }

    public int getFileCount() { return fileCount; }
    public void setFileCount(int fileCount) { this.fileCount = fileCount; }

    public List<String> getFiles() { return files; }
    public void setFiles(List<String> files) { this.files = files; }

    public LlmConfigDto getLlmConfig() { return llmConfig; }
    public void setLlmConfig(LlmConfigDto llmConfig) { this.llmConfig = llmConfig; }
}
