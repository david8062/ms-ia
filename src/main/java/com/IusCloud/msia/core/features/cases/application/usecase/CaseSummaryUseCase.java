package com.IusCloud.msia.core.features.cases.application.usecase;

import com.IusCloud.msia.core.common.abuse.AbuseGuardService;
import com.IusCloud.msia.core.common.ai.AiResult;
import com.IusCloud.msia.core.common.ai.AnthropicService;
import com.IusCloud.msia.core.common.ai.LegalPrompts;
import com.IusCloud.msia.core.common.usage.TokenLimitGuardService;
import com.IusCloud.msia.core.common.usage.UsageFeature;
import com.IusCloud.msia.core.common.usage.UsageLogService;
import com.IusCloud.msia.core.features.cases.application.dto.CaseSummaryRequest;
import com.IusCloud.msia.core.features.cases.application.dto.CaseSummaryResponse;
import com.IusCloud.msia.shared.exceptions.BusinessException;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;

import java.util.List;
import java.util.function.Consumer;

@Service
@RequiredArgsConstructor
public class CaseSummaryUseCase {

    private final AnthropicService anthropicService;
    private final UsageLogService usageLogService;
    private final AbuseGuardService abuseGuard;
    private final TokenLimitGuardService tokenGuard;

    /**
     * Genera un resumen del caso en streaming, reenviando cada fragmento vía
     * {@code onDelta}. Al terminar registra el consumo y devuelve el resultado
     * completo. No persiste nada: un resumen es de un solo turno.
     */
    public CaseSummaryResponse streamSummary(CaseSummaryRequest request, Consumer<String> onDelta) {
        abuseGuard.screen(UsageFeature.CASE_SUMMARY, request.instruction());
        tokenGuard.check();
        CaseSummaryRequest.CaseData caso = request.caso();

        String instruction = (request.instruction() == null || request.instruction().isBlank())
                ? LegalPrompts.DEFAULT_CASE_SUMMARY_INSTRUCTION
                : request.instruction();

        String userMessage = instruction
                + "\n\n--- EXPEDIENTE DEL CASO ---\n"
                + buildCaseText(request)
                + "\n--- FIN DEL EXPEDIENTE ---";

        AiResult result = anthropicService.chatStream(
                LegalPrompts.CASE_SUMMARY_SYSTEM, List.of(), userMessage, onDelta);

        usageLogService.record(UsageFeature.CASE_SUMMARY, anthropicService.getModel(), result);

        return new CaseSummaryResponse(
                caso.caseNumber(),
                caso.title(),
                result.text(),
                anthropicService.getModel(),
                result.inputTokens(),
                result.outputTokens());
    }

    // ── Construcción del texto del expediente ──────────────────────────────────

    private String buildCaseText(CaseSummaryRequest request) {
        StringBuilder sb = new StringBuilder();
        CaseSummaryRequest.CaseData c = request.caso();

        sb.append("## INFORMACIÓN GENERAL\n");
        appendField(sb, "Número de caso", c.caseNumber());
        appendField(sb, "Título", c.title());
        appendField(sb, "Descripción", c.description());
        appendField(sb, "Tipo de caso", c.caseType());
        appendField(sb, "Estado", c.caseStatus());
        appendField(sb, "Cliente", c.clientName());
        appendField(sb, "Contraparte", c.opposingParty());
        appendField(sb, "Juzgado", c.courtName());
        appendField(sb, "Radicado del juzgado", c.courtCaseNumber());
        appendField(sb, "Fecha de apertura", c.openedAt());
        appendField(sb, "Fecha de cierre", c.closedAt());
        appendField(sb, "Cierre estimado", c.estimatedCloseAt());
        appendField(sb, "Prioridad", c.priority());
        appendField(sb, "Confidencial", c.isConfidential() != null ? (c.isConfidential() ? "Sí" : "No") : null);

        boolean hasNotes = request.notas() != null && !request.notas().isEmpty();
        boolean hasActivities = request.actividades() != null && !request.actividades().isEmpty();
        boolean hasHearings = request.audiencias() != null && !request.audiencias().isEmpty();

        if (!hasNotes && !hasActivities && !hasHearings
                && isBlank(c.description()) && isBlank(c.caseNumber()) && isBlank(c.title())) {
            throw new BusinessException("Los datos del caso están vacíos; no hay nada que resumir.");
        }

        if (hasNotes) {
            sb.append("\n## NOTAS\n");
            int i = 1;
            for (CaseSummaryRequest.NoteData n : request.notas()) {
                sb.append(i++).append(". ");
                appendInline(sb, n.createdAt());
                appendInline(sb, n.author());
                sb.append(value(n.content())).append("\n");
            }
        }

        if (hasActivities) {
            sb.append("\n## ACTUACIONES / TAREAS\n");
            int i = 1;
            for (CaseSummaryRequest.ActivityData a : request.actividades()) {
                sb.append(i++).append(". ");
                appendInline(sb, a.dueDate() != null ? "vence " + a.dueDate() : a.createdAt());
                appendInline(sb, a.type());
                appendInline(sb, a.status());
                sb.append(value(a.title()));
                if (!isBlank(a.description())) {
                    sb.append(" — ").append(a.description());
                }
                sb.append("\n");
            }
        }

        if (hasHearings) {
            sb.append("\n## AUDIENCIAS\n");
            int i = 1;
            for (CaseSummaryRequest.HearingData h : request.audiencias()) {
                sb.append(i++).append(". ");
                appendInline(sb, h.scheduledAt());
                appendInline(sb, h.type());
                appendInline(sb, h.status());
                if (!isBlank(h.location())) {
                    appendInline(sb, "lugar: " + h.location());
                }
                if (!isBlank(h.result())) {
                    sb.append("resultado: ").append(h.result()).append(". ");
                }
                if (!isBlank(h.notes())) {
                    sb.append(h.notes());
                }
                sb.append("\n");
            }
        }

        return sb.toString();
    }

    private void appendField(StringBuilder sb, String label, String value) {
        if (!isBlank(value)) {
            sb.append("- ").append(label).append(": ").append(value).append("\n");
        }
    }

    /** Antepone un dato entre corchetes si existe (para líneas de lista). */
    private void appendInline(StringBuilder sb, String value) {
        if (!isBlank(value)) {
            sb.append("[").append(value).append("] ");
        }
    }

    private String value(String s) {
        return isBlank(s) ? "(sin contenido)" : s;
    }

    private boolean isBlank(String s) {
        return s == null || s.isBlank();
    }
}
