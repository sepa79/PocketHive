package io.pockethive.worker.sdk.autoconfigure;

import io.pockethive.work.config.WorkerInputType;
import io.pockethive.work.config.WorkerOutputType;
import io.pockethive.work.config.binding.*;
import io.pockethive.worker.sdk.config.RedisDataSetInputProperties;
import io.pockethive.worker.sdk.config.RedisOutputProperties;
import io.pockethive.worker.sdk.config.SchedulerInputProperties;
import io.pockethive.worker.sdk.config.WorkIoConfigurationCatalog;
import io.pockethive.worker.sdk.input.csv.CsvDataSetInputProperties;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

/**
 * Responsibility: compose startup binding descriptors for SDK-owned local IO and the shared catalog.
 * Must not: register broker configuration classes or repeat adapter settings/route rules.
 * Contract: RESP-WORK-IO-CONFIG — docs/architecture/runtime-responsibilities.md#resp-work-io-config.
 */
@Configuration(proxyBeanMethods = false)
public class WorkIoBindingConfiguration {
    @Bean WorkInputConfigProvider schedulerInputBinding() {
        return new WorkInputConfigProvider(WorkerInputType.SCHEDULER, SchedulerInputProperties.class);
    }
    @Bean WorkInputConfigProvider csvInputBinding() {
        return new WorkInputConfigProvider(WorkerInputType.CSV_DATASET, CsvDataSetInputProperties.class);
    }
    @Bean WorkInputConfigProvider redisInputBinding() {
        return new WorkInputConfigProvider(WorkerInputType.REDIS_DATASET, RedisDataSetInputProperties.class);
    }
    @Bean WorkOutputConfigProvider redisOutputBinding() {
        return new WorkOutputConfigProvider(WorkerOutputType.REDIS, RedisOutputProperties.class);
    }
    @Bean WorkOutputConfigProvider noneOutputBinding() {
        return new WorkOutputConfigProvider(WorkerOutputType.NONE, WorkOutputConfig.class);
    }
    @Bean WorkIoConfigurationCatalog workIoConfigurationCatalog(ObjectProvider<WorkInputConfigProvider> inputs,
                                                               ObjectProvider<WorkOutputConfigProvider> outputs) {
        return new WorkIoConfigurationCatalog(inputs.stream().toList(), outputs.stream().toList());
    }
}
