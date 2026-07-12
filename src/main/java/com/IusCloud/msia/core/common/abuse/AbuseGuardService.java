package com.IusCloud.msia.core.common.abuse;

import com.IusCloud.msia.core.common.usage.UsageFeature;
import com.IusCloud.msia.shared.exceptions.PromptInjectionException;
import com.IusCloud.msia.shared.exceptions.UserBlockedException;
import com.IusCloud.msia.shared.tenant.TenantContext;
import com.IusCloud.msia.shared.tenant.UserContext;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;

import java.time.Duration;
import java.time.Instant;
import java.util.Optional;
import java.util.UUID;

/**
 * Guarda las funciones de IA frente a uso indebido. En cada invocación:
 * <ol>
 *   <li>Si el usuario superó el umbral de intentos dentro de la ventana, lo bloquea
 *       (cooldown temporal auto-liberable) → {@link UserBlockedException}.</li>
 *   <li>Si el texto entrante parece manipulación, registra el evento y rechaza la
 *       solicitud → {@link PromptInjectionException} (o bloqueo si cruzó el umbral).</li>
 * </ol>
 * El bloqueo es por usuario y solo afecta a las funciones de IA. Se auto-libera a
 * medida que los eventos salen de la ventana de tiempo.
 */
@Service
@RequiredArgsConstructor
@Slf4j
public class AbuseGuardService {

    private static final String SECURITY_MESSAGE =
            "Se detectó contenido destinado a modificar el comportamiento del asistente. "
                    + "Dichas instrucciones fueron ignoradas y la solicitud no se procesó, conforme a las "
                    + "políticas de seguridad de IusCloud.";

    private final AbuseEventJpaRepository repository;
    private final AbuseEventRecorder recorder;
    private final PromptInjectionDetector detector;

    /** Número de intentos dentro de la ventana que dispara el bloqueo. */
    @Value("${ai.abuse.threshold:3}")
    private int threshold;

    /** Ventana / duración del cooldown en minutos. */
    @Value("${ai.abuse.window-minutes:60}")
    private long windowMinutes;

    /**
     * Verifica el bloqueo y analiza el texto del usuario. Debe llamarse al inicio
     * de cada use case de IA, antes de cualquier escritura o llamada al modelo.
     */
    public void screen(UsageFeature feature, String userText) {
        UUID tenantId = TenantContext.getTenantId();
        UUID userId = UserContext.getUserId();

        // Sin userId no se puede rastrear por usuario: solo detectamos y rechazamos.
        if (userId == null) {
            if (detector.detect(userText).isPresent()) {
                throw new PromptInjectionException(SECURITY_MESSAGE);
            }
            return;
        }

        Instant since = Instant.now().minus(Duration.ofMinutes(windowMinutes));
        long recent = repository.countByTenantIdAndUserIdAndCreatedAtAfter(tenantId, userId, since);

        // 1. ¿Ya bloqueado?
        if (recent >= threshold) {
            log.warn("IA bloqueada por abuso reiterado: tenant={}, user={}, eventos={} en {}min",
                    tenantId, userId, recent, windowMinutes);
            throw new UserBlockedException(blockMessage());
        }

        // 2. ¿El texto nuevo parece manipulación?
        Optional<String> reason = detector.detect(userText);
        if (reason.isEmpty()) {
            return;
        }

        // 3. Registrar el intento (transacción independiente) + alerta.
        AbuseEventEntity event = new AbuseEventEntity();
        event.setTenantId(tenantId);
        event.setUserId(userId);
        event.setFeature(feature);
        event.setReason(reason.get());
        event.setSnippet(truncate(userText));
        recorder.record(event);

        long total = recent + 1;
        log.warn("Intento de manipulación detectado: tenant={}, user={}, feature={}, motivo={}, total={}/{}",
                tenantId, userId, feature, reason.get(), total, threshold);

        // 4. ¿Este intento cruzó el umbral? → bloqueo. Si no, rechazo simple.
        if (total >= threshold) {
            throw new UserBlockedException(blockMessage());
        }
        throw new PromptInjectionException(SECURITY_MESSAGE);
    }

    private String blockMessage() {
        return "Se detectaron varios intentos de uso indebido del asistente. El acceso a las funciones de IA "
                + "quedó temporalmente bloqueado para tu usuario; vuelve a intentarlo más tarde.";
    }

    private String truncate(String text) {
        if (text == null) {
            return null;
        }
        return text.length() <= 300 ? text : text.substring(0, 300) + "…";
    }
}
