package com.IusCloud.msia.core.features.chat.application.dto;

import jakarta.validation.constraints.Size;

import java.util.UUID;

public record CreateConversationRequest(
        @Size(max = 255, message = "El título no puede superar 255 caracteres")
        String title,
        /** Caso de legalCore al que se asocia la conversación (opcional). */
        UUID caseId
) {
}
