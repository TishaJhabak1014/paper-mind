package com.project.main.service;

import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.HttpEntity;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpMethod;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.client.HttpClientErrorException;
import org.springframework.stereotype.Service;
import org.springframework.web.client.RestTemplate;
import org.springframework.web.server.ResponseStatusException;
import org.springframework.web.util.UriComponents;
import org.springframework.web.util.UriComponentsBuilder;

import tools.jackson.core.type.TypeReference;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;

import java.util.List;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.LinkedHashSet;
import java.util.Set;
import java.net.URI;

@Service
public class SemanticService {

    @Autowired
    private RestTemplate restTemplate; // for HTTP request

    private final String SEARCH_API_URL = "https://api.semanticscholar.org/graph/v1/paper/search/bulk";
    private final int PAPER_LIMIT = 20; // limit to 20 papers
    private final int CITATION_LIMIT = 5; // limit to 5 citations per paper
    private static final long MIN_REQUEST_INTERVAL_MS = 1_100;
    private long lastRequestAt;

    public ResponseEntity<JsonNode> paperRelevanceSearch(String query) {
        String fields = "title,url,authors,citationCount,abstract,publicationTypes,publicationDate,openAccessPdf";

        UriComponents fullUrl = UriComponentsBuilder.fromUriString(SEARCH_API_URL)
                .queryParam("query", query)
                .queryParam("fields", fields)
                .build()
                .encode();

        System.out.println("SearchAPI:" + fullUrl);

        HttpHeaders headers = new HttpHeaders();
        headers.set("Accept", "application/json");

        HttpEntity<String> entity = new HttpEntity<>(headers);
        ResponseEntity<String> response = exchangeWithRateLimit(fullUrl.toUri(), entity);

        try {
            // parse JSON into java.util.Map<String, Object>
            ObjectMapper objectMapper = new ObjectMapper();
            java.util.Map<String, Object> responseBody = objectMapper.readValue(response.getBody(), new TypeReference<java.util.Map<String, Object>>() {});

            // extract the list of papers from the response
            List<java.util.Map<String, Object>> papers = objectMapper.convertValue(responseBody.get("data"), 
                                                    new TypeReference<List<java.util.Map<String, Object>>>() {});

            if (papers != null && !papers.isEmpty()) {
                papers = selectRelevantAndDiversePapers(papers);
                JsonNode papersNode = objectMapper.convertValue(papers, JsonNode.class);
                return ResponseEntity.ok(papersNode);

            } else {
                return ResponseEntity.ok(objectMapper.createObjectNode().put("message", "No papers found for the query."));
            }

        } catch (Exception e) {
            e.printStackTrace();
            return ResponseEntity.status(HttpStatus.INTERNAL_SERVER_ERROR).body(null);
        }
    }

    /**
     * Keep Semantic Scholar's relevance ordering while selecting at least one result
     * from each publication type available in the candidate set before filling the
     * remaining slots by relevance. This prevents a citation-count-only top 20 from
     * hiding less-cited document categories.
     */
    private List<java.util.Map<String, Object>> selectRelevantAndDiversePapers(
            List<java.util.Map<String, Object>> candidates) {
        if (candidates.size() <= PAPER_LIMIT) {
            return candidates;
        }

        Set<String> availableTypes = new LinkedHashSet<>();
        for (java.util.Map<String, Object> paper : candidates) {
            Object rawTypes = paper.get("publicationTypes");
            if (rawTypes instanceof List<?> types) {
                for (Object type : types) {
                    if (type != null) availableTypes.add(type.toString());
                }
            }
        }

        List<java.util.Map<String, Object>> selected = new ArrayList<>(PAPER_LIMIT);
        Set<String> selectedIds = new HashSet<>();
        for (String type : availableTypes) {
            for (java.util.Map<String, Object> paper : candidates) {
                Object rawTypes = paper.get("publicationTypes");
                Object rawId = paper.get("paperId");
                if (rawTypes instanceof List<?> types && types.contains(type) && rawId != null
                        && selectedIds.add(rawId.toString())) {
                    selected.add(paper);
                    break;
                }
            }
            if (selected.size() >= PAPER_LIMIT) return selected;
        }

        for (java.util.Map<String, Object> paper : candidates) {
            Object rawId = paper.get("paperId");
            if (rawId != null && selectedIds.add(rawId.toString())) {
                selected.add(paper);
                if (selected.size() >= PAPER_LIMIT) break;
            }
        }
        return selected;
    }

    public ResponseEntity<JsonNode> citationSearch(String paperID) {
        String fields = "authors,citationCount,abstract,publicationTypes,publicationDate,openAccessPdf";

        UriComponents fullUrl = UriComponentsBuilder.fromUriString("https://api.semanticscholar.org")
                .pathSegment("graph", "v1", "paper", paperID, "citations")
                .queryParam("fields", fields)
                .queryParam("offset", 0)
                .queryParam("limit", CITATION_LIMIT)
                .build()
                .encode();

        System.out.println("CitationAPI: " + fullUrl);

        HttpHeaders headers = new HttpHeaders();
        headers.set("Accept", "application/json");

        HttpEntity<String> entity = new HttpEntity<>(headers);
        ResponseEntity<String> response = exchangeWithRateLimit(fullUrl.toUri(), entity);

        try {
            ObjectMapper objectMapper = new ObjectMapper();

            java.util.Map<String, Object> responseBody = objectMapper.readValue(response.getBody(), new TypeReference<java.util.Map<String, Object>>() {});
            List<java.util.Map<String, Object>> data = objectMapper.convertValue(responseBody.get("data"), 
                                                    new TypeReference<List<java.util.Map<String, Object>>>() {});

            if (data != null && !data.isEmpty()) {
                JsonNode citationNode = objectMapper.convertValue(data, JsonNode.class);
                return ResponseEntity.ok(citationNode);

            } else {
                return ResponseEntity.ok(objectMapper.createObjectNode().put("message", "No citations found for the paper."));
            }

        } catch (Exception e) {
            e.printStackTrace();
            return ResponseEntity.status(HttpStatus.INTERNAL_SERVER_ERROR).body(null);
        }
    }

    private synchronized ResponseEntity<String> exchangeWithRateLimit(URI uri, HttpEntity<String> entity) {
        for (int attempt = 0; attempt < 3; attempt++) {
            long waitMillis = MIN_REQUEST_INTERVAL_MS - (System.currentTimeMillis() - lastRequestAt);
            if (waitMillis > 0) {
                pause(waitMillis);
            }

            lastRequestAt = System.currentTimeMillis();
            try {
                return restTemplate.exchange(uri, HttpMethod.GET, entity, String.class);
            } catch (HttpClientErrorException.TooManyRequests exception) {
                if (attempt == 2) {
                    throw new ResponseStatusException(
                            HttpStatus.TOO_MANY_REQUESTS,
                            "Semantic Scholar is rate limiting requests. Please retry in a short while.",
                            exception);
                }
                pause(2_000L * (attempt + 1));
            }
        }
        throw new ResponseStatusException(HttpStatus.SERVICE_UNAVAILABLE, "Semantic Scholar is temporarily unavailable.");
    }

    private void pause(long millis) {
        try {
            Thread.sleep(millis);
        } catch (InterruptedException exception) {
            Thread.currentThread().interrupt();
            throw new ResponseStatusException(HttpStatus.SERVICE_UNAVAILABLE, "The paper search was interrupted.", exception);
        }
    }
}

