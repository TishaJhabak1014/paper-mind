package com.project.main.service;

/** Provider-neutral boundary for generated research text. */
public interface AiTextGateway {
    String generate(String prompt);

    /** Generates a JSON-formatted response when the provider supports response-format constraints. */
    default String generateJson(String prompt) {
        return generate(prompt);
    }
}
