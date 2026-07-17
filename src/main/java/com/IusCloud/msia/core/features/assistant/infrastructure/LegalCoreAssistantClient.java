package com.IusCloud.msia.core.features.assistant.infrastructure;

import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.core.ParameterizedTypeReference;
import org.springframework.http.client.SimpleClientHttpRequestFactory;
import org.springframework.stereotype.Component;
import org.springframework.web.client.RestClient;

import java.time.Duration;
import java.time.OffsetDateTime;
import java.time.format.DateTimeFormatter;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

/**
 * Lee la agenda del abogado (audiencias, tareas, estado de proceso) desde los endpoints internos
 * de legal-core (X-Internal-Key). El {@code tenantId} ya viene resuelto y acota TODA consulta.
 */
@Component
@Slf4j
public class LegalCoreAssistantClient {

    private final RestClient client;
    private final String internalApiKey;

    public LegalCoreAssistantClient(
            @Value("${legal.core.service.url}") String baseUrl,
            @Value("${internal.api.key}") String internalApiKey) {
        this.internalApiKey = internalApiKey;

        SimpleClientHttpRequestFactory factory = new SimpleClientHttpRequestFactory();
        factory.setConnectTimeout((int) Duration.ofSeconds(5).toMillis());
        factory.setReadTimeout((int) Duration.ofSeconds(10).toMillis());

        this.client = RestClient.builder()
                .baseUrl(baseUrl)
                .requestFactory(factory)
                .build();
    }

    public List<HearingDTO> hearings(UUID tenantId, OffsetDateTime from, OffsetDateTime to) {
        String f = from.format(DateTimeFormatter.ISO_OFFSET_DATE_TIME);
        String t = to.format(DateTimeFormatter.ISO_OFFSET_DATE_TIME);
        List<HearingDTO> res = client.get()
                .uri(uri -> uri.path("/api/v1/internal/assistant/hearings")
                        .queryParam("tenantId", tenantId)
                        .queryParam("from", f)
                        .queryParam("to", t)
                        .build())
                .header("X-Internal-Key", internalApiKey)
                .retrieve()
                .body(new ParameterizedTypeReference<>() {});
        return res != null ? res : List.of();
    }

    public List<TaskDTO> tasks(UUID tenantId) {
        List<TaskDTO> res = client.get()
                .uri(uri -> uri.path("/api/v1/internal/assistant/tasks")
                        .queryParam("tenantId", tenantId)
                        .build())
                .header("X-Internal-Key", internalApiKey)
                .retrieve()
                .body(new ParameterizedTypeReference<>() {});
        return res != null ? res : List.of();
    }

    /** Documentos de un proceso por radicado; vacío si el caso no existe (404). */
    public List<DocumentDTO> documents(UUID tenantId, String radicado) {
        try {
            List<DocumentDTO> res = client.get()
                    .uri(uri -> uri.path("/api/v1/internal/assistant/documents")
                            .queryParam("tenantId", tenantId)
                            .queryParam("radicado", radicado)
                            .build())
                    .header("X-Internal-Key", internalApiKey)
                    .retrieve()
                    .body(new ParameterizedTypeReference<>() {});
            return res != null ? res : List.of();
        } catch (Exception e) {
            log.info("Documentos del proceso {} no disponibles (tenant {}): {}",
                    radicado, tenantId, e.getMessage());
            return List.of();
        }
    }

    /** Estado de un proceso; vacío si el radicado no está en seguimiento (404). */
    public Optional<CaseStatusDTO> caseStatus(UUID tenantId, String radicado) {
        try {
            CaseStatusDTO res = client.get()
                    .uri(uri -> uri.path("/api/v1/internal/assistant/case")
                            .queryParam("tenantId", tenantId)
                            .queryParam("radicado", radicado)
                            .build())
                    .header("X-Internal-Key", internalApiKey)
                    .retrieve()
                    .body(CaseStatusDTO.class);
            return Optional.ofNullable(res);
        } catch (Exception e) {
            log.info("Proceso {} no encontrado en seguimiento del tenant {}: {}",
                    radicado, tenantId, e.getMessage());
            return Optional.empty();
        }
    }

    // ── DTOs (espejo de los de legal-core; fechas como String ISO para no depender de config) ──
    public record HearingDTO(String caseTitle, String title, String scheduledAt,
                             String location, String virtualLink, String status) {}

    public record TaskDTO(String caseTitle, String title, String activityDate,
                          Short priority, String status) {}

    public record CaseStatusDTO(String radicado, String temperature, String status,
                                String lastFechaActuacion, ActuacionDTO latestActuacion) {}

    public record ActuacionDTO(Integer consActuacion, String fechaActuacion,
                               String actuacion, String anotacion) {}

    public record DocumentDTO(String name, String mimeType, String url) {}
}
