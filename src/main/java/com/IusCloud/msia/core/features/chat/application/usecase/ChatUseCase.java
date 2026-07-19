package com.IusCloud.msia.core.features.chat.application.usecase;

import com.IusCloud.msia.core.common.abuse.AbuseGuardService;
import com.IusCloud.msia.core.common.ai.AiResult;
import com.IusCloud.msia.core.common.ai.AnthropicService;
import com.IusCloud.msia.core.common.ai.ChatTurn;
import com.IusCloud.msia.core.common.ai.LegalPrompts;
import com.IusCloud.msia.core.common.documents.DocumentFetcher;
import com.IusCloud.msia.core.common.legalcore.LegalCoreClient;
import com.IusCloud.msia.core.common.usage.TokenLimitGuardService;
import com.IusCloud.msia.core.common.usage.UsageFeature;
import com.IusCloud.msia.core.common.usage.UsageLogService;
import com.IusCloud.msia.core.features.chat.application.dto.ChatReplyResponse;
import com.IusCloud.msia.core.features.chat.application.dto.ConversationResponse;
import com.IusCloud.msia.core.features.chat.application.dto.CreateConversationRequest;
import com.IusCloud.msia.core.features.chat.application.dto.CreateDocumentConversationRequest;
import com.IusCloud.msia.core.features.chat.application.dto.MessageResponse;
import com.IusCloud.msia.core.features.chat.application.dto.SaveSummaryConversationRequest;
import com.IusCloud.msia.core.features.chat.domain.model.ConversationEntity;
import com.IusCloud.msia.core.features.chat.domain.model.MessageEntity;
import com.IusCloud.msia.core.features.chat.domain.model.MessageRole;
import com.IusCloud.msia.core.features.chat.infrastructure.persistence.ConversationJpaRepository;
import com.IusCloud.msia.core.features.chat.infrastructure.persistence.MessageJpaRepository;
import com.IusCloud.msia.shared.exceptions.ResourceNotFoundException;
import com.IusCloud.msia.shared.tenant.TenantContext;
import com.IusCloud.msia.shared.tenant.UserContext;
import lombok.RequiredArgsConstructor;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.Instant;
import java.util.ArrayList;
import java.util.Base64;
import java.util.List;
import java.util.UUID;
import java.util.function.Consumer;

@Service
@RequiredArgsConstructor
public class ChatUseCase {

    private final ConversationJpaRepository conversationRepository;
    private final MessageJpaRepository messageRepository;
    private final AnthropicService anthropicService;
    private final UsageLogService usageLogService;
    private final AbuseGuardService abuseGuard;
    private final TokenLimitGuardService tokenGuard;
    private final LegalCoreClient legalCoreClient;
    private final DocumentFetcher documentFetcher;

    /**
     * Máximo de mensajes previos que se reenvían como contexto. Acota el costo:
     * sin tope, cada turno reenvía todo el historial y el costo crece de forma
     * cuadrática a lo largo de la conversación. 0 o negativo = sin tope.
     */
    @Value("${chat.history.max-messages:20}")
    private int maxHistoryMessages;

    @Transactional
    public ConversationResponse createConversation(CreateConversationRequest request) {
        ConversationEntity conversation = new ConversationEntity();
        conversation.setTenantId(TenantContext.getTenantId());
        conversation.setUserId(UserContext.getUserId());
        conversation.setTitle(request != null ? request.title() : null);
        conversation.setCaseId(request != null ? request.caseId() : null);
        conversationRepository.save(conversation);
        return ConversationResponse.summary(conversation);
    }

