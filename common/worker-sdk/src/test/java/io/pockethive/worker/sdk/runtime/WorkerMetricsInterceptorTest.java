package io.pockethive.worker.sdk.runtime;

import static org.assertj.core.api.Assertions.assertThat;

import io.micrometer.core.instrument.MeterRegistry;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import io.micrometer.observation.ObservationRegistry;
import io.pockethive.observability.ObservabilityContext;
import io.pockethive.worker.sdk.autoconfigure.PocketHiveWorkerSdkAutoConfiguration;
import io.pockethive.controlplane.topology.ControlPlaneRouteCatalog;
import io.pockethive.controlplane.topology.ControlPlaneTopologyDescriptor;
import io.pockethive.controlplane.topology.ControlQueueDescriptor;
import io.pockethive.worker.sdk.api.PocketHiveWorkerFunction;
import io.pockethive.worker.sdk.api.StatusPublisher;
import io.pockethive.worker.sdk.api.WorkMessage;
import io.pockethive.worker.sdk.api.WorkResult;
import io.pockethive.worker.sdk.api.WorkerContext;
import io.pockethive.worker.sdk.api.WorkerInfo;
import io.pockethive.worker.sdk.config.WorkInputConfig;
import io.pockethive.worker.sdk.config.WorkOutputConfig;
import io.pockethive.worker.sdk.config.WorkerCapability;
import io.pockethive.worker.sdk.config.WorkerInputType;
import io.pockethive.worker.sdk.config.WorkerOutputType;
import io.pockethive.worker.sdk.config.PocketHiveWorker;
import io.pockethive.worker.sdk.input.WorkInput;
import io.pockethive.worker.sdk.input.WorkInputFactory;
import java.util.Optional;
import java.util.Set;
import java.util.Optional;
import java.util.Set;
import org.junit.jupiter.api.Test;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

class WorkerMetricsInterceptorTest {

    private static final WorkerDefinition DEFINITION = new WorkerDefinition(
        "metricsWorker",
        Object.class,
        WorkerInputType.RABBITMQ,
        "metrics-role",
        WorkIoBindings.of("in.metrics", "out.metrics", "exchange.hive"),
        Void.class,
        WorkInputConfig.class,
        WorkOutputConfig.class,
        WorkerOutputType.RABBITMQ,
        "Metrics worker",
        Set.of(WorkerCapability.MESSAGE_DRIVEN)
    );

    private final ApplicationContextRunner contextRunner = new ApplicationContextRunner()
        .withPropertyValues(
            "pockethive.control-plane.worker.role=metrics-role",
            "pockethive.control-plane.worker.enabled=false",
            "pockethive.control-plane.manager.enabled=false",
            "pockethive.control-plane.instance-id=metrics-test",
            "pockethive.control-plane.swarm-id=metrics-swarm",
            "pockethive.control-plane.exchange=metrics-swarm.control",
            "pockethive.control-plane.control-queue-prefix=ph.control.metrics",
            "management.prometheus.metrics.export.pushgateway.enabled=true",
            "management.prometheus.metrics.export.pushgateway.base-url=http://pushgateway:9091",
            "management.prometheus.metrics.export.pushgateway.push-rate=PT30S",
            "management.prometheus.metrics.export.pushgateway.job=worker-metrics-test",
            "management.prometheus.metrics.export.pushgateway.shutdown-operation=DELETE",
            "management.prometheus.metrics.export.pushgateway.grouping-key.instance=metrics-worker",
            "pockethive.worker.inputs.autowire=false",
            "pockethive.inputs.rabbit.queue=ph.metrics.in",
            "pockethive.outputs.rabbit.exchange=ph.metrics.hive",
            "pockethive.outputs.rabbit.routingKey=ph.metrics.out"
        )
        .withUserConfiguration(TestConfiguration.class, PocketHiveWorkerSdkAutoConfiguration.class);

    @Test
    void autoConfigurationDisablesMetricsInterceptorByDefault() {
        contextRunner.run(context -> assertThat(context).doesNotHaveBean(WorkerMetricsInterceptor.class));
    }

