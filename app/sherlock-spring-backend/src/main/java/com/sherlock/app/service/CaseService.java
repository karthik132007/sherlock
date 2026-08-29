package com.sherlock.app.service;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.SerializationFeature;
import com.fasterxml.jackson.datatype.jsr310.JavaTimeModule;
import com.sherlock.app.config.AppProperties;
import com.sherlock.app.model.*;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;
import org.springframework.web.multipart.MultipartFile;

import java.io.BufferedReader;
import java.io.File;
import java.io.IOException;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.nio.file.*;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.*;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.stream.Collectors;

@Service
public class CaseService {

    private static final Logger log = LoggerFactory.getLogger(CaseService.class);

    private static final Set<String> ALLOWED_EXTENSIONS = Set.of(
            ".txt", ".pdf", ".png", ".jpg", ".jpeg", ".doc", ".docx", ".json", ".csv");

    private final AppProperties appProperties;
    private final Neo4jGraphService neo4jGraphService;
    private final OllamaService ollamaService;
    private final ObjectMapper objectMapper;
    private final Map<String, ProcessingStatus> statusTracker = new ConcurrentHashMap<>();
    /**
     * Shared executor for the Python pipeline background tasks. A single shared,
     * daemon-threaded pool avoids the per-request thread leak that occurred when a
     * new single-thread executor was created (and never shut down) for every run.
     */
    private final ExecutorService pipelineExecutor = Executors.newCachedThreadPool(r -> {
        Thread t = new Thread(r, "sherlock-pipeline");
        t.setDaemon(true);
        return t;
    });

    public CaseService(AppProperties appProperties, Neo4jGraphService neo4jGraphService, OllamaService ollamaService) {
        this.appProperties = appProperties;
        this.neo4jGraphService = neo4jGraphService;
        this.ollamaService = ollamaService;
        this.objectMapper = new ObjectMapper();
        this.objectMapper.registerModule(new JavaTimeModule());
        this.objectMapper.disable(SerializationFeature.WRITE_DATES_AS_TIMESTAMPS);
    }

    public CaseResponse createCase(CaseRequest request) {
        String caseName = request.getCaseName() != null ? request.getCaseName().trim() : "Case";
        String caseId = request.getCaseId() != null && !request.getCaseId().isBlank()
                ? normalizeCaseId(request.getCaseId())
                : generateCaseId(normalizeCaseName(caseName));

        Path rootDir = Paths.get(appProperties.getBaseDirectory());
        Path caseDirectory = rootDir.resolve(caseId);
        Path dataDirectory = caseDirectory.resolve("data");
        Path processedDirectory = caseDirectory.resolve("processed");

        try {
            Files.createDirectories(dataDirectory);
            Files.createDirectories(processedDirectory);

            if (request.getLlmConfig() != null) {
                writeLlmConfig(caseDirectory, request.getLlmConfig());
            }

            CaseResponse response = new CaseResponse(
                    caseId,
                    caseName,
                    caseDirectory.toString(),
                    dataDirectory.toString(),
                    LocalDateTime.now(),
                    "READY");
            response.setLlmConfig(request.getLlmConfig());
            writeMetadata(caseDirectory, response);
            return response;
        } catch (IOException e) {
            log.error("Unable to create case directory for: {}", caseName, e);
            throw new IllegalStateException("Unable to create case directory for: " + caseName, e);
        }
    }

    public String getNextCaseId() {
        Path rootDir = Paths.get(appProperties.getBaseDirectory());
        if (!Files.exists(rootDir)) {
            return "CASE-001";
        }

        int maxNum = 0;
        try (var paths = Files.list(rootDir)) {
            List<Path> dirs = paths.filter(Files::isDirectory).collect(Collectors.toList());
            for (Path d : dirs) {
                String name = d.getFileName().toString().toUpperCase(Locale.ROOT);
                if (name.startsWith("CASE-") || name.startsWith("CASE_")) {
                    try {
                        String numStr = name.substring(5).replaceAll("[^0-9]", "");
                        if (!numStr.isEmpty()) {
                            int val = Integer.parseInt(numStr);
                            if (val > maxNum)
                                maxNum = val;
                        }
                    } catch (Exception ignored) {
                    }
                }
            }
        } catch (IOException ignored) {
        }

        return String.format("CASE-%03d", maxNum + 1);
    }

    public List<CaseResponse> listCases() {
        Path rootDir = Paths.get(appProperties.getBaseDirectory());
        if (!Files.exists(rootDir)) {
            return List.of();
        }

        try (var paths = Files.list(rootDir)) {
            return paths.filter(Files::isDirectory)
                    .map(this::readCaseMetadata)
                    .filter(Objects::nonNull)
                    .collect(Collectors.toList());
        } catch (IOException e) {
            log.error("Unable to list Sherlock cases", e);
            throw new IllegalStateException("Unable to list Sherlock cases", e);
        }
    }

    public CaseResponse updateLlmConfig(String caseId, LlmConfigRequest llmConfig) {
        Path caseDirectory = getCaseDirectory(caseId);
        if (!Files.exists(caseDirectory)) {
            throw new IllegalArgumentException("Case not found: " + caseId);
        }
        try {
            if (llmConfig != null) {
                writeLlmConfig(caseDirectory, llmConfig);
            }
            CaseResponse response = readCaseMetadata(caseDirectory);
            if (response != null && llmConfig != null) {
                response.setLlmConfig(llmConfig);
                writeMetadata(caseDirectory, response);
            }
            return response;
        } catch (IOException e) {
            throw new IllegalStateException("Failed to update LLM config for case: " + caseId, e);
        }
    }

