package io.pockethive.tcpmock.service;

import io.pockethive.tcpmock.model.MessageTypeMapping;
import java.util.Collection;
import java.util.List;

/**
 * Responsibility: load and atomically replace the complete persisted mapping catalogue.
 * Must not: choose defaults, mutate runtime state or suppress storage failure.
 * Contract: RESP-TCP-MOCK-MAPPING-FILES — docs/architecture/runtime-responsibilities.md#resp-tcp-mock-mapping-files.
 */
public interface MappingPersistence {
    boolean hasSnapshot();
    List<MessageTypeMapping> load();
    void save(Collection<MessageTypeMapping> mappings);
}
