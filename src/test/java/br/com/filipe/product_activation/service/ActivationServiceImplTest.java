package br.com.filipe.product_activation.service;

import br.com.filipe.product_activation.dto.ActivationRequestDTO;
import br.com.filipe.product_activation.dto.ActivationResponseDTO;
import br.com.filipe.product_activation.dto.CreateActivationResult;
import br.com.filipe.product_activation.exception.ActivationNotFoundException;
import br.com.filipe.product_activation.exception.DuplicateActivationException;
import br.com.filipe.product_activation.integration.ActivationDispatcher;
import br.com.filipe.product_activation.model.Activation;
import br.com.filipe.product_activation.model.ActivationStatus;
import br.com.filipe.product_activation.repository.ActivationRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageImpl;
import org.springframework.data.domain.PageRequest;
import org.springframework.test.util.ReflectionTestUtils;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.SimpleTransactionStatus;
import org.springframework.transaction.support.TransactionTemplate;

import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.lenient;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class ActivationServiceImplTest {

    @Mock
    private ActivationRepository repository;

    @Mock
    private ActivationDispatcher dispatcher;

    @Mock
    private PlatformTransactionManager transactionManager;

    @InjectMocks
    private ActivationServiceImpl service;

    private final Clock clock = Clock.fixed(Instant.parse("2026-10-26T14:03:11Z"), ZoneOffset.UTC);

    @BeforeEach
    void setUp() {
        lenient().when(transactionManager.getTransaction(any())).thenReturn(new SimpleTransactionStatus());
        ReflectionTestUtils.setField(service, "clock", clock);
        ReflectionTestUtils.setField(service, "transactionTemplate", new TransactionTemplate(transactionManager));
    }

    private ActivationRequestDTO dto(String externalId, String customerId, String productCode) {
        return new ActivationRequestDTO(externalId, customerId, productCode);
    }

    @Test
    void createSavesNewActivationAndDispatches() {
        ActivationRequestDTO request = dto("EXT-1", "CUST-1", "INTERNET-500");
        when(repository.findByExternalId("EXT-1")).thenReturn(Optional.empty());
        when(repository.saveAndFlush(any(Activation.class))).thenAnswer(inv -> inv.getArgument(0));

        CreateActivationResult result = service.create(request);

        assertThat(result.created()).isTrue();
        assertThat(result.dto().externalId()).isEqualTo("EXT-1");
        assertThat(result.dto().customerId()).isEqualTo("CUST-1");
        assertThat(result.dto().productCode()).isEqualTo("INTERNET-500");
        assertThat(result.dto().status()).isEqualTo(ActivationStatus.PENDING);
        assertThat(result.dto().createdAt()).isEqualTo(clock.instant());
        verify(dispatcher).dispatch(result.dto().id());
    }

    @Test
    void createWithSameExternalIdAndSameContentReturnsExisting() {
        Activation existing = Activation.newActivation("EXT-1", "CUST-1", "INTERNET-500", clock.instant());
        when(repository.findByExternalId("EXT-1")).thenReturn(Optional.of(existing));

        CreateActivationResult result = service.create(dto("EXT-1", "CUST-1", "INTERNET-500"));

        assertThat(result.created()).isFalse();
        assertThat(result.dto().id()).isEqualTo(existing.getId());
        verify(repository, never()).saveAndFlush(any());
        verify(dispatcher, never()).dispatch(any());
    }

    @Test
    void createWithSameExternalIdAndDifferentContentThrowsConflict() {
        Activation existing = Activation.newActivation("EXT-1", "CUST-1", "INTERNET-500", clock.instant());
        when(repository.findByExternalId("EXT-1")).thenReturn(Optional.of(existing));

        assertThatThrownBy(() -> service.create(dto("EXT-1", "CUST-1", "INTERNET-1000")))
                .isInstanceOf(DuplicateActivationException.class);
    }

    @Test
    void createHandlesRaceWithUniqueConstraint() {
        ActivationRequestDTO request = dto("EXT-1", "CUST-1", "INTERNET-500");
        Activation raced = Activation.newActivation("EXT-1", "CUST-1", "INTERNET-500", clock.instant());
        when(repository.findByExternalId("EXT-1"))
                .thenReturn(Optional.empty())
                .thenReturn(Optional.of(raced));
        when(repository.saveAndFlush(any(Activation.class)))
                .thenThrow(new DataIntegrityViolationException("uk_activations_external_id"));

        CreateActivationResult result = service.create(request);

        assertThat(result.created()).isFalse();
        assertThat(result.dto().id()).isEqualTo(raced.getId());
    }

    @Test
    void findByIdReturnsDtoWhenFound() {
        Activation activation = Activation.newActivation("EXT-1", "CUST-1", "INTERNET-500", clock.instant());
        when(repository.findById(activation.getId())).thenReturn(Optional.of(activation));

        ActivationResponseDTO response = service.findById(activation.getId());

        assertThat(response.id()).isEqualTo(activation.getId());
    }

    @Test
    void findByIdThrowsWhenMissing() {
        UUID missing = UUID.randomUUID();
        when(repository.findById(missing)).thenReturn(Optional.empty());

        assertThatThrownBy(() -> service.findById(missing))
                .isInstanceOf(ActivationNotFoundException.class);
    }

    @Test
    void listWithFiltersDelegatesToRepository() {
        Activation activation = Activation.newActivation("EXT-1", "CUST-1", "INTERNET-500", clock.instant());
        Page<Activation> page = new PageImpl<>(List.of(activation));
        when(repository.findByStatusAndCustomerId(ActivationStatus.PENDING, "CUST-1", PageRequest.of(0, 20)))
                .thenReturn(page);

        Page<ActivationResponseDTO> result =
                service.list(ActivationStatus.PENDING, "CUST-1", PageRequest.of(0, 20));

        assertThat(result.getContent()).hasSize(1);
        assertThat(result.getContent().getFirst().customerId()).isEqualTo("CUST-1");
    }
}
