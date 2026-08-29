package com.sherlock.app.service;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.sherlock.app.config.AppProperties;
import com.sherlock.app.model.GraphDataResponse;
import org.neo4j.driver.*;
import org.neo4j.driver.Record;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

import jakarta.annotation.PreDestroy;
import java.util.*;

@Service
public class Neo4jGraphService {

    private static final Logger log = LoggerFactory.getLogger(Neo4jGraphService.class);

    private final AppProperties appProperties;
    private final ObjectMapper objectMapper;
    private Driver driver;

    public Neo4jGraphService(AppProperties appProperties) {
        this.appProperties = appProperties;
        this.objectMapper = new ObjectMapper();
        initDriver();
    }

    private synchronized void initDriver() {
        // Guard against creating (and leaking) multiple drivers when several
        // threads call isConnected() concurrently while the driver is null.
        if (this.driver != null) {
            return;
        }
        AppProperties.Neo4jConfig cfg = appProperties.getNeo4j();
        if (cfg != null && cfg.isEnabled() && cfg.getUri() != null && !cfg.getUri().isBlank()) {
            try {
                AuthToken auth = (cfg.getUsername() != null && !cfg.getUsername().isBlank())
                        ? AuthTokens.basic(cfg.getUsername(), cfg.getPassword() != null ? cfg.getPassword() : "")
                        : AuthTokens.none();

                this.driver = GraphDatabase.driver(cfg.getUri(), auth);
                log.info("Initialized Neo4j driver connected to {}", cfg.getUri());
            } catch (Exception e) {
                log.warn("Could not initialize Neo4j driver: {}", e.getMessage());
                this.driver = null;
            }
        } else {
            this.driver = null;
        }
    }

    public boolean isConnected() {
        if (driver == null) {
            initDriver();
        }
        if (driver == null) return false;

        try {
            driver.verifyConnectivity();
            return true;
        } catch (Exception e) {
            return false;
        }
    }

    public Map<String, Object> getStatus() {
        Map<String, Object> status = new HashMap<>();
        AppProperties.Neo4jConfig cfg = appProperties.getNeo4j();
        boolean enabled = cfg != null && cfg.isEnabled();
        status.put("enabled", enabled);
        status.put("uri", cfg != null ? cfg.getUri() : "none");
        status.put("database", cfg != null ? cfg.getDatabase() : "neo4j");

        boolean connected = isConnected();
        status.put("connected", connected);
        status.put("message", connected ? "Connected to Neo4j" : "Neo4j is offline or unreachable (falling back to JSON graph files)");
        return status;
    }

