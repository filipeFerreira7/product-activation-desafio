package br.com.filipe.product_activation.integration;

import br.com.filipe.product_activation.model.Activation;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.MediaType;
import org.springframework.stereotype.Component;
import org.springframework.web.client.RestClient;

import java.nio.charset.StandardCharsets;
import java.util.Map;

@Component
public class RestClientActivationProviderClient implements ActivationProviderClient {

    @Autowired
    private RestClient restClient;

    @Override
    public ProviderResponse activate(Activation activation) {
        Map<String, Object> body = Map.of(
                "externalId", activation.getExternalId(),
                "customerId", activation.getCustomerId(),
                "productCode", activation.getProductCode()
        );
        return restClient.post()
                .uri("/provider/v1/activate")
                .contentType(MediaType.APPLICATION_JSON)
                .header("Idempotency-Key", activation.getExternalId())
                .body(body)
                .exchange((request, response) -> {
                    String responseBody = "";
                    if (response.getBody() != null) {
                        responseBody = new String(response.getBody().readAllBytes(), StandardCharsets.UTF_8);
                    }
                    return new ProviderResponse(response.getStatusCode().value(), responseBody);
                });
    }
}
