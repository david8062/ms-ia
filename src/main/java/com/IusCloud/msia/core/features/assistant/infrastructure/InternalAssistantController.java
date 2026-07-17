package com.IusCloud.msia.core.features.assistant.infrastructure;

import com.IusCloud.msia.core.features.assistant.application.WhatsAppAssistantUseCase;
import com.IusCloud.msia.core.features.assistant.application.WhatsAppAssistantUseCase.AssistantResult;
import lombok.RequiredArgsConstructor;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;

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
        AssistantResult result = useCase.handle(request.phone(), request.text());
        List<MediaDTO> media = result.media().stream()
                .map(m -> new MediaDTO(m.url(), m.filename()))
                .toList();
        LeadNotifyDTO lead = result.leadNotify() != null
                ? new LeadNotifyDTO(result.leadNotify().phone(), result.leadNotify().text())
                : null;
        return new AssistantReplyResponse(result.reply(), media, lead);
    }

    public record AssistantWhatsappRequest(String phone, String text) {}

    /** Un archivo a enviar (URL de descarga + nombre); ms-messaging lo entrega por WhatsApp. */
    public record MediaDTO(String url, String filename) {}

    /** Aviso al dueño de un lead (número sin cuenta); ms-messaging lo envía aparte. */
    public record LeadNotifyDTO(String phone, String text) {}

    public record AssistantReplyResponse(String reply, List<MediaDTO> media, LeadNotifyDTO leadNotify) {}
}
