package br.com.filipe.product_activation.dto;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Size;

public record ActivationRequestDTO(
        @NotBlank
        @Size(max = 100)
        @Pattern(regexp = "^[A-Za-z0-9._-]+$", message = "deve conter apenas letras, numeros, '.', '_' ou '-'")
        String externalId,

        @NotBlank
        @Size(max = 100)
        @Pattern(regexp = "^[A-Za-z0-9._-]+$", message = "deve conter apenas letras, numeros, '.', '_' ou '-'")
        String customerId,

        @NotBlank
        @Size(max = 100)
        @Pattern(regexp = "^[A-Za-z0-9._-]+$", message = "deve conter apenas letras, numeros, '.', '_' ou '-'")
        String productCode
) {
}
