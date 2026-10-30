package br.com.filipe.product_activation.integration;

import br.com.filipe.product_activation.integration.ActivationProcessor.ProcessOutcome;
import br.com.filipe.product_activation.integration.ActivationProcessor.ProcessResult;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.test.util.ReflectionTestUtils;

import java.time.Duration;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class ActivationDispatcherTest {

    @Mock
    private ActivationProcessor processor;

    @InjectMocks
    private ActivationDispatcher dispatcher;

    private final UUID activationId = UUID.randomUUID();

    @BeforeEach
    void setUp() {
        ReflectionTestUtils.setField(dispatcher, "mode", "SYNC");
        ReflectionTestUtils.setField(dispatcher, "maxAttempts", 3);
        ReflectionTestUtils.setField(dispatcher, "backoffMs", 1L);
        ReflectionTestUtils.setField(dispatcher, "retryAfterMaxMs", 100L);
    }

    @Test
    void waitsProviderRetryAfterWhenPresent() {
        when(processor.processOnce(activationId))
                .thenReturn(new ProcessResult(ProcessOutcome.RETRY, Duration.ofMillis(60)))
                .thenReturn(new ProcessResult(ProcessOutcome.DONE, null));

        long start = System.nanoTime();
        dispatcher.dispatch(activationId);
        long elapsedMs = (System.nanoTime() - start) / 1_000_000;

        verify(processor, times(2)).processOnce(activationId);
        assertThat(elapsedMs).isGreaterThanOrEqualTo(50);
    }

    @Test
    void capsExcessiveRetryAfterToConfiguredMaximum() {
        when(processor.processOnce(activationId))
                .thenReturn(new ProcessResult(ProcessOutcome.RETRY, Duration.ofSeconds(120)))
                .thenReturn(new ProcessResult(ProcessOutcome.DONE, null));

        long start = System.nanoTime();
        dispatcher.dispatch(activationId);
        long elapsedMs = (System.nanoTime() - start) / 1_000_000;

        verify(processor, times(2)).processOnce(activationId);
        assertThat(elapsedMs).isLessThan(5_000);
    }

    @Test
    void fallsBackToLinearBackoffWhenNoRetryAfter() {
        when(processor.processOnce(activationId))
                .thenReturn(new ProcessResult(ProcessOutcome.RETRY, null))
                .thenReturn(new ProcessResult(ProcessOutcome.DONE, null));

        dispatcher.dispatch(activationId);

        verify(processor, times(2)).processOnce(activationId);
    }

    @Test
    void disabledModeDoesNotProcess() {
        ReflectionTestUtils.setField(dispatcher, "mode", "DISABLED");

        dispatcher.dispatch(activationId);

        verify(processor, times(0)).processOnce(activationId);
    }
}
