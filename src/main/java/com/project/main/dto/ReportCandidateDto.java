package com.project.main.dto;

import java.util.List;

public record ReportCandidateDto(
        String paperId,
        String title,
        List<String> authors,
        Integer year,
        String sourceUrl,
        boolean hasAbstract,
        List<String> classifications,
        List<CitationPassageDto> citationPassages) {}
