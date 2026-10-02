package io.pockethive.processor.mip;

import com.fasterxml.jackson.databind.ObjectMapper;
import io.pockethive.processor.ProcessorWorkerConfig;
import io.pockethive.worker.sdk.runtime.WorkerControlPlaneRuntime;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.beans.factory.SmartInitializingSingleton;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

/**
 * Responsibility: connect accepted worker configuration events to the MIP resource owner before intake.
 * Must not: merge configuration, own session state or construct authorization fields.
 * Contract: RESP-PROCESSOR-MIP-CONFIG — docs/architecture/runtime-responsibilities.md#resp-processor-mip-config.
 */
@Configuration
public class MipServerComposition implements SmartInitializingSingleton {
  public static final String PROCESSOR_BEAN = "processorWorker";
  private final ObjectProvider<WorkerControlPlaneRuntime> control;
  private final ObjectProvider<MipServerRuntime> runtime;
  public MipServerComposition(ObjectProvider<WorkerControlPlaneRuntime> control,
                              ObjectProvider<MipServerRuntime> runtime) {
    this.control = control;
    this.runtime = runtime;
  }
  @Bean(destroyMethod = "close")
  public MipServerRuntime mipServerRuntime(ObjectMapper mapper) { return new MipServerRuntime(mapper); }
  @Override public void afterSingletonsInstantiated() {
    // Initial configuration errors must fail startup, outside the runtime observer's
    // exception isolation. The immediately replayed snapshot then catches concurrent changes.
    control.getObject().workerConfig(PROCESSOR_BEAN, ProcessorWorkerConfig.class)
        .ifPresent(runtime.getObject()::configure);
    control.getObject().registerStateListener(PROCESSOR_BEAN, snapshot -> {
      var config = snapshot.config(ProcessorWorkerConfig.class);
      if (config.isPresent()) runtime.getObject().configure(config.get());
      else runtime.getObject().configurationRemoved();
    });
  }
}
