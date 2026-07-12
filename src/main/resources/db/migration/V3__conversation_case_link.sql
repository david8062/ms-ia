-- ─────────────────────────────────────────────────────────────────────────────
-- ms-ia: vincula (opcionalmente) una conversación de chat con un caso de legalCore.
-- 'case_id' es nullable: las conversaciones sueltas (no ligadas a un caso) siguen
-- siendo válidas. Permite consultar el histórico de chat por caso.
-- ─────────────────────────────────────────────────────────────────────────────

ALTER TABLE ai_conversations ADD COLUMN case_id UUID;

CREATE INDEX idx_ai_conversations_case
    ON ai_conversations (tenant_id, user_id, case_id)
    WHERE deleted_at IS NULL AND case_id IS NOT NULL;
