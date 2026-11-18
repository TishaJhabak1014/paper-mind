package com.project.main.service;

import java.time.Duration;

import dev.langchain4j.model.chat.ChatModel;
import dev.langchain4j.model.googleai.GoogleAiGeminiChatModel;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.web.server.ResponseStatusException;

/** Uses LangChain4j's Gemini integration for report and abstract-summary generation. */
@Service
public class LangChain4jGeminiGateway implements AiTextGateway {
    private final ApiRateLimitService rateLimitService;

    @Value("${google.ai.api-key:}")
    private String apiKey;

    @Value("${google.ai.model:gemini-3.8-flash}")
    private String model;

    @Value("${google.ai.fallback-model:gemini-3.5-flash-lite}")
    private String fallbackModel;

    @Value("${app.rate-limit.gemini-interval-ms:4000}")
    private long minimumRequestIntervalMillis;

    public LangChain4jGeminiGateway(ApiRateLimitService rateLimitService) {
        this.rateLimitService = rateLimitService;
    }

    @Override
    public String generate(String prompt) {
        if (apiKey == null || apiKey.isBlank()) {
            throw new ResponseStatusException(HttpStatus.SERVICE_UNAVAILABLE,
                    "Set GOOGLE_AI_API_KEY before requesting an AI summary or report.");
        }
        try {
            return generateWithModel(model, prompt);
        } catch (RuntimeException primaryFailure) {
            if (fallbackModel == null || fallbackModel.isBlank() || fallbackModel.equals(model)) {
                throw new ResponseStatusException(HttpStatus.SERVICE_UNAVAILABLE,
                        "Gemini could not generate the requested text. Please retry.", primaryFailure);
            }
            try {
                return generateWithModel(fallbackModel, prompt);
            } catch (RuntimeException fallbackFailure) {
                throw new ResponseStatusException(HttpStatus.SERVICE_UNAVAILABLE,
                        "Gemini could not generate the requested text. Please retry.", fallbackFailure);
            }
        }
    }

    private String generateWithModel(String modelName, String prompt) {
        rateLimitService.acquire("gemini", minimumRequestIntervalMillis);
        ChatModel chatModel = GoogleAiGeminiChatModel.builder()
                .apiKey(apiKey)
                .modelName(modelName)
                .maxRetries(0)
                .timeout(Duration.ofSeconds(45))
                .build();
        return chatModel.chat(prompt);
    }
}
