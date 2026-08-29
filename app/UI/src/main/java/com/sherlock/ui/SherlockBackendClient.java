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

    public CompletableFuture<ChatMessageDto> sendChatMessageAsync(String caseId, String query) {
        return sendChatMessageAsync(caseId, query, null);
    }

    public CompletableFuture<ChatMessageDto> sendChatMessageAsync(String caseId, String query, String model) {
        return sendChatMessageAsync(caseId, query, model, null);
    }

    public CompletableFuture<ChatMessageDto> sendChatMessageAsync(String caseId, String query, String model, String provider) {
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
                String json = objectMapper.writeValueAsString(map);
                HttpRequest request = HttpRequest.newBuilder()
                        .uri(URI.create(baseUrl + "/cases/" + caseId + "/chat"))
                        .header("Content-Type", "application/json")
                        .POST(HttpRequest.BodyPublishers.ofString(json, StandardCharsets.UTF_8))
                        .timeout(Duration.ofSeconds(60))
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
                        .uri(URI.create(baseUrl + "/llm/ollama/models"))
                        .GET()
                        .timeout(Duration.ofSeconds(4))
                        .build();

                HttpResponse<String> response = httpClient.send(request, HttpResponse.BodyHandlers.ofString());
                if (response.statusCode() == 200) {
                    return objectMapper.readValue(response.body(), new TypeReference<List<String>>() {});
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
