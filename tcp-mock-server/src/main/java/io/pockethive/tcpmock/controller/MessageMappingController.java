package io.pockethive.tcpmock.controller;

import io.pockethive.tcpmock.service.MessageTypeRegistry;
import io.pockethive.tcpmock.model.MessageTypeMapping;
import org.springframework.web.bind.annotation.*;
import java.util.Collection;

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
    public void addMapping(@RequestBody MessageTypeMapping mapping) {
        registry.addMapping(mapping);
    }

    @DeleteMapping("/{id}")
    public void removeMapping(@PathVariable String id) {
        registry.removeMapping(id);
    }
}
