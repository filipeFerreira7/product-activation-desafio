package br.com.filipe.product_activation.exception;

public class InvalidActivationRequestException extends RuntimeException {

    private final String field;

    public InvalidActivationRequestException(String field, String message) {
        super(message);
        this.field = field;
    }

    public String getField() {
        return field;
    }
}
