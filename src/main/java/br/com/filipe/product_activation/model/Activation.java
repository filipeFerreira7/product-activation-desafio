package br.com.filipe.product_activation.model;

import br.com.filipe.product_activation.exception.InvalidStatusTransitionException;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import jakarta.persistence.UniqueConstraint;
import jakarta.persistence.Version;
import lombok.Getter;
import lombok.NoArgsConstructor;

import java.time.Instant;
import java.util.UUID;

@Entity
@Table(name = "activations", uniqueConstraints = {
        @UniqueConstraint(name = "uk_activations_external_id", columnNames = "external_id")
})
@Getter
@NoArgsConstructor
public class Activation {

    @Id
    @Column(name = "id", nullable = false)
    private UUID id;

    @Column(name = "external_id", nullable = false, length = 100)
    private String externalId;

    @Column(name = "customer_id", nullable = false, length = 100)
    private String customerId;

    @Column(name = "product_code", nullable = false, length = 100)
    private String productCode;

    @Enumerated(EnumType.STRING)
    @Column(name = "status", nullable = false, length = 20)
    private ActivationStatus status;

    @Column(name = "attempt_count", nullable = false)
    private int attemptCount;

    @Column(name = "created_at", nullable = false, updatable = false)
    private Instant createdAt;

    @Column(name = "updated_at", nullable = false)
    private Instant updatedAt;

    @Version
    @Column(name = "version", nullable = false)
    private long version;


    private Activation(UUID id, String externalId, String customerId, String productCode, Instant now) {
        this.id = id;
        this.externalId = externalId;
        this.customerId = customerId;
        this.productCode = productCode;
        this.status = ActivationStatus.PENDING;
        this.attemptCount = 0;
        this.createdAt = now;
        this.updatedAt = now;
    }

    public static Activation newActivation(String externalId, String customerId, String productCode, Instant now) {
        return new Activation(UUID.randomUUID(), externalId, customerId, productCode, now);
    }

    public void markProcessing(Instant now) {
        if (status != ActivationStatus.PENDING && status != ActivationStatus.PROCESSING) {
            throw new InvalidStatusTransitionException(status, ActivationStatus.PROCESSING);
        }
        this.status = ActivationStatus.PROCESSING;
        this.updatedAt = now;
    }

    public void markCompleted(Instant now) {
        if (status != ActivationStatus.PROCESSING) {
            throw new InvalidStatusTransitionException(status, ActivationStatus.COMPLETED);
        }
        this.status = ActivationStatus.COMPLETED;
        this.updatedAt = now;
    }

    public void markFailed(Instant now) {
        if (status == ActivationStatus.COMPLETED) {
            throw new InvalidStatusTransitionException(status, ActivationStatus.FAILED);
        }
        this.status = ActivationStatus.FAILED;
        this.updatedAt = now;
    }

    public void backToPending(Instant now) {
        if (status != ActivationStatus.PROCESSING) {
            throw new InvalidStatusTransitionException(status, ActivationStatus.PENDING);
        }
        this.status = ActivationStatus.PENDING;
        this.updatedAt = now;
    }

    public int registerAttempt() {
        this.attemptCount++;
        return this.attemptCount;
    }

    public boolean isProcessable() {
        return status == ActivationStatus.PENDING;
    }

}
