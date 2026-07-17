package com.IusCloud.msia.core.features.assistant.infrastructure;

import com.IusCloud.msia.core.features.assistant.application.WhatsAppAssistantUseCase;
import lombok.RequiredArgsConstructor;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * Entrada del asistente por WhatsApp. Server-a-server (X-Internal-Key, sin JWT): lo llama
 * ms-messaging cuando llega un mensaje a la línea de plataforma. Devuelve el texto a enviar;
 * ms-messaging (que es dueño de la conexión de WhatsApp) lo entrega.
 */
@RestController
@RequestMapping("/api/v1/internal/assistant")
@RequiredArgsConstructor
public class InternalAssistantController {

    private final WhatsAppAssistantUseCase useCase;

    @PostMapping("/whatsapp")
    public AssistantReplyResponse whatsapp(@RequestBody AssistantWhatsappRequest request) {
        String reply = useCase.handle(request.phone(), request.text());
        return new AssistantReplyResponse(reply);
    }

    public record AssistantWhatsappRequest(String phone, String text) {}

    public record AssistantReplyResponse(String reply) {}
}
