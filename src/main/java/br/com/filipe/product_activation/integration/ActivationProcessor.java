package br.com.filipe.product_activation.integration;

import br.com.filipe.product_activation.exception.ProviderCommunicationException;
import br.com.filipe.product_activation.model.Activation;
import br.com.filipe.product_activation.repository.ActivationRepository;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;
import org.springframework.transaction.support.TransactionTemplate;
import org.springframework.web.client.ResourceAccessException;

import java.time.Clock;
import java.time.Duration;
import java.util.UUID;

@Component
public class ActivationProcessor {

    private static final Logger log = LoggerFactory.getLogger(ActivationProcessor.class);

    public enum ProcessOutcome {
        SKIP,
        DONE,
        RETRY
    }

    public record ProcessResult(ProcessOutcome outcome, Duration retryAfter) {

        static ProcessResult of(ProcessOutcome outcome) {
            return new ProcessResult(outcome, null);
        }
    }

    @Autowired
    private ActivationRepository repository;

    @Autowired
    private ActivationProviderClient providerClient;

    @Autowired
    private Clock clock;

    @Autowired
    @Qualifier("requiresNewTransactionTemplate")
    private TransactionTemplate transactionTemplate;

    @Value("${app.provider.max-attempts:3}")
    private int maxAttempts;

    public ProcessResult processOnce(UUID activationId) {
        return transactionTemplate.execute(status -> doProcessOnce(activationId));
    }

    private ProcessResult doProcessOnce(UUID activationId) {
        Activation activation = repository.findById(activationId).orElse(null);
        if (activation == null || !activation.isProcessable()) {
            return ProcessResult.of(ProcessOutcome.SKIP);
        }
        activation.markProcessing(clock.instant());
        repository.saveAndFlush(activation);

        try {
            ProviderResponse response = providerClient.activate(activation);
            return applyProviderResponse(activation, response);
        } catch (ResourceAccessException ex) {
            log.warn("Timeout/erro de rede ao chamar provedor id={}: {}", activationId, ex.getMessage());
            return applyRetryOrFailure(activation, null);
        } catch (ProviderCommunicationException ex) {
            log.warn("Falha de comunicacao com provedor id={}: {}", activationId, ex.getMessage());
            return applyRetryOrFailure(activation, null);
        }
    }

    private ProcessResult applyProviderResponse(Activation activation, ProviderResponse response) {
        if (response.isSuccess()) {
            activation.markCompleted(clock.instant());
            return ProcessResult.of(ProcessOutcome.DONE);
        }
        if (response.isRetryable()) {
            Duration retryAfter = response.retryAfterSeconds() != null
                    ? Duration.ofSeconds(response.retryAfterSeconds())
                    : null;
            return applyRetryOrFailure(activation, retryAfter);
        }
        activation.markFailed(clock.instant());
        return ProcessResult.of(ProcessOutcome.DONE);
    }

    private ProcessResult applyRetryOrFailure(Activation activation, Duration retryAfter) {
        int attempt = activation.registerAttempt();
        if (attempt >= maxAttempts) {
            activation.markFailed(clock.instant());
            log.info("Limite de tentativas atingido id={} attempts={}", activation.getId(), attempt);
            return ProcessResult.of(ProcessOutcome.DONE);
        }
        activation.backToPending(clock.instant());
        return new ProcessResult(ProcessOutcome.RETRY, retryAfter);
    }
}
