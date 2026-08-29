package com.sherlock.ui.model;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import com.fasterxml.jackson.annotation.JsonProperty;

import java.util.ArrayList;
import java.util.List;

@JsonIgnoreProperties(ignoreUnknown = true)
public class TimelineEventDto {

    private String project;
    private boolean sorted;
    private int totalEvents;
    private List<TimelineEventItem> timeline = new ArrayList<>();

    public TimelineEventDto() {}

    public String getProject() { return project; }
    public void setProject(String project) { this.project = project; }

    public boolean isSorted() { return sorted; }
    public void setSorted(boolean sorted) { this.sorted = sorted; }

    public int getTotalEvents() { return totalEvents; }
    public void setTotalEvents(int totalEvents) { this.totalEvents = totalEvents; }

    public List<TimelineEventItem> getTimeline() { return timeline; }
    public void setTimeline(List<TimelineEventItem> timeline) { this.timeline = timeline; }

    @JsonIgnoreProperties(ignoreUnknown = true)
    public static class TimelineEventItem {
        private String timestamp;
        private String title;
        private String description;

        @JsonProperty("entities_involved")
        private List<String> entitiesInvolved = new ArrayList<>();

        @JsonProperty("source_file")
        private String sourceFile;

        @JsonProperty("chunk_id")
        private String chunkId;

        @JsonProperty("evidence_text")
        private String evidenceText;

        private Double confidence;

        public TimelineEventItem() {}

        public String getTimestamp() { return timestamp; }
        public void setTimestamp(String timestamp) { this.timestamp = timestamp; }

        public String getTitle() { return title; }
        public void setTitle(String title) { this.title = title; }

        public String getDescription() { return description; }
        public void setDescription(String description) { this.description = description; }

        public List<String> getEntitiesInvolved() { return entitiesInvolved; }
        public void setEntitiesInvolved(List<String> entitiesInvolved) { this.entitiesInvolved = entitiesInvolved; }

        public String getSourceFile() { return sourceFile; }
        public void setSourceFile(String sourceFile) { this.sourceFile = sourceFile; }

        public String getChunkId() { return chunkId; }
        public void setChunkId(String chunkId) { this.chunkId = chunkId; }

        public String getEvidenceText() { return evidenceText; }
        public void setEvidenceText(String evidenceText) { this.evidenceText = evidenceText; }

        public Double getConfidence() { return confidence; }
        public void setConfidence(Double confidence) { this.confidence = confidence; }
    }
}
