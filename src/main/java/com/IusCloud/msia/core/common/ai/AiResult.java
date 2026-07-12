package com.IusCloud.msia.core.common.ai;

/**
 * Resultado de una invocación a Claude: el texto generado y el consumo de tokens.
 *
 * @param inputTokens             tokens de entrada NO cacheados (precio normal).
 * @param outputTokens            tokens generados.
 * @param cacheReadInputTokens    tokens de entrada leídos de caché (≈ 0.1× del precio de input).
 * @param cacheCreationInputTokens tokens de entrada escritos a caché (≈ 1.25× la primera vez).
 */
public record AiResult(
        String text,
        int inputTokens,
        int outputTokens,
        int cacheReadInputTokens,
        int cacheCreationInputTokens
) {
}
