package io.pockethive.tcpmock.service;

import io.pockethive.tcpmock.model.MessageTypeMapping;
import java.util.Collection;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import org.springframework.stereotype.Service;

/**
 * Responsibility: initialize and commit the durable mapping catalogue through its persistence port.
 * Must not: implement filesystem IO, execute requests or acknowledge an unpersisted change.
 * Contract: RESP-TCP-MOCK-MAPPING-FILES — docs/architecture/runtime-responsibilities.md#resp-tcp-mock-mapping-files.
 */
@Service
public class MessageTypeRegistry {
    private final MappingPersistence persistence;
    private volatile Map<String, MessageTypeMapping> mappings;

    public MessageTypeRegistry(MappingPersistence persistence, StartupMappingSource startup) {
        this.persistence = Objects.requireNonNull(persistence);
        Map<String, MessageTypeMapping> initial;
        if (persistence.hasSnapshot()) {
            initial = new LinkedHashMap<>();
            for (MessageTypeMapping mapping : persistence.load()) {
                String id = Objects.requireNonNull(mapping.getId(), "Saved mapping id");
                if (initial.putIfAbsent(id, mapping) != null) {
                    throw new IllegalStateException("Duplicate mapping id in saved catalogue: " + id);
                }
            }
        } else {
            initial = defaultMappings();
            for (MessageTypeMapping mapping : startup.load()) {
                initial.put(Objects.requireNonNull(mapping.getId(), "Startup mapping id"), mapping);
            }
            persistence.save(initial.values());
        }
        mappings = Collections.unmodifiableMap(new LinkedHashMap<>(initial));
    }

    private Map<String, MessageTypeMapping> defaultMappings() {
        Map<String, MessageTypeMapping> initial = new LinkedHashMap<>();
        MessageTypeMapping echoMapping = new MessageTypeMapping("echo", "^ECHO.*", "{{message}}", "Echo response");
        echoMapping.setPriority(10);
        initial.put(echoMapping.getId(), echoMapping);

        MessageTypeMapping jsonMapping = new MessageTypeMapping("json", "^\\{.*\\}$",
            "{\"status\":\"success\",\"timestamp\":\"{{timestamp}}\",\"echo\":{{message}}}", "JSON response");
        jsonMapping.setPriority(10);
        initial.put(jsonMapping.getId(), jsonMapping);

        MessageTypeMapping defaultMapping = new MessageTypeMapping("default", ".*", "OK", "Default response");
        defaultMapping.setPriority(1);
        initial.put(defaultMapping.getId(), defaultMapping);

        return initial;
    }

    public List<MessageTypeMapping> getSortedMappings() {
        return mappings.values().stream()
            .filter(MessageTypeMapping::isEnabled)
            .sorted((m1, m2) -> Integer.compare(m2.getPriority(), m1.getPriority()))
            .toList();
    }

    public synchronized void addMapping(MessageTypeMapping mapping) {
        String id = Objects.requireNonNull(mapping.getId(), "Mapping id");
        Map<String, MessageTypeMapping> candidate = new LinkedHashMap<>(mappings);
        candidate.put(id, mapping);
        commit(candidate);
    }

    public synchronized void removeMapping(String id) {
        Objects.requireNonNull(id, "Mapping id");
        if (!mappings.containsKey(id)) {
            return;
        }
        Map<String, MessageTypeMapping> candidate = new LinkedHashMap<>(mappings);
        candidate.remove(id);
        commit(candidate);
    }

    public synchronized void clearMappings() {
        commit(Map.of());
    }

    private void commit(Map<String, MessageTypeMapping> candidate) {
        Map<String, MessageTypeMapping> accepted = Collections.unmodifiableMap(new LinkedHashMap<>(candidate));
        persistence.save(accepted.values());
        mappings = accepted;
    }

    public Collection<MessageTypeMapping> getAllMappings() {
        return List.copyOf(mappings.values());
    }
}
