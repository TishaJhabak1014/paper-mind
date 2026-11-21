package com.project.main.controller;

import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.server.ResponseStatusException;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import tools.jackson.core.JacksonException;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;
import tools.jackson.databind.node.ObjectNode;

import org.json.JSONArray;

import com.project.main.entity.*;
import com.project.main.service.*;
import com.project.main.enums.*;

import java.time.LocalDateTime;
import java.time.LocalDate;
import java.time.format.DateTimeFormatter;
import java.util.Set;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.ArrayList;
import java.util.Comparator;

@RestController
@RequestMapping("/api/data")
public class APIController {
	private static final Logger logger = LoggerFactory.getLogger(APIController.class);
	private static final int OPINION_CLASSIFICATION_VERSION = 2;

	@ExceptionHandler(ResponseStatusException.class)
	public ResponseEntity<String> handleProviderException(ResponseStatusException exception) {
		String message = exception.getReason() == null ? "The upstream provider is temporarily unavailable." : exception.getReason();
		return ResponseEntity.status(exception.getStatusCode()).body(message);
	}

	@Autowired
	GeminiService geminiService;

	@Autowired
	SemanticService semanticService;

	@Autowired
	UserPromptService userPromptService;

	@Autowired
	TokenResponseService tokenResponseService;

	@Autowired
	AppResponseService appResponseService;

	@Autowired
	GraphService graphService;

	@Autowired
	AuthorService authorService;

	@Autowired
	ResearchPaperService researchPaperService;

	@Autowired
	CitationEdgeService citationEdgeService;

	@Autowired
	OpinionService opinionService;

	@Autowired
	OpinionRelatedPaperService opinionRelatedPaperService;

	@Autowired
	OpinionEdgeService opinionEdgeService;

	@Autowired
	OpinionEdgeIdService opinionEdgeIdService;

	@Value("${google.ai.api-key:}")
    private String apiKey;

	public APIController() {
	}

