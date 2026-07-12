# CLAUDE.md — ms-ia

Guía para Claude Code al trabajar en este microservicio.

## Qué es

`ms-ia` es el microservicio de **inteligencia artificial** de IusCloud. Integra el modelo
**Claude** (Anthropic) vía el SDK oficial de Java y ofrece dos capacidades en su primera versión:

1. **Chat legal** — asistente conversacional multi-turno para abogados.
2. **Análisis de documentos** — resumen / extracción sobre documentos legales (PDF o texto).

- Puerto **8087**, context-path **`/ms-ia/`**.
- Base de datos PostgreSQL **`iuscloud_ai`** (debe existir; Flyway crea el esquema, no la BD).
- Spring Boot 4.x / Java 21, arquitectura hexagonal feature-based (igual que `legalCore` / `ms-plans`).

## Comandos

```bash
./mvnw clean install -DskipTests   # build
./mvnw spring-boot:run             # requiere PostgreSQL accesible
./mvnw test                        # tests
```

En el stack completo: `docker compose up -d --build ms_ia` (desde la raíz `Back/`).

## Configuración clave

`application-dev.properties` / `application-prod.properties`:

- `anthropic.api-key=${ANTHROPIC_API_KEY:}` — **placeholder por defecto**. Sin clave, las llamadas
  a la IA devuelven `422` con un mensaje claro; el resto del servicio arranca igual.
- `anthropic.model=claude-opus-4-8` (configurable vía `ANTHROPIC_MODEL`).
- `anthropic.max-tokens=8000`.

La API key se inyecta como variable de entorno `ANTHROPIC_API_KEY` (ver `.env` en la raíz).

## Multi-tenancy

`TenantFilter` (`config/TenantFilter.java`) extrae del JWT `tenantId` y el `sub` (userId) y los
deja en `TenantContext` / `UserContext` (ThreadLocal, limpiados en `finally`). **Toda consulta
filtra por `tenant_id` + `user_id`** (las conversaciones son privadas por usuario dentro del tenant).

## Estructura

```
com.IusCloud.msia
├── config/                # security (JWT/CORS), TenantFilter, anthropic (AnthropicConfig)
├── core/
│   ├── base/              # BaseModel (JPA)
│   ├── common/
│   │   ├── ai/            # AnthropicService (wrapper del SDK), prompts, AiResult, ChatTurn
│   │   ├── usage/         # registro de consumo de tokens (ai_usage_logs)
│   │   └── abuse/         # detección de prompt injection + bloqueo por abuso (ai_abuse_events)
│   └── features/
│       ├── chat/          # conversaciones + mensajes (ai_conversations, ai_messages)
│       ├── documents/     # análisis de documentos (sin persistencia propia)
│       └── cases/         # resumen de caso por IA (sin persistencia; el front envía el caso)
└── shared/                # tenant contexts, responses, exceptions
```

`AnthropicService` es el **único** punto que toca el SDK de Claude. Usa *adaptive thinking* y
extrae solo los bloques de texto de la respuesta. Las features dependen de él, nunca del SDK.

### Streaming SSE

`POST /api/v1/chat/conversations/{id}/messages/stream` devuelve `text/event-stream`. El stream
corre en `aiStreamExecutor` (hilo aparte); como tenant/usuario viven en ThreadLocals poblados por
`TenantFilter`, el controller los captura y re-establece en el hilo del stream. Eventos:
- `delta` → `{"text": "..."}` por cada fragmento generado.
- `done`  → el `ChatReplyResponse` final (con tokens).
- `error` → `{"message": "..."}`.

El mensaje del usuario y la respuesta completa se persisten igual que en el endpoint no-streaming.

### Análisis de PDF por URL (sin re-subir)

`POST /api/v1/documents/analyze-url` recibe `{ "url", "instruction?" }`. ms-ia descarga el PDF de
la URL (típicamente una presigned URL de MinIO emitida por legalCore vía `/files/presigned-url`),
valida los magic bytes `%PDF` y lo envía a Claude. **Solo PDF.** Guard anti-SSRF: el host de la URL
debe estar en `documents.download.allowed-hosts` (vacío = deshabilitado). ⚠️ El host de la presigned
URL debe ser alcanzable desde el contenedor ms_ia (en prod `storage.ius-cloud.com`; en dev `localhost`
NO resuelve a MinIO desde el contenedor).

### Resumen de caso (streaming)

`POST /api/v1/cases/summary/stream` genera un resumen ejecutivo de un caso por SSE (mismos eventos
`delta`/`done`/`error` que el chat). **El frontend ensambla y envía el caso** (`caso`, `notas`,
`actividades`, `audiencias`) desde lo que ya cargó de legalCore — ms-ia **no llama a legalCore** ni
persiste nada (resumen de un solo turno). Las fechas se reciben como texto y se incrustan tal cual.
`CaseSummaryUseCase` arma un texto estructurado del expediente y lo resume con `AnthropicService.chatStream`
(prompt `LegalPrompts.CASE_SUMMARY_SYSTEM`). El consumo se registra en `ai_usage_logs` (feature `CASE_SUMMARY`).

### Conversación con documento

