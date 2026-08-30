package com.sherlock.ui.model;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;

import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.List;

@JsonIgnoreProperties(ignoreUnknown = true)
public class ChatMessageDto {

    private String role; // "user" or "sherlock"
    private String answer;
    private String content;
    private String timestamp;
    private String sessionId;
    private String sessionTitle;
    private List<String> referencedEntities = new ArrayList<>();
    private List<String> referencedSources = new ArrayList<>();
    private List<String> evidenceSnippets = new ArrayList<>();
    private List<String> highlightNodeIds = new ArrayList<>();
    private List<String> highlightRelationIds = new ArrayList<>();
    private List<String> cypherQueries = new ArrayList<>();
    private Integer toolCallsUsed = 0;

    public ChatMessageDto() {
        this.timestamp = LocalDateTime.now().format(DateTimeFormatter.ofPattern("hh:mm a"));
    }

    public ChatMessageDto(String role, String answer) {
        this();
        this.role = role;
        this.answer = answer;
    }

    public String getSessionId() { return sessionId; }
    public void setSessionId(String sessionId) { this.sessionId = sessionId; }

    public String getSessionTitle() { return sessionTitle; }
    public void setSessionTitle(String sessionTitle) { this.sessionTitle = sessionTitle; }

    public String getRole() { return role; }
    public void setRole(String role) { this.role = role; }

    public String getAnswer() { return answer; }
    public void setAnswer(String answer) { this.answer = answer; }

    public String getContent() { return content; }
    public void setContent(String content) { this.content = content; }

    public String getTimestamp() { return timestamp; }
    public void setTimestamp(String timestamp) { this.timestamp = timestamp; }

    public List<String> getReferencedEntities() { return referencedEntities; }
    public void setReferencedEntities(List<String> referencedEntities) { this.referencedEntities = referencedEntities; }

    public List<String> getReferencedSources() { return referencedSources; }
    public void setReferencedSources(List<String> referencedSources) { this.referencedSources = referencedSources; }

    public List<String> getEvidenceSnippets() { return evidenceSnippets; }
    public void setEvidenceSnippets(List<String> evidenceSnippets) { this.evidenceSnippets = evidenceSnippets; }

    public List<String> getHighlightNodeIds() { return highlightNodeIds; }
    public void setHighlightNodeIds(List<String> highlightNodeIds) { this.highlightNodeIds = highlightNodeIds; }

    public List<String> getHighlightRelationIds() { return highlightRelationIds; }
    public void setHighlightRelationIds(List<String> highlightRelationIds) { this.highlightRelationIds = highlightRelationIds; }

    public List<String> getCypherQueries() { return cypherQueries; }
    public void setCypherQueries(List<String> cypherQueries) { this.cypherQueries = cypherQueries; }

    public Integer getToolCallsUsed() { return toolCallsUsed; }
    public void setToolCallsUsed(Integer toolCallsUsed) { this.toolCallsUsed = toolCallsUsed; }
}
