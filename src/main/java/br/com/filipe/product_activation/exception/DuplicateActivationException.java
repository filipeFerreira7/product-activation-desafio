package br.com.filipe.product_activation.exception;

public class DuplicateActivationException extends RuntimeException {

    public DuplicateActivationException(String externalId) {
        super("Ja existe uma solicitacao com externalId '" + externalId + "' e conteudo divergente");
    }
}
