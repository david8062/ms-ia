package com.IusCloud.msia.core.features.chat.infrastructure.persistence;

import com.IusCloud.msia.core.features.chat.domain.model.ConversationEntity;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;
import java.util.Optional;
import java.util.UUID;

public interface ConversationJpaRepository extends JpaRepository<ConversationEntity, UUID> {

    List<ConversationEntity> findByTenantIdAndUserIdAndDeletedAtIsNullOrderByCreatedAtDesc(
            UUID tenantId, UUID userId);

    List<ConversationEntity> findByTenantIdAndUserIdAndCaseIdAndDeletedAtIsNullOrderByCreatedAtDesc(
            UUID tenantId, UUID userId, UUID caseId);

    Optional<ConversationEntity> findByIdAndTenantIdAndUserIdAndDeletedAtIsNull(
            UUID id, UUID tenantId, UUID userId);
}
