package com.IusCloud.msia.core.common.abuse;

import org.springframework.data.jpa.repository.JpaRepository;

import java.time.Instant;
import java.util.UUID;

public interface AbuseEventJpaRepository extends JpaRepository<AbuseEventEntity, UUID> {

    /** Cuántos intentos de abuso registró un usuario desde {@code since} (ventana de cooldown). */
    long countByTenantIdAndUserIdAndCreatedAtAfter(UUID tenantId, UUID userId, Instant since);
}
