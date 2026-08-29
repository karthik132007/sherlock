package com.sherlock.ui.model;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import com.fasterxml.jackson.annotation.JsonProperty;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

@JsonIgnoreProperties(ignoreUnknown = true)
public class GraphDataDto {

    private String caseId;
    private List<NodeDto> nodes = new ArrayList<>();
    private List<EdgeDto> edges = new ArrayList<>();
    private Map<String, Object> stats = new HashMap<>();

    public GraphDataDto() {}

    public String getCaseId() { return caseId; }
    public void setCaseId(String caseId) { this.caseId = caseId; }

    public List<NodeDto> getNodes() { return nodes; }
    public void setNodes(List<NodeDto> nodes) { this.nodes = nodes; }

    public List<EdgeDto> getEdges() { return edges; }
    public void setEdges(List<EdgeDto> edges) { this.edges = edges; }

    public Map<String, Object> getStats() { return stats; }
    public void setStats(Map<String, Object> stats) { this.stats = stats; }

    @JsonIgnoreProperties(ignoreUnknown = true)
    public static class NodeDto {
        private String id;
        private String name;
        private String type = "ENTITY";
        private Double confidence;

        @JsonProperty("source_files")
        private List<String> sourceFiles = new ArrayList<>();

        @JsonProperty("chunk_ids")
        private List<String> chunkIds = new ArrayList<>();

        private List<String> aliases = new ArrayList<>();
        private Map<String, Object> data = new HashMap<>();
        private Integer mentions = 1;

        // Visual layout coordinates for UI canvas
        private double x;
        private double y;
        private double vx;
        private double vy;

        public NodeDto() {}

        public NodeDto(String id, String name, String type) {
            this.id = id;
            this.name = name;
            this.type = type;
        }

        public String getId() { return id; }
        public void setId(String id) { this.id = id; }

        public String getName() { return name; }
        public void setName(String name) { this.name = name; }

        public String getType() { return type; }
        public void setType(String type) { this.type = type; }

        public Double getConfidence() { return confidence; }
        public void setConfidence(Double confidence) { this.confidence = confidence; }

        public List<String> getSourceFiles() { return sourceFiles; }
        public void setSourceFiles(List<String> sourceFiles) { this.sourceFiles = sourceFiles; }

        public List<String> getChunkIds() { return chunkIds; }
        public void setChunkIds(List<String> chunkIds) { this.chunkIds = chunkIds; }

        public List<String> getAliases() { return aliases; }
        public void setAliases(List<String> aliases) { this.aliases = aliases; }

        public Map<String, Object> getData() { return data; }
        public void setData(Map<String, Object> data) { this.data = data; }

        public Integer getMentions() { return mentions; }
        public void setMentions(Integer mentions) { this.mentions = mentions; }

        public double getX() { return x; }
        public void setX(double x) { this.x = x; }

        public double getY() { return y; }
        public void setY(double y) { this.y = y; }

        public double getVx() { return vx; }
        public void setVx(double vx) { this.vx = vx; }

        public double getVy() { return vy; }
        public void setVy(double vy) { this.vy = vy; }
    }

    @JsonIgnoreProperties(ignoreUnknown = true)
    public static class EdgeDto {
        @JsonProperty("relation_id")
        private String relationId;

        private String source;
        private String relation;
        private String target;
        private Double confidence = 1.0;

        @JsonProperty("evidence_text")
        private String evidenceText;

        @JsonProperty("source_file")
        private String sourceFile;

        @JsonProperty("chunk_id")
        private String chunkId;

        public EdgeDto() {}

        public String getRelationId() { return relationId; }
        public void setRelationId(String relationId) { this.relationId = relationId; }

        public String getSource() { return source; }
        public void setSource(String source) { this.source = source; }

        public String getRelation() { return relation; }
        public void setRelation(String relation) { this.relation = relation; }

        public String getTarget() { return target; }
        public void setTarget(String target) { this.target = target; }

        public Double getConfidence() { return confidence; }
        public void setConfidence(Double confidence) { this.confidence = confidence; }

        public String getEvidenceText() { return evidenceText; }
        public void setEvidenceText(String evidenceText) { this.evidenceText = evidenceText; }

        public String getSourceFile() { return sourceFile; }
        public void setSourceFile(String sourceFile) { this.sourceFile = sourceFile; }

        public String getChunkId() { return chunkId; }
        public void setChunkId(String chunkId) { this.chunkId = chunkId; }
    }
}
