package com.sherlock.app.model;

import java.time.LocalDateTime;

public class CaseResponse {
    private String caseId;
    private String caseName;
    private String caseDirectory;
    private String dataDirectory;
    private LocalDateTime createdAt;
    private String status;

    public CaseResponse() {
    }

    public CaseResponse(String caseId, String caseName, String caseDirectory, String dataDirectory, LocalDateTime createdAt, String status) {
        this.caseId = caseId;
        this.caseName = caseName;
        this.caseDirectory = caseDirectory;
        this.dataDirectory = dataDirectory;
        this.createdAt = createdAt;
        this.status = status;
    }

    public String getCaseId() { return caseId; }
    public void setCaseId(String caseId) { this.caseId = caseId; }
    public String getCaseName() { return caseName; }
    public void setCaseName(String caseName) { this.caseName = caseName; }
    public String getCaseDirectory() { return caseDirectory; }
    public void setCaseDirectory(String caseDirectory) { this.caseDirectory = caseDirectory; }
    public String getDataDirectory() { return dataDirectory; }
    public void setDataDirectory(String dataDirectory) { this.dataDirectory = dataDirectory; }
    public LocalDateTime getCreatedAt() { return createdAt; }
    public void setCreatedAt(LocalDateTime createdAt) { this.createdAt = createdAt; }
    public String getStatus() { return status; }
    public void setStatus(String status) { this.status = status; }
}
