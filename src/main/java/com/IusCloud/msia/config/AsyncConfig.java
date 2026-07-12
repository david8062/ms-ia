package com.IusCloud.msia.config;

import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.scheduling.concurrent.ThreadPoolTaskExecutor;

/**
 * Pool de hilos dedicado para servir respuestas en streaming (SSE). El stream a
 * Claude se ejecuta fuera del hilo de la petición HTTP para que el servlet libere
 * el hilo de trabajo mientras la respuesta sigue abierta.
 */
@Configuration
public class AsyncConfig {

    public static final String AI_STREAM_EXECUTOR = "aiStreamExecutor";

    @Bean(AI_STREAM_EXECUTOR)
    public ThreadPoolTaskExecutor aiStreamExecutor() {
        ThreadPoolTaskExecutor executor = new ThreadPoolTaskExecutor();
        executor.setCorePoolSize(4);
        executor.setMaxPoolSize(16);
        executor.setQueueCapacity(50);
        executor.setThreadNamePrefix("ai-stream-");
        executor.initialize();
        return executor;
    }
}
