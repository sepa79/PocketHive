package io.pockethive.controlplane.spring;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.amqp.rabbit.config.SimpleRabbitListenerContainerFactory;
import org.springframework.amqp.rabbit.listener.ConditionalRejectingErrorHandler;
import org.springframework.beans.BeansException;
import org.springframework.beans.factory.config.BeanPostProcessor;

/**
 * Central poison-message handling for Rabbit listeners.
 * <p>
 * Control-plane messages are contract-driven; when a message cannot be parsed or violates the
 * envelope contract, retrying (requeue/redelivery) only creates a redelivery storm.
 * <p>
 * This customizer configures {@link ConditionalRejectingErrorHandler} so parse/schema/contract
 * failures are rejected without requeue, allowing the system to stay healthy.
 * <p>
 * Responsibility: apply poison-message handling to the named Control Plane factory.
 * Must not: modify Work listener error or executor policy.
 * Contract: RESP-CP-LISTENER-POLICY — docs/architecture/runtime-responsibilities.md#resp-cp-listener-policy.
 */
final class ControlPlaneRabbitPoisonMessageCustomizer implements BeanPostProcessor {

    private static final Logger log = LoggerFactory.getLogger(ControlPlaneRabbitPoisonMessageCustomizer.class);

    @Override
    public Object postProcessAfterInitialization(Object bean, String beanName) throws BeansException {
        if (bean instanceof SimpleRabbitListenerContainerFactory factory
            && ControlPlaneRabbitListenerConfiguration.FACTORY_NAME.equals(beanName)) {
            factory.setErrorHandler(new ConditionalRejectingErrorHandler(new ControlPlaneFatalExceptionStrategy()));
            log.info("Configured Rabbit listener container factory '{}' with control-plane poison-message handling", beanName);
        }
        return bean;
    }

}
