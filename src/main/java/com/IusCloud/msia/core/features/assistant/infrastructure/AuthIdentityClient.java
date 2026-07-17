package com.IusCloud.msia.core.features.assistant.infrastructure;

import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.MediaType;
import org.springframework.http.client.SimpleClientHttpRequestFactory;
import org.springframework.stereotype.Component;
import org.springframework.web.client.RestClient;

import java.time.Duration;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;

/**
 * Resuelve la identidad del abogado que escribe al asistente por WhatsApp, contra el endpoint
 * interno de auth (X-Internal-Key). auth es la fuente de verdad del teléfono.
 *
 * <p><b>Seguridad:</b> si auth responde 404 (ningún usuario) o 409 (número ambiguo), se devuelve
 * vacío y el asistente NO muestra datos de nadie. Ante una caída de auth también se devuelve vacío
 * (nunca se atiende una consulta sin identidad confirmada).
 */
@Component
@Slf4j
public class AuthIdentityClient {

    private final RestClient client;
    private final String internalApiKey;

    public AuthIdentityClient(
            @Value("${auth.service.url}") String baseUrl,
            @Value("${internal.api.key}") String internalApiKey) {
        this.internalApiKey = internalApiKey;

        SimpleClientHttpRequestFactory factory = new SimpleClientHttpRequestFactory();
        factory.setConnectTimeout((int) Duration.ofSeconds(5).toMillis());
        factory.setReadTimeout((int) Duration.ofSeconds(8).toMillis());

        this.client = RestClient.builder()
                .baseUrl(baseUrl)
                .requestFactory(factory)
                .build();
    }

    public Optional<ResolvedIdentity> resolveByPhone(String phone) {
        try {
            ResolvedIdentity id = client.post()
                    .uri("/api/v1/internal/users/resolve-by-phone")
                    .header("X-Internal-Key", internalApiKey)
                    .contentType(MediaType.APPLICATION_JSON)
                    .body(Map.of("phone", phone))
                    .retrieve()
                    .body(ResolvedIdentity.class);
            return Optional.ofNullable(id);
        } catch (Exception e) {
            // 404 (nadie), 409 (ambiguo) o caída de auth: en todos, no hay identidad confirmada.
            log.info("No se pudo resolver identidad por teléfono ({}): {}",
                    phone, e.getMessage());
            return Optional.empty();
        }
    }

    /** {@code externalAuthId} = users.id de auth (= external_auth_id en legal-core). */
    public record ResolvedIdentity(UUID externalAuthId, UUID tenantId) {}
}
