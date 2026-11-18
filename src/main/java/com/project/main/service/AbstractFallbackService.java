package com.project.main.service;

import java.io.ByteArrayInputStream;
import java.net.URI;
import java.time.Duration;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.TreeMap;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import javax.xml.XMLConstants;
import javax.xml.parsers.DocumentBuilderFactory;

import org.apache.pdfbox.Loader;
import org.apache.pdfbox.pdmodel.PDDocument;
import org.apache.pdfbox.text.PDFTextStripper;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.HttpEntity;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpMethod;
import org.springframework.http.ResponseEntity;
import org.springframework.stereotype.Service;
import org.springframework.web.client.RestTemplate;
import org.springframework.web.util.UriComponents;
import org.springframework.web.util.UriComponentsBuilder;
import org.w3c.dom.Document;
import org.w3c.dom.Element;
import org.w3c.dom.NodeList;

import tools.jackson.core.type.TypeReference;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;

/** Fills Semantic Scholar omissions from openly accessible full text and metadata APIs. */
@Service
public class AbstractFallbackService {
    private static final Logger log = LoggerFactory.getLogger(AbstractFallbackService.class);
    private static final String ATOM_NAMESPACE = "http://www.w3.org/2005/Atom";
    private static final long MAX_PDF_BYTES = 15_000_000L;
    private static final Pattern ABSTRACT_HEADING = Pattern.compile("(?i)\\babstract\\b\\s*[:.\\-]?\\s*");
    private static final Pattern NEXT_SECTION = Pattern.compile(
            "(?im)^\\s*(?:keywords?|index terms|\\d+(?:\\.\\d+)*\\s+introduction|introduction|background)\\b");

    private final RestTemplate restTemplate;
    private final RedisCacheService cache;
    private final ApiRateLimitService rateLimiter;
    private final ObjectMapper objectMapper = new ObjectMapper();

    @Value("${app.rate-limit.abstract-source-interval-ms:1000}")
    private long minimumRequestIntervalMillis;

    @Value("${app.cache.abstract-source-ttl:30d}")
    private Duration cacheTtl;

    public AbstractFallbackService(RestTemplate restTemplate, RedisCacheService cache, ApiRateLimitService rateLimiter) {
        this.restTemplate = restTemplate;
        this.cache = cache;
        this.rateLimiter = rateLimiter;
    }

    public void enrich(List<Map<String, Object>> papers) {
        List<Map<String, Object>> missing = new ArrayList<>();
        for (Map<String, Object> paper : papers) {
            if (hasText(paper.get("abstract"))) {
                paper.putIfAbsent("abstractSource", "Semantic Scholar");
            } else if (!hasTldr(paper)) {
                missing.add(paper);
            }
        }

        // The S2 TLDR is useful when no abstract exists; it is kept as a distinct field
        // and is not mislabeled as an abstract or fetched from full text.
        List<Map<String, Object>> needsOpenAlex = new ArrayList<>();
        for (Map<String, Object> paper : missing) {
            String pdfAbstract = extractOpenAccessPdfAbstract(paper);
            if (hasText(pdfAbstract)) {
                paper.put("abstract", pdfAbstract);
                paper.put("abstractSource", "Open-access PDF");
            } else {
                needsOpenAlex.add(paper);
            }
        }

        Map<String, String> openAlexAbstracts = fetchOpenAlexAbstracts(needsOpenAlex);
        List<Map<String, Object>> needsArxiv = new ArrayList<>();
        for (Map<String, Object> paper : needsOpenAlex) {
            String doi = externalId(paper, "doi");
            String abstractText = doi == null ? null : openAlexAbstracts.get(normalizeDoi(doi));
            if (hasText(abstractText)) {
                paper.put("abstract", abstractText);
                paper.put("abstractSource", "OpenAlex");
            } else if (externalId(paper, "arxiv") != null) {
                needsArxiv.add(paper);
            }
        }

        Map<String, String> arxivAbstracts = fetchArxivAbstracts(needsArxiv);
        for (Map<String, Object> paper : needsArxiv) {
            String arxivId = externalId(paper, "arxiv");
            String abstractText = arxivId == null ? null : arxivAbstracts.get(normalizeArxivId(arxivId));
            if (hasText(abstractText)) {
                paper.put("abstract", abstractText);
                paper.put("abstractSource", "arXiv");
            }
        }
    }

