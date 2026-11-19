package com.project.main.service;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import java.util.List;
import java.util.Map;

import com.project.main.dto.EvidenceReportResponse;
import com.project.main.dto.ReportGenerateRequest;
import com.project.main.dto.ReportPreviewRequest;
import com.project.main.dto.ReportScope;
import com.project.main.dto.ReportType;
import com.project.main.enums.GraphType;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.http.ResponseEntity;
import org.springframework.web.server.ResponseStatusException;

import tools.jackson.databind.ObjectMapper;
import tools.jackson.databind.node.ArrayNode;
import tools.jackson.databind.node.ObjectNode;

@ExtendWith(MockitoExtension.class)
class EvidenceReportServiceTest {
    @Mock
    private ReportSourceService reportSourceService;

    @Mock
    private SemanticService semanticService;

    @Mock
    private AiTextGateway aiTextGateway;

    @InjectMocks
    private EvidenceReportService evidenceReportService;

    private final ObjectMapper mapper = new ObjectMapper();

    @Test
    void previewFiltersFocalPapersUsingServerClassifications() {
        when(reportSourceService.load(8)).thenReturn(opinionSnapshot());

        var preview = evidenceReportService.preview(new ReportPreviewRequest(8,
                ReportType.EVIDENCE_SYNTHESIS, ReportScope.SUPPORTING, null));

        assertEquals(1, preview.candidates().size());
        assertEquals("p1", preview.candidates().get(0).paperId());
        assertEquals(1, preview.scopes().stream().filter(option -> option.scope() == ReportScope.SUPPORTING)
                .findFirst().orElseThrow().paperCount());
    }

    @Test
    void synthesisValidatesEveryClaimSourceAgainstIncludedAbstracts() {
        when(reportSourceService.load(8)).thenReturn(opinionSnapshot());
        when(aiTextGateway.generateJson(anyString())).thenReturn(
                "{\"synthesis\":\"The abstracts indicate a relationship. [[SOURCE:p1]]\","
                        + "\"claims\":[{\"text\":\"The first paper reports a link.\",\"sourcePaperIds\":[\"p1\"],\"caveat\":\"Abstract only.\"}],"
                        + "\"sourceCoverage\":[{\"paperId\":\"p1\",\"status\":\"USED\",\"rationale\":\"Supports the reported relationship.\"}],"
                        + "\"coverageGaps\":[],\"limitations\":[\"Abstract-only evidence.\"]}");

        EvidenceReportResponse response = evidenceReportService.generate(new ReportGenerateRequest(8,
                ReportType.EVIDENCE_SYNTHESIS, ReportScope.SUPPORTING, List.of("p1")));

        assertEquals("Abstract-based synthesis", response.evidenceBasis());
        assertEquals("The abstracts indicate a relationship. [[SOURCE:p1]]", response.synthesis());
        assertEquals(List.of("p1"), response.claims().get(0).sourcePaperIds());
        assertEquals("USED", response.sourceCoverage().get(0).status());
    }

    @Test
    void mapsCompactFallbackSourceKeysToExactPaperIdsBeforeValidatingAndReturning() {
        when(reportSourceService.load(8)).thenReturn(opinionSnapshot());
        when(aiTextGateway.generateJson(anyString())).thenReturn(
                "{\"synthesis\":[{\"text\":\"The U.S. group reports e.g. a 2.5-fold improvement.\",\"sourceKeys\":[\"S1\"]},"
                        + "{\"text\":\"The second finding is reported.\",\"sourceKeys\":[\"S2\"]}],"
                        + "\"claims\":[{\"text\":\"The first finding is reported.\",\"sourcePaperIds\":[\"p1\"]},"
                        + "{\"text\":\"The second finding is reported.\",\"sourcePaperIds\":[\"p2\"]}],"
                        + "\"sourceCoverage\":[{\"paperId\":\"p1\",\"status\":\"USED\",\"rationale\":\"The abstract supports the first finding.\"},"
                        + "{\"paperId\":\"p2\",\"status\":\"USED\",\"rationale\":\"The abstract supports the second finding.\"}],"
                        + "\"coverageGaps\":[],\"limitations\":[]}");

        EvidenceReportResponse response = evidenceReportService.generate(new ReportGenerateRequest(8,
                ReportType.EVIDENCE_SYNTHESIS, ReportScope.ALL, List.of("p1", "p2")));

        assertEquals("The U.S. group reports e.g. a 2.5-fold improvement. [[SOURCE:p1]] The second finding is reported. [[SOURCE:p2]]",
                response.synthesis());
        assertEquals(List.of("p1", "p2"), response.sourceCoverage().stream().map(item -> item.paperId()).toList());
        ArgumentCaptor<String> prompt = ArgumentCaptor.forClass(String.class);
        verify(aiTextGateway).generateJson(prompt.capture());
        assertTrue(prompt.getValue().contains("\"sourceKey\":\"S1\""));
        assertTrue(prompt.getValue().contains("\"sourceKey\":\"S2\""));
    }

