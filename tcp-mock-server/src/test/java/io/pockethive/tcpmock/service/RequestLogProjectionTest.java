package io.pockethive.tcpmock.service;

import io.pockethive.tcpmock.model.TcpRequest;
import java.time.Instant;
import java.util.Map;
import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;

class RequestLogProjectionTest {
    @Test
    void preservesUiClassificationAndNullProjection() {
        var projection = new RequestLogProjection();
        for (String response : new String[] {null, "", "OK", "UNKNOWN_MESSAGE_TYPE", "INVALID_MESSAGE", "ERROR: failure"}) {
            var projected = projection.toMap(new TcpRequest("id", null, null, Map.of(), null, Instant.EPOCH, response));
            assertEquals(false, projected.get("matched"));
            assertEquals("", projected.get("message"));
            assertEquals("", projected.get("clientAddress"));
            assertEquals("", projected.get("behavior"));
            assertEquals(Instant.EPOCH.toString(), projected.get("timestamp"));
        }
        assertEquals(true, projection.toMap(new TcpRequest("id", "client", "in", Map.of(), "MAPPING",
            Instant.EPOCH, "accepted")).get("matched"));
    }
}
