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
import java.util.UUID;

@Component
public class ActivationProcessor {

    private static final Logger log = LoggerFactory.getLogger(ActivationProcessor.class);

    public enum ProcessOutcome {
        SKIP,
        DONE,
        RETRY
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

    public ProcessOutcome processOnce(UUID activationId) {
        return transactionTemplate.execute(status -> doProcessOnce(activationId));
    }

    private ProcessOutcome doProcessOnce(UUID activationId) {
        Activation activation = repository.findById(activationId).orElse(null);
        if (activation == null || !activation.isProcessable()) {
            return ProcessOutcome.SKIP;
        }
        activation.markProcessing(clock.instant());
        repository.saveAndFlush(activation);

        try {
            ProviderResponse response = providerClient.activate(activation);
            return applyProviderResponse(activation, response.httpStatus());
        } catch (ResourceAccessException ex) {
            log.warn("Timeout/erro de rede ao chamar provedor id={}: {}", activationId, ex.getMessage());
            return applyRetryOrFailure(activation);
        } catch (ProviderCommunicationException ex) {
            log.warn("Falha de comunicacao com provedor id={}: {}", activationId, ex.getMessage());
            return applyRetryOrFailure(activation);
        }
    }

    private ProcessOutcome applyProviderResponse(Activation activation, int httpStatus) {
        if (httpStatus >= 200 && httpStatus < 300) {
            activation.markCompleted(clock.instant());
            return ProcessOutcome.DONE;
        }
        if (httpStatus == 408 || httpStatus == 429 || httpStatus >= 500) {
            return applyRetryOrFailure(activation);
        }
        activation.markFailed(clock.instant());
        return ProcessOutcome.DONE;
    }

    private ProcessOutcome applyRetryOrFailure(Activation activation) {
        int attempt = activation.registerAttempt();
        if (attempt >= maxAttempts) {
            activation.markFailed(clock.instant());
            log.info("Limite de tentativas atingido id={} attempts={}", activation.getId(), attempt);
            return ProcessOutcome.DONE;
        }
        activation.backToPending(clock.instant());
        return ProcessOutcome.RETRY;
    }
}
