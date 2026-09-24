package io.pockethive.journal.api;

import java.util.List;

/**
 * Responsibility: carry operator metadata updates with the existing wire fields.
 * Must not: normalize requests or write storage.
 * Contract: RESP-JOURNAL-WRITES — docs/architecture/runtime-responsibilities.md#resp-journal-writes.
 */
public record SwarmRunMetadataUpdate(String testPlan, String description, List<String> tags) {}
