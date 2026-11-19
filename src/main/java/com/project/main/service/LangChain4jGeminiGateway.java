package com.project.main.service;

import java.time.Duration;

import dev.langchain4j.model.chat.ChatModel;
import dev.langchain4j.model.googleai.GoogleAiGeminiChatModel;
import dev.langchain4j.model.chat.request.ResponseFormat;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.web.server.ResponseStatusException;

/** Uses LangChain4j's Gemini integration for report and abstract-summary generation. */
@Service
public class LangChain4jGeminiGateway implements AiTextGateway {
    private static final Logger logger = LoggerFactory.getLogger(LangChain4jGeminiGateway.class);
    private final ApiRateLimitService rateLimitService;

    @Value("${google.ai.api-key:}")
    private String apiKey;

    @Value("${google.ai.model:gemini-3.8-flash}")
    private String model;

    @Value("${google.ai.fallback-model:gemini-3.5-flash-lite}")
    private String fallbackModel;

    @Value("${app.rate-limit.gemini-interval-ms:4000}")
    private long minimumRequestIntervalMillis;

    @Value("${app.ai.timeout-seconds:90}")
    private long timeoutSeconds = 90;

    public LangChain4jGeminiGateway(ApiRateLimitService rateLimitService) {
        this.rateLimitService = rateLimitService;
    }

    @Override
    public String generate(String prompt) {
        return generate(prompt, false);
    }

    @Override
    public String generateJson(String prompt) {
        return generate(prompt, true);
    }

    private String generate(String prompt, boolean jsonResponse) {
        if (apiKey == null || apiKey.isBlank()) {
            throw new ResponseStatusException(HttpStatus.SERVICE_UNAVAILABLE,
                    "Set GOOGLE_AI_API_KEY before requesting an AI summary or report.");
        }
        try {
            return generateWithModel(model, prompt, jsonResponse);
        } catch (RuntimeException primaryFailure) {
            logProviderFailure(model, primaryFailure);
            if (fallbackModel == null || fallbackModel.isBlank() || fallbackModel.equals(model)) {
                throw new ResponseStatusException(HttpStatus.SERVICE_UNAVAILABLE,
                        "Gemini could not generate the requested text. Please retry.", primaryFailure);
            }
            try {
                String response = generateWithModel(fallbackModel, prompt, jsonResponse);
                logger.info("LangChain4j Gemini fallback model {} completed after primary model {} failed.", fallbackModel, model);
                return response;
            } catch (RuntimeException fallbackFailure) {
                logProviderFailure(fallbackModel, fallbackFailure);
                throw new ResponseStatusException(HttpStatus.SERVICE_UNAVAILABLE,
                        "Gemini could not generate the requested text. Please retry.", fallbackFailure);
            }
        }
    }

    private void logProviderFailure(String modelName, RuntimeException failure) {
        // Log provider/model diagnostics without logging prompts, abstracts, or API credentials.
        logger.warn("LangChain4j Gemini request failed for model {} ({}): {}", modelName,
                failure.getClass().getSimpleName(), safeDiagnostic(failure.getMessage()));
    }

    private String safeDiagnostic(String message) {
        if (message == null || message.isBlank()) return "No provider diagnostic message";
        String sanitized = message.replaceAll("(?i)(key|api[_-]?key|token)(\\s*[:=]\\s*)[^\\s,;]+", "$1$2[redacted]");
        return sanitized.length() > 600 ? sanitized.substring(0, 600) : sanitized;
    }

    private String generateWithModel(String modelName, String prompt, boolean jsonResponse) {
        rateLimitService.acquire("gemini", minimumRequestIntervalMillis);
        var builder = GoogleAiGeminiChatModel.builder()
                .apiKey(apiKey)
                .modelName(modelName)
                .maxRetries(0)
                .timeout(Duration.ofSeconds(Math.max(1, timeoutSeconds)));
        if (jsonResponse) builder.responseFormat(ResponseFormat.JSON).maxOutputTokens(4096);
        ChatModel chatModel = builder.build();
        return chatModel.chat(prompt);
    }
}
