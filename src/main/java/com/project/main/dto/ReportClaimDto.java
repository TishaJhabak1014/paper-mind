package com.project.main.dto;

import java.util.List;

public record ReportClaimDto(String text, List<String> sourcePaperIds, String caveat) {}