    /**
     * Persiste un resumen de caso ya generado como conversación del expediente.
     *
     * <p>Deliberadamente NO llama al modelo: el texto llega hecho desde el streaming que el
     * usuario acaba de ver, así que guardar es gratis. Se escriben dos mensajes (la petición y
     * el resumen) para que la conversación se lea natural y, sobre todo, para que los mensajes
     * de seguimiento hereden el contexto por el camino normal de {@code buildHistory}.
     */
    @Transactional
    public ConversationResponse saveSummaryAsConversation(SaveSummaryConversationRequest request) {
        ConversationEntity conversation = new ConversationEntity();
        conversation.setTenantId(TenantContext.getTenantId());
        conversation.setUserId(UserContext.getUserId());
        conversation.setCaseId(request.caseId());
        conversation.setTitle(
                request.title() != null && !request.title().isBlank()
                        ? request.title()
                        : "Resumen del caso");
        conversationRepository.save(conversation);

        String asked = request.instruction() != null && !request.instruction().isBlank()
                ? request.instruction()
                : "Genera un resumen del caso.";

        MessageEntity userMsg = new MessageEntity();
        userMsg.setConversationId(conversation.getId());
        userMsg.setRole(MessageRole.USER);
        userMsg.setContent(asked);
        messageRepository.save(userMsg);

        MessageEntity assistantMsg = new MessageEntity();
        assistantMsg.setConversationId(conversation.getId());
        assistantMsg.setRole(MessageRole.ASSISTANT);
        assistantMsg.setContent(request.summary());
        messageRepository.save(assistantMsg);

        return ConversationResponse.summary(conversation);
    }

    @Transactional(readOnly = true)
    public List<ConversationResponse> listConversations(UUID caseId) {
        UUID tenantId = TenantContext.getTenantId();
        UUID userId = UserContext.getUserId();
        List<ConversationEntity> conversations = (caseId == null)
                ? conversationRepository.findByTenantIdAndUserIdAndDeletedAtIsNullOrderByCreatedAtDesc(
                        tenantId, userId)
                : conversationRepository.findByTenantIdAndUserIdAndCaseIdAndDeletedAtIsNullOrderByCreatedAtDesc(
                        tenantId, userId, caseId);
        return conversations.stream()
                .map(ConversationResponse::summary)
                .toList();
    }

    @Transactional(readOnly = true)
    public ConversationResponse getConversation(UUID conversationId) {
        ConversationEntity conversation = loadOwnedConversation(conversationId);
        List<MessageResponse> messages = messageRepository
                .findByConversationIdOrderByCreatedAtAsc(conversation.getId())
                .stream()
                .map(MessageResponse::from)
                .toList();
        return ConversationResponse.detail(conversation, messages);
    }

    @Transactional
    public ChatReplyResponse sendMessage(UUID conversationId, String userMessage) {
        abuseGuard.screen(UsageFeature.CHAT, userMessage);
        tokenGuard.check();
        ConversationEntity conversation = loadOwnedConversation(conversationId);

        // Historial previo (acotado) como contexto para el modelo.
        List<ChatTurn> history = buildHistory(conversation.getId());

        // Persistir el mensaje del usuario.
        MessageEntity userMsg = new MessageEntity();
        userMsg.setConversationId(conversation.getId());
        userMsg.setRole(MessageRole.USER);
        userMsg.setContent(userMessage);
        messageRepository.save(userMsg);

        // Invocar a Claude (con documento adjunto si la conversación lo tiene).
        AiResult result = invokeModel(conversation, history, userMessage, null);

        // Persistir la respuesta del asistente.
        MessageEntity assistantMsg = new MessageEntity();
        assistantMsg.setConversationId(conversation.getId());
        assistantMsg.setRole(MessageRole.ASSISTANT);
        assistantMsg.setContent(result.text());
        assistantMsg.setInputTokens(result.inputTokens());
        assistantMsg.setOutputTokens(result.outputTokens());
        messageRepository.save(assistantMsg);

        // Registrar consumo.
        usageLogService.record(UsageFeature.CHAT, anthropicService.getModel(), result);

        // Título automático a partir del primer mensaje.
        if (conversation.getTitle() == null || conversation.getTitle().isBlank()) {
            conversation.setTitle(buildTitle(userMessage));
        }
        conversation.setUpdatedAt(Instant.now());
        conversationRepository.save(conversation);

        return new ChatReplyResponse(
                conversation.getId(),
                MessageResponse.from(assistantMsg),
                result.inputTokens(),
                result.outputTokens());
    }

