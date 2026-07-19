package com.IusCloud.msia.core.features.assistant.application;

import com.IusCloud.msia.core.common.abuse.AbuseGuardService;
import com.IusCloud.msia.core.common.ai.AiResult;
import com.IusCloud.msia.core.common.ai.AnthropicService;
import com.IusCloud.msia.core.common.usage.TokenLimitGuardService;
import com.IusCloud.msia.core.common.usage.UsageFeature;
import com.IusCloud.msia.core.common.usage.UsageLogService;
import com.IusCloud.msia.core.features.assistant.infrastructure.AuthIdentityClient;
import com.IusCloud.msia.core.features.assistant.infrastructure.AuthIdentityClient.ResolvedIdentity;
import com.IusCloud.msia.core.features.assistant.application.DocumentSelectionCache.CachedDoc;
import com.IusCloud.msia.core.features.assistant.infrastructure.LegalCoreAssistantClient;
import com.IusCloud.msia.core.features.assistant.infrastructure.LegalCoreAssistantClient.CaseStatusDTO;
import com.IusCloud.msia.core.features.assistant.infrastructure.LegalCoreAssistantClient.DocumentDTO;
import com.IusCloud.msia.core.features.assistant.infrastructure.LegalCoreAssistantClient.HearingDTO;
import com.IusCloud.msia.core.features.assistant.infrastructure.LegalCoreAssistantClient.TaskDTO;
import com.IusCloud.msia.shared.tenant.TenantContext;
import com.IusCloud.msia.shared.tenant.UserContext;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

import java.time.LocalDate;
import java.time.LocalTime;
import java.time.OffsetDateTime;
import java.time.ZoneId;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;

/**
 * Cerebro del asistente por WhatsApp ("secretaria virtual"). Un mensaje entra, sale una respuesta.
 *
 * <p>Diseño: <b>router de intención</b> (una llamada a Claude clasifica el mensaje y extrae
 * parámetros) → consulta a legal-core en Java → texto armado en Java. Así el modelo NUNCA inventa
 * datos (solo entiende la pregunta) y hay una sola llamada de IA por mensaje (barato).
 */
@Service
@RequiredArgsConstructor
@Slf4j
public class WhatsAppAssistantUseCase {

    private final AnthropicService anthropicService;
    private final UsageLogService usageLogService;
    private final AbuseGuardService abuseGuard;
    private final TokenLimitGuardService tokenGuard;
    private final AuthIdentityClient authIdentityClient;
    private final LegalCoreAssistantClient legalCoreClient;
    private final DocumentSelectionCache docCache;
    private final RecentContactCache recentContactCache;

    /** Teléfono del dueño para avisarle de leads (números sin cuenta). Vacío = no avisar. */
    @org.springframework.beans.factory.annotation.Value("${assistant.leads.notify-phone:}")
    private String leadsNotifyPhone;

    private final ObjectMapper objectMapper = new ObjectMapper();

    private static final ZoneId ZONE = ZoneId.of("America/Bogota");
    private static final DateTimeFormatter DISPLAY = DateTimeFormatter.ofPattern("dd/MM/yyyy HH:mm");

    private static final String CAPABILITIES =
            "👋 Soy tu asistente de *IusCloud*. Puedo ayudarte con:\n\n"
            + "• 📅 *Tus audiencias* — \"¿qué audiencias tengo mañana?\"\n"
            + "• ✅ *Tus tareas pendientes* — \"¿qué tengo pendiente?\"\n"
            + "• ⚖️ *Estado de un proceso* — \"¿cómo va el 11001...?\"\n"
            + "• 📎 *Documentos de un proceso* — \"mándame los documentos del 11001...\"\n\n"
            + "¿En qué te ayudo?";

