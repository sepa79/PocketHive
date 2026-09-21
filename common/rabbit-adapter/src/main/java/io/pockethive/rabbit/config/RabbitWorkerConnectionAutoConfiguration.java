package io.pockethive.rabbit.config;

import org.springframework.boot.autoconfigure.AutoConfiguration;
import org.springframework.context.annotation.Conditional;
import org.springframework.context.annotation.Import;

/**
 * Responsibility: compose Rabbit WORK connections for workers selecting Rabbit IO.
 * Must not: activate WORK for CONTROL-only participants or provide connection defaults.
 * Contract: RESP-RABBIT-CONNECTION — docs/architecture/runtime-responsibilities.md#resp-rabbit-connection.
 */
@AutoConfiguration(after = RabbitConnectionConfiguration.class,
    before = {io.pockethive.rabbit.transport.RabbitTransportAutoConfiguration.class,
              io.pockethive.rabbit.topology.RabbitResourceAutoConfiguration.class})
@Conditional(RabbitWorkerIoCondition.class)
@Import(RabbitWorkConnectionConfiguration.class)
public class RabbitWorkerConnectionAutoConfiguration {}