    public CaseResponse getCase(String caseId) {
        Path caseDirectory = getCaseDirectory(caseId);
        if (!Files.exists(caseDirectory)) {
            throw new IllegalArgumentException("Case not found: " + caseId);
        }
        return readCaseMetadata(caseDirectory);
    }

    public CaseResponse saveUploadedFiles(String caseId, List<MultipartFile> files) throws IOException {
        Path caseDirectory = getCaseDirectory(caseId);
        if (!Files.exists(caseDirectory)) {
            throw new IllegalArgumentException("Case not found: " + caseId);
        }

        Path dataDirectory = caseDirectory.resolve("data");
        Files.createDirectories(dataDirectory);

        List<String> savedFiles = new ArrayList<>();

        for (MultipartFile file : files) {
            if (file == null || file.isEmpty()) {
                continue;
            }

            String originalName = file.getOriginalFilename();
            if (originalName == null || originalName.isBlank()) {
                continue;
            }

            String extension = "";
            int dotIdx = originalName.lastIndexOf('.');
            if (dotIdx >= 0) {
                extension = originalName.substring(dotIdx).toLowerCase(Locale.ROOT);
            }

            if (!extension.isEmpty() && !ALLOWED_EXTENSIONS.contains(extension)) {
                log.warn("Unsupported file type skipped: {}", originalName);
                continue;
            }

            String sanitized = sanitizeFileName(originalName);
            Path target = dataDirectory.resolve(sanitized);
            Files.copy(file.getInputStream(), target, StandardCopyOption.REPLACE_EXISTING);
            savedFiles.add(sanitized);
        }

        // Generate / refresh warehouse.txt
        buildWarehouseFile(caseDirectory);

        CaseResponse response = readCaseMetadata(caseDirectory);
        if (response == null) {
            response = new CaseResponse(caseId, caseId, caseDirectory.toString(), dataDirectory.toString(),
                    LocalDateTime.now(), "FILES_UPLOADED");
        }
        response.setStatus("FILES_UPLOADED");
        updateFileListInResponse(response, dataDirectory);
        writeMetadata(caseDirectory, response);
        return response;
    }

    public String buildWarehouseFile(Path caseDirectory) throws IOException {
        Path dataDirectory = caseDirectory.resolve("data");
        Path warehouseFile = caseDirectory.resolve("warehouse.txt");

        if (Files.exists(dataDirectory) && Files.isDirectory(dataDirectory)) {
            try (var stream = Files.list(dataDirectory);
                    java.io.BufferedWriter writer = Files.newBufferedWriter(warehouseFile, StandardCharsets.UTF_8,
                            StandardOpenOption.CREATE, StandardOpenOption.TRUNCATE_EXISTING)) {

                List<Path> files = stream
                        .filter(Files::isRegularFile)
                        .sorted(Comparator.comparing(p -> {
                            Path name = p.getFileName();
                            return name != null ? name.toString() : "";
                        }))
                        .collect(Collectors.toList());

                for (Path file : files) {
                    String fileName = file.getFileName().toString();

                    writer.write("========================================\n");
                    writer.write("SOURCE_FILE: " + fileName + "\n");
                    writer.write("SOURCE_TYPE: TEXT\n");
                    writer.write("========================================\n\n");

                    try (BufferedReader reader = Files.newBufferedReader(file, StandardCharsets.UTF_8)) {
                        String line;
                        while ((line = reader.readLine()) != null) {
                            writer.write(line);
                            writer.write("\n");
                        }
                    } catch (Exception e) {
                        try (BufferedReader reader2 = Files.newBufferedReader(file, StandardCharsets.ISO_8859_1)) {
                            String line;
                            while ((line = reader2.readLine()) != null) {
                                writer.write(line);
                                writer.write("\n");
                            }
                        } catch (Exception ignored) {
                            writer.write("[Binary or unreadable content for " + fileName + "]\n");
                        }
                    }

                    writer.write("\n========================================\n");
                    writer.write("END_SOURCE: " + fileName + "\n");
                    writer.write("========================================\n\n\n");
                }

                log.info("Built warehouse.txt for case: {}", caseDirectory.getFileName());
                return warehouseFile.toString();
            }
        }

        return warehouseFile.toString();
    }

