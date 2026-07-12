package com.IusCloud.msia.core.common.usage;

import lombok.RequiredArgsConstructor;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.time.Instant;
import java.time.YearMonth;
import java.time.ZoneId;
import java.util.Map;
import java.util.UUID;

/**
 * Tokens de IA consumidos por el tenant en el mes en curso, para la pantalla de uso vs.
 * límites. Lo consume auth server-a-server con X-Internal-Key.
 *
 * El corte del mes es el mismo que usa {@link TokenLimitGuardService} para bloquear, así
 * que lo que el usuario ve coincide con lo que se le aplica.
 */
@RestController
@RequestMapping("/api/v1/internal/usage")
@RequiredArgsConstructor
public class InternalUsageController {

    private final UsageLogJpaRepository usageRepository;

    @Value("${ai.usage.reset-zone:America/Bogota}")
    private String resetZone;

    @GetMapping("/tenant/{tenantId}")
    public ResponseEntity<Map<String, Object>> getUsage(@PathVariable UUID tenantId) {
        long tokens = usageRepository.sumTokensByTenantSince(tenantId, startOfCurrentMonth());
        return ResponseEntity.ok(Map.of("aiTokensMonth", tokens));
    }

    private Instant startOfCurrentMonth() {
        ZoneId zone = ZoneId.of(resetZone);
        return YearMonth.now(zone).atDay(1).atStartOfDay(zone).toInstant();
    }
}
