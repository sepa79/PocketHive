package io.pockethive.acceptance.suites;

import static org.junit.jupiter.api.Assertions.*;
import com.fasterxml.jackson.databind.JsonNode;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.stream.StreamSupport;

/**
 * Responsibility: compare owned TCP journal observations with the explicitly authored customer sequences.
 * Must not: parse WebAuth, calculate runtime outcomes or count unrelated traffic as test progress.
 * Contract: docs/architecture/acceptance-tests.md#five-customer-redis-webauth-loop-acceptance-da-4.
 */
final class WebAuthLoopAssertions {
  private WebAuthLoopAssertions() { }
  static boolean complete(JsonNode journal, String nonce, Map<String, List<String>> expected, String response) {
    var owned = StreamSupport.stream(journal.spliterator(), false)
        .filter(row -> row.required("message").textValue().contains(nonce))
        .sorted(Comparator.comparing(row -> Instant.parse(row.required("timestamp").textValue()))).toList();
    var allowed = new HashSet<String>();
    expected.values().forEach(allowed::addAll);
    var ids = new HashSet<String>();
    var messages = new ArrayList<String>();
    for (var row : owned) {
      String id = row.required("id").textValue();
      assertNotNull(id);
      assertFalse(id.isBlank());
      assertTrue(ids.add(id), "Duplicate TCP journal request ID");
      String message = row.required("message").textValue().strip();
      assertTrue(allowed.contains(message), "Unexpected owned WebAuth request: " + message);
      assertEquals(response, row.required("response").textValue());
      messages.add(message);
    }
    boolean complete = true;
    for (var customer : expected.entrySet()) {
      var sequence = customer.getValue();
      var observed = messages.stream().filter(sequence::contains).toList();
      for (int i = 0; i < Math.min(observed.size(), sequence.size()); i++) {
        assertEquals(sequence.get(i), observed.get(i), "Loop order for " + customer.getKey());
      }
      complete &= observed.size() >= sequence.size();
    }
    return complete;
  }
}
