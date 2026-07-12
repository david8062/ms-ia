# ms-ia — Guía de integración para el frontend

Microservicio de IA de IusCloud (chat legal, análisis de documentos, resumen de casos).

## 1. Base, auth y nginx

- **Base URL:** `https://{tenant}.ius-cloud.com/ms-ia/` (en local: vía nginx en `{tenant}.localhost/ms-ia/`).
- **Todas** las rutas (excepto `/actuator/health`) requieren `Authorization: Bearer <JWT>` — el mismo JWT que emite `ms-auth` y que ya usas para legal-core.
- ⚠️ **GOTCHA #1 (causó un 401):** el interceptor HTTP de Angular **debe adjuntar el Bearer a las URLs de `ms-ia`**. Si tu interceptor filtra por host/base-url y solo cubre `legal-core`/`crm`, las peticiones a `/ms-ia/...` saldrán **sin token → 401**. Agrega el dominio/base de ms-ia a la whitelist del interceptor.

## 2. Envoltorios de respuesta

**Éxito (recurso único):**
```json
{ "timestamp": "...", "status": 200, "message": "Operación exitosa", "data": { ... } }
```
**Éxito (listas):**
```json
{ "timestamp": "...", "status": 200, "message": "Operación exitosa", "count": 3, "data": [ ... ] }
```
**Error:**
```json
{ "status": 422, "error": "Unprocessable Entity", "message": "<mostrar al usuario>", "path": "...", "timestamp": "..." }
```

### Códigos de estado a manejar
| Código | Significado | Acción en el front |
|--------|-------------|--------------------|
| 400 | Body inválido / validación | Revisar el payload; mostrar `message` |
| 401 | Sin token o token inválido | Ver GOTCHA #1 / re-login |
| 404 | Conversación no encontrada | — |
| 422 | Regla de negocio **o intento de manipulación detectado** | Mostrar `message` tal cual |
| 423 | **Usuario bloqueado por abuso** (cooldown temporal) | Mostrar `message`; deshabilitar IA un rato |

## 3. Chat (conversaciones)

| Método | Ruta | Body | Respuesta (`data`) |
|--------|------|------|--------------------|
| POST | `/api/v1/chat/conversations` | `{ "title?": "...", "caseId?": "uuid" }` | `ConversationResponse` |
| GET | `/api/v1/chat/conversations` | — (query `?caseId=uuid` opcional) | `ConversationResponse[]` |
| GET | `/api/v1/chat/conversations/{id}` | — | `ConversationResponse` (con `messages`) |
| POST | `/api/v1/chat/conversations/{id}/messages` | `{ "message": "..." }` | `ChatReplyResponse` |
| POST | `/api/v1/chat/conversations/{id}/messages/stream` | `{ "message": "..." }` | **SSE** (ver §4) |
| DELETE | `/api/v1/chat/conversations/{id}` | — | 204 |

**`ConversationResponse`:**
```json
{ "id":"uuid", "caseId":"uuid|null", "documentFilename":"x.pdf|null",
  "title":"...", "createdAt":"...", "updatedAt":"...", "messages": [ /* solo en detalle */ ] }
```
**`MessageResponse`:** `{ "id":"uuid", "role":"USER|ASSISTANT", "content":"...", "createdAt":"..." }`

**`ChatReplyResponse`:**
```json
{ "conversationId":"uuid", "reply": { /* MessageResponse del asistente */ },
  "inputTokens": 0, "outputTokens": 0 }
```

> El `caseId` liga la conversación a un proceso de legal-core. Para mostrar el "historial de IA del caso", lista con `?caseId=`.

## 4. Streaming (SSE) — IMPORTANTE

Los endpoints `/stream` devuelven `text/event-stream`. Emiten estos eventos:
- `delta` → `{ "text": "<fragmento>" }` (concaténalos para ir mostrando la respuesta)
- `done` → el objeto final completo (`ChatReplyResponse` o `CaseSummaryResponse`)
- `error` → `{ "message": "..." }`

⚠️ **GOTCHA #2:** son **POST con header `Authorization`**. El `EventSource` nativo del navegador **NO sirve** (solo hace GET y no permite headers). Usa **`fetch` + `ReadableStream`** o la librería **`@microsoft/fetch-event-source`**.

