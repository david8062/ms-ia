package com.IusCloud.msia.core.common.abuse;

import com.IusCloud.msia.core.base.BaseModel;
import com.IusCloud.msia.core.common.usage.UsageFeature;
import jakarta.persistence.*;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;

import java.util.UUID;

/**
 * Registro de un intento de uso indebido / manipulación del asistente
 * (p. ej. prompt injection) detectado por {@link PromptInjectionDetector}.
 */
@Entity
@Table(name = "ai_abuse_events")
@Getter
@Setter
@NoArgsConstructor
public class AbuseEventEntity extends BaseModel {

    @Column(name = "tenant_id", nullable = false)
    private UUID tenantId;

    @Column(name = "user_id")
    private UUID userId;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false, length = 50)
    private UsageFeature feature;

    @Column(length = 120)
    private String reason;

    @Column(columnDefinition = "TEXT")
    private String snippet;
}
