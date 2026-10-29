package br.com.filipe.product_activation.integration;

import br.com.filipe.product_activation.model.Activation;
import br.com.filipe.product_activation.model.ActivationStatus;
import br.com.filipe.product_activation.repository.ActivationRepository;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.testcontainers.service.connection.ServiceConnection;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Primary;
import org.springframework.test.context.TestPropertySource;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;

import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.function.Supplier;

import static org.assertj.core.api.Assertions.assertThat;
import static org.awaitility.Awaitility.await;

@SpringBootTest
@Testcontainers
@TestPropertySource(properties = "app.activation.mode=SYNC")
class ActivationProcessingIntegrationTest {

    @Container
    @ServiceConnection
    static PostgreSQLContainer<?> postgres = new PostgreSQLContainer<>("postgres:16");

    @Autowired
    private br.com.filipe.product_activation.service.ActivationService activationService;

    @Autowired
    private ActivationRepository repository;

    @Autowired
    private StubProviderClient stubProviderClient;

    @TestConfiguration
    static class StubProviderConfig {
        @Bean
        StubProviderClient stubProviderClient() {
            return new StubProviderClient();
        }

        @Bean
        @Primary
        ActivationProviderClient primaryProvider(StubProviderClient stub) {
            return stub;
        }
    }

    static class StubProviderClient implements ActivationProviderClient {
        final AtomicInteger calls = new AtomicInteger();
        volatile Supplier<ProviderResponse> behavior = () -> new ProviderResponse(200, "{\"ok\":true}");

        @Override
        public ProviderResponse activate(Activation activation) {
            calls.incrementAndGet();
            return behavior.get();
        }
    }

    @Test
    void createWithSuccessfulProviderMarksCompleted() {
        stubProviderClient.calls.set(0);
        stubProviderClient.behavior = () -> new ProviderResponse(200, "{\"ok\":true}");
        var result = activationService.create(
                new br.com.filipe.product_activation.dto.ActivationRequestDTO("EXT-OK", "CUST-1", "INTERNET-500"));
        assertThat(result.created()).isTrue();

        await().atMost(java.time.Duration.ofSeconds(5)).untilAsserted(() -> {
            Activation saved = repository.findById(result.dto().id()).orElseThrow();
            assertThat(saved.getStatus()).isEqualTo(ActivationStatus.COMPLETED);
        });
        assertThat(stubProviderClient.calls.get()).isGreaterThanOrEqualTo(1);
    }

    @Test
    void createWithPermanentFailureMarksFailed() {
        stubProviderClient.behavior = () -> new ProviderResponse(400, "invalid product");
        var result = activationService.create(
                new br.com.filipe.product_activation.dto.ActivationRequestDTO("EXT-FAIL", "CUST-1", "UNKNOWN"));

        await().atMost(java.time.Duration.ofSeconds(5)).untilAsserted(() -> {
            Activation saved = repository.findById(result.dto().id()).orElseThrow();
            assertThat(saved.getStatus()).isEqualTo(ActivationStatus.FAILED);
        });
    }

    @Test
    void retryableFailureReturnsToPendingUntilMaxAttempts() {
        stubProviderClient.calls.set(0);
        stubProviderClient.behavior = () -> new ProviderResponse(500, "boom");
        var result = activationService.create(
                new br.com.filipe.product_activation.dto.ActivationRequestDTO("EXT-RETRY", "CUST-1", "INTERNET-500"));

        await().atMost(java.time.Duration.ofSeconds(10)).untilAsserted(() -> {
            Activation saved = repository.findById(result.dto().id()).orElseThrow();
            assertThat(saved.getStatus()).isEqualTo(ActivationStatus.FAILED);
            assertThat(saved.getAttemptCount()).isEqualTo(3);
        });
        assertThat(stubProviderClient.calls.get()).isEqualTo(3);
    }
}
