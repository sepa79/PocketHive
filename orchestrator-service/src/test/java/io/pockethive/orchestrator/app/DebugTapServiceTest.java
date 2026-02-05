package io.pockethive.orchestrator.app;

import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.assertj.core.api.Assertions.assertThat;

import io.pockethive.orchestrator.app.DebugTapController.DebugTapRequest;
import io.pockethive.orchestrator.domain.Swarm;
import io.pockethive.orchestrator.domain.SwarmStore;
import io.pockethive.swarm.model.Bee;
import io.pockethive.swarm.model.Work;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.concurrent.CopyOnWriteArrayList;
import java.lang.reflect.Proxy;
import org.junit.jupiter.api.Test;
import org.springframework.amqp.core.AmqpAdmin;
import org.springframework.amqp.core.Binding;
import org.springframework.amqp.rabbit.core.RabbitTemplate;
import org.springframework.web.server.ResponseStatusException;

class DebugTapServiceTest {

    @Test
    void cleanupExpiredRemovesTapAndDeletesQueue() {
        SwarmStore store = new SwarmStore();
        var declaredBindings = new CopyOnWriteArrayList<Binding>();
        var deletedQueues = new CopyOnWriteArrayList<String>();
        AmqpAdmin amqp = recordingAmqpAdmin(declaredBindings, deletedQueues);
        RabbitTemplate rabbit = new RabbitTemplate();

        Swarm swarm = new Swarm("sw1", "inst-1", "c1", "run-1");
        swarm.attachTemplate(new io.pockethive.orchestrator.domain.SwarmTemplateMetadata(
            "tpl-1",
            "swarm-controller:latest",
            List.of(new Bee("processor", "processor:latest", Work.ofDefaults("in", "final"), Map.of()))
        ));
        store.register(swarm);

        DebugTapService service = new DebugTapService(store, amqp, rabbit);
        var created = service.create(new DebugTapRequest("sw1", "processor", "OUT", null, 1, 1));
        String tapId = created.tapId();
        String queue = created.queue();
        assertThat(created.exchange()).isEqualTo("ph.sw1.hive");
        assertThat(created.routingKey()).isEqualTo("ph.sw1.final");

        assertThat(declaredBindings).hasSize(1);
        assertThat(declaredBindings.getFirst().getExchange()).isEqualTo("ph.sw1.hive");
        assertThat(declaredBindings.getFirst().getRoutingKey()).isEqualTo("ph.sw1.final");

        service.cleanupExpired(Instant.now().plusSeconds(5));

        assertThat(deletedQueues).contains(queue);
        assertThatThrownBy(() -> service.read(tapId, 1))
            .isInstanceOf(ResponseStatusException.class)
            .hasMessageContaining("debug tap not found");
    }

    private static AmqpAdmin recordingAmqpAdmin(List<Binding> bindings, List<String> deletedQueues) {
        Objects.requireNonNull(bindings, "bindings");
        Objects.requireNonNull(deletedQueues, "deletedQueues");

        return (AmqpAdmin) Proxy.newProxyInstance(
            AmqpAdmin.class.getClassLoader(),
            new Class<?>[]{AmqpAdmin.class},
            (proxy, method, args) -> {
                String name = method.getName();
                if ("declareQueue".equals(name) && args != null && args.length == 1) {
                    Object queue = args[0];
                    if (queue instanceof org.springframework.amqp.core.Queue q) {
                        return q.getName();
                    }
                    return null;
                }
                if ("declareBinding".equals(name) && args != null && args.length == 1) {
                    if (args[0] instanceof Binding binding) {
                        bindings.add(binding);
                    }
                    return null;
                }
                if ("deleteQueue".equals(name) && args != null && args.length >= 1) {
                    if (args[0] instanceof String queueName) {
                        deletedQueues.add(queueName);
                    }
                    Class<?> returnType = method.getReturnType();
                    if (returnType == boolean.class || returnType == Boolean.class) {
                        return true;
                    }
                    return null;
                }
                Class<?> returnType = method.getReturnType();
                if (returnType == boolean.class) return false;
                if (returnType == int.class) return 0;
                if (returnType == long.class) return 0L;
                if (returnType == double.class) return 0.0d;
                if (returnType == float.class) return 0.0f;
                if (returnType == short.class) return (short) 0;
                if (returnType == byte.class) return (byte) 0;
                if (returnType == char.class) return (char) 0;
                return null;
            });
    }
}
