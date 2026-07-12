package com.IusCloud.msia.core.common.usage;

import com.IusCloud.msia.shared.exceptions.BusinessException;
import com.IusCloud.msia.shared.plans.PlansLimitsClient;
import com.IusCloud.msia.shared.plans.dto.TenantLimits;
import com.IusCloud.msia.shared.tenant.TenantContext;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.test.util.ReflectionTestUtils;

import java.time.Instant;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.*;

@ExtendWith(MockitoExtension.class)
@DisplayName("TokenLimitGuardService — tope mensual de tokens por tenant")
class TokenLimitGuardServiceTest {

    @Mock PlansLimitsClient plansLimitsClient;
    @Mock UsageLogJpaRepository usageRepository;

    @InjectMocks TokenLimitGuardService guard;

    private final UUID tenantId = UUID.randomUUID();

    @BeforeEach
    void setUp() {
        ReflectionTestUtils.setField(guard, "resetZone", "America/Bogota");
        TenantContext.setTenantId(tenantId);
    }

    @AfterEach
    void tearDown() {
        TenantContext.clear();
    }

    private TenantLimits limits(Long maxTokens) {
        return new TenantLimits(null, null, null, maxTokens, "ACTIVE");
    }

    @Test
    @DisplayName("bloquea cuando el consumo del mes alcanzó el tope")
    void blocksWhenLimitReached() {
        when(plansLimitsClient.getLimits(tenantId)).thenReturn(limits(1_000L));
        when(usageRepository.sumTokensByTenantSince(eq(tenantId), any(Instant.class))).thenReturn(1_000L);

        assertThatThrownBy(() -> guard.check())
                .isInstanceOf(BusinessException.class)
                .hasMessageContaining("límite mensual de tokens");
    }

    @Test
    @DisplayName("permite cuando el consumo está por debajo del tope")
    void allowsBelowLimit() {
        when(plansLimitsClient.getLimits(tenantId)).thenReturn(limits(1_000L));
        when(usageRepository.sumTokensByTenantSince(eq(tenantId), any(Instant.class))).thenReturn(500L);

        assertThatCode(() -> guard.check()).doesNotThrowAnyException();
    }

    @Test
    @DisplayName("no aplica tope cuando el plan no lo define (maxAiTokensMonthly null)")
    void skipsWhenLimitNull() {
        when(plansLimitsClient.getLimits(tenantId)).thenReturn(limits(null));

        assertThatCode(() -> guard.check()).doesNotThrowAnyException();
        verify(usageRepository, never()).sumTokensByTenantSince(any(), any());
    }

    @Test
    @DisplayName("fail-open: si ms-plans no responde, no bloquea")
    void failOpenWhenPlansUnavailable() {
        when(plansLimitsClient.getLimits(tenantId)).thenReturn(null);

        assertThatCode(() -> guard.check()).doesNotThrowAnyException();
        verify(usageRepository, never()).sumTokensByTenantSince(any(), any());
    }

    @Test
    @DisplayName("sin tenant en contexto no hace nada")
    void noopWhenNoTenant() {
        TenantContext.clear();

        assertThatCode(() -> guard.check()).doesNotThrowAnyException();
        verifyNoInteractions(plansLimitsClient, usageRepository);
    }
}
