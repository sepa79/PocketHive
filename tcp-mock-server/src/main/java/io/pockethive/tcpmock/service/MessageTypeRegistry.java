package io.pockethive.tcpmock.service;

import io.pockethive.tcpmock.model.MessageTypeMapping;
import org.springframework.stereotype.Service;
import java.util.*;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Responsibility: own runtime mappings, defaults and enabled/priority ordering.
 * Must not: execute requests or implement file IO.
 * Contract: RESP-TCP-MOCK-EXECUTION — docs/architecture/runtime-responsibilities.md#resp-tcp-mock-execution.
 */
@Service
public class MessageTypeRegistry {
    private final ConcurrentHashMap<String, MessageTypeMapping> mappings = new ConcurrentHashMap<>();
    public MessageTypeRegistry() {
        initializeDefaultMappings();
    }

    private void initializeDefaultMappings() {
        MessageTypeMapping echoMapping = new MessageTypeMapping("echo", "^ECHO.*", "{{message}}", "Echo response");
        echoMapping.setPriority(10);
        addMapping(echoMapping);

        MessageTypeMapping jsonMapping = new MessageTypeMapping("json", "^\\{.*\\}$",
            "{\"status\":\"success\",\"timestamp\":\"{{timestamp}}\",\"echo\":{{message}}}", "JSON response");
        jsonMapping.setPriority(10);
        addMapping(jsonMapping);

        MessageTypeMapping defaultMapping = new MessageTypeMapping("default", ".*", "OK", "Default response");
        defaultMapping.setPriority(1);
        addMapping(defaultMapping);

        System.out.println("Initialized 3 default mappings (echo, json, default)");
    }

    public List<MessageTypeMapping> getSortedMappings() {
        return mappings.values().stream()
            .filter(MessageTypeMapping::isEnabled)
            .sorted((m1, m2) -> Integer.compare(m2.getPriority(), m1.getPriority()))
            .toList();
    }

    public void addMapping(MessageTypeMapping mapping) {
        mappings.put(mapping.getId(), mapping);
    }

    public void removeMapping(String id) {
        mappings.remove(id);
    }

    public Collection<MessageTypeMapping> getAllMappings() {
        return new ArrayList<>(mappings.values());
    }





}
