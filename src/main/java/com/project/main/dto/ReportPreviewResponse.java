package com.project.main.dto;

import java.util.List;

public record ReportPreviewResponse(
        Integer graphId,
        String graphType,
        ReportType reportType,
        ReportScope scope,
        String researchQuestion,
        List<ReportScopeOption> scopes,
        List<ReportCandidateDto> candidates,
        boolean canGenerate,
        String message,
        String coverageDescription) {}
