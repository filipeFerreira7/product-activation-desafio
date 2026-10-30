package br.com.filipe.product_activation.integration;

import br.com.filipe.product_activation.model.Activation;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpMethod;
import org.springframework.http.MediaType;
import org.springframework.test.util.ReflectionTestUtils;
import org.springframework.test.web.client.MockRestServiceServer;
import org.springframework.web.client.RestClient;

import java.net.SocketTimeoutException;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.springframework.test.web.client.ExpectedCount.once;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.header;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.method;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.requestTo;
import static org.springframework.test.web.client.response.MockRestResponseCreators.withException;
import static org.springframework.test.web.client.response.MockRestResponseCreators.withStatus;
import static org.springframework.test.web.client.response.MockRestResponseCreators.withSuccess;
import static org.springframework.http.HttpStatus.BAD_REQUEST;

class RestClientActivationProviderClientTest {

    private final Clock clock = Clock.fixed(Instant.parse("2026-10-26T14:03:11Z"), ZoneOffset.UTC);

    private Activation activation() {
        return Activation.newActivation("EXT-12345", "CUST-10001", "INTERNET-500", clock.instant());
    }

    private MockRestServiceServer server;
    private RestClientActivationProviderClient client;

    private void setUp() {
        RestClient.Builder builder = RestClient.builder().baseUrl("http://provider.test");
        server = MockRestServiceServer.bindTo(builder).build();
        client = new RestClientActivationProviderClient();
        ReflectionTestUtils.setField(client, "restClient", builder.build());
    }

    @Test
    void successReturns2xx() {
        setUp();
        server.expect(once(), requestTo("http://provider.test/provider/v1/activate"))
                .andExpect(method(HttpMethod.POST))
                .andExpect(header("Idempotency-Key", "EXT-12345"))
                .andRespond(withSuccess("{\"activationId\":\"prov-1\"}", MediaType.APPLICATION_JSON));

        ProviderResponse response = client.activate(activation());

        assertThat(response.isSuccess()).isTrue();
        assertThat(response.httpStatus()).isEqualTo(200);
        server.verify();
    }

    @Test
    void clientError400IsNotRetryable() {
        setUp();
        server.expect(once(), requestTo("http://provider.test/provider/v1/activate"))
                .andRespond(withStatus(BAD_REQUEST).body("invalid"));

        ProviderResponse response = client.activate(activation());

        assertThat(response.isSuccess()).isFalse();
        assertThat(response.isRetryable()).isFalse();
        assertThat(response.httpStatus()).isEqualTo(400);
    }

    @Test
    void serverError500IsRetryable() {
        setUp();
        server.expect(once(), requestTo("http://provider.test/provider/v1/activate"))
                .andRespond(withStatus(org.springframework.http.HttpStatus.INTERNAL_SERVER_ERROR));

        ProviderResponse response = client.activate(activation());

        assertThat(response.isRetryable()).isTrue();
        assertThat(response.httpStatus()).isEqualTo(500);
    }

    @Test
    void timeoutRaisesResourceAccessException() {
        setUp();
        server.expect(once(), requestTo("http://provider.test/provider/v1/activate"))
                .andRespond(withException(new SocketTimeoutException("read timed out")));

        assertThatThrownBy(() -> client.activate(activation()))
                .isInstanceOf(org.springframework.web.client.ResourceAccessException.class)
                .hasMessageContaining("timed out");
    }

    @Test
    void retryAfterHeaderIsParsedAsSeconds() {
        setUp();
        server.expect(once(), requestTo("http://provider.test/provider/v1/activate"))
                .andRespond(withStatus(org.springframework.http.HttpStatus.TOO_MANY_REQUESTS)
                        .header("Retry-After", "5")
                        .body("slow down"));

        ProviderResponse response = client.activate(activation());

        assertThat(response.isRetryable()).isTrue();
        assertThat(response.retryAfterSeconds()).isEqualTo(5L);
    }

    @Test
    void missingRetryAfterHeaderYieldsNull() {
        setUp();
        server.expect(once(), requestTo("http://provider.test/provider/v1/activate"))
                .andRespond(withStatus(org.springframework.http.HttpStatus.SERVICE_UNAVAILABLE));

        ProviderResponse response = client.activate(activation());

        assertThat(response.retryAfterSeconds()).isNull();
    }

    @Test
    void nonNumericRetryAfterHeaderYieldsNull() {
        setUp();
        server.expect(once(), requestTo("http://provider.test/provider/v1/activate"))
                .andRespond(withStatus(org.springframework.http.HttpStatus.SERVICE_UNAVAILABLE)
                        .header("Retry-After", "Wed, 21 Oct 2026 07:28:00 GMT"));

        ProviderResponse response = client.activate(activation());

        assertThat(response.isRetryable()).isTrue();
        assertThat(response.retryAfterSeconds()).isNull();
    }
}
