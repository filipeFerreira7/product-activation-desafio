package br.com.filipe.product_activation.integration;

import br.com.filipe.product_activation.dto.CreateActivationResult;
import br.com.filipe.product_activation.model.Activation;
import br.com.filipe.product_activation.model.ActivationStatus;
import br.com.filipe.product_activation.repository.ActivationRepository;
import br.com.filipe.product_activation.service.ActivationService;
import com.sun.net.httpserver.HttpServer;
import io.github.resilience4j.circuitbreaker.CircuitBreaker;
import io.github.resilience4j.circuitbreaker.CircuitBreakerRegistry;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.MethodOrderer;
import org.junit.jupiter.api.Order;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.TestMethodOrder;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.testcontainers.service.connection.ServiceConnection;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.test.context.TestPropertySource;
import org.springframework.test.web.servlet.MockMvc;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;

import java.io.IOException;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.util.concurrent.atomic.AtomicInteger;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

@SpringBootTest
@AutoConfigureMockMvc
@Testcontainers
@TestMethodOrder(MethodOrderer.OrderAnnotation.class)
@TestPropertySource(properties = {
        "app.activation.mode=SYNC",
        "app.provider.backoff-ms=10",
        "resilience4j.circuitbreaker.instances.provider.minimum-number-of-calls=2",
        "resilience4j.circuitbreaker.instances.provider.sliding-window-size=2",
        "resilience4j.circuitbreaker.instances.provider.failure-rate-threshold=50",
        "resilience4j.circuitbreaker.instances.provider.wait-duration-in-open-state=60s",
        "management.endpoints.web.exposure.include=health,info,metrics,circuitbreakers"
})
class ActivationCircuitBreakerIntegrationTest {

    @Container
    @ServiceConnection
    static PostgreSQLContainer<?> postgres = new PostgreSQLContainer<>("postgres:16");

    static final AtomicInteger providerCalls = new AtomicInteger();

    static HttpServer providerServer;

    @DynamicPropertySource
    static void providerProperties(DynamicPropertyRegistry registry) throws IOException {
        providerServer = HttpServer.create(new InetSocketAddress(0), 0);
        providerServer.createContext("/provider/v1/activate", exchange -> {
            providerCalls.incrementAndGet();
            exchange.getRequestBody().readAllBytes();
            byte[] body = "{\"error\":\"unavailable\"}".getBytes(StandardCharsets.UTF_8);
            exchange.getResponseHeaders().add("Content-Type", "application/json");
            exchange.sendResponseHeaders(500, body.length);
            exchange.getResponseBody().write(body);
            exchange.close();
        });
        providerServer.start();
        registry.add("app.provider.base-url",
                () -> "http://localhost:" + providerServer.getAddress().getPort());
    }

    @AfterAll
    static void stopProviderServer() {
        if (providerServer != null) {
            providerServer.stop(0);
        }
    }

    @Autowired
    private ActivationService activationService;

    @Autowired
    private ActivationRepository repository;

    @Autowired
    private CircuitBreakerRegistry circuitBreakerRegistry;

    @Autowired
    private MockMvc mockMvc;

    private CreateActivationResult create(String externalId) {
        return activationService.create(new br.com.filipe.product_activation.dto.ActivationRequestDTO(
                externalId, "CUST-1", "INTERNET-500"));
    }

    @Test
    @Order(1)
    void providerFailuresOpenCircuitBreakerAndActivationFailsAfterLocalRetries() {
        CreateActivationResult result = create("EXT-CB-1");

        Activation saved = repository.findById(result.dto().id()).orElseThrow();
        assertThat(saved.getStatus()).isEqualTo(ActivationStatus.FAILED);
        assertThat(saved.getAttemptCount()).isEqualTo(3);
        // 1a e 2a tentativa chamam o provedor (500); a 2a falha abre o breaker;
        // a 3a tentativa e cortada pelo breaker (fallback 503) sem tocar a rede.
        assertThat(providerCalls.get()).isEqualTo(2);
        assertThat(circuitBreakerRegistry.circuitBreaker("provider").getState())
                .isEqualTo(CircuitBreaker.State.OPEN);
    }

    @Test
    @Order(2)
    void openBreakerShortCircuitsWithoutCallingProvider() {
        int callsBefore = providerCalls.get();

        CreateActivationResult result = create("EXT-CB-2");

        Activation saved = repository.findById(result.dto().id()).orElseThrow();
        assertThat(saved.getStatus()).isEqualTo(ActivationStatus.FAILED);
        assertThat(providerCalls.get()).isEqualTo(callsBefore);
        assertThat(circuitBreakerRegistry.circuitBreaker("provider").getState())
                .isEqualTo(CircuitBreaker.State.OPEN);
    }

    @Test
    @Order(3)
    void breakerStateIsExposedViaActuator() throws Exception {
        mockMvc.perform(get("/actuator/circuitbreakers"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.circuitBreakers.provider.state").value("OPEN"));
    }
}
