package com.IusCloud.msia.core.common.usage;

import com.IusCloud.msia.shared.exceptions.BusinessException;
import com.IusCloud.msia.shared.plans.PlansLimitsClient;
import com.IusCloud.msia.shared.plans.dto.TenantLimits;
import com.IusCloud.msia.shared.tenant.TenantContext;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;

import java.time.Instant;
import java.time.YearMonth;
import java.time.ZoneId;
import java.util.UUID;

/**
 * Bloquea las funciones de IA cuando el tenant alcanzó su tope mensual de tokens
 * (definido por plan en ms-plans). El consumo se acumula por tenant y se reinicia
 * el primer día de cada mes en la zona horaria configurada.
 *
 * <p>Debe llamarse al inicio de cada use case de IA, antes de invocar al modelo
 * (junto al guard de abuso). Si ms-plans no responde o el plan no define límite,
 * no se bloquea (fail-open).
 */
@Service
@RequiredArgsConstructor
@Slf4j
public class TokenLimitGuardService {

    private final PlansLimitsClient plansLimitsClient;
    private final UsageLogJpaRepository usageRepository;

    @Value("${ai.usage.reset-zone:America/Bogota}")
    private String resetZone;

    public void check() {
        if (!TenantContext.hasTenant()) {
            return;
        }
        UUID tenantId = TenantContext.getTenantId();

        TenantLimits limits = plansLimitsClient.getLimits(tenantId);

        // Fail-open: si ms-plans no responde no se bloquea al usuario. Pero esto DESACTIVA el
        // control de gasto, así que no puede pasar en silencio — una URL mal escrita tuvo el
        // tope apagado sin que nadie lo notara. Se registra como ERROR para que sea visible.
        if (limits == null) {
            log.error("Tope de tokens NO aplicado (ms-plans no devolvió límites para tenant {}): " +
                    "el consumo de IA queda sin control hasta que se resuelva", tenantId);
            return;
        }
        if (limits.maxAiTokensMonthly() == null) {
            return;   // el plan no define tope: decisión de producto, no un fallo
        }

        long used = usageRepository.sumTokensByTenantSince(tenantId, startOfCurrentMonth());
        if (used >= limits.maxAiTokensMonthly()) {
            log.warn("Tope mensual de tokens alcanzado: tenant={}, usados={}, límite={}",
                    tenantId, used, limits.maxAiTokensMonthly());
            throw new BusinessException(
                    "Se alcanzó el límite mensual de tokens de IA de tu plan (" +
                    limits.maxAiTokensMonthly() + " tokens). El consumo se reinicia el primer día del mes. " +
                    "Actualiza tu plan para ampliar el cupo.");
        }
    }

    private Instant startOfCurrentMonth() {
        ZoneId zone = ZoneId.of(resetZone);
        return YearMonth.now(zone).atDay(1).atStartOfDay(zone).toInstant();
    }
}
