package com.IusCloud.msia.shared.plans.dto;

/**
 * Límites del plan de un tenant, obtenidos del endpoint interno de ms-plans.
 * Un valor null significa "sin límite" para ese recurso.
 */
public record TenantLimits(
        Integer maxUsers,
        Integer maxCases,
        Integer maxStorageGb,
        Long maxAiTokensMonthly,
        String status
) {}
