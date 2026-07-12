package com.IusCloud.msia.core.common.documents;

import com.IusCloud.msia.shared.exceptions.BusinessException;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.ResponseEntity;
import org.springframework.stereotype.Component;
import org.springframework.web.client.RestClient;
import org.springframework.web.client.RestClientResponseException;

import java.net.URI;
import java.util.Arrays;
import java.util.Set;
import java.util.stream.Collectors;

/**
 * Descarga PDFs desde almacenamiento externo (presigned URLs de MinIO) con guard
 * anti-SSRF (allowlist de hosts) y validación de magic bytes. Reutilizable por el
 * análisis de documentos por URL y por las conversaciones con documento adjunto.
 */
@Component
@RequiredArgsConstructor
@Slf4j
public class DocumentFetcher {

    private final RestClient documentDownloadRestClient;

    /** Hosts autorizados para descarga por URL (anti-SSRF). Vacío = descarga deshabilitada. */
    @Value("${documents.download.allowed-hosts:}")
    private String allowedHostsRaw;

    /** Tamaño máximo del PDF descargado (bytes). Por defecto 32MB. */
    @Value("${documents.download.max-bytes:33554432}")
    private long maxDownloadBytes;

    /** Descarga el PDF de la URL, valida host y que sea un PDF, y devuelve los bytes. */
    public byte[] downloadPdf(String url) {
        URI uri = parseAndValidateUrl(url);
        byte[] bytes = download(uri);
        validateIsPdf(bytes);
        return bytes;
    }

    public String filenameFromUrl(String url) {
        try {
            String path = URI.create(url.trim()).getPath();
            if (path == null || path.isBlank()) {
                return "documento.pdf";
            }
            String name = path.substring(path.lastIndexOf('/') + 1);
            return name.isBlank() ? "documento.pdf" : name;
        } catch (Exception e) {
            return "documento.pdf";
        }
    }

    // ── internos ─────────────────────────────────────────────────────────────

    private URI parseAndValidateUrl(String url) {
        URI uri;
        try {
            uri = URI.create(url.trim());
        } catch (IllegalArgumentException e) {
            throw new BusinessException("La url no es válida.");
        }
        String scheme = uri.getScheme();
        if (scheme == null || !(scheme.equalsIgnoreCase("http") || scheme.equalsIgnoreCase("https"))) {
            throw new BusinessException("La url debe usar http o https.");
        }
        String host = uri.getHost();
        if (host == null || host.isBlank()) {
            throw new BusinessException("La url no tiene un host válido.");
        }
        if (!isHostAllowed(host, uri.getPort())) {
            throw new BusinessException("El host de la url no está autorizado para descarga.");
        }
        return uri;
    }

    private boolean isHostAllowed(String host, int port) {
        Set<String> allowed = allowedHosts();
        if (allowed.isEmpty()) {
            log.warn("Descarga por URL bloqueada: 'documents.download.allowed-hosts' no está configurado.");
            return false;
        }
        String hostLower = host.toLowerCase();
        String hostPort = port == -1 ? hostLower : hostLower + ":" + port;
        return allowed.contains(hostLower) || allowed.contains(hostPort);
    }

    private Set<String> allowedHosts() {
        if (allowedHostsRaw == null || allowedHostsRaw.isBlank()) {
            return Set.of();
        }
        return Arrays.stream(allowedHostsRaw.split(","))
                .map(String::trim)
                .filter(s -> !s.isEmpty())
                .map(String::toLowerCase)
                .collect(Collectors.toSet());
    }

    private byte[] download(URI uri) {
        try {
            ResponseEntity<byte[]> response = documentDownloadRestClient.get()
                    .uri(uri)
                    .retrieve()
                    .toEntity(byte[].class);
            byte[] body = response.getBody();
            if (body == null || body.length == 0) {
                throw new BusinessException("El documento descargado está vacío.");
            }
            if (body.length > maxDownloadBytes) {
                throw new BusinessException("El documento excede el tamaño máximo permitido (32MB).");
            }
            return body;
        } catch (BusinessException e) {
            throw e;
        } catch (RestClientResponseException e) {
            throw new BusinessException("No se pudo descargar el documento (HTTP " + e.getStatusCode().value() + ").");
        } catch (Exception e) {
            throw new BusinessException("No se pudo descargar el documento: " + e.getMessage());
        }
    }

    private void validateIsPdf(byte[] bytes) {
        boolean isPdf = bytes.length >= 4
                && bytes[0] == 0x25 && bytes[1] == 0x50 && bytes[2] == 0x44 && bytes[3] == 0x46; // %PDF
        if (!isPdf) {
            throw new BusinessException("El documento descargado no es un PDF válido. Por URL solo se admite PDF.");
        }
    }
}
