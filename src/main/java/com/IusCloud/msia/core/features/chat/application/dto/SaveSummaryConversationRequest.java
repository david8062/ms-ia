package com.IusCloud.msia.core.features.chat.application.dto;

import jakarta.validation.constraints.NotBlank;

import java.util.UUID;

/**
 * Guarda un resumen de caso YA GENERADO como una conversación del expediente.
 *
 * <p>El resumen se produce por streaming y hasta ahora moría en la pantalla: al recargar se
 * perdía. En vez de inventar un almacén nuevo, se persiste como conversación ligada al caso —
 * el mismo sitio donde el abogado ya busca lo de IA — y así además queda <b>continuable</b>:
 * puede seguir preguntando sobre el resumen sin regenerarlo.
 *
 * <p>No vuelve a llamar al modelo: el texto ya está pagado y generado.
 *
 * @param caseId      caso de legalCore al que se liga (obligatorio: sin él no es "del caso").
 * @param summary     el texto del resumen, tal como lo recibió el usuario.
 * @param instruction qué se le pidió; se guarda como el mensaje del usuario para que la
 *                    conversación se lea natural y los follow-ups tengan contexto.
 * @param title       título de la conversación; si viene vacío se genera uno.
 */
public record SaveSummaryConversationRequest(
        UUID caseId,
        @NotBlank(message = "El resumen no puede estar vacío") String summary,
        String instruction,
        String title
) {
}
