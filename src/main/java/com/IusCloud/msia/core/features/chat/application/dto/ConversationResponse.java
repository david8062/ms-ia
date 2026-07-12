package com.IusCloud.msia.core.features.chat.application.dto;

import com.IusCloud.msia.core.features.chat.domain.model.ConversationEntity;

import java.time.Instant;
import java.util.List;
import java.util.UUID;

public record ConversationResponse(
        UUID id,
        UUID caseId,
        String documentFilename,
        String title,
        Instant createdAt,
        Instant updatedAt,
        List<MessageResponse> messages
) {
    /** Vista de listado (sin mensajes). */
    public static ConversationResponse summary(ConversationEntity entity) {
        return new ConversationResponse(
                entity.getId(),
                entity.getCaseId(),
                entity.getDocumentFilename(),
                entity.getTitle(),
                entity.getCreatedAt(),
                entity.getUpdatedAt(),
                null
        );
    }

    /** Vista de detalle (con mensajes). */
    public static ConversationResponse detail(ConversationEntity entity, List<MessageResponse> messages) {
        return new ConversationResponse(
                entity.getId(),
                entity.getCaseId(),
                entity.getDocumentFilename(),
                entity.getTitle(),
                entity.getCreatedAt(),
                entity.getUpdatedAt(),
                messages
        );
    }
}
