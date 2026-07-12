package com.IusCloud.msia.core.features.chat.domain.model;

import com.IusCloud.msia.core.base.BaseModel;
import jakarta.persistence.*;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;

import java.util.UUID;

@Entity
@Table(name = "ai_conversations")
@Getter
@Setter
@NoArgsConstructor
public class ConversationEntity extends BaseModel {

    @Column(name = "tenant_id", nullable = false)
    private UUID tenantId;

    @Column(name = "user_id", nullable = false)
    private UUID userId;

    /** Caso de legalCore al que pertenece la conversación (opcional). */
    @Column(name = "case_id")
    private UUID caseId;

    /** Documento de legalCore/MinIO adjunto (opcional): si está, es un chat con documento. */
    @Column(name = "document_bucket", columnDefinition = "TEXT")
    private String documentBucket;

    @Column(name = "document_object_key", columnDefinition = "TEXT")
    private String documentObjectKey;

    @Column(name = "document_filename", length = 512)
    private String documentFilename;

    @Column(length = 255)
    private String title;
}
