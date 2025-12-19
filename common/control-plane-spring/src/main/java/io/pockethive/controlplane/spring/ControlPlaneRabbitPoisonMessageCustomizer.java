package io.pockethive.controlplane.spring;

import com.fasterxml.jackson.core.JsonProcessingException;
import java.util.Optional;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.amqp.rabbit.config.SimpleRabbitListenerContainerFactory;
import org.springframework.amqp.rabbit.listener.ConditionalRejectingErrorHandler;
import org.springframework.amqp.rabbit.listener.FatalExceptionStrategy;
import org.springframework.amqp.rabbit.support.ListenerExecutionFailedException;
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
 */
final class ControlPlaneRabbitPoisonMessageCustomizer implements BeanPostProcessor {

    private static final Logger log = LoggerFactory.getLogger(ControlPlaneRabbitPoisonMessageCustomizer.class);

    @Override
    public Object postProcessAfterInitialization(Object bean, String beanName) throws BeansException {
        if (bean instanceof SimpleRabbitListenerContainerFactory factory) {
            factory.setErrorHandler(new ConditionalRejectingErrorHandler(new ControlPlaneFatalExceptionStrategy()));
            log.info("Configured Rabbit listener container factory '{}' with control-plane poison-message handling", beanName);
        }
        return bean;
    }

    static final class ControlPlaneFatalExceptionStrategy extends ConditionalRejectingErrorHandler.DefaultExceptionStrategy {

        @Override
        public boolean isFatal(Throwable throwable) {
            if (throwable == null) {
                return false;
            }
            Throwable candidate = unwrapListenerException(throwable);
            if (findCause(candidate, JsonProcessingException.class).isPresent()) {
                return true;
            }
            if (findCause(candidate, IllegalArgumentException.class).isPresent()) {
                return true;
            }
            return super.isFatal(throwable);
        }

        private static Throwable unwrapListenerException(Throwable throwable) {
            if (throwable instanceof ListenerExecutionFailedException failed && failed.getCause() != null) {
                return failed.getCause();
            }
            return throwable;
        }

        private static <T extends Throwable> Optional<T> findCause(Throwable throwable, Class<T> type) {
            Throwable current = throwable;
            while (current != null) {
                if (type.isInstance(current)) {
                    return Optional.of(type.cast(current));
                }
                current = current.getCause();
            }
            return Optional.empty();
        }
    }
}