    @Test
    void rejectsEvidenceReportThatDoesNotAccountForEverySelectedAbstract() {
        when(reportSourceService.load(8)).thenReturn(opinionSnapshot());
        when(aiTextGateway.generateJson(anyString())).thenReturn(
                "{\"synthesis\":\"The first paper reports a link. [[SOURCE:p1]]\","
                        + "\"claims\":[{\"text\":\"The first paper reports a link.\",\"sourcePaperIds\":[\"p1\"]}],"
                        + "\"sourceCoverage\":[],\"coverageGaps\":[],\"limitations\":[]}");

        ResponseStatusException exception = assertThrows(ResponseStatusException.class,
                () -> evidenceReportService.generate(new ReportGenerateRequest(8,
                        ReportType.EVIDENCE_SYNTHESIS, ReportScope.SUPPORTING, List.of("p1"))));

        assertEquals(502, exception.getStatusCode().value());
    }

    @Test
    void rejectsModelClaimThatCitesPaperOutsideSelectedScope() {
        when(reportSourceService.load(8)).thenReturn(opinionSnapshot());
        when(aiTextGateway.generateJson(anyString())).thenReturn(
                "{\"synthesis\":\"Claim. [[SOURCE:p1]]\",\"claims\":[{\"text\":\"Claim.\",\"sourcePaperIds\":[\"p2\"]}],\"coverageGaps\":[],\"limitations\":[]}");

        ResponseStatusException exception = assertThrows(ResponseStatusException.class,
                () -> evidenceReportService.generate(new ReportGenerateRequest(8,
                        ReportType.EVIDENCE_SYNTHESIS, ReportScope.SUPPORTING, List.of("p1"))));

        assertEquals(502, exception.getStatusCode().value());
    }

    @Test
    void rejectsSynthesisMarkerOutsideSelectedSourceManifest() {
        when(reportSourceService.load(8)).thenReturn(opinionSnapshot());
        when(aiTextGateway.generateJson(anyString())).thenReturn(
                "{\"synthesis\":\"Claim [[SOURCE:p2]]\",\"claims\":[],\"coverageGaps\":[],\"limitations\":[]}");

        ResponseStatusException exception = assertThrows(ResponseStatusException.class,
                () -> evidenceReportService.generate(new ReportGenerateRequest(8,
                        ReportType.EVIDENCE_SYNTHESIS, ReportScope.SUPPORTING, List.of("p1"))));

        assertEquals(502, exception.getStatusCode().value());
    }

    @Test
    void rejectsSynthesisSentenceWithoutItsOwnSourceMarker() {
        when(reportSourceService.load(8)).thenReturn(opinionSnapshot());
        when(aiTextGateway.generateJson(anyString())).thenReturn(
                "{\"synthesis\":\"The first paper reports a link. [[SOURCE:p1]] A second claim is unsupported.\","
                        + "\"claims\":[{\"text\":\"The first paper reports a link.\",\"sourcePaperIds\":[\"p1\"]}],"
                        + "\"sourceCoverage\":[{\"paperId\":\"p1\",\"status\":\"USED\",\"rationale\":\"Supports the link.\"}],"
                        + "\"coverageGaps\":[],\"limitations\":[]}");

        ResponseStatusException exception = assertThrows(ResponseStatusException.class,
                () -> evidenceReportService.generate(new ReportGenerateRequest(8,
                        ReportType.EVIDENCE_SYNTHESIS, ReportScope.SUPPORTING, List.of("p1"))));

        assertEquals(502, exception.getStatusCode().value());
    }