    public ProcessingStatus startTimelineProcessing(String caseId) {
        Path caseDirectory = getCaseDirectory(caseId);
        if (!Files.exists(caseDirectory)) {
            throw new IllegalArgumentException("Case not found: " + caseId);
        }

        // Ensure warehouse.txt exists
        Path warehousePath = caseDirectory.resolve("warehouse.txt");
        if (!Files.exists(warehousePath)) {
            try {
                buildWarehouseFile(caseDirectory);
            } catch (IOException e) {
                log.error("Failed to build warehouse.txt before timeline processing: {}", e.getMessage());
            }
        }

        String pythonScript = resolvePythonScriptPath();
        String pythonCommand = resolvePythonCommand(pythonScript);
        List<String> commandTokens = new ArrayList<>();
        commandTokens.add(pythonCommand);
        commandTokens.add("-u");
        commandTokens.add(pythonScript);
        commandTokens.add(appProperties.getProcessArgument());
        commandTokens.add(caseDirectory.toAbsolutePath().toString());
        commandTokens.add("--timeline-only");

        ProcessingStatus status = new ProcessingStatus();
        status.setCaseId(caseId);
        status.setStatus("PROCESSING_TIMELINE");
        status.setScriptPath(pythonScript);
        status.setCommand(String.join(" ", commandTokens));
        status.setMessage("Timeline extraction started...");
        status.setCompleted(false);
        status.setSuccess(false);

        statusTracker.put(caseId + "_timeline", status);

        pipelineExecutor.submit(() -> {
            try {
                log.info("Executing Python timeline command: {}", String.join(" ", commandTokens));
                ProcessBuilder processBuilder = new ProcessBuilder(commandTokens);
                processBuilder.directory(new File(pythonScript).getParentFile());
                processBuilder.environment().put("PYTHONUNBUFFERED", "1");
                processBuilder.environment().put("PYTHONIOENCODING", "utf-8");
                processBuilder.environment().put("PYTHONUTF8", "1");
                processBuilder.redirectErrorStream(true);

                Process process = processBuilder.start();

                try (BufferedReader reader = new BufferedReader(
                        new InputStreamReader(process.getInputStream(), StandardCharsets.UTF_8))) {
                    String line;
                    while ((line = reader.readLine()) != null) {
                        final String logLine = line;
                        status.getLogs().add(logLine);
                        status.setMessage(logLine);
                        log.info("[Python Timeline - {}] {}", caseId, logLine);
                    }
                }

                int exitCode = process.waitFor();
                status.setExitCode(exitCode);
                status.setCompleted(true);

                if (exitCode == 0) {
                    status.setStatus("COMPLETED");
                    status.setSuccess(true);
                    status.setMessage("Timeline extracted successfully.");
                } else {
                    status.setStatus("FAILED");
                    status.setSuccess(false);
                    status.setMessage("Python timeline pipeline finished with error exit code: " + exitCode);
                }

            } catch (Exception e) {
                log.error("Error executing Python timeline pipeline for case: {}", caseId, e);
                status.setStatus("FAILED");
                status.setCompleted(true);
                status.setSuccess(false);
                status.setMessage("Processing failed: " + e.getMessage());
                status.getLogs().add("ERROR: " + e.getMessage());
            }
        });

        return status;
    }

    public ProcessingStatus getTimelineProcessingStatus(String caseId) {
        ProcessingStatus status = statusTracker.get(caseId + "_timeline");
        if (status == null) {
            Path caseDirectory = getCaseDirectory(caseId);
            Path timelineFile = caseDirectory.resolve("processed").resolve("timeline.json");
            status = new ProcessingStatus();
            status.setCaseId(caseId);
            if (Files.exists(timelineFile)) {
                status.setStatus("COMPLETED");
                status.setCompleted(true);
                status.setSuccess(true);
                status.setMessage("Timeline ready.");
            } else {
                status.setStatus("READY");
                status.setCompleted(false);
                status.setMessage("Ready for processing.");
            }
        }
        return status;
    }

    public ProcessingStatus startProcessing(String caseId) {
        Path caseDirectory = getCaseDirectory(caseId);
        if (!Files.exists(caseDirectory)) {
            throw new IllegalArgumentException("Case not found: " + caseId);
        }

        // Ensure warehouse.txt exists
        Path warehousePath = caseDirectory.resolve("warehouse.txt");
        if (!Files.exists(warehousePath)) {
            try {
                buildWarehouseFile(caseDirectory);
            } catch (IOException e) {
                log.error("Failed to build warehouse.txt before processing: {}", e.getMessage());
            }
        }

        String pythonScript = resolvePythonScriptPath();
        String pythonCommand = resolvePythonCommand(pythonScript);
        List<String> commandTokens = new ArrayList<>();
        commandTokens.add(pythonCommand);
        commandTokens.add("-u");
        commandTokens.add(pythonScript);
        commandTokens.add(appProperties.getProcessArgument());
        commandTokens.add(caseDirectory.toAbsolutePath().toString());
        commandTokens.add("--extract");
        commandTokens.add("--timeline");

        ProcessingStatus status = new ProcessingStatus();
        status.setCaseId(caseId);
        status.setStatus("PROCESSING");
        status.setScriptPath(pythonScript);
        status.setCommand(String.join(" ", commandTokens));
        status.setMessage("Extraction and graph construction started...");
        status.setCompleted(false);
        status.setSuccess(false);

        statusTracker.put(caseId, status);

        pipelineExecutor.submit(() -> {
            try {
                log.info("Executing Python command: {}", String.join(" ", commandTokens));
                ProcessBuilder processBuilder = new ProcessBuilder(commandTokens);
                processBuilder.directory(new File(pythonScript).getParentFile());
                processBuilder.environment().put("PYTHONUNBUFFERED", "1");
                processBuilder.environment().put("PYTHONIOENCODING", "utf-8");
                processBuilder.environment().put("PYTHONUTF8", "1");
                processBuilder.redirectErrorStream(true);

                Process process = processBuilder.start();

                try (BufferedReader reader = new BufferedReader(
                        new InputStreamReader(process.getInputStream(), StandardCharsets.UTF_8))) {
                    String line;
                    while ((line = reader.readLine()) != null) {
                        final String logLine = line;
                        status.getLogs().add(logLine);
                        status.setMessage(logLine);
                        log.info("[Python Pipeline - {}] {}", caseId, logLine);
                    }
                }

                int exitCode = process.waitFor();
                status.setExitCode(exitCode);
                status.setCompleted(true);

                if (exitCode == 0) {
                    status.setStatus("COMPLETED");
                    status.setSuccess(true);
                    status.setMessage("Investigation Knowledge Graph generated successfully.");

                    // Auto-sync into Neo4j database
                    try {
                        GraphDataResponse graphData = getGraphData(caseId);
                        if (graphData != null && neo4jGraphService != null && neo4jGraphService.isConnected()) {
                            boolean synced = neo4jGraphService.syncGraphToNeo4j(caseId, graphData);
                            if (synced) {
                                log.info("Auto-synced case {} to Neo4j database", caseId);
                                status.getLogs().add("[Sherlock] Graph data synchronized to Neo4j database.");
                            }
                        }
                    } catch (Exception syncEx) {
                        log.warn("Failed auto-syncing to Neo4j for case {}: {}", caseId, syncEx.getMessage());
                    }
                } else {
                    status.setStatus("FAILED");
                    status.setSuccess(false);
                    status.setMessage("Python pipeline finished with error exit code: " + exitCode);
                }

                // Update case metadata status
                CaseResponse meta = readCaseMetadata(caseDirectory);
                if (meta != null) {
                    meta.setStatus(status.getStatus());
                    writeMetadata(caseDirectory, meta);
                }

            } catch (Exception e) {
                log.error("Error executing Python processing pipeline for case: {}", caseId, e);
                status.setStatus("FAILED");
                status.setCompleted(true);
                status.setSuccess(false);
                status.setMessage("Processing failed: " + e.getMessage());
                status.getLogs().add("ERROR: " + e.getMessage());
            }
        });

        return status;
    }

