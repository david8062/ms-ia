package com.IusCloud.msia.core.features.chat.infrastructure.inbound;

import com.IusCloud.msia.config.AsyncConfig;
import com.IusCloud.msia.core.features.chat.application.dto.ChatReplyResponse;
import com.IusCloud.msia.core.features.chat.application.dto.ConversationResponse;
import com.IusCloud.msia.core.features.chat.application.dto.CreateConversationRequest;
import com.IusCloud.msia.core.features.chat.application.dto.CreateDocumentConversationRequest;
import com.IusCloud.msia.core.features.chat.application.dto.SaveSummaryConversationRequest;
import com.IusCloud.msia.core.features.chat.application.dto.SendMessageRequest;
import com.IusCloud.msia.core.features.chat.application.usecase.ChatUseCase;
import com.IusCloud.msia.shared.responses.ApiResponse;
import com.IusCloud.msia.shared.responses.ListResponse;
import com.IusCloud.msia.shared.responses.ResponseUtil;
import com.IusCloud.msia.shared.tenant.AuthTokenContext;
import com.IusCloud.msia.shared.tenant.TenantContext;
import com.IusCloud.msia.shared.tenant.UserContext;
import jakarta.validation.Valid;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.scheduling.concurrent.ThreadPoolTaskExecutor;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.servlet.mvc.method.annotation.SseEmitter;

import java.io.IOException;
import java.util.Map;
import java.util.UUID;

@RestController
@Slf4j
@RequestMapping("/api/v1/chat/conversations")
public class ChatController {

    /** Timeout del stream SSE (5 min), alineado con proxy_read_timeout de nginx. */
    private static final long SSE_TIMEOUT_MS = 300_000L;

    private final ChatUseCase useCase;
    private final ThreadPoolTaskExecutor streamExecutor;

    public ChatController(ChatUseCase useCase,
                          @Qualifier(AsyncConfig.AI_STREAM_EXECUTOR) ThreadPoolTaskExecutor streamExecutor) {
        this.useCase = useCase;
        this.streamExecutor = streamExecutor;
    }

    @PostMapping
    public ResponseEntity<ApiResponse<ConversationResponse>> create(
            @Valid @RequestBody(required = false) CreateConversationRequest request) {
        return ResponseUtil.created(useCase.createConversation(request));
    }

    /**
     * Inicia una conversación a partir de un documento de legalCore/MinIO: lo
     * analiza y deja el análisis como primer mensaje. Luego se puede seguir
     * preguntando con los endpoints de mensajes (re-adjunta el documento cacheado).
     */
    @PostMapping("/from-document")
    public ResponseEntity<ApiResponse<ChatReplyResponse>> createFromDocument(
            @Valid @RequestBody CreateDocumentConversationRequest request) {
        return ResponseUtil.created(useCase.createFromDocument(request));
    }

    /** Guarda un resumen ya generado como conversación del caso (no vuelve a llamar al modelo). */
    @PostMapping("/from-summary")
    public ResponseEntity<ApiResponse<ConversationResponse>> createFromSummary(
            @Valid @RequestBody SaveSummaryConversationRequest request) {
        return ResponseUtil.created(useCase.saveSummaryAsConversation(request));
    }

    @GetMapping
    public ResponseEntity<ListResponse<ConversationResponse>> list(
            @RequestParam(required = false) UUID caseId) {
        return ResponseUtil.list(useCase.listConversations(caseId));
    }

    @GetMapping("/{id}")
    public ResponseEntity<ApiResponse<ConversationResponse>> getById(@PathVariable UUID id) {
        return ResponseUtil.ok(useCase.getConversation(id));
    }

    @PostMapping("/{id}/messages")
    public ResponseEntity<ApiResponse<ChatReplyResponse>> sendMessage(
            @PathVariable UUID id,
            @Valid @RequestBody SendMessageRequest request) {
        return ResponseUtil.created(useCase.sendMessage(id, request.message()));
    }

    /**
     * Igual que {@link #sendMessage} pero la respuesta llega en streaming (SSE):
     * eventos {@code delta} ({@code {"text": "..."}}) por cada fragmento, un
     * evento {@code done} con el {@link ChatReplyResponse} final, o {@code error}.
     *
     * <p>El stream corre en un hilo aparte ({@code aiStreamExecutor}); como el
     * tenant/usuario viven en ThreadLocals poblados por {@code TenantFilter} sobre
     * el hilo de la petición, los capturamos aquí y los re-establecemos dentro.
     */
    @PostMapping(value = "/{id}/messages/stream", produces = MediaType.TEXT_EVENT_STREAM_VALUE)
    public SseEmitter sendMessageStream(
            @PathVariable UUID id,
            @Valid @RequestBody SendMessageRequest request) {

        SseEmitter emitter = new SseEmitter(SSE_TIMEOUT_MS);
        UUID tenantId = TenantContext.getTenantId();
        UUID userId = UserContext.getUserId();
        String authToken = AuthTokenContext.getToken();

        streamExecutor.execute(() -> {
            try {
                TenantContext.setTenantId(tenantId);
                if (userId != null) {
                    UserContext.setUserId(userId);
                }
                if (authToken != null) {
                    AuthTokenContext.setToken(authToken);
                }
                ChatReplyResponse reply = useCase.streamMessage(
                        id, request.message(),
                        delta -> sendEvent(emitter, "delta", Map.of("text", delta)));
                sendEvent(emitter, "done", reply);
                emitter.complete();
            } catch (Exception ex) {
                log.warn("Stream SSE falló para conversación {}: {}", id, ex.getMessage());
                sendEvent(emitter, "error", Map.of("message", ex.getMessage() != null ? ex.getMessage() : "Error inesperado"));
                emitter.completeWithError(ex);
            } finally {
                TenantContext.clear();
                UserContext.clear();
                AuthTokenContext.clear();
            }
        });

        return emitter;
    }

    @DeleteMapping("/{id}")
    public ResponseEntity<Void> delete(@PathVariable UUID id) {
        useCase.deleteConversation(id);
        return ResponseUtil.noContent();
    }

    private void sendEvent(SseEmitter emitter, String name, Object data) {
        try {
            emitter.send(SseEmitter.event().name(name).data(data, MediaType.APPLICATION_JSON));
        } catch (IOException | IllegalStateException e) {
            // El cliente cerró la conexión o el emitter ya terminó: nada que hacer.
            log.debug("No se pudo enviar evento SSE '{}': {}", name, e.getMessage());
        }
    }
}
