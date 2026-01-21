package io.pockethive.orchestrator.app;

import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;
import java.time.Instant;
import java.util.List;
import java.util.Objects;
import org.springframework.http.ResponseEntity;
import org.springframework.validation.annotation.Validated;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

@RestController
@Validated
@RequestMapping("/api/debug/taps")
public class DebugTapController {

    private final DebugTapService service;

    public DebugTapController(DebugTapService service) {
        this.service = Objects.requireNonNull(service, "service");
    }

    @PostMapping
    public ResponseEntity<DebugTapResponse> create(@Valid @RequestBody DebugTapRequest request) {
        return ResponseEntity.ok(service.create(request));
    }

    @GetMapping("/{tapId}")
    public ResponseEntity<DebugTapResponse> read(@PathVariable("tapId") @NotBlank String tapId,
                                                 @RequestParam(name = "drain", required = false) Integer drain) {
        return ResponseEntity.ok(service.read(tapId, drain));
    }

    @DeleteMapping("/{tapId}")
    public ResponseEntity<DebugTapResponse> close(@PathVariable("tapId") @NotBlank String tapId) {
        return ResponseEntity.ok(service.close(tapId));
    }

    public record DebugTapRequest(
        @NotBlank String swarmId,
        @NotBlank String role,
        @NotBlank String direction,
        String ioName,
        Integer maxItems,
        Integer ttlSeconds
    ) {
    }

    public record DebugTapResponse(
        String tapId,
        String swarmId,
        String role,
        String direction,
        String ioName,
        String exchange,
        String routingKey,
        String queue,
        int maxItems,
        int ttlSeconds,
        Instant createdAt,
        Instant lastReadAt,
        List<DebugTapSample> samples
    ) {
    }

    public record DebugTapSample(
        String id,
        Instant receivedAt,
        int sizeBytes,
        String payload
    ) {
    }
}
