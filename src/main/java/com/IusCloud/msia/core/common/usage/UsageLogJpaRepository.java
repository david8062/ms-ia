package com.IusCloud.msia.core.common.usage;

import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.time.Instant;
import java.util.UUID;

public interface UsageLogJpaRepository extends JpaRepository<UsageLogEntity, UUID> {

    /**
     * Suma total de tokens consumidos por el tenant desde {@code since}
     * (input + output + lectura/escritura de caché).
     */
    @Query("""
        SELECT COALESCE(SUM(u.inputTokens + u.outputTokens
                          + u.cacheReadInputTokens + u.cacheCreationInputTokens), 0)
        FROM UsageLogEntity u
        WHERE u.tenantId = :tenantId AND u.createdAt >= :since
        """)
    long sumTokensByTenantSince(@Param("tenantId") UUID tenantId, @Param("since") Instant since);
}
