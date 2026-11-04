package io.github.ajdailey.qcaret.bootstrap;

import static com.tngtech.archunit.lang.syntax.ArchRuleDefinition.noClasses;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.tngtech.archunit.core.domain.JavaClasses;
import com.tngtech.archunit.core.importer.ClassFileImporter;
import com.tngtech.archunit.core.importer.ImportOption;
import com.tngtech.archunit.lang.ArchRule;
import com.tngtech.archunit.lang.EvaluationResult;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;

/** The bootstrap's dependency, process and exit rules, each shown to fail on a fixture class. */
class BootstrapArchitectureTest {
  private static JavaClasses classes;

  @BeforeAll
  static void importClasses() {
    classes =
        new ClassFileImporter()
            .withImportOption(ImportOption.Predefined.DO_NOT_INCLUDE_TESTS)
            .importPackages("io.github.ajdailey.qcaret.bootstrap");
  }

  @Test
  void dependsOnTheJdkOnly() {
    dependsOnTheJdkOnlyRule().check(classes);
  }

  private static ArchRule dependsOnTheJdkOnlyRule() {
    return noClasses()
        .should()
        .dependOnClassesThat()
        .resideOutsideOfPackages("io.github.ajdailey.qcaret.bootstrap", "java..");
  }

  @Test
  void onlyTheMacRelaunchStartsProcesses() {
    onlyTheMacRelaunchStartsProcessesRule().check(classes);
  }

  private static ArchRule onlyTheMacRelaunchStartsProcessesRule() {
    return noClasses()
        .that()
        .doNotHaveFullyQualifiedName(MacRelaunch.class.getName())
        .should()
        .dependOnClassesThat()
        .haveFullyQualifiedName(ProcessBuilder.class.getName())
        .orShould()
        .callMethod(Runtime.class, "exec", String.class);
  }

  @Test
  void onlyMainExits() {
    onlyMainExitsRule().check(classes);
  }

  private static ArchRule onlyMainExitsRule() {
    return noClasses()
        .that()
        .doNotHaveFullyQualifiedName(Main.class.getName())
        .should()
        .callMethod(System.class, "exit", int.class);
  }

  @Test
  void rulesFailOnTheirFixtures() throws ClassNotFoundException {
    String fixtures = "io.github.ajdailey.qcaret.bootstrap.archfixture.BootViolations$";
    assertViolations(dependsOnTheJdkOnlyRule(), fixtures + "UsesJavax", "javax.naming.Context");
    assertViolations(
        onlyTheMacRelaunchStartsProcessesRule(),
        fixtures + "StartsProcesses",
        "ProcessBuilder",
        "Runtime.exec");
    assertViolations(onlyMainExitsRule(), fixtures + "Exits", "System.exit");
  }

  /** Checks a rule against one fixture class: every fragment appears in the failure report. */
  private static void assertViolations(ArchRule rule, String fixture, String... fragments)
      throws ClassNotFoundException {
    JavaClasses imported = new ClassFileImporter().importClasses(Class.forName(fixture));
    EvaluationResult result = rule.evaluate(imported);
    String report = String.join("\n", result.getFailureReport().getDetails());
    assertTrue(result.hasViolation(), fixture);
    for (String fragment : fragments) {
      assertTrue(report.contains(fragment), fixture + " lacks " + fragment + ": " + report);
    }
  }
}
