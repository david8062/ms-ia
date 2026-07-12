package com.IusCloud.msia.core.features.cases.application.dto;

public record CaseSummaryResponse(
        String caseNumber,
        String title,
        String summary,
        String model,
        int inputTokens,
        int outputTokens
) {
}
