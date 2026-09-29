package io.pockethive.worker.sdk.config;

import io.pockethive.work.config.WorkDeliveryParser;
import java.util.LinkedHashMap;
import java.util.Map;

/**
 * Responsibility: bind startup delivery scalar text for the canonical parser.
 * Must not: validate modes or numeric semantics, apply defaults or schedule work.
 * Contract: RESP-WORK-DELIVERY — docs/architecture/work-plane-boundaries.md#12-delayed-work-delivery.
 */
public final class WorkDeliveryProperties {
    private String mode;
    private String delayMs;

    public String getMode() { return mode; }
    public void setMode(String mode) { this.mode = mode; }
    public String getDelayMs() { return delayMs; }
    public void setDelayMs(String delayMs) { this.delayMs = delayMs; }

    public Map<String, Object> declaration() {
        var fields = new LinkedHashMap<String, Object>();
        if (mode != null) fields.put(WorkDeliveryParser.MODE, mode);
        if (delayMs != null) fields.put(WorkDeliveryParser.DELAY_MS, delayMs);
        return Map.copyOf(fields);
    }
}
