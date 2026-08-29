package com.sherlock.app.model;

public class ProcessingStatus {
    private String caseId;
    private String status;
    private String message;
    private String scriptPath;
    private String command;

    public String getCaseId() { return caseId; }
    public void setCaseId(String caseId) { this.caseId = caseId; }
    public String getStatus() { return status; }
    public void setStatus(String status) { this.status = status; }
    public String getMessage() { return message; }
    public void setMessage(String message) { this.message = message; }
    public String getScriptPath() { return scriptPath; }
    public void setScriptPath(String scriptPath) { this.scriptPath = scriptPath; }
    public String getCommand() { return command; }
    public void setCommand(String command) { this.command = command; }
}