	@PostMapping
	public ResponseEntity<String> mainAPIflow(@RequestBody UserPrompt userPrompt,
			@RequestParam(defaultValue = "false") boolean progressive) {

		ObjectMapper objectMapper = new ObjectMapper();
		
		userPromptService.saveUserPrompt(userPrompt); // saving user prompt using service

		// gemini (1): tuned user_prompt for a list of extracted keywords
		/**
		 * E.g.
		 * extract key words and determine positive or negative sentiment for statement "Channel estimation with pilots is outdated";
		 * must structure response like this: "keywords:keyword1, keyword2 etc.; sentiment:positive/negative;"
		 */
		String formattedPrompt = String.format(
				"Extract concise, broad research-search keywords from the user's question. Correct obvious spelling errors and preserve the core topic and claim direction so the results can include papers with supporting and opposing evidence. Do not add stance filters or narrow the query to one side. "
				+ "Return strict JSON with exactly these string fields: keywords and sentiment.\nQuestion: %s",
				userPrompt.getSearchPrompt());

		String responseBody = geminiService.callApi(formattedPrompt, apiKey);
		
		ObjectNode responseJSON = responseFormatting(responseBody).getBody(); // response formatting

		if (responseJSON == null)
			return ResponseEntity.status(HttpStatus.INTERNAL_SERVER_ERROR).body("No gemini response (1) received.");

		// create token response
		TokenResponse tokenResponse = new TokenResponse();
		tokenResponse.setUserPrompt(userPrompt);
		tokenResponse.setProcessedPrompt(responseJSON.toString());

		// semantic (1): use the list of extracted keywords to get a JSON list of papers
		String semanticQuery = responseJSON.path("keywords").asText("")
						.replace(",", "")
						.replaceAll(";$", "").trim();

		/**
		 * Note: Fetched Papers are limited by a certain number in descending order from most cited 
		 */
		boolean citationSearch = userPrompt.getGraphViewType() == GraphViewType.CITATION;
		// Preserve Semantic Scholar's broad relevance-ranked result set. Stance classification
		// runs later over these papers and must not narrow citation search retrieval.
		JsonNode fetchedPapers = semanticService.paperRelevanceSearch(semanticQuery).getBody();
		if (citationSearch && fetchedPapers != null && fetchedPapers.isArray()) {
			List<JsonNode> rankedPapers = new ArrayList<>();
			fetchedPapers.forEach(rankedPapers::add);
			rankedPapers.sort(Comparator.comparingInt((JsonNode paper) -> paper.path("citationCount").asInt(0)).reversed());
			var sortedPapers = objectMapper.createArrayNode();
			rankedPapers.forEach(sortedPapers::add);
			fetchedPapers = sortedPapers;
		}

		if (fetchedPapers == null)
			return ResponseEntity.status(HttpStatus.INTERNAL_SERVER_ERROR).body("No semantic response (1) received.");
		if (!fetchedPapers.isArray()) {
			logger.warn("Semantic Scholar search returned a non-array response for prompt {}", userPrompt.getId());
			return ResponseEntity.status(HttpStatus.BAD_GATEWAY).body("Semantic Scholar returned an invalid paper list.");
		}

		// Provider records can be incomplete. Exclude entries without the identifiers needed
		// by persistence and downstream graph construction instead of failing the whole search.
		var validPapers = objectMapper.createArrayNode();
		for (JsonNode paperNode : fetchedPapers) {
			if (paperNode.isObject() && !paperNode.path("title").asText("").isBlank()
					&& !paperNode.path("paperId").asText("").isBlank()) {
				validPapers.add(paperNode);
			} else {
				logger.warn("Skipping incomplete Semantic Scholar paper record for prompt {}", userPrompt.getId());
			}
		}
		fetchedPapers = validPapers;

		// save all research papers
		for (JsonNode paperNode : fetchedPapers) {
			String title = paperNode.path("title").asText();
			String paperId = paperNode.path("paperId").asText();

			LocalDate publishedDate = null; // Initialize to null
			if (paperNode.has("publicationDate") && !paperNode.get("publicationDate").isNull()) {
				String publicationDateStr = paperNode.get("publicationDate").asText();
				
				if (!publicationDateStr.isEmpty() && !publicationDateStr.equalsIgnoreCase("null")) {
					publishedDate = LocalDate.parse(publicationDateStr, DateTimeFormatter.ISO_LOCAL_DATE);
				}
			}
	
			String abstractText = paperNode.path("abstract").asText("");
			String sourceLink = paperNode.path("url").asText("");
			if (paperNode.has("openAccessPdf") && paperNode.get("openAccessPdf").has("url")) {
				sourceLink = paperNode.get("openAccessPdf").get("url").asText();
			}

			int citationsCount = paperNode.get("citationCount").asInt(0);
			int viewsCount = 0; 
			LocalDateTime publishedDateTime = null;
			if (publishedDate != null) {
				publishedDateTime = publishedDate.atStartOfDay();
			}
			if (researchPaperService.refreshExistingPaperMetadata(paperId, title, publishedDateTime, abstractText, sourceLink)) {
				continue;
			}

			ResearchPaper researchPaper = new ResearchPaper(
				paperId,
				title,
				publishedDateTime,
				abstractText,
				null,
				sourceLink,
				citationsCount,
				viewsCount
			);
			researchPaperService.saveResearchPaper(researchPaper);

			// extract authors from the 'authors' array in the paperNode
			Set<Author> authors = new HashSet<>();
			if (paperNode.has("authors")) {
				for (JsonNode authorNode : paperNode.get("authors")) {

					String authorName = authorNode.get("name").asText();
					Author author = new Author(authorName);
					
					author.getResearchPapers().add(researchPaper);
					authors.add(author);

					authorService.saveAuthor(author);
				}
			}

			// update authors to the researchPaper
			researchPaper.setAuthors(authors);
			researchPaperService.updateResearchPaper(researchPaper.getPaperId(), researchPaper);
		}

		// first get paper ids into json array
		// set generated response to token response and save
		// for TokenResponse
		JSONArray fetchedPapersIds = DataService.getPaperIDs(fetchedPapers);
		tokenResponse.setGeneratedResponse(fetchedPapersIds.toString());
		tokenResponseService.saveTokenResponse(tokenResponse);

		// create app response. will save after
		AppResponse appResponse = new AppResponse();
		appResponse.setUserPrompt(userPrompt);
		appResponseService.saveAppResponse(appResponse);

		// save graph
		Graph graph = new Graph();
		graph.setAppResponse(appResponse);
		if (userPrompt.getGraphViewType() != GraphViewType.NONE && userPrompt.getGraphViewType() == GraphViewType.CITATION) {
			graph.setGraphType(GraphType.CITATION);
		} else if (userPrompt.getGraphViewType() != GraphViewType.NONE && userPrompt.getGraphViewType() == GraphViewType.OPINION) {
			graph.setGraphType(GraphType.OPINION);
		}
		graphService.saveGraph(graph);

		if (progressive) {
			ObjectNode initialResponse = objectMapper.createObjectNode();
			initialResponse.set("Papers", fetchedPapers);
			initialResponse.set("Citations", objectMapper.createObjectNode());
			initialResponse.set("supporting", objectMapper.createObjectNode());
			initialResponse.set("opposing", objectMapper.createObjectNode());
			initialResponse.put("graphId", graph.getId());
			initialResponse.put("graphType", userPrompt.getGraphViewType().name());
			try {
				return ResponseEntity.ok(objectMapper.writeValueAsString(initialResponse));
			} catch (JacksonException exception) {
				return ResponseEntity.status(HttpStatus.INTERNAL_SERVER_ERROR).body("Could not format initial paper results.");
			}
		}

		// ------------ Citation-based ------------------
		// Fetch citation edges only for citation graphs, and only for a small top-paper sample.
		ObjectNode fetchedCitations = objectMapper.createObjectNode();
		boolean citationGraph = userPrompt.getGraphViewType() == GraphViewType.CITATION;
		if (citationGraph) {
			int sourcePaperCount = 0;
			for (JsonNode paper : fetchedPapers) {
				if (sourcePaperCount++ >= 5) {
					break;
				}
				String paperID = paper.path("paperId").asText();
				try {
					JsonNode citation = semanticService.citationSearch(paperID).getBody();
					fetchedCitations.set(paperID, citation != null && citation.isArray()
							? citation
							: objectMapper.createArrayNode());
				} catch (Exception exception) {
					fetchedCitations.set(paperID, objectMapper.createArrayNode());
				}
			}
		}

		// if citiation graph, then create citationedge
		if (citationGraph) {
			fetchedCitations.properties().forEach(citationEntry -> {
				String firstPaperId = citationEntry.getKey();
				ResearchPaper firstResearchPaper = researchPaperService.getReferenceBySemanticPaperId(firstPaperId);

				JsonNode citingPapers = fetchedCitations.get(firstPaperId);
				citingPapers.forEach(citingPaperNode -> {
					JsonNode citingPaper = citingPaperNode.get("citingPaper");
					if (citingPaper == null || !citingPaper.has("paperId")) {
						System.out.println("Skipping citingPaperNode due to missing citingPaper or paperId field.");
						return;
					}

					String secondPaperId = citingPaper.get("paperId").asText();
    				System.out.println("Retrieved paperId: " + secondPaperId);

					ResearchPaper secondResearchPaper = researchPaperService.getReferenceBySemanticPaperId(secondPaperId);

					// second paper does not exist
					// NOTE: there is less information here, they lack title, etc.
					if (secondResearchPaper == null) {
						secondResearchPaper = new ResearchPaper();
						secondResearchPaper.setSemanticPaperId(secondPaperId);
						secondResearchPaper.setAbstractText(citingPaperNode.get("citingPaper").get("abstract").asText(""));
						secondResearchPaper.setCitationsCount(citingPaperNode.get("citingPaper").get("citationCount").asInt(0));
						String sourceLink = "";
						if (citingPaperNode.has("openAccessPdf") && citingPaperNode.get("openAccessPdf").has("url")) {
							sourceLink = citingPaperNode.get("citingPaper").get("openAccessPdf").get("url").asText();
						}
						secondResearchPaper.setSourceLink(sourceLink);
						
						researchPaperService.saveResearchPaper(secondResearchPaper);
					}

					// create a new CitationEdge
					CitationEdge citationEdge = new CitationEdge(graph, firstResearchPaper, secondResearchPaper);
					citationEdgeService.saveCitationEdge(citationEdge);
				});
			});
		}

		// ------------ Opinion-based ------------------
		
		// gemini (2): tuned_prompt for top 20 papers, sort them by support/oppose, and get the summarized opinions
		String opinionPrompt = String.format(
				"Here is the user's opinionated prompt: '%s' and a JSON list of relevant research papers: '%s',"
				 + "Classify only when supplied title/abstract evidence shows a finding directly supports or challenges the query; topic relevance alone is not a stance. "
				 + "Leave papers unclassified when evidence is missing, ambiguous, mixed, or insufficient. Do not force every paper into a category or put an ID in both. Use only supplied paper IDs. "
				 + "Return strict JSON with supporting and opposing objects whose values are arrays of paper ID strings, using concise evidence-based group labels."
				 , userPrompt.getSearchPrompt(), fetchedPapers);

		String categorizedResponseBody = geminiService.callApi(opinionPrompt, apiKey);

		// save app response
		appResponse.setGeneratedResponse(categorizedResponseBody);
		appResponse.setGeneratedDateTime(LocalDateTime.now());
		appResponseService.updateAppResponse(appResponse.getId(), appResponse);

		ObjectNode categorizedResponseJSON = categorizedResponseFormatting(categorizedResponseBody).getBody(); // response formatting

		// if opinion graph, then create opinions, opinionrelatedpapers and opinionedges
		if (userPrompt.getGraphViewType() != GraphViewType.NONE && userPrompt.getGraphViewType() == GraphViewType.OPINION) {
			for (Map.Entry<String, JsonNode> categoryEntry : categorizedResponseJSON.properties()) {
				String key = categoryEntry.getKey();
				JsonNode opinionCategoryNode = categoryEntry.getValue();

				String opinionType = key.equals("supporting") ? "supporting" : "opposing";

				if (opinionCategoryNode != null && opinionCategoryNode.isObject()) {
					for (Map.Entry<String, JsonNode> opinionEntry : ((ObjectNode) opinionCategoryNode).properties()) {
						String opinionVal = opinionEntry.getKey();

						// save new opinion
						Opinion opinion = new Opinion(); 
						opinion.setOpinionVal(opinionVal);

						if (opinionType.equals("supporting")) {
							opinion.setOpinionType(OpinionType.SUPPORTING);
						} else {
							opinion.setOpinionType(OpinionType.OPPOSING);
						}


						opinionService.saveOpinion(opinion);

						// save opinionrelatedpaper object
						JsonNode paperIdsArray = opinionEntry.getValue();
						if (paperIdsArray.isArray()) {
							for (JsonNode paperIdNode : paperIdsArray) {
								String paperId = paperIdNode.asText();

								// find researchpaper related to the paperId
								ResearchPaper researchPaper = researchPaperService.getReferenceBySemanticPaperId(paperId);

								if (researchPaper == null) {
									researchPaper = new ResearchPaper();
									researchPaper.setSemanticPaperId(paperId);
									researchPaperService.saveResearchPaper(researchPaper);
								}

								// create opinionrelatedpaper object
								OpinionRelatedPapers opinionRelatedPaper = new OpinionRelatedPapers(opinion, researchPaper);
								opinionRelatedPaperService.saveOpinionRelatedPapers(opinionRelatedPaper);
							}
						}
						
						// save opinion edge
						OpinionEdgeId opinionEdgeId = new OpinionEdgeId(0, graph.getId(), opinion.getId());
						opinionEdgeId.setEdgeId(opinionEdgeIdService.generateRandomEdgeId());
						OpinionEdge opinionEdge = new OpinionEdge();
						opinionEdge.setId(opinionEdgeId);
						opinionEdge.setGraph(graph);
						opinionEdge.setOpinion(opinion);
						opinionEdgeService.saveOpinionEdge(opinionEdge);
					}
				}
			}
		}

		if (categorizedResponseJSON != null) {
			categorizedResponseJSON.putPOJO("Papers", fetchedPapers);
			categorizedResponseJSON.putPOJO("Citations", fetchedCitations);

			try {
				return ResponseEntity.ok(objectMapper.writeValueAsString(categorizedResponseJSON)); // final output: JSON list of papers + support/opposing
			} catch (JacksonException e) {
				e.printStackTrace();
				return ResponseEntity.status(HttpStatus.INTERNAL_SERVER_ERROR).body("No gemini response (2) received.");
			} 
		} 
		else {
			return ResponseEntity.status(HttpStatus.INTERNAL_SERVER_ERROR).body(null);
		}
	}

