package io.pockethive.tcpmock.controller;

import io.pockethive.tcpmock.service.MappingAuthoringService;
import io.pockethive.tcpmock.model.MessageTypeMapping;
import org.springframework.web.bind.annotation.*;
import org.springframework.http.ResponseEntity;
import java.util.Collection;
import java.util.Map;

/**
 * Responsibility: map mapping-authoring HTTP requests and responses.
 * Must not: iterate imports, mutate the registry or coordinate file persistence.
 * Contract: RESP-TCP-MOCK-MAPPING-AUTHORING — docs/architecture/runtime-responsibilities.md#resp-tcp-mock-mapping-authoring.
 */
@RestController
@RequestMapping("/api/mappings")
public class MessageMappingController {
    private final MappingAuthoringService authoring;

    public MessageMappingController(MappingAuthoringService authoring) {
        this.authoring = authoring;
    }

    @GetMapping
    public Collection<MessageTypeMapping> getAllMappings() {
        return authoring.getAllMappings();
    }

    @PostMapping
    public ResponseEntity<Map<String, Object>> addMapping(@RequestBody String rawBody) {
        try {
            return ResponseEntity.ok(authoring.addMapping(rawBody));
        } catch (Exception e) {
            e.printStackTrace();
            return ResponseEntity.badRequest().body(Map.of("error", e.getMessage(), "details", e.getClass().getSimpleName()));
        }
    }

    @DeleteMapping("/{id}")
    public ResponseEntity<Map<String, String>> removeMapping(@PathVariable("id") String id) {
        authoring.removeMapping(id);
        return ResponseEntity.noContent().build();
    }
}
