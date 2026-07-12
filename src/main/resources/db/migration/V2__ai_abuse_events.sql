-- ─────────────────────────────────────────────────────────────────────────────
-- ms-ia: bitácora de intentos de uso indebido / manipulación del asistente.
-- Se usa para detectar abuso reiterado por usuario y aplicar un bloqueo temporal
-- (cooldown) sobre las funciones de IA. Filtrada por tenant_id (multi-tenant).
-- ─────────────────────────────────────────────────────────────────────────────

CREATE TABLE ai_abuse_events (
    id          UUID         PRIMARY KEY DEFAULT gen_random_uuid(),
    tenant_id   UUID         NOT NULL,
    user_id     UUID,
    feature     VARCHAR(50)  NOT NULL,             -- CHAT | DOCUMENT_ANALYSIS | CASE_SUMMARY
    reason      VARCHAR(120),                      -- categoría/patrón detectado
    snippet     TEXT,                              -- fragmento del input (recortado), para auditoría
    is_active   BOOLEAN      NOT NULL DEFAULT TRUE,
    created_at  TIMESTAMPTZ  NOT NULL DEFAULT NOW(),
    updated_at  TIMESTAMPTZ,
    deleted_at  TIMESTAMPTZ
);

-- Conteo de eventos recientes por usuario dentro de una ventana de tiempo.
CREATE INDEX idx_ai_abuse_events_tenant_user_date
    ON ai_abuse_events (tenant_id, user_id, created_at);
