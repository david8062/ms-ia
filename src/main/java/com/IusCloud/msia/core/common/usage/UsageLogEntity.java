package com.IusCloud.msia.core.common.usage;

import com.IusCloud.msia.core.base.BaseModel;
import jakarta.persistence.*;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;

import java.util.UUID;

@Entity
@Table(name = "ai_usage_logs")
@Getter
@Setter
@NoArgsConstructor
public class UsageLogEntity extends BaseModel {

    @Column(name = "tenant_id", nullable = false)
    private UUID tenantId;

    @Column(name = "user_id")
    private UUID userId;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false, length = 50)
    private UsageFeature feature;

    @Column(nullable = false, length = 60)
    private String model;

    @Column(name = "input_tokens", nullable = false)
    private int inputTokens;

    @Column(name = "output_tokens", nullable = false)
    private int outputTokens;

    @Column(name = "cache_read_input_tokens", nullable = false)
    private int cacheReadInputTokens;

    @Column(name = "cache_creation_input_tokens", nullable = false)
    private int cacheCreationInputTokens;
}
