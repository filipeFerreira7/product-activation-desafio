CREATE TABLE activations (
    id            UUID         NOT NULL,
    external_id   VARCHAR(100) NOT NULL,
    customer_id   VARCHAR(100) NOT NULL,
    product_code  VARCHAR(100) NOT NULL,
    status        VARCHAR(20)  NOT NULL,
    attempt_count INTEGER      NOT NULL DEFAULT 0,
    created_at    TIMESTAMPTZ  NOT NULL,
    updated_at    TIMESTAMPTZ  NOT NULL,
    version       BIGINT       NOT NULL DEFAULT 0,
    CONSTRAINT pk_activations PRIMARY KEY (id),
    CONSTRAINT uk_activations_external_id UNIQUE (external_id),
    CONSTRAINT ck_activations_status CHECK (
        status IN ('PENDING', 'PROCESSING', 'COMPLETED', 'FAILED')
    ),
    CONSTRAINT ck_activations_attempt_count CHECK (attempt_count >= 0),
    CONSTRAINT ck_activations_external_id_len CHECK (char_length(external_id) BETWEEN 1 AND 100),
    CONSTRAINT ck_activations_customer_id_len CHECK (char_length(customer_id) BETWEEN 1 AND 100),
    CONSTRAINT ck_activations_product_code_len CHECK (char_length(product_code) BETWEEN 1 AND 100)
);

CREATE INDEX idx_activations_status ON activations (status);
CREATE INDEX idx_activations_customer_id ON activations (customer_id);
CREATE INDEX idx_activations_status_customer_id ON activations (status, customer_id);
CREATE INDEX idx_activations_created_at ON activations (created_at DESC);
