package io.pockethive.orchestrator.config;

import io.pockethive.rabbit.work.RabbitWorkPlaneConfiguration;
import org.springframework.context.annotation.Configuration;
import org.springframework.context.annotation.Import;

/**
 * Responsibility: compose the currently selected Rabbit WorkPlane infrastructure for this service.
 * Must not: implement Rabbit operations, naming or Work lifecycle behavior.
 * Contract: docs/architecture/work-plane-boundaries.md#10-remaining-workplane-isolation-target--rabbit-plus-a-test-adapter.
 */
@Configuration(proxyBeanMethods = false)
@Import(RabbitWorkPlaneConfiguration.class)
public class WorkPlaneConfiguration {}
