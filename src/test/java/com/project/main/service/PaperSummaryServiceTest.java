package com.project.main.service;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.argThat;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import java.time.Duration;
import java.time.LocalDateTime;

import com.project.main.dto.SummaryResponse;
import com.project.main.entity.ResearchPaper;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.test.util.ReflectionTestUtils;
import org.springframework.web.server.ResponseStatusException;

@ExtendWith(MockitoExtension.class)
class PaperSummaryServiceTest {
    @Mock
    private ResearchPaperService researchPaperService;

    @Mock
    private AiTextGateway aiTextGateway;

    @Mock
    private RedisCacheService cacheService;

    @InjectMocks
    private PaperSummaryService paperSummaryService;

    @BeforeEach
    void setUp() {
        ReflectionTestUtils.setField(paperSummaryService, "model", "test-model");
        ReflectionTestUtils.setField(paperSummaryService, "configuredPromptVersion", "test-prompt-v2");
        ReflectionTestUtils.setField(paperSummaryService, "summaryCacheTtl", Duration.ofDays(1));
    }

    @Test
    void summarizesOnlyTheServerStoredAbstract() {
        String abstractText = "The server stored abstract is the only evidence.";
        when(researchPaperService.getResearchPaperBySemanticPaperId("paper-1"))
                .thenReturn(new ResearchPaper("paper-1", "Example", LocalDateTime.of(2020, 1, 1, 0, 0),
                        abstractText, null, "https://example.org/paper", 4, 0));
        when(cacheService.get(anyString())).thenReturn(null);
        when(aiTextGateway.generate(anyString())).thenReturn("A concise summary.");

        SummaryResponse response = paperSummaryService.summarize("paper-1");

        assertEquals("paper-1", response.paperId());
        assertEquals("A concise summary.", response.summary());
        assertEquals("Available abstract", response.basis());
        verify(aiTextGateway).generate(argThat(prompt -> prompt.contains(abstractText)
                && prompt.contains("Do not imply that you read the full paper")));
    }

    @Test
    void refusesToSummarizeWhenNoServerAbstractExists() {
        when(researchPaperService.getResearchPaperBySemanticPaperId("paper-2"))
                .thenReturn(new ResearchPaper("paper-2", "Example", null, " ", null, null, 0, 0));

        ResponseStatusException exception = assertThrows(ResponseStatusException.class,
                () -> paperSummaryService.summarize("paper-2"));

        assertEquals(422, exception.getStatusCode().value());
        verify(aiTextGateway, never()).generate(anyString());
    }

    @Test
    void returnsCachedSummaryWithoutCallingModel() {
        when(researchPaperService.getResearchPaperBySemanticPaperId("paper-3"))
                .thenReturn(new ResearchPaper("paper-3", "Example", null, "Stored abstract", null, null, 0, 0));
        when(cacheService.get(anyString())).thenReturn("Cached summary.");

        SummaryResponse response = paperSummaryService.summarize("paper-3");

        assertEquals("Cached summary.", response.summary());
        verify(aiTextGateway, never()).generate(anyString());
    }
}
