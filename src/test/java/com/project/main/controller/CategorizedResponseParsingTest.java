package com.project.main.controller;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.project.main.entity.AppResponse;
import com.project.main.entity.Graph;
import com.project.main.enums.GraphType;
import com.project.main.service.AppResponseService;
import com.project.main.service.GeminiService;
import com.project.main.service.GraphService;
import com.project.main.service.SemanticService;
import com.project.main.service.UserPromptService;
import com.project.main.service.TokenResponseService;
import com.project.main.service.ResearchPaperService;
import com.project.main.service.AuthorService;
import com.project.main.service.CitationEdgeService;
import com.project.main.service.OpinionService;
import com.project.main.service.OpinionRelatedPaperService;
import com.project.main.service.OpinionEdgeService;
import com.project.main.service.OpinionEdgeIdService;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.test.util.ReflectionTestUtils;

import tools.jackson.databind.ObjectMapper;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.node.ObjectNode;

class CategorizedResponseParsingTest {
    private final ObjectMapper mapper = new ObjectMapper();

    @Test
    void acceptsDirectClassificationJsonAndNormalizesMissingGroup() {
        APIController controller = new APIController();
        String raw = "{\"supporting\":{\"supports topic\":[\"p1\"]}}";

        var response = controller.categorizedResponseFormatting(raw);

        assertEquals(HttpStatus.OK, response.getStatusCode());
        assertEquals("p1", response.getBody().path("supporting").path("supports topic").path(0).asText());
        assertTrue(response.getBody().path("opposing").isObject());
    }

    @Test
    void parsesArrayKeywordsWithoutStanceSpecificSearchQueries() throws Exception {
        APIController controller = new APIController();
        ObjectNode envelope = mapper.createObjectNode();
        String generated = "{\"keywords\":[\"circadian rhythm\",\"sleep timing\"],\"sentiment\":\"positive\"}";
        envelope.putArray("candidates").addObject().putObject("content").putArray("parts").addObject()
                .put("text", generated);

        var response = controller.responseFormatting(mapper.writeValueAsString(envelope));

        assertEquals(HttpStatus.OK, response.getStatusCode());
        assertEquals("circadian rhythm sleep timing", response.getBody().path("keywords").asText());
        assertEquals("positive", response.getBody().path("sentiment").asText());
        assertTrue(response.getBody().path("supportingQuery").isMissingNode());
        assertTrue(response.getBody().path("opposingQuery").isMissingNode());
    }

    @Test
    void preservesEscapedQuotesBackslashesAndNewlinesInGeminiText() throws Exception {
        APIController controller = new APIController();
        ObjectNode envelope = mapper.createObjectNode();
        String classification = "{\"supporting\":{\"supports\":[\"p1\"]},\"opposing\":{\"limitations\":[\"p2\"]}}";
        envelope.putArray("candidates").addObject().putObject("content").putArray("parts").addObject()
                .put("text", "```json\n" + classification + "\n```");

        var response = controller.categorizedResponseFormatting(mapper.writeValueAsString(envelope));

        assertEquals(HttpStatus.OK, response.getStatusCode());
        assertEquals("p2", response.getBody().path("opposing").path("limitations").path(0).asText());
    }

