package io.pockethive.tcpmock.service;

import org.springframework.stereotype.Service;

@Service
public class LatencySimulator {

    public void simulateLatency() {
        // Default no latency
    }

    public void simulateLatency(long milliseconds) {
        if (milliseconds > 0) {
            try {
                Thread.sleep(milliseconds);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
            }
        }
    }
}
