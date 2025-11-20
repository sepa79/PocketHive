package io.pockethive.worker.sdk.autoconfigure;

import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.amqp.rabbit.config.SimpleRabbitListenerContainerFactory;
import org.springframework.beans.BeansException;
import org.springframework.beans.factory.config.BeanPostProcessor;

/**
 * Bean post-processor that configures the default Rabbit listener container factory to use
 * virtual threads for work dispatch. This keeps worker code simple and blocking while
 * allowing high concurrency without exhausting platform threads.
 *
 * The customisation is applied only when a {@link SimpleRabbitListenerContainerFactory}
 * named {@code rabbitListenerContainerFactory} is present.
 */
final class VirtualThreadRabbitContainerCustomizer implements BeanPostProcessor {

    private static final Logger log = LoggerFactory.getLogger(VirtualThreadRabbitContainerCustomizer.class);

    @Override
    public Object postProcessAfterInitialization(Object bean, String beanName) throws BeansException {
        if (bean instanceof SimpleRabbitListenerContainerFactory factory && "rabbitListenerContainerFactory".equals(beanName)) {
            ExecutorService executor = Executors.newVirtualThreadPerTaskExecutor();
            factory.setTaskExecutor(executor);
            log.info("Configured Rabbit listener container factory '{}' to use virtual threads", beanName);
        }
        return bean;
    }
}