    @Test
    void malformedSavedClassificationIsRegeneratedAndReplacedWithNormalizedJson() throws Exception {
        APIController controller = new APIController();
        GeminiService gemini = org.mockito.Mockito.mock(GeminiService.class);
        GraphService graphs = org.mockito.Mockito.mock(GraphService.class);
        AppResponseService responses = org.mockito.Mockito.mock(AppResponseService.class);
        Graph graph = new Graph();
        graph.setId(12);
        graph.setGraphType(GraphType.CITATION);
        AppResponse appResponse = new AppResponse();
        appResponse.setId(9);
        appResponse.setGeneratedResponse("{truncated");
        when(graphs.getGraphById(12)).thenReturn(graph);
        when(graphs.getAppResponseByGraphId(12)).thenReturn(appResponse);
        when(gemini.callApi(anyString(), anyString())).thenReturn(
                "{\"candidates\":[{\"content\":{\"parts\":[{\"text\":\"{\\\"supporting\\\":{\\\"supports\\\":[\\\"p1\\\"]},\\\"opposing\\\":{}}\"}]}}]}");
        ReflectionTestUtils.setField(controller, "geminiService", gemini);
        ReflectionTestUtils.setField(controller, "graphService", graphs);
        ReflectionTestUtils.setField(controller, "appResponseService", responses);
        ReflectionTestUtils.setField(controller, "apiKey", "test-key");
        ObjectNode request = mapper.createObjectNode().put("graphId", 12).put("prompt", "Question");
        request.putArray("papers").addObject().put("paperId", "p1");

        var result = controller.loadOpinionGraph(request);

        assertEquals(HttpStatus.OK, result.getStatusCode());
        assertEquals("p1", result.getBody().path("supporting").path("supports").path(0).asText());
        var saved = mapper.readTree(appResponse.getGeneratedResponse());
        assertTrue(saved.path("opposing").isObject());
        assertEquals(0, saved.path("opposing").size());
        verify(responses).updateAppResponse(9, appResponse);
    }

    @Test
    void malformedFirstClassificationIsRetriedForFreshSearch() throws Exception {
        APIController controller = new APIController();
        GeminiService gemini = org.mockito.Mockito.mock(GeminiService.class);
        GraphService graphs = org.mockito.Mockito.mock(GraphService.class);
        Graph graph = new Graph();
        graph.setId(13);
        graph.setGraphType(GraphType.CITATION);
        when(graphs.getGraphById(13)).thenReturn(graph);
        when(graphs.getAppResponseByGraphId(13)).thenReturn(null);
        when(gemini.callApi(anyString(), anyString())).thenReturn(
                "{\"candidates\":[{\"content\":{\"parts\":[{\"text\":\"{truncated\"}]}}]}",
                "{\"candidates\":[{\"content\":{\"parts\":[{\"text\":\"{\\\"supporting\\\":{\\\"supports\\\":[\\\"p1\\\"]},\\\"opposing\\\":{}}\"}]}}]}");
        ReflectionTestUtils.setField(controller, "geminiService", gemini);
        ReflectionTestUtils.setField(controller, "graphService", graphs);
        ReflectionTestUtils.setField(controller, "apiKey", "test-key");
        ObjectNode request = mapper.createObjectNode().put("graphId", 13).put("prompt", "Sleep is best at nighttime");
        request.putArray("papers").addObject().put("paperId", "p1");

        var result = controller.loadOpinionGraph(request);

        assertEquals(HttpStatus.OK, result.getStatusCode());
        assertEquals("p1", result.getBody().path("supporting").path("supports").path(0).asText());
        verify(gemini, org.mockito.Mockito.times(2)).callApi(anyString(), anyString());
    }