    private static final String ROUTER_SYSTEM = """
            Eres el enrutador del asistente por WhatsApp de IusCloud (plataforma para abogados en Colombia).
            Clasifica el mensaje del abogado en UNA intención y extrae parámetros.
            Responde ÚNICAMENTE con un objeto JSON válido, sin texto adicional y sin ```.

            Esquema: {"intent": "HEARINGS"|"TASKS"|"CASE_STATUS"|"DOCUMENTS"|"OTHER", "range": "TODAY"|"TOMORROW"|"WEEK"|null, "radicado": string|null, "selection": string|null}

            - HEARINGS: pregunta por audiencias/diligencias. range = a qué fecha se refiere
              (hoy=TODAY, mañana=TOMORROW, esta semana o próximos días=WEEK; si no especifica, TODAY).
            - TASKS: pregunta por tareas/pendientes/actividades.
            - CASE_STATUS: pregunta por el estado o avance de un proceso. radicado = solo los dígitos
              del radicado si los menciona; si no da radicado, null.
            - DOCUMENTS: pide los documentos/archivos de un proceso, O elige de una lista que se le
              acaba de mostrar. radicado = dígitos del radicado si los menciona (para LISTAR).
              selection = qué documentos quiere de la lista previa: "all" para todos; o los números
              separados por coma ("1" o "1,3"); null si no está eligiendo.
            - OTHER: saludo, agradecimiento, algo fuera de alcance, o no está claro.

            Ejemplos:
            "¿qué audiencias tengo mañana?" -> {"intent":"HEARINGS","range":"TOMORROW","radicado":null,"selection":null}
            "audiencias de esta semana" -> {"intent":"HEARINGS","range":"WEEK","radicado":null,"selection":null}
            "mis pendientes" -> {"intent":"TASKS","range":null,"radicado":null,"selection":null}
            "cómo va el 11001310300120230037901" -> {"intent":"CASE_STATUS","range":null,"radicado":"11001310300120230037901","selection":null}
            "mándame los documentos del proceso 11001310300120230037901" -> {"intent":"DOCUMENTS","range":null,"radicado":"11001310300120230037901","selection":null}
            "documentos del 11001310300120230037901" -> {"intent":"DOCUMENTS","range":null,"radicado":"11001310300120230037901","selection":null}
            "todos" -> {"intent":"DOCUMENTS","range":null,"radicado":null,"selection":"all"}
            "el 2" -> {"intent":"DOCUMENTS","range":null,"radicado":null,"selection":"2"}
            "mándame el 1 y el 3" -> {"intent":"DOCUMENTS","range":null,"radicado":null,"selection":"1,3"}
            "hola" -> {"intent":"OTHER","range":null,"radicado":null,"selection":null}
            """;