    @Test
    void acceptsAbbreviationsAndDecimalsInsideCitedSynthesisSentences() {
        when(reportSourceService.load(8)).thenReturn(opinionSnapshot());
        when(aiTextGateway.generateJson(anyString())).thenReturn(
                "{\"synthesis\":\"Prior work by Smith et al. describes U.S. results, e.g. a 2.5-fold effect, i.e. the main finding. [[SOURCE:p1]]\","
                        + "\"claims\":[{\"text\":\"The abstract reports the effect.\",\"sourcePaperIds\":[\"p1\"]}],"
                        + "\"sourceCoverage\":[{\"paperId\":\"p1\",\"status\":\"USED\",\"rationale\":\"The abstract reports that effect.\"}],"
                        + "\"coverageGaps\":[],\"limitations\":[]}");

        EvidenceReportResponse response = evidenceReportService.generate(new ReportGenerateRequest(8,
                ReportType.EVIDENCE_SYNTHESIS, ReportScope.SUPPORTING, List.of("p1")));

        assertEquals("Prior work by Smith et al. describes U.S. results, e.g. a 2.5-fold effect, i.e. the main finding. [[SOURCE:p1]]",
                response.synthesis());
    }

    @Test
    void rejectsMalformedEvidenceReportJson() {
        when(reportSourceService.load(8)).thenReturn(opinionSnapshot());
        when(aiTextGateway.generateJson(anyString())).thenReturn("{not valid JSON");

        ResponseStatusException exception = assertThrows(ResponseStatusException.class,
                () -> evidenceReportService.generate(new ReportGenerateRequest(8,
                        ReportType.EVIDENCE_SYNTHESIS, ReportScope.SUPPORTING, List.of("p1"))));

        assertEquals(502, exception.getStatusCode().value());
    }

    @Test
    void rejectsEmptyEvidenceReportResponse() {
        when(reportSourceService.load(8)).thenReturn(opinionSnapshot());
        when(aiTextGateway.generateJson(anyString())).thenReturn("  ");

        ResponseStatusException exception = assertThrows(ResponseStatusException.class,
                () -> evidenceReportService.generate(new ReportGenerateRequest(8,
                        ReportType.EVIDENCE_SYNTHESIS, ReportScope.SUPPORTING, List.of("p1"))));

        assertEquals(502, exception.getStatusCode().value());
    }

    @Test
    void rejectsNullEvidenceReportResponse() {
        when(reportSourceService.load(8)).thenReturn(opinionSnapshot());
        when(aiTextGateway.generateJson(anyString())).thenReturn(null);

        ResponseStatusException exception = assertThrows(ResponseStatusException.class,
                () -> evidenceReportService.generate(new ReportGenerateRequest(8,
                        ReportType.EVIDENCE_SYNTHESIS, ReportScope.SUPPORTING, List.of("p1"))));

        assertEquals(502, exception.getStatusCode().value());
    }

    @Test
    void mapsEvidenceReportProviderFailureToBadGateway() {
        when(reportSourceService.load(8)).thenReturn(opinionSnapshot());
        when(aiTextGateway.generateJson(anyString())).thenThrow(new IllegalStateException("provider unavailable"));

        ResponseStatusException exception = assertThrows(ResponseStatusException.class,
                () -> evidenceReportService.generate(new ReportGenerateRequest(8,
                        ReportType.EVIDENCE_SYNTHESIS, ReportScope.SUPPORTING, List.of("p1"))));

        assertEquals(502, exception.getStatusCode().value());
    }

    @Test
    void refusesSynthesisWhenSelectedPapersHaveNoAbstracts() {
        var snapshot = new ReportSourceService.GraphSnapshot(8, GraphType.CITATION, "Question",
                List.of(paper("p3", "", List.of())), Map.of());
        when(reportSourceService.load(8)).thenReturn(snapshot);

        ResponseStatusException exception = assertThrows(ResponseStatusException.class,
                () -> evidenceReportService.generate(new ReportGenerateRequest(8,
                        ReportType.EVIDENCE_SYNTHESIS, ReportScope.ALL, List.of("p3"))));

        assertEquals(422, exception.getStatusCode().value());
        verify(aiTextGateway, never()).generateJson(anyString());
    }

