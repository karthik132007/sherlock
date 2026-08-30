package com.sherlock.app.model;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import com.fasterxml.jackson.annotation.JsonProperty;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

@JsonIgnoreProperties(ignoreUnknown = true)
public class ContradictionResponse {

    private String project;
    private int totalContradictions;
    private String summary;

    @JsonProperty("severity_breakdown")
    private Map<String, Integer> severityBreakdown = new HashMap<>();

    @JsonProperty("types_breakdown")
    private Map<String, Integer> typesBreakdown = new HashMap<>();

    @JsonProperty("status_breakdown")
    private Map<String, Integer> statusBreakdown = new HashMap<>();

    private List<ContradictionItem> contradictions = new ArrayList<>();

    public ContradictionResponse() {}

    public String getProject() { return project; }
    public void setProject(String project) { this.project = project; }

    public int getTotalContradictions() { return totalContradictions; }
    public void setTotalContradictions(int totalContradictions) { this.totalContradictions = totalContradictions; }

    public String getSummary() { return summary; }
    public void setSummary(String summary) { this.summary = summary; }

    public Map<String, Integer> getSeverityBreakdown() { return severityBreakdown; }
    public void setSeverityBreakdown(Map<String, Integer> severityBreakdown) { this.severityBreakdown = severityBreakdown; }

    public Map<String, Integer> getTypesBreakdown() { return typesBreakdown; }
    public void setTypesBreakdown(Map<String, Integer> typesBreakdown) { this.typesBreakdown = typesBreakdown; }

    public Map<String, Integer> getStatusBreakdown() { return statusBreakdown; }
    public void setStatusBreakdown(Map<String, Integer> statusBreakdown) { this.statusBreakdown = statusBreakdown; }

    public List<ContradictionItem> getContradictions() { return contradictions; }
    public void setContradictions(List<ContradictionItem> contradictions) { this.contradictions = contradictions; }

    @JsonProperty("items")
    public void setItems(List<ContradictionItem> items) {
        if (this.contradictions == null || this.contradictions.isEmpty()) {
            this.contradictions = items;
        }
    }

    @JsonIgnoreProperties(ignoreUnknown = true)
    public static class ContradictionItem {
        @JsonProperty("contradiction_id")
        private String contradictionId;

        private String type;
        private String summary;
        private String description;
        private String severity;
        private Double confidence;

        @JsonProperty("entities_involved")
        private List<String> entitiesInvolved = new ArrayList<>();

        @JsonProperty("conflicting_points")
        private List<ConflictingPoint> conflictingPoints = new ArrayList<>();

        @JsonProperty("resolution_status")
        private String resolutionStatus;

        @JsonProperty("investigation_lead")
        private String investigationLead;

        public ContradictionItem() {}

        public String getContradictionId() { return contradictionId; }
        public void setContradictionId(String contradictionId) { this.contradictionId = contradictionId; }

        public String getType() { return type; }
        public void setType(String type) { this.type = type; }

        public String getSummary() { return summary; }
        public void setSummary(String summary) { this.summary = summary; }

        public String getDescription() { return description; }
        public void setDescription(String description) { this.description = description; }

        public String getSeverity() { return severity; }
        public void setSeverity(String severity) { this.severity = severity; }

        public Double getConfidence() { return confidence; }
        public void setConfidence(Double confidence) { this.confidence = confidence; }

        public List<String> getEntitiesInvolved() { return entitiesInvolved; }
        public void setEntitiesInvolved(List<String> entitiesInvolved) { this.entitiesInvolved = entitiesInvolved; }

        public List<ConflictingPoint> getConflictingPoints() { return conflictingPoints; }
        public void setConflictingPoints(List<ConflictingPoint> conflictingPoints) { this.conflictingPoints = conflictingPoints; }

        public String getResolutionStatus() { return resolutionStatus; }
        public void setResolutionStatus(String resolutionStatus) { this.resolutionStatus = resolutionStatus; }

        public String getInvestigationLead() { return investigationLead; }
        public void setInvestigationLead(String investigationLead) { this.investigationLead = investigationLead; }
    }

    @JsonIgnoreProperties(ignoreUnknown = true)
    public static class ConflictingPoint {
        private String claim;

        @JsonProperty("speaker_or_source")
        private String speakerOrSource;

        @JsonProperty("source_file")
        private String sourceFile;

        @JsonProperty("chunk_id")
        private String chunkId;

        private String quote;

        public ConflictingPoint() {}

        public String getClaim() { return claim; }
        public void setClaim(String claim) { this.claim = claim; }

        public String getSpeakerOrSource() { return speakerOrSource; }
        public void setSpeakerOrSource(String speakerOrSource) { this.speakerOrSource = speakerOrSource; }

        public String getSourceFile() { return sourceFile; }
        public void setSourceFile(String sourceFile) { this.sourceFile = sourceFile; }

        public String getChunkId() { return chunkId; }
        public void setChunkId(String chunkId) { this.chunkId = chunkId; }

        public String getQuote() { return quote; }
        public void setQuote(String quote) { this.quote = quote; }
    }
}