    /**
     * Procesa un mensaje entrante y devuelve el texto a enviar por WhatsApp.
     * Nunca lanza: cualquier fallo se traduce en un mensaje seguro para el usuario.
     */
    public AssistantResult handle(String phone, String text) {
        if (text == null || text.isBlank()) {
            return AssistantResult.text(CAPABILITIES);
        }

        Optional<ResolvedIdentity> identity = authIdentityClient.resolveByPhone(phone);
        if (identity.isEmpty()) {
            // SILENCIO TOTAL a los números sin cuenta. Responderle a un no-contacto (aunque sea una
            // sola vez) es justo el patrón por el que WhatsApp restringió la línea: la cuenta se ve
            // escribiéndole a gente que nunca la agendó. El asistente solo habla con usuarios ya
            // registrados, que son quienes iniciaron la relación.
            //
            // El aviso de lead al DUEÑO sí se conserva (va a un número propio, no al desconocido),
            // acotado por la caché para no repetirlo con cada mensaje del mismo número.
            if (!recentContactCache.greetedRecently(phone)) {
                recentContactCache.markGreeted(phone);
                if (leadsNotifyPhone != null && !leadsNotifyPhone.isBlank()) {
                    return new AssistantResult("", List.of(), new LeadNotify(leadsNotifyPhone,
                            "📥 *IusCloud* — un número sin cuenta escribió al asistente.\n"
                            + "Posible interesado: +" + phone + "\nEscríbele tú para contarle."));
                }
            }
            return AssistantResult.silent();
        }
        ResolvedIdentity id = identity.get();

        try {
            TenantContext.setTenantId(id.tenantId());
            UserContext.setUserId(id.externalAuthId());

            // Guardas (anti prompt-injection + tope mensual de IA). Ante bloqueo, mensaje seguro.
            try {
                abuseGuard.screen(UsageFeature.ASSISTANT, text);
            } catch (Exception e) {
                log.info("Mensaje bloqueado por el guarda de abuso para tenant {}: {}",
                        id.tenantId(), e.getMessage());
                return AssistantResult.text(
                        "No puedo procesar ese mensaje. ¿Te ayudo con tus audiencias, tareas, "
                        + "el estado o los documentos de un proceso?");
            }
            try {
                tokenGuard.check();
            } catch (Exception e) {
                return AssistantResult.text(
                        "Alcanzaste el límite de IA de tu plan este mes. Se reinicia el primer día del mes. "
                        + "Puedes ampliarlo desde IusCloud.");
            }

            Intent intent = classify(text, id);
            return switch (intent.intent()) {
                case "HEARINGS" -> AssistantResult.text(replyHearings(id, intent.range()));
                case "TASKS" -> AssistantResult.text(replyTasks(id));
                case "CASE_STATUS" -> AssistantResult.text(replyCase(id, intent.radicado()));
                case "DOCUMENTS" -> replyDocuments(id, phone, intent.radicado(), intent.selection());
                default -> AssistantResult.text(CAPABILITIES);
            };
        } catch (Exception e) {
            log.error("Fallo atendiendo el asistente para tenant {}: {}", id.tenantId(), e.getMessage(), e);
            return AssistantResult.text("Tuve un problema consultando tu información. Intenta de nuevo en un momento.");
        } finally {
            TenantContext.clear();
            UserContext.clear();
        }
    }

    // ── clasificación ──────────────────────────────────────────────────────────

    private Intent classify(String text, ResolvedIdentity id) {
        AiResult result = anthropicService.chat(ROUTER_SYSTEM, List.of(), text);
        usageLogService.record(UsageFeature.ASSISTANT, anthropicService.getModel(), result);
        try {
            String json = extractJson(result.text());
            JsonNode node = objectMapper.readTree(json);
            String intent = node.path("intent").asText("OTHER");
            String range = node.path("range").isNull() ? null : node.path("range").asText(null);
            String radicado = node.path("radicado").isNull() ? null : node.path("radicado").asText(null);
            String selection = node.path("selection").isNull() ? null : node.path("selection").asText(null);
            return new Intent(intent, range, radicado, selection);
        } catch (Exception e) {
            log.info("No se pudo parsear la intención (tenant {}): '{}'", id.tenantId(), result.text());
            return new Intent("OTHER", null, null, null);
        }
    }

    private static String extractJson(String raw) {
        if (raw == null) return "{}";
        int start = raw.indexOf('{');
        int end = raw.lastIndexOf('}');
        return (start >= 0 && end > start) ? raw.substring(start, end + 1) : "{}";
    }

    // ── respuestas ─────────────────────────────────────────────────────────────

