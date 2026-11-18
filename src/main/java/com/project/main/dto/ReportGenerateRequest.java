package com.project.main.dto;

import java.util.List;

public record ReportGenerateRequest(
        Integer graphId,
        ReportType reportType,
        ReportScope scope,
        List<String> includedPaperIds) {}
