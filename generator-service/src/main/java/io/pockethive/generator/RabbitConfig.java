package io.pockethive.generator;

import io.pockethive.Topology;
import org.springframework.amqp.core.*;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

import io.pockethive.util.BeeNameGenerator;
import io.pockethive.controlplane.routing.ControlPlaneRouting;

@Configuration
public class RabbitConfig {
  private static final String ROLE = "generator";

  @Bean
  public String instanceId(){
    return System.getProperty("bee.name", BeeNameGenerator.generate(ROLE, Topology.SWARM_ID));
  }

  @Bean
  TopicExchange direct(){ return new TopicExchange(Topology.EXCHANGE, true, false); }

  @Bean
  TopicExchange controlExchange(){ return new TopicExchange(Topology.CONTROL_EXCHANGE, true, false); }

  @Bean
  Queue controlQueue(String instanceId){
    String name = Topology.CONTROL_QUEUE + "." + Topology.SWARM_ID + "." + ROLE + "." + instanceId;
    return QueueBuilder.durable(name).build();
  }

  @Bean
  Binding bindConfigFleet(@Qualifier("controlQueue") Queue controlQueue,
                          @Qualifier("controlExchange") TopicExchange controlExchange) {
    return BindingBuilder.bind(controlQueue).to(controlExchange)
        .with(ControlPlaneRouting.signal("config-update", "ALL", ROLE, "ALL"));
  }

  @Bean
  Binding bindConfigSwarmRole(@Qualifier("controlQueue") Queue controlQueue,
                              @Qualifier("controlExchange") TopicExchange controlExchange) {
    return BindingBuilder.bind(controlQueue).to(controlExchange)
        .with(ControlPlaneRouting.signal("config-update", Topology.SWARM_ID, ROLE, "ALL"));
  }

  @Bean
  Binding bindConfigSwarmInstance(@Qualifier("controlQueue") Queue controlQueue,
                                  @Qualifier("controlExchange") TopicExchange controlExchange,
                                  String instanceId) {
    return BindingBuilder.bind(controlQueue).to(controlExchange)
        .with(ControlPlaneRouting.signal("config-update", Topology.SWARM_ID, ROLE, instanceId));
  }

  @Bean
  Binding bindConfigSwarmBroadcast(@Qualifier("controlQueue") Queue controlQueue,
                                   @Qualifier("controlExchange") TopicExchange controlExchange) {
    return BindingBuilder.bind(controlQueue).to(controlExchange)
        .with(ControlPlaneRouting.signal("config-update", Topology.SWARM_ID, "ALL", "ALL"));
  }

  @Bean
  Binding bindStatusFleet(@Qualifier("controlQueue") Queue controlQueue,
                          @Qualifier("controlExchange") TopicExchange controlExchange) {
    return BindingBuilder.bind(controlQueue).to(controlExchange)
        .with(ControlPlaneRouting.signal("status-request", "ALL", ROLE, "ALL"));
  }

  @Bean
  Binding bindStatusSwarmRole(@Qualifier("controlQueue") Queue controlQueue,
                              @Qualifier("controlExchange") TopicExchange controlExchange) {
    return BindingBuilder.bind(controlQueue).to(controlExchange)
        .with(ControlPlaneRouting.signal("status-request", Topology.SWARM_ID, ROLE, "ALL"));
  }

  @Bean
  Binding bindStatusSwarmInstance(@Qualifier("controlQueue") Queue controlQueue,
                                  @Qualifier("controlExchange") TopicExchange controlExchange,
                                  String instanceId) {
    return BindingBuilder.bind(controlQueue).to(controlExchange)
        .with(ControlPlaneRouting.signal("status-request", Topology.SWARM_ID, ROLE, instanceId));
  }
}