    private String replyHearings(ResolvedIdentity id, String range) {
        LocalDate today = LocalDate.now(ZONE);
        LocalDate startDate;
        LocalDate endDate;
        String etiqueta;
        switch (range == null ? "TODAY" : range) {
            case "TOMORROW" -> { startDate = today.plusDays(1); endDate = startDate; etiqueta = "mañana"; }
            case "WEEK" -> { startDate = today; endDate = today.plusDays(7); etiqueta = "los próximos días"; }
            default -> { startDate = today; endDate = today; etiqueta = "hoy"; }
        }
        OffsetDateTime from = startDate.atStartOfDay(ZONE).toOffsetDateTime();
        OffsetDateTime to = endDate.atTime(LocalTime.MAX).atZone(ZONE).toOffsetDateTime();

        List<HearingDTO> hearings = legalCoreClient.hearings(id.tenantId(), from, to);
        if (hearings.isEmpty()) {
            return "✅ No tienes audiencias programadas para " + etiqueta + ".";
        }
        StringBuilder sb = new StringBuilder("⚖️ *Tus audiencias para ").append(etiqueta).append(":*\n");
        for (HearingDTO h : hearings) {
            sb.append("\n📅 ").append(formatDateTime(h.scheduledAt()));
            if (h.caseTitle() != null) sb.append("\n📁 ").append(h.caseTitle());
            if (h.title() != null) sb.append("\n").append(h.title());
            if (h.location() != null && !h.location().isBlank()) sb.append("\n📍 ").append(h.location());
            else if (h.virtualLink() != null && !h.virtualLink().isBlank()) sb.append("\n💻 ").append(h.virtualLink());
            sb.append("\n");
        }
        return sb.toString().trim();
    }

    private String replyTasks(ResolvedIdentity id) {
        List<TaskDTO> tasks = legalCoreClient.tasks(id.tenantId());
        if (tasks.isEmpty()) {
            return "✅ No tienes tareas pendientes. ¡Al día!";
        }
        StringBuilder sb = new StringBuilder("✅ *Tus tareas pendientes:*\n");
        for (TaskDTO t : tasks) {
            sb.append("\n• ").append(t.title());
            if (t.activityDate() != null) sb.append(" — ").append(formatDateTime(t.activityDate()));
            if (t.caseTitle() != null) sb.append("\n  📁 ").append(t.caseTitle());
            sb.append("\n");
        }
        return sb.toString().trim();
    }

    private String replyCase(ResolvedIdentity id, String radicado) {
        if (radicado == null || radicado.isBlank()) {
            return "¿De qué proceso quieres el estado? Envíame el número de radicado.";
        }
        Optional<CaseStatusDTO> maybe = legalCoreClient.caseStatus(id.tenantId(), radicado);
        if (maybe.isEmpty()) {
            return "No encuentro ese proceso en tu seguimiento. Verifica el radicado o actívalo en IusCloud.";
        }
        CaseStatusDTO c = maybe.get();
        StringBuilder sb = new StringBuilder("⚖️ *Proceso ").append(c.radicado()).append("*\n");
        if (c.latestActuacion() != null) {
            sb.append("\n🔔 *Última actuación:*");
            if (c.latestActuacion().fechaActuacion() != null) {
                sb.append("\n📅 ").append(formatDate(c.latestActuacion().fechaActuacion()));
            }
            if (c.latestActuacion().actuacion() != null) {
                sb.append("\n📝 ").append(c.latestActuacion().actuacion().trim());
            }
            if (c.latestActuacion().anotacion() != null && !c.latestActuacion().anotacion().isBlank()) {
                sb.append("\n🗒️ ").append(c.latestActuacion().anotacion().trim());
            }
        } else {
            sb.append("\nAún no hay actuaciones registradas en el seguimiento.");
        }
        sb.append("\n\nIngresa a IusCloud para ver el detalle completo.");
        return sb.toString();
    }

