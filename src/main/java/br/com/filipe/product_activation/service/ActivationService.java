package br.com.filipe.product_activation.service;

import br.com.filipe.product_activation.dto.ActivationRequestDTO;
import br.com.filipe.product_activation.dto.ActivationResponseDTO;
import br.com.filipe.product_activation.dto.CreateActivationResult;
import br.com.filipe.product_activation.model.Activation;
import br.com.filipe.product_activation.model.ActivationStatus;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;

import java.util.UUID;

public interface ActivationService {

    CreateActivationResult create(ActivationRequestDTO dto);

    ActivationResponseDTO findById(UUID id);

    Page<ActivationResponseDTO> list(ActivationStatus status, String customerId, Pageable pageable);
}
