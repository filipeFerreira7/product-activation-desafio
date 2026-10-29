package br.com.filipe.product_activation.repository;

import br.com.filipe.product_activation.model.Activation;
import br.com.filipe.product_activation.model.ActivationStatus;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;

import java.util.Optional;
import java.util.UUID;

@Repository
public interface ActivationRepository extends JpaRepository<Activation, UUID> {

    Optional<Activation> findByExternalId(String externalId);

    Page<Activation> findByStatus(ActivationStatus status, Pageable pageable);

    Page<Activation> findByCustomerId(String customerId, Pageable pageable);

    Page<Activation> findByStatusAndCustomerId(ActivationStatus status, String customerId, Pageable pageable);
}