	/** Retains the direct-call signature used by existing integrations and tests. */
	public ResponseEntity<String> mainAPIflow(UserPrompt userPrompt) {
		return mainAPIflow(userPrompt, false);
	}

	@GetMapping("/citations/{graphId}/{paperId}")
	public ResponseEntity<JsonNode> loadCitationsForPaper(@PathVariable Integer graphId, @PathVariable String paperId) {
		Graph graph = graphService.getGraphById(graphId);
		if (graph == null || graph.getGraphType() != GraphType.CITATION) {
			return ResponseEntity.notFound().build();
		}

		ResearchPaper citedPaper = researchPaperService.getReferenceBySemanticPaperId(paperId);
		if (citedPaper == null) return ResponseEntity.notFound().build();

		JsonNode citations = semanticService.citationSearch(paperId).getBody();
		if (citations == null || !citations.isArray()) citations = new ObjectMapper().createArrayNode();
		for (JsonNode citationNode : citations) {
			JsonNode citingPaper = citationNode.path("citingPaper");
			String citingPaperId = citingPaper.path("paperId").asText("");
			if (citingPaperId.isBlank()) continue;

			ResearchPaper citingResearchPaper = researchPaperService.getReferenceBySemanticPaperId(citingPaperId);
			if (citingResearchPaper == null) {
				citingResearchPaper = new ResearchPaper();
				citingResearchPaper.setSemanticPaperId(citingPaperId);
				citingResearchPaper.setTitle(citingPaper.path("title").asText(null));
				citingResearchPaper.setAbstractText(citingPaper.path("abstract").asText(""));
				citingResearchPaper.setCitationsCount(citingPaper.path("citationCount").asInt(0));
				String sourceLink = citingPaper.path("openAccessPdf").path("url").asText("");
				if (sourceLink.isBlank()) sourceLink = citingPaper.path("url").asText("");
				citingResearchPaper.setSourceLink(sourceLink);
				citingResearchPaper = researchPaperService.saveResearchPaper(citingResearchPaper);
			}

			citationEdgeService.saveCitationEdge(new CitationEdge(graph, citedPaper, citingResearchPaper));
		}
		return ResponseEntity.ok(citations);
	}

