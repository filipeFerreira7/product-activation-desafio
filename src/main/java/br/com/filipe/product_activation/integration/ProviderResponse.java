package br.com.filipe.product_activation.integration;

public record ProviderResponse(int httpStatus, String body) {

    public boolean isSuccess() {
        return httpStatus >= 200 && httpStatus < 300;
    }

    public boolean isRetryable() {
        return httpStatus == 408 || httpStatus == 429 || httpStatus >= 500;
    }
}
