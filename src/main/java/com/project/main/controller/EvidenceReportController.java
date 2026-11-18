package com.project.main.controller;

import com.project.main.dto.EvidenceReportResponse;
import com.project.main.dto.ReportGenerateRequest;
import com.project.main.dto.ReportPreviewRequest;
import com.project.main.dto.ReportPreviewResponse;
import com.project.main.service.EvidenceReportService;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.server.ResponseStatusException;

@RestController
@RequestMapping("/api/reports")
public class EvidenceReportController {
    private final EvidenceReportService evidenceReportService;

    public EvidenceReportController(EvidenceReportService evidenceReportService) {
        this.evidenceReportService = evidenceReportService;
    }

    @PostMapping("/preview")
    public ResponseEntity<ReportPreviewResponse> preview(@RequestBody ReportPreviewRequest request) {
        return ResponseEntity.ok(evidenceReportService.preview(request));
    }

    @PostMapping("/generate")
    public ResponseEntity<EvidenceReportResponse> generate(@RequestBody ReportGenerateRequest request) {
        return ResponseEntity.ok(evidenceReportService.generate(request));
    }

    @ExceptionHandler(ResponseStatusException.class)
    public ResponseEntity<String> handleReportFailure(ResponseStatusException exception) {
        String message = exception.getReason() == null ? "The research report could not be completed." : exception.getReason();
        return ResponseEntity.status(exception.getStatusCode()).body(message);
    }
}