    @Test
    void citationReportUsesOnlyUsableCitationPassages() {
        when(reportSourceService.load(9)).thenReturn(new ReportSourceService.GraphSnapshot(9, GraphType.CITATION,
                "Question", List.of(paper("focal", "not used as citation evidence", List.of())), Map.of()));
        ObjectNode citation = mapper.createObjectNode();
        citation.putArray("contexts").add("We extend the focal method with a broader evaluation.");
        citation.putArray("intents").add("extends");
        citation.put("isInfluential", true);
        ObjectNode citingPaper = citation.putObject("citingPaper").put("paperId", "citing").put("title", "Citing Paper");
        citingPaper.put("url", "https://example.org/citing");
        citingPaper.put("publicationDate", "2024-05-20");
        citingPaper.putArray("authors").addObject().put("name", "A. Citation");
        ArrayNode citations = mapper.createArrayNode().add(citation);
        when(semanticService.citationSearch("focal")).thenReturn(ResponseEntity.ok(citations));
        when(aiTextGateway.generateJson(anyString())).thenReturn(
                "{\"synthesis\":\"One sampled passage extends the focal work. [[CONTEXT:focal:citing:0]]\","
                        + "\"analyses\":[{\"contextId\":\"focal:citing:0\",\"relation\":\"EXTENDS\",\"explanation\":\"It says the focal study is extended.\"}],"
                        + "\"coverageGaps\":[],\"limitations\":[]}");

        EvidenceReportResponse response = evidenceReportService.generate(new ReportGenerateRequest(9,
                ReportType.CITATION_CONTEXT, ReportScope.ALL, List.of("focal")));

        assertEquals("Semantic Scholar citation passages", response.evidenceBasis());
        assertEquals("One sampled passage extends the work. [[CONTEXT:focal:citing:0]]", response.synthesis());
        assertEquals("EXTENDS", response.citationAnalyses().get(0).relation());
        assertEquals("It says the study is extended.", response.citationAnalyses().get(0).explanation());
        assertEquals("We extend the focal method with a broader evaluation.",
                response.sources().get(0).citationPassages().get(0).passage());
        assertEquals(List.of("A. Citation"), response.sources().get(0).citationPassages().get(0).citingPaperAuthors());
        assertEquals(Integer.valueOf(2024), response.sources().get(0).citationPassages().get(0).citingPaperYear());
    }

    @Test
    void citationReportMapsFallbackContextKeysToExactContextIds() {
        when(reportSourceService.load(9)).thenReturn(new ReportSourceService.GraphSnapshot(9, GraphType.CITATION,
                "Question", List.of(paper("focal", "", List.of())), Map.of()));
        ObjectNode citation = mapper.createObjectNode();
        citation.putArray("contexts").add("We extend the focal method with a broader evaluation.");
        citation.putObject("citingPaper").put("paperId", "citing");
        when(semanticService.citationSearch("focal")).thenReturn(ResponseEntity.ok(mapper.createArrayNode().add(citation)));
        when(aiTextGateway.generateJson(anyString())).thenReturn(
                "{\"synthesis\":[{\"text\":\"The passage describes an extension.\",\"contextKeys\":[\"C1\"]}],"
                        + "\"analyses\":[{\"contextId\":\"focal:citing:0\",\"relation\":\"EXTENDS\",\"explanation\":\"It describes an extension.\"}],"
                        + "\"coverageGaps\":[],\"limitations\":[]}");

        EvidenceReportResponse response = evidenceReportService.generate(new ReportGenerateRequest(9,
                ReportType.CITATION_CONTEXT, ReportScope.ALL, List.of("focal")));

        assertEquals("The passage describes an extension. [[CONTEXT:focal:citing:0]]", response.synthesis());
        assertEquals("EXTENDS", response.citationAnalyses().get(0).relation());
        ArgumentCaptor<String> prompt = ArgumentCaptor.forClass(String.class);
        verify(aiTextGateway).generateJson(prompt.capture());
        assertTrue(prompt.getValue().contains("\"contextKey\":\"C1\""));
        assertTrue(prompt.getValue().contains("compact contextKey values"));
    }

