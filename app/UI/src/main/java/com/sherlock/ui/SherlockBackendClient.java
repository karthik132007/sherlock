package com.sherlock.ui;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.DeserializationFeature;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.SerializationFeature;
import com.fasterxml.jackson.datatype.jsr310.JavaTimeModule;
import com.sherlock.ui.model.*;

import java.io.File;
import java.io.IOException;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.HashMap;
import java.util.List;
import java.util.ArrayList;
import java.util.Map;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

public class SherlockBackendClient {

    private static final String DEFAULT_BASE_URL = "http://localhost:8080/api";
    private final String baseUrl;
    private final HttpClient httpClient;
    private final ObjectMapper objectMapper;
    private final ExecutorService ioExecutor = Executors.newCachedThreadPool();

    public SherlockBackendClient() {
        this(DEFAULT_BASE_URL);
    }

    public SherlockBackendClient(String baseUrl) {
        this.baseUrl = baseUrl;
        this.httpClient = HttpClient.newBuilder()
                .connectTimeout(Duration.ofSeconds(10))
                .build();
        this.objectMapper = new ObjectMapper();
        this.objectMapper.registerModule(new JavaTimeModule());
        this.objectMapper.disable(SerializationFeature.WRITE_DATES_AS_TIMESTAMPS);
        this.objectMapper.configure(DeserializationFeature.FAIL_ON_UNKNOWN_PROPERTIES, false);
    }

    public CompletableFuture<CaseDto> createCaseAsync(String caseName, String caseId, LlmConfigDto llmConfig) {
        return CompletableFuture.supplyAsync(() -> {
            try {
                Map<String, Object> payload = new HashMap<>();
                payload.put("caseName", caseName);
                if (caseId != null && !caseId.isBlank()) {
                    payload.put("caseId", caseId);
                }
                if (llmConfig != null) {
                    payload.put("llmConfig", llmConfig);
                }

                String json = objectMapper.writeValueAsString(payload);
                HttpRequest request = HttpRequest.newBuilder()
                        .uri(URI.create(baseUrl + "/cases"))
                        .header("Content-Type", "application/json")
                        .POST(HttpRequest.BodyPublishers.ofString(json, StandardCharsets.UTF_8))
                        .timeout(Duration.ofSeconds(20))
                        .build();

                HttpResponse<String> response = httpClient.send(request, HttpResponse.BodyHandlers.ofString());
                if (response.statusCode() >= 400) {
                    throw new IOException("Backend error (" + response.statusCode() + "): " + response.body());
                }
                return objectMapper.readValue(response.body(), CaseDto.class);
            } catch (Exception e) {
                throw new RuntimeException("Failed to create case: " + e.getMessage(), e);
            }
        }, ioExecutor);
    }

    public CompletableFuture<CaseDto> uploadFilesAsync(String caseId, List<File> files) {
        return CompletableFuture.supplyAsync(() -> {
            try {
                var multipart = new MultipartFormDataBuilder();
                for (File file : files) {
                    multipart.addFile("files", file);
                }

                HttpRequest request = HttpRequest.newBuilder()
                        .uri(URI.create(baseUrl + "/cases/" + caseId + "/files"))
                        .header("Content-Type", multipart.contentType())
                        .POST(HttpRequest.BodyPublishers.ofByteArray(multipart.build()))
                        .timeout(Duration.ofSeconds(60))
                        .build();

                HttpResponse<String> response = httpClient.send(request, HttpResponse.BodyHandlers.ofString());
                if (response.statusCode() >= 400) {
                    throw new IOException("Backend error (" + response.statusCode() + "): " + response.body());
                }
                return objectMapper.readValue(response.body(), CaseDto.class);
            } catch (Exception e) {
                throw new RuntimeException("Failed to upload files: " + e.getMessage(), e);
            }
        }, ioExecutor);
    }

    public CompletableFuture<CaseDto> getCaseAsync(String caseId) {
        return CompletableFuture.supplyAsync(() -> {
            try {
                HttpRequest request = HttpRequest.newBuilder()
                        .uri(URI.create(baseUrl + "/cases/" + caseId))
                        .GET()
                        .timeout(Duration.ofSeconds(10))
                        .build();
                HttpResponse<String> response = httpClient.send(request, HttpResponse.BodyHandlers.ofString());
                if (response.statusCode() >= 400) {
                    throw new IOException("Backend error (" + response.statusCode() + "): " + response.body());
                }
                return objectMapper.readValue(response.body(), CaseDto.class);
            } catch (Exception e) {
                throw new RuntimeException("Failed to get case: " + e.getMessage(), e);
            }
        }, ioExecutor);
    }

