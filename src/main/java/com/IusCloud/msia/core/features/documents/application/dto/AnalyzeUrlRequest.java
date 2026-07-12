package com.IusCloud.msia.core.features.documents.application.dto;

import jakarta.validation.constraints.NotBlank;

/**
 * Petición para analizar un PDF que ya vive en almacenamiento (p.ej. una
 * presigned URL de MinIO emitida por legalCore), sin re-subir el archivo.
 *
 * @param url         URL http/https del PDF a descargar y analizar.
 * @param instruction instrucción opcional; si se omite, se genera un resumen.
 */
public record AnalyzeUrlRequest(
        @NotBlank(message = "La url es obligatoria") String url,
        String instruction
) {
}
