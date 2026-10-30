package br.com.filipe.product_activation.integration;

import br.com.filipe.product_activation.dto.ProviderRequestDTO;
import br.com.filipe.product_activation.dto.ProviderResponseDTO;
import br.com.filipe.product_activation.model.Activation;
import io.github.resilience4j.circuitbreaker.annotation.CircuitBreaker;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.MediaType;
import org.springframework.stereotype.Component;
import org.springframework.web.client.RestClient;

import java.nio.charset.StandardCharsets;
import java.util.Map;

@Component
public class RestClientActivationProviderClient implements ActivationProviderClient {

    private static final Logger log = LoggerFactory.getLogger(RestClientActivationProviderClient.class);

    @Autowired
    private RestClient restClient;

    @Override
    @CircuitBreaker(name = "provider", fallbackMethod = "fallbackWhenUnavailable")
    public ProviderResponseDTO activate(ProviderRequestDTO request) {
        Map<String, Object> body = Map.of(
                "externalId", request.externalId(),
                "customerId", request.costumerId(),
                "productCode", request.productCode()
        );
        return restClient.post()
                .uri("/provider/v1/activate")
                .contentType(MediaType.APPLICATION_JSON)
                .header("Idempotency-Key", request.externalId())
                .body(body)
                .exchange((req, response) -> {
                    String responseBody = "";
                    if (response.getBody() != null) {
                        responseBody = new String(response.getBody().readAllBytes(), StandardCharsets.UTF_8);
                    }
                    return new ProviderResponseDTO(
                            response.getStatusCode().value(),
                            responseBody,
                            parseRetryAfterSeconds(response.getHeaders().getFirst("Retry-After")));
                });
    }

    private Long parseRetryAfterSeconds(String header) {
        if (header == null || header.isBlank()) {
            return null;
        }
        try {
            return Long.parseLong(header.trim());
        } catch (NumberFormatException ex) {
            log.debug("Header Retry-After nao interpretavel como segundos: {}", header);
            return null;
        }
    }

    private ProviderResponseDTO fallbackWhenUnavailable(ProviderRequestDTO request, Throwable cause) {
        log.warn("Circuit breaker aberto ou falha de chamada para activationId={}: {}",
                request.id(), cause.toString());
        return new ProviderResponseDTO(503, "provedor indisponivel (circuit breaker aberto)");
    }
}
