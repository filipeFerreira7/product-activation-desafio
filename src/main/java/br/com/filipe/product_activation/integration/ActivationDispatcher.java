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
            ActivationProcessor.ProcessOutcome outcome = processor.processOnce(activationId);
            if (outcome != ActivationProcessor.ProcessOutcome.RETRY) {
                return;
            }
            sleep(backoffMs * (attempt + 1));
        }
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
