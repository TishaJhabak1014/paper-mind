package com.project.main.dto;

import java.util.List;

public record ReportPreviewRequest(
        Integer graphId,
        ReportType reportType,
        ReportScope scope,
        List<String> selectedPaperIds) {}
