package br.com.filipe.product_activation.integration;

import br.com.filipe.product_activation.model.Activation;
import br.com.filipe.product_activation.model.ActivationStatus;
import br.com.filipe.product_activation.repository.ActivationRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.test.util.ReflectionTestUtils;
import org.springframework.web.client.ResourceAccessException;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.TransactionDefinition;
import org.springframework.transaction.support.SimpleTransactionStatus;
import org.springframework.transaction.support.TransactionTemplate;

import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.Optional;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.lenient;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class ActivationProcessorTest {

    @Mock
    private ActivationRepository repository;

    @Mock
    private ActivationProviderClient providerClient;

    @Mock
    private PlatformTransactionManager transactionManager;

    @InjectMocks
    private ActivationProcessor processor;

    private final Clock clock = Clock.fixed(Instant.parse("2026-10-26T14:03:11Z"), ZoneOffset.UTC);

    @BeforeEach
    void setUp() {
        lenient().when(transactionManager.getTransaction(any())).thenReturn(new SimpleTransactionStatus());
        TransactionTemplate template = new TransactionTemplate(transactionManager);
        template.setPropagationBehavior(TransactionDefinition.PROPAGATION_REQUIRES_NEW);
        ReflectionTestUtils.setField(processor, "clock", clock);
        ReflectionTestUtils.setField(processor, "transactionTemplate", template);
        ReflectionTestUtils.setField(processor, "maxAttempts", 3);
    }

    private Activation pending() {
        return Activation.newActivation("EXT-1", "CUST-1", "INTERNET-500", clock.instant());
    }

    @Test
    void successMarksCompleted() {
        Activation activation = pending();
        when(repository.findById(activation.getId())).thenReturn(Optional.of(activation));
        when(repository.saveAndFlush(any())).thenAnswer(inv -> inv.getArgument(0));
        when(providerClient.activate(any())).thenReturn(new ProviderResponse(200, "{\"ok\":true}"));

        ActivationProcessor.ProcessOutcome outcome = processor.processOnce(activation.getId());

        assertThat(outcome).isEqualTo(ActivationProcessor.ProcessOutcome.DONE);
        assertThat(activation.getStatus()).isEqualTo(ActivationStatus.COMPLETED);
    }

    @Test
    void clientError400MarksFailedPermanently() {
        Activation activation = pending();
        when(repository.findById(activation.getId())).thenReturn(Optional.of(activation));
        when(repository.saveAndFlush(any())).thenAnswer(inv -> inv.getArgument(0));
        when(providerClient.activate(any())).thenReturn(new ProviderResponse(400, "bad request"));

        ActivationProcessor.ProcessOutcome outcome = processor.processOnce(activation.getId());

        assertThat(outcome).isEqualTo(ActivationProcessor.ProcessOutcome.DONE);
        assertThat(activation.getStatus()).isEqualTo(ActivationStatus.FAILED);
        assertThat(activation.getAttemptCount()).isZero();
    }

    @Test
    void serverError500RequestsRetry() {
        Activation activation = pending();
        when(repository.findById(activation.getId())).thenReturn(Optional.of(activation));
        when(repository.saveAndFlush(any())).thenAnswer(inv -> inv.getArgument(0));
        when(providerClient.activate(any())).thenReturn(new ProviderResponse(500, "boom"));

        ActivationProcessor.ProcessOutcome outcome = processor.processOnce(activation.getId());

        assertThat(outcome).isEqualTo(ActivationProcessor.ProcessOutcome.RETRY);
        assertThat(activation.getStatus()).isEqualTo(ActivationStatus.PENDING);
        assertThat(activation.getAttemptCount()).isEqualTo(1);
    }

    @Test
    void timeoutRequestsRetry() {
        Activation activation = pending();
        when(repository.findById(activation.getId())).thenReturn(Optional.of(activation));
        when(repository.saveAndFlush(any())).thenAnswer(inv -> inv.getArgument(0));
        when(providerClient.activate(any())).thenThrow(new ResourceAccessException("timeout"));

        ActivationProcessor.ProcessOutcome outcome = processor.processOnce(activation.getId());

        assertThat(outcome).isEqualTo(ActivationProcessor.ProcessOutcome.RETRY);
        assertThat(activation.getStatus()).isEqualTo(ActivationStatus.PENDING);
        assertThat(activation.getAttemptCount()).isEqualTo(1);
    }

    @Test
    void maxAttemptsReachedMarksFailed() {
        Activation activation = pending();
        activation.markProcessing(clock.instant());
        activation.registerAttempt();
        activation.registerAttempt();
        activation.backToPending(clock.instant());
        when(repository.findById(activation.getId())).thenReturn(Optional.of(activation));
        when(repository.saveAndFlush(any())).thenAnswer(inv -> inv.getArgument(0));
        when(providerClient.activate(any())).thenReturn(new ProviderResponse(503, "unavailable"));

        ActivationProcessor.ProcessOutcome outcome = processor.processOnce(activation.getId());

        assertThat(outcome).isEqualTo(ActivationProcessor.ProcessOutcome.DONE);
        assertThat(activation.getStatus()).isEqualTo(ActivationStatus.FAILED);
        assertThat(activation.getAttemptCount()).isEqualTo(3);
    }

    @Test
    void skipsAlreadyCompleted() {
        Activation activation = pending();
        activation.markProcessing(clock.instant());
        activation.markCompleted(clock.instant());
        when(repository.findById(activation.getId())).thenReturn(Optional.of(activation));

        ActivationProcessor.ProcessOutcome outcome = processor.processOnce(activation.getId());

        assertThat(outcome).isEqualTo(ActivationProcessor.ProcessOutcome.SKIP);
        verify(providerClient, never()).activate(any());
    }

    @Test
    void skipsMissingActivation() {
        UUID missing = UUID.randomUUID();
        when(repository.findById(missing)).thenReturn(Optional.empty());

        ActivationProcessor.ProcessOutcome outcome = processor.processOnce(missing);

        assertThat(outcome).isEqualTo(ActivationProcessor.ProcessOutcome.SKIP);
        verify(providerClient, never()).activate(any());
    }
}
