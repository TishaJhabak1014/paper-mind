package com.project.main.service;

import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.HttpEntity;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpMethod;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.stereotype.Service;
import org.springframework.web.client.RestTemplate;
import org.springframework.web.client.RestClientResponseException;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.web.server.ResponseStatusException;

import tools.jackson.databind.ObjectMapper;
import tools.jackson.databind.node.ObjectNode;

@Service
public class GeminiService {

    @Autowired
    private RestTemplate restTemplate; // for HTTP request

    @Autowired
    private ApiRateLimitService rateLimitService;

    @Value("${google.ai.model:gemini-3.8-flash}")
    private String model = "gemini-3.8-flash";

    @Value("${google.ai.fallback-model:gemini-3.5-flash-lite}")
    private String fallbackModel = "gemini-3.5-flash-lite";

    @Value("${app.rate-limit.gemini-interval-ms:1000}")
    private long minimumRequestIntervalMillis;

    private static final String API_URL_TEMPLATE = "https://generativelanguage.googleapis.com/v1beta/models/%s:generateContent";

    public String callApi(String prompt, String geminiKey) {
        if (geminiKey == null || geminiKey.isBlank()) {
            throw new IllegalStateException("Set GOOGLE_AI_API_KEY before making Gemini requests");
        }
        HttpHeaders headers = new HttpHeaders();
        headers.set("Content-Type", "application/json");
        headers.set("x-goog-api-key", geminiKey);

        ObjectMapper objectMapper = new ObjectMapper();
        ObjectNode contentNode = objectMapper.createObjectNode();
        ObjectNode partsNode = objectMapper.createObjectNode();
        ObjectNode requestBodyNode = objectMapper.createObjectNode();

        // {
        //     "contents": [
        //       {
        //         "parts": [
        //           {
        //             "text": "prompt"
        //           }
        //         ]
        //       }
        //     ]
        //  }
        partsNode.put("text", prompt); // user prompt
        contentNode.set("parts", objectMapper.createArrayNode().add(partsNode));
        requestBodyNode.set("contents", objectMapper.createArrayNode().add(contentNode));

        String requestBody;

        try {
            requestBody = objectMapper.writeValueAsString(requestBodyNode);
        } 
        catch (Exception e) {
            throw new RuntimeException("Failed to construct JSON request body", e);
        }

        HttpEntity<String> request = new HttpEntity<>(requestBody, headers);

        try {
            return generateContent(model, request);
        } catch (RestClientResponseException exception) {
            int status = exception.getStatusCode().value();
            boolean transientFailure = status == 429 || status == 503;
            if (!transientFailure || fallbackModel == null || fallbackModel.isBlank() || fallbackModel.equals(model)) {
                throw exception;
            }

            try {
                return generateContent(fallbackModel, request);
            } catch (RestClientResponseException fallbackException) {
                throw new ResponseStatusException(
                        HttpStatus.SERVICE_UNAVAILABLE,
                        "Gemini is temporarily busy. Please retry in a short while.",
                        fallbackException);
            }
        }
    }

    private String generateContent(String modelName, HttpEntity<String> request) {
        rateLimitService.acquire("gemini", minimumRequestIntervalMillis);
        String apiUrl = String.format(API_URL_TEMPLATE, modelName);
        ResponseEntity<String> response = restTemplate.exchange(apiUrl, HttpMethod.POST, request, String.class);
        return response.getBody();
    }
}
