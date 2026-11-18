package com.project.main.service;

import java.time.Duration;

import com.project.main.dto.SummaryResponse;
import com.project.main.entity.ResearchPaper;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.web.server.ResponseStatusException;

/** Summarizes only abstract text retrieved from PaperMind's server-side paper record. */
@Service
public class PaperSummaryService {
    private static final String PROMPT_VERSION = "2";

    private final ResearchPaperService researchPaperService;
    private final AiTextGateway aiTextGateway;
    private final RedisCacheService cacheService;

    @Value("${google.ai.model:gemini-3.8-flash}")
    private String model;

    @Value("${app.cache.summary-ttl:30d}")
    private Duration summaryCacheTtl;

    @Value("${app.summary.prompt-version:2}")
    private String configuredPromptVersion;

    public PaperSummaryService(ResearchPaperService researchPaperService,
            AiTextGateway aiTextGateway,
            RedisCacheService cacheService) {
        this.researchPaperService = researchPaperService;
        this.aiTextGateway = aiTextGateway;
        this.cacheService = cacheService;
    }

    public SummaryResponse summarize(String semanticPaperId) {
        if (semanticPaperId == null || semanticPaperId.isBlank()) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "A paper ID is required.");
        }
        ResearchPaper paper = researchPaperService.getResearchPaperBySemanticPaperId(semanticPaperId.trim());
        if (paper == null) {
            throw new ResponseStatusException(HttpStatus.NOT_FOUND,
                    "This paper is not available in the current search results.");
        }
        String abstractText = paper.getAbstractText();
        if (abstractText == null || abstractText.isBlank()) {
            throw new ResponseStatusException(HttpStatus.UNPROCESSABLE_CONTENT,
                    "No abstract was supplied by Semantic Scholar or the available fallback sources for this paper.");
        }

        String promptVersion = configuredPromptVersion == null || configuredPromptVersion.isBlank()
                ? PROMPT_VERSION : configuredPromptVersion;
        String cacheKey = "papermind:summary:" + RedisCacheService.digest(semanticPaperId.trim())
                + ":" + RedisCacheService.digest(model == null ? "" : model)
                + ":v" + RedisCacheService.digest(promptVersion)
                + ":" + RedisCacheService.digest(abstractText);
        String cached = cacheService.get(cacheKey);
        if (cached != null && !cached.isBlank()) {
            return new SummaryResponse(semanticPaperId.trim(), cached, "Available abstract");
        }

        String prompt = "Summarize the following academic paper abstract in one clear paragraph for a general research audience. "
                + "Use only the supplied abstract. Do not imply that you read the full paper, do not add methods or results absent from the abstract, "
                + "and do not invent bibliographic details.\n\n<abstract>\n" + abstractText.trim() + "\n</abstract>";
        String summary;
        try {
            summary = aiTextGateway.generate(prompt);
        } catch (ResponseStatusException exception) {
            throw exception;
        } catch (RuntimeException exception) {
            throw new ResponseStatusException(HttpStatus.BAD_GATEWAY,
                    "The AI service could not generate an abstract summary. Please retry.", exception);
        }
        if (summary == null || summary.isBlank()) {
            throw new ResponseStatusException(HttpStatus.BAD_GATEWAY,
                    "The AI provider returned no abstract summary. Please retry.");
        }

        summary = summary.trim();
        cacheService.put(cacheKey, summary, summaryCacheTtl);
        return new SummaryResponse(semanticPaperId.trim(), summary, "Available abstract");
    }
}