    public CompletableFuture<String> getFileContentAsync(String caseId, String fileName) {
        return CompletableFuture.supplyAsync(() -> {
            try {
                String encodedFileName = java.net.URLEncoder.encode(fileName, StandardCharsets.UTF_8);
                HttpRequest request = HttpRequest.newBuilder()
                        .uri(URI.create(baseUrl + "/cases/" + caseId + "/files/" + encodedFileName))
                        .GET()
                        .timeout(Duration.ofSeconds(15))
                        .build();
                HttpResponse<String> response = httpClient.send(request, HttpResponse.BodyHandlers.ofString());
                if (response.statusCode() >= 400) {
                    throw new IOException("Backend error (" + response.statusCode() + "): " + response.body());
                }
                return response.body();
            } catch (Exception e) {
                throw new RuntimeException("Failed to load file " + fileName + ": " + e.getMessage(), e);
            }
        }, ioExecutor);
    }

    public CompletableFuture<String> getWarehouseContentAsync(String caseId) {
        return CompletableFuture.supplyAsync(() -> {
            try {
                HttpRequest request = HttpRequest.newBuilder()
                        .uri(URI.create(baseUrl + "/cases/" + caseId + "/warehouse"))
                        .GET()
                        .timeout(Duration.ofSeconds(15))
                        .build();
                HttpResponse<String> response = httpClient.send(request, HttpResponse.BodyHandlers.ofString());
                if (response.statusCode() >= 400) {
                    throw new IOException("Backend error (" + response.statusCode() + "): " + response.body());
                }
                return response.body();
            } catch (Exception e) {
                throw new RuntimeException("Failed to load warehouse content: " + e.getMessage(), e);
            }
        }, ioExecutor);
    }

    public CompletableFuture<ProcessingStatusDto> startTimelineProcessingAsync(String caseId) {
        return CompletableFuture.supplyAsync(() -> {
            try {
                HttpRequest request = HttpRequest.newBuilder()
                        .uri(URI.create(baseUrl + "/cases/" + caseId + "/timeline/process"))
                        .POST(HttpRequest.BodyPublishers.noBody())
                        .build();
                HttpResponse<String> response = httpClient.send(request, HttpResponse.BodyHandlers.ofString());
                return objectMapper.readValue(response.body(), ProcessingStatusDto.class);
            } catch (Exception e) {
                throw new RuntimeException("Failed to start timeline processing: " + e.getMessage(), e);
            }
        });
    }

    public CompletableFuture<ProcessingStatusDto> getTimelineProcessingStatusAsync(String caseId) {
        return CompletableFuture.supplyAsync(() -> {
            try {
                HttpRequest request = HttpRequest.newBuilder()
                        .uri(URI.create(baseUrl + "/cases/" + caseId + "/timeline/status"))
                        .GET()
                        .build();
                HttpResponse<String> response = httpClient.send(request, HttpResponse.BodyHandlers.ofString());
                return objectMapper.readValue(response.body(), ProcessingStatusDto.class);
            } catch (Exception e) {
                throw new RuntimeException("Failed to get timeline processing status: " + e.getMessage(), e);
            }
        });
    }

    public CompletableFuture<ProcessingStatusDto> startContradictionsProcessingAsync(String caseId) {
        return CompletableFuture.supplyAsync(() -> {
            try {
                HttpRequest request = HttpRequest.newBuilder()
                        .uri(URI.create(baseUrl + "/cases/" + caseId + "/contradictions/process"))
                        .POST(HttpRequest.BodyPublishers.noBody())
                        .build();
                HttpResponse<String> response = httpClient.send(request, HttpResponse.BodyHandlers.ofString());
                return objectMapper.readValue(response.body(), ProcessingStatusDto.class);
            } catch (Exception e) {
                throw new RuntimeException("Failed to start contradiction processing: " + e.getMessage(), e);
            }
        });
    }