    /**
     * Fetch graph data directly from Neo4j for a given caseId
     */
    public GraphDataResponse fetchGraphFromNeo4j(String caseId) {
        if (!isConnected()) {
            return null;
        }

        List<GraphDataResponse.Node> nodes = new ArrayList<>();
        List<GraphDataResponse.Edge> edges = new ArrayList<>();
        Map<String, GraphDataResponse.Node> nodeMap = new HashMap<>();

        String db = appProperties.getNeo4j().getDatabase();
        SessionConfig sessionConfig = (db != null && !db.isBlank())
                ? SessionConfig.forDatabase(db)
                : SessionConfig.defaultConfig();

        try (Session session = driver.session(sessionConfig)) {
            // 1. Fetch all nodes for this case/project directly from Neo4j
            String nodeQuery = "MATCH (n {project_id: $caseId}) " +
                    "RETURN n.id AS id, n.name AS name, n.type AS type, labels(n) AS labels, n.data AS data, n.mentions AS mentions, n.confidence AS confidence, n.aliases AS aliases, n.source_files AS sourceFiles, n.chunk_ids AS chunkIds";

            List<Record> nodeRecords = session.run(nodeQuery, Values.parameters("caseId", caseId)).list();
            for (Record rec : nodeRecords) {
                String id = rec.get("id").asString("");
                String name = rec.get("name").asString(id);
                String type = rec.get("type").asString("ENTITY");
                if ("ENTITY".equals(type) && !rec.get("labels").asList().isEmpty()) {
                    type = rec.get("labels").asList().get(0).toString();
                }

                GraphDataResponse.Node node = new GraphDataResponse.Node(id, name, type);
                if (!rec.get("confidence").isNull()) {
                    node.setConfidence(rec.get("confidence").asDouble());
                }
                if (!rec.get("mentions").isNull()) {
                    node.setMentions(rec.get("mentions").asInt());
                }
                if (!rec.get("aliases").isNull()) {
                    try {
                        node.setAliases(rec.get("aliases").asList(Value::asString));
                    } catch (Exception ignored) {}
                }
                if (!rec.get("sourceFiles").isNull()) {
                    try {
                        node.setSourceFiles(rec.get("sourceFiles").asList(Value::asString));
                    } catch (Exception ignored) {}
                }
                if (!rec.get("chunkIds").isNull()) {
                    try {
                        node.setChunkIds(rec.get("chunkIds").asList(Value::asString));
                    } catch (Exception ignored) {}
                }
                if (!rec.get("data").isNull()) {
                    String dataStr = rec.get("data").asString("{}");
                    try {
                        node.setData(objectMapper.readValue(dataStr, new com.fasterxml.jackson.core.type.TypeReference<Map<String, Object>>() {}));
                    } catch (Exception ignored) {
                        node.setData(Map.of("raw", dataStr));
                    }
                }

                nodes.add(node);
                nodeMap.put(name.toLowerCase(Locale.ROOT), node);
                if (id != null && !id.isBlank()) {
                    nodeMap.put(id.toLowerCase(Locale.ROOT), node);
                }
            }

            // 2. Fetch all relationships for this case/project directly from Neo4j
            String edgeQuery = "MATCH (s)-[r {project_id: $caseId}]->(t) " +
                    "RETURN s.name AS sourceName, s.id AS sourceId, s.type AS sourceType, " +
                    "       t.name AS targetName, t.id AS targetId, t.type AS targetType, " +
                    "       type(r) AS relType, r.relation_id AS relId, r.confidence AS confidence, " +
                    "       r.evidence_text AS evidenceText, r.source_file AS sourceFile, r.chunk_id AS chunkId";

            List<Record> edgeRecords = session.run(edgeQuery, Values.parameters("caseId", caseId)).list();
            for (Record rec : edgeRecords) {
                String sourceName = rec.get("sourceName").asString("");
                String targetName = rec.get("targetName").asString("");
                String relType = rec.get("relType").asString("RELATED_TO");

                GraphDataResponse.Edge edge = new GraphDataResponse.Edge(sourceName, relType, targetName);
                if (!rec.get("relId").isNull()) edge.setRelationId(rec.get("relId").asString());
                if (!rec.get("confidence").isNull()) edge.setConfidence(rec.get("confidence").asDouble());
                if (!rec.get("evidenceText").isNull()) edge.setEvidenceText(rec.get("evidenceText").asString());
                if (!rec.get("sourceFile").isNull()) edge.setSourceFile(rec.get("sourceFile").asString());
                if (!rec.get("chunkId").isNull()) edge.setChunkId(rec.get("chunkId").asString());

                edges.add(edge);

                // Add source / target nodes if not yet registered
                if (!nodeMap.containsKey(sourceName.toLowerCase(Locale.ROOT))) {
                    String sType = rec.get("sourceType").asString("ENTITY");
                    GraphDataResponse.Node sNode = new GraphDataResponse.Node(rec.get("sourceId").asString(sourceName), sourceName, sType);
                    nodes.add(sNode);
                    nodeMap.put(sourceName.toLowerCase(Locale.ROOT), sNode);
                }
                if (!nodeMap.containsKey(targetName.toLowerCase(Locale.ROOT))) {
                    String tType = rec.get("targetType").asString("ENTITY");
                    GraphDataResponse.Node tNode = new GraphDataResponse.Node(rec.get("targetId").asString(targetName), targetName, tType);
                    nodes.add(tNode);
                    nodeMap.put(targetName.toLowerCase(Locale.ROOT), tNode);
                }
            }

            if (nodes.isEmpty() && edges.isEmpty()) {
                return null;
            }

            return new GraphDataResponse(caseId, nodes, edges);
        } catch (Exception e) {
            log.warn("Error querying Neo4j for case {}: {}", caseId, e.getMessage());
            return null;
        }
    }

