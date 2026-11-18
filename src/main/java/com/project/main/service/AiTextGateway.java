package com.project.main.service;

/** Provider-neutral boundary for generated research text. */
public interface AiTextGateway {
    String generate(String prompt);
}
