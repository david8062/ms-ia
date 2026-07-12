package com.IusCloud.msia.core.common.ai;

import com.IusCloud.msia.shared.exceptions.BusinessException;
import com.anthropic.client.AnthropicClient;
import com.anthropic.core.http.StreamResponse;
import com.anthropic.helpers.MessageAccumulator;
import com.anthropic.models.messages.Base64PdfSource;
import com.anthropic.models.messages.CacheControlEphemeral;
import com.anthropic.models.messages.ContentBlockParam;
import com.anthropic.models.messages.DocumentBlockParam;
import com.anthropic.models.messages.Message;
import com.anthropic.models.messages.MessageCreateParams;
import com.anthropic.models.messages.RawMessageStreamEvent;
import com.anthropic.models.messages.TextBlockParam;
import com.anthropic.models.messages.ThinkingConfigAdaptive;
import com.anthropic.models.messages.Usage;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;

import java.util.List;
import java.util.function.Consumer;

/**
 * Encapsula el acceso al modelo de Claude. Las demás capas dependen de este
 * servicio en lugar de manipular el SDK directamente.
 */
@Service
@Slf4j
public class AnthropicService {

    private final AnthropicClient client;
    private final String model;
    private final long maxTokens;
    private final boolean configured;

    public AnthropicService(AnthropicClient client,
                            @Value("${anthropic.api-key:}") String apiKey,
                            @Value("${anthropic.model:claude-sonnet-4-6}") String model,
                            @Value("${anthropic.max-tokens:8000}") long maxTokens) {
        this.client = client;
        this.model = model;
        this.maxTokens = maxTokens;
        this.configured = apiKey != null && !apiKey.isBlank();
    }

    /**
     * Conversa con el modelo reenviando el historial completo más el mensaje nuevo.
     */
    public AiResult chat(String systemPrompt, List<ChatTurn> history, String userMessage) {
        ensureConfigured();
        return invoke(buildChatParams(systemPrompt, history, userMessage));
    }

    /**
     * Igual que {@link #chat}, pero recibe la respuesta en streaming: invoca
     * {@code onDelta} con cada fragmento de texto a medida que el modelo lo
     * genera. Devuelve el {@link AiResult} completo (texto final + tokens) una
     * vez terminado el stream, para persistir el mensaje y registrar consumo.
     */
    public AiResult chatStream(String systemPrompt, List<ChatTurn> history, String userMessage,
                               Consumer<String> onDelta) {
        ensureConfigured();
        return streamInvoke(buildChatParams(systemPrompt, history, userMessage), onDelta);
    }

    /**
     * Como {@link #chat}, pero con un documento (PDF base64) adjunto a la
     * conversación. El documento se incluye en el primer mensaje de usuario,
     * marcado como cacheable para no re-pagarlo en cada turno.
     */
    public AiResult chatWithDocument(String systemPrompt, String base64Pdf, List<ChatTurn> history,
                                     String userMessage) {
        ensureConfigured();
        return invoke(buildChatParamsWithDocument(systemPrompt, base64Pdf, history, userMessage));
    }

    /** Variante en streaming de {@link #chatWithDocument}. */
    public AiResult chatWithDocumentStream(String systemPrompt, String base64Pdf, List<ChatTurn> history,
                                           String userMessage, Consumer<String> onDelta) {
        ensureConfigured();
        return streamInvoke(buildChatParamsWithDocument(systemPrompt, base64Pdf, history, userMessage), onDelta);
    }

    private MessageCreateParams buildChatParams(String systemPrompt, List<ChatTurn> history, String userMessage) {
        MessageCreateParams.Builder builder = MessageCreateParams.builder()
                .model(model)
                .maxTokens(maxTokens)
                .thinking(ThinkingConfigAdaptive.builder().build());

        applyCachedSystem(builder, systemPrompt);

        if (history != null) {
            for (ChatTurn turn : history) {
                if (turn.assistant()) {
                    builder.addAssistantMessage(turn.content());
                } else {
                    builder.addUserMessage(turn.content());
                }
            }
        }

        builder.addUserMessage(userMessage);

        return builder.build();
    }

    private MessageCreateParams buildChatParamsWithDocument(String systemPrompt, String base64Pdf,
                                                            List<ChatTurn> history, String userMessage) {
        MessageCreateParams.Builder builder = MessageCreateParams.builder()
                .model(model)
                .maxTokens(maxTokens)
                .thinking(ThinkingConfigAdaptive.builder().build());

        applyCachedSystem(builder, systemPrompt);

        DocumentBlockParam document = DocumentBlockParam.builder()
                .source(Base64PdfSource.builder().data(base64Pdf).build())
                .cacheControl(CacheControlEphemeral.builder().build())
                .build();

        boolean docAttached = false;
        if (history != null) {
            for (ChatTurn turn : history) {
                if (turn.assistant()) {
                    builder.addAssistantMessage(turn.content());
                } else if (!docAttached) {
                    // El documento (cacheado) viaja con el primer mensaje de usuario.
                    builder.addUserMessageOfBlockParams(documentWithText(document, turn.content()));
                    docAttached = true;
                } else {
                    builder.addUserMessage(turn.content());
                }
            }
        }

        if (docAttached) {
            builder.addUserMessage(userMessage);
        } else {
            // No había mensajes de usuario previos: el documento va con el mensaje nuevo.
            builder.addUserMessageOfBlockParams(documentWithText(document, userMessage));
        }

        return builder.build();
    }