`POST /chat/conversations/from-document` (`{bucket, objectKey, filename?, caseId?, instruction?}`) crea una
conversación **a partir de un documento que vive en legalCore/MinIO**: ms-ia pide una presigned URL fresca a
legalCore (`LegalCoreClient`, reenviando el JWT vía `AuthTokenContext`), descarga el PDF (`DocumentFetcher`),
lo analiza y deja el análisis como primer mensaje. La conversación guarda la referencia del doc
(`document_bucket`/`document_object_key`/`document_filename`, V5) y el `caseId` opcional.

En los **mensajes de follow-up** (`/messages` y `/messages/stream`), `ChatUseCase.invokeModel` detecta que la
conversación tiene documento y lo **re-adjunta cacheado** (descarga otra vez por su objectKey y lo pasa como
bloque con `cacheControl`), usando `DOCUMENT_SYSTEM`. ⚠️ En dev la presigned URL apunta a `localhost:9000`,
no alcanzable desde el contenedor; funciona en prod (`storage.ius-cloud.com`). El JWT se captura también en el
hilo del stream. El `/documents/analyze` multipart sigue existiendo como análisis one-shot sin persistencia.

### Optimización de tokens

- **Prompt caching del system prompt:** `AnthropicService.applyCachedSystem` envía el system como bloque
  con `cacheControl(ephemeral)`. El system (~1.5k tokens, idéntico en todas las llamadas) se cachea (TTL ~5 min)
  y desde la 2ª llamada cuesta ~0.1×. Aplica a chat, documentos y resumen de caso.
- **Cap del historial:** `chat.history.max-messages` (def. 20) acota cuántos mensajes previos se reenvían
  (sin tope, el costo del chat crece de forma cuadrática). `ChatUseCase.buildHistory`.
- **Métricas de caché:** `ai_usage_logs.cache_read_input_tokens` / `cache_creation_input_tokens` (V4) registran
  los aciertos/escrituras de caché para medir el ahorro real. Vienen de `Usage.cacheReadInputTokens()` etc.
- Pendiente (follow-up): cache del prefijo de conversación; thinking budget / modelo por tarea (Haiku para lo ligero).

### Bloqueo por abuso (prompt injection)

`core/common/abuse/AbuseGuardService.screen(feature, userText)` se llama al inicio de cada use case de
IA (chat, documentos, resumen de caso), **antes** de tocar BD o llamar a Claude. Detección heurística
por patrones (`PromptInjectionDetector`, sin costo de tokens). Comportamiento:
- Texto con manipulación → registra evento en `ai_abuse_events` (vía `AbuseEventRecorder`, `REQUIRES_NEW`,
  para que persista aunque el use case haga rollback) y rechaza con **422** + mensaje de seguridad.
- Tras `ai.abuse.threshold` (def. 3) intentos dentro de `ai.abuse.window-minutes` (def. 60), el usuario
  queda **bloqueado** de la IA → **423 Locked**. Es un cooldown por usuario que se **auto-libera** cuando
  los eventos salen de la ventana. No requiere intervención de admin.

## Endpoints

| Método | Ruta (tras `/ms-ia/`)                          | Descripción                          |
|--------|-----------------------------------------------|--------------------------------------|
| POST   | `/api/v1/chat/conversations`                  | Crear conversación (`title?`, `caseId?`) |
| POST   | `/api/v1/chat/conversations/from-document`    | Analizar un doc de legalCore y abrir conversación sobre él |
| GET    | `/api/v1/chat/conversations`                  | Listar conversaciones del usuario (`?caseId=` opcional) |
| GET    | `/api/v1/chat/conversations/{id}`             | Detalle + mensajes                   |
| POST   | `/api/v1/chat/conversations/{id}/messages`    | Enviar mensaje y obtener respuesta   |
| POST   | `/api/v1/chat/conversations/{id}/messages/stream` | Igual, pero respuesta en streaming (SSE) |
| DELETE | `/api/v1/chat/conversations/{id}`             | Borrado lógico                       |
| POST   | `/api/v1/documents/analyze` (multipart)       | Analizar PDF/texto (`file`, `instruction?`) |
| POST   | `/api/v1/documents/analyze-url` (json)        | Analizar PDF por URL (`url`, `instruction?`) — solo PDF |
| POST   | `/api/v1/cases/summary/stream` (SSE)          | Resumen de un caso en streaming (el front envía el caso ensamblado) |

Todos requieren `Authorization: Bearer <JWT>` con claim `tenantId`.

## Base de datos

Flyway en `src/main/resources/db/migration/` (`V{N}__descripcion.sql`). Nunca modificar
migraciones existentes. Tablas: `ai_conversations`, `ai_messages`, `ai_usage_logs`, `ai_abuse_events`.
`ai_conversations.case_id` (nullable, V3) vincula una conversación a un caso de legalCore; permite el
histórico de chat por caso (`GET /chat/conversations?caseId=`).

## Pendientes / follow-ups conocidos

- Enforcement de tope de tokens por tenant/usuario (hoy solo se registra el consumo en
  `ai_usage_logs`; falta la lógica de bloqueo). Modelo sugerido: ~500-670k tokens/abogado/mes.
- Soporte de Word (.docx) — **descartado** por decisión de producto; solo PDF.
- ⚠️ Análisis de PDF por URL: el host de la presigned URL de MinIO debe ser alcanzable desde el
  contenedor ms_ia. En dev legalCore firma con `localhost:9000` (no alcanzable desde el contenedor);
  resuelto en prod con `storage.ius-cloud.com`. Para probar en dev, ejecutar ms-ia fuera de Docker o
  unificar `minio.public-endpoint`.
