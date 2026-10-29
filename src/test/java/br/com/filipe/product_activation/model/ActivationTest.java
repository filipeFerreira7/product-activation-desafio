package br.com.filipe.product_activation.model;

import br.com.filipe.product_activation.exception.InvalidStatusTransitionException;
import org.junit.jupiter.api.Test;

import java.time.Instant;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class ActivationTest {

    private static final Instant T0 = Instant.parse("2026-10-26T14:03:11Z");
    private static final Instant T1 = Instant.parse("2026-10-26T14:05:00Z");

    private Activation pending() {
        return Activation.newActivation("EXT-1", "CUST-1", "INTERNET-500", T0);
    }

    @Test
    void newActivationStartsPendingWithZeroAttempts() {
        Activation activation = pending();
        assertThat(activation.getStatus()).isEqualTo(ActivationStatus.PENDING);
        assertThat(activation.getAttemptCount()).isZero();
        assertThat(activation.getCreatedAt()).isEqualTo(T0);
        assertThat(activation.getUpdatedAt()).isEqualTo(T0);
        assertThat(activation.getId()).isNotNull();
    }

    @Test
    void pendingToProcessingToCompleted() {
        Activation activation = pending();
        activation.markProcessing(T1);
        assertThat(activation.getStatus()).isEqualTo(ActivationStatus.PROCESSING);
        activation.markCompleted(T1);
        assertThat(activation.getStatus()).isEqualTo(ActivationStatus.COMPLETED);
        assertThat(activation.getUpdatedAt()).isEqualTo(T1);
    }

    @Test
    void processingCanFail() {
        Activation activation = pending();
        activation.markProcessing(T1);
        activation.markFailed(T1);
        assertThat(activation.getStatus()).isEqualTo(ActivationStatus.FAILED);
    }

    @Test
    void retryableFailureReturnsToPendingAndIncrementsAttempts() {
        Activation activation = pending();
        activation.markProcessing(T1);
        int attempt = activation.registerAttempt();
        activation.backToPending(T1);
        assertThat(activation.getStatus()).isEqualTo(ActivationStatus.PENDING);
        assertThat(attempt).isEqualTo(1);
        assertThat(activation.getAttemptCount()).isEqualTo(1);
    }

    @Test
    void completedCannotGoBackToProcessing() {
        Activation activation = pending();
        activation.markProcessing(T1);
        activation.markCompleted(T1);
        assertThatThrownBy(() -> activation.markProcessing(T1))
                .isInstanceOf(InvalidStatusTransitionException.class)
                .hasMessageContaining("COMPLETED -> PROCESSING");
    }

    @Test
    void completedCannotFail() {
        Activation activation = pending();
        activation.markProcessing(T1);
        activation.markCompleted(T1);
        assertThatThrownBy(() -> activation.markFailed(T1))
                .isInstanceOf(InvalidStatusTransitionException.class);
    }

    @Test
    void pendingCannotCompleteDirectly() {
        Activation activation = pending();
        assertThatThrownBy(() -> activation.markCompleted(T1))
                .isInstanceOf(InvalidStatusTransitionException.class);
    }

    @Test
    void processingCannotGoBackToPendingTwiceWithoutProcessingAgain() {
        Activation activation = pending();
        activation.markProcessing(T1);
        activation.backToPending(T1);
        assertThatThrownBy(() -> activation.backToPending(T1))
                .isInstanceOf(InvalidStatusTransitionException.class);
    }

    @Test
    void onlyPendingIsProcessable() {
        Activation activation = pending();
        assertThat(activation.isProcessable()).isTrue();
        activation.markProcessing(T1);
        assertThat(activation.isProcessable()).isFalse();
    }
}