	@PostMapping("/opinions")
	public ResponseEntity<ObjectNode> loadOpinionGraph(@RequestBody JsonNode requestBody) {
		int graphId = requestBody.path("graphId").asInt(-1);
		Graph graph = graphService.getGraphById(graphId);
		if (graph == null || (graph.getGraphType() != GraphType.OPINION && graph.getGraphType() != GraphType.CITATION)) {
			return ResponseEntity.notFound().build();
		}

		AppResponse appResponse = graphService.getAppResponseByGraphId(graphId);
		String categorizedResponseBody = appResponse == null ? "" : appResponse.getGeneratedResponse();
		JsonNode papers = requestBody.path("papers");
		ResponseEntity<ObjectNode> formatted = categorizedResponseFormatting(categorizedResponseBody);
		boolean storedClassification = categorizedResponseBody != null && !categorizedResponseBody.isBlank();
		boolean storedHasAssignments = isValidClassification(formatted) && hasPaperAssignments(formatted.getBody());
		boolean storedIsCurrentEmpty = isValidClassification(formatted)
				&& !storedHasAssignments && hasCurrentClassificationVersion(categorizedResponseBody);
		boolean forceEmptyReclassification = requestBody.path("force").asBoolean(false) && !storedHasAssignments;
		boolean generateClassification = forceEmptyReclassification || !storedClassification
				|| !isValidClassification(formatted)
				|| (!storedHasAssignments && !storedIsCurrentEmpty);
		boolean generatedThisRequest = false;
		if (generateClassification) {
			String prompt = requestBody.path("prompt").asText("");
			categorizedResponseBody = geminiService.callApi(buildOpinionClassificationPrompt(prompt, papers, false), apiKey);
			formatted = categorizedResponseFormatting(categorizedResponseBody);
			generatedThisRequest = true;
		}

		// Retry once if Gemini returned malformed JSON or no assignments despite usable paper metadata.
		if (!isValidClassification(formatted)
				|| (generatedThisRequest && !hasPaperAssignments(formatted.getBody()) && hasPaperEvidence(papers))) {
			String prompt = requestBody.path("prompt").asText("");
			categorizedResponseBody = geminiService.callApi(buildOpinionClassificationPrompt(prompt, papers, true), apiKey);
			formatted = categorizedResponseFormatting(categorizedResponseBody);
			generatedThisRequest = true;
		}
		ObjectNode categorized = formatted == null || !formatted.getStatusCode().is2xxSuccessful() ? null : formatted.getBody();
		if (categorized == null) {
			logger.warn("Could not parse Gemini opinion classification for graph {}. A retry may regenerate the classification.", graphId);
			ObjectMapper errorMapper = new ObjectMapper();
			ObjectNode error = errorMapper.createObjectNode();
			error.put("message", "Opinion classification returned an empty or invalid response. No labels were applied; retry classification later.");
			return ResponseEntity.status(HttpStatus.BAD_GATEWAY).body(error);
		}
		if (appResponse != null && generatedThisRequest) {
			try {
				ObjectMapper mapper = new ObjectMapper();
				ObjectNode stored = categorized.deepCopy();
				stored.put("_opinionClassificationVersion", OPINION_CLASSIFICATION_VERSION);
				appResponse.setGeneratedResponse(mapper.writeValueAsString(stored));
				appResponse.setGeneratedDateTime(LocalDateTime.now());
				appResponseService.updateAppResponse(appResponse.getId(), appResponse);
			} catch (Exception exception) {
				logger.warn("Parsed opinion classification could not be saved for graph {} ({}).", graphId,
						exception.getClass().getSimpleName());
			}
		}
		if (generatedThisRequest && graph.getGraphType() == GraphType.OPINION && hasPaperAssignments(categorized)) {
			persistOpinionGraph(graph, categorized);
		}

		ObjectMapper objectMapper = new ObjectMapper();
		ObjectNode result = objectMapper.createObjectNode();
		result.set("supporting", categorized.path("supporting").isObject()
				? categorized.path("supporting") : objectMapper.createObjectNode());
		result.set("opposing", categorized.path("opposing").isObject()
				? categorized.path("opposing") : objectMapper.createObjectNode());
		return ResponseEntity.ok(result);
	}

