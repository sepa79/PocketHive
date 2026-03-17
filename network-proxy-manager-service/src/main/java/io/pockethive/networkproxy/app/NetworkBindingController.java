package io.pockethive.networkproxy.app;

import io.pockethive.swarm.model.NetworkBinding;
import io.pockethive.swarm.model.NetworkBindingClearRequest;
import io.pockethive.swarm.model.NetworkBindingRequest;
import jakarta.validation.Valid;
import java.util.List;
import java.util.Optional;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/api/network")
public class NetworkBindingController {

    private final NetworkBindingService service;

    public NetworkBindingController(NetworkBindingService service) {
        this.service = service;
    }

    @GetMapping(value = "/bindings", produces = MediaType.APPLICATION_JSON_VALUE)
    public List<NetworkBinding> listBindings() {
        return service.listBindings();
    }

    @GetMapping(value = "/bindings/{swarmId}", produces = MediaType.APPLICATION_JSON_VALUE)
    public ResponseEntity<NetworkBinding> getBinding(@PathVariable("swarmId") String swarmId) {
        return Optional.ofNullable(service.findBinding(swarmId))
            .map(ResponseEntity::ok)
            .orElseGet(() -> ResponseEntity.notFound().build());
    }

    @PostMapping(value = "/bindings/{swarmId}", consumes = MediaType.APPLICATION_JSON_VALUE, produces = MediaType.APPLICATION_JSON_VALUE)
    public NetworkBinding bind(@PathVariable("swarmId") String swarmId, @Valid @RequestBody NetworkBindingRequest request) throws Exception {
        return service.bind(swarmId, request);
    }

    @PostMapping(value = "/bindings/{swarmId}/clear", consumes = MediaType.APPLICATION_JSON_VALUE, produces = MediaType.APPLICATION_JSON_VALUE)
    public NetworkBinding clear(@PathVariable("swarmId") String swarmId, @Valid @RequestBody NetworkBindingClearRequest request) throws Exception {
        return service.clear(swarmId, request);
    }

    @GetMapping(value = "/proxies", produces = MediaType.APPLICATION_JSON_VALUE)
    public List<NetworkBinding> listProxies() {
        return service.listProxies();
    }

    @GetMapping(value = "/manual-override", produces = MediaType.APPLICATION_JSON_VALUE)
    public ManualNetworkOverrideStatus manualOverride() {
        return service.manualOverride();
    }

    @PutMapping(value = "/manual-override", consumes = MediaType.APPLICATION_JSON_VALUE, produces = MediaType.APPLICATION_JSON_VALUE)
    public ManualNetworkOverrideStatus applyManualOverride(@Valid @RequestBody ManualNetworkOverrideRequest request) throws Exception {
        return service.applyManualOverride(request);
    }

    @PostMapping(value = "/manual-override/drop-connections", consumes = MediaType.APPLICATION_JSON_VALUE, produces = MediaType.APPLICATION_JSON_VALUE)
    public ManualNetworkOverrideStatus dropConnections(@Valid @RequestBody ManualNetworkActionRequest request) throws Exception {
        return service.dropConnections(request);
    }
}