    private List<ContentBlockParam> documentWithText(DocumentBlockParam document, String text) {
        return List.of(
                ContentBlockParam.ofDocument(document),
                ContentBlockParam.ofText(TextBlockParam.builder().text(text).build()));
    }

    /**
     * Analiza un PDF (codificado en base64) según una instrucción.
     */
    public AiResult analyzePdf(String systemPrompt, String base64Pdf, String instruction) {
        ensureConfigured();

        DocumentBlockParam document = DocumentBlockParam.builder()
                .source(Base64PdfSource.builder().data(base64Pdf).build())
                .build();

        MessageCreateParams.Builder builder = MessageCreateParams.builder()
                .model(model)
                .maxTokens(maxTokens)
                .thinking(ThinkingConfigAdaptive.builder().build())
                .addUserMessageOfBlockParams(List.of(
                        ContentBlockParam.ofDocument(document),
                        ContentBlockParam.ofText(TextBlockParam.builder().text(instruction).build())));
        applyCachedSystem(builder, systemPrompt);

        return invoke(builder.build());
    }

    /**
     * Analiza un documento de texto plano según una instrucción.
     */
    public AiResult analyzeText(String systemPrompt, String documentText, String instruction) {
        ensureConfigured();

        String userMessage = instruction
                + "\n\n--- DOCUMENTO ---\n"
                + documentText
                + "\n--- FIN DEL DOCUMENTO ---";

        MessageCreateParams.Builder builder = MessageCreateParams.builder()
                .model(model)
                .maxTokens(maxTokens)
                .thinking(ThinkingConfigAdaptive.builder().build())
                .addUserMessage(userMessage);
        applyCachedSystem(builder, systemPrompt);

        return invoke(builder.build());
    }

    public String getModel() {
        return model;
    }

    // ── internos ─────────────────────────────────────────────────────────────

    private AiResult invoke(MessageCreateParams params) {
        try {
            Message response = client.messages().create(params);
            return toResult(extractText(response), response.usage());
        } catch (Exception ex) {
            log.error("Error invocando a Claude: {}", ex.getMessage(), ex);
            throw new BusinessException("No se pudo obtener respuesta del asistente de IA: " + ex.getMessage());
        }
    }

    private AiResult streamInvoke(MessageCreateParams params, Consumer<String> onDelta) {
        try (StreamResponse<RawMessageStreamEvent> stream = client.messages().createStreaming(params)) {
            MessageAccumulator accumulator = MessageAccumulator.create();
            stream.stream().forEach(event -> {
                accumulator.accumulate(event);
                event.contentBlockDelta()
                        .flatMap(delta -> delta.delta().text())
                        .ifPresent(textDelta -> onDelta.accept(textDelta.text()));
            });
            Message finalMessage = accumulator.message();
            return toResult(extractText(finalMessage), finalMessage.usage());
        } catch (Exception ex) {
            log.error("Error en streaming con Claude: {}", ex.getMessage(), ex);
            throw new BusinessException("No se pudo obtener respuesta del asistente de IA: " + ex.getMessage());
        }
    }

    /**
     * Marca el system prompt como cacheable (prompt caching). El system es idéntico
     * en todas las llamadas, así que Claude lo cachea (TTL ~5 min) y las llamadas
     * siguientes pagan ~0.1× por esos tokens en vez del precio completo.
     */
    private void applyCachedSystem(MessageCreateParams.Builder builder, String systemPrompt) {
        if (systemPrompt != null && !systemPrompt.isBlank()) {
            builder.systemOfTextBlockParams(List.of(
                    TextBlockParam.builder()
                            .text(systemPrompt)
                            .cacheControl(CacheControlEphemeral.builder().build())
                            .build()));
        }
    }

    private AiResult toResult(String text, Usage usage) {
        int inputTokens = (int) usage.inputTokens();
        int outputTokens = (int) usage.outputTokens();
        int cacheRead = usage.cacheReadInputTokens().map(Long::intValue).orElse(0);
        int cacheCreation = usage.cacheCreationInputTokens().map(Long::intValue).orElse(0);
        return new AiResult(text, inputTokens, outputTokens, cacheRead, cacheCreation);
    }

    /**
     * Extrae únicamente los bloques de texto, ignorando los bloques de
     * razonamiento (thinking) que devuelve el modelo.
     */
    private String extractText(Message response) {
        StringBuilder sb = new StringBuilder();
        response.content().forEach(block -> block.text().ifPresent(t -> sb.append(t.text())));
        return sb.toString().trim();
    }

    private void ensureConfigured() {
        if (!configured) {
            throw new BusinessException(
                    "La API key de Anthropic no está configurada (variable ANTHROPIC_API_KEY).");
        }
    }
}
