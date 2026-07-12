package com.IusCloud.msia.core.features.chat.infrastructure.persistence;

import com.IusCloud.msia.core.features.chat.domain.model.MessageEntity;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;
import java.util.UUID;

public interface MessageJpaRepository extends JpaRepository<MessageEntity, UUID> {

    List<MessageEntity> findByConversationIdOrderByCreatedAtAsc(UUID conversationId);
}
