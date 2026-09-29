package io.pockethive.acceptance.suites;

import static org.junit.jupiter.api.Assertions.*;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.util.List;
import java.util.Map;
import java.util.stream.IntStream;
import org.junit.jupiter.api.Test;

class WebAuthLoopAssertionsTest {
  private final ObjectMapper json = new ObjectMapper();
  private final Map<String, List<String>> expected = Map.of("one", List.of("nonce-red", "nonce-bal", "nonce-top", "nonce-red"));
  private Map<String, Object> row(int id, String message, String response) {
    return Map.of("id", "id-" + id, "message", message, "response", response,
        "timestamp", "2026-09-18T10:00:0" + id + "Z");
  }
  @Test void requiresOrderedReturnAndIgnoresUnrelatedTraffic() {
    var rows = new java.util.ArrayList<>(List.of(row(0, "foreign-red", "ERROR")));
    for (int i = 0; i < 3; i++) rows.add(row(i + 1, expected.get("one").get(i), "OK"));
    assertFalse(WebAuthLoopAssertions.complete(json.valueToTree(rows), "nonce", expected, "OK"));
    rows.add(row(4, "nonce-red", "OK"));
    java.util.Collections.reverse(rows);
    assertTrue(WebAuthLoopAssertions.complete(json.valueToTree(rows), "nonce", expected, "OK"));
  }
  @Test void wrongOrderResponsePayloadAndDuplicateRequestCannotPass() {
    for (var rows : List.of(
        List.of(row(1, "nonce-bal", "OK")), List.of(row(1, "nonce-red", "ERROR")),
        List.of(row(1, "nonce-corrupted", "OK")), List.of(row(1, "nonce-red", "OK"), row(1, "nonce-red", "OK")))) {
      assertThrows(AssertionError.class, () -> WebAuthLoopAssertions.complete(json.valueToTree(rows), "nonce", expected, "OK"));
    }
  }
  @Test void requiresEveryCustomerNotJustTotalTraffic() {
    var both = Map.of("one", expected.get("one"), "two", List.of("nonce-other"));
    var rows = IntStream.range(0, 4).mapToObj(i -> row(i, expected.get("one").get(i), "OK")).toList();
    assertFalse(WebAuthLoopAssertions.complete(json.valueToTree(rows), "nonce", both, "OK"));
  }

  @Test void rejectsReturnThroughAnotherCustomersListEvenWithCorrectPayloadAndStage() throws Exception {
    var keys = List.of("red-a", "red-b", "red-c", "red-d", "red-e", "balance", "topup");
    var fixture = new WebAuthLoopFixture(keys, "nonce");
    for (int customer = 0; customer < 5; customer++) {
      String name = "customer-" + customer;
      var sequence = fixture.requests.get(name);
      var sources = List.of(keys.get(customer), "balance", "topup", keys.get(customer));
      for (int stage = 0; stage < 4; stage++) {
        assertTrue(sequence.get(stage).contains("sourceList=\"" + sources.get(stage) + "\""),
            "Expected source list must be bound to customer and stage");
      }
      var observed = new java.util.ArrayList<>(IntStream.range(0, 4)
          .mapToObj(index -> row(index, sequence.get(index), "OK")).toList());
      var expectedCustomer = Map.of(name, sequence);
      assertTrue(WebAuthLoopAssertions.complete(json.valueToTree(observed), "nonce", expectedCustomer, "OK"));
      String wrongReturn = sequence.get(3).replace("sourceList=\"" + keys.get(customer) + "\"",
          "sourceList=\"" + keys.get((customer + 1) % 5) + "\"");
      assertNotEquals(sequence.get(3), wrongReturn);
      observed.set(3, row(3, wrongReturn, "OK"));
      assertThrows(AssertionError.class,
          () -> WebAuthLoopAssertions.complete(json.valueToTree(observed), "nonce", expectedCustomer, "OK"));
    }
  }
}
