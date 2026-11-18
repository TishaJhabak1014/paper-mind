package com.project.main.controller;

import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.server.ResponseStatusException;
import java.time.Duration;

import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;
import com.project.main.service.GeminiService;
import com.project.main.service.RedisCacheService;

@RestController
@RequestMapping("/api/generate-summary")
public class AISummaryController {

	@Autowired
	GeminiService geminiService;

	@Autowired
	RedisCacheService cacheService;

	@Value("${google.ai.api-key:}")
    private String apiKey;

	@Value("${google.ai.model:gemini-3.8-flash}")
	private String model;

	@Value("${app.summary.prompt-version:1}")
	private String promptVersion;

	@Value("${app.cache.summary-ttl:30d}")
	private Duration summaryCacheTtl;

	@PostMapping
	public ResponseEntity<String> generateAISummary(@RequestBody JsonNode requestBody) {
		String content = requestBody.path("abstract").asText("").trim();
		if (content.isBlank()) {
			return ResponseEntity.badRequest().body("An abstract is required to generate a summary.");
		}
		String paperId = requestBody.path("paperId").asText("").trim();
		String cacheKey = paperId.isBlank() ? null : "papermind:summary:"
				+ RedisCacheService.digest(paperId) + ":" + RedisCacheService.digest(model)
				+ ":v" + RedisCacheService.digest(promptVersion) + ":" + RedisCacheService.digest(content);
		if (cacheKey != null) {
			String cachedSummary = cacheService.get(cacheKey);
			if (cachedSummary != null && !cachedSummary.isBlank()) return ResponseEntity.ok(cachedSummary);
		}

		// The request contains only the abstract; don't ask the model to summarize full text.
        ObjectMapper objectMapper = new ObjectMapper();

		String aiSummary = String.format(
				"Summarize this abstract of an academic paper for a general audience: '%s'. Avoid technical jargon, or explain technical terms simply. Return one cohesive paragraph without a list. Base the summary only on the supplied abstract; do not imply that you have read the full paper. If you cannot summarize it, return an empty string.",
				content);

		try {
			String responseBody = geminiService.callApi(aiSummary, apiKey);
			JsonNode responseJson = objectMapper.readTree(responseBody);
			String response = responseJson.path("candidates").path(0)
					.path("content").path("parts").path(0)
					.path("text").asText("")
					.replaceAll("\\*|\\n", " ").trim();

			if (response.isBlank()) {
				return ResponseEntity.status(HttpStatus.BAD_GATEWAY)
						.body("The AI provider returned no summary. Please retry.");
			}
			if (cacheKey != null) cacheService.put(cacheKey, response, summaryCacheTtl);
			return ResponseEntity.ok(response);
		} catch (ResponseStatusException exception) {
			return ResponseEntity.status(exception.getStatusCode())
					.body(exception.getReason() == null ? "The AI provider is temporarily unavailable." : exception.getReason());
		} catch (Exception exception) {
			return ResponseEntity.status(HttpStatus.BAD_GATEWAY)
					.body("The AI provider could not generate a summary. Please retry.");
		}
	}
}

