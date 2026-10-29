package br.com.filipe.product_activation.controller;

import br.com.filipe.product_activation.dto.ActivationRequestDTO;
import br.com.filipe.product_activation.dto.ActivationResponseDTO;
import br.com.filipe.product_activation.dto.CreateActivationResult;
import br.com.filipe.product_activation.exception.ActivationNotFoundException;
import br.com.filipe.product_activation.exception.DuplicateActivationException;
import br.com.filipe.product_activation.exception.GlobalExceptionHandler;
import br.com.filipe.product_activation.model.ActivationStatus;
import br.com.filipe.product_activation.service.ActivationService;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.WebMvcTest;
import org.springframework.boot.test.mock.mockito.MockBean;
import org.springframework.context.annotation.Import;
import org.springframework.data.domain.PageImpl;
import org.springframework.data.domain.PageRequest;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.MockMvc;

import java.time.Instant;
import java.util.List;
import java.util.UUID;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.when;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.jwt;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.header;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

@WebMvcTest(ActivationController.class)
@Import(GlobalExceptionHandler.class)
class ActivationControllerTest {

    @Autowired
    private MockMvc mockMvc;

    @MockBean
    private ActivationService activationService;

    private ActivationResponseDTO sampleDto(UUID id) {
        return new ActivationResponseDTO(id, "EXT-12345", "CUST-10001", "INTERNET-500",
                ActivationStatus.PENDING,
                Instant.parse("2026-10-26T14:03:11Z"),
                Instant.parse("2026-10-26T14:03:11Z"));
    }

    @Test
    void createReturns201WithLocation() throws Exception {
        UUID id = UUID.fromString("0b7f3c1e-5d2a-4c4e-9a51-2f8d7b1e6c90");
        when(activationService.create(any())).thenReturn(new CreateActivationResult(sampleDto(id), true));

        mockMvc.perform(post("/api/v1/requests")
                        .with(jwt().authorities(() -> "SCOPE_activation:write"))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {
                                  "externalId": "EXT-12345",
                                  "customerId": "CUST-10001",
                                  "productCode": "INTERNET-500"
                                }
                                """))
                .andExpect(status().isCreated())
                .andExpect(header().string("Location", org.hamcrest.Matchers.endsWith("/api/v1/requests/" + id)))
                .andExpect(jsonPath("$.id").value(id.toString()))
                .andExpect(jsonPath("$.externalId").value("EXT-12345"))
                .andExpect(jsonPath("$.customerId").value("CUST-10001"))
                .andExpect(jsonPath("$.productCode").value("INTERNET-500"))
                .andExpect(jsonPath("$.status").value("PENDING"))
                .andExpect(jsonPath("$.createdAt").exists())
                .andExpect(jsonPath("$.updatedAt").exists());
    }

    @Test
    void createIdempotentRetryReturns200() throws Exception {
        UUID id = UUID.randomUUID();
        when(activationService.create(any())).thenReturn(new CreateActivationResult(sampleDto(id), false));

        mockMvc.perform(post("/api/v1/requests")
                        .with(jwt().authorities(() -> "SCOPE_activation:write"))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {
                                  "externalId": "EXT-12345",
                                  "customerId": "CUST-10001",
                                  "productCode": "INTERNET-500"
                                }
                                """))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.id").value(id.toString()));
    }

    @Test
    void createWithInvalidBodyReturns400ProblemDetail() throws Exception {
        mockMvc.perform(post("/api/v1/requests")
                        .with(jwt().authorities(() -> "SCOPE_activation:write"))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {
                                  "externalId": "",
                                  "customerId": "",
                                  "productCode": ""
                                }
                                """))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.title").value("Dados invalidos"))
                .andExpect(jsonPath("$.status").value(400))
                .andExpect(jsonPath("$.errors").isArray());
    }

    @Test
    void getByIdReturns200() throws Exception {
        UUID id = UUID.randomUUID();
        when(activationService.findById(id)).thenReturn(sampleDto(id));

        mockMvc.perform(get("/api/v1/requests/" + id)
                        .with(jwt().authorities(() -> "SCOPE_activation:read")))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.id").value(id.toString()))
                .andExpect(jsonPath("$.status").value("PENDING"));
    }

    @Test
    void getByIdReturns404WhenMissing() throws Exception {
        UUID id = UUID.randomUUID();
        when(activationService.findById(id)).thenThrow(new ActivationNotFoundException(id));

        mockMvc.perform(get("/api/v1/requests/" + id)
                        .with(jwt().authorities(() -> "SCOPE_activation:read")))
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.title").value("Solicitacao nao encontrada"));
    }

    @Test
    void createConflictReturns409() throws Exception {
        when(activationService.create(any())).thenThrow(new DuplicateActivationException("EXT-1"));

        mockMvc.perform(post("/api/v1/requests")
                        .with(jwt().authorities(() -> "SCOPE_activation:write"))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {
                                  "externalId": "EXT-1",
                                  "customerId": "CUST-1",
                                  "productCode": "INTERNET-1000"
                                }
                                """))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.title").value("Solicitacao duplicada"));
    }

    @Test
    void listReturnsPagedResponse() throws Exception {
        UUID id = UUID.randomUUID();
        when(activationService.list(eq(ActivationStatus.PENDING), eq("CUST-10001"), any()))
                .thenReturn(new PageImpl<>(List.of(sampleDto(id)), PageRequest.of(0, 20), 1));

        mockMvc.perform(get("/api/v1/requests")
                        .param("status", "PENDING")
                        .param("customerId", "CUST-10001")
                        .with(jwt().authorities(() -> "SCOPE_activation:read")))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.content[0].id").value(id.toString()))
                .andExpect(jsonPath("$.page.totalElements").value(1));
    }
}
