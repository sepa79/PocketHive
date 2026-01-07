package io.pockethive.tcpmock.controller;

import io.pockethive.tcpmock.service.MessageTypeRegistry;
import io.pockethive.tcpmock.model.MessageTypeMapping;
import org.springframework.web.bind.annotation.*;
import org.springframework.http.ResponseEntity;
import java.util.Collection;
import java.util.Map;

@RestController
@RequestMapping("/api/mappings")
public class MessageMappingController {
    private final MessageTypeRegistry registry;

    public MessageMappingController(MessageTypeRegistry registry) {
        this.registry = registry;
    }

    @GetMapping
    public Collection<MessageTypeMapping> getAllMappings() {
        return registry.getAllMappings();
    }

    @PostMapping
    public ResponseEntity<Map<String, Object>> addMapping(@RequestBody String rawBody) {
        try {
            com.fasterxml.jackson.databind.ObjectMapper jsonMapper = new com.fasterxml.jackson.databind.ObjectMapper();
            com.fasterxml.jackson.dataformat.yaml.YAMLMapper yamlMapper = new com.fasterxml.jackson.dataformat.yaml.YAMLMapper();
            com.fasterxml.jackson.databind.JsonNode node;

            try {
                node = jsonMapper.readTree(rawBody);
            } catch (Exception jsonEx) {
                node = yamlMapper.readTree(rawBody);
            }

            if (node.isArray()) {
                int created = 0;
                for (com.fasterxml.jackson.databind.JsonNode item : node) {
                    MessageTypeMapping mapping = yamlMapper.treeToValue(item, MessageTypeMapping.class);
                    registry.addMapping(mapping);
                    registry.saveMappingToFile(mapping);
                    created++;
                }
                return ResponseEntity.ok(Map.of("status", "created", "count", created));
            } else {
                MessageTypeMapping mapping = yamlMapper.treeToValue(node, MessageTypeMapping.class);
                registry.addMapping(mapping);
                registry.saveMappingToFile(mapping);
                return ResponseEntity.ok(Map.of("status", "created", "id", mapping.getId()));
            }
        } catch (Exception e) {
            e.printStackTrace();
            return ResponseEntity.badRequest().body(Map.of("error", e.getMessage(), "details", e.getClass().getSimpleName()));
        }
    }

    @DeleteMapping("/{id}")
    public ResponseEntity<Map<String, String>> removeMapping(@PathVariable String id) {
        registry.removeMapping(id);
        registry.deleteMappingFile(id);
        return ResponseEntity.ok(Map.of("status", "deleted", "id", id));
    }
}
