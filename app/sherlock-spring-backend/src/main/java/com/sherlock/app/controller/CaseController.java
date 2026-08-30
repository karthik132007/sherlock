package com.sherlock.app.controller;

import com.sherlock.app.model.*;
import com.sherlock.app.service.CaseService;
import jakarta.validation.Valid;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.multipart.MultipartFile;

import java.io.IOException;
import java.util.List;

@RestController
@RequestMapping("/api")
@CrossOrigin(origins = "*")
public class CaseController {

    private final CaseService caseService;

    public CaseController(CaseService caseService) {
        this.caseService = caseService;
    }

    @PostMapping("/cases")
    public ResponseEntity<CaseResponse> createCase(@Valid @RequestBody CaseRequest request) {
        CaseResponse response = caseService.createCase(request);
        return ResponseEntity.status(HttpStatus.CREATED).body(response);
    }

    @GetMapping("/cases/next-id")
    public ResponseEntity<java.util.Map<String, String>> getNextCaseId() {
        return ResponseEntity.ok(java.util.Map.of("nextCaseId", caseService.getNextCaseId()));
    }

    @GetMapping("/cases")
    public ResponseEntity<List<CaseResponse>> listCases() {
        return ResponseEntity.ok(caseService.listCases());
    }

    @PutMapping("/cases/{caseId}/llm")
    public ResponseEntity<CaseResponse> updateLlmConfig(@PathVariable String caseId, @Valid @RequestBody LlmConfigRequest request) {
        return ResponseEntity.ok(caseService.updateLlmConfig(caseId, request));
    }

    @GetMapping("/cases/{caseId}")
    public ResponseEntity<CaseResponse> getCase(@PathVariable String caseId) {
        return ResponseEntity.ok(caseService.getCase(caseId));
    }

    @PostMapping(value = "/cases/{caseId}/files", consumes = MediaType.MULTIPART_FORM_DATA_VALUE)
    public ResponseEntity<CaseResponse> uploadFiles(
            @PathVariable String caseId,
            @RequestParam("files") List<MultipartFile> files) throws IOException {
        return ResponseEntity.ok(caseService.saveUploadedFiles(caseId, files));
    }

    @PostMapping("/cases/{caseId}/process")
    public ResponseEntity<ProcessingStatus> processCase(@PathVariable String caseId) {
        return ResponseEntity.ok(caseService.startProcessing(caseId));
    }

    @GetMapping("/cases/{caseId}/status")
    public ResponseEntity<ProcessingStatus> getProcessingStatus(@PathVariable String caseId) {
        return ResponseEntity.ok(caseService.getProcessingStatus(caseId));
    }

    @GetMapping("/cases/{caseId}/graph")
    public ResponseEntity<GraphDataResponse> getGraphData(@PathVariable String caseId) {
        return ResponseEntity.ok(caseService.getGraphData(caseId));
    }

    @PostMapping("/cases/{caseId}/timeline/process")
    public ResponseEntity<ProcessingStatus> processTimeline(@PathVariable String caseId) {
        return ResponseEntity.ok(caseService.startTimelineProcessing(caseId));
    }

    @GetMapping("/cases/{caseId}/timeline/status")
    public ResponseEntity<ProcessingStatus> getTimelineProcessingStatus(@PathVariable String caseId) {
        return ResponseEntity.ok(caseService.getTimelineProcessingStatus(caseId));
    }

    @GetMapping("/cases/{caseId}/timeline")
    public ResponseEntity<TimelineEventResponse> getTimeline(@PathVariable String caseId) {
        return ResponseEntity.ok(caseService.getTimeline(caseId));
    }

    @PostMapping("/cases/{caseId}/contradictions/process")
    public ResponseEntity<ProcessingStatus> processContradictions(@PathVariable String caseId) {
        return ResponseEntity.ok(caseService.startContradictionsProcessing(caseId));
    }

    @GetMapping("/cases/{caseId}/contradictions/status")
    public ResponseEntity<ProcessingStatus> getContradictionsProcessingStatus(@PathVariable String caseId) {
        return ResponseEntity.ok(caseService.getContradictionsProcessingStatus(caseId));
    }

    @GetMapping("/cases/{caseId}/contradictions")
    public ResponseEntity<ContradictionResponse> getContradictions(@PathVariable String caseId) {
        return ResponseEntity.ok(caseService.getContradictions(caseId));
    }

    @GetMapping("/neo4j/status")
    public ResponseEntity<java.util.Map<String, Object>> getNeo4jStatus() {
        return ResponseEntity.ok(caseService.getNeo4jStatus());
    }

    @PostMapping("/cases/{caseId}/neo4j/sync")
    public ResponseEntity<java.util.Map<String, Object>> syncToNeo4j(@PathVariable String caseId) {
        boolean success = caseService.syncToNeo4j(caseId);
        return ResponseEntity.ok(java.util.Map.of("caseId", caseId, "synced", success));
    }

    @GetMapping("/llm/ollama/models")
    public ResponseEntity<List<String>> getOllamaModels() {
        return ResponseEntity.ok(caseService.getOllamaModels());
    }

    @PostMapping("/cases/{caseId}/neo4j/query")
    public ResponseEntity<java.util.Map<String, Object>> queryNeo4j(
            @PathVariable String caseId,
            @RequestBody java.util.Map<String, String> payload) {
        String cypher = payload.getOrDefault("cypher", "");
        List<java.util.Map<String, Object>> records = caseService.executeNeo4jCypher(caseId, cypher);
        return ResponseEntity.ok(java.util.Map.of("caseId", caseId, "cypher", cypher, "records", records));
    }

    @PostMapping("/cases/{caseId}/chat")
    public ResponseEntity<ChatResponse> chatWithSherlock(
            @PathVariable String caseId,
            @RequestBody ChatRequest request) {
        return ResponseEntity.ok(caseService.chatWithSherlock(caseId, request));
    }

    @GetMapping("/cases/{caseId}/chat")
    public ResponseEntity<List<java.util.Map<String, Object>>> getChatHistory(@PathVariable String caseId) {
        return ResponseEntity.ok(caseService.getChatHistory(caseId));
    }
}
