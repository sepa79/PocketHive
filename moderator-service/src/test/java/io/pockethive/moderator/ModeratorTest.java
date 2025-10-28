package io.pockethive.moderator;

import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import io.pockethive.controlplane.spring.WorkerControlPlaneProperties;
import io.pockethive.moderator.shaper.config.PatternConfig;
import io.pockethive.moderator.shaper.config.PatternConfigValidator;
import io.pockethive.moderator.shaper.config.RepeatAlignment;
import io.pockethive.moderator.shaper.config.RepeatConfig;
import io.pockethive.moderator.shaper.config.RepeatUntil;
import io.pockethive.moderator.shaper.config.StepConfig;
import io.pockethive.moderator.shaper.config.StepMode;
import io.pockethive.moderator.shaper.config.StepRangeConfig;
import io.pockethive.moderator.shaper.config.StepRangeUnit;
import io.pockethive.moderator.shaper.config.TransitionConfig;
import io.pockethive.moderator.shaper.config.TransitionType;
import io.pockethive.moderator.shaper.runtime.PatternAckPacer;
import io.pockethive.worker.sdk.api.StatusPublisher;
import io.pockethive.worker.sdk.api.WorkMessage;
import io.pockethive.worker.sdk.api.WorkResult;
import io.pockethive.worker.sdk.api.WorkerContext;
import io.pockethive.worker.sdk.api.WorkerInfo;
import io.pockethive.worker.sdk.testing.ControlPlaneTestFixtures;
import java.math.BigDecimal;
import java.nio.charset.StandardCharsets;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneId;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.ConcurrentHashMap;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class ModeratorTest {

    private ModeratorDefaults defaults;
    private ModeratorWorkerImpl worker;
    private MutableClock clock;
    private PatternAckPacer.Sleeper sleeper;
    private CapturingStatusPublisher statusPublisher;
    private SimpleMeterRegistry meterRegistry;
    private static final WorkerControlPlaneProperties WORKER_PROPERTIES =
        ControlPlaneTestFixtures.workerProperties("swarm", "moderator", "instance");
    private static final String IN_QUEUE = WORKER_PROPERTIES.getQueues().get("generator");
    private static final String OUT_QUEUE = WORKER_PROPERTIES.getQueues().get("moderator");

    @BeforeEach
    void setUp() {
        defaults = new ModeratorDefaults();
        defaults.setValidator(new PatternConfigValidator());
        defaults.setEnabled(true);
        clock = new MutableClock(Instant.parse("2025-01-01T00:00:00Z"), ZoneId.of("UTC"));
        sleeper = duration -> clock.advance(duration);
        statusPublisher = new CapturingStatusPublisher();
        meterRegistry = new SimpleMeterRegistry();
        worker = new ModeratorWorkerImpl(defaults, clock, sleeper);
    }

    @Test
    void onMessageReturnsForwardedPayload() {
        WorkMessage message = WorkMessage.builder()
                .body("test".getBytes(StandardCharsets.UTF_8))
                .header("original", "true")
                .header("x-ph-in-queue-depth", 37)
                .build();

        assertThat(defaults.getPattern()).isNotNull();
        ModeratorWorkerConfig overrideConfig = new ModeratorWorkerConfig(
                true,
                defaults.getTime(),
                defaults.getRun(),
                defaults.getPattern(),
                defaults.getNormalization(),
                defaults.getGlobalMutators(),
                defaults.getJitter(),
                defaults.getSeeds()
        );

        WorkResult result = worker.onMessage(message, new TestWorkerContext(overrideConfig));

        assertThat(result).isInstanceOf(WorkResult.Message.class);
        WorkMessage forwarded = ((WorkResult.Message) result).value();
        assertThat(new String(forwarded.body(), StandardCharsets.UTF_8)).isEqualTo("test");
        assertThat(forwarded.headers()).containsEntry("original", "true");
        assertThat(forwarded.headers()).containsEntry("x-ph-service", "moderator");
        assertThat(statusPublisher.lastData).containsEntry("enabled", true);
        assertThat(meterRegistry.get("moderator.target_rps").gauge().value()).isGreaterThan(0d);
        assertThat(meterRegistry.get("moderator.actual_rps_out.count").counter().count()).isEqualTo(1d);
    }

    @Test
    void usesDefaultsWhenConfigMissing() {
        WorkMessage message = WorkMessage.builder()
                .textBody("payload")
                .build();

        assertThat(defaults.getPattern()).isNotNull();
        WorkResult result = worker.onMessage(message, new TestWorkerContext(null));

        WorkMessage forwarded = ((WorkResult.Message) result).value();
        assertThat(forwarded.headers()).containsEntry("x-ph-service", "moderator");
    }

    @Test
    void disablingWorkerHaltsEmissions() {
        ModeratorWorkerConfig disabledConfig = new ModeratorWorkerConfig(
                false,
                defaults.getTime(),
                defaults.getRun(),
                defaults.getPattern(),
                defaults.getNormalization(),
                defaults.getGlobalMutators(),
                defaults.getJitter(),
                defaults.getSeeds()
        );

        WorkMessage message = WorkMessage.text("payload").build();

        WorkResult result = worker.onMessage(message, new TestWorkerContext(disabledConfig));

        assertThat(result).isEqualTo(WorkResult.none());
        assertThat(statusPublisher.lastData).containsEntry("enabled", false);
        assertThat(meterRegistry.get("moderator.target_rps").gauge().value()).isZero();
    }

    @Test
    void rejectsGappedPatternDuringBinding() {
        defaults.setPattern(new PatternConfig(
                Duration.ofHours(24),
                BigDecimal.valueOf(1000),
                new RepeatConfig(true, RepeatUntil.TOTAL_TIME, null, RepeatAlignment.FROM_START),
                List.of(
                        new StepConfig(
                                "morning",
                                new StepRangeConfig(StepRangeUnit.PERCENT, null, null, BigDecimal.ZERO, BigDecimal.valueOf(30)),
                                StepMode.FLAT,
                                Map.of(),
                                List.of(),
                                TransitionConfig.none()
                        ),
                        new StepConfig(
                                "evening",
                                new StepRangeConfig(StepRangeUnit.PERCENT, null, null, BigDecimal.valueOf(70), BigDecimal.valueOf(100)),
                                StepMode.FLAT,
                                Map.of(),
                                List.of(),
                                TransitionConfig.none()
                        )
                )
        ));

        assertThatThrownBy(defaults::asConfig)
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("cover");
    }

    private final class TestWorkerContext implements WorkerContext {

        private final ModeratorWorkerConfig config;
        private final WorkerInfo info = new WorkerInfo(
                "moderator",
                WORKER_PROPERTIES.getSwarmId(),
                WORKER_PROPERTIES.getInstanceId(),
                IN_QUEUE,
                OUT_QUEUE);

        private TestWorkerContext(ModeratorWorkerConfig config) {
            this.config = config;
        }

        @Override
        public WorkerInfo info() {
            return info;
        }

        @Override
        public <C> Optional<C> config(Class<C> type) {
            if (config != null && type.isAssignableFrom(ModeratorWorkerConfig.class)) {
                return Optional.of(type.cast(config));
            }
            return Optional.empty();
        }

        @Override
        public StatusPublisher statusPublisher() {
            return statusPublisher;
        }

        @Override
        public org.slf4j.Logger logger() {
            return org.slf4j.LoggerFactory.getLogger("test");
        }

        @Override
        public io.micrometer.core.instrument.MeterRegistry meterRegistry() {
            return meterRegistry;
        }

        @Override
        public io.micrometer.observation.ObservationRegistry observationRegistry() {
            return io.micrometer.observation.ObservationRegistry.create();
        }

        @Override
        public io.pockethive.observability.ObservabilityContext observabilityContext() {
            return new io.pockethive.observability.ObservabilityContext();
        }
    }

    private static final class MutableClock extends Clock {

        private Instant current;
        private final ZoneId zone;

        private MutableClock(Instant start, ZoneId zone) {
            this.current = start;
            this.zone = zone;
        }

        private void advance(Duration duration) {
            if (duration == null || duration.isNegative()) {
                return;
            }
            current = current.plus(duration);
        }

        @Override
        public ZoneId getZone() {
            return zone;
        }

        @Override
        public Clock withZone(ZoneId zone) {
            return new MutableClock(current, zone);
        }

        @Override
        public Instant instant() {
            return current;
        }
    }

    private static final class CapturingStatusPublisher implements StatusPublisher {

        private final Map<String, Object> lastData = new ConcurrentHashMap<>();
        private String inRoute;
        private String outRoute;

        @Override
        public StatusPublisher workIn(String route) {
            this.inRoute = route;
            return this;
        }

        @Override
        public StatusPublisher workOut(String route) {
            this.outRoute = route;
            return this;
        }

        @Override
        public void update(java.util.function.Consumer<MutableStatus> consumer) {
            consumer.accept(new Mutable(lastData));
        }

        private static final class Mutable implements MutableStatus {
            private final Map<String, Object> target;

            private Mutable(Map<String, Object> target) {
                this.target = target;
            }

            @Override
            public MutableStatus data(String key, Object value) {
                target.put(key, value);
                return this;
            }
        }
    }
}
