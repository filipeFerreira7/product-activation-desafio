package br.com.filipe.product_activation.dto;

public record ProviderResponseDTO(int httpStatus, String body, Long retryAfterSeconds) {

    public ProviderResponseDTO(int httpStatus, String body) {
        this(httpStatus, body, null);
    }

    public boolean isSuccess() {
        return httpStatus >= 200 && httpStatus < 300;
    }

    public boolean isRetryable() {
        return httpStatus == 408 || httpStatus == 429 || httpStatus >= 500;
    }
}
