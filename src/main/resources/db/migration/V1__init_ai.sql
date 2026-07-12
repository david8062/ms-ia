-- ─────────────────────────────────────────────────────────────────────────────
-- ms-ia: esquema inicial (chat legal, análisis de documentos, consumo de tokens)
-- Todas las tablas se filtran por tenant_id (multi-tenant). Ver TenantContext.
-- ─────────────────────────────────────────────────────────────────────────────

-- ─── Conversaciones de chat ──────────────────────────────────────────────────

CREATE TABLE ai_conversations (
    id          UUID         PRIMARY KEY DEFAULT gen_random_uuid(),
    tenant_id   UUID         NOT NULL,
    user_id     UUID         NOT NULL,
    title       VARCHAR(255),
    is_active   BOOLEAN      NOT NULL DEFAULT TRUE,
    created_at  TIMESTAMPTZ  NOT NULL DEFAULT NOW(),
    updated_at  TIMESTAMPTZ,
    deleted_at  TIMESTAMPTZ
);

CREATE INDEX idx_ai_conversations_tenant_user
    ON ai_conversations (tenant_id, user_id)
    WHERE deleted_at IS NULL;

-- ─── Mensajes por conversación ───────────────────────────────────────────────

CREATE TABLE ai_messages (
    id              UUID         PRIMARY KEY DEFAULT gen_random_uuid(),
    conversation_id UUID         NOT NULL REFERENCES ai_conversations(id),
    role            VARCHAR(20)  NOT NULL,            -- USER | ASSISTANT
    content         TEXT         NOT NULL,
    input_tokens    INTEGER,
    output_tokens   INTEGER,
    is_active       BOOLEAN      NOT NULL DEFAULT TRUE,
    created_at      TIMESTAMPTZ  NOT NULL DEFAULT NOW(),
    updated_at      TIMESTAMPTZ,
    deleted_at      TIMESTAMPTZ
);

CREATE INDEX idx_ai_messages_conversation
    ON ai_messages (conversation_id, created_at);

-- ─── Bitácora de consumo de tokens por tenant ────────────────────────────────

CREATE TABLE ai_usage_logs (
    id            UUID         PRIMARY KEY DEFAULT gen_random_uuid(),
    tenant_id     UUID         NOT NULL,
    user_id       UUID,
    feature       VARCHAR(50)  NOT NULL,             -- CHAT | DOCUMENT_ANALYSIS
    model         VARCHAR(60)  NOT NULL,
    input_tokens  INTEGER      NOT NULL DEFAULT 0,
    output_tokens INTEGER      NOT NULL DEFAULT 0,
    is_active     BOOLEAN      NOT NULL DEFAULT TRUE,
    created_at    TIMESTAMPTZ  NOT NULL DEFAULT NOW(),
    updated_at    TIMESTAMPTZ,
    deleted_at    TIMESTAMPTZ
);

CREATE INDEX idx_ai_usage_logs_tenant_date
    ON ai_usage_logs (tenant_id, created_at);
