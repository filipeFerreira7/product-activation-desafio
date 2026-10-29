package br.com.filipe.product_activation.config;

import br.com.filipe.product_activation.integration.ProviderResponse;
import io.github.resilience4j.common.circuitbreaker.configuration.CircuitBreakerConfigCustomizer;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

@Configuration
public class ResilienceConfig {

    @Bean
    public CircuitBreakerConfigCustomizer providerCircuitBreakerCustomizer() {
        return CircuitBreakerConfigCustomizer.of("provider",
                builder -> builder.recordResult(
                        response -> response instanceof ProviderResponse pr && pr.httpStatus() >= 500));
    }
}