	private String buildOpinionClassificationPrompt(String prompt, JsonNode papers, boolean retry) {
		String instructions = "Evaluate each paper against the user's claim using its title, abstract, and TLDR text when available. "
				+ "Classify findings that support the claim under supporting. Classify contrary findings, null results, harms, lack of the claimed benefit, and important exceptions under opposing. "
				+ "Do not put every paper into supporting by default. Include each paper whose title, abstract, or TLDR gives a reasonable basis for one side; omit only papers with no useful evidence or genuinely indeterminate findings. Do not require the paper to use the words 'support' or 'oppose' explicitly. "
				+ "Use each supplied paperId exactly and at most once. Return strict JSON with supporting and opposing objects mapping short evidence-based group labels to arrays of paperId strings. Use an empty object for a side with no supported papers. ";
		if (retry) {
			instructions += "The previous attempt returned no usable assignments. Re-evaluate every paper using the available title, abstract, and TLDR; infer the direction from the reported result even when the paper does not explicitly say it supports or opposes the claim. Check the opposing evidence separately before completing the JSON. ";
		}
		return String.format("%s\nClaim: %s\nPapers JSON: %s", instructions, prompt, papers);
	}

	private boolean isValidClassification(ResponseEntity<ObjectNode> formatted) {
		return formatted != null && formatted.getStatusCode().is2xxSuccessful() && formatted.getBody() != null;
	}

