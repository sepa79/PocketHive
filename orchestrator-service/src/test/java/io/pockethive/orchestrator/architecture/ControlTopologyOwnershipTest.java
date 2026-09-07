package io.pockethive.orchestrator.architecture;

import static com.tngtech.archunit.lang.syntax.ArchRuleDefinition.noClasses;
import static org.assertj.core.api.Assertions.assertThat;

import com.tngtech.archunit.core.importer.ClassFileImporter;
import com.tngtech.archunit.core.importer.ImportOption;
import com.tngtech.archunit.lang.ArchRule;
import io.pockethive.controlplane.spring.ControlPlaneTopologyDeclarableFactory;
import io.pockethive.controlplane.topology.ControlPlaneTopologyDescriptor;
import io.pockethive.orchestrator.app.DebugTapService;
import org.junit.jupiter.api.Test;
import org.springframework.amqp.core.BindingBuilder;
import org.springframework.amqp.core.Declarable;
import org.springframework.amqp.core.Declarables;
import org.springframework.amqp.core.ExchangeBuilder;
import org.springframework.amqp.core.Queue;
import org.springframework.amqp.core.QueueBuilder;
import org.springframework.context.annotation.Bean;

class ControlTopologyOwnershipTest {

    // DebugTapService owns only temporary Work Plane tap queues, not manager control topology.
    private static final ArchRule NO_SERVICE_DECLARATIONS = noClasses()
        .that().doNotHaveFullyQualifiedName(DebugTapService.class.getName())
        .should().dependOnClassesThat().areAssignableTo(Declarable.class)
        .orShould().dependOnClassesThat().haveFullyQualifiedName(Declarables.class.getName())
        .orShould().dependOnClassesThat().haveFullyQualifiedName(QueueBuilder.class.getName())
        .orShould().dependOnClassesThat().haveFullyQualifiedName(BindingBuilder.class.getName())
        .orShould().dependOnClassesThat().haveFullyQualifiedName(ExchangeBuilder.class.getName())
        .orShould().dependOnClassesThat().haveFullyQualifiedName(ControlPlaneTopologyDeclarableFactory.class.getName())
        .because("shared manager auto-configuration is the only Orchestrator control topology declaration path");

    @Test
    void productionCannotReintroduceRabbitDeclarationsOrAnAlternativeDescriptor() {
        var classes = new ClassFileImporter().withImportOption(ImportOption.Predefined.DO_NOT_INCLUDE_TESTS)
            .importPackages("io.pockethive.orchestrator");
        NO_SERVICE_DECLARATIONS.check(classes);
        noClasses().should().implement(ControlPlaneTopologyDescriptor.class).check(classes);
    }

    @Test
    void guardRejectsReintroducedServiceOwnedQueueBean() {
        var mutant = new ClassFileImporter().importClasses(DuplicateQueueConfiguration.class);
        assertThat(NO_SERVICE_DECLARATIONS.evaluate(mutant).hasViolation()).isTrue();
    }

    private static class DuplicateQueueConfiguration {
        @Bean
        Queue duplicateControlQueue() {
            return QueueBuilder.durable("ph.control.orchestrator.orch-1").build();
        }
    }
}
