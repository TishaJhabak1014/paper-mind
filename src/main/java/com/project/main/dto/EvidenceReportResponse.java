package com.project.main.dto;

import java.util.List;

public record EvidenceReportResponse(
        Integer graphId,
        String graphType,
        ReportType reportType,
        ReportScope scope,
        String researchQuestion,
        String synthesis,
        String evidenceBasis,
        List<ReportClaimDto> claims,
        List<SourceCoverageDto> sourceCoverage,
        List<CitationAnalysisDto> citationAnalyses,
        List<String> coverageGaps,
        List<String> limitations,
        List<ReportCandidateDto> sources,
        String coverageDescription) {}
