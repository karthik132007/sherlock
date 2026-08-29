package com.sherlock.app.model;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import com.fasterxml.jackson.annotation.JsonProperty;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

@JsonIgnoreProperties(ignoreUnknown = true)
public class GraphDataResponse {

    private String caseId;
    private List<Node> nodes = new ArrayList<>();
    private List<Edge> edges = new ArrayList<>();
    private Map<String, Object> stats = new HashMap<>();

    public GraphDataResponse() {}

    public GraphDataResponse(String caseId, List<Node> nodes, List<Edge> edges) {
        this.caseId = caseId;
        this.nodes = nodes;
        this.edges = edges;
        this.stats.put("totalNodes", nodes != null ? nodes.size() : 0);
        this.stats.put("totalEdges", edges != null ? edges.size() : 0);
    }

    public String getCaseId() { return caseId; }
    public void setCaseId(String caseId) { this.caseId = caseId; }

    public List<Node> getNodes() { return nodes; }
    public void setNodes(List<Node> nodes) { this.nodes = nodes; }

    public List<Edge> getEdges() { return edges; }
    public void setEdges(List<Edge> edges) { this.edges = edges; }

    public Map<String, Object> getStats() { return stats; }
    public void setStats(Map<String, Object> stats) { this.stats = stats; }

    @JsonIgnoreProperties(ignoreUnknown = true)
    public static class Node {
        private String id;
        private String name;
        private String type = "UNKNOWN";
        private Double confidence;

        @JsonProperty("source_files")
        private List<String> sourceFiles = new ArrayList<>();

        @JsonProperty("chunk_ids")
        private List<String> chunkIds = new ArrayList<>();

        private List<String> aliases = new ArrayList<>();
        private Map<String, Object> data = new HashMap<>();
        private Integer mentions = 1;

        public Node() {}

        public Node(String id, String name, String type) {
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
    }

    @JsonIgnoreProperties(ignoreUnknown = true)
    public static class Edge {
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

        private Map<String, Object> metadata = new HashMap<>();

        public Edge() {}

        public Edge(String source, String relation, String target) {
            this.relationId = "rel_" + UUID.randomUUID().toString().substring(0, 8);
            this.source = source;
            this.relation = relation;
            this.target = target;
        }

        public Edge(String relationId, String source, String relation, String target) {
            this.relationId = relationId;
            this.source = source;
            this.relation = relation;
            this.target = target;
        }

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

        public Map<String, Object> getMetadata() { return metadata; }
        public void setMetadata(Map<String, Object> metadata) { this.metadata = metadata; }
    }
}
