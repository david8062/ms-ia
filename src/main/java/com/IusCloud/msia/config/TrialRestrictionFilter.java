package com.IusCloud.msia.config;

import com.IusCloud.msia.config.security.JwtService;
import io.jsonwebtoken.Claims;
import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.http.HttpMethod;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.stereotype.Component;
import org.springframework.web.filter.OncePerRequestFilter;

import java.io.IOException;
import java.time.Instant;
import java.time.LocalDateTime;
import java.util.Set;

/**
 * Backstop de "trial vencido / cuenta bloqueada" para las escrituras de IA
 * (chat, análisis de documentos, resumen de caso).
 *
 * Lee el estado de los claims del JWT ({@code tenantStatus}, {@code trialEndsAt}),
 * que auth ya emite, sin acoplar servicios. Espeja el filtro homónimo de auth,
 * legal-core y ms-fees. {@code trialEndsAt} es un timestamp fijo del token, así
 * que un trial que vence a mitad de sesión se detecta aunque el token no se haya
 * refrescado.
 */
@Component
@RequiredArgsConstructor
@Slf4j
public class TrialRestrictionFilter extends OncePerRequestFilter {

    private static final Set<String> WRITE_METHODS = Set.of(
            HttpMethod.POST.name(),
            HttpMethod.PUT.name(),
            HttpMethod.PATCH.name(),
            HttpMethod.DELETE.name()
    );

    private final JwtService jwtService;

    @Override
    protected boolean shouldNotFilter(HttpServletRequest request) {
        if (!WRITE_METHODS.contains(request.getMethod().toUpperCase())) {
            return true;
        }
        String path = request.getServletPath();
        return path.startsWith("/api/v1/onboard")
                || path.startsWith("/api/v1/internal/");
    }

    @Override
    protected void doFilterInternal(
            HttpServletRequest request,
            HttpServletResponse response,
            FilterChain filterChain
    ) throws ServletException, IOException {

        String authHeader = request.getHeader("Authorization");
        if (authHeader == null || !authHeader.startsWith("Bearer ")) {
            filterChain.doFilter(request, response);
            return;
        }

        String status;
        Instant trialEndsAt;
        Instant subscriptionEndsAt;
        try {
            Claims claims = jwtService.extractClaims(authHeader.substring(7));
            status = claims.get("tenantStatus", String.class);
            String trial = claims.get("trialEndsAt", String.class);
            trialEndsAt = (trial != null && !trial.isBlank()) ? Instant.parse(trial) : null;
            String sub = claims.get("subscriptionEndsAt", String.class);
            subscriptionEndsAt = (sub != null && !sub.isBlank()) ? Instant.parse(sub) : null;
        } catch (Exception ex) {
            // Token inválido/ilegible: no es nuestro trabajo, que lo maneje seguridad.
            filterChain.doFilter(request, response);
            return;
        }

        if (isAccessBlocked(status, trialEndsAt, subscriptionEndsAt)) {
            writeAccessBlockedResponse(request, response, status);
            return;
        }

        filterChain.doFilter(request, response);
    }

    private boolean isAccessBlocked(String status, Instant trialEndsAt, Instant subscriptionEndsAt) {
        if (status == null) {
            return false;
        }
        // SUSPENDED/CANCELLED/PAST_DUE => solo lectura
        if (status.equals("SUSPENDED") || status.equals("CANCELLED") || status.equals("PAST_DUE")) {
            return true;
        }
        // Trial vencido aunque el job aún no haya movido el estado
        if (status.equals("TRIAL")
                && trialEndsAt != null
                && Instant.now().isAfter(trialEndsAt)) {
            return true;
        }
        // Suscripción de pago con el período cumplido: el estado sigue en ACTIVE hasta que el
        // job nocturno lo pasa a PAST_DUE, y en esa ventana auth ya bloqueaba pero aquí no.
        // Se compara contra el reloj, igual que el trial, para que caduque sola sin re-login.
        return status.equals("ACTIVE")
                && subscriptionEndsAt != null
                && Instant.now().isAfter(subscriptionEndsAt);
    }

    private void writeAccessBlockedResponse(HttpServletRequest request, HttpServletResponse response,
                                            String status) throws IOException {
        response.setStatus(HttpStatus.PAYMENT_REQUIRED.value());
        response.setContentType(MediaType.APPLICATION_JSON_VALUE);

        String message = switch (status == null ? "" : status) {
            case "SUSPENDED" -> "Your account has been suspended. Please contact support.";
            case "CANCELLED" -> "Your account has been cancelled.";
            case "PAST_DUE"  -> "Your subscription payment is overdue. Access is read-only until payment is settled.";
            default          -> "Your trial has ended. Upgrade your plan to continue making changes.";
        };

        String body = String.format(
                "{\"timestamp\":\"%s\",\"status\":402,\"error\":\"Access Blocked\"," +
                "\"message\":\"%s\",\"tenantStatus\":\"%s\",\"path\":\"%s\"}",
                LocalDateTime.now(), message, status, request.getServletPath()
        );
        response.getWriter().write(body);
    }
}
