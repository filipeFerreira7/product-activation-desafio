package br.com.filipe.product_activation.integration;

import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.testcontainers.service.connection.ServiceConnection;
import org.springframework.test.context.TestPropertySource;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.MvcResult;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

@SpringBootTest
@AutoConfigureMockMvc
@Testcontainers
@TestPropertySource(properties = {
        "app.activation.mode=DISABLED",
        "resilience4j.ratelimiter.instances.api.limit-for-period=2",
        "resilience4j.ratelimiter.instances.api.limit-refresh-period=1m",
        "resilience4j.ratelimiter.instances.api.timeout-duration=0"
})
class ActivationRateLimitIntegrationTest {

    @Container
    @ServiceConnection
    static PostgreSQLContainer<?> postgres = new PostgreSQLContainer<>("postgres:16");

    @Autowired
    private MockMvc mockMvc;

    @Test
    void returns429AfterExceedingConfiguredRateLimit() throws Exception {
        mockMvc.perform(get("/api/v1/requests"))
                .andExpect(status().isOk());
        mockMvc.perform(get("/api/v1/requests"))
                .andExpect(status().isOk());

        MvcResult third = mockMvc.perform(get("/api/v1/requests"))
                .andExpect(status().isTooManyRequests())
                .andReturn();

        String body = third.getResponse().getContentAsString();
        assertThat(body).contains("Limite de requisicoes excedido");
    }
}
