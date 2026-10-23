package io.pockethive.httpsequence;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import com.fasterxml.jackson.databind.ObjectMapper;
import io.pockethive.controlplane.ControlPlaneIdentity;
import io.pockethive.controlplane.spring.WorkerControlPlaneProperties;
import io.pockethive.controlplane.spring.ControlPlaneRabbitBindings;
import io.pockethive.requesttemplates.files.TemplateLoader;
import io.pockethive.templating.api.TemplateRenderer;
import io.pockethive.work.api.StatusPublisher;
import io.pockethive.work.api.WorkItem;
import io.pockethive.work.api.WorkPayloadEncoding;
import io.pockethive.work.api.WorkerContext;
import io.pockethive.work.api.WorkerInfo;
import io.pockethive.worker.sdk.autoconfigure.PocketHiveWorkerSdkAutoConfiguration;
import io.pockethive.worker.sdk.config.RedisSequenceProperties;
import io.pockethive.worker.sdk.input.SchedulerWorkInput;
import io.pockethive.worker.sdk.input.WorkInputRegistry;
import io.pockethive.worker.sdk.runtime.WorkerControlPlaneRuntime;
import io.pockethive.worker.sdk.runtime.WorkerRuntime;
import java.net.URI;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Clock;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.atomic.AtomicReference;
import java.util.function.Consumer;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.support.BeanDefinitionRegistry;
import org.springframework.beans.factory.support.RootBeanDefinition;
import org.springframework.boot.autoconfigure.AutoConfigurations;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;

class HttpSequenceSchedulerInputTest {

  @TempDir
  Path templates;

