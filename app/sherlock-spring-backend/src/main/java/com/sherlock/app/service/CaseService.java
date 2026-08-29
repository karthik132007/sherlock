package com.sherlock.app.service;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.SerializationFeature;
import com.fasterxml.jackson.datatype.jsr310.JavaTimeModule;
import com.sherlock.app.config.AppProperties;
import com.sherlock.app.model.CaseResponse;
import com.sherlock.app.model.ProcessingStatus;
import org.springframework.stereotype.Service;
import org.springframework.web.multipart.MultipartFile;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.*;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Set;
import java.util.stream.Collectors;

@Service
public class CaseService {

    private static final Set<String> ALLOWED_EXTENSIONS = Set.of(
            ".txt", ".pdf", ".png", ".jpg", ".jpeg", ".doc", ".docx"
    );

    private final AppProperties appProperties;
    private final ObjectMapper objectMapper;

    public CaseService(AppProperties appProperties) {
        this.appProperties = appProperties;
        this.objectMapper = new ObjectMapper();
        this.objectMapper.registerModule(new JavaTimeModule());
        this.objectMapper.disable(SerializationFeature.WRITE_DATES_AS_TIMESTAMPS);
    }

    public CaseResponse createCase(String caseName) {
        String normalizedCaseName = normalizeCaseName(caseName);
        String caseId = generateCaseId(normalizedCaseName);

        Path rootDir = Paths.get(appProperties.getBaseDirectory());
        Path caseDirectory = rootDir.resolve(caseId);
        Path dataDirectory = caseDirectory.resolve("data");

        try {
            Files.createDirectories(dataDirectory);
            writeMetadata(caseDirectory, caseName, caseId);
        } catch (IOException e) {
            throw new IllegalStateException("Unable to create case directory for: " + caseName, e);
        }

        return new CaseResponse(
                caseId,
                caseName,
                caseDirectory.toString(),
                dataDirectory.toString(),
                LocalDateTime.now(),
                "READY"
        );
    }

    public List<CaseResponse> listCases() {
        Path rootDir = Paths.get(appProperties.getBaseDirectory());
        if (!Files.exists(rootDir)) {
            return List.of();
        }

        try (var paths = Files.list(rootDir)) {
            return paths.filter(Files::isDirectory)
                    .map(this::readCaseMetadata)
                    .filter(response -> response != null)
                    .collect(Collectors.toList());
        } catch (IOException e) {
            throw new IllegalStateException("Unable to list Sherlock cases", e);
        }
    }

    public CaseResponse getCase(String caseId) {
        Path rootDir = Paths.get(appProperties.getBaseDirectory());
        Path caseDirectory = rootDir.resolve(caseId);
        if (!Files.exists(caseDirectory)) {
            throw new IllegalArgumentException("Case not found: " + caseId);
        }
        return readCaseMetadata(caseDirectory);
    }

    public CaseResponse saveUploadedFiles(String caseId, List<MultipartFile> files) throws IOException {
        Path caseDirectory = Paths.get(appProperties.getBaseDirectory()).resolve(caseId);
        if (!Files.exists(caseDirectory)) {
            throw new IllegalArgumentException("Case not found: " + caseId);
        }

        Path dataDirectory = caseDirectory.resolve("data");
        Files.createDirectories(dataDirectory);

        for (MultipartFile file : files) {
            if (file == null || file.isEmpty()) {
                continue;
            }

            String originalName = file.getOriginalFilename();
            if (originalName == null || originalName.isBlank()) {
                continue;
            }

            String extension = originalName.substring(originalName.lastIndexOf('.')).toLowerCase(Locale.ROOT);
            if (!ALLOWED_EXTENSIONS.contains(extension)) {
                throw new IllegalArgumentException("Unsupported file type: " + originalName);
            }

            Path target = dataDirectory.resolve(sanitizeFileName(originalName));
            Files.copy(file.getInputStream(), target, StandardCopyOption.REPLACE_EXISTING);
        }

        CaseResponse response = readCaseMetadata(caseDirectory);
        response.setStatus("FILES_UPLOADED");
        return response;
    }

    public ProcessingStatus startProcessing(String caseId) {
        Path caseDirectory = Paths.get(appProperties.getBaseDirectory()).resolve(caseId);
        if (!Files.exists(caseDirectory)) {
            throw new IllegalArgumentException("Case not found: " + caseId);
        }

        ProcessingStatus status = new ProcessingStatus();
        status.setCaseId(caseId);
        status.setStatus("STARTING");
        status.setScriptPath(appProperties.getPythonScriptPath());
        status.setCommand(String.join(" ", buildCommandTokens(caseId)));
        status.setMessage("Python processing will start and read the case data folder.");

        String[] command = buildCommandTokens(caseId).toArray(new String[0]);
        try {
            ProcessBuilder processBuilder = new ProcessBuilder(command);
            processBuilder.directory(caseDirectory.toFile());
            processBuilder.redirectErrorStream(true);
            Process process = processBuilder.start();
            status.setStatus("PROCESSING");
            status.setMessage("Python processing started for case " + caseId + ".");
            try {
                String output = new String(process.getInputStream().readAllBytes(), StandardCharsets.UTF_8);
                if (!output.isBlank()) {
                    status.setMessage(output.trim());
                }
            } catch (IOException ignored) {
                // Intentionally non-blocking; UI shows generic status if output is not immediately available.
            }
        } catch (IOException e) {
            status.setStatus("FAILED");
            status.setMessage("Unable to start Python processing: " + e.getMessage());
        }

        return status;
    }

    private List<String> buildCommandTokens(String caseId) {
        List<String> tokens = new ArrayList<>();
        tokens.add(appProperties.getPythonCommand());
        if (!appProperties.getPythonScriptPath().isBlank()) {
            tokens.add(appProperties.getPythonScriptPath());
        }
        tokens.add(appProperties.getProcessArgument());
        tokens.add(caseId);
        return tokens;
    }

    private void writeMetadata(Path caseDirectory, String caseName, String caseId) throws IOException {
        Path metadataPath = caseDirectory.resolve("metadata.json");
        CaseResponse response = new CaseResponse(
                caseId,
                caseName,
                caseDirectory.toString(),
                caseDirectory.resolve("data").toString(),
                LocalDateTime.now(),
                "READY"
        );
        objectMapper.writerWithDefaultPrettyPrinter().writeValue(metadataPath.toFile(), response);
    }

    private CaseResponse readCaseMetadata(Path caseDirectory) {
        Path metadataPath = caseDirectory.resolve("metadata.json");
        if (!Files.exists(metadataPath)) {
            return null;
        }

        try {
            return objectMapper.readValue(metadataPath.toFile(), CaseResponse.class);
        } catch (IOException e) {
            throw new IllegalStateException("Unable to read case metadata for: " + caseDirectory, e);
        }
    }

    private String normalizeCaseName(String caseName) {
        String trimmed = caseName == null ? "case" : caseName.trim();
        if (trimmed.isBlank()) {
            trimmed = "case";
        }
        return trimmed
                .replaceAll("[^a-zA-Z0-9\s-]", "")
                .trim()
                .replaceAll("\\s+", "_")
                .toLowerCase(Locale.ROOT);
    }

    private String generateCaseId(String normalizedCaseName) {
        String datePart = LocalDateTime.now().format(DateTimeFormatter.ofPattern("yyyyMMdd_HHmmss"));
        return normalizedCaseName + "_" + datePart;
    }

    private String sanitizeFileName(String fileName) {
        return fileName.replaceAll("[\\\\/:*?\"<>|]", "_");
    }
}
