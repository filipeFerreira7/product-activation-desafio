package br.com.filipe.product_activation.integration;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

import java.util.UUID;
import java.util.concurrent.Executor;

@Component
public class ActivationDispatcher {

    private static final Logger log = LoggerFactory.getLogger(ActivationDispatcher.class);

    @Autowired
    private ActivationProcessor processor;

    @Autowired
    @Qualifier("activationExecutor")
    private Executor executor;

    @Value("${app.activation.mode:ASYNC}")
    private String mode;

    @Value("${app.provider.max-attempts:3}")
    private int maxAttempts;

    @Value("${app.provider.backoff-ms:200}")
    private long backoffMs;

    @Value("${app.provider.retry-after-max-ms:30000}")
    private long retryAfterMaxMs;

    public void dispatch(UUID activationId) {
        switch (mode.toUpperCase()) {
            case "SYNC" -> runWithRetries(activationId);
            case "ASYNC" -> executor.execute(() -> runWithRetries(activationId));
            default -> {
                // DISABLED: processamento externo ignorado (testes de API)
            }
        }
    }

    private void runWithRetries(UUID activationId) {
        for (int attempt = 0; attempt < maxAttempts; attempt++) {
            ActivationProcessor.ProcessResult result = processor.processOnce(activationId);
            if (result.outcome() != ActivationProcessor.ProcessOutcome.RETRY) {
                return;
            }
            sleep(waitMillis(result, attempt));
        }
    }

    private long waitMillis(ActivationProcessor.ProcessResult result, int attempt) {
        if (result.retryAfter() != null) {
            return Math.min(Math.max(result.retryAfter().toMillis(), 0), retryAfterMaxMs);
        }
        return backoffMs * (attempt + 1);
    }

    private void sleep(long millis) {
        try {
            Thread.sleep(millis);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            log.warn("Retry interrompido para activationId");
        }
    }
}