    private String extractOpenAccessPdfAbstract(Map<String, Object> paper) {
        Object pdfValue = paper.get("openAccessPdf");
        if (!(pdfValue instanceof Map<?, ?> pdf)) return null;
        Object rawUrl = pdf.get("url");
        if (rawUrl == null || rawUrl.toString().isBlank()) return null;

        URI uri;
        try {
            uri = URI.create(rawUrl.toString());
            if (!"https".equalsIgnoreCase(uri.getScheme()) || uri.getHost() == null) return null;
        } catch (IllegalArgumentException exception) {
            return null;
        }

        String cacheKey = "papermind:abstract:pdf:" + RedisCacheService.digest(uri.toString());
        String cachedAbstract = cache.get(cacheKey);
        if (cachedAbstract != null) return cachedAbstract.isBlank() ? null : cachedAbstract;

        try {
            rateLimiter.acquire("open-access-pdf", minimumRequestIntervalMillis);
            HttpHeaders headers = new HttpHeaders();
            headers.set(HttpHeaders.ACCEPT, "application/pdf,application/octet-stream;q=0.9,*/*;q=0.1");
            ResponseEntity<byte[]> response = restTemplate.exchange(uri, HttpMethod.GET, new HttpEntity<>(headers), byte[].class);
            byte[] bytes = response.getBody();
            if (!response.getStatusCode().is2xxSuccessful() || bytes == null || bytes.length == 0 || bytes.length > MAX_PDF_BYTES) {
                return null;
            }
            String extracted = extractAbstractSection(bytes);
            if (hasText(extracted)) cache.put(cacheKey, extracted, cacheTtl);
            return extracted;
        } catch (Exception exception) {
            log.debug("Could not extract an abstract from open-access PDF {}: {}", uri, exception.getMessage());
            return null;
        }
    }

    private String extractAbstractSection(byte[] pdfBytes) {
        try (PDDocument document = Loader.loadPDF(pdfBytes)) {
            if (document.getNumberOfPages() == 0) return null;
            PDFTextStripper stripper = new PDFTextStripper();
            stripper.setStartPage(1);
            stripper.setEndPage(Math.min(3, document.getNumberOfPages()));
            String text = stripper.getText(document);
            Matcher abstractHeading = ABSTRACT_HEADING.matcher(text);
            if (!abstractHeading.find()) return null;

            int start = abstractHeading.end();
            String afterHeading = text.substring(start);
            Matcher nextSection = NEXT_SECTION.matcher(afterHeading);
            int end = nextSection.find() ? start + nextSection.start() : Math.min(text.length(), start + 3500);
            String abstractText = text.substring(start, end).replaceAll("\\s+", " ").trim();
            abstractText = abstractText.replaceAll("^[\\p{Punct}\\s]+", "");
            if (abstractText.length() < 80) return null;
            return abstractText.length() > 5000 ? abstractText.substring(0, 5000).trim() : abstractText;
        } catch (Exception exception) {
            log.debug("Could not parse the open-access PDF: {}", exception.getMessage());
            return null;
        }
    }

    private Map<String, String> fetchOpenAlexAbstracts(List<Map<String, Object>> papers) {
        List<String> dois = papers.stream().map(paper -> externalId(paper, "doi"))
                .filter(AbstractFallbackService::hasText).map(AbstractFallbackService::normalizeDoi).distinct().toList();
        if (dois.isEmpty()) return Map.of();

        UriComponents uri = UriComponentsBuilder.fromUriString("https://api.openalex.org/works")
                .queryParam("filter", "doi:" + String.join("|", dois))
                .queryParam("select", "doi,abstract_inverted_index")
                .queryParam("per-page", Math.min(100, dois.size()))
                .build().encode();
        String body = getCachedExternalResponse("openalex", uri.toUri());
        if (!hasText(body)) return Map.of();

        try {
            JsonNode results = objectMapper.readTree(body).path("results");
            Map<String, String> abstracts = new HashMap<>();
            if (results.isArray()) {
                for (JsonNode result : results) {
                    String doi = result.path("doi").asText("");
                    String abstractText = reconstructOpenAlexAbstract(result.path("abstract_inverted_index"));
                    if (hasText(doi) && hasText(abstractText)) abstracts.put(normalizeDoi(doi), abstractText);
                }
            }
            return abstracts;
        } catch (Exception exception) {
            log.warn("Could not parse OpenAlex abstract metadata: {}", exception.getMessage());
            return Map.of();
        }
    }

    private String reconstructOpenAlexAbstract(JsonNode invertedIndex) {
        if (!invertedIndex.isObject() || invertedIndex.isEmpty()) return null;
        TreeMap<Integer, String> wordsByPosition = new TreeMap<>();
        invertedIndex.properties().forEach(entry -> {
            JsonNode positions = entry.getValue();
            if (!positions.isArray()) return;
            for (JsonNode position : positions) {
                if (position.canConvertToInt()) wordsByPosition.put(position.asInt(), entry.getKey());
            }
        });
        return wordsByPosition.values().isEmpty() ? null : String.join(" ", wordsByPosition.values());
    }

