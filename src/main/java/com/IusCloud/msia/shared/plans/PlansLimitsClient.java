package com.IusCloud.msia.shared.plans;

import com.IusCloud.msia.shared.plans.dto.PlansApiResponse;
import com.IusCloud.msia.shared.plans.dto.TenantLimits;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.core.ParameterizedTypeReference;
import org.springframework.http.client.SimpleClientHttpRequestFactory;
import org.springframework.stereotype.Component;
import org.springframework.web.client.RestClient;

import java.time.Duration;
import java.util.UUID;

/**
 * Cliente del endpoint interno de límites de ms-plans (X-Internal-Key).
 * ms-plans es la única fuente de verdad de los límites del plan; aquí solo se consultan.
 */
@Slf4j
@Component
public class PlansLimitsClient {

    private final RestClient client;
    private final String internalApiKey;

    public PlansLimitsClient(
            @Value("${plans.service.url}") String baseUrl,
            @Value("${internal.api.key}") String internalApiKey
    ) {
        this.internalApiKey = internalApiKey;

        SimpleClientHttpRequestFactory factory = new SimpleClientHttpRequestFactory();
        factory.setConnectTimeout((int) Duration.ofSeconds(5).toMillis());
        factory.setReadTimeout((int) Duration.ofSeconds(8).toMillis());

        this.client = RestClient.builder()
                .baseUrl(baseUrl)
                .requestFactory(factory)
                .build();
    }

    /**
     * Devuelve los límites del plan del tenant, o {@code null} si no hay suscripción activa
     * o ms-plans no responde (fail-open: no se bloquea la operación por una caída del servicio).
     */
    public TenantLimits getLimits(UUID tenantId) {
        try {
            PlansApiResponse<TenantLimits> resp = client.get()
                    .uri("/api/v1/internal/subscriptions/tenant/{tenantId}/limits", tenantId)
                    .header("X-Internal-Key", internalApiKey)
                    .retrieve()
                    .body(new ParameterizedTypeReference<>() {});
            return resp != null ? resp.data() : null;
        } catch (Exception e) {
            log.warn("No se pudieron obtener los límites del plan para tenant {}: {}", tenantId, e.getMessage());
            return null;
        }
    }
}
