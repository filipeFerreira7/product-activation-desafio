package br.com.filipe.product_activation.controller;

import br.com.filipe.product_activation.dto.ActivationRequestDTO;
import br.com.filipe.product_activation.dto.ActivationResponseDTO;
import br.com.filipe.product_activation.dto.CreateActivationResult;
import br.com.filipe.product_activation.model.ActivationStatus;
import br.com.filipe.product_activation.service.ActivationService;
import jakarta.validation.Valid;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.domain.Sort;
import org.springframework.data.web.PageableDefault;
import org.springframework.data.web.PagedModel;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.util.UriComponentsBuilder;

import java.net.URI;
import java.util.UUID;

@RestController
@RequestMapping("/api/v1/requests")
public class ActivationController {

    @Autowired
    private ActivationService activationService;

    @PostMapping
    public ResponseEntity<ActivationResponseDTO> create(
            @Valid @RequestBody ActivationRequestDTO dto,
            UriComponentsBuilder uriBuilder) {
        CreateActivationResult result = activationService.create(dto);
        URI location = uriBuilder
                .path("/api/v1/requests/{id}")
                .buildAndExpand(result.dto().id())
                .toUri();
        if (result.created()) {
            return ResponseEntity.created(location).body(result.dto());
        }
        return ResponseEntity.ok().location(location).body(result.dto());
    }

    @GetMapping("/{id}")
    public ResponseEntity<ActivationResponseDTO> getById(@PathVariable UUID id) {
        return ResponseEntity.ok(activationService.findById(id));
    }

    @GetMapping
    public ResponseEntity<PagedModel<ActivationResponseDTO>> list(
            @RequestParam(required = false) ActivationStatus status,
            @RequestParam(required = false) String customerId,
            @PageableDefault(size = 20, sort = "createdAt", direction = Sort.Direction.DESC) Pageable pageable) {
        Page<ActivationResponseDTO> page = activationService.list(status, customerId, pageable);
        return ResponseEntity.ok(new PagedModel<>(page));
    }
}
