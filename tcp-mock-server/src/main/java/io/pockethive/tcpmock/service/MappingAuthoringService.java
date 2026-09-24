package io.pockethive.tcpmock.service;

import com.fasterxml.jackson.databind.JsonNode;
import io.pockethive.tcpmock.model.MessageTypeMapping;
import java.io.IOException;
import java.util.Collection;
import java.util.Map;
import org.springframework.stereotype.Service;

/**
 * Responsibility: coordinate sequential authored mapping registration, persistence and deletion.
 * Must not: own catalogue state, construct paths or execute mock responses.
 * Contract: RESP-TCP-MOCK-MAPPING-AUTHORING — docs/architecture/runtime-responsibilities.md#resp-tcp-mock-mapping-authoring.
 */
@Service
public class MappingAuthoringService {
    private final MessageTypeRegistry registry;
    private final MappingFileStore files;
    private final MappingAuthoringParser parser;

    public MappingAuthoringService(MessageTypeRegistry registry, MappingFileStore files, MappingAuthoringParser parser) {
        this.registry = registry;
        this.files = files;
        this.parser = parser;
    }

    public Collection<MessageTypeMapping> getAllMappings() {
        return registry.getAllMappings();
    }

    public Map<String, Object> addMapping(String body) throws IOException {
        JsonNode node = parser.readDocument(body);
        if (node.isArray()) {
            int created = 0;
            for (JsonNode item : node) {
                registerAndSave(item);
                created++;
            }
            return Map.of("status", "created", "count", created);
        }
        MessageTypeMapping mapping = registerAndSave(node);
        return Map.of("status", "created", "id", mapping.getId());
    }

    private MessageTypeMapping registerAndSave(JsonNode item) throws IOException {
        MessageTypeMapping mapping = parser.readMapping(item);
        registry.addMapping(mapping);
        files.saveMappingToFile(mapping);
        return mapping;
    }

    public void removeMapping(String id) {
        try {
            registry.removeMapping(id);
            files.deleteMappingFile(id);
        } catch (Exception ignored) {
            // Preserve the existing idempotent deletion response.
        }
    }
}
