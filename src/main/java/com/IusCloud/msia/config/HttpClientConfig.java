package com.IusCloud.msia.config;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.http.client.SimpleClientHttpRequestFactory;
import org.springframework.web.client.RestClient;

import java.time.Duration;

/**
 * Clientes HTTP: descarga de documentos (presigned URLs de MinIO) y llamadas
 * internas a legalCore (p. ej. para obtener una presigned URL por objectKey).
 */
@Configuration
public class HttpClientConfig {

    public static final String DOCUMENT_DOWNLOAD_REST_CLIENT = "documentDownloadRestClient";
    public static final String LEGAL_CORE_REST_CLIENT = "legalCoreRestClient";

    @Bean(DOCUMENT_DOWNLOAD_REST_CLIENT)
    public RestClient documentDownloadRestClient() {
        SimpleClientHttpRequestFactory factory = new SimpleClientHttpRequestFactory();
        factory.setConnectTimeout(Duration.ofSeconds(5));
        factory.setReadTimeout(Duration.ofSeconds(60));
        return RestClient.builder()
                .requestFactory(factory)
                .build();
    }

    @Bean(LEGAL_CORE_REST_CLIENT)
    public RestClient legalCoreRestClient(@Value("${legal.core.service.url}") String baseUrl) {
        SimpleClientHttpRequestFactory factory = new SimpleClientHttpRequestFactory();
        factory.setConnectTimeout(Duration.ofSeconds(5));
        factory.setReadTimeout(Duration.ofSeconds(30));
        return RestClient.builder()
                .baseUrl(baseUrl)
                .requestFactory(factory)
                .build();
    }
}
