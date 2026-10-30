package br.com.filipe.product_activation.dto;

import br.com.filipe.product_activation.model.Activation;

import java.util.UUID;

public record ProviderRequestDTO(
        UUID id,
        String externalId,
        String costumerId,
        String productCode
) {
}