    @Test
    void autoConfigurationEnablesMetricsInterceptorWhenPropertySet() {
        contextRunner
            .withPropertyValues("pockethive.control-plane.worker.metrics.enabled=true")
            .run(context -> assertThat(context).hasSingleBean(WorkerMetricsInterceptor.class));
    }

    @Test
    void recordsInvocationDuration() throws Exception {
        SimpleMeterRegistry registry = new SimpleMeterRegistry();
        WorkerMetricsInterceptor interceptor = new WorkerMetricsInterceptor(registry);
        WorkerState state = new WorkerState(DEFINITION);
        state.setStatusPublisher(new WorkerStatusPublisher(state, () -> { }, () -> { }));
        WorkerInvocationContext context = new WorkerInvocationContext(
            DEFINITION,
            state,
            workerContext(state, registry),
            WorkMessage.text("body").build()
        );

        WorkResult result = interceptor.intercept(context, ctx -> WorkResult.none());

        assertThat(result).isInstanceOf(WorkResult.None.class);
        var timer = registry.find("pockethive.worker.invocation.duration").timer();
        assertThat(timer).isNotNull();
        assertThat(timer.count()).isEqualTo(1);
    }

    private WorkerContext workerContext(WorkerState state, SimpleMeterRegistry registry) {
        WorkerInfo info = new WorkerInfo("metrics-role", "swarm", "metrics-instance", "in.metrics", "out.metrics");
        ObservabilityContext observabilityContext = new ObservabilityContext();
        observabilityContext.setHops(new java.util.ArrayList<>());
        return new WorkerContext() {
            @Override
            public WorkerInfo info() {
                return info;
            }

            @Override
            public boolean enabled() {
                return true;
            }

            @Override
            public <C> Optional<C> config(Class<C> type) {
                return state.config(type);
            }

            @Override
            public StatusPublisher statusPublisher() {
                return state.statusPublisher();
            }

            @Override
            public org.slf4j.Logger logger() {
                return org.slf4j.LoggerFactory.getLogger("metrics-test");
            }

            @Override
            public io.micrometer.core.instrument.MeterRegistry meterRegistry() {
                return registry;
            }

            @Override
            public ObservationRegistry observationRegistry() {
                return ObservationRegistry.create();
            }

            @Override
            public ObservabilityContext observabilityContext() {
                return observabilityContext;
            }
        };
    }

    @Configuration(proxyBeanMethods = false)
    static class TestConfiguration {

        @Bean
        MeterRegistry meterRegistry() {
            return new SimpleMeterRegistry();
        }

        @Bean
        TestWorker metricsWorker() {
            return new TestWorker();
        }

        @Bean
        WorkInputFactory stubWorkInputFactory() {
            return new WorkInputFactory() {
                @Override
                public boolean supports(WorkerDefinition definition) {
                    return true;
                }

                @Override
                public WorkInput create(WorkerDefinition definition, WorkInputConfig config) {
                    return new WorkInput() { };
                }
            };
        }

        @Bean("workerControlPlaneTopologyDescriptor")
        ControlPlaneTopologyDescriptor workerControlPlaneTopologyDescriptor() {
            return new ControlPlaneTopologyDescriptor() {
                @Override
                public String role() {
                    return "metrics-role";
                }

                @Override
                public Optional<ControlQueueDescriptor> controlQueue(String instanceId) {
                    return Optional.of(new ControlQueueDescriptor("ph.control.metrics." + instanceId, Set.of(), Set.of()));
                }

                @Override
                public ControlPlaneRouteCatalog routes() {
                    return ControlPlaneRouteCatalog.empty();
                }
            };
        }
    }

    @PocketHiveWorker(
        role = "metrics-role",
        input = WorkerInputType.RABBITMQ,
        output = WorkerOutputType.RABBITMQ
    )
    static class TestWorker implements PocketHiveWorkerFunction {

        @Override
        public WorkResult onMessage(WorkMessage in, WorkerContext context) {
            return WorkResult.none();
        }
    }
}
