package br.com.filipe.product_activation.dto;

import br.com.filipe.product_activation.model.Activation;
import br.com.filipe.product_activation.model.ActivationStatus;

import java.time.Instant;
import java.util.UUID;

public record ActivationResponseDTO(
        UUID id,
        String externalId,
        String customerId,
        String productCode,
        ActivationStatus status,
        Instant createdAt,
        Instant updatedAt
) {
    public static ActivationResponseDTO from(Activation activation) {
        return new ActivationResponseDTO(
                activation.getId(),
                activation.getExternalId(),
                activation.getCustomerId(),
                activation.getProductCode(),
                activation.getStatus(),
                activation.getCreatedAt(),
                activation.getUpdatedAt()
        );
    }
}
