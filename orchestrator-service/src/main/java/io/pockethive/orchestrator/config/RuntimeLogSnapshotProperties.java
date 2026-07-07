package io.pockethive.orchestrator.config;

import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotNull;
import java.time.Duration;
import java.util.Objects;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.validation.annotation.Validated;

@Validated
@ConfigurationProperties(prefix = "pockethive.runtime-log-snapshots")
public class RuntimeLogSnapshotProperties {

    private final RuntimeLogSnapshotMode mode;
    private final int tailLines;
    private final Duration sinceBeforeAlert;
    private final int maxChars;

    public RuntimeLogSnapshotProperties(@NotNull RuntimeLogSnapshotMode mode,
                                        @Min(1) @Max(2000) int tailLines,
                                        @NotNull Duration sinceBeforeAlert,
                                        @Min(1) int maxChars) {
        this.mode = Objects.requireNonNull(mode, "mode");
        this.tailLines = tailLines;
        this.sinceBeforeAlert = Objects.requireNonNull(sinceBeforeAlert, "sinceBeforeAlert");
        this.maxChars = maxChars;
        if (this.tailLines < 1 || this.tailLines > 2000) {
            throw new IllegalArgumentException("runtime log snapshot tailLines must be between 1 and 2000");
        }
        if (this.sinceBeforeAlert.isNegative()) {
            throw new IllegalArgumentException("runtime log snapshot sinceBeforeAlert must not be negative");
        }
        if (this.maxChars < 1) {
            throw new IllegalArgumentException("runtime log snapshot maxChars must be positive");
        }
    }

    public RuntimeLogSnapshotMode getMode() {
        return mode;
    }

    public int getTailLines() {
        return tailLines;
    }

    public Duration getSinceBeforeAlert() {
        return sinceBeforeAlert;
    }

    public int getMaxChars() {
        return maxChars;
    }
}
