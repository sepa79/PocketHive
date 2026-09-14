package io.pockethive.swarmcontroller.config;

import io.pockethive.redis.config.RedisDatasetEnvironment;
import io.pockethive.redis.config.RedisOutputEnvironment;
import io.pockethive.swarmcontroller.infra.configuration.WorkerWorkConfigurationAdapter;
import io.pockethive.swarmcontroller.runtime.WorkerWorkConfigurationPort;
import io.pockethive.swarmcontroller.runtime.environment.WorkConnectionEnvironmentResolver;
import io.pockethive.work.config.WorkAdapterEnvironment;
import io.pockethive.work.config.composition.CurrentWorkConfigurationProviders;
import io.pockethive.work.config.policy.InputLifecyclePolicy;
import io.pockethive.work.local.csv.CsvDatasetEnvironment;
import io.pockethive.work.local.scheduler.SchedulerSettingsEnvironment;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

/**
 * Responsibility: explicitly assemble the Controller Work configuration adapter and its settings collaborators.
 * Must not: parse settings, resolve names, perform domain decisions or apply runtime effects.
 * Contract: RESP-CONTROLLER-WORK-CONFIGURATION — docs/architecture/runtime-responsibilities.md#resp-controller-work-configuration.
 */
@Configuration(proxyBeanMethods = false)
public class WorkerWorkConfigurationComposition {
  @Bean
  public WorkerWorkConfigurationPort workerWorkConfiguration(WorkAdapterEnvironment workEnvironment) {
    return new WorkerWorkConfigurationAdapter(workEnvironment,
        new InputLifecyclePolicy(),
        new CsvDatasetEnvironment(), new SchedulerSettingsEnvironment(), new RedisDatasetEnvironment(),
        new WorkConnectionEnvironmentResolver(workEnvironment),
        new CurrentWorkConfigurationProviders().workConfigurationParser(),
        new RedisOutputEnvironment());
  }
}