    public ProcessingStatus getProcessingStatus(String caseId) {
        ProcessingStatus status = statusTracker.get(caseId);
        if (status == null) {
            Path caseDirectory = getCaseDirectory(caseId);
            Path graphFile = caseDirectory.resolve("processed").resolve("graph_data.json");
            status = new ProcessingStatus();
            status.setCaseId(caseId);
            if (Files.exists(graphFile)) {
                status.setStatus("COMPLETED");
                status.setCompleted(true);
                status.setSuccess(true);
                status.setMessage("Knowledge Graph ready.");
            } else {
                status.setStatus("READY");
                status.setCompleted(false);
                status.setMessage("Ready for processing.");
            }
        }
        return status;
    }

    public GraphDataResponse getGraphData(String caseId) {
        // 1. Primary: Fetch directly from Neo4j database
        if (neo4jGraphService != null && neo4jGraphService.isConnected()) {
            try {
                GraphDataResponse neo4jData = neo4jGraphService.fetchGraphFromNeo4j(caseId);
                if (neo4jData != null && neo4jData.getNodes() != null && !neo4jData.getNodes().isEmpty()) {
                    log.info("Directly loaded {} nodes and {} edges for case {} from Neo4j database",
                            neo4jData.getNodes().size(), neo4jData.getEdges().size(), caseId);
                    return neo4jData;
                }

                // If Neo4j has no records yet for this case, sync case files into Neo4j and return from Neo4j
                log.info("No records in Neo4j for case {}, performing initial sync from case files into Neo4j...", caseId);
                GraphDataResponse fileData = readGraphDataFromFiles(caseId);
                if (fileData != null && fileData.getNodes() != null && !fileData.getNodes().isEmpty()) {
                    neo4jGraphService.syncGraphToNeo4j(caseId, fileData);
                    GraphDataResponse syncedFromDb = neo4jGraphService.fetchGraphFromNeo4j(caseId);
                    if (syncedFromDb != null && syncedFromDb.getNodes() != null && !syncedFromDb.getNodes().isEmpty()) {
                        log.info("Fetched {} nodes for case {} from Neo4j database after sync", syncedFromDb.getNodes().size(), caseId);
                        return syncedFromDb;
                    }
                    return fileData;
                }
            } catch (Exception e) {
                log.warn("Neo4j database query error for case {}, falling back: {}", caseId, e.getMessage());
            }
        } else {
            log.warn("Neo4j database is offline or not reachable for case {}", caseId);
        }

        // 2. Fallback to reading file-based JSON if Neo4j is offline
        return readGraphDataFromFiles(caseId);
    }

