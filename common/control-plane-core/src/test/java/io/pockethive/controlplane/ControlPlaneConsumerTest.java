package io.pockethive.controlplane;

import com.fasterxml.jackson.databind.ObjectMapper;
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
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class ControlPlaneConsumerTest {

    private final ObjectMapper mapper = new ObjectMapper().findAndRegisterModules();

    @Test
    void appliesDuplicateSuppression() throws Exception {
        MutableClock clock = new MutableClock(Instant.parse("2024-01-01T00:00:00Z"));
        ControlPlaneConsumer consumer = ControlPlaneConsumer.builder(mapper)
            .identity(new ControlPlaneIdentity("swarm", "generator", "gen-1"))
            .duplicateGuard(DuplicateSignalGuard.create(Duration.ofMinutes(1), 32, clock))
            .clock(clock)
            .build();

        ControlSignal signal = ControlSignal.forInstance(
            "config-update", "swarm", "generator", "gen-1", "orchestrator-1", "corr", "idemp", null);
        String payload = mapper.writeValueAsString(signal);

        AtomicInteger processed = new AtomicInteger();
        consumer.consume(payload, "signal.config-update.swarm.generator.gen-1", env -> processed.incrementAndGet());
        consumer.consume(payload, "signal.config-update.swarm.generator.gen-1", env -> processed.incrementAndGet());

        assertThat(processed.get()).isEqualTo(1);

        clock.advance(Duration.ofMinutes(2));
        consumer.consume(payload, "signal.config-update.swarm.generator.gen-1", env -> processed.incrementAndGet());
        assertThat(processed.get()).isEqualTo(2);
    }

    @Test
    void selfFilterSkipsInstance() throws Exception {
        ControlPlaneConsumer consumer = ControlPlaneConsumer.builder(mapper)
            .identity(new ControlPlaneIdentity("swarm", "generator", "gen-1"))
            .selfFilter(SelfFilter.skipSelfInstance())
            .build();

        ControlSignal signal = ControlSignal.forInstance(
            "config-update", "swarm", "generator", "gen-1", "gen-1", "corr", "id", null);
        String payload = mapper.writeValueAsString(signal);

        AtomicInteger processed = new AtomicInteger();
        boolean result = consumer.consume(payload, "signal.config-update.swarm.generator.gen-1", env -> processed.incrementAndGet());

        assertThat(result).isFalse();
        assertThat(processed).hasValue(0);
    }

    @Test
    void selfFilterProcessesWhenOriginDiffers() throws Exception {
        ControlPlaneConsumer consumer = ControlPlaneConsumer.builder(mapper)
            .identity(new ControlPlaneIdentity("swarm", "generator", "gen-1"))
            .selfFilter(SelfFilter.skipSelfInstance())
            .build();

        ControlSignal signal = ControlSignal.forInstance(
            "config-update", "swarm", "generator", "gen-1", "gen-2", "corr", "id", null);
        String payload = mapper.writeValueAsString(signal);

        AtomicInteger processed = new AtomicInteger();
        boolean result = consumer.consume(payload, "signal.config-update.swarm.generator.gen-1", env -> processed.incrementAndGet());

        assertThat(result).isTrue();
        assertThat(processed).hasValue(1);
    }

    @Test
    void rejectsNullPayload() {
        ControlPlaneConsumer consumer = ControlPlaneConsumer.builder(mapper)
            .identity(new ControlPlaneIdentity("swarm", "generator", "gen-1"))
            .build();

        assertThatThrownBy(() -> consumer.consume(null, "signal.config-update.swarm.generator.gen-1", env -> { }))
            .isInstanceOf(IllegalArgumentException.class)
            .hasMessage("payload must not be null or blank");
    }

    @Test
    void rejectsBlankRoutingKey() throws Exception {
        ControlPlaneConsumer consumer = ControlPlaneConsumer.builder(mapper)
            .identity(new ControlPlaneIdentity("swarm", "generator", "gen-1"))
            .build();

        ControlSignal signal = ControlSignal.forInstance(
            "config-update", "swarm", "generator", "gen-1", "orchestrator-1", "corr", "id", null);
        assertThatThrownBy(() -> consumer.consume(mapper.writeValueAsString(signal), "  ", env -> { }))
            .isInstanceOf(IllegalArgumentException.class)
            .hasMessage("routingKey must not be null or blank");
    }

    @Test
    void processesValidPayload() throws Exception {
        ControlPlaneConsumer consumer = ControlPlaneConsumer.builder(mapper)
            .identity(new ControlPlaneIdentity("swarm", "generator", "gen-2"))
            .build();

        ControlSignal signal = ControlSignal.forInstance(
            "config-update", "swarm", "generator", "gen-2", "orchestrator-1", "corr", "id", null);
        String payload = mapper.writeValueAsString(signal);

        AtomicInteger processed = new AtomicInteger();
        boolean consumed = consumer.consume(payload, "signal.config-update.swarm.generator.gen-2", env -> processed.incrementAndGet());

        assertThat(consumed).isTrue();
        assertThat(processed).hasValue(1);
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
