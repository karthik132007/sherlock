package com.sherlock.app.service;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;

@Service
public class OllamaService {

    private static final Logger log = LoggerFactory.getLogger(OllamaService.class);

    private final ObjectMapper objectMapper;
    private final HttpClient httpClient;

    public OllamaService() {
        this.objectMapper = new ObjectMapper();
        this.httpClient = HttpClient.newBuilder()
                .connectTimeout(Duration.ofSeconds(4))
                .build();
    }

    private String getOllamaBaseUrl() {
        return "http://localhost:11434";
    }

    /**
     * Get list of locally available Ollama models by querying /api/tags
     */
    public List<String> getAvailableModels() {
        List<String> models = new ArrayList<>();
        try {
            HttpRequest request = HttpRequest.newBuilder()
                    .uri(URI.create(getOllamaBaseUrl() + "/api/tags"))
                    .timeout(Duration.ofSeconds(3))
                    .GET()
                    .build();

            HttpResponse<String> response = httpClient.send(request, HttpResponse.BodyHandlers.ofString());
            if (response.statusCode() == 200) {
                JsonNode root = objectMapper.readTree(response.body());
                if (root.has("models") && root.get("models").isArray()) {
                    for (JsonNode m : root.get("models")) {
                        if (m.has("name")) {
                            models.add(m.get("name").asText());
                        }
                    }
                }
            }
        } catch (Exception e) {
            log.debug("Ollama /api/tags query failed (Ollama may be offline): {}", e.getMessage());
        }
        return models;
    }

    /**
     * Chat / Generate using local Ollama model
     */
    public String generateCompletion(String model, String systemPrompt, String prompt) {
        try {
            String selectedModel = (model != null && !model.isBlank()) ? model : "gpt-oss:120b-cloud";
            Map<String, Object> payload = Map.of(
                    "model", selectedModel,
                    "system",
                    systemPrompt != null ? systemPrompt
                            : "You are Sherlock, an AI investigation intelligence assistant.",
                    "prompt", prompt,
                    "stream", false,
                    "options", Map.of("temperature", 0.1));

            String bodyJson = objectMapper.writeValueAsString(payload);

            HttpRequest request = HttpRequest.newBuilder()
                    .uri(URI.create(getOllamaBaseUrl() + "/api/generate"))
                    .timeout(Duration.ofSeconds(12))
                    .header("Content-Type", "application/json")
                    .POST(HttpRequest.BodyPublishers.ofString(bodyJson))
                    .build();

            HttpResponse<String> response = httpClient.send(request, HttpResponse.BodyHandlers.ofString());
            if (response.statusCode() == 200) {
                JsonNode root = objectMapper.readTree(response.body());
                if (root.has("response")) {
                    return root.get("response").asText().trim();
                }
            }
        } catch (Exception e) {
            log.warn("Ollama generation failed with model {}: {}", model, e.getMessage());
        }
        return null;
    }

    /**
     * Generate a Cypher query (CQL) from natural language using Ollama
     */
    public String generateCypherQuery(String caseId, String userQuestion, String model) {
        String systemPrompt = "You are a Neo4j Cypher Query Generator for the Sherlock Investigation Knowledge Graph.\n"
                +
                "Graph Schema:\n" +
                "- Nodes have label based on type (:Person, :Location, :Phone, :Document, :Event, :Organization, :Entity).\n"
                +
                "- Node properties: id, name, type, project_id, confidence, mentions, data (JSON string with attributes).\n"
                +
                "- Relationship properties: relation_id, confidence, evidence_text, source_file, chunk_id, project_id.\n"
                +
                "- Crucial: Every query MUST filter by project_id = '" + caseId + "'.\n" +
                "Output ONLY the valid Cypher query without markdown formatting, quotes, or conversational text.";

        String prompt = "Question: " + userQuestion + "\nGenerate Cypher query for project_id '" + caseId + "':";

        String cypher = generateCompletion(model, systemPrompt, prompt);
        if (cypher != null) {
            cypher = cypher.replaceAll("```cypher", "").replaceAll("```", "").trim();
        }
        return cypher;
    }
}
