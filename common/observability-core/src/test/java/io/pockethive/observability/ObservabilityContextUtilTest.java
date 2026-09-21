package io.pockethive.observability;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;

class ObservabilityContextUtilTest {
    @Test
    void initSetsSwarmId() {
        ObservabilityContext ctx = ObservabilityContextUtil.init("svc", "inst", "sw1");
        assertEquals("sw1", ctx.getSwarmId());
    }

    @Test
    void headerRoundTripPreservesSwarmId() {
        ObservabilityContext ctx = ObservabilityContextUtil.init("svc", "inst", "sw1");
        String header = ObservabilityContextUtil.toHeader(ctx);
        ObservabilityContext parsed = ObservabilityContextUtil.fromHeader(header);
        assertEquals("sw1", parsed.getSwarmId());
    }
}