    /**
     * Variante en streaming de {@link #sendMessage}. Persiste el mensaje del
     * usuario, invoca a Claude en streaming reenviando cada fragmento de texto
     * vía {@code onDelta}, y al terminar persiste la respuesta completa, registra
     * el consumo y devuelve el {@link ChatReplyResponse} final.
     *
     * <p>No es transaccional a propósito: la llamada al modelo puede tardar
     * decenas de segundos y no queremos mantener una transacción/conexión de BD
     * abierta todo ese tiempo. Cada {@code save} corre en su propia transacción.
     */
    public ChatReplyResponse streamMessage(UUID conversationId, String userMessage, Consumer<String> onDelta) {
        abuseGuard.screen(UsageFeature.CHAT, userMessage);
        tokenGuard.check();
        ConversationEntity conversation = loadOwnedConversation(conversationId);

        // Historial previo (acotado) como contexto para el modelo.
        List<ChatTurn> history = buildHistory(conversation.getId());

        // Persistir el mensaje del usuario.
        MessageEntity userMsg = new MessageEntity();
        userMsg.setConversationId(conversation.getId());
        userMsg.setRole(MessageRole.USER);
        userMsg.setContent(userMessage);
        messageRepository.save(userMsg);

        // Invocar a Claude en streaming (con documento adjunto si la conversación lo tiene).
        AiResult result = invokeModel(conversation, history, userMessage, onDelta);

        // Persistir la respuesta completa del asistente.
        MessageEntity assistantMsg = new MessageEntity();
        assistantMsg.setConversationId(conversation.getId());
        assistantMsg.setRole(MessageRole.ASSISTANT);
        assistantMsg.setContent(result.text());
        assistantMsg.setInputTokens(result.inputTokens());
        assistantMsg.setOutputTokens(result.outputTokens());
        messageRepository.save(assistantMsg);

        // Registrar consumo.
        usageLogService.record(UsageFeature.CHAT, anthropicService.getModel(), result);

        // Título automático a partir del primer mensaje.
        if (conversation.getTitle() == null || conversation.getTitle().isBlank()) {
            conversation.setTitle(buildTitle(userMessage));
        }
        conversation.setUpdatedAt(Instant.now());
        conversationRepository.save(conversation);

        return new ChatReplyResponse(
                conversation.getId(),
                MessageResponse.from(assistantMsg),
                result.inputTokens(),
                result.outputTokens());
    }

    /**
     * Crea una conversación a partir de un documento de legalCore/MinIO: descarga
     * el PDF (presigned URL fresca de legalCore), lo analiza, y deja el análisis
     * como primer mensaje. La conversación queda con la referencia del documento
     * para poder seguir preguntando sobre él, y con {@code caseId} si se ligó a un caso.
     */
    @Transactional
    public ChatReplyResponse createFromDocument(CreateDocumentConversationRequest request) {
        abuseGuard.screen(UsageFeature.DOCUMENT_ANALYSIS, request.instruction());
        tokenGuard.check();

        // Descargar el documento (presigned URL fresca de legalCore) y analizar.
        String base64 = fetchDocument(request.bucket(), request.objectKey());
        String instruction = (request.instruction() == null || request.instruction().isBlank())
                ? LegalPrompts.DEFAULT_DOCUMENT_INSTRUCTION
                : request.instruction();

        // Crear la conversación con la referencia del documento.
        ConversationEntity conversation = new ConversationEntity();
        conversation.setTenantId(TenantContext.getTenantId());
        conversation.setUserId(UserContext.getUserId());
        conversation.setCaseId(request.caseId());
        conversation.setDocumentBucket(request.bucket());
        conversation.setDocumentObjectKey(request.objectKey());
        conversation.setDocumentFilename(request.filename());
        conversation.setTitle(request.filename() != null && !request.filename().isBlank()
                ? buildTitle(request.filename())
                : "Análisis de documento");
        conversationRepository.save(conversation);

        // La instrucción del análisis queda como primer mensaje de usuario.
        MessageEntity userMsg = new MessageEntity();
        userMsg.setConversationId(conversation.getId());
        userMsg.setRole(MessageRole.USER);
        userMsg.setContent(instruction);
        messageRepository.save(userMsg);

        AiResult result = anthropicService.analyzePdf(LegalPrompts.DOCUMENT_SYSTEM, base64, instruction);

        MessageEntity assistantMsg = new MessageEntity();
        assistantMsg.setConversationId(conversation.getId());
        assistantMsg.setRole(MessageRole.ASSISTANT);
        assistantMsg.setContent(result.text());
        assistantMsg.setInputTokens(result.inputTokens());
        assistantMsg.setOutputTokens(result.outputTokens());
        messageRepository.save(assistantMsg);

        usageLogService.record(UsageFeature.DOCUMENT_ANALYSIS, anthropicService.getModel(), result);

        conversation.setUpdatedAt(Instant.now());
        conversationRepository.save(conversation);

        return new ChatReplyResponse(
                conversation.getId(),
                MessageResponse.from(assistantMsg),
                result.inputTokens(),
                result.outputTokens());
    }