    @Test
    void citationSynthesisUsesReaderFriendlyPaperNamesAndPrioritizesSubstantiveRelations() {
        var sourcePaper = new ReportSourceService.PaperSource("focal", "QualiSpeech",
                List.of("Wang", "A. Zhang"), 2025, "https://example.org/qualispeech", "");
        when(reportSourceService.load(9)).thenReturn(new ReportSourceService.GraphSnapshot(9, GraphType.CITATION,
                "Question", List.of(sourcePaper), Map.of()));
        ObjectNode citation = mapper.createObjectNode();
        citation.putArray("contexts").add("The citing study extends the named method.")
                .add("The paper is mentioned, but the passage does not state a relation.");
        citation.putObject("citingPaper").put("paperId", "citing").put("title", "Later Study");
        when(semanticService.citationSearch("focal")).thenReturn(ResponseEntity.ok(mapper.createArrayNode().add(citation)));
        when(aiTextGateway.generateJson(anyString())).thenReturn(
                "{\"synthesis\":["
                        + "{\"text\":\"QualiSpeech (Wang et al., 2025) extends the focal paper.\",\"contextKeys\":[\"C1\"]},"
                        + "{\"text\":\"The focal dataset is mentioned without a clear relationship.\",\"contextKeys\":[\"C2\"]}],"
                        + "\"analyses\":["
                        + "{\"contextId\":\"focal:citing:0\",\"relation\":\"EXTENDS\",\"explanation\":\"This focal study extends QualiSpeech.\"},"
                        + "{\"contextId\":\"focal:citing:1\",\"relation\":\"UNCLEAR\",\"explanation\":\"The focal work's role is not clear.\"}],"
                        + "\"coverageGaps\":[],\"limitations\":[]}");

        EvidenceReportResponse response = evidenceReportService.generate(new ReportGenerateRequest(9,
                ReportType.CITATION_CONTEXT, ReportScope.ALL, List.of("focal")));

        assertEquals("QualiSpeech (Wang et al., 2025) extends the paper. [[CONTEXT:focal:citing:0]]",
                response.synthesis());
        assertEquals("This study extends QualiSpeech.", response.citationAnalyses().get(0).explanation());
        assertEquals("The work's role is not clear.", response.citationAnalyses().get(1).explanation());
        assertNoInternalFocalJargon(response.synthesis());
        response.citationAnalyses().forEach(analysis -> assertNoInternalFocalJargon(analysis.explanation()));
        assertFalseContains(response.coverageDescription(), "focal");

        ArgumentCaptor<String> prompt = ArgumentCaptor.forClass(String.class);
        verify(aiTextGateway).generateJson(prompt.capture());
        String value = prompt.getValue();
        assertTrue(value.contains("\"paperTitle\":\"QualiSpeech\""));
        assertTrue(value.contains("\"paperAuthors\":[\"Wang\",\"A. Zhang\"]"));
        assertTrue(value.contains("\"paperYear\":2025"));
        assertTrue(value.contains("short title or an author-year form"));
        assertTrue(value.contains("Exclude UNCLEAR passages from synthesis whenever any substantive relation is present"));
        assertTrue(value.contains("exact compact contextKey values"));
    }

    @Test
    void citationSynthesisIncludesUnclearPassagesWhenAllRelationsAreUnclear() {
        when(reportSourceService.load(9)).thenReturn(new ReportSourceService.GraphSnapshot(9, GraphType.CITATION,
                "Question", List.of(paper("focal", "", List.of())), Map.of()));
        ObjectNode citation = mapper.createObjectNode();
        citation.putArray("contexts").add("The passage does not clearly describe a relationship.");
        citation.putObject("citingPaper").put("paperId", "citing");
        when(semanticService.citationSearch("focal")).thenReturn(ResponseEntity.ok(mapper.createArrayNode().add(citation)));
        when(aiTextGateway.generateJson(anyString())).thenReturn(
                "{\"synthesis\":[{\"text\":\"The passage mentions the paper but does not establish a relation.\",\"contextKeys\":[\"C1\"]}],"
                        + "\"analyses\":[{\"contextId\":\"focal:citing:0\",\"relation\":\"UNCLEAR\",\"explanation\":\"The relation is not clear from the passage.\"}],"
                        + "\"coverageGaps\":[],\"limitations\":[]}");

        EvidenceReportResponse response = evidenceReportService.generate(new ReportGenerateRequest(9,
                ReportType.CITATION_CONTEXT, ReportScope.ALL, List.of("focal")));

        assertEquals("The passage mentions the paper but does not establish a relation. [[CONTEXT:focal:citing:0]]",
                response.synthesis());
    }

