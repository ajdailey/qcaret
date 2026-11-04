package io.github.ajdailey.qcaret.bootstrap;

import static com.tngtech.archunit.lang.syntax.ArchRuleDefinition.noClasses;

import com.tngtech.archunit.core.domain.JavaClasses;
import com.tngtech.archunit.core.importer.ClassFileImporter;
import com.tngtech.archunit.core.importer.ImportOption;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;

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
    noClasses()
        .should()
        .dependOnClassesThat()
        .resideOutsideOfPackages("io.github.ajdailey.qcaret.bootstrap", "java..")
        .check(classes);
  }

  @Test
  void onlyTheMacRelaunchStartsProcesses() {
    noClasses()
        .that()
        .doNotHaveFullyQualifiedName(MacRelaunch.class.getName())
        .should()
        .dependOnClassesThat()
        .haveFullyQualifiedName(ProcessBuilder.class.getName())
        .orShould()
        .callMethod(Runtime.class, "exec", String.class)
        .check(classes);
  }

  @Test
  void onlyMainExits() {
    noClasses()
        .that()
        .doNotHaveFullyQualifiedName(Main.class.getName())
        .should()
        .callMethod(System.class, "exit", int.class)
        .check(classes);
  }
}
