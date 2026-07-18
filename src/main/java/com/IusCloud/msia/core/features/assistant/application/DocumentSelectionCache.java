package com.IusCloud.msia.core.features.assistant.application;

import org.springframework.stereotype.Component;

import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Memoria corta para el flujo "listar documentos → elegir": guarda por teléfono el último
 * listado de documentos que se le mostró al abogado, para resolver el "todos" / "el 2" del
 * siguiente mensaje (el asistente es sin estado por mensaje). TTL corto; ms-ia es instancia
 * única en prod, así que un ConcurrentHashMap basta (si reinicia, el usuario reasca).
 */
@Component
public class DocumentSelectionCache {

    /** Un documento listado: el nombre visible, su tipo MIME y la URL de descarga temporal. */
    public record CachedDoc(String filename, String mimeType, String url) {}

    private record Entry(List<CachedDoc> docs, Instant expiresAt) {}

    private static final Duration TTL = Duration.ofMinutes(10);

    private final Map<String, Entry> byPhone = new ConcurrentHashMap<>();

    public void put(String phone, List<CachedDoc> docs) {
        byPhone.put(phone, new Entry(docs, Instant.now().plus(TTL)));
    }

    /** Último listado vigente para el teléfono, o {@code null} si no hay o expiró. */
    public List<CachedDoc> get(String phone) {
        Entry e = byPhone.get(phone);
        if (e == null || Instant.now().isAfter(e.expiresAt())) {
            byPhone.remove(phone);
            return null;
        }
        return e.docs();
    }

    public void clear(String phone) {
        byPhone.remove(phone);
    }
}