    @Test
    void citationReportSplitsLargePassageSetsIntoSmallValidatedRequests() throws Exception {
        when(reportSourceService.load(9)).thenReturn(new ReportSourceService.GraphSnapshot(9, GraphType.CITATION,
                "Question", List.of(paper("focal", "", List.of())), Map.of()));
        ObjectNode citation = mapper.createObjectNode();
        ArrayNode contexts = citation.putArray("contexts");
        for (int i = 0; i < 6; i++) contexts.add("Passage " + (i + 1) + " provides citation evidence.");
        citation.putObject("citingPaper").put("paperId", "citing");
        when(semanticService.citationSearch("focal")).thenReturn(ResponseEntity.ok(mapper.createArrayNode().add(citation)));

        List<String> firstBatchIds = java.util.stream.IntStream.range(0, 5)
                .mapToObj(i -> "focal:citing:" + i).toList();
        List<String> secondBatchIds = List.of("focal:citing:5");
        when(aiTextGateway.generateJson(anyString())).thenReturn(
                citationBatchResponse(firstBatchIds, "SUPPORTS", "The passages support the named method."),
                citationBatchResponse(secondBatchIds, "UNCLEAR", "The final passage mentions the paper."));

        EvidenceReportResponse response = evidenceReportService.generate(new ReportGenerateRequest(9,
                ReportType.CITATION_CONTEXT, ReportScope.ALL, List.of("focal")));

        assertEquals(6, response.citationAnalyses().size());
        assertEquals(6, response.sources().get(0).citationPassages().size());
        assertEquals(1, response.synthesis().split("\\[\\[CONTEXT:").length - 1,
                "UNCLEAR-only content should be omitted when substantive relations are available.");
        assertTrue(response.synthesis().contains("[[CONTEXT:focal:citing:0]]"));
        assertFalseContains(response.synthesis(), "focal:citing:5");

        ArgumentCaptor<String> prompt = ArgumentCaptor.forClass(String.class);
        verify(aiTextGateway, org.mockito.Mockito.times(2)).generateJson(prompt.capture());
        assertTrue(prompt.getAllValues().get(0).contains("\"contextKey\":\"C5\""));
        assertFalseContains(prompt.getAllValues().get(0), "focal:citing:5");
        assertTrue(prompt.getAllValues().get(1).contains("\"contextKey\":\"C1\""));
        assertTrue(prompt.getAllValues().get(1).contains("focal:citing:5"));
    }

    private String citationBatchResponse(List<String> contextIds, String relation, String sentence) throws Exception {
        ObjectNode response = mapper.createObjectNode();
        ArrayNode synthesis = response.putArray("synthesis");
        ObjectNode sentenceNode = synthesis.addObject();
        sentenceNode.put("text", sentence);
        sentenceNode.putArray("contextKeys").add("C1");
        ArrayNode analyses = response.putArray("analyses");
        for (String contextId : contextIds) {
            analyses.addObject().put("contextId", contextId).put("relation", relation)
                    .put("explanation", "The passage describes this relation.");
        }
        response.putArray("coverageGaps");
        response.putArray("limitations");
        return mapper.writeValueAsString(response);
    }

    private void assertNoInternalFocalJargon(String value) {
        assertFalseContains(value, "focal paper");
        assertFalseContains(value, "focal work");
        assertFalseContains(value, "focal study");
        assertFalseContains(value, "focal dataset");
    }

    private void assertFalseContains(String value, String forbidden) {
        assertTrue(!value.toLowerCase(java.util.Locale.ROOT).contains(forbidden.toLowerCase(java.util.Locale.ROOT)),
                "Unexpected internal wording: " + forbidden);
    }

