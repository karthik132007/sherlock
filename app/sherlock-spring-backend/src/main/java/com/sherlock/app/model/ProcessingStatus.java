package com.sherlock.app.model;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;

import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;

@JsonIgnoreProperties(ignoreUnknown = true)
public class ProcessingStatus {
    private String caseId;
    private String status;
    private String message;
    private String scriptPath;
    private String command;
    // CopyOnWriteArrayList: the pipeline background thread appends log lines while
    // status-poll requests serialize this list — a plain ArrayList can throw
    // ConcurrentModificationException in that situation.
    private List<String> logs = new CopyOnWriteArrayList<>();
    private Integer exitCode;
    private boolean completed;
    private boolean success;

    public ProcessingStatus() {}

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

    public List<String> getLogs() { return logs; }
    public void setLogs(List<String> logs) { this.logs = logs; }

    public Integer getExitCode() { return exitCode; }
    public void setExitCode(Integer exitCode) { this.exitCode = exitCode; }

    public boolean isCompleted() { return completed; }
    public void setCompleted(boolean completed) { this.completed = completed; }

    public boolean isSuccess() { return success; }
    public void setSuccess(boolean success) { this.success = success; }
}
