package io.github.ajdailey.qcaret.bootstrap;

import static com.tngtech.archunit.lang.syntax.ArchRuleDefinition.noClasses;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.tngtech.archunit.core.domain.JavaClass;
import com.tngtech.archunit.core.domain.JavaClasses;
import com.tngtech.archunit.core.importer.ClassFileImporter;
import com.tngtech.archunit.core.importer.ImportOption;
import com.tngtech.archunit.lang.ArchCondition;
import com.tngtech.archunit.lang.ArchRule;
import com.tngtech.archunit.lang.ConditionEvents;
import com.tngtech.archunit.lang.EvaluationResult;
import com.tngtech.archunit.lang.SimpleConditionEvent;
import java.io.IOException;
import java.io.InputStream;
import java.io.UncheckedIOException;
import java.nio.charset.StandardCharsets;
import java.util.Arrays;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;

/**
 * The bootstrap's dependency, process, exit and {@code os.name} rules, each shown to fail on a
 * fixture class.
 */
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
  void osNameOnlyInTarget() {
    osNameOnlyInTargetRule().check(classes);
  }

  private static ArchRule osNameOnlyInTargetRule() {
    return noClasses()
        .that()
        .doNotHaveFullyQualifiedName(Target.class.getName())
        .should(holdTheString("os.name"));
  }

  /** Matches a class whose constant pool holds the text as a {@code CONSTANT_Utf8} entry. */
  private static ArchCondition<JavaClass> holdTheString(final String text) {
    byte[] utf8 = text.getBytes(StandardCharsets.UTF_8);
    final byte[] entry = new byte[utf8.length + 3];
    entry[0] = 1;
    entry[1] = (byte) (utf8.length >> 8);
    entry[2] = (byte) utf8.length;
    System.arraycopy(utf8, 0, entry, 3, utf8.length);
    return new ArchCondition<JavaClass>("hold the string \"" + text + "\"") {
      @Override
      public void check(JavaClass type, ConditionEvents events) {
        String file = type.getName().replace('.', '/') + ".class";
        byte[] bytes;
        try (InputStream in =
            BootstrapArchitectureTest.class.getClassLoader().getResourceAsStream(file)) {
          if (in == null) {
            throw new AssertionError("No class file " + file);
          }
          bytes = in.readAllBytes();
        } catch (IOException e) {
          throw new UncheckedIOException(e);
        }
        boolean holds = false;
        for (int i = 0; !holds && i + entry.length <= bytes.length; i++) {
          holds = Arrays.equals(bytes, i, i + entry.length, entry, 0, entry.length);
        }
        events.add(
            new SimpleConditionEvent(
                type,
                holds,
                type.getName() + (holds ? " holds" : " does not hold") + " \"" + text + "\""));
      }
    };
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
    assertViolations(osNameOnlyInTargetRule(), fixtures + "ReadsOsName", "os.name");
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
