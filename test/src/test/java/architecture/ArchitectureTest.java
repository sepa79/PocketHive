package architecture;

import com.tngtech.archunit.core.domain.JavaClasses;
import com.tngtech.archunit.core.importer.ClassFileImporter;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertTrue;

class ArchitectureTest {
  @Test
  void allServicesShouldDependOnTopologyCore() {
    List<String> services = List.of(
        "io.pockethive.generator",
        "io.pockethive.logaggregator",
        "io.pockethive.moderator",
        "io.pockethive.orchestrator",
        "io.pockethive.postprocessor",
        "io.pockethive.processor",
        "io.pockethive.swarmcontroller",
        "io.pockethive.trigger"
    );
    for (String pkg : services) {
      JavaClasses classes = new ClassFileImporter().importPackages(pkg);
      boolean dependsOnTopology = classes.stream().anyMatch(c ->
          c.getDirectDependenciesFromSelf().stream()
              .anyMatch(d -> d.getTargetClass().getName().equals("io.pockethive.Topology")));
      assertTrue(dependsOnTopology, pkg + " should depend on io.pockethive.Topology");
    }
  }
}
