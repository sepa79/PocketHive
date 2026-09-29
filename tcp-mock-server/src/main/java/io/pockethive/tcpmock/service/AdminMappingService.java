package io.pockethive.tcpmock.service;

import io.pockethive.tcpmock.model.StubMapping;
import io.pockethive.tcpmock.model.MessageTypeMapping;
import java.util.Map;
import org.springframework.stereotype.Service;

/**
 * Responsibility: coordinate existing admin stub mapping operations.
 * Must not: implement conversion, persistence or independent mapping state.
 * Contract: RESP-TCP-MOCK-ADMIN — docs/architecture/runtime-responsibilities.md#resp-tcp-mock-admin.
 */
@Service
public class AdminMappingService {
    private final MessageTypeRegistry registry;
    private final StubMappingConverter converter;

    public AdminMappingService(MessageTypeRegistry registry, StubMappingConverter converter) {
        this.registry = registry;
        this.converter = converter;
    }

    public Map<String, String> create(StubMapping stub) {
        MessageTypeMapping mapping = converter.toMapping(stub, "WireMock-style stub");
        registry.addMapping(mapping);
        return Map.of("status", "Created", "id", stub.getId());
    }

    public Map<String, Object> list() {
        return Map.of("mappings", registry.getAllMappings(),
            "meta", Map.of("total", registry.getAllMappings().size()));
    }

    public void delete(String id) {
        registry.removeMapping(id);
    }

    public void reset() {
        registry.clearMappings();
    }
}
