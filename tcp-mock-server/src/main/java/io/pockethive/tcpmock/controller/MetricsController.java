package io.pockethive.tcpmock.controller;

import io.pockethive.tcpmock.util.TcpMetrics;
import org.springframework.web.bind.annotation.*;
import java.util.Map;

@RestController
@RequestMapping("/api/metrics")
public class MetricsController {
    private final TcpMetrics metrics;

    public MetricsController(TcpMetrics metrics) {
        this.metrics = metrics;
    }

    @GetMapping
    public Map<String, Object> getMetrics() {
        return Map.of(
            "totalRequests", metrics.getTotalRequests(),
            "echoRequests", metrics.getEchoRequests(),
            "jsonRequests", metrics.getJsonRequests()
        );
    }
}
