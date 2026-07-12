-- ─────────────────────────────────────────────────────────────────────────────
-- ms-ia: métricas de prompt caching en la bitácora de consumo. Permiten medir el
-- % de aciertos de caché y el ahorro real tras activar el caching del system prompt.
-- ─────────────────────────────────────────────────────────────────────────────

ALTER TABLE ai_usage_logs
    ADD COLUMN cache_read_input_tokens     INTEGER NOT NULL DEFAULT 0,
    ADD COLUMN cache_creation_input_tokens INTEGER NOT NULL DEFAULT 0;
