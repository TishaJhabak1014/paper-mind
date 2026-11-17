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

import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;
import com.project.main.service.GeminiService;

@RestController
@RequestMapping("/api/generate-summary")
public class AISummaryController {

	@Autowired
	GeminiService geminiService;

	@Value("${google.ai.api-key:}")
    private String apiKey;

	@PostMapping
	public ResponseEntity<String> generateAISummary(@RequestBody JsonNode requestBody) {
		String content = requestBody.path("abstract").asText("").trim();
		if (content.isBlank()) {
			return ResponseEntity.badRequest().body("An abstract is required to generate a summary.");
		}

		// gemini (1): AI summary of abstract
        ObjectMapper objectMapper = new ObjectMapper();

		String aiSummary = String.format(
				"Paraphraze this paragraph that is abstract of an academic paper: '%s', aiming for general audience, do not use technical jargon or give simple explanation when technical terms are used. The output should be one cohesive paragraph so no list or dot points. If you receive empty abstract or cannot summarize for any reason, return empty string.",
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

