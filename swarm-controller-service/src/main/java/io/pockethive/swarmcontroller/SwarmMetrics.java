package io.pockethive.swarmcontroller;

import java.time.Instant;

public record SwarmMetrics(int desired, int healthy, int running, int enabled, Instant watermark) {}
