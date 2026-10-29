package br.com.filipe.product_activation.exception;

import java.util.UUID;

public class ActivationNotFoundException extends RuntimeException {

    public ActivationNotFoundException(UUID id) {
        super("Solicitacao de ativacao nao encontrada: " + id);
    }
}
