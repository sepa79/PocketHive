package io.pockethive.rabbit.work;

import io.pockethive.rabbit.api.RabbitConnections;
import io.pockethive.rabbit.api.RabbitResourceBeans;
import io.pockethive.rabbit.api.RabbitResources;
import io.pockethive.rabbit.config.RabbitWorkConnectionConfiguration;
import io.pockethive.topology.work.WorkPlaneResources;
import io.pockethive.work.config.WorkAdapterEnvironment;
import org.springframework.context.annotation.Import;
/**
 * Responsibility: opt manager composition into the explicit Rabbit WorkPlane connection.
 * Activated only by explicit import; intentionally not a component-scan candidate.
 * Must not: infer a broker from worker roles or supply missing WORK configuration.
 * Contract: RESP-RABBIT-CONNECTION — docs/architecture/runtime-responsibilities.md#resp-rabbit-connection.
 */
@Import(RabbitWorkConnectionConfiguration.class)
public class RabbitWorkPlaneConfiguration {
    @org.springframework.context.annotation.Bean
    public WorkAdapterEnvironment workAdapterEnvironment(RabbitConnections connections) {
        return new RabbitWorkBootstrapEnvironment(connections.work());
    }
    @org.springframework.context.annotation.Bean
    public WorkPlaneResources workPlaneResources(
        @org.springframework.beans.factory.annotation.Qualifier(RabbitResourceBeans.WORK)
        RabbitResources resources, RabbitConnections connections) {
        return new RabbitWorkResources(resources, connections.work());
    }
}