    private AssistantResult replyDocuments(ResolvedIdentity id, String phone, String radicado, String selection) {
        // Caso 1: está eligiendo de una lista que se le mostró antes ("todos" / "el 2").
        if ((radicado == null || radicado.isBlank()) && selection != null && !selection.isBlank()) {
            List<CachedDoc> cached = docCache.get(phone);
            if (cached == null || cached.isEmpty()) {
                return AssistantResult.text(
                        "¿De qué proceso quieres los documentos? Envíame el número de radicado.");
            }
            List<CachedDoc> chosen = resolveSelection(cached, selection);
            if (chosen.isEmpty()) {
                return AssistantResult.text(
                        "No entendí cuál documento. Responde con el número (ej: *1*) o *todos*.");
            }
            docCache.clear(phone);
            List<MediaItem> media = chosen.stream()
                    .map(d -> new MediaItem(d.url(), d.filename(), d.mimeType()))
                    .toList();
            String reply = chosen.size() == 1
                    ? "📎 Aquí está tu documento:"
                    : "📎 Aquí están tus " + chosen.size() + " documentos:";
            return new AssistantResult(reply, media, null);
        }

        // Caso 2: pide los documentos de un proceso → listar y cachear para el siguiente mensaje.
        if (radicado == null || radicado.isBlank()) {
            return AssistantResult.text("¿De qué proceso quieres los documentos? Envíame el número de radicado.");
        }
        List<DocumentDTO> docs = legalCoreClient.documents(id.tenantId(), radicado);
        if (docs.isEmpty()) {
            return AssistantResult.text(
                    "No encuentro documentos para ese proceso. Verifica el radicado o súbelos en IusCloud.");
        }
        docCache.put(phone, docs.stream().map(d -> new CachedDoc(d.name(), d.mimeType(), d.url())).toList());

        StringBuilder sb = new StringBuilder("📎 El proceso tiene ")
                .append(docs.size()).append(docs.size() == 1 ? " documento:\n" : " documentos:\n");
        for (int i = 0; i < docs.size(); i++) {
            sb.append("\n").append(i + 1).append(". ").append(docs.get(i).name());
        }
        sb.append("\n\nResponde con el número (ej: *1* o *1,3*) o *todos*, y te los mando.");
        return AssistantResult.text(sb.toString());
    }

    /** Resuelve "all"/"todos" o índices 1-based ("1", "1,3") contra el listado cacheado. */
    private List<CachedDoc> resolveSelection(List<CachedDoc> docs, String selection) {
        String s = selection.trim().toLowerCase();
        if (s.equals("all") || s.equals("todos") || s.equals("todas")) {
            return docs;
        }
        List<CachedDoc> chosen = new ArrayList<>();
        for (String part : s.split("[,\\s]+")) {
            String digits = part.replaceAll("[^0-9]", "");
            if (digits.isEmpty()) {
                continue;
            }
            try {
                int idx = Integer.parseInt(digits) - 1;
                if (idx >= 0 && idx < docs.size() && !chosen.contains(docs.get(idx))) {
                    chosen.add(docs.get(idx));
                }
            } catch (NumberFormatException ignored) {
                // parte no numérica: se ignora
            }
        }
        return chosen;
    }

    // ── formato de fechas ──────────────────────────────────────────────────────

    private String formatDateTime(String iso) {
        if (iso == null) return "Fecha no disponible";
        try {
            return OffsetDateTime.parse(iso).atZoneSameInstant(ZONE).format(DISPLAY);
        } catch (Exception e) {
            try {
                // Algunos campos vienen como LocalDateTime (sin offset).
                return java.time.LocalDateTime.parse(iso).format(DISPLAY);
            } catch (Exception ignored) {
                return iso;
            }
        }
    }

    private String formatDate(String iso) {
        if (iso == null) return "Fecha no disponible";
        try {
            return java.time.Instant.parse(iso).atZone(ZONE)
                    .format(DateTimeFormatter.ofPattern("dd/MM/yyyy"));
        } catch (Exception e) {
            return formatDateTime(iso);
        }
    }

    private record Intent(String intent, String range, String radicado, String selection) {}

    /** Un archivo a enviar por WhatsApp (URL de descarga + nombre + tipo MIME). */
    public record MediaItem(String url, String filename, String mimeType) {}

    /** Aviso al dueño de un lead (número que escribió sin tener cuenta). */
    public record LeadNotify(String phone, String text) {}

    /** Respuesta del asistente: texto + archivos + aviso de lead, todos opcionales. */
    public record AssistantResult(String reply, List<MediaItem> media, LeadNotify leadNotify) {
        static AssistantResult text(String reply) {
            return new AssistantResult(reply, List.of(), null);
        }

        /** Sin respuesta: no se envía nada (para no spamear a un no-contacto ya saludado). */
        static AssistantResult silent() {
            return new AssistantResult("", List.of(), null);
        }
    }
}