    @Test
    void legacyEmptyClassificationIsRegeneratedAndRetriesWithEvidence() throws Exception {
        APIController controller = new APIController();
        GeminiService gemini = org.mockito.Mockito.mock(GeminiService.class);
        GraphService graphs = org.mockito.Mockito.mock(GraphService.class);
        AppResponseService responses = org.mockito.Mockito.mock(AppResponseService.class);
        Graph graph = new Graph();
        graph.setId(14);
        graph.setGraphType(GraphType.CITATION);
        AppResponse appResponse = new AppResponse();
        appResponse.setId(10);
        appResponse.setGeneratedResponse("{\"supporting\":{},\"opposing\":{}}");
        when(graphs.getGraphById(14)).thenReturn(graph);
        when(graphs.getAppResponseByGraphId(14)).thenReturn(appResponse);
        when(gemini.callApi(anyString(), anyString())).thenReturn(
                "{\"supporting\":{},\"opposing\":{}}",
                "{\"supporting\":{\"Improves nighttime sleep\":[\"p1\"]},\"opposing\":{\"Reports daytime advantage\":[\"p2\"]}}");
        ReflectionTestUtils.setField(controller, "geminiService", gemini);
        ReflectionTestUtils.setField(controller, "graphService", graphs);
        ReflectionTestUtils.setField(controller, "appResponseService", responses);
        ReflectionTestUtils.setField(controller, "apiKey", "test-key");
        ObjectNode request = mapper.createObjectNode().put("graphId", 14).put("prompt", "Sleep is best at nighttime");
        var papers = request.putArray("papers");
        papers.addObject().put("paperId", "p1").put("title", "Nighttime sleep improves quality")
                .put("abstract", "Nighttime sleep improved quality.");
        papers.addObject().put("paperId", "p2").put("title", "Daytime sleep can be preferable")
                .put("abstract", "Under some schedules daytime sleep had better outcomes.");

        var result = controller.loadOpinionGraph(request);

        assertEquals(HttpStatus.OK, result.getStatusCode());
        assertEquals("p1", result.getBody().path("supporting").path("Improves nighttime sleep").path(0).asText());
        assertEquals("p2", result.getBody().path("opposing").path("Reports daytime advantage").path(0).asText());
        assertTrue(mapper.readTree(appResponse.getGeneratedResponse()).path("_opinionClassificationVersion").isInt());
        verify(gemini, org.mockito.Mockito.times(2)).callApi(anyString(), anyString());
        verify(responses).updateAppResponse(10, appResponse);
    }

    @Test
    void preservesBroadRelevanceSearchAndItsPaperListInBothGraphModes() throws Exception {
        for (var mode : java.util.List.of(com.project.main.enums.GraphViewType.OPINION,
                com.project.main.enums.GraphViewType.CITATION)) {
            APIController controller = org.mockito.Mockito.spy(new APIController());
            GeminiService gemini = org.mockito.Mockito.mock(GeminiService.class);
            SemanticService semantic = org.mockito.Mockito.mock(SemanticService.class);
            UserPromptService prompts = org.mockito.Mockito.mock(UserPromptService.class);
            TokenResponseService tokens = org.mockito.Mockito.mock(TokenResponseService.class);
            AppResponseService responses = org.mockito.Mockito.mock(AppResponseService.class);
            GraphService graphs = org.mockito.Mockito.mock(GraphService.class);
            ResearchPaperService papersService = org.mockito.Mockito.mock(ResearchPaperService.class);
            var prompt = new com.project.main.entity.UserPrompt();
            prompt.setSearchPrompt("sleep is bets for nighttime");
            prompt.setGraphViewType(mode);
            ObjectNode keywords = mapper.createObjectNode().put("keywords", "sleep nighttime");
            ReflectionTestUtils.setField(controller, "geminiService", gemini);
            ReflectionTestUtils.setField(controller, "semanticService", semantic);
            ReflectionTestUtils.setField(controller, "userPromptService", prompts);
            ReflectionTestUtils.setField(controller, "tokenResponseService", tokens);
            ReflectionTestUtils.setField(controller, "appResponseService", responses);
            ReflectionTestUtils.setField(controller, "graphService", graphs);
            ReflectionTestUtils.setField(controller, "researchPaperService", papersService);
            ReflectionTestUtils.setField(controller, "authorService", org.mockito.Mockito.mock(AuthorService.class));
            ReflectionTestUtils.setField(controller, "citationEdgeService", org.mockito.Mockito.mock(CitationEdgeService.class));
            ReflectionTestUtils.setField(controller, "opinionService", org.mockito.Mockito.mock(OpinionService.class));
            ReflectionTestUtils.setField(controller, "opinionRelatedPaperService", org.mockito.Mockito.mock(OpinionRelatedPaperService.class));
            ReflectionTestUtils.setField(controller, "opinionEdgeService", org.mockito.Mockito.mock(OpinionEdgeService.class));
            ReflectionTestUtils.setField(controller, "opinionEdgeIdService", org.mockito.Mockito.mock(OpinionEdgeIdService.class));
            ReflectionTestUtils.setField(controller, "apiKey", "test-key");
            org.mockito.Mockito.doReturn(ResponseEntity.ok(keywords)).when(controller)
                    .responseFormatting(org.mockito.ArgumentMatchers.nullable(String.class));
            var papers = mapper.createArrayNode();
            for (int i = 1; i <= 12; i++) {
                papers.addObject().put("paperId", "p" + i).put("title", "Sleep study " + i).put("citationCount", 100 - i);
            }
            when(semantic.paperRelevanceSearch("sleep nighttime")).thenReturn(ResponseEntity.ok(papers));

            var result = controller.mainAPIflow(prompt, true);

            assertEquals(HttpStatus.OK, result.getStatusCode(), mode.name());
            JsonNode resultBody = mapper.readTree(result.getBody());
            assertEquals(12, resultBody.path("Papers").size(), mode.name());
            verify(gemini).callApi(org.mockito.ArgumentMatchers.contains("sleep is bets for nighttime"),
                    org.mockito.ArgumentMatchers.eq("test-key"));
            verify(semantic).paperRelevanceSearch("sleep nighttime");
            org.mockito.Mockito.verifyNoMoreInteractions(semantic);
        }
    }

