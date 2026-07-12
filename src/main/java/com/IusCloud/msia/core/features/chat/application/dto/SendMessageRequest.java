package com.IusCloud.msia.core.features.chat.application.dto;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;

public record SendMessageRequest(
        @NotBlank(message = "El mensaje no puede estar vacío")
        @Size(max = 20000, message = "El mensaje es demasiado largo")
        String message
) {
}
