-- ─────────────────────────────────────────────────────────────────────────────
-- ms-ia: referencia (opcional) a un documento de legalCore/MinIO adjunto a una
-- conversación. Permite "seguir chateando con el documento": en cada turno ms-ia
-- re-adjunta el PDF (cacheado) descargándolo por su bucket+objectKey. Si estos
-- campos son NULL, la conversación es un chat normal sin documento.
-- ─────────────────────────────────────────────────────────────────────────────

ALTER TABLE ai_conversations ADD COLUMN document_bucket     TEXT;
ALTER TABLE ai_conversations ADD COLUMN document_object_key TEXT;
ALTER TABLE ai_conversations ADD COLUMN document_filename   VARCHAR(512);