	private boolean hasPaperAssignments(ObjectNode classification) {
		if (classification == null) return false;
		for (String stance : List.of("supporting", "opposing")) {
			JsonNode groups = classification.path(stance);
			if (!groups.isObject()) continue;
			for (Map.Entry<String, JsonNode> entry : ((ObjectNode) groups).properties()) {
				if (entry.getValue().isArray() && !entry.getValue().isEmpty()) return true;
			}
		}
		return false;
	}

	private boolean hasPaperEvidence(JsonNode papers) {
		if (papers == null || !papers.isArray()) return false;
		for (JsonNode paper : papers) {
			if (hasText(paper.path("title")) || hasText(paper.path("abstract"))
					|| hasText(paper.path("tldr")) || hasText(paper.path("tldr").path("text"))) return true;
		}
		return false;
	}

	private boolean hasText(JsonNode node) {
		return node != null && node.isTextual() && !node.asText().isBlank();
	}

	private boolean hasCurrentClassificationVersion(String responseBody) {
		try {
			JsonNode stored = new ObjectMapper().readTree(responseBody);
			return stored.path("_opinionClassificationVersion").asInt(0) >= OPINION_CLASSIFICATION_VERSION;
		} catch (Exception ignored) {
			return false;
		}
	}

	private void persistOpinionGraph(Graph graph, ObjectNode categorizedResponseJSON) {
		for (String key : List.of("supporting", "opposing")) {
			JsonNode opinionCategoryNode = categorizedResponseJSON.path(key);
			if (!opinionCategoryNode.isObject()) continue;
			OpinionType opinionType = key.equals("supporting") ? OpinionType.SUPPORTING : OpinionType.OPPOSING;
			for (Map.Entry<String, JsonNode> opinionEntry : ((ObjectNode) opinionCategoryNode).properties()) {
				Opinion opinion = new Opinion();
				opinion.setOpinionVal(opinionEntry.getKey());
				opinion.setOpinionType(opinionType);
				opinionService.saveOpinion(opinion);

				JsonNode paperIds = opinionEntry.getValue();
				if (paperIds.isArray()) {
					for (JsonNode paperIdNode : paperIds) {
						String paperId = paperIdNode.asText();
						ResearchPaper paper = researchPaperService.getReferenceBySemanticPaperId(paperId);
						if (paper != null) {
							opinionRelatedPaperService.saveOpinionRelatedPapers(new OpinionRelatedPapers(opinion, paper));
						}
					}
				}

				OpinionEdgeId edgeId = new OpinionEdgeId(0, graph.getId(), opinion.getId());
				edgeId.setEdgeId(opinionEdgeIdService.generateRandomEdgeId());
				OpinionEdge edge = new OpinionEdge();
				edge.setId(edgeId);
				edge.setGraph(graph);
				edge.setOpinion(opinion);
				opinionEdgeService.saveOpinionEdge(edge);
			}
		}
	}

