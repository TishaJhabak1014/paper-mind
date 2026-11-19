package com.project.main.dto;

/** How one selected abstract contributed to, or was omitted from, an evidence report. */
public record SourceCoverageDto(String paperId, String status, String rationale) {}
