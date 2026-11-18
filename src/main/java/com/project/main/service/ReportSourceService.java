package com.project.main.service;

import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

import com.project.main.entity.Graph;
import com.project.main.entity.ResearchPaper;
import com.project.main.entity.TokenResponse;
import com.project.main.entity.UserPrompt;
import com.project.main.enums.GraphType;
import com.project.main.enums.GraphViewType;
import com.project.main.repository.GraphRepo;
import com.project.main.repository.ResearchPaperRepo;
import com.project.main.repository.TokenResponseRepo;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.server.ResponseStatusException;

import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;

/** Loads search results and saved group classifications from server-side records. */
@Service
public class ReportSourceService {
    private final GraphRepo graphRepo;
    private final TokenResponseRepo tokenResponseRepo;
    private final ResearchPaperRepo researchPaperRepo;
    private final ObjectMapper objectMapper = new ObjectMapper();

    public ReportSourceService(GraphRepo graphRepo, TokenResponseRepo tokenResponseRepo,
            ResearchPaperRepo researchPaperRepo) {
        this.graphRepo = graphRepo;
        this.tokenResponseRepo = tokenResponseRepo;
        this.researchPaperRepo = researchPaperRepo;
    }

    @Transactional(readOnly = true)
    public GraphSnapshot load(Integer graphId) {
        if (graphId == null) throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "A graph ID is required.");
        Graph graph = graphRepo.findWithSearchContextByGraphId(graphId)
                .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND, "The search graph was not found."));
        UserPrompt prompt = graph.getAppResponse() == null ? null : graph.getAppResponse().getUserPrompt();
        if (prompt == null || prompt.getId() == null) {
            throw new ResponseStatusException(HttpStatus.NOT_FOUND, "The search question for this graph is unavailable.");
        }
        TokenResponse tokenResponse = tokenResponseRepo.findByUserPrompt_Id(prompt.getId())
                .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND,
                        "The search-result list for this graph is unavailable."));

        List<String> paperIds = parsePaperIds(tokenResponse.getGeneratedResponse());
        if (paperIds.isEmpty()) {
            throw new ResponseStatusException(HttpStatus.UNPROCESSABLE_CONTENT,
                    "This graph has no saved search-result papers to use as report sources.");
        }
        Map<String, ResearchPaper> papersById = new LinkedHashMap<>();
        researchPaperRepo.findAllWithAuthorsBySemanticPaperIdIn(paperIds).forEach(paper ->
                papersById.putIfAbsent(paper.getSemanticPaperId(), paper));

        List<PaperSource> sources = new ArrayList<>();
        for (String paperId : paperIds) {
            ResearchPaper paper = papersById.get(paperId);
            if (paper == null) continue;
            List<String> authors = paper.getAuthors() == null ? List.of()
                    : paper.getAuthors().stream().map(author -> author.getAuthorName())
                            .filter(name -> name != null && !name.isBlank()).sorted().toList();
            LocalDateTime publishedDate = paper.getPublishedDate();
            sources.add(new PaperSource(paperId, paper.getTitle(), authors,
                    publishedDate == null ? null : publishedDate.getYear(), paper.getSourceLink(), paper.getAbstractText()));
        }
        Map<String, List<String>> classifications = parseClassifications(
                graph.getAppResponse() == null ? null : graph.getAppResponse().getGeneratedResponse());
        GraphType graphType = graph.getGraphType();
        if (graphType == null) {
            graphType = prompt.getGraphViewType() == GraphViewType.CITATION ? GraphType.CITATION
                    : prompt.getGraphViewType() == GraphViewType.OPINION ? GraphType.OPINION : null;
        }
        return new GraphSnapshot(graphId, graphType, prompt.getSearchPrompt(), sources, classifications);
    }

    private List<String> parsePaperIds(String encoded) {
        try {
            JsonNode ids = objectMapper.readTree(encoded);
            if (!ids.isArray()) return List.of();
            LinkedHashSet<String> ordered = new LinkedHashSet<>();
            for (JsonNode id : ids) {
                if (id.isTextual() && !id.asText().isBlank()) ordered.add(id.asText());
            }
            return List.copyOf(ordered);
        } catch (Exception exception) {
            throw new ResponseStatusException(HttpStatus.UNPROCESSABLE_CONTENT,
                    "The saved search-result list is malformed.", exception);
        }
    }

    private Map<String, List<String>> parseClassifications(String encoded) {
        Map<String, List<String>> byPaperId = new LinkedHashMap<>();
        if (encoded == null || encoded.isBlank()) return byPaperId;
        try {
            JsonNode root = objectMapper.readTree(encoded);
            JsonNode classified = root.path("supporting").isObject() || root.path("opposing").isObject()
                    ? root : extractModelJson(root);
            addClassification(byPaperId, classified.path("supporting"), "SUPPORTING");
            addClassification(byPaperId, classified.path("opposing"), "OPPOSING");
        } catch (Exception ignored) {
            // Classification is optional: All remains available and group scopes stay unavailable.
        }
        return byPaperId;
    }

    private JsonNode extractModelJson(JsonNode envelope) {
        String text = envelope.path("candidates").path(0).path("content").path("parts").path(0).path("text").asText("");
        if (text.isBlank()) return objectMapper.createObjectNode();
        text = text.replaceAll("(?s)^\\s*```(?:json)?\\s*", "").replaceAll("\\s*```\\s*$", "").trim();
        try {
            return objectMapper.readTree(text);
        } catch (Exception ignored) {
            return objectMapper.createObjectNode();
        }
    }

    private void addClassification(Map<String, List<String>> byPaperId, JsonNode groups, String groupName) {
        if (!groups.isObject()) return;
        for (Map.Entry<String, JsonNode> group : groups.properties()) {
            if (!group.getValue().isArray()) continue;
            for (JsonNode id : group.getValue()) {
                if (!id.isTextual() || id.asText().isBlank()) continue;
                String label = groupName + ": " + group.getKey();
                List<String> labels = byPaperId.computeIfAbsent(id.asText(), ignored -> new ArrayList<>());
                if (!labels.contains(label)) labels.add(label);
            }
        }
    }

    public record PaperSource(String paperId, String title, List<String> authors, Integer year,
            String sourceUrl, String abstractText) {}

    public record GraphSnapshot(Integer graphId, GraphType graphType, String researchQuestion,
            List<PaperSource> papers, Map<String, List<String>> classifications) {}
}
