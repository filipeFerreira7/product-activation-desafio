package br.com.filipe.product_activation.integration;

import br.com.filipe.product_activation.dto.ProviderRequestDTO;
import br.com.filipe.product_activation.dto.ProviderResponseDTO;
import br.com.filipe.product_activation.exception.ProviderCommunicationException;
import br.com.filipe.product_activation.model.Activation;
import br.com.filipe.product_activation.model.ActivationStatus;
import br.com.filipe.product_activation.repository.ActivationRepository;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.orm.ObjectOptimisticLockingFailureException;
import org.springframework.stereotype.Component;
import org.springframework.transaction.support.TransactionTemplate;
import org.springframework.web.client.ResourceAccessException;

import java.security.Provider;
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

    private sealed interface ProviderCall permits Answered, NetworkFailure{}
    private record Answered(ProviderResponseDTO response) implements ProviderCall {}
    private record NetworkFailure(String reason) implements ProviderCall {}

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
        ProviderRequestDTO request;

        try{
            request = transactionTemplate.execute(status -> claim(activationId));
        } catch (ObjectOptimisticLockingFailureException ex) {
            log.info("Solicitacao id={} reivindicada por outro worker", activationId);
            return ProcessResult.of(ProcessOutcome.SKIP);
        }
        if (request == null){
            return ProcessResult.of(ProcessOutcome.SKIP);
        }

        //sem transacao (chamada HTTP)
        ProviderCall call = callProvider(request);

        // tx curta: grava o resultado

        try{
            return transactionTemplate.execute(status -> finish(activationId, call));
        } catch (ObjectOptimisticLockingFailureException ex){
            log.warn("Resultado do provedor não gravado por conflito de versão id{}",activationId);
            return ProcessResult.of(ProcessOutcome.SKIP);
        }
    }

    private ProcessResult finish(UUID activationID, ProviderCall call) {
        Activation activation = repository.findById(activationID).orElse(null);
        if(activation == null || activation.getStatus() != ActivationStatus.PROCESSING){
            log.warn("Solicitacao id={} nao esta mais em PROCESSING, resultado descartado", activationID);
            return ProcessResult.of(ProcessOutcome.SKIP);
        }
        return switch (call) {
            case Answered answered -> applyProviderResponse(activation, answered.response());
            case NetworkFailure failure -> applyRetryOrFailure(activation,null);
        };
    }

    private ProviderCall callProvider(ProviderRequestDTO request) {
        try {
            return new Answered(providerClient.activate(request));
        } catch (ResourceAccessException ex) {
            log.warn("Timeout/erro de rede ao chamar provedor id{}: {}", request.id(), ex.getMessage());
            return new NetworkFailure(ex.getMessage());
        } catch (ProviderCommunicationException ex){
            log.warn("Erro de comunicação com provedor id{}: {}", request.id(), ex.getMessage());
            return new NetworkFailure(ex.getMessage());
        } catch (RuntimeException ex) {
            log.error("Erro inesperado ao chamar o provedor id={}",request.id(), ex);
            return new NetworkFailure(ex.getMessage());
        }
    }


    private ProviderRequestDTO claim(UUID activationId) {
        Activation activation = repository.findById(activationId).orElse(null);
        if(activation == null || !activation.isProcessable()){
            return null;
        }
        activation.markProcessing(clock.instant());
        repository.saveAndFlush(activation);

        return new ProviderRequestDTO(
                activation.getId(),
                activation.getExternalId(),
                activation.getCustomerId(),
                activation.getProductCode()

        );
    }



    private ProcessResult applyProviderResponse(Activation activation, ProviderResponseDTO response) {
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