    public CompletableFuture<ProcessingStatusDto> getContradictionsProcessingStatusAsync(String caseId) {
        return CompletableFuture.supplyAsync(() -> {
            try {
                HttpRequest request = HttpRequest.newBuilder()
                        .uri(URI.create(baseUrl + "/cases/" + caseId + "/contradictions/status"))
                        .GET()
                        .build();
                HttpResponse<String> response = httpClient.send(request, HttpResponse.BodyHandlers.ofString());
                return objectMapper.readValue(response.body(), ProcessingStatusDto.class);
            } catch (Exception e) {
                throw new RuntimeException("Failed to get contradiction processing status: " + e.getMessage(), e);
            }
        });
    }

    public CompletableFuture<ProcessingStatusDto> startOpinionProcessingAsync(String caseId) {
        return CompletableFuture.supplyAsync(() -> {
            try {
                HttpRequest request = HttpRequest.newBuilder()
                        .uri(URI.create(baseUrl + "/cases/" + caseId + "/opinion/process"))
                        .POST(HttpRequest.BodyPublishers.noBody())
                        .build();
                HttpResponse<String> response = httpClient.send(request, HttpResponse.BodyHandlers.ofString());
                return objectMapper.readValue(response.body(), ProcessingStatusDto.class);
            } catch (Exception e) {
                throw new RuntimeException("Failed to start opinion processing: " + e.getMessage(), e);
            }
        });
    }

    public CompletableFuture<ProcessingStatusDto> getOpinionProcessingStatusAsync(String caseId) {
        return CompletableFuture.supplyAsync(() -> {
            try {
                HttpRequest request = HttpRequest.newBuilder()
                        .uri(URI.create(baseUrl + "/cases/" + caseId + "/opinion/status"))
                        .GET()
                        .build();
                HttpResponse<String> response = httpClient.send(request, HttpResponse.BodyHandlers.ofString());
                return objectMapper.readValue(response.body(), ProcessingStatusDto.class);
            } catch (Exception e) {
                throw new RuntimeException("Failed to get opinion processing status: " + e.getMessage(), e);
            }
        });
    }

    public CompletableFuture<ProcessingStatusDto> startProcessingAsync(String caseId) {
        return CompletableFuture.supplyAsync(() -> {
            try {
                HttpRequest request = HttpRequest.newBuilder()
                        .uri(URI.create(baseUrl + "/cases/" + caseId + "/process"))
                        .POST(HttpRequest.BodyPublishers.noBody())
                        .timeout(Duration.ofSeconds(25))
                        .build();

                HttpResponse<String> response = httpClient.send(request, HttpResponse.BodyHandlers.ofString());
                if (response.statusCode() >= 400) {
                    throw new IOException("Backend error (" + response.statusCode() + "): " + response.body());
                }
                return objectMapper.readValue(response.body(), ProcessingStatusDto.class);
            } catch (Exception e) {
                throw new RuntimeException("Failed to start processing: " + e.getMessage(), e);
            }
        }, ioExecutor);
    }

    public CompletableFuture<ProcessingStatusDto> getProcessingStatusAsync(String caseId) {
        return CompletableFuture.supplyAsync(() -> {
            try {
                HttpRequest request = HttpRequest.newBuilder()
                        .uri(URI.create(baseUrl + "/cases/" + caseId + "/status"))
                        .GET()
                        .timeout(Duration.ofSeconds(10))
                        .build();

                HttpResponse<String> response = httpClient.send(request, HttpResponse.BodyHandlers.ofString());
                if (response.statusCode() >= 400) {
                    throw new IOException("Backend error (" + response.statusCode() + "): " + response.body());
                }
                return objectMapper.readValue(response.body(), ProcessingStatusDto.class);
            } catch (Exception e) {
                throw new RuntimeException("Failed to get processing status: " + e.getMessage(), e);
            }
        }, ioExecutor);
    }

    public CompletableFuture<GraphDataDto> getGraphDataAsync(String caseId) {
        return CompletableFuture.supplyAsync(() -> {
            try {
                HttpRequest request = HttpRequest.newBuilder()
                        .uri(URI.create(baseUrl + "/cases/" + caseId + "/graph"))
                        .GET()
                        .timeout(Duration.ofSeconds(15))
                        .build();

                HttpResponse<String> response = httpClient.send(request, HttpResponse.BodyHandlers.ofString());
                if (response.statusCode() >= 400) {
                    throw new IOException("Backend error (" + response.statusCode() + "): " + response.body());
                }
                return objectMapper.readValue(response.body(), GraphDataDto.class);
            } catch (Exception e) {
                throw new RuntimeException("Failed to fetch graph data: " + e.getMessage(), e);
            }
        }, ioExecutor);
    }

