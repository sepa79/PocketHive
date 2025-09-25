package io.pockethive.controlplane;

import io.pockethive.control.CommandTarget;
import io.pockethive.control.ControlSignal;
import io.pockethive.controlplane.consumer.DuplicateSignalGuard;
import org.junit.jupiter.api.Test;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneId;
import java.time.ZoneOffset;

import static org.assertj.core.api.Assertions.assertThat;

class DuplicateSignalGuardTest {

    private final ControlSignal sample = new ControlSignal("config-update", "corr", "idemp", "swarm", "role", "inst", CommandTarget.INSTANCE, null);

    @Test
    void allowsFirstDeliveryAndBlocksSecondWithinTtl() {
        MutableClock clock = new MutableClock(Instant.parse("2024-01-01T00:00:00Z"));
        DuplicateSignalGuard guard = DuplicateSignalGuard.create(Duration.ofMinutes(5), 10, clock);

        assertThat(guard.markIfNew(sample)).isTrue();
        assertThat(guard.markIfNew(sample)).isFalse();
    }

    @Test
    void expiresEntriesAfterTtl() {
        MutableClock clock = new MutableClock(Instant.parse("2024-01-01T00:00:00Z"));
        DuplicateSignalGuard guard = DuplicateSignalGuard.create(Duration.ofMinutes(5), 10, clock);

        assertThat(guard.markIfNew(sample)).isTrue();
        clock.advance(Duration.ofMinutes(6));
        assertThat(guard.markIfNew(sample)).isTrue();
    }

    private static final class MutableClock extends Clock {
        private Instant current;

        private MutableClock(Instant current) {
            this.current = current;
        }

        @Override
        public ZoneId getZone() {
            return ZoneOffset.UTC;
        }

        @Override
        public Clock withZone(ZoneId zone) {
            return this;
        }

        @Override
        public Instant instant() {
            return current;
        }

        void advance(Duration duration) {
            current = current.plus(duration);
        }
    }
}