    public GraphDataResponse readGraphDataFromFiles(String caseId) {
        Path caseDirectory = getCaseDirectory(caseId);
        Path processedDir = caseDirectory.resolve("processed");

        List<GraphDataResponse.Node> nodes = new ArrayList<>();
        List<GraphDataResponse.Edge> edges = new ArrayList<>();

        // 1. Try reading processed/entities.json
        Path entitiesFile = processedDir.resolve("entities.json");
        Map<String, GraphDataResponse.Node> nodeMap = new HashMap<>();

        if (Files.exists(entitiesFile)) {
            try {
                JsonNode root = objectMapper.readTree(entitiesFile.toFile());
                JsonNode itemsNode = root.isArray() ? root : (root.has("entities") ? root.get("entities") : null);
                if (itemsNode != null && itemsNode.isArray()) {
                    for (JsonNode item : itemsNode) {
                        GraphDataResponse.Node node = objectMapper.treeToValue(item, GraphDataResponse.Node.class);
                        if (node != null && node.getId() != null) {
                            nodeMap.put(node.getId(), node);
                            if (node.getName() != null) {
                                nodeMap.put(node.getName().toLowerCase(Locale.ROOT), node);
                            }
                            nodes.add(node);
                        }
                    }
                }
            } catch (IOException e) {
                log.warn("Could not read entities.json: {}", e.getMessage());
            }
        }

        // 2. Try reading processed/graph_data.json or processed/relations.json
        Path graphDataFile = processedDir.resolve("graph_data.json");
        Path relationsFile = processedDir.resolve("relations.json");
        if (!Files.exists(relationsFile)) {
            relationsFile = processedDir.resolve("relationships.json");
        }

        if (Files.exists(graphDataFile)) {
            try {
                JsonNode root = objectMapper.readTree(graphDataFile.toFile());
                JsonNode itemsNode = root.isArray() ? root
                        : (root.has("graph") ? root.get("graph")
                                : (root.has("mappings") ? root.get("mappings") : null));
                if (itemsNode != null && itemsNode.isArray()) {
                    for (JsonNode item : itemsNode) {
                        String relId = item.has("relation_id") ? item.get("relation_id").asText() : "";
                        String relation = item.has("relation") ? item.get("relation").asText() : "RELATED_TO";
                        Double conf = item.has("confidence") ? item.get("confidence").asDouble() : 1.0;
                        String evidence = item.has("evidence_text") ? item.get("evidence_text").asText() : "";
                        String sourceFile = item.has("source_file") ? item.get("source_file").asText() : "";
                        String chunkId = item.has("chunk_id") ? item.get("chunk_id").asText() : "";

                        String sourceName = "";
                        JsonNode srcNode = item.get("source");
                        if (srcNode != null) {
                            sourceName = srcNode.isObject() && srcNode.has("name") ? srcNode.get("name").asText()
                                    : srcNode.asText();
                            ensureNodeExists(srcNode, nodeMap, nodes);
                        }

                        String targetName = "";
                        JsonNode tgtNode = item.get("target");
                        if (tgtNode != null) {
                            targetName = tgtNode.isObject() && tgtNode.has("name") ? tgtNode.get("name").asText()
                                    : tgtNode.asText();
                            ensureNodeExists(tgtNode, nodeMap, nodes);
                        }

                        GraphDataResponse.Edge edge = new GraphDataResponse.Edge(relId, sourceName, relation,
                                targetName);
                        edge.setConfidence(conf);
                        edge.setEvidenceText(evidence);
                        edge.setSourceFile(sourceFile);
                        edge.setChunkId(chunkId);
                        edges.add(edge);
                    }
                }
            } catch (IOException e) {
                log.warn("Could not read graph_data.json: {}", e.getMessage());
            }
        } else if (Files.exists(relationsFile)) {
            try {
                JsonNode root = objectMapper.readTree(relationsFile.toFile());
                JsonNode itemsNode = root.isArray() ? root
                        : (root.has("relations") ? root.get("relations")
                                : (root.has("relationships") ? root.get("relationships") : null));
                if (itemsNode != null && itemsNode.isArray()) {
                    for (JsonNode item : itemsNode) {
                        String source = item.has("source") ? item.get("source").asText() : "";
                        String relation = item.has("relation") ? item.get("relation").asText() : "RELATED_TO";
                        String target = item.has("target") ? item.get("target").asText() : "";
                        Double conf = item.has("confidence") ? item.get("confidence").asDouble() : 1.0;
                        String evidence = item.has("evidence_text") ? item.get("evidence_text").asText() : "";
                        String sourceFile = item.has("source_file") ? item.get("source_file").asText() : "";
                        String chunkId = item.has("chunk_id") ? item.get("chunk_id").asText() : "";
                        String relId = item.has("relation_id") ? item.get("relation_id").asText()
                                : "rel_" + (edges.size() + 1);

                        GraphDataResponse.Edge edge = new GraphDataResponse.Edge(relId, source, relation, target);
                        edge.setConfidence(conf);
                        edge.setEvidenceText(evidence);
                        edge.setSourceFile(sourceFile);
                        edge.setChunkId(chunkId);
                        edges.add(edge);

                        if (!nodeMap.containsKey(source.toLowerCase(Locale.ROOT))) {
                            GraphDataResponse.Node n = new GraphDataResponse.Node(slugify(source), source, "ENTITY");
                            nodes.add(n);
                            nodeMap.put(source.toLowerCase(Locale.ROOT), n);
                        }
                        if (!nodeMap.containsKey(target.toLowerCase(Locale.ROOT))) {
                            GraphDataResponse.Node n = new GraphDataResponse.Node(slugify(target), target, "ENTITY");
                            nodes.add(n);
                            nodeMap.put(target.toLowerCase(Locale.ROOT), n);
                        }
                    }
                }
            } catch (IOException e) {
                log.warn("Could not read relations.json: {}", e.getMessage());
            }
        }

        return new GraphDataResponse(caseId, nodes, edges);
    }

    public TimelineEventResponse getTimeline(String caseId) {
        Path caseDirectory = getCaseDirectory(caseId);
        Path timelineFile = caseDirectory.resolve("processed").resolve("timeline.json");

        if (Files.exists(timelineFile)) {
            try {
                return objectMapper.readValue(timelineFile.toFile(), TimelineEventResponse.class);
            } catch (IOException e) {
                log.warn("Failed to read timeline.json for {}: {}", caseId, e.getMessage());
            }
        }

        TimelineEventResponse resp = new TimelineEventResponse();
        resp.setProject(caseId);
        resp.setSorted(true);
        resp.setTotalEvents(0);
        return resp;
    }