    @Test
    void truncatedModelJsonReturnsBadGatewayInsteadOfPersistingMalformedData() {
        APIController controller = new APIController();
        var response = controller.categorizedResponseFormatting("{\"candidates\":[{\"content\":{\"parts\":[{\"text\":\"{\\\"supporting\\\":\\\"truncated\"}]}}]}");

        assertEquals(HttpStatus.BAD_GATEWAY, response.getStatusCode());
        assertTrue(response.getBody().path("message").asText().contains("No labels were applied"));
    }

    @Test
    void acceptsValidEmptyClassificationAsNoConfidentLabels() {
        APIController controller = new APIController();

        var response = controller.categorizedResponseFormatting("{\"supporting\":{},\"opposing\":{}}");

        assertEquals(HttpStatus.OK, response.getStatusCode());
        assertTrue(response.getBody().path("supporting").isObject());
        assertEquals(0, response.getBody().path("opposing").size());
    }

    @Test
    void emptyGeminiCandidateReturnsHelpfulBadGatewayBody() {
        APIController controller = new APIController();

        var response = controller.categorizedResponseFormatting("{\"candidates\":[{\"content\":{\"parts\":[{\"text\":\" \"}]}}]}");

        assertEquals(HttpStatus.BAD_GATEWAY, response.getStatusCode());
        assertTrue(response.getBody().path("message").asText().contains("No labels were applied"));
    }

    @Test
    void normalizesSinglePaperIdGroupValues() {
        APIController controller = new APIController();

        var response = controller.categorizedResponseFormatting("{\"supporting\":{\"group\":\"p1\"},\"opposing\":{}}");

        assertEquals(HttpStatus.OK, response.getStatusCode());
        assertEquals("p1", response.getBody().path("supporting").path("group").path(0).asText());
    }

    @Test
    void normalizesGroupedPaperObjectsFromModelOutput() {
        APIController controller = new APIController();
        String raw = "{\"supporting\":[{\"opinion\":\"Improves sleep\",\"paperIds\":[\"p1\",\"p2\"]}],\"opposing\":[{\"label\":\"No clear benefit\",\"papers\":[{\"paperId\":\"p3\"}]}]}";

        var response = controller.categorizedResponseFormatting(raw);

        assertEquals(HttpStatus.OK, response.getStatusCode());
        assertEquals("p1", response.getBody().path("supporting").path("Improves sleep").path(0).asText());
        assertEquals("p2", response.getBody().path("supporting").path("Improves sleep").path(1).asText());
        assertEquals("p3", response.getBody().path("opposing").path("No clear benefit").path(0).asText());
    }
}
