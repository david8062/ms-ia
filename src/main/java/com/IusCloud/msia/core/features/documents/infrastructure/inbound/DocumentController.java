package com.IusCloud.msia.core.features.documents.infrastructure.inbound;

import com.IusCloud.msia.core.features.documents.application.dto.AnalyzeUrlRequest;
import com.IusCloud.msia.core.features.documents.application.dto.DocumentAnalysisResponse;
import com.IusCloud.msia.core.features.documents.application.usecase.DocumentAnalysisUseCase;
import com.IusCloud.msia.shared.responses.ApiResponse;
import com.IusCloud.msia.shared.responses.ResponseUtil;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.multipart.MultipartFile;

@RestController
@RequiredArgsConstructor
@RequestMapping("/api/v1/documents")
public class DocumentController {

    private final DocumentAnalysisUseCase useCase;

    /**
     * Analiza un documento legal (PDF o texto plano).
     *
     * @param file        archivo a analizar (multipart).
     * @param instruction instrucción opcional (qué hacer con el documento).
     *                    Si se omite, se genera un resumen estructurado.
     */
    @PostMapping(value = "/analyze", consumes = MediaType.MULTIPART_FORM_DATA_VALUE)
    public ResponseEntity<ApiResponse<DocumentAnalysisResponse>> analyze(
            @RequestParam("file") MultipartFile file,
            @RequestParam(value = "instruction", required = false) String instruction) {
        return ResponseUtil.ok(useCase.analyze(file, instruction));
    }

    /**
     * Analiza un PDF que ya vive en almacenamiento (p.ej. presigned URL de MinIO
     * emitida por legalCore) sin re-subirlo. Solo PDF. El host de la URL debe
     * estar en {@code documents.download.allowed-hosts}.
     */
    @PostMapping("/analyze-url")
    public ResponseEntity<ApiResponse<DocumentAnalysisResponse>> analyzeUrl(
            @Valid @RequestBody AnalyzeUrlRequest request) {
        return ResponseUtil.ok(useCase.analyzeFromUrl(request.url(), request.instruction()));
    }
}