    @Transactional
    public void deleteConversation(UUID conversationId) {
        ConversationEntity conversation = loadOwnedConversation(conversationId);
        conversation.setIsActive(false);
        conversation.setDeletedAt(Instant.now());
        conversationRepository.save(conversation);
    }

    // ── internos ─────────────────────────────────────────────────────────────

    /**
     * Invoca a Claude eligiendo el modo según la conversación: si tiene documento
     * adjunto, lo re-adjunta (cacheado) con el prompt de documentos; si no, chat normal.
     * Si {@code onDelta} no es null, usa streaming.
     */
    private AiResult invokeModel(ConversationEntity conversation, List<ChatTurn> history,
                                 String userMessage, Consumer<String> onDelta) {
        if (conversation.getDocumentObjectKey() != null) {
            String base64 = fetchDocument(conversation.getDocumentBucket(), conversation.getDocumentObjectKey());
            return (onDelta != null)
                    ? anthropicService.chatWithDocumentStream(
                            LegalPrompts.DOCUMENT_SYSTEM, base64, history, userMessage, onDelta)
                    : anthropicService.chatWithDocument(
                            LegalPrompts.DOCUMENT_SYSTEM, base64, history, userMessage);
        }
        return (onDelta != null)
                ? anthropicService.chatStream(LegalPrompts.CHAT_SYSTEM, history, userMessage, onDelta)
                : anthropicService.chat(LegalPrompts.CHAT_SYSTEM, history, userMessage);
    }

    /** Descarga el documento de legalCore (presigned URL fresca) y lo devuelve en base64. */
    private String fetchDocument(String bucket, String objectKey) {
        String url = legalCoreClient.presignedUrl(bucket, objectKey);
        byte[] pdf = documentFetcher.downloadPdf(url);
        return Base64.getEncoder().encodeToString(pdf);
    }

    /** Historial de la conversación, acotado a los últimos {@code maxHistoryMessages} mensajes. */
    private List<ChatTurn> buildHistory(UUID conversationId) {
        List<MessageEntity> previous =
                messageRepository.findByConversationIdOrderByCreatedAtAsc(conversationId);
        if (maxHistoryMessages > 0 && previous.size() > maxHistoryMessages) {
            previous = previous.subList(previous.size() - maxHistoryMessages, previous.size());
        }
        List<ChatTurn> history = new ArrayList<>();
        for (MessageEntity m : previous) {
            history.add(new ChatTurn(m.getRole() == MessageRole.ASSISTANT, m.getContent()));
        }
        return history;
    }

    private ConversationEntity loadOwnedConversation(UUID conversationId) {
        return conversationRepository
                .findByIdAndTenantIdAndUserIdAndDeletedAtIsNull(
                        conversationId, TenantContext.getTenantId(), UserContext.getUserId())
                .orElseThrow(() -> new ResourceNotFoundException("Conversación no encontrada"));
    }

    private String buildTitle(String firstMessage) {
        String trimmed = firstMessage.strip().replaceAll("\\s+", " ");
        return trimmed.length() <= 60 ? trimmed : trimmed.substring(0, 57) + "...";
    }
}
