package com.sherlock.app.model;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;

import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.List;

@JsonIgnoreProperties(ignoreUnknown = true)
public class ChatResponse {

    private String answer;
    private List<String> referencedEntities = new ArrayList<>();
    private List<String> referencedSources = new ArrayList<>();
    private List<String> evidenceSnippets = new ArrayList<>();
    /** Exact graph identifiers returned by the Python query agent for UI highlighting. */
    private List<String> highlightNodeIds = new ArrayList<>();
    private List<String> highlightRelationIds = new ArrayList<>();
    private List<String> cypherQueries = new ArrayList<>();
    private Integer toolCallsUsed = 0;
    private String timestamp;

    public ChatResponse() {
        this.timestamp = LocalDateTime.now().format(DateTimeFormatter.ofPattern("hh:mm a"));
    }

    public ChatResponse(String answer) {
        this();
        this.answer = answer;
    }

    public String getAnswer() { return answer; }
    public void setAnswer(String answer) { this.answer = answer; }

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

    public String getTimestamp() { return timestamp; }
    public void setTimestamp(String timestamp) { this.timestamp = timestamp; }
}