    public CompletableFuture<TimelineEventDto> getTimelineAsync(String caseId) {
        return CompletableFuture.supplyAsync(() -> {
            try {
                HttpRequest request = HttpRequest.newBuilder()
                        .uri(URI.create(baseUrl + "/cases/" + caseId + "/timeline"))
                        .GET()
                        .timeout(Duration.ofSeconds(15))
                        .build();

                HttpResponse<String> response = httpClient.send(request, HttpResponse.BodyHandlers.ofString());
                if (response.statusCode() >= 400) {
                    throw new IOException("Backend error (" + response.statusCode() + "): " + response.body());
                }
                return objectMapper.readValue(response.body(), TimelineEventDto.class);
            } catch (Exception e) {
                throw new RuntimeException("Failed to fetch timeline: " + e.getMessage(), e);
            }
        }, ioExecutor);
    }

    public CompletableFuture<ContradictionDto> getContradictionsAsync(String caseId) {
        return CompletableFuture.supplyAsync(() -> {
            try {
                HttpRequest request = HttpRequest.newBuilder()
                        .uri(URI.create(baseUrl + "/cases/" + caseId + "/contradictions"))
                        .GET()
                        .timeout(Duration.ofSeconds(15))
                        .build();

                HttpResponse<String> response = httpClient.send(request, HttpResponse.BodyHandlers.ofString());
                if (response.statusCode() >= 400) {
                    throw new IOException("Backend error (" + response.statusCode() + "): " + response.body());
                }
                return objectMapper.readValue(response.body(), ContradictionDto.class);
            } catch (Exception e) {
                throw new RuntimeException("Failed to fetch contradictions: " + e.getMessage(), e);
            }
        }, ioExecutor);
    }

    public CompletableFuture<OpinionDto> getOpinionAsync(String caseId) {
        return CompletableFuture.supplyAsync(() -> {
            try {
                HttpRequest request = HttpRequest.newBuilder()
                        .uri(URI.create(baseUrl + "/cases/" + caseId + "/opinion"))
                        .GET()
                        .timeout(Duration.ofSeconds(15))
                        .build();

                HttpResponse<String> response = httpClient.send(request, HttpResponse.BodyHandlers.ofString());
                if (response.statusCode() >= 400) {
                    throw new IOException("Backend error (" + response.statusCode() + "): " + response.body());
                }
                return objectMapper.readValue(response.body(), OpinionDto.class);
            } catch (Exception e) {
                throw new RuntimeException("Failed to fetch opinion: " + e.getMessage(), e);
            }
        }, ioExecutor);
    }

    public CompletableFuture<ChatMessageDto> sendChatMessageAsync(String caseId, String query) {
        return sendChatMessageAsync(caseId, query, null, null, null);
    }

    public CompletableFuture<ChatMessageDto> sendChatMessageAsync(String caseId, String query, String model) {
        return sendChatMessageAsync(caseId, query, model, null, null);
    }

    public CompletableFuture<ChatMessageDto> sendChatMessageAsync(String caseId, String query, String model, String provider) {
        return sendChatMessageAsync(caseId, query, model, provider, null);
    }

    public CompletableFuture<ChatMessageDto> sendChatMessageAsync(String caseId, String query, String model, String provider, String sessionId) {
        return CompletableFuture.supplyAsync(() -> {
            try {
                Map<String, Object> map = new HashMap<>();
                map.put("query", query);
                if (model != null && !model.isBlank()) {
                    map.put("model", model);
                }
                if (provider != null && !provider.isBlank()) {
                    map.put("provider", provider);
                }
                if (sessionId != null && !sessionId.isBlank()) {
                    map.put("sessionId", sessionId);
                }
                String json = objectMapper.writeValueAsString(map);
                HttpRequest request = HttpRequest.newBuilder()
                        .uri(URI.create(baseUrl + "/cases/" + caseId + "/chat"))
                        .header("Content-Type", "application/json")
                        .POST(HttpRequest.BodyPublishers.ofString(json, StandardCharsets.UTF_8))
                        // A graph answer can require up to five bounded LLM/tool turns.
                        .timeout(Duration.ofSeconds(360))
                        .build();

                HttpResponse<String> response = httpClient.send(request, HttpResponse.BodyHandlers.ofString());
                if (response.statusCode() >= 400) {
                    throw new IOException("Backend error (" + response.statusCode() + "): " + response.body());
                }
                return objectMapper.readValue(response.body(), ChatMessageDto.class);
            } catch (Exception e) {
                throw new RuntimeException("Failed to query Sherlock chat: " + e.getMessage(), e);
            }
        }, ioExecutor);
    }