	public ResponseEntity<ObjectNode> categorizedResponseFormatting(String responseBody) {
		try {         
			ObjectMapper mapper = new ObjectMapper();
			JsonNode rootNode = mapper.readTree(responseBody);
			JsonNode categorized = rootNode;
			if (!isCategorizedResponse(categorized)) {
				String textNode = rootNode.path("candidates").path(0).path("content").path("parts").path(0).path("text").asText("");
				textNode = textNode.trim().replaceFirst("(?s)^```(?:json)?\\s*", "").replaceFirst("\\s*```$", "").trim();
				if (textNode.isBlank()) return invalidOpinionResponse(mapper);
				categorized = mapper.readTree(textNode);
			}
			if (!isCategorizedResponse(categorized)) return invalidOpinionResponse(mapper);
			ObjectNode normalized = mapper.createObjectNode();
			normalized.set("supporting", normalizeOpinionGroups(categorized.path("supporting"), "Supporting evidence", mapper));
			normalized.set("opposing", normalizeOpinionGroups(categorized.path("opposing"), "Opposing evidence", mapper));
			return ResponseEntity.ok(normalized);

		} catch (Exception e) {
			logger.warn("Gemini opinion output was malformed JSON ({}).", e.getClass().getSimpleName());
			return invalidOpinionResponse(new ObjectMapper());
        }
    }

	private ResponseEntity<ObjectNode> invalidOpinionResponse(ObjectMapper mapper) {
		ObjectNode error = mapper.createObjectNode();
		error.put("message", "Opinion classification returned an empty or invalid response. No labels were applied; retry classification later.");
		return ResponseEntity.status(HttpStatus.BAD_GATEWAY).body(error);
	}

	private ObjectNode normalizeOpinionGroups(JsonNode rawGroups, String defaultLabel, ObjectMapper mapper) {
		ObjectNode groups = mapper.createObjectNode();
		if (rawGroups == null || rawGroups.isMissingNode() || rawGroups.isNull()) return groups;

		if (rawGroups.isObject()) {
			boolean groupObject = List.of("paperId", "paperIds", "paper_ids", "ids", "papers")
					.stream().anyMatch(key -> rawGroups.has(key));
			if (groupObject) {
				String label = firstText(rawGroups, "label", "opinion", "group", "category");
				List<String> ids = extractPaperIds(rawGroups);
				if (!ids.isEmpty()) addIds(groups, label.isBlank() ? defaultLabel : label, ids);
			} else {
				for (Map.Entry<String, JsonNode> entry : ((ObjectNode) rawGroups).properties()) {
					String label = entry.getKey().trim();
					if (label.isBlank()) continue;
					List<String> ids = extractPaperIds(entry.getValue());
					if (!ids.isEmpty()) addIds(groups, label, ids);
				}
			}
		} else if (rawGroups.isArray()) {
			for (JsonNode item : rawGroups) {
				if (!item.isObject() && !item.isTextual()) continue;
				String label = item.isObject() ? firstText(item, "label", "opinion", "group", "category") : "";
				List<String> ids = extractPaperIds(item);
				if (!ids.isEmpty()) addIds(groups, label.isBlank() ? defaultLabel : label, ids);
			}
		}
		return groups;
	}

	private List<String> extractPaperIds(JsonNode value) {
		List<String> ids = new ArrayList<>();
		if (value == null || value.isNull()) return ids;
		if (value.isTextual()) {
			String id = value.asText().trim();
			if (!id.isBlank()) ids.add(id);
			return ids;
		}
		if (value.isArray()) {
			for (JsonNode item : value) ids.addAll(extractPaperIds(item));
			return ids;
		}
		if (value.isObject()) {
			for (String key : List.of("paperId", "paperIds", "paper_ids", "ids", "papers")) {
				JsonNode idsNode = value.get(key);
				if (idsNode != null) ids.addAll(extractPaperIds(idsNode));
			}
		}
		return ids;
	}

