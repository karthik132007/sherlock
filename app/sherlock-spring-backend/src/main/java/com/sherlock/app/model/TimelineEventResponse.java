package com.sherlock.app.model;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import com.fasterxml.jackson.annotation.JsonProperty;

import java.util.ArrayList;
import java.util.List;

@JsonIgnoreProperties(ignoreUnknown = true)
public class TimelineEventResponse {

    private String project;
    private boolean sorted;
    private int totalEvents;
    private List<TimelineEvent> timeline = new ArrayList<>();

    public TimelineEventResponse() {}

    public String getProject() { return project; }
    public void setProject(String project) { this.project = project; }

    public boolean isSorted() { return sorted; }
    public void setSorted(boolean sorted) { this.sorted = sorted; }

    public int getTotalEvents() { return totalEvents; }
    public void setTotalEvents(int totalEvents) { this.totalEvents = totalEvents; }

    public List<TimelineEvent> getTimeline() { return timeline; }
    public void setTimeline(List<TimelineEvent> timeline) { this.timeline = timeline; }

    @JsonIgnoreProperties(ignoreUnknown = true)
    public static class TimelineEvent {
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

        private String event;

        public TimelineEvent() {}

        public String getEvent() { return event != null ? event : (title != null ? title : description); }
        @JsonProperty("event")
        public void setEvent(String event) {
            this.event = event;
            if (this.title == null || this.title.isBlank() || this.title.equals("Event")) {
                this.title = event;
            }
            if (this.description == null || this.description.isBlank()) {
                this.description = event;
            }
        }

        public String getTimestamp() { return timestamp; }
        public void setTimestamp(String timestamp) { this.timestamp = timestamp; }

        public String getTitle() { return title != null ? title : (event != null ? event : description); }
        public void setTitle(String title) { this.title = title; }

        public String getDescription() { return description != null ? description : (event != null ? event : title); }
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
