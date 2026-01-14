package io.pockethive.tcpmock.config;

import org.springframework.stereotype.Component;

@Component
public class LoadTestingConfig {
    private long latency = 0;

    public long getLatency() { return latency; }
    public void setLatency(long latency) { this.latency = latency; }
}
