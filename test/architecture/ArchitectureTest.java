package architecture;

import com.tngtech.archunit.core.importer.ImportOption;
import com.tngtech.archunit.junit5.AnalyzeClasses;
import com.tngtech.archunit.junit5.ArchTest;
import com.tngtech.archunit.lang.ArchRule;

import static com.tngtech.archunit.lang.syntax.ArchRuleDefinition.noClasses;

@AnalyzeClasses(packages = "com.yourorg.pockethive", importOptions = ImportOption.DoNotIncludeTests.class)
public class ArchitectureTest {

  @ArchTest
  static final ArchRule domain_should_not_depend_on_other_layers =
      noClasses().that().resideInAPackage("..domain..")
          .should().dependOnClassesThat().resideInAnyPackage("..api..", "..app..", "..infra..");

  @ArchTest
  static final ArchRule api_should_not_depend_on_infra =
      noClasses().that().resideInAPackage("..api..")
          .should().dependOnClassesThat().resideInAnyPackage("..infra..");

  @ArchTest
  static final ArchRule only_infra_may_depend_on_infra =
      noClasses().that().resideOutsideOfPackage("..infra..")
          .should().dependOnClassesThat().resideInAnyPackage("..infra..");
}
