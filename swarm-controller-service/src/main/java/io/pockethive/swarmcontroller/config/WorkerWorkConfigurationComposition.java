package io.pockethive.swarmcontroller.config;

import io.pockethive.rabbit.config.RabbitInputSettingsParser;
import io.pockethive.rabbit.config.RabbitOutputSettingsParser;
import io.pockethive.rabbit.config.RabbitWorkSettingsBootstrap;
import io.pockethive.redis.config.RedisDatasetEnvironment;
import io.pockethive.swarmcontroller.infra.configuration.WorkerWorkConfigurationAdapter;
import io.pockethive.swarmcontroller.runtime.WorkerWorkConfigurationPort;
import io.pockethive.swarmcontroller.runtime.environment.WorkConnectionEnvironmentResolver;
import io.pockethive.work.config.policy.InputLifecyclePolicy;
import io.pockethive.work.local.csv.CsvDatasetEnvironment;
import io.pockethive.work.local.scheduler.SchedulerSettingsEnvironment;
import io.pockethive.topology.work.WorkResourceNamesPort;
import io.pockethive.topology.work.PrefixedWorkResourceNames;
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
  public WorkResourceNamesPort workResourceNames() {
    return new PrefixedWorkResourceNames();
  }

  @Bean
  public WorkerWorkConfigurationPort workerWorkConfiguration(SwarmControllerProperties properties, WorkResourceNamesPort names) {
    return new WorkerWorkConfigurationAdapter(properties, names, new io.pockethive.rabbit.config.RabbitWorkEnvironment(),
        new InputLifecyclePolicy(),
        new CsvDatasetEnvironment(), new SchedulerSettingsEnvironment(), new RedisDatasetEnvironment(),
        new WorkConnectionEnvironmentResolver(),
        new RabbitWorkSettingsBootstrap(new RabbitInputSettingsParser(), new RabbitOutputSettingsParser()));
  }
}