    public CompletableFuture<List<ChatSessionDto>> getChatSessionsAsync(String caseId) {
        return CompletableFuture.supplyAsync(() -> {
            try {
                HttpRequest request = HttpRequest.newBuilder()
                        .uri(URI.create(baseUrl + "/cases/" + caseId + "/chat/sessions"))
                        .GET()
                        .timeout(Duration.ofSeconds(15))
                        .build();
                HttpResponse<String> response = httpClient.send(request, HttpResponse.BodyHandlers.ofString());
                if (response.statusCode() >= 400) {
                    throw new IOException("Backend error (" + response.statusCode() + "): " + response.body());
                }
                return objectMapper.readValue(response.body(), new TypeReference<List<ChatSessionDto>>() {});
            } catch (Exception e) {
                throw new RuntimeException("Failed to load chat sessions: " + e.getMessage(), e);
            }
        }, ioExecutor);
    }

    public CompletableFuture<ChatSessionDto> getChatSessionAsync(String caseId, String sessionId) {
        return CompletableFuture.supplyAsync(() -> {
            try {
                HttpRequest request = HttpRequest.newBuilder()
                        .uri(URI.create(baseUrl + "/cases/" + caseId + "/chat/sessions/" + sessionId))
                        .GET()
                        .timeout(Duration.ofSeconds(15))
                        .build();
                HttpResponse<String> response = httpClient.send(request, HttpResponse.BodyHandlers.ofString());
                if (response.statusCode() >= 400) {
                    throw new IOException("Backend error (" + response.statusCode() + "): " + response.body());
                }
                return objectMapper.readValue(response.body(), ChatSessionDto.class);
            } catch (Exception e) {
                throw new RuntimeException("Failed to load chat session: " + e.getMessage(), e);
            }
        }, ioExecutor);
    }

    public CompletableFuture<ChatSessionDto> createChatSessionAsync(String caseId, String title) {
        return CompletableFuture.supplyAsync(() -> {
            try {
                Map<String, String> map = new HashMap<>();
                if (title != null && !title.isBlank()) {
                    map.put("title", title);
                }
                String json = objectMapper.writeValueAsString(map);
                HttpRequest request = HttpRequest.newBuilder()
                        .uri(URI.create(baseUrl + "/cases/" + caseId + "/chat/sessions"))
                        .header("Content-Type", "application/json")
                        .POST(HttpRequest.BodyPublishers.ofString(json, StandardCharsets.UTF_8))
                        .timeout(Duration.ofSeconds(15))
                        .build();
                HttpResponse<String> response = httpClient.send(request, HttpResponse.BodyHandlers.ofString());
                if (response.statusCode() >= 400) {
                    throw new IOException("Backend error (" + response.statusCode() + "): " + response.body());
                }
                return objectMapper.readValue(response.body(), ChatSessionDto.class);
            } catch (Exception e) {
                throw new RuntimeException("Failed to create chat session: " + e.getMessage(), e);
            }
        }, ioExecutor);
    }

    public CompletableFuture<Boolean> deleteChatSessionAsync(String caseId, String sessionId) {
        return CompletableFuture.supplyAsync(() -> {
            try {
                HttpRequest request = HttpRequest.newBuilder()
                        .uri(URI.create(baseUrl + "/cases/" + caseId + "/chat/sessions/" + sessionId))
                        .DELETE()
                        .timeout(Duration.ofSeconds(15))
                        .build();
                HttpResponse<String> response = httpClient.send(request, HttpResponse.BodyHandlers.ofString());
                if (response.statusCode() >= 400) {
                    throw new IOException("Backend error (" + response.statusCode() + "): " + response.body());
                }
                return true;
            } catch (Exception e) {
                throw new RuntimeException("Failed to delete chat session: " + e.getMessage(), e);
            }
        }, ioExecutor);
    }

