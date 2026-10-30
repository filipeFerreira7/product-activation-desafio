package br.com.filipe.product_activation.integration;

import br.com.filipe.product_activation.dto.ProviderRequestDTO;
import br.com.filipe.product_activation.dto.ProviderResponseDTO;
import br.com.filipe.product_activation.model.Activation;
import br.com.filipe.product_activation.model.ActivationStatus;
import br.com.filipe.product_activation.repository.ActivationRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.InOrder;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.orm.ObjectOptimisticLockingFailureException;
import org.springframework.test.util.ReflectionTestUtils;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.TransactionDefinition;
import org.springframework.transaction.support.SimpleTransactionStatus;
import org.springframework.transaction.support.TransactionTemplate;
import org.springframework.web.client.ResourceAccessException;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.Optional;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.doNothing;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.inOrder;
import static org.mockito.Mockito.lenient;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class RestClientActivationProviderClientTest {

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

    private void givenActivationIsStored(Activation activation) {
        when(repository.findById(activation.getId())).thenReturn(Optional.of(activation));
        when(repository.saveAndFlush(any())).thenAnswer(inv -> inv.getArgument(0));
    }

    @Test
    void successMarksCompleted() {
        Activation activation = pending();
        givenActivationIsStored(activation);
        when(providerClient.activate(any())).thenReturn(new ProviderResponseDTO(200, "{\"ok\":true}"));

        ActivationProcessor.ProcessResult result = processor.processOnce(activation.getId());

        assertThat(result.outcome()).isEqualTo(ActivationProcessor.ProcessOutcome.DONE);
        assertThat(activation.getStatus()).isEqualTo(ActivationStatus.COMPLETED);
    }

    @Test
    void clientError400MarksFailedPermanently() {
        Activation activation = pending();
        givenActivationIsStored(activation);
        when(providerClient.activate(any())).thenReturn(new ProviderResponseDTO(400, "bad request"));

        ActivationProcessor.ProcessResult result = processor.processOnce(activation.getId());

        assertThat(result.outcome()).isEqualTo(ActivationProcessor.ProcessOutcome.DONE);
        assertThat(activation.getStatus()).isEqualTo(ActivationStatus.FAILED);
        assertThat(activation.getAttemptCount()).isZero();
    }

    @Test
    void serverError500RequestsRetryWithoutRetryAfter() {
        Activation activation = pending();
        givenActivationIsStored(activation);
        when(providerClient.activate(any())).thenReturn(new ProviderResponseDTO(500, "boom"));

        ActivationProcessor.ProcessResult result = processor.processOnce(activation.getId());

        assertThat(result.outcome()).isEqualTo(ActivationProcessor.ProcessOutcome.RETRY);
        assertThat(result.retryAfter()).isNull();
        assertThat(activation.getStatus()).isEqualTo(ActivationStatus.PENDING);
        assertThat(activation.getAttemptCount()).isEqualTo(1);
    }

    @Test
    void retryableStatusWithRetryAfterCarriesDelayToResult() {
        Activation activation = pending();
        givenActivationIsStored(activation);
        when(providerClient.activate(any())).thenReturn(new ProviderResponseDTO(429, "slow down", 7L));

        ActivationProcessor.ProcessResult result = processor.processOnce(activation.getId());

        assertThat(result.outcome()).isEqualTo(ActivationProcessor.ProcessOutcome.RETRY);
        assertThat(result.retryAfter()).isEqualTo(Duration.ofSeconds(7));
        assertThat(activation.getStatus()).isEqualTo(ActivationStatus.PENDING);
    }

    @Test
    void timeoutRequestsRetry() {
        Activation activation = pending();
        givenActivationIsStored(activation);
        when(providerClient.activate(any())).thenThrow(new ResourceAccessException("timeout"));

        ActivationProcessor.ProcessResult result = processor.processOnce(activation.getId());

        assertThat(result.outcome()).isEqualTo(ActivationProcessor.ProcessOutcome.RETRY);
        assertThat(result.retryAfter()).isNull();
        assertThat(activation.getStatus()).isEqualTo(ActivationStatus.PENDING);
        assertThat(activation.getAttemptCount()).isEqualTo(1);
    }

    @Test
    void unexpectedExceptionFromProviderRequestsRetryInsteadOfLeavingProcessing() {
        Activation activation = pending();
        givenActivationIsStored(activation);
        when(providerClient.activate(any())).thenThrow(new IllegalStateException("inesperado"));

        ActivationProcessor.ProcessResult result = processor.processOnce(activation.getId());

        assertThat(result.outcome()).isEqualTo(ActivationProcessor.ProcessOutcome.RETRY);
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
        givenActivationIsStored(activation);
        when(providerClient.activate(any())).thenReturn(new ProviderResponseDTO(503, "unavailable"));

        ActivationProcessor.ProcessResult result = processor.processOnce(activation.getId());

        assertThat(result.outcome()).isEqualTo(ActivationProcessor.ProcessOutcome.DONE);
        assertThat(activation.getStatus()).isEqualTo(ActivationStatus.FAILED);
        assertThat(activation.getAttemptCount()).isEqualTo(3);
    }

    @Test
    void skipsAlreadyCompleted() {
        Activation activation = pending();
        activation.markProcessing(clock.instant());
        activation.markCompleted(clock.instant());
        when(repository.findById(activation.getId())).thenReturn(Optional.of(activation));

        ActivationProcessor.ProcessResult result = processor.processOnce(activation.getId());

        assertThat(result.outcome()).isEqualTo(ActivationProcessor.ProcessOutcome.SKIP);
        verify(providerClient, never()).activate(any());
    }

    @Test
    void skipsMissingActivation() {
        UUID missing = UUID.randomUUID();
        when(repository.findById(missing)).thenReturn(Optional.empty());

        ActivationProcessor.ProcessResult result = processor.processOnce(missing);

        assertThat(result.outcome()).isEqualTo(ActivationProcessor.ProcessOutcome.SKIP);
        verify(providerClient, never()).activate(any());
    }

    // ---- Testes das tres fases (claim / chamada HTTP / finish) ----

    @Test
    void sendsActivationDataToProviderAsDto() {
        Activation activation = pending();
        givenActivationIsStored(activation);
        when(providerClient.activate(any())).thenReturn(new ProviderResponseDTO(200, "ok"));

        processor.processOnce(activation.getId());

        ArgumentCaptor<ProviderRequestDTO> captor = ArgumentCaptor.forClass(ProviderRequestDTO.class);
        verify(providerClient).activate(captor.capture());
        ProviderRequestDTO sent = captor.getValue();
        assertThat(sent.id()).isEqualTo(activation.getId());
        assertThat(sent.externalId()).isEqualTo("EXT-1");
        assertThat(sent.costumerId()).isEqualTo("CUST-1");
        assertThat(sent.productCode()).isEqualTo("INTERNET-500");
    }

    @Test
    void statusIsProcessingWhileProviderIsCalled() {
        Activation activation = pending();
        givenActivationIsStored(activation);
        when(providerClient.activate(any())).thenAnswer(inv -> {
            assertThat(activation.getStatus()).isEqualTo(ActivationStatus.PROCESSING);
            return new ProviderResponseDTO(200, "ok");
        });

        processor.processOnce(activation.getId());

        assertThat(activation.getStatus()).isEqualTo(ActivationStatus.COMPLETED);
    }

    @Test
    void firstTransactionCommitsBeforeProviderCallAndSecondAfter() {
        Activation activation = pending();
        givenActivationIsStored(activation);
        when(providerClient.activate(any())).thenReturn(new ProviderResponseDTO(200, "ok"));

        processor.processOnce(activation.getId());

        InOrder order = inOrder(transactionManager, providerClient);
        order.verify(transactionManager).commit(any());
        order.verify(providerClient).activate(any());
        order.verify(transactionManager).commit(any());
    }

    @Test
    void discardsProviderResultWhenStatusChangedDuringCall() {
        Activation activation = pending();
        givenActivationIsStored(activation);
        when(providerClient.activate(any())).thenAnswer(inv -> {
            activation.markFailed(clock.instant());
            return new ProviderResponseDTO(200, "ok");
        });

        ActivationProcessor.ProcessResult result = processor.processOnce(activation.getId());

        assertThat(result.outcome()).isEqualTo(ActivationProcessor.ProcessOutcome.SKIP);
        assertThat(activation.getStatus()).isEqualTo(ActivationStatus.FAILED);
    }

    @Test
    void skipsWhenAnotherWorkerClaimedFirst() {
        Activation activation = pending();
        when(repository.findById(activation.getId())).thenReturn(Optional.of(activation));
        when(repository.saveAndFlush(any()))
                .thenThrow(new ObjectOptimisticLockingFailureException(Activation.class, activation.getId()));

        ActivationProcessor.ProcessResult result = processor.processOnce(activation.getId());

        assertThat(result.outcome()).isEqualTo(ActivationProcessor.ProcessOutcome.SKIP);
        verify(providerClient, never()).activate(any());
    }

    @Test
    void skipsWhenResultCannotBeSavedDueToVersionConflict() {
        Activation activation = pending();
        givenActivationIsStored(activation);
        when(providerClient.activate(any())).thenReturn(new ProviderResponseDTO(200, "ok"));
        doNothing()
                .doThrow(new ObjectOptimisticLockingFailureException(Activation.class, activation.getId()))
                .when(transactionManager).commit(any());

        ActivationProcessor.ProcessResult result = processor.processOnce(activation.getId());

        assertThat(result.outcome()).isEqualTo(ActivationProcessor.ProcessOutcome.SKIP);
    }
}