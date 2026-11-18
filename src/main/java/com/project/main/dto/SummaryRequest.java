package com.project.main.dto;

/** Identifies a server-stored paper; the browser never supplies summary evidence. */
public record SummaryRequest(String paperId) {}
