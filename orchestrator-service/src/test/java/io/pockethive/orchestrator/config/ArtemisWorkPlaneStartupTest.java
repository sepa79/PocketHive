package io.pockethive.orchestrator.config;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static io.pockethive.artemis.config.ArtemisEnvironmentKeys.*;

import io.pockethive.artemis.api.ArtemisWorkIoType;
import io.pockethive.topology.work.WorkPlaneResources;
import io.pockethive.topology.work.WorkTopologyResolver;
import io.pockethive.work.config.WorkAdapterEnvironment;
import io.pockethive.work.config.WorkPlaneSelection;
import java.util.Map;
import java.util.Set;
import org.junit.jupiter.api.Test;
import org.springframework.context.annotation.AnnotationConfigApplicationContext;
import org.springframework.core.env.MapPropertySource;

class ArtemisWorkPlaneStartupTest {
    @Test void orchestratorWorkCompositionStartsWithoutArtemisButResourceOperationsFail() {
        try (var context = new AnnotationConfigApplicationContext()) {
            context.getEnvironment().getPropertySources().addFirst(new MapPropertySource("test", Map.of(
                WorkPlaneSelection.PROPERTY, ArtemisWorkIoType.ARTEMIS.name(),
                BROKER_URL_PROPERTY, "vm://2147483647",
                USERNAME_PROPERTY, "test-user", PASSWORD_PROPERTY, "test-password",
                CALL_TIMEOUT_PROPERTY, "500", NAMESPACE_PROPERTY, "ph")));
            context.register(WorkPlaneConfiguration.class);
            context.refresh();
            var topology = context.getBean(WorkTopologyResolver.class).resolve("swarm", Set.of("jobs"));
            var resources = context.getBean(WorkPlaneResources.class);
            assertThat(context.getBean(WorkAdapterEnvironment.class).connectionEnvironment())
                .containsEntry(BROKER_URL, "vm://2147483647");
            assertThat(resources.identify(resources.removalTarget(topology.channel("jobs").resource())))
                .isEqualTo(topology.channel("jobs").resource());
            assertThatThrownBy(() -> resources.observeInput(topology.channel("jobs").inputAddress()))
                .isInstanceOf(IllegalStateException.class).hasMessageContaining("Artemis");
            assertThat(context.isActive()).isTrue();
        }
    }
}