    @SuppressWarnings("null")
    public ChatResponse chatWithSherlock(String caseId, ChatRequest request) {
        String query = request.getQuery() != null ? request.getQuery().trim() : "";
        String qLower = query.toLowerCase(Locale.ROOT);

        GraphDataResponse graph = getGraphData(caseId);
        TimelineEventResponse timeline = getTimeline(caseId);

        ChatResponse response = new ChatResponse();
        List<String> matchedEntities = new ArrayList<>();
        List<String> matchedSources = new ArrayList<>();
        List<String> matchedSnippets = new ArrayList<>();

        StringBuilder answer = new StringBuilder();

        // 1. Search entities (null-safe: names/aliases may be missing in JSON data)
        List<GraphDataResponse.Node> relevantNodes = graph.getNodes().stream()
                .filter(n -> {
                    String name = n.getName() != null ? n.getName().toLowerCase(Locale.ROOT) : "";
                    boolean nameMatches = !name.isEmpty()
                            && (qLower.contains(name) || name.contains(qLower));
                    boolean aliasMatches = n.getAliases().stream()
                            .anyMatch(a -> a != null && !a.isBlank() && qLower.contains(a.toLowerCase(Locale.ROOT)));
                    return nameMatches || aliasMatches;
                })
                .collect(Collectors.toList());

        // 2. Search edges connected to relevant nodes or relation words (null-safe)
        List<GraphDataResponse.Edge> relevantEdges = graph.getEdges().stream()
                .filter(e -> {
                    boolean match = false;
                    String source = e.getSource();
                    String target = e.getTarget();
                    for (var n : relevantNodes) {
                        if ((source != null && source.equalsIgnoreCase(n.getName()))
                                || (target != null && target.equalsIgnoreCase(n.getName()))) {
                            match = true;
                            break;
                        }
                    }
                    String relation = e.getRelation();
                    if (relation != null && qLower.contains(relation.toLowerCase(Locale.ROOT).replace("_", " "))) {
                        match = true;
                    }
                    return match;
                })
                .collect(Collectors.toList());

        // 3. Search timeline events
        List<TimelineEventResponse.TimelineEvent> relevantEvents = timeline.getTimeline().stream()
                .filter(ev -> {
                    String title = ev.getTitle() != null ? ev.getTitle() : "";
                    String description = ev.getDescription() != null ? ev.getDescription() : "";
                    String timestamp = ev.getTimestamp() != null ? ev.getTimestamp() : "";
                    String combined = (title + " " + description + " " + timestamp).toLowerCase(Locale.ROOT);
                    for (var n : relevantNodes) {
                        if (n.getName() != null && combined.contains(n.getName().toLowerCase(Locale.ROOT)))
                            return true;
                    }
                    return relevantNodes.isEmpty() && !qLower.isEmpty() && combined.contains(qLower);
                })
                .collect(Collectors.toList());

        // 0. If Ollama is available or requested, generate and run Cypher query
        String cypherQuery = null;
        List<Map<String, Object>> cypherResults = Collections.emptyList();
        try {
            String model = request.getModel();
            if (model == null || model.isBlank()) {
                CaseResponse caseResp = getCase(caseId);
                if (caseResp != null && caseResp.getLlmConfig() != null && caseResp.getLlmConfig().getModel() != null) {
                    model = caseResp.getLlmConfig().getModel();
                }
            }
            List<String> available = ollamaService != null ? ollamaService.getAvailableModels() : List.of();
            if (model == null || model.isBlank()) {
                model = !available.isEmpty() ? available.get(0) : "gemma4:e2b";
            }

            if (!available.isEmpty()) {
                cypherQuery = ollamaService.generateCypherQuery(caseId, query, model);
                if (cypherQuery != null && !cypherQuery.isBlank() && neo4jGraphService != null
                        && neo4jGraphService.isConnected()) {
                    cypherResults = neo4jGraphService.executeCypher(cypherQuery, Map.of("caseId", caseId));
                }
            }
        } catch (Exception e) {
            log.debug("Cypher generation or execution skipped: {}", e.getMessage());
        }

        if (!relevantNodes.isEmpty() || !cypherResults.isEmpty()) {
            answer.append("### Sherlock Investigation Findings\n\n");

            if (cypherQuery != null && !cypherQuery.isBlank()) {
                answer.append("```cypher\n// Generated CQL for Neo4j\n").append(cypherQuery).append("\n```\n\n");
                if (!cypherResults.isEmpty()) {
                    answer.append("**Neo4j Query Matches (").append(cypherResults.size())
                            .append(" records found):**\n");
                    for (int i = 0; i < Math.min(cypherResults.size(), 5); i++) {
                        answer.append("• `").append(cypherResults.get(i).toString()).append("`\n");
                    }
                    answer.append("\n");
                }
            }

            for (var node : relevantNodes) {
                matchedEntities.add(node.getName());
                answer.append("• **").append(node.getName()).append("** (`").append(node.getType()).append("`)\n");
                if (node.getData() != null && !node.getData().isEmpty()) {
                    answer.append("  - Attributes: ").append(node.getData().toString()).append("\n");
                }
                if (node.getSourceFiles() != null && !node.getSourceFiles().isEmpty()) {
                    matchedSources.addAll(node.getSourceFiles());
                    answer.append("  - Mentioned in: ").append(String.join(", ", node.getSourceFiles())).append("\n");
                }
            }

            if (!relevantEdges.isEmpty()) {
                answer.append("\n**Key Relationships & Evidence:**\n");
                for (var edge : relevantEdges.stream().limit(6).collect(Collectors.toList())) {
                    answer.append("• **").append(edge.getSource()).append("** —[`").append(edge.getRelation())
                            .append("`]-> **")
                            .append(edge.getTarget()).append("**");
                    if (edge.getConfidence() != null) {
                        answer.append(" (Confidence: ").append(String.format("%.0f%%", edge.getConfidence() * 100))
                                .append(")");
                    }
                    answer.append("\n");
                    if (edge.getEvidenceText() != null && !edge.getEvidenceText().isBlank()) {
                        answer.append("  > \"").append(edge.getEvidenceText()).append("\"\n");
                        matchedSnippets.add(edge.getEvidenceText());
                    }
                    if (edge.getSourceFile() != null) {
                        matchedSources.add(edge.getSourceFile());
                    }
                }
            }

            if (!relevantEvents.isEmpty()) {
                answer.append("\n**Timeline Occurrences:**\n");
                for (var ev : relevantEvents.stream().limit(5).collect(Collectors.toList())) {
                    answer.append("• `").append(ev.getTimestamp()).append("`: **").append(ev.getTitle()).append("**\n");
                    if (ev.getDescription() != null && !ev.getDescription().isBlank()) {
                        answer.append("  ").append(ev.getDescription()).append("\n");
                    }
                    if (ev.getSourceFile() != null) {
                        matchedSources.add(ev.getSourceFile());
                    }
                }
            }
        } else if (!graph.getNodes().isEmpty()) {
            answer.append("Here is an overview of the current case knowledge graph (").append(graph.getNodes().size())
                    .append(" entities, ").append(graph.getEdges().size()).append(" relationships):\n\n");
            answer.append("• **Primary Entities**: ");
            answer.append(graph.getNodes().stream().limit(8).map(GraphDataResponse.Node::getName)
                    .collect(Collectors.joining(", ")));
            answer.append(
                    "\n\nYou can ask about specific people (e.g. *\"Who is Arjun?\"* or *\"Show calls involving Rose\"*) or examine timeline occurrences.");
        } else {
            answer.append(
                    "No processed graph data found for this case yet. Please upload evidence documents and click **Let's Lock in with SHERLOCK** to trigger entity and relationship extraction.");
        }

        response.setAnswer(answer.toString());
        response.setReferencedEntities(matchedEntities.stream().distinct().collect(Collectors.toList()));
        response.setReferencedSources(matchedSources.stream().distinct().collect(Collectors.toList()));
        response.setEvidenceSnippets(matchedSnippets.stream().distinct().collect(Collectors.toList()));
        return response;
    }

