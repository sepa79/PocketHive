package io.pockethive.tcpmock.service;

import io.pockethive.tcpmock.model.MessageTypeMapping;
import java.util.List;

/**
 * Responsibility: supply initial mapping definitions for a fresh runtime only.
 * Must not: persist or mutate the accepted runtime catalogue.
 * Contract: RESP-TCP-MOCK-MAPPING-FILES — docs/architecture/runtime-responsibilities.md#resp-tcp-mock-mapping-files.
 */
public interface StartupMappingSource {
    List<MessageTypeMapping> load();
}
