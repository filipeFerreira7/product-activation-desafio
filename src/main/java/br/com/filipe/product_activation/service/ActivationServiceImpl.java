package br.com.filipe.product_activation.service;

import br.com.filipe.product_activation.dto.ActivationRequestDTO;
import br.com.filipe.product_activation.dto.ActivationResponseDTO;
import br.com.filipe.product_activation.dto.CreateActivationResult;
import br.com.filipe.product_activation.exception.ActivationNotFoundException;
import br.com.filipe.product_activation.exception.DuplicateActivationException;
import br.com.filipe.product_activation.exception.InvalidActivationRequestException;
import br.com.filipe.product_activation.integration.ActivationDispatcher;
import br.com.filipe.product_activation.model.Activation;
import br.com.filipe.product_activation.model.ActivationStatus;
import br.com.filipe.product_activation.repository.ActivationRepository;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.stereotype.Service;
import org.springframework.transaction.support.TransactionSynchronization;
import org.springframework.transaction.support.TransactionSynchronizationManager;
import org.springframework.transaction.support.TransactionTemplate;

import java.time.Clock;
import java.time.Instant;
import java.util.UUID;

@Service
public class ActivationServiceImpl implements ActivationService {

    private static final Logger log = LoggerFactory.getLogger(ActivationServiceImpl.class);

    @Autowired
    private ActivationRepository repository;

    @Autowired
    private ActivationDispatcher dispatcher;

    @Autowired
    private Clock clock;

    @Autowired
    @Qualifier("transactionTemplate")
    private TransactionTemplate transactionTemplate;

    @Override
    public CreateActivationResult create(ActivationRequestDTO dto) {
        validateRequest(dto);
        try {
            return transactionTemplate.execute(status -> createInTransaction(dto));
        } catch (DataIntegrityViolationException ex) {
            log.info("Conflito de unicidade detectado para externalId={}, resolvendo corrida", dto.externalId());
            return transactionTemplate.execute(status -> recoverFromRace(dto));
        }
    }

    private void validateRequest(ActivationRequestDTO dto) {
        if (dto == null) {
            throw new InvalidActivationRequestException("body", "corpo da requisicao nao pode ser nulo");
        }
        requireNotBlank(dto.externalId(), "externalId");
        requireNotBlank(dto.customerId(), "customerId");
        requireNotBlank(dto.productCode(), "productCode");
    }

    private void requireNotBlank(String value, String field) {
        if (value == null || value.isBlank()) {
            throw new InvalidActivationRequestException(field, "nao pode ser vazio");
        }
    }

    private CreateActivationResult resolveExisting(Activation existing, ActivationRequestDTO dto) {
        boolean sameContent = existing.getCustomerId().equals(dto.customerId().trim())
                && existing.getProductCode().equals(dto.productCode().trim());
        if (sameContent) {
            log.info("Retry idempotente detectado, retornando registro existente id={}", existing.getId());
            return new CreateActivationResult(ActivationResponseDTO.from(existing), false);
        }
        throw new DuplicateActivationException(dto.externalId());
    }
    private CreateActivationResult createInTransaction(ActivationRequestDTO dto) {
        String externalId = dto.externalId().trim(); // tratamento caso exista espaço em branco
        return repository.findByExternalId(externalId)
                .map(existing -> resolveExisting(existing, dto))
                .orElseGet(() -> createNew(dto, externalId));
    }

    private CreateActivationResult recoverFromRace(ActivationRequestDTO dto) {
        String externalId = dto.externalId().trim();
        Activation raced = repository.findByExternalId(externalId)
                .orElseThrow(() -> new DataIntegrityViolationException("Registro duplicado nao recuperado: " + externalId));
        return resolveExisting(raced, dto);
    }

    private CreateActivationResult createNew(ActivationRequestDTO dto, String externalId) {
        Instant now = clock.instant();
        Activation activation = Activation.newActivation(
                externalId,
                dto.customerId().trim(),
                dto.productCode().trim(),
                now
        );
        Activation saved = repository.saveAndFlush(activation);
        registerAfterCommitDispatch(saved.getId());
        log.info("Solicitacao criada id={} externalId={}", saved.getId(), saved.getExternalId());
        return new CreateActivationResult(ActivationResponseDTO.from(saved), true);
    }


    private void registerAfterCommitDispatch(UUID id) {
        if (TransactionSynchronizationManager.isSynchronizationActive()) {
            TransactionSynchronizationManager.registerSynchronization(new TransactionSynchronization() {
                @Override
                public void afterCommit() {
                    dispatcher.dispatch(id);
                }
            });
        } else {
            dispatcher.dispatch(id);
        }
    }

    @Override
    public ActivationResponseDTO findById(UUID id) {
        return transactionTemplate.execute(tx ->
                repository.findById(id)
                        .map(ActivationResponseDTO::from)
                        .orElseThrow(() -> new ActivationNotFoundException(id))
        );
    }

    @Override
    public Page<ActivationResponseDTO> list(ActivationStatus status, String customerId, Pageable pageable) {
        return transactionTemplate.execute(tx -> {
            Page<Activation> page;
            if (status != null && customerId != null && !customerId.isBlank()) {
                page = repository.findByStatusAndCustomerId(status, customerId.trim(), pageable);
            } else if (status != null) {
                page = repository.findByStatus(status, pageable);
            } else if (customerId != null && !customerId.isBlank()) {
                page = repository.findByCustomerId(customerId.trim(), pageable);
            } else {
                page = repository.findAll(pageable);
            }
            return page.map(ActivationResponseDTO::from);
        });
    }
}