    @SuppressWarnings("unchecked")
    private void ensureNodeExists(JsonNode nodeJson, Map<String, GraphDataResponse.Node> nodeMap,
            List<GraphDataResponse.Node> nodes) {
        if (nodeJson == null)
            return;
        String name = nodeJson.isObject() && nodeJson.has("name") ? nodeJson.get("name").asText() : nodeJson.asText();
        String id = nodeJson.isObject() && nodeJson.has("id") ? nodeJson.get("id").asText() : slugify(name);
        String type = nodeJson.isObject() && nodeJson.has("type") ? nodeJson.get("type").asText() : "ENTITY";

        if (!nodeMap.containsKey(name.toLowerCase(Locale.ROOT))) {
            GraphDataResponse.Node n = new GraphDataResponse.Node(id, name, type);
            if (nodeJson.isObject() && nodeJson.has("data")) {
                try {
                    Map<String, Object> data = objectMapper.convertValue(nodeJson.get("data"), Map.class);
                    n.setData(data);
                } catch (Exception ignored) {
                }
            }
            nodes.add(n);
            nodeMap.put(name.toLowerCase(Locale.ROOT), n);
        }
    }

    private Path getCaseDirectory(String caseId) {
        Path rootDir = Paths.get(appProperties.getBaseDirectory()).toAbsolutePath().normalize();
        if (caseId == null || caseId.isBlank()) {
            throw new IllegalArgumentException("Case id must not be empty");
        }
        Path caseDirectory = rootDir.resolve(caseId).normalize();
        // Guard against path traversal (e.g. caseId = "../other-case" or absolute
        // paths)
        if (!caseDirectory.startsWith(rootDir) || caseDirectory.equals(rootDir)) {
            throw new IllegalArgumentException("Invalid case id: " + caseId);
        }
        return caseDirectory;
    }

    private String resolvePythonScriptPath() {
        // 1. Explicit configuration wins
        if (appProperties.getPythonScriptPath() != null && !appProperties.getPythonScriptPath().isBlank()) {
            File f = new File(appProperties.getPythonScriptPath());
            if (f.exists())
                return f.getAbsolutePath();
            log.warn("Configured sherlock.python-script-path does not exist: {}", appProperties.getPythonScriptPath());
        }

        // 2. Search upward from the current working directory (covers running the
        // backend from the repo root, app/sherlock-spring-backend, or any nested dir)
        Path cur = Paths.get("").toAbsolutePath();
        for (Path p = cur; p != null; p = p.getParent()) {
            Path candidate = p.resolve("main.py");
            if (Files.exists(candidate))
                return candidate.toAbsolutePath().toString();
        }

        // 3. Search relative to where the application classes/jar live
        try {
            Path codeLocation = Paths.get(
                    CaseService.class.getProtectionDomain().getCodeSource().getLocation().toURI()).toAbsolutePath();
            for (Path p = codeLocation; p != null; p = p.getParent()) {
                Path candidate = p.resolve("main.py");
                if (Files.exists(candidate))
                    return candidate.toAbsolutePath().toString();
            }
        } catch (Exception ignored) {
        }

        log.warn("main.py could not be located automatically; falling back to relative 'main.py'");
        return "main.py";
    }