    /**
     * Sync JSON graph data into Neo4j
     */
    public boolean syncGraphToNeo4j(String caseId, GraphDataResponse graphData) {
        if (!isConnected() || graphData == null) {
            return false;
        }

        String db = appProperties.getNeo4j().getDatabase();
        SessionConfig sessionConfig = (db != null && !db.isBlank())
                ? SessionConfig.forDatabase(db)
                : SessionConfig.defaultConfig();

        try (Session session = driver.session(sessionConfig)) {
            session.executeWrite(tx -> {
                // Merge Nodes
                if (graphData.getNodes() != null) {
                    for (GraphDataResponse.Node node : graphData.getNodes()) {
                        String dataJson = "{}";
                        if (node.getData() != null) {
                            try {
                                dataJson = objectMapper.writeValueAsString(node.getData());
                            } catch (JsonProcessingException ignored) {}
                        }

                        String safeType = sanitizeLabel(node.getType() != null ? node.getType() : "Entity");

                        String nodeCypher = String.format(
                                "MERGE (n:%s {id: $id, project_id: $caseId}) " +
                                "ON CREATE SET n.name = $name, n.type = $type, n.confidence = $confidence, n.mentions = $mentions, n.data = $data, n.aliases = $aliases, n.source_files = $sourceFiles, n.chunk_ids = $chunkIds " +
                                "ON MATCH SET n.name = $name, n.type = $type, n.confidence = $confidence, n.mentions = $mentions, n.data = $data, n.aliases = $aliases, n.source_files = $sourceFiles, n.chunk_ids = $chunkIds",
                                safeType
                        );

                        tx.run(nodeCypher, Values.parameters(
                                "id", node.getId() != null ? node.getId() : node.getName(),
                                "caseId", caseId,
                                "name", node.getName(),
                                "type", node.getType() != null ? node.getType() : "ENTITY",
                                "confidence", node.getConfidence() != null ? node.getConfidence() : 1.0,
                                "mentions", node.getMentions() != null ? node.getMentions() : 1,
                                "data", dataJson,
                                "aliases", node.getAliases() != null ? node.getAliases() : List.of(),
                                "sourceFiles", node.getSourceFiles() != null ? node.getSourceFiles() : List.of(),
                                "chunkIds", node.getChunkIds() != null ? node.getChunkIds() : List.of()
                        ));
                    }
                }

                // Merge Relationships
                if (graphData.getEdges() != null) {
                    for (GraphDataResponse.Edge edge : graphData.getEdges()) {
                        String safeRel = sanitizeRelation(edge.getRelation() != null ? edge.getRelation() : "RELATED_TO");

                        String relCypher = String.format(
                                "MATCH (s) WHERE (s.name = $source OR s.id = $source) AND s.project_id = $caseId " +
                                "MATCH (t) WHERE (t.name = $target OR t.id = $target) AND t.project_id = $caseId " +
                                "MERGE (s)-[r:%s {relation_id: $relId, project_id: $caseId}]->(t) " +
                                "ON CREATE SET r.confidence = $confidence, r.evidence_text = $evidenceText, r.source_file = $sourceFile, r.chunk_id = $chunkId " +
                                "ON MATCH SET r.confidence = $confidence, r.evidence_text = $evidenceText, r.source_file = $sourceFile, r.chunk_id = $chunkId",
                                safeRel
                        );

                        String relId = edge.getRelationId() != null ? edge.getRelationId() : UUID.randomUUID().toString();

                        tx.run(relCypher, Values.parameters(
                                "source", edge.getSource(),
                                "target", edge.getTarget(),
                                "caseId", caseId,
                                "relId", relId,
                                "confidence", edge.getConfidence() != null ? edge.getConfidence() : 1.0,
                                "evidenceText", edge.getEvidenceText() != null ? edge.getEvidenceText() : "",
                                "sourceFile", edge.getSourceFile() != null ? edge.getSourceFile() : "",
                                "chunkId", edge.getChunkId() != null ? edge.getChunkId() : ""
                        ));
                    }
                }
                return null;
            });

            log.info("Successfully synchronized case {} graph data to Neo4j", caseId);
            return true;
        } catch (Exception e) {
            log.warn("Failed to sync case {} to Neo4j: {}", caseId, e.getMessage());
            return false;
        }
    }

    /**
     * Execute arbitrary Cypher query against Neo4j and return raw map records
     */
    public List<Map<String, Object>> executeCypher(String cypherQuery, Map<String, Object> params) {
        if (!isConnected()) {
            return Collections.emptyList();
        }

        String db = appProperties.getNeo4j().getDatabase();
        SessionConfig sessionConfig = (db != null && !db.isBlank())
                ? SessionConfig.forDatabase(db)
                : SessionConfig.defaultConfig();

        List<Map<String, Object>> results = new ArrayList<>();
        try (Session session = driver.session(sessionConfig)) {
            Value cypherParams = params != null ? Values.value(params) : Values.EmptyMap;
            List<Record> records = session.run(cypherQuery, cypherParams).list();
            for (Record r : records) {
                results.add(r.asMap());
            }
        } catch (Exception e) {
            log.warn("Cypher execution failed: {} - Query: {}", e.getMessage(), cypherQuery);
        }
        return results;
    }

    private String sanitizeLabel(String label) {
        if (label == null || label.isBlank()) return "Entity";
        String clean = label.replaceAll("[^a-zA-Z0-9_]", "");
        if (clean.isEmpty()) return "Entity";
        // Cypher identifiers cannot start with a digit
        if (Character.isDigit(clean.charAt(0))) {
            clean = "L_" + clean;
        }
        return Character.toUpperCase(clean.charAt(0)) + clean.substring(1).toLowerCase(Locale.ROOT);
    }

    private String sanitizeRelation(String rel) {
        if (rel == null || rel.isBlank()) return "RELATED_TO";
        String clean = rel.toUpperCase(Locale.ROOT).replaceAll("[^A-Z0-9_]", "_");
        if (clean.isEmpty()) return "RELATED_TO";
        // Cypher identifiers cannot start with a digit
        if (Character.isDigit(clean.charAt(0))) {
            clean = "R_" + clean;
        }
        // Underscores alone are not a valid identifier either
        if (clean.replaceAll("_", "").isEmpty()) {
            return "RELATED_TO";
        }
        return clean;
    }

    @PreDestroy
    public void close() {
        if (driver != null) {
            try {
                driver.close();
            } catch (Exception ignored) {}
        }
    }
}
