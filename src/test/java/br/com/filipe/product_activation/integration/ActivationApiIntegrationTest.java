package br.com.filipe.product_activation.integration;

import br.com.filipe.product_activation.model.ActivationStatus;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.testcontainers.service.connection.ServiceConnection;
import org.springframework.http.MediaType;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.TestPropertySource;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.MvcResult;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;

import java.util.List;
import java.util.UUID;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.header;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

@SpringBootTest
@AutoConfigureMockMvc
@Testcontainers
@TestPropertySource(properties = "app.activation.mode=DISABLED")
class ActivationApiIntegrationTest {

    @Container
    @ServiceConnection
    static PostgreSQLContainer<?> postgres = new PostgreSQLContainer<>("postgres:16");

    @Autowired
    private MockMvc mockMvc;

    @Autowired
    private JdbcTemplate jdbcTemplate;

    private String body(String externalId, String customerId, String productCode) {
        return """
                {
                  "externalId": "%s",
                  "customerId": "%s",
                  "productCode": "%s"
                }
                """.formatted(externalId, customerId, productCode);
    }

    @Test
    void createThenGetReturnsRegisteredRequest() throws Exception {
        MvcResult created = mockMvc.perform(post("/api/v1/requests")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(body("EXT-100", "CUST-1", "INTERNET-500")))
                .andExpect(status().isCreated())
                .andExpect(header().exists("Location"))
                .andExpect(jsonPath("$.status").value("PENDING"))
                .andReturn();

        String id = com.jayway.jsonpath.JsonPath.parse(created.getResponse().getContentAsString()).read("$.id");

        mockMvc.perform(get("/api/v1/requests/" + id))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.id").value(id))
                .andExpect(jsonPath("$.externalId").value("EXT-100"));
    }

    @Test
    void getByIdReturns404WhenMissing() throws Exception {
        mockMvc.perform(get("/api/v1/requests/" + UUID.randomUUID()))
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.title").value("Solicitacao nao encontrada"));
    }

    @Test
    void invalidBodyReturns400() throws Exception {
        mockMvc.perform(post("/api/v1/requests")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(body("", "", "")))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.errors").isArray());
    }

    @Test
    void duplicateSameContentReturns200WithSameId() throws Exception {
        MvcResult first = mockMvc.perform(post("/api/v1/requests")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(body("EXT-DUP", "CUST-1", "INTERNET-500")))
                .andExpect(status().isCreated())
                .andReturn();
        String id = com.jayway.jsonpath.JsonPath.parse(first.getResponse().getContentAsString()).read("$.id");

        mockMvc.perform(post("/api/v1/requests")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(body("EXT-DUP", "CUST-1", "INTERNET-500")))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.id").value(id));

        Integer count = jdbcTemplate.queryForObject(
                "SELECT COUNT(*) FROM activations WHERE external_id = 'EXT-DUP'", Integer.class);
        assertThat(count).isEqualTo(1);
    }

    @Test
    void duplicateDifferentContentReturns409() throws Exception {
        mockMvc.perform(post("/api/v1/requests")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(body("EXT-DIFF", "CUST-1", "INTERNET-500")))
                .andExpect(status().isCreated());

        mockMvc.perform(post("/api/v1/requests")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(body("EXT-DIFF", "CUST-1", "INTERNET-1000")))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.title").value("Solicitacao duplicada"));
    }

    @Test
    void concurrentSameExternalIdResultsInExactlyOneRecord() throws Exception {
        String externalId = "EXT-RACE-" + UUID.randomUUID();
        int threads = 8;
        ExecutorService pool = Executors.newFixedThreadPool(threads);
        CountDownLatch ready = new CountDownLatch(threads);
        CountDownLatch go = new CountDownLatch(1);
        List<Future<Integer>> futures = java.util.stream.IntStream.range(0, threads)
                .mapToObj(i -> pool.submit(() -> {
                    ready.countDown();
                    go.await();
                    MvcResult result = mockMvc.perform(post("/api/v1/requests")
                                    .contentType(MediaType.APPLICATION_JSON)
                                    .content(body(externalId, "CUST-1", "INTERNET-500")))
                            .andReturn();
                    return result.getResponse().getStatus();
                }))
                .toList();
        ready.await(10, TimeUnit.SECONDS);
        go.countDown();

        List<Integer> statuses = new java.util.ArrayList<>();
        for (Future<Integer> future : futures) {
            statuses.add(future.get(30, TimeUnit.SECONDS));
        }
        pool.shutdownNow();

        long created = statuses.stream().filter(s -> s == 201).count();
        long ok = statuses.stream().filter(s -> s == 200).count();
        long conflict = statuses.stream().filter(s -> s == 409).count();
        assertThat(created).isEqualTo(1);
        assertThat(created + ok + conflict).isEqualTo(threads);

        Integer count = jdbcTemplate.queryForObject(
                "SELECT COUNT(*) FROM activations WHERE external_id = ?", Integer.class, externalId);
        assertThat(count).isEqualTo(1);
    }

    @Test
    void listWithFiltersReturnsMatchingPage() throws Exception {
        mockMvc.perform(post("/api/v1/requests")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(body("EXT-LIST-1", "CUST-LIST", "INTERNET-500")))
                .andExpect(status().isCreated());
        mockMvc.perform(post("/api/v1/requests")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(body("EXT-LIST-2", "CUST-OTHER", "INTERNET-500")))
                .andExpect(status().isCreated());

        mockMvc.perform(get("/api/v1/requests")
                        .param("customerId", "CUST-LIST"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.content").isArray())
                .andExpect(jsonPath("$.content.length()").value(1))
                .andExpect(jsonPath("$.content[0].customerId").value("CUST-LIST"));

        mockMvc.perform(get("/api/v1/requests")
                        .param("status", ActivationStatus.PENDING.name())
                        .param("page", "0")
                        .param("size", "1"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.content.length()").value(1))
                .andExpect(jsonPath("$.page.size").value(1));
    }
}
