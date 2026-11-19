package com.project.main.controller;

import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.project.main.dto.SummaryResponse;
import com.project.main.service.PaperSummaryService;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.MockitoAnnotations;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;
import org.springframework.web.server.ResponseStatusException;
import org.springframework.http.HttpStatus;

class AISummaryControllerTest {
    @Mock
    private PaperSummaryService paperSummaryService;

    @InjectMocks
    private AISummaryController controller;

    private MockMvc mockMvc;

    @BeforeEach
    void setUp() {
        MockitoAnnotations.openMocks(this);
        mockMvc = MockMvcBuilders.standaloneSetup(controller).build();
    }

    @Test
    void returnsTypedSummaryForPaperId() throws Exception {
        when(paperSummaryService.summarize("paper-1"))
                .thenReturn(new SummaryResponse("paper-1", "A concise summary.", "Available abstract"));

        mockMvc.perform(post("/api/generate-summary")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"paperId\":\"paper-1\"}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.paperId").value("paper-1"))
                .andExpect(jsonPath("$.summary").value("A concise summary."))
                .andExpect(jsonPath("$.basis").value("Available abstract"));
    }

    @Test
    void returnsExplicitErrorWhenAbstractIsUnavailable() throws Exception {
        when(paperSummaryService.summarize("paper-2")).thenThrow(new ResponseStatusException(
                HttpStatus.UNPROCESSABLE_CONTENT,
                "No abstract was supplied by Semantic Scholar or the available fallback sources for this paper."));

        mockMvc.perform(post("/api/generate-summary")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"paperId\":\"paper-2\"}"))
                .andExpect(status().is(422));
    }

    @Test
    void returnsProviderFailureForValidJsonRequest() throws Exception {
        when(paperSummaryService.summarize("paper-3")).thenThrow(new ResponseStatusException(
                HttpStatus.BAD_GATEWAY, "The AI service could not generate an abstract summary. Please retry."));

        mockMvc.perform(post("/api/generate-summary")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"paperId\":\"paper-3\"}"))
                .andExpect(status().isBadGateway());
    }
}