  @Test
  void selectedSchedulerInputRunsHttpSequenceAndRetainsDefaultSeedMetadata() throws Exception {
    Files.writeString(templates.resolve("status.yaml"), """
        protocol: HTTP
        serviceId: default
        callId: status
        method: GET
        pathTemplate: /status
        headersTemplate: {}
        bodyTemplate: ""
        """);
    var config = new HttpSequenceWorkerConfig(
        "http://sut", templates.toString(), "default", 1,
        List.of(new HttpSequenceWorkerConfig.Step(
            "status", "status", null, false, null, List.of(), List.of())),
        HttpSequenceWorkerConfig.DebugCapture.defaults(), Map.of());
    var identity = new ControlPlaneIdentity("swarm-1", "http-sequence", "instance-1");
    var control = mock(WorkerControlPlaneRuntime.class);
    var runtime = mock(WorkerRuntime.class);
    var listener = new AtomicReference<Consumer<WorkerControlPlaneRuntime.WorkerStateSnapshot>>();
    doAnswer(call -> {
      listener.set(call.getArgument(1));
      return null;
    }).when(control).registerStateListener(eq("httpSequenceWorker"), any());

    var workerContext = mock(WorkerContext.class);
    when(workerContext.info()).thenReturn(
        new WorkerInfo(identity.role(), identity.swarmId(), identity.instanceId(), null, null));
    when(workerContext.logger()).thenReturn(LoggerFactory.getLogger(getClass()));
    when(workerContext.statusPublisher()).thenReturn(StatusPublisher.NO_OP);
    when(workerContext.enabled()).thenReturn(true);
    var dispatched = new ArrayList<WorkItem>();
    var results = new ArrayList<WorkItem>();
    var requests = new ArrayList<URI>();

    new ApplicationContextRunner()
        .withConfiguration(AutoConfigurations.of(PocketHiveWorkerSdkAutoConfiguration.class))
        .withUserConfiguration(HttpSequenceSchedulingConfiguration.class)
        .withPropertyValues(
            "pockethive.control-plane.enabled=false",
            "pockethive.control-plane.worker.enabled=false",
            "pockethive.control-plane.manager.enabled=false",
            "pockethive.inputs.type=SCHEDULER",
            "pockethive.inputs.scheduler.rate-per-sec=1",
            "pockethive.inputs.scheduler.max-messages=1",
            "pockethive.inputs.scheduler.initial-delay-ms=600000",
            "pockethive.outputs.type=NONE",
            "pockethive.worker.config.redis.enabled=false")
        .withBean(WorkerControlPlaneProperties.class, () -> new WorkerControlPlaneProperties(
            false, false, "control", identity.swarmId(), identity.instanceId(), "ph.control",
            new WorkerControlPlaneProperties.Worker(false, false, identity.role(), null, null, null)))
        .withBean("workerControlPlaneIdentity", ControlPlaneIdentity.class, () -> identity)
        .withBean(ControlPlaneRabbitBindings.class, () -> new ControlPlaneRabbitBindings(true))
        .withBean("workerControlQueueName", String.class, () -> "unused.control")
        .withBean(WorkerControlPlaneRuntime.class, () -> control)
        .withBean(WorkerRuntime.class, () -> runtime)
        .withInitializer(context -> {
          var worker = new RootBeanDefinition(HttpSequenceWorkerImpl.class);
          worker.setLazyInit(true);
          ((BeanDefinitionRegistry) context.getBeanFactory())
              .registerBeanDefinition("httpSequenceWorker", worker);
        })
        .run(context -> {
          assertThat(context).hasNotFailed();
          var registration = context.getBean(WorkInputRegistry.class)
              .find("httpSequenceWorker").orElseThrow();
          var input = (SchedulerWorkInput<?>) registration.input();
          assertThat(listener.get()).isNotNull();
          try (var runner = new HttpSequenceRunner(
              new ObjectMapper().findAndRegisterModules(), Clock.systemUTC(),
              context.getBean(TemplateRenderer.class), new TemplateLoader(),
              (target, call) -> {
                requests.add(target);
                return new HttpCallResult(200, Map.of(), "{}", null);
              },
              new DefaultHttpSequenceTargetResolver(), context.getBean(RedisSequenceProperties.class))) {
            when(runtime.dispatch(eq("httpSequenceWorker"), any())).thenAnswer(call -> {
              WorkItem seed = call.getArgument(1);
              dispatched.add(seed);
              WorkItem result = runner.run(seed, workerContext, config);
              results.add(result);
              return result;
            });
            var enabled = mock(WorkerControlPlaneRuntime.WorkerStateSnapshot.class);
            when(enabled.enabled()).thenReturn(true);
            when(enabled.rawConfig()).thenReturn(Map.of());
            when(enabled.config(Object.class)).thenReturn(Optional.empty());
            listener.get().accept(enabled);

            input.tick(0);
            input.tick(1_000);

            assertThat(requests).containsExactly(URI.create("http://sut/status"));
            assertThat(results).singleElement().isNotNull();
            assertThat(dispatched).singleElement().satisfies(seed -> {
              assertThat(seed.payload()).isEqualTo("{}");
              assertThat(seed.contentType()).isEqualTo("application/json");
              assertThat(seed.headers()).containsEntry("swarmId", identity.swarmId())
                  .containsEntry("instanceId", identity.instanceId())
                  .containsEntry("x-ph-scheduler-remaining", 0L);
              assertThat(((Number) seed.headers().get("x-ph-seq")).longValue()).isPositive();
              assertThat(Instant.parse((String) seed.headers().get("generatedAt"))).isNotNull();
              assertThat(seed.steps()).singleElement().satisfies(step -> {
                assertThat(step.index()).isZero();
                assertThat(step.payload()).isEqualTo("{}");
                assertThat(step.payloadEncoding()).isEqualTo(WorkPayloadEncoding.UTF_8);
                assertThat(step.headers()).containsEntry(WorkItem.STEP_SERVICE_HEADER, identity.role())
                    .containsEntry(WorkItem.STEP_INSTANCE_HEADER, identity.instanceId());
              });
              assertThat(seed.observabilityContext()).hasValueSatisfying(observability -> {
                assertThat(observability.getTraceId()).isNotBlank();
                assertThat(observability.getSwarmId()).isEqualTo(identity.swarmId());
                assertThat(observability.getHops()).singleElement().satisfies(hop -> {
                  assertThat(hop.getService()).isEqualTo(identity.role());
                  assertThat(hop.getInstance()).isEqualTo(identity.instanceId());
                });
              });
            });
          }
        });
  }
}
