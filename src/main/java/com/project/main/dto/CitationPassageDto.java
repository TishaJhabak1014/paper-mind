package com.project.main.dto;

import java.util.List;

public record CitationPassageDto(
        String contextId,
        String focalPaperId,
        String citingPaperId,
        String citingPaperTitle,
        List<String> citingPaperAuthors,
        Integer citingPaperYear,
        String passage,
        String citingPaperUrl,
        List<String> intents,
        boolean influential) {}
