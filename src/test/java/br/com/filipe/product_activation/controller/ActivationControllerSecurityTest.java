package br.com.filipe.product_activation.controller;

import br.com.filipe.product_activation.config.SecurityConfig;
import br.com.filipe.product_activation.exception.GlobalExceptionHandler;
import br.com.filipe.product_activation.service.ActivationService;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.WebMvcTest;
import org.springframework.boot.test.mock.mockito.MockBean;
import org.springframework.context.annotation.Import;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.security.oauth2.jwt.JwtDecoder;
import org.springframework.test.context.TestPropertySource;
import org.springframework.test.web.servlet.MockMvc;

import java.util.UUID;

import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.jwt;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

@WebMvcTest(ActivationController.class)
@Import({GlobalExceptionHandler.class, SecurityConfig.class})
@TestPropertySource(properties = "app.security.enabled=true")
class ActivationControllerSecurityTest {

    @Autowired
    private MockMvc mockMvc;

    @MockBean
    private ActivationService activationService;

    @MockBean
    private JwtDecoder jwtDecoder;

    @Test
    void withoutTokenReturns401() throws Exception {
        mockMvc.perform(get("/api/v1/requests/" + UUID.randomUUID()))
                .andExpect(status().isUnauthorized());
    }

    @Test
    void insufficientScopeReturns403() throws Exception {
        mockMvc.perform(get("/api/v1/requests/" + UUID.randomUUID())
                        .with(jwt().authorities(new SimpleGrantedAuthority("SCOPE_other"))))
                .andExpect(status().isForbidden());
    }

    @Test
    void withReadScopeReturns200() throws Exception {
        mockMvc.perform(get("/api/v1/requests/" + UUID.randomUUID())
                        .with(jwt().authorities(new SimpleGrantedAuthority("SCOPE_activation:read"))))
                .andExpect(status().isOk());
    }
}
