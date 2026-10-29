package br.com.filipe.product_activation.exception;

import br.com.filipe.product_activation.model.ActivationStatus;

public class InvalidStatusTransitionException extends RuntimeException {

    public InvalidStatusTransitionException(ActivationStatus from, ActivationStatus to) {
        super("Transicao invalida de status: " + from + " -> " + to);
    }
}
