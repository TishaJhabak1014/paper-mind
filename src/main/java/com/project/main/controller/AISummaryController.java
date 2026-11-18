package com.project.main.controller;

import com.project.main.dto.SummaryRequest;
import com.project.main.dto.SummaryResponse;
import com.project.main.service.PaperSummaryService;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.server.ResponseStatusException;

@RestController
@RequestMapping("/api/generate-summary")
public class AISummaryController {
    private final PaperSummaryService paperSummaryService;

    public AISummaryController(PaperSummaryService paperSummaryService) {
        this.paperSummaryService = paperSummaryService;
    }

    @PostMapping
    public ResponseEntity<SummaryResponse> generateAISummary(@RequestBody SummaryRequest request) {
        SummaryResponse response = paperSummaryService.summarize(request == null ? null : request.paperId());
        return ResponseEntity.ok(response);
    }

    @ExceptionHandler(ResponseStatusException.class)
    public ResponseEntity<String> handleSummaryFailure(ResponseStatusException exception) {
        String message = exception.getReason() == null
                ? "The AI service could not generate an abstract summary. Please retry."
                : exception.getReason();
        return ResponseEntity.status(exception.getStatusCode()).body(message);
    }
}
