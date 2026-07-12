package com.IusCloud.msia.core.common.legalcore;

import com.IusCloud.msia.config.HttpClientConfig;
import com.IusCloud.msia.shared.exceptions.BusinessException;
import com.IusCloud.msia.shared.tenant.AuthTokenContext;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.stereotype.Component;
import org.springframework.web.client.RestClient;
import org.springframework.web.client.RestClientResponseException;

import java.util.Map;

/**
 * Cliente para llamadas internas a legalCore, reenviando el JWT del usuario
 * (vía {@link AuthTokenContext}). Hoy: obtener una presigned URL de descarga por
 * {@code bucket}+{@code objectKey} (los documentos viven en legalCore/MinIO).
 */
@Component
@Slf4j
public class LegalCoreClient {

    private final RestClient legalCoreRestClient;
    private final ObjectMapper objectMapper = new ObjectMapper();

    public LegalCoreClient(@Qualifier(HttpClientConfig.LEGAL_CORE_REST_CLIENT) RestClient legalCoreRestClient) {
        this.legalCoreRestClient = legalCoreRestClient;
    }

    /** Pide a legalCore una presigned URL fresca para descargar el documento. */
    public String presignedUrl(String bucketName, String objectKey) {
        String token = AuthTokenContext.getToken();
        if (token == null || token.isBlank()) {
            throw new BusinessException("No hay sesión para consultar el documento en legalCore.");
        }
        try {
            // Leemos como String y parseamos con el ObjectMapper de la app (robusto
            // ante la config de convertidores del RestClient).
            String body = legalCoreRestClient.post()
                    .uri("/api/v1/files/presigned-url")
                    .header(HttpHeaders.AUTHORIZATION, "Bearer " + token)
                    .contentType(MediaType.APPLICATION_JSON)
                    .body(Map.of("bucketName", bucketName, "objectKey", objectKey))
                    .retrieve()
                    .body(String.class);

            JsonNode response = (body != null && !body.isBlank()) ? objectMapper.readTree(body) : null;
            String url = response != null ? response.path("data").path("url").asText(null) : null;
            if (url == null || url.isBlank()) {
                throw new BusinessException("legalCore no devolvió la URL del documento.");
            }
            return url;
        } catch (RestClientResponseException e) {
            log.warn("Error obteniendo presigned URL de legalCore (HTTP {})", e.getStatusCode().value());
            throw new BusinessException("No se pudo obtener el documento de legalCore (HTTP "
                    + e.getStatusCode().value() + ").");
        } catch (BusinessException e) {
            throw e;
        } catch (Exception e) {
            throw new BusinessException("No se pudo contactar a legalCore para el documento: " + e.getMessage());
        }
    }
}