	private String firstText(JsonNode node, String... fields) {
		for (String field : fields) {
			JsonNode value = node.path(field);
			if (value.isTextual() && !value.asText().isBlank()) return value.asText().trim();
		}
		return "";
	}

	private void addIds(ObjectNode groups, String label, List<String> ids) {
		var groupIds = groups.path(label).isArray()
				? (tools.jackson.databind.node.ArrayNode) groups.path(label)
				: groups.putArray(label);
		for (String id : ids) {
			boolean alreadyPresent = false;
			for (JsonNode existing : groupIds) {
				if (id.equals(existing.asText())) {
					alreadyPresent = true;
					break;
				}
			}
			if (!alreadyPresent) groupIds.add(id);
		}
	}

	private boolean isCategorizedResponse(JsonNode node) {
		return node != null && node.isObject()
				&& (isOpinionCategory(node.path("supporting")) || isOpinionCategory(node.path("opposing")));
	}

	private boolean isOpinionCategory(JsonNode node) {
		return node != null && (node.isObject() || node.isArray());
	}

	public ResponseEntity<ObjectNode> responseFormatting(String responseBody) {
		try {
			ObjectMapper objectMapper = new ObjectMapper();
			JsonNode jsonNode = objectMapper.readTree(responseBody);

			String text = jsonNode.path("candidates").path(0)
					.path("content").path("parts").path(0)
					.path("text").asText("")
					.replaceAll("```[a-zA-Z]*", "")
					.replaceAll("\\*|\\r|\\n", " ").trim();
			if (text.isBlank()) {
				return ResponseEntity.status(HttpStatus.BAD_GATEWAY).body(null);
			}

			// Gemini may follow the requested JSON shape instead of the legacy labeled text.
			// Parse that shape before treating the entire response as search keywords.
			try {
				JsonNode structured = objectMapper.readTree(text);
				if (structured != null && structured.isObject()) {
					String normalizedKeywords = normalizeKeywords(structured.path("keywords"));
					if (normalizedKeywords.isBlank()) return ResponseEntity.status(HttpStatus.BAD_GATEWAY).body(null);
					ObjectNode responseJson = objectMapper.createObjectNode();
					responseJson.put("keywords", normalizedKeywords);
					responseJson.put("sentiment", structured.path("sentiment").asText("unknown").trim());
					return ResponseEntity.ok(responseJson);
				}
			} catch (Exception ignored) {
				// Continue parsing the legacy `keywords: ...; sentiment: ...` format.
			}

			String lowerText = text.toLowerCase(java.util.Locale.ROOT);
			int keywordsLabel = lowerText.indexOf("keywords:");
			int sentimentLabel = lowerText.indexOf("sentiment:");
			String keywords;
			if (keywordsLabel >= 0) {
				int keywordsStart = keywordsLabel + "keywords:".length();
				int keywordsEnd = sentimentLabel > keywordsStart ? sentimentLabel : text.length();
				keywords = text.substring(keywordsStart, keywordsEnd).trim();
			} else {
				// Keep a usable query if Gemini returns plain text instead of the requested labels.
				keywords = text;
			}
			String sentiment = sentimentLabel >= 0
					? text.substring(sentimentLabel + "sentiment:".length()).trim()
					: "unknown";
			if (keywords.isBlank()) {
				return ResponseEntity.status(HttpStatus.BAD_GATEWAY).body(null);
			}
	
			ObjectNode responseJson = objectMapper.createObjectNode();
			responseJson.put("keywords", keywords);
			responseJson.put("sentiment", sentiment);
	
			return ResponseEntity.ok(responseJson);

		} catch (Exception e) {
            e.printStackTrace();
            return ResponseEntity.status(HttpStatus.INTERNAL_SERVER_ERROR).body(null);
        }
	}

	private String normalizeKeywords(JsonNode value) {
		if (value == null || value.isNull()) return "";
		if (value.isTextual()) return value.asText().trim();
		if (value.isArray()) {
			List<String> keywords = new ArrayList<>();
			for (JsonNode item : value) {
				if (item.isTextual() && !item.asText().isBlank()) keywords.add(item.asText().trim());
			}
			return String.join(" ", keywords);
		}
		return "";
	}
}
