package com.IusCloud.msia.core.features.chat.application.dto;

import com.IusCloud.msia.core.features.chat.domain.model.MessageEntity;

import java.time.Instant;
import java.util.UUID;

public record MessageResponse(
        UUID id,
        String role,
        String content,
        Instant createdAt
) {
    public static MessageResponse from(MessageEntity entity) {
        return new MessageResponse(
                entity.getId(),
                entity.getRole().name(),
                entity.getContent(),
                entity.getCreatedAt()
        );
    }
}
