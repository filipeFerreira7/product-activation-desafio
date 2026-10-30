package br.com.filipe.product_activation.integration;

import br.com.filipe.product_activation.dto.ProviderRequestDTO;
import br.com.filipe.product_activation.dto.ProviderResponseDTO;
import br.com.filipe.product_activation.model.Activation;

public interface ActivationProviderClient {

    ProviderResponseDTO activate(ProviderRequestDTO request);
}
