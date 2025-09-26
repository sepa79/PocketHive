package io.pockethive.controlplane;

import com.fasterxml.jackson.databind.ObjectMapper;
import io.pockethive.control.CommandTarget;
import io.pockethive.control.ControlSignal;
import io.pockethive.controlplane.consumer.ControlPlaneConsumer;
import io.pockethive.controlplane.consumer.DuplicateSignalGuard;
import io.pockethive.controlplane.consumer.SelfFilter;
import org.junit.jupiter.api.Test;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneId;
import java.time.ZoneOffset;
import java.util.concurrent.atomic.AtomicInteger;

import static org.assertj.core.api.Assertions.assertThat;

class ControlPlaneConsumerTest {

    private final ObjectMapper mapper = new ObjectMapper();

    @Test
    void appliesDuplicateSuppression() throws Exception {
        MutableClock clock = new MutableClock(Instant.parse("2024-01-01T00:00:00Z"));
        ControlPlaneConsumer consumer = ControlPlaneConsumer.builder(mapper)
            .identity(new ControlPlaneIdentity("swarm", "generator", "gen-1"))
            .duplicateGuard(DuplicateSignalGuard.create(Duration.ofMinutes(1), 32, clock))
            .clock(clock)
            .build();

        ControlSignal signal = new ControlSignal("config-update", "corr", "idemp", "swarm", "generator", "gen-1", CommandTarget.INSTANCE, null);
        String payload = mapper.writeValueAsString(signal);

        AtomicInteger processed = new AtomicInteger();
        consumer.consume(payload, "sig.config-update.generator.gen-1", env -> processed.incrementAndGet());
        consumer.consume(payload, "sig.config-update.generator.gen-1", env -> processed.incrementAndGet());

        assertThat(processed.get()).isEqualTo(1);

        clock.advance(Duration.ofMinutes(2));
        consumer.consume(payload, "sig.config-update.generator.gen-1", env -> processed.incrementAndGet());
        assertThat(processed.get()).isEqualTo(2);
    }

    @Test
    void selfFilterSkipsInstance() throws Exception {
        ControlPlaneConsumer consumer = ControlPlaneConsumer.builder(mapper)
            .identity(new ControlPlaneIdentity("swarm", "generator", "gen-1"))
            .selfFilter(SelfFilter.skipSelfInstance())
            .build();

        ControlSignal signal = new ControlSignal("config-update", "corr", "id", "swarm", "generator", "gen-1", CommandTarget.INSTANCE, null);
        String payload = mapper.writeValueAsString(signal);

        AtomicInteger processed = new AtomicInteger();
        boolean result = consumer.consume(payload, "sig.config-update.generator.gen-1", env -> processed.incrementAndGet());

        assertThat(result).isFalse();
        assertThat(processed).hasValue(0);
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
