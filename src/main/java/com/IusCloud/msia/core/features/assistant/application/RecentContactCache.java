package com.IusCloud.msia.core.features.assistant.application;

import org.springframework.stereotype.Component;

import java.time.Duration;
import java.time.Instant;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Recuerda a qué números desconocidos ya se les respondió, para NO contestarles cada mensaje.
 * Responderle a cada mensaje de un no-contacto es "mensajería masiva" a ojos de WhatsApp (fue
 * parte de lo que restringió la línea). Se saluda una vez; dentro de la ventana, silencio.
 */
@Component
public class RecentContactCache {

    private static final Duration TTL = Duration.ofHours(24);

    private final Map<String, Instant> greeted = new ConcurrentHashMap<>();

    public boolean greetedRecently(String phone) {
        Instant t = greeted.get(phone);
        if (t == null) {
            return false;
        }
        if (Instant.now().isAfter(t.plus(TTL))) {
            greeted.remove(phone);
            return false;
        }
        return true;
    }

    public void markGreeted(String phone) {
        greeted.put(phone, Instant.now());
    }
}
