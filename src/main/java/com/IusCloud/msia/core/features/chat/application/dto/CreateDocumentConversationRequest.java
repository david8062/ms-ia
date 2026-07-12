package com.IusCloud.msia.core.features.chat.application.dto;

import jakarta.validation.constraints.NotBlank;

import java.util.UUID;

/**
 * Inicia una conversación a partir de un documento que vive en legalCore/MinIO.
 * ms-ia descarga el PDF por {@code bucket}+{@code objectKey} (presigned URL fresca
 * de legalCore), lo analiza y deja la conversación lista para seguir preguntando.
 *
 * @param bucket      bucket de MinIO donde está el documento.
 * @param objectKey   llave del objeto (referencia estable).
 * @param filename    nombre para mostrar (opcional).
 * @param caseId      caso de legalCore al que se liga la conversación (opcional).
 * @param instruction qué hacer con el documento; si se omite, resumen por defecto.
 */
public record CreateDocumentConversationRequest(
        @NotBlank(message = "El bucket es obligatorio") String bucket,
        @NotBlank(message = "El objectKey es obligatorio") String objectKey,
        String filename,
        UUID caseId,
        String instruction
) {
}
