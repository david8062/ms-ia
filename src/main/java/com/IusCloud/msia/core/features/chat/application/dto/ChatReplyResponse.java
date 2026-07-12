package com.IusCloud.msia.core.features.chat.application.dto;

import java.util.UUID;

public record ChatReplyResponse(
        UUID conversationId,
        MessageResponse reply,
        int inputTokens,
        int outputTokens
) {
}
