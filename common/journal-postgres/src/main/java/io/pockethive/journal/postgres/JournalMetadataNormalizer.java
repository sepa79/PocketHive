package io.pockethive.journal.postgres;

import io.pockethive.journal.api.SwarmRunMetadataUpdate;
import java.util.ArrayList;
import java.util.List;

/**
 * Responsibility: normalize operator metadata fields and enforce the existing tag limits.
 * Must not: query storage or normalize persisted read projections.
 * Contract: RESP-JOURNAL-WRITES — docs/architecture/runtime-responsibilities.md#resp-journal-writes.
 */
final class JournalMetadataNormalizer {
    private JournalMetadataNormalizer() {}
    static SwarmRunMetadataUpdate clean(SwarmRunMetadataUpdate input) {
      if (input == null) {
        return new SwarmRunMetadataUpdate(null, null, null);
      }
      String testPlan = input.testPlan();
      if (testPlan != null) {
        testPlan = testPlan.trim();
        if (testPlan.isBlank()) {
          testPlan = null;
        }
      }
      String description = input.description();
      if (description != null) {
        description = description.trim();
        if (description.isBlank()) {
          description = null;
        }
      }
      List<String> tags = input.tags();
      if (tags != null) {
        List<String> out = new ArrayList<>();
        for (String tag : tags) {
          if (tag == null) {
            continue;
          }
          String trimmed = tag.trim();
          if (trimmed.isBlank()) {
            continue;
          }
          if (trimmed.length() > 64) {
            trimmed = trimmed.substring(0, 64);
          }
          if (!out.contains(trimmed)) {
            out.add(trimmed);
          }
          if (out.size() >= 32) {
            break;
          }
        }
        tags = out.isEmpty() ? null : java.util.Collections.unmodifiableList(out);
      }
      return new SwarmRunMetadataUpdate(testPlan, description, tags);
    }
}
