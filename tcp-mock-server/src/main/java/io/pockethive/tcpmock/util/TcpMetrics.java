package io.pockethive.tcpmock.util;

import io.micrometer.core.instrument.Timer;
import io.micrometer.core.instrument.MeterRegistry;
import org.springframework.stereotype.Component;
import java.util.concurrent.atomic.AtomicLong;

@Component
public class TcpMetrics {
    private final AtomicLong totalRequests = new AtomicLong(0);
    private final AtomicLong echoRequests = new AtomicLong(0);
    private final AtomicLong jsonRequests = new AtomicLong(0);
    private final AtomicLong invalidRequests = new AtomicLong(0);
    private final Timer requestTimer;

    public TcpMetrics(MeterRegistry meterRegistry) {
        this.requestTimer = Timer.builder("tcp.request.duration")
            .description("TCP request processing duration")
            .register(meterRegistry);
    }

    public Timer.Sample startTimer() {
        return Timer.start();
    }

    public void recordDuration(Timer.Sample sample) {
        sample.stop(requestTimer);
    }

    public void incrementTotal() { totalRequests.incrementAndGet(); }
    public void incrementEcho() { echoRequests.incrementAndGet(); }
    public void incrementJson() { jsonRequests.incrementAndGet(); }
    public void incrementInvalid() { invalidRequests.incrementAndGet(); }
    public void incrementRequestResponse() { totalRequests.incrementAndGet(); }

    public long getTotalRequests() { return totalRequests.get(); }
    public long getEchoRequests() { return echoRequests.get(); }
    public long getJsonRequests() { return jsonRequests.get(); }
    public long getInvalidRequests() { return invalidRequests.get(); }
}