**Ejemplo (Angular/TS) con `@microsoft/fetch-event-source`:**
```ts
import { fetchEventSource } from '@microsoft/fetch-event-source';

await fetchEventSource(`${BASE}/api/v1/chat/conversations/${id}/messages/stream`, {
  method: 'POST',
  headers: {
    'Authorization': `Bearer ${token}`,
    'Content-Type': 'application/json',
  },
  body: JSON.stringify({ message }),
  onmessage(ev) {
    if (ev.event === 'delta')      acc += JSON.parse(ev.data).text;   // ir pintando acc
    else if (ev.event === 'done')  final = JSON.parse(ev.data);       // ChatReplyResponse
    else if (ev.event === 'error') showError(JSON.parse(ev.data).message);
  },
  onerror(err) { /* corta el stream / reintento */ throw err; },
});
```
> Timeout del stream: 5 min (alineado con nginx). Mensajes con prompt injection o usuario bloqueado llegan como evento `error` (no como HTTP 422/423, porque el stream ya abrió con 200).

## 5. Conversación con documento (analizar y seguir chateando)

El documento debe vivir en **legal-core/MinIO** (sube primero por los endpoints de archivos de legal-core para obtener `bucket` + `objectKey`).

**Crear conversación a partir del documento:**
```
POST /api/v1/chat/conversations/from-document
{ "bucket":"docs", "objectKey":"ruta/al/archivo.pdf",
  "filename?":"archivo.pdf", "caseId?":"uuid", "instruction?":"Resume el documento" }
```
→ `data` = `ChatReplyResponse` (el análisis es el primer mensaje del asistente; `conversationId` para seguir).

**Seguir preguntando:** usa los endpoints normales de mensajes (§3) con ese `conversationId`. ms-ia re-adjunta el documento automáticamente en cada turno.

- `caseId` es **opcional**: una conversación de documento puede existir sin caso.
- Solo PDF. El documento debe estar en legal-core/MinIO (si es un PDF ad-hoc del PC que no está en legal-core, súbelo primero a legal-core, o usa el análisis one-shot de §7).

## 6. Resumen de caso (SSE)

El **front ensambla** el caso desde lo que ya cargó de legal-core y lo manda:
```
POST /api/v1/cases/summary/stream   → SSE (eventos delta/done/error; done = CaseSummaryResponse)
{
  "caso": { "caseNumber","title","description","caseType","caseStatus","clientName",
            "opposingParty","courtName","courtCaseNumber","openedAt","closedAt",
            "estimatedCloseAt","priority","isConfidential" },
  "notas":       [ { "content","author","createdAt" } ],
  "actividades": [ { "title","description","type","status","dueDate","createdAt" } ],
  "audiencias":  [ { "type","status","scheduledAt","location","result","notes" } ],
  "instruction": "opcional"
}
```
- Solo `caso` es obligatorio; las listas pueden ir vacías/omitidas.
- ⚠️ **GOTCHA #3 (causó un 400):** **todos los campos viajan como texto**, incluido `priority` (manda `"Media"`, no un número) y las fechas como string ISO. No mandes números/objetos donde el contrato dice string.

**`CaseSummaryResponse`** (evento `done`): `{ "caseNumber","title","summary","model","inputTokens","outputTokens" }`

## 7. Análisis de documento one-shot (sin conversación)

Para análisis desechable que **no** necesita continuación:
```
POST /api/v1/documents/analyze        (multipart/form-data: file=<pdf|txt>, instruction?=<texto>)
POST /api/v1/documents/analyze-url     { "url":"<presigned URL>", "instruction?":"..." }   // solo PDF
```
→ `data` = `DocumentAnalysisResponse`: `{ "filename","analysis","model","inputTokens","outputTokens" }`

## 8. Resumen de gotchas
1. **Interceptor:** adjuntar el `Bearer` a las URLs de ms-ia (si no → 401).
2. **SSE:** usar `fetch`/`@microsoft/fetch-event-source` (POST + headers), **no** `EventSource`.
3. **Tipos:** en resumen de caso, todo es texto (`priority="Media"`, fechas string).
4. **423:** si el usuario abusa del asistente (prompt injection reiterado), queda bloqueado un rato; mostrar el `message`.
5. **Documento + chat:** el doc debe estar en legal-core/MinIO (`bucket`+`objectKey`).