    public CompletableFuture<List<ChatMessageDto>> getChatHistoryAsync(String caseId) {
        return CompletableFuture.supplyAsync(() -> {
            try {
                HttpRequest request = HttpRequest.newBuilder()
                        .uri(URI.create(baseUrl + "/cases/" + caseId + "/chat"))
                        .GET()
                        .timeout(Duration.ofSeconds(15))
                        .build();
                HttpResponse<String> response = httpClient.send(request, HttpResponse.BodyHandlers.ofString());
                if (response.statusCode() >= 400) {
                    throw new IOException("Backend error (" + response.statusCode() + "): " + response.body());
                }
                return objectMapper.readValue(response.body(), new TypeReference<List<ChatMessageDto>>() {});
            } catch (Exception e) {
                throw new RuntimeException("Failed to load persistent chat history: " + e.getMessage(), e);
            }
        }, ioExecutor);
    }

    public CompletableFuture<List<CaseDto>> listCasesAsync() {
        return CompletableFuture.supplyAsync(() -> {
            try {
                HttpRequest request = HttpRequest.newBuilder()
                        .uri(URI.create(baseUrl + "/cases"))
                        .GET()
                        .timeout(Duration.ofSeconds(10))
                        .build();

                HttpResponse<String> response = httpClient.send(request, HttpResponse.BodyHandlers.ofString());
                if (response.statusCode() >= 400) {
                    throw new IOException("Backend error (" + response.statusCode() + "): " + response.body());
                }
                return objectMapper.readValue(response.body(), new TypeReference<List<CaseDto>>() {});
            } catch (Exception e) {
                throw new RuntimeException("Failed to list cases: " + e.getMessage(), e);
            }
        }, ioExecutor);
    }

    public CompletableFuture<List<String>> getOllamaModelsAsync() {
        return CompletableFuture.supplyAsync(() -> {
            try {
                HttpRequest request = HttpRequest.newBuilder()
                        .uri(URI.create("http://127.0.0.1:11434/api/tags"))
                        .GET()
                        .timeout(Duration.ofSeconds(4))
                        .build();

                HttpResponse<String> response = httpClient.send(request, HttpResponse.BodyHandlers.ofString());
                if (response.statusCode() == 200) {
                    com.fasterxml.jackson.databind.JsonNode root = objectMapper.readTree(response.body());
                    List<String> models = new ArrayList<>();
                    if (root.has("models") && root.get("models").isArray()) {
                        for (com.fasterxml.jackson.databind.JsonNode m : root.get("models")) {
                            if (m.has("name")) {
                                models.add(m.get("name").asText());
                            }
                        }
                    }
                    return models;
                }
                return List.of();
            } catch (Exception e) {
                return List.of();
            }
        }, ioExecutor);
    }

    public CompletableFuture<Boolean> syncNeo4jAsync(String caseId) {
        return CompletableFuture.supplyAsync(() -> {
            try {
                HttpRequest request = HttpRequest.newBuilder()
                        .uri(URI.create(baseUrl + "/cases/" + caseId + "/neo4j/sync"))
                        .POST(HttpRequest.BodyPublishers.noBody())
                        .timeout(Duration.ofSeconds(10))
                        .build();

                HttpResponse<String> response = httpClient.send(request, HttpResponse.BodyHandlers.ofString());
                return response.statusCode() == 200;
            } catch (Exception e) {
                return false;
            }
        }, ioExecutor);
    }

    public CompletableFuture<CaseDto> updateLlmConfigAsync(String caseId, LlmConfigDto config) {
        return CompletableFuture.supplyAsync(() -> {
            try {
                String requestBody = objectMapper.writeValueAsString(config);
                HttpRequest request = HttpRequest.newBuilder()
                        .uri(URI.create(baseUrl + "/cases/" + caseId + "/llm"))
                        .header("Content-Type", "application/json")
                        .PUT(HttpRequest.BodyPublishers.ofString(requestBody))
                        .build();
                HttpResponse<String> response = httpClient.send(request, HttpResponse.BodyHandlers.ofString());
                return objectMapper.readValue(response.body(), CaseDto.class);
            } catch (Exception e) {
                throw new RuntimeException("Failed to update LLM config: " + e.getMessage(), e);
            }
        });
    }
}
