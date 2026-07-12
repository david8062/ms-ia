package com.IusCloud.msia.core.features.cases.application.dto;

import jakarta.validation.Valid;
import jakarta.validation.constraints.NotNull;

import java.util.List;

/**
 * Datos de un caso (ensamblados por el frontend desde legalCore) para generar
 * un resumen con IA. Las fechas se reciben como texto (ISO u otro) y se incrustan
 * tal cual en el prompt, sin parsearlas, para no acoplar formatos.
 *
 * @param caso         información general del caso (obligatoria).
 * @param notas        notas del caso (opcional).
 * @param actividades  actuaciones/tareas del caso (opcional).
 * @param audiencias   audiencias del caso (opcional).
 * @param instruction  qué resumen se quiere; si se omite, se usa el resumen ejecutivo por defecto.
 */
public record CaseSummaryRequest(
        @NotNull(message = "Los datos del caso son obligatorios") @Valid CaseData caso,
        List<NoteData> notas,
        List<ActivityData> actividades,
        List<HearingData> audiencias,
        String instruction
) {
    public record CaseData(
            String caseNumber,
            String title,
            String description,
            String caseType,
            String caseStatus,
            String clientName,
            String opposingParty,
            String courtName,
            String courtCaseNumber,
            String openedAt,
            String closedAt,
            String estimatedCloseAt,
            String priority,
            Boolean isConfidential
    ) {}

    public record NoteData(
            String content,
            String author,
            String createdAt
    ) {}

    public record ActivityData(
            String title,
            String description,
            String type,
            String status,
            String dueDate,
            String createdAt
    ) {}

    public record HearingData(
            String type,
            String status,
            String scheduledAt,
            String location,
            String result,
            String notes
    ) {}
}
