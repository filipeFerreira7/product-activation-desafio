package br.com.filipe.product_activation.integration;

import br.com.filipe.product_activation.model.Activation;

public interface ActivationProviderClient {

    ProviderResponse activate(Activation activation);
}
