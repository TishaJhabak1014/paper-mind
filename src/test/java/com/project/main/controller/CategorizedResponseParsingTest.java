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
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpStatus;
import org.springframework.test.util.ReflectionTestUtils;

import tools.jackson.databind.ObjectMapper;
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
    void truncatedModelJsonReturnsBadGatewayInsteadOfPersistingMalformedData() {
        APIController controller = new APIController();
        var response = controller.categorizedResponseFormatting("{\"candidates\":[{\"content\":{\"parts\":[{\"text\":\"{\\\"supporting\\\":\\\"truncated\"}]}}]}");

        assertEquals(HttpStatus.BAD_GATEWAY, response.getStatusCode());
    }
}
