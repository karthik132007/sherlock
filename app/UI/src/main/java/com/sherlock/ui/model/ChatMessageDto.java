package com.sherlock.ui.model;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;

import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.List;

@JsonIgnoreProperties(ignoreUnknown = true)
public class ChatMessageDto {

    private String role; // "user" or "sherlock"
    private String content;
    private String timestamp;
    private List<String> referencedEntities = new ArrayList<>();
    private List<String> referencedSources = new ArrayList<>();
    private List<String> evidenceSnippets = new ArrayList<>();

    public ChatMessageDto() {
        this.timestamp = LocalDateTime.now().format(DateTimeFormatter.ofPattern("hh:mm a"));
    }

    public ChatMessageDto(String role, String content) {
        this();
        this.role = role;
        this.content = content;
    }

    public String getRole() { return role; }
    public void setRole(String role) { this.role = role; }

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
}
