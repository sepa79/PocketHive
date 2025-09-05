package io.pockethive.orchestrator.architecture;

import com.tngtech.archunit.core.importer.ClassFileImporter;
import com.tngtech.archunit.lang.ArchRule;
import org.junit.jupiter.api.Test;

import static com.tngtech.archunit.lang.syntax.ArchRuleDefinition.noClasses;

class ArchitectureTest {
    @Test
    void domainDoesNotDependOnInfra() {
        var classes = new ClassFileImporter().importPackages("io.pockethive.orchestrator");
        ArchRule rule = noClasses().that().resideInAPackage("..domain..")
                .should().dependOnClassesThat().resideInAPackage("..infra..");
        rule.check(classes);
    }
}
