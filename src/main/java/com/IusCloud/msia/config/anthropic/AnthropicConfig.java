package com.IusCloud.msia.config.anthropic;

import com.anthropic.client.AnthropicClient;
import com.anthropic.client.okhttp.AnthropicOkHttpClient;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

@Configuration
public class AnthropicConfig {

    /**
     * Placeholder usado cuando {@code ANTHROPIC_API_KEY} aún no está configurada.
     * Permite que el bean se construya; las llamadas reales fallan de forma
     * controlada en {@code AnthropicService} con un mensaje claro.
     */
    private static final String PLACEHOLDER_KEY = "anthropic-api-key-not-configured";

    @Bean
    public AnthropicClient anthropicClient(@Value("${anthropic.api-key:}") String apiKey) {
        String key = (apiKey == null || apiKey.isBlank()) ? PLACEHOLDER_KEY : apiKey;
        return AnthropicOkHttpClient.builder()
                .apiKey(key)
                .build();
    }
}
