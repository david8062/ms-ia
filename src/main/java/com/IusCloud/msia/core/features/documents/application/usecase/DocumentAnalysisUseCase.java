package com.IusCloud.msia.core.features.documents.application.usecase;

import com.IusCloud.msia.core.common.abuse.AbuseGuardService;
import com.IusCloud.msia.core.common.ai.AiResult;
import com.IusCloud.msia.core.common.ai.AnthropicService;
import com.IusCloud.msia.core.common.ai.LegalPrompts;
import com.IusCloud.msia.core.common.documents.DocumentFetcher;
import com.IusCloud.msia.core.common.usage.TokenLimitGuardService;
import com.IusCloud.msia.core.common.usage.UsageFeature;
import com.IusCloud.msia.core.common.usage.UsageLogService;
import com.IusCloud.msia.core.features.documents.application.dto.DocumentAnalysisResponse;
import com.IusCloud.msia.shared.exceptions.BusinessException;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.web.multipart.MultipartFile;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.util.Base64;

@Service
@RequiredArgsConstructor
@Slf4j
public class DocumentAnalysisUseCase {

    private final AnthropicService anthropicService;
    private final UsageLogService usageLogService;
    private final DocumentFetcher documentFetcher;
    private final AbuseGuardService abuseGuard;
    private final TokenLimitGuardService tokenGuard;

    public DocumentAnalysisResponse analyze(MultipartFile file, String instruction) {
        abuseGuard.screen(UsageFeature.DOCUMENT_ANALYSIS, instruction);
        tokenGuard.check();
        if (file == null || file.isEmpty()) {
            throw new BusinessException("Debe adjuntar un documento para analizar.");
        }

        String effectiveInstruction = (instruction == null || instruction.isBlank())
                ? LegalPrompts.DEFAULT_DOCUMENT_INSTRUCTION
                : instruction;

        AiResult result = isPdf(file)
                ? analyzePdf(file, effectiveInstruction)
                : analyzeText(file, effectiveInstruction);

        usageLogService.record(UsageFeature.DOCUMENT_ANALYSIS, anthropicService.getModel(), result);

        return new DocumentAnalysisResponse(
                file.getOriginalFilename(),
                result.text(),
                anthropicService.getModel(),
                result.inputTokens(),
                result.outputTokens());
    }

    /**
     * Analiza un PDF que ya vive en almacenamiento externo (presigned URL de
     * MinIO emitida por legalCore) sin re-subirlo: descarga el archivo, valida
     * que sea un PDF y lo envía a Claude. Solo PDF.
     */
    public DocumentAnalysisResponse analyzeFromUrl(String url, String instruction) {
        abuseGuard.screen(UsageFeature.DOCUMENT_ANALYSIS, instruction);
        tokenGuard.check();
        byte[] pdfBytes = documentFetcher.downloadPdf(url);

        String effectiveInstruction = (instruction == null || instruction.isBlank())
                ? LegalPrompts.DEFAULT_DOCUMENT_INSTRUCTION
                : instruction;

        String base64 = Base64.getEncoder().encodeToString(pdfBytes);
        AiResult result = anthropicService.analyzePdf(LegalPrompts.DOCUMENT_SYSTEM, base64, effectiveInstruction);

        usageLogService.record(UsageFeature.DOCUMENT_ANALYSIS, anthropicService.getModel(), result);

        return new DocumentAnalysisResponse(
                documentFetcher.filenameFromUrl(url),
                result.text(),
                anthropicService.getModel(),
                result.inputTokens(),
                result.outputTokens());
    }

    private AiResult analyzePdf(MultipartFile file, String instruction) {
        try {
            String base64 = Base64.getEncoder().encodeToString(file.getBytes());
            return anthropicService.analyzePdf(LegalPrompts.DOCUMENT_SYSTEM, base64, instruction);
        } catch (IOException e) {
            throw new BusinessException("No se pudo leer el archivo PDF: " + e.getMessage());
        }
    }

    private AiResult analyzeText(MultipartFile file, String instruction) {
        if (!isText(file)) {
            throw new BusinessException(
                    "Formato no soportado. Por ahora se admite PDF y texto plano (.txt, .md). "
                            + "El soporte para Word (.docx) se agregará próximamente.");
        }
        try {
            String content = new String(file.getBytes(), StandardCharsets.UTF_8);
            if (content.isBlank()) {
                throw new BusinessException("El documento está vacío.");
            }
            return anthropicService.analyzeText(LegalPrompts.DOCUMENT_SYSTEM, content, instruction);
        } catch (IOException e) {
            throw new BusinessException("No se pudo leer el archivo: " + e.getMessage());
        }
    }

    private boolean isPdf(MultipartFile file) {
        String contentType = file.getContentType();
        String name = file.getOriginalFilename();
        return "application/pdf".equalsIgnoreCase(contentType)
                || (name != null && name.toLowerCase().endsWith(".pdf"));
    }

    private boolean isText(MultipartFile file) {
        String contentType = file.getContentType();
        String name = file.getOriginalFilename();
        if (contentType != null && contentType.startsWith("text/")) {
            return true;
        }
        if (name == null) {
            return false;
        }
        String lower = name.toLowerCase();
        return lower.endsWith(".txt") || lower.endsWith(".md") || lower.endsWith(".csv");
    }
}
