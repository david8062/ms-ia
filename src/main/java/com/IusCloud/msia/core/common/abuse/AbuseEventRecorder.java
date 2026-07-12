package com.IusCloud.msia.core.common.abuse;

import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

/**
 * Persiste eventos de abuso en una transacción independiente ({@code REQUIRES_NEW}).
 * Necesario porque el guard lanza una excepción justo después de registrar el
 * evento; si compartiera la transacción del use case, el rollback borraría el
 * registro. Es un bean aparte para que el proxy de Spring aplique la propagación.
 */
@Service
@RequiredArgsConstructor
public class AbuseEventRecorder {

    private final AbuseEventJpaRepository repository;

    @Transactional(propagation = Propagation.REQUIRES_NEW)
    public void record(AbuseEventEntity event) {
        repository.save(event);
    }
}
