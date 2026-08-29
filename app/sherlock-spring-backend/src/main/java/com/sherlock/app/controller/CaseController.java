package com.sherlock.app.controller;

import com.sherlock.app.model.CaseRequest;
import com.sherlock.app.model.CaseResponse;
import com.sherlock.app.model.ProcessingStatus;
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
public class CaseController {

    private final CaseService caseService;

    public CaseController(CaseService caseService) {
        this.caseService = caseService;
    }

    @PostMapping("/cases")
    public ResponseEntity<CaseResponse> createCase(@Valid @RequestBody CaseRequest request) {
        CaseResponse response = caseService.createCase(request.getCaseName());
        return ResponseEntity.status(HttpStatus.CREATED).body(response);
    }

    @GetMapping("/cases")
    public ResponseEntity<List<CaseResponse>> listCases() {
        return ResponseEntity.ok(caseService.listCases());
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

    @GetMapping("/cases/{caseId}")
    public ResponseEntity<CaseResponse> getCase(@PathVariable String caseId) {
        return ResponseEntity.ok(caseService.getCase(caseId));
    }
}
