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
import java.util.Map;
import java.util.Set;
import java.net.URI;
import java.time.Duration;
import java.time.ZonedDateTime;
import java.time.format.DateTimeFormatter;
import java.time.format.DateTimeParseException;
import org.springframework.beans.factory.annotation.Value;

@Service
public class SemanticService {

    @Autowired
    private RestTemplate restTemplate; // for HTTP request

    @Autowired
    private RedisCacheService cacheService;

    @Autowired
    private ApiRateLimitService rateLimitService;

    @Autowired
    private AbstractFallbackService abstractFallbackService;

    private final String SEARCH_API_URL = "https://api.semanticscholar.org/graph/v1/paper/search/bulk";
    private final int PAPER_LIMIT = 20; // limit to 20 papers
    private final int CITATION_LIMIT = 5; // limit to 5 citations per paper
    @Value("${app.cache.semantic-search-ttl:6h}")
    private Duration searchCacheTtl;

    @Value("${app.cache.semantic-citation-ttl:1h}")
    private Duration citationCacheTtl;

    @Value("${app.rate-limit.semantic-scholar-interval-ms:1100}")
    private long minimumRequestIntervalMillis;

    public ResponseEntity<JsonNode> paperRelevanceSearch(String query) {
        // Semantic Scholar may omit an abstract; a blank field means unavailable from this source.
        // The bulk-search endpoint rejects `tldr`; fetch TLDR only through the paper batch endpoint.
        String fields = "title,url,authors,citationCount,abstract,externalIds,publicationTypes,publicationDate,openAccessPdf";

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
                enrichMissingAbstracts(papers, objectMapper);
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

    /** Fill abstracts omitted by bulk search using Semantic Scholar's paper batch endpoint. */
    private void enrichMissingAbstracts(List<java.util.Map<String, Object>> papers, ObjectMapper objectMapper) {
        List<String> missingAbstractIds = new ArrayList<>();
        for (java.util.Map<String, Object> paper : papers) {
            Object abstractValue = paper.get("abstract");
            Object paperId = paper.get("paperId");
            if ((abstractValue == null || abstractValue.toString().isBlank()) && paperId != null) {
                missingAbstractIds.add(paperId.toString());
            }
        }
        if (!missingAbstractIds.isEmpty()) {
            UriComponents detailsUrl = UriComponentsBuilder.fromUriString("https://api.semanticscholar.org")
                    .pathSegment("graph", "v1", "paper", "batch")
                    .queryParam("fields", "paperId,abstract,tldr,externalIds,openAccessPdf")
                    .build()
                    .encode();
            HttpHeaders headers = new HttpHeaders();
            headers.set("Accept", "application/json");
            headers.set("Content-Type", "application/json");
            HttpEntity<Map<String, Object>> entity = new HttpEntity<>(Map.of("ids", missingAbstractIds), headers);

            try {
                ResponseEntity<String> response = exchangeWithRateLimit(detailsUrl.toUri(), entity, HttpMethod.POST);
                List<java.util.Map<String, Object>> details = objectMapper.readValue(response.getBody(),
                        new TypeReference<List<java.util.Map<String, Object>>>() {});
                Map<String, JsonNode> detailsById = new java.util.HashMap<>();
                for (java.util.Map<String, Object> detail : details) {
                    if (detail == null) continue;
                    Object id = detail.get("paperId");
                    if (id != null) detailsById.put(id.toString(), objectMapper.convertValue(detail, JsonNode.class));
                }
                for (java.util.Map<String, Object> paper : papers) {
                    Object id = paper.get("paperId");
                    if (id == null) continue;
                    JsonNode detail = detailsById.get(id.toString());
                    if (detail == null) continue;

                    if (!hasText(paper.get("abstract")) && detail.path("abstract").isTextual()
                            && !detail.path("abstract").asText().isBlank()) {
                        paper.put("abstract", detail.path("abstract").asText());
                        paper.put("abstractSource", "Semantic Scholar");
                    }
                    mergeMapField(paper, "tldr", detail.path("tldr"), objectMapper);
                    mergeMapField(paper, "externalIds", detail.path("externalIds"), objectMapper);
                    mergeMapField(paper, "openAccessPdf", detail.path("openAccessPdf"), objectMapper);
                }
            } catch (Exception exception) {
                // Keep search results usable when the optional enrichment request fails.
                System.err.println("Could not enrich missing Semantic Scholar abstracts: " + exception.getMessage());
            }
        }

        for (java.util.Map<String, Object> paper : papers) {
            Object abstractValue = paper.get("abstract");
            if (abstractValue != null && !abstractValue.toString().isBlank()) {
                paper.putIfAbsent("abstractSource", "Semantic Scholar");
            }
        }
        abstractFallbackService.enrich(papers);
    }

    @SuppressWarnings("unchecked")
    private void mergeMapField(Map<String, Object> paper, String field, JsonNode detailValue, ObjectMapper objectMapper) {
        if (!detailValue.isObject()) return;
        Map<String, Object> merged = new java.util.HashMap<>();
        Object current = paper.get(field);
        if (current instanceof Map<?, ?> currentMap) currentMap.forEach((key, value) -> merged.put(key.toString(), value));
        merged.putAll(objectMapper.convertValue(detailValue, Map.class));
        paper.put(field, merged);
    }

    private boolean hasText(Object value) {
        return value != null && !value.toString().isBlank();
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
        String fields = "title,url,authors,citationCount,abstract,publicationTypes,publicationDate,openAccessPdf";

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

    private ResponseEntity<String> exchangeWithRateLimit(URI uri, HttpEntity<String> entity) {
        return exchangeWithRateLimit(uri, entity, HttpMethod.GET);
    }

    private ResponseEntity<String> exchangeWithRateLimit(URI uri, HttpEntity<?> entity, HttpMethod method) {
        String requestBody = entity.getBody() == null ? "" : entity.getBody().toString();
        String cacheKey = "papermind:semantic:" + RedisCacheService.digest(method.name() + "|" + uri + "|" + requestBody);
        String cachedBody = cacheService.get(cacheKey);
        if (cachedBody != null) return ResponseEntity.ok(cachedBody);

        Duration cacheTtl = uri.getPath().contains("/citations") ? citationCacheTtl : searchCacheTtl;
        rateLimitService.acquire("semantic-scholar", minimumRequestIntervalMillis);
        try {
            ResponseEntity<String> response = restTemplate.exchange(uri, method, entity, String.class);
            if (response.getStatusCode().is2xxSuccessful() && response.getBody() != null) {
                cacheService.put(cacheKey, response.getBody(), cacheTtl);
            }
            return response;
        } catch (HttpClientErrorException.TooManyRequests exception) {
            long cooldownMillis = retryAfterMillis(exception, 0);
            rateLimitService.applyCooldown("semantic-scholar", cooldownMillis);
            long retrySeconds = (cooldownMillis + 999) / 1_000;
            throw new ResponseStatusException(
                    HttpStatus.TOO_MANY_REQUESTS,
                    "Semantic Scholar asked us to slow down. Retry in about " + retrySeconds + " seconds.",
                    exception);
        }
    }

    private long retryAfterMillis(HttpClientErrorException exception, int attempt) {
        String retryAfter = exception.getResponseHeaders() == null
                ? null
                : exception.getResponseHeaders().getFirst("Retry-After");
        long requestedMillis = 0;
        if (retryAfter != null && !retryAfter.isBlank()) {
            try {
                requestedMillis = Math.multiplyExact(Long.parseLong(retryAfter.trim()), 1_000L);
            } catch (NumberFormatException | ArithmeticException ignored) {
                try {
                    requestedMillis = ZonedDateTime.parse(retryAfter, DateTimeFormatter.RFC_1123_DATE_TIME)
                            .toInstant().toEpochMilli() - System.currentTimeMillis();
                } catch (DateTimeParseException ignoredDate) {
                    // Use a bounded exponential delay if the provider omitted or malformed the header.
                }
            }
        }
        long fallbackMillis = 5_000L * (attempt + 1);
        return Math.max(1_000L, Math.min(300_000L, requestedMillis > 0 ? requestedMillis : fallbackMillis));
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