    @Test
    void rejectsCitationReportFallbackWithUnknownContextKey() {
        when(reportSourceService.load(9)).thenReturn(new ReportSourceService.GraphSnapshot(9, GraphType.CITATION,
                "Question", List.of(paper("focal", "", List.of())), Map.of()));
        ObjectNode citation = mapper.createObjectNode();
        citation.putArray("contexts").add("A real citation passage.");
        citation.putObject("citingPaper").put("paperId", "citing");
        when(semanticService.citationSearch("focal")).thenReturn(ResponseEntity.ok(mapper.createArrayNode().add(citation)));
        when(aiTextGateway.generateJson(anyString())).thenReturn(
                "{\"synthesis\":[{\"text\":\"The passage describes an extension.\",\"contextKeys\":[\"C99\"]}],"
                        + "\"analyses\":[{\"contextId\":\"focal:citing:0\",\"relation\":\"EXTENDS\",\"explanation\":\"It describes an extension.\"}],"
                        + "\"coverageGaps\":[],\"limitations\":[]}");

        ResponseStatusException exception = assertThrows(ResponseStatusException.class,
                () -> evidenceReportService.generate(new ReportGenerateRequest(9,
                        ReportType.CITATION_CONTEXT, ReportScope.ALL, List.of("focal"))));

        assertEquals(502, exception.getStatusCode().value());
    }

    @Test
    void rejectsCitationAnalysisForPassageNotReturnedByProvider() {
        when(reportSourceService.load(9)).thenReturn(new ReportSourceService.GraphSnapshot(9, GraphType.CITATION,
                "Question", List.of(paper("focal", "", List.of())), Map.of()));
        ObjectNode citation = mapper.createObjectNode();
        citation.putArray("contexts").add("A real citation passage.");
        citation.putObject("citingPaper").put("paperId", "citing");
        when(semanticService.citationSearch("focal")).thenReturn(ResponseEntity.ok(mapper.createArrayNode().add(citation)));
        when(aiTextGateway.generateJson(anyString())).thenReturn(
                "{\"synthesis\":\"Some analysis. [[CONTEXT:focal:citing:0]]\",\"analyses\":[{\"contextId\":\"made-up\",\"relation\":\"SUPPORTS\",\"explanation\":\"Unsupported.\"}],\"coverageGaps\":[],\"limitations\":[]}");

        ResponseStatusException exception = assertThrows(ResponseStatusException.class,
                () -> evidenceReportService.generate(new ReportGenerateRequest(9,
                        ReportType.CITATION_CONTEXT, ReportScope.ALL, List.of("focal"))));

        assertEquals(502, exception.getStatusCode().value());
    }

    @Test
    void rejectsCitationReportThatOmitsASampledPassage() {
        when(reportSourceService.load(9)).thenReturn(new ReportSourceService.GraphSnapshot(9, GraphType.CITATION,
                "Question", List.of(paper("focal", "", List.of())), Map.of()));
        ObjectNode citation = mapper.createObjectNode();
        citation.putArray("contexts").add("First passage supports the method.")
                .add("Second passage challenges the result.");
        citation.putObject("citingPaper").put("paperId", "citing");
        when(semanticService.citationSearch("focal")).thenReturn(ResponseEntity.ok(mapper.createArrayNode().add(citation)));
        when(aiTextGateway.generateJson(anyString())).thenReturn(
                "{\"synthesis\":\"The first passage supports the method. [[CONTEXT:focal:citing:0]]\","
                        + "\"analyses\":[{\"contextId\":\"focal:citing:0\",\"relation\":\"SUPPORTS\",\"explanation\":\"It says so.\"}],"
                        + "\"coverageGaps\":[],\"limitations\":[]}");

        ResponseStatusException exception = assertThrows(ResponseStatusException.class,
                () -> evidenceReportService.generate(new ReportGenerateRequest(9,
                        ReportType.CITATION_CONTEXT, ReportScope.ALL, List.of("focal"))));

        assertEquals(502, exception.getStatusCode().value());
    }

    private ReportSourceService.GraphSnapshot opinionSnapshot() {
        return new ReportSourceService.GraphSnapshot(8, GraphType.OPINION, "Research question",
                List.of(paper("p1", "Abstract 1", List.of("SUPPORTING: group A")),
                        paper("p2", "Abstract 2", List.of("OPPOSING: group B"))),
                Map.of("p1", List.of("SUPPORTING: group A"), "p2", List.of("OPPOSING: group B")));
    }

    private ReportSourceService.PaperSource paper(String id, String abstractText, List<String> classifications) {
        return new ReportSourceService.PaperSource(id, "Title " + id, List.of("Author"), 2020,
                "https://example.org/" + id, abstractText);
    }
}