    private Map<String, String> fetchArxivAbstracts(List<Map<String, Object>> papers) {
        Map<String, String> requestedIds = new LinkedHashMap<>();
        for (Map<String, Object> paper : papers) {
            String id = externalId(paper, "arxiv");
            if (hasText(id)) requestedIds.putIfAbsent(normalizeArxivId(id), id);
        }
        if (requestedIds.isEmpty()) return Map.of();

        UriComponents uri = UriComponentsBuilder.fromUriString("https://export.arxiv.org/api/query")
                .queryParam("id_list", String.join(",", requestedIds.values()))
                .queryParam("max_results", requestedIds.size())
                .build().encode();
        String body = getCachedExternalResponse("arxiv", uri.toUri());
        if (!hasText(body)) return Map.of();

        try {
            DocumentBuilderFactory factory = DocumentBuilderFactory.newInstance();
            factory.setNamespaceAware(true);
            factory.setFeature(XMLConstants.FEATURE_SECURE_PROCESSING, true);
            factory.setFeature("http://apache.org/xml/features/disallow-doctype-decl", true);
            factory.setAttribute(XMLConstants.ACCESS_EXTERNAL_DTD, "");
            factory.setAttribute(XMLConstants.ACCESS_EXTERNAL_SCHEMA, "");
            Document document = factory.newDocumentBuilder().parse(new ByteArrayInputStream(body.getBytes(java.nio.charset.StandardCharsets.UTF_8)));
            NodeList entries = document.getElementsByTagNameNS(ATOM_NAMESPACE, "entry");
            Map<String, String> abstracts = new HashMap<>();
            for (int index = 0; index < entries.getLength(); index++) {
                Element entry = (Element) entries.item(index);
                String id = childText(entry, "id");
                String summary = childText(entry, "summary");
                String arxivId = normalizeArxivId(id);
                if (hasText(arxivId) && hasText(summary)) abstracts.put(arxivId, summary.replaceAll("\\s+", " ").trim());
            }
            return abstracts;
        } catch (Exception exception) {
            log.warn("Could not parse arXiv abstract metadata: {}", exception.getMessage());
            return Map.of();
        }
    }

    private String childText(Element parent, String localName) {
        NodeList nodes = parent.getElementsByTagNameNS(ATOM_NAMESPACE, localName);
        return nodes.getLength() == 0 ? null : nodes.item(0).getTextContent();
    }

    private String getCachedExternalResponse(String provider, URI uri) {
        String key = "papermind:abstract:metadata:" + provider + ":" + RedisCacheService.digest(uri.toString());
        String cached = cache.get(key);
        if (cached != null) return cached;
        try {
            rateLimiter.acquire("abstract-" + provider, minimumRequestIntervalMillis);
            HttpHeaders headers = new HttpHeaders();
            headers.set(HttpHeaders.ACCEPT, "application/json,application/atom+xml,application/xml;q=0.9,*/*;q=0.1");
            ResponseEntity<String> response = restTemplate.exchange(uri, HttpMethod.GET, new HttpEntity<>(headers), String.class);
            if (response.getStatusCode().is2xxSuccessful() && hasText(response.getBody())) {
                cache.put(key, response.getBody(), cacheTtl);
                return response.getBody();
            }
        } catch (Exception exception) {
            log.debug("{} abstract lookup failed: {}", provider, exception.getMessage());
        }
        return null;
    }

    private String externalId(Map<String, Object> paper, String name) {
        Object idsValue = paper.get("externalIds");
        if (!(idsValue instanceof Map<?, ?> ids)) return null;
        for (Map.Entry<?, ?> entry : ids.entrySet()) {
            if (entry.getKey() != null && entry.getKey().toString().equalsIgnoreCase(name)
                    && entry.getValue() != null && !entry.getValue().toString().isBlank()) {
                return entry.getValue().toString();
            }
        }
        return null;
    }

    private static String normalizeDoi(String doi) {
        return doi.trim().toLowerCase(Locale.ROOT).replaceFirst("^https?://(dx\\.)?doi\\.org/", "").replaceFirst("^doi:", "");
    }

    private static String normalizeArxivId(String id) {
        if (id == null) return null;
        return id.trim().toLowerCase(Locale.ROOT)
                .replaceFirst("^https?://(export\\.)?arxiv\\.org/(abs|pdf)/", "")
                .replaceFirst("\\.pdf$", "")
                .replaceFirst("v\\d+$", "");
    }

    private static boolean hasTldr(Map<String, Object> paper) {
        Object value = paper.get("tldr");
        return value instanceof Map<?, ?> tldr && hasText(tldr.get("text"));
    }

    private static boolean hasText(Object value) {
        return value != null && !value.toString().isBlank();
    }
}
