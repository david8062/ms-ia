package com.IusCloud.msia.core.common.usage;

import com.IusCloud.msia.core.common.ai.AiResult;
import com.IusCloud.msia.shared.tenant.TenantContext;
import com.IusCloud.msia.shared.tenant.UserContext;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;

/**
 * Registra el consumo de tokens por tenant para cada invocación de IA.
 */
@Service
@RequiredArgsConstructor
public class UsageLogService {

    private final UsageLogJpaRepository repository;

    public void record(UsageFeature feature, String model, AiResult result) {
        UsageLogEntity log = new UsageLogEntity();
        log.setTenantId(TenantContext.getTenantId());
        log.setUserId(UserContext.getUserId());
        log.setFeature(feature);
        log.setModel(model);
        log.setInputTokens(result.inputTokens());
        log.setOutputTokens(result.outputTokens());
        log.setCacheReadInputTokens(result.cacheReadInputTokens());
        log.setCacheCreationInputTokens(result.cacheCreationInputTokens());
        repository.save(log);
    }
}
