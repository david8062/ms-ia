package com.IusCloud.msia.core.features.documents.application.dto;

public record DocumentAnalysisResponse(
        String filename,
        String analysis,
        String model,
        int inputTokens,
        int outputTokens
) {
}
