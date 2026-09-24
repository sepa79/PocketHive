package io.pockethive.tcpmock.service;

import io.pockethive.tcpmock.model.TcpRequest;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;

class CompatibilityQueriesTest {
    @Test
    void usesStoredUnmatchedMembershipAndPreservesDiagnosticShape() {
        var store = new RequestStore();
        var request = new TcpRequest("id", "client", "hello", Map.of(), "MAPPING", Instant.EPOCH, "OK");
        store.addRequest(request);
        var queries = new CompatibilityQueries(store, TestMappingCatalogues.fresh(), null);
        assertEquals(Map.of("total", 0), queries.getUnmatchedRequests().get("meta"));
        store.addUnmatchedRequest(request);
        assertEquals(Map.of("total", 1), queries.getUnmatchedRequests().get("meta"));
        var entries = (List<?>) queries.getRequests().get("requests");
        assertEquals(Map.of("id", "id", "request", Map.of("method", "TCP", "url", "/tcp-stream", "body", "hello"),
            "response", Map.of("status", 200, "body", "OK"), "loggedDate", Instant.EPOCH), entries.getFirst());
        assertEquals(Map.of("total", 3), queries.getMappings().get("meta"));
    }
}