    /**
     * Picks the Python interpreter used to run main.py. Prefers the project
     * virtualenv (".venv" next to main.py) so the engine dependencies
     * (openai, tiktoken, scikit-learn) are available, unless a custom
     * interpreter was explicitly configured via sherlock.python-command.
     */
    private String resolvePythonCommand(String pythonScript) {
        String configured = appProperties.getPythonCommand();
        Path scriptDir = Paths.get(pythonScript).toAbsolutePath().getParent();
        if (scriptDir != null) {
            Path venvPython = isWindows()
                    ? scriptDir.resolve(".venv").resolve("Scripts").resolve("python.exe")
                    : scriptDir.resolve(".venv").resolve("bin").resolve("python");
            if (Files.exists(venvPython)
                    && (configured == null || configured.isBlank()
                            || configured.equals("python3") || configured.equals("python"))) {
                log.info("Using project virtualenv Python (.venv): {}", venvPython.toAbsolutePath());
                return venvPython.toAbsolutePath().toString();
            }

            Path venvPythonNoDot = isWindows()
                    ? scriptDir.resolve("venv").resolve("Scripts").resolve("python.exe")
                    : scriptDir.resolve("venv").resolve("bin").resolve("python");
            if (Files.exists(venvPythonNoDot)
                    && (configured == null || configured.isBlank()
                            || configured.equals("python3") || configured.equals("python"))) {
                log.info("Using project virtualenv Python (venv): {}", venvPythonNoDot.toAbsolutePath());
                return venvPythonNoDot.toAbsolutePath().toString();
            }
        }
        return (configured == null || configured.isBlank()) ? "python3" : configured;
    }

    private boolean isWindows() {
        return System.getProperty("os.name", "").toLowerCase(Locale.ROOT).contains("win");
    }

    private void writeLlmConfig(Path caseDirectory, LlmConfigRequest config) throws IOException {
        Path llmFile = caseDirectory.resolve("llm.json");
        objectMapper.writerWithDefaultPrettyPrinter().writeValue(llmFile.toFile(), config);
        log.info("Wrote case LLM config to: {}", llmFile);
    }

    private void writeMetadata(Path caseDirectory, CaseResponse response) throws IOException {
        Path metadataPath = caseDirectory.resolve("metadata.json");
        objectMapper.writerWithDefaultPrettyPrinter().writeValue(metadataPath.toFile(), response);
    }

    private CaseResponse readCaseMetadata(Path caseDirectory) {
        Path metadataPath = caseDirectory.resolve("metadata.json");
        if (!Files.exists(metadataPath)) {
            String caseId = caseDirectory.getFileName().toString();
            CaseResponse fallback = new CaseResponse(
                    caseId,
                    caseId,
                    caseDirectory.toString(),
                    caseDirectory.resolve("data").toString(),
                    LocalDateTime.now(),
                    "READY");
            updateFileListInResponse(fallback, caseDirectory.resolve("data"));
            return fallback;
        }

        try {
            CaseResponse response = objectMapper.readValue(metadataPath.toFile(), CaseResponse.class);
            updateFileListInResponse(response, caseDirectory.resolve("data"));
            return response;
        } catch (IOException e) {
            log.error("Unable to read case metadata for: {}", caseDirectory, e);
            return null;
        }
    }

    private void updateFileListInResponse(CaseResponse response, Path dataDirectory) {
        if (Files.exists(dataDirectory) && Files.isDirectory(dataDirectory)) {
            try (var stream = Files.list(dataDirectory)) {
                List<String> files = stream
                        .filter(Files::isRegularFile)
                        .map(p -> p.getFileName().toString())
                        .collect(Collectors.toList());
                response.setFiles(files);
                response.setFileCount(files.size());
            } catch (IOException ignored) {
            }
        }
    }

    public boolean syncToNeo4j(String caseId) {
        if (neo4jGraphService == null || !neo4jGraphService.isConnected()) {
            return false;
        }
        GraphDataResponse graphData = readGraphDataFromFiles(caseId);
        return neo4jGraphService.syncGraphToNeo4j(caseId, graphData);
    }

    public List<Map<String, Object>> executeNeo4jCypher(String caseId, String cypherQuery) {
        if (neo4jGraphService == null || !neo4jGraphService.isConnected()) {
            return Collections.emptyList();
        }
        return neo4jGraphService.executeCypher(cypherQuery, Map.of("caseId", caseId));
    }

    public List<String> getOllamaModels() {
        return ollamaService != null ? ollamaService.getAvailableModels() : Collections.emptyList();
    }

    public Map<String, Object> getNeo4jStatus() {
        return neo4jGraphService != null ? neo4jGraphService.getStatus() : Map.of("enabled", false, "connected", false);
    }

    private GraphDataResponse getGraphDataFromFileOnly(String caseId) {
        return readGraphDataFromFiles(caseId);
    }

    private String normalizeCaseName(String caseName) {
        String trimmed = caseName == null ? "case" : caseName.trim();
        if (trimmed.isBlank()) {
            trimmed = "case";
        }
        return trimmed
                .replaceAll("[^a-zA-Z0-9\\s-]", "")
                .trim()
                .replaceAll("\\s+", "_")
                .toLowerCase(Locale.ROOT);
    }

    private String normalizeCaseId(String caseId) {
        return caseId.trim().replaceAll("[^a-zA-Z0-9_-]", "_");
    }

    private String generateCaseId(String normalizedCaseName) {
        String datePart = LocalDateTime.now().format(DateTimeFormatter.ofPattern("yyyyMMdd_HHmmss"));
        return normalizedCaseName + "_" + datePart;
    }

    private String sanitizeFileName(String fileName) {
        return fileName.replaceAll("[\\\\/:*?\"<>|]", "_");
    }

    private String slugify(String text) {
        if (text == null)
            return "entity";
        return text.trim().toLowerCase(Locale.ROOT).replaceAll("[^a-z0-9]+", "_");
    }
}
