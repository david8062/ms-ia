package com.IusCloud.msia.core.features.cases.infrastructure.inbound;

import com.IusCloud.msia.config.AsyncConfig;
import com.IusCloud.msia.core.features.cases.application.dto.CaseSummaryRequest;
import com.IusCloud.msia.core.features.cases.application.dto.CaseSummaryResponse;
import com.IusCloud.msia.core.features.cases.application.usecase.CaseSummaryUseCase;
import com.IusCloud.msia.shared.tenant.TenantContext;
import com.IusCloud.msia.shared.tenant.UserContext;
import jakarta.validation.Valid;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.http.MediaType;
import org.springframework.scheduling.concurrent.ThreadPoolTaskExecutor;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.servlet.mvc.method.annotation.SseEmitter;

import java.io.IOException;
import java.util.Map;
import java.util.UUID;

@RestController
@Slf4j
@RequestMapping("/api/v1/cases")
public class CaseSummaryController {

    /** Timeout del stream SSE (5 min), alineado con proxy_read_timeout de nginx. */
    private static final long SSE_TIMEOUT_MS = 300_000L;

    private final CaseSummaryUseCase useCase;
    private final ThreadPoolTaskExecutor streamExecutor;

    public CaseSummaryController(CaseSummaryUseCase useCase,
                                 @Qualifier(AsyncConfig.AI_STREAM_EXECUTOR) ThreadPoolTaskExecutor streamExecutor) {
        this.useCase = useCase;
        this.streamExecutor = streamExecutor;
    }

    /**
     * Genera el resumen de un caso en streaming (SSE). El front envía el caso ya
     * ensamblado ({@code caso}, {@code notas}, {@code actividades}, {@code audiencias}).
     * Eventos: {@code delta} ({@code {"text": "..."}}), {@code done} ({@link CaseSummaryResponse})
     * y {@code error}. El stream corre en {@code aiStreamExecutor}; tenant/usuario
     * se capturan del hilo de la petición y se re-establecen en el del stream.
     */
    @PostMapping(value = "/summary/stream", produces = MediaType.TEXT_EVENT_STREAM_VALUE)
    public SseEmitter summaryStream(@Valid @RequestBody CaseSummaryRequest request) {
        SseEmitter emitter = new SseEmitter(SSE_TIMEOUT_MS);
        UUID tenantId = TenantContext.getTenantId();
        UUID userId = UserContext.getUserId();

        streamExecutor.execute(() -> {
            try {
                TenantContext.setTenantId(tenantId);
                if (userId != null) {
                    UserContext.setUserId(userId);
                }
                CaseSummaryResponse result = useCase.streamSummary(
                        request,
                        delta -> sendEvent(emitter, "delta", Map.of("text", delta)));
                sendEvent(emitter, "done", result);
                emitter.complete();
            } catch (Exception ex) {
                log.warn("Stream SSE de resumen de caso falló: {}", ex.getMessage());
                sendEvent(emitter, "error", Map.of("message", ex.getMessage() != null ? ex.getMessage() : "Error inesperado"));
                emitter.completeWithError(ex);
            } finally {
                TenantContext.clear();
                UserContext.clear();
            }
        });

        return emitter;
    }

    private void sendEvent(SseEmitter emitter, String name, Object data) {
        try {
            emitter.send(SseEmitter.event().name(name).data(data, MediaType.APPLICATION_JSON));
        } catch (IOException | IllegalStateException e) {
            log.debug("No se pudo enviar evento SSE '{}': {}", name, e.getMessage());
        }
    }
}
