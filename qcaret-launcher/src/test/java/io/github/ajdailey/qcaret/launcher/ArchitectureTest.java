package io.github.ajdailey.qcaret.launcher;

import static com.tngtech.archunit.lang.syntax.ArchRuleDefinition.noClasses;
import static com.tngtech.archunit.lang.syntax.ArchRuleDefinition.noMethods;
import static com.tngtech.archunit.library.Architectures.layeredArchitecture;
import static com.tngtech.archunit.library.dependencies.SlicesRuleDefinition.slices;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.tngtech.archunit.base.DescribedPredicate;
import com.tngtech.archunit.core.domain.JavaAccess;
import com.tngtech.archunit.core.domain.JavaClass;
import com.tngtech.archunit.core.domain.JavaClasses;
import com.tngtech.archunit.core.domain.JavaCodeUnit;
import com.tngtech.archunit.core.domain.JavaConstructorCall;
import com.tngtech.archunit.core.domain.JavaField;
import com.tngtech.archunit.core.domain.JavaMethodCall;
import com.tngtech.archunit.core.domain.JavaModifier;
import com.tngtech.archunit.core.importer.ClassFileImporter;
import com.tngtech.archunit.core.importer.ImportOption;
import com.tngtech.archunit.core.importer.Location;
import com.tngtech.archunit.lang.ArchRule;
import com.tngtech.archunit.lang.EvaluationResult;
import io.github.ajdailey.qcaret.app.i18n.UiText;
import io.github.ajdailey.qcaret.core.concurrent.OperationHandle;
import io.github.ajdailey.qcaret.core.concurrent.WorkerThread;
import java.beans.XMLDecoder;
import java.io.ObjectInputStream;
import java.io.ObjectOutputStream;
import java.net.Socket;
import java.net.URL;
import java.net.URLConnection;
import java.security.SecureRandom;
import java.util.ArrayList;
import java.util.List;
import java.util.Random;
import java.util.Timer;
import java.util.concurrent.AbstractExecutorService;
import java.util.concurrent.BlockingQueue;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.Executors;
import java.util.concurrent.ForkJoinPool;
import java.util.concurrent.Future;
import java.util.concurrent.ScheduledThreadPoolExecutor;
import java.util.concurrent.Semaphore;
import java.util.concurrent.ThreadFactory;
import java.util.concurrent.ThreadPoolExecutor;
import java.util.concurrent.locks.Condition;
import java.util.concurrent.locks.Lock;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;

/**
 * Module, layering, security and thread-ownership rules over every class except the bootstrap,
 * which has its own test. A rule is never deleted or loosened to pass a build. A needed violation
 * is an {@code ignoreDependency} or a narrowed predicate naming the class, approved by the user and
 * recorded in the plan's decision table and the design document's rule table.
 *
 * <p>Each rule is shown to fail on a fixture class that breaks it ({@code archfixture} packages in
 * this module's tests), so a rule that matches nothing is caught.
 *
 * <p>logback's {@code AsyncAppender} starts its own writer thread for the debug log file; the
 * thread rules see QCaret's classes only, and that thread is the one documented exception to {@code
 * Workers}-owned threads.
 */
class ArchitectureTest {
  private static final String ROOT = "io.github.ajdailey.qcaret";
  private static final String CORE = ROOT + ".core..";
  private static final String APP = ROOT + ".app..";
  private static final String UI = ROOT + ".ui..";
  private static final String LAUNCHER = ROOT + ".launcher..";
  private static final String WORKERS = ROOT + ".core.concurrent.Workers";
  private static final String[] VIEWS = {ROOT + ".ui.swt..", ROOT + ".app.view.."};

  private static JavaClasses classes;

  @BeforeAll
  static void importClasses() {
    classes =
        new ClassFileImporter()
            .withImportOption(ImportOption.Predefined.DO_NOT_INCLUDE_TESTS)
            .withImportOption(
                new ImportOption() {
                  @Override
                  public boolean includes(Location location) {
                    return !location.contains("-tests.jar");
                  }
                })
            .importPackages(ROOT);
  }

  @Test
  void swtOnlyInViewsAndLauncher() {
    swtOnlyInViewsAndLauncherRule().check(classes);
  }

  private static ArchRule swtOnlyInViewsAndLauncherRule() {
    return noClasses()
        .that()
        .resideOutsideOfPackages(ROOT + ".ui.swt..", LAUNCHER)
        .should()
        .dependOnClassesThat()
        .resideInAPackage("org.eclipse.swt..");
  }

  /**
   * Only SWT's public API is used, so the same code runs on GTK3 and GTK4 and on every platform's
   * SWT build. The internal namespace holds the backend bindings.
   */
  @Test
  void noSwtInternals() {
    noSwtInternalsRule().check(classes);
  }

  private static ArchRule noSwtInternalsRule() {
    return noClasses()
        .should()
        .dependOnClassesThat()
        .resideInAPackage("org.eclipse.swt.internal..");
  }

  @Test
  void layering() {
    for (ArchRule rule : layeringRules()) {
      rule.check(classes);
    }
  }

  private static List<ArchRule> layeringRules() {
    return List.of(
        noClasses()
            .that()
            .resideInAPackage(CORE)
            .should()
            .dependOnClassesThat()
            .resideInAnyPackage(APP, UI, LAUNCHER),
        noClasses()
            .that()
            .resideInAPackage(APP)
            .should()
            .dependOnClassesThat()
            .resideInAnyPackage(UI, LAUNCHER),
        noClasses()
            .that()
            .resideInAPackage(ROOT + ".app.view..")
            .should()
            .dependOnClassesThat()
            .resideInAnyPackage(
                ROOT + ".app.controller..",
                ROOT + ".app.search..",
                ROOT + ".app.config..",
                ROOT + ".app.session..",
                ROOT + ".app.recovery..",
                ROOT + ".app.cli..",
                ROOT + ".app.ipc.."),
        noClasses()
            .that()
            .resideInAPackage(ROOT + ".app.editor..")
            .should()
            .dependOnClassesThat()
            .resideInAPackage(ROOT + ".app.controller..")
            .allowEmptyShould(true),
        noClasses()
            .that()
            .resideInAPackage(ROOT + ".ui.swt..")
            .should()
            .dependOnClassesThat()
            .resideInAnyPackage(
                ROOT + ".core.concurrent..",
                ROOT + ".core.io..",
                ROOT + ".core.buffer..",
                ROOT + ".core.edit..",
                ROOT + ".core.syntax..",
                ROOT + ".core.search..",
                ROOT + ".core.diff..",
                ROOT + ".core.watch..",
                ROOT + ".core.workspace..",
                ROOT + ".app.config..",
                ROOT + ".app.session..",
                ROOT + ".app.recovery..",
                ROOT + ".app.cli..",
                ROOT + ".app.ipc.."));
  }

  /**
   * Core layers, lowest first: util, concurrent, io, text, buffer, document, then edit, syntax,
   * search and diff, which do not use each other; watch and workspace sit on io. A layer uses only
   * the layers below it. A layer with no classes yet is allowed.
   */
  @Test
  void coreLayers() {
    coreLayersRule().check(classes);
  }

  private static ArchRule coreLayersRule() {
    return layeredArchitecture()
        .consideringOnlyDependenciesInLayers()
        .withOptionalLayers(true)
        .layer("util")
        .definedBy(ROOT + ".core.util..")
        .layer("concurrent")
        .definedBy(ROOT + ".core.concurrent..")
        .layer("io")
        .definedBy(ROOT + ".core.io..")
        .layer("text")
        .definedBy(ROOT + ".core.text..")
        .layer("buffer")
        .definedBy(ROOT + ".core.buffer..")
        .layer("document")
        .definedBy(ROOT + ".core.document..")
        .layer("edit")
        .definedBy(ROOT + ".core.edit..")
        .layer("syntax")
        .definedBy(ROOT + ".core.syntax..")
        .layer("search")
        .definedBy(ROOT + ".core.search..")
        .layer("diff")
        .definedBy(ROOT + ".core.diff..")
        .layer("watch")
        .definedBy(ROOT + ".core.watch..")
        .layer("workspace")
        .definedBy(ROOT + ".core.workspace..")
        .whereLayer("concurrent")
        .mayOnlyBeAccessedByLayers(
            "io",
            "text",
            "buffer",
            "document",
            "edit",
            "syntax",
            "search",
            "diff",
            "watch",
            "workspace")
        .whereLayer("io")
        .mayOnlyBeAccessedByLayers(
            "text", "buffer", "document", "edit", "syntax", "search", "diff", "watch", "workspace")
        .whereLayer("text")
        .mayOnlyBeAccessedByLayers("buffer", "document", "edit", "syntax", "search", "diff")
        .whereLayer("buffer")
        .mayOnlyBeAccessedByLayers("document", "edit", "syntax", "search", "diff")
        .whereLayer("document")
        .mayOnlyBeAccessedByLayers("edit", "syntax", "search", "diff")
        .whereLayer("edit")
        .mayNotBeAccessedByAnyLayer()
        .whereLayer("syntax")
        .mayNotBeAccessedByAnyLayer()
        .whereLayer("search")
        .mayNotBeAccessedByAnyLayer()
        .whereLayer("diff")
        .mayNotBeAccessedByAnyLayer()
        .whereLayer("watch")
        .mayNotBeAccessedByAnyLayer()
        .whereLayer("workspace")
        .mayNotBeAccessedByAnyLayer();
  }

  @Test
  void utilitiesDependOnNoOtherPackage() {
    utilitiesDependOnNoOtherPackageRule().check(classes);
  }

  private static ArchRule utilitiesDependOnNoOtherPackageRule() {
    return noClasses()
        .that()
        .resideInAPackage(ROOT + ".core.util..")
        .should()
        .dependOnClassesThat(
            JavaClass.Predicates.resideInAPackage(ROOT + "..")
                .and(JavaClass.Predicates.resideOutsideOfPackage(ROOT + ".core.util..")));
  }

  @Test
  void everyUiTextIsUsed() {
    assertEquals(List.of(), unusedConstants(classes, UiText.class));
  }

  /** Lists the enum constants no class but the enum itself reads. */
  private static List<String> unusedConstants(JavaClasses imported, Class<?> type) {
    JavaClass text = imported.get(type);
    List<String> unused = new ArrayList<String>();
    for (JavaField field : text.getFields()) {
      if (!field.getModifiers().contains(JavaModifier.ENUM)) {
        continue;
      }
      boolean used = false;
      for (JavaAccess<?> access : field.getAccessesToSelf()) {
        if (!access.getOriginOwner().equals(text)) {
          used = true;
        }
      }
      if (!used) {
        unused.add(field.getName());
      }
    }
    return unused;
  }

  @Test
  void noPackageCycles() {
    noPackageCyclesRule().check(classes);
  }

  private static ArchRule noPackageCyclesRule() {
    return slices().matching(ROOT + ".(**)").should().beFreeOfCycles();
  }

  @Test
  void logbackOnlyInLauncher() {
    logbackOnlyInLauncherRule().check(classes);
  }

  private static ArchRule logbackOnlyInLauncherRule() {
    return noClasses()
        .that()
        .resideOutsideOfPackage(LAUNCHER)
        .should()
        .dependOnClassesThat()
        .resideInAPackage("ch.qos.logback..");
  }

  @Test
  void noOutboundNetwork() {
    for (ArchRule rule : noOutboundNetworkRules()) {
      rule.check(classes);
    }
  }

  private static List<ArchRule> noOutboundNetworkRules() {
    return List.of(
        noClasses()
            .should()
            .dependOnClassesThat()
            .resideInAPackage("java.net.http..")
            .orShould()
            .dependOnClassesThat()
            .areAssignableTo(URLConnection.class)
            .orShould()
            .dependOnClassesThat()
            .areAssignableTo(Socket.class)
            .orShould()
            .callMethod(URL.class, "openStream"),
        noClasses()
            .that()
            .resideOutsideOfPackage(ROOT + ".app.ipc..")
            .should()
            .dependOnClassesThat()
            .haveFullyQualifiedName("java.nio.channels.SocketChannel")
            .orShould()
            .dependOnClassesThat()
            .haveFullyQualifiedName("java.nio.channels.ServerSocketChannel"));
  }

  @Test
  void noProcessesDeserializationOrWeakRandom() {
    noProcessesDeserializationOrWeakRandomRule().check(classes);
  }

  private static ArchRule noProcessesDeserializationOrWeakRandomRule() {
    return noClasses()
        .should()
        .dependOnClassesThat()
        .areAssignableTo(ProcessBuilder.class)
        .orShould()
        .callMethod(Runtime.class, "exec", String.class)
        .orShould()
        .callMethod(Runtime.class, "exec", String[].class)
        .orShould()
        .dependOnClassesThat()
        .areAssignableTo(ObjectInputStream.class)
        .orShould()
        .dependOnClassesThat()
        .areAssignableTo(ObjectOutputStream.class)
        .orShould()
        .dependOnClassesThat()
        .areAssignableTo(XMLDecoder.class)
        .orShould()
        .dependOnClassesThat(weakRandom());
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
        .callMethod(System.class, "exit", int.class)
        .orShould()
        .callMethod(Runtime.class, "halt", int.class);
  }

  @Test
  void noSyncExec() {
    noSyncExecRule().check(classes);
  }

  private static ArchRule noSyncExecRule() {
    return noClasses()
        .should()
        .callMethod("org.eclipse.swt.widgets.Display", "syncExec", Runnable.class.getName());
  }

  @Test
  void onlyTheDispatcherPostsToTheDisplay() {
    onlyTheDispatcherPostsToTheDisplayRule().check(classes);
  }

  private static ArchRule onlyTheDispatcherPostsToTheDisplayRule() {
    return noClasses()
        .that()
        .doNotHaveFullyQualifiedName(ROOT + ".ui.swt.SwtUiDispatcher")
        .should()
        .callMethod("org.eclipse.swt.widgets.Display", "asyncExec", Runnable.class.getName())
        .orShould()
        .callMethod(
            "org.eclipse.swt.widgets.Display", "timerExec", "int", Runnable.class.getName());
  }

  @Test
  void viewsNeverBlock() {
    viewsNeverBlockRule().check(classes);
  }

  private static ArchRule viewsNeverBlockRule() {
    return noClasses()
        .that()
        .resideInAnyPackage(VIEWS)
        .should()
        .callMethodWhere(call(Future.class, "get"))
        .orShould()
        .callMethodWhere(call(Thread.class, "join|sleep"))
        .orShould()
        .callMethodWhere(call(Object.class, "wait"))
        .orShould()
        .callMethodWhere(call(Lock.class, "lock.*"))
        .orShould()
        .callMethodWhere(call(Condition.class, "await.*"))
        .orShould()
        .callMethodWhere(call(Semaphore.class, "acquire.*"))
        .orShould()
        .callMethodWhere(call(BlockingQueue.class, "put|take"))
        .orShould()
        .callMethodWhere(call(CountDownLatch.class, "await"))
        .orShould()
        .callMethodWhere(call(OperationHandle.class, "await"))
        .orShould()
        .dependOnClassesThat()
        .resideInAPackage("java.nio.channels..");
  }

  @Test
  void controllersNeverWait() {
    controllersNeverWaitRule().check(classes);
  }

  private static ArchRule controllersNeverWaitRule() {
    return noClasses()
        .that()
        .resideInAnyPackage(ROOT + ".app.controller..", ROOT + ".app.command..")
        .should()
        .callMethodWhere(call(Future.class, "get"))
        .orShould()
        .callMethodWhere(call(Thread.class, "join|sleep"))
        .orShould()
        .callMethodWhere(call(OperationHandle.class, "await"))
        .orShould()
        .callMethodWhere(call(CountDownLatch.class, "await"));
  }

  /** Controllers take platform facts from their callers, so tests can supply any platform. */
  @Test
  void controllersReadNoSystemProperties() {
    controllersReadNoSystemPropertiesRule().check(classes);
  }

  private static ArchRule controllersReadNoSystemPropertiesRule() {
    return noClasses()
        .that()
        .resideInAPackage(ROOT + ".app.controller..")
        .should()
        .callMethod(System.class, "getProperty", String.class)
        .orShould()
        .callMethod(System.class, "getProperty", String.class, String.class);
  }

  /**
   * In a controller, a blocking file or settings call, or a call to a {@link WorkerThread} method,
   * comes from a {@link WorkerThread} method or a worker task's {@code run}.
   */
  @Test
  void controllersBlockOnlyOnWorkers() {
    controllersBlockOnlyOnWorkersRule().check(classes);
  }

  private static ArchRule controllersBlockOnlyOnWorkersRule() {
    return noClasses()
        .that()
        .resideInAPackage(ROOT + ".app.controller..")
        .should()
        .callMethodWhere(blockingCallOffAWorker());
  }

  @Test
  void onlyWorkersCreatesThreads() {
    onlyWorkersCreatesThreadsRule().check(classes);
  }

  private static ArchRule onlyWorkersCreatesThreadsRule() {
    return noClasses()
        .that()
        .doNotHaveFullyQualifiedName(WORKERS)
        .should()
        .callConstructorWhere(newInstanceOf(Thread.class))
        .orShould()
        .callMethodWhere(call(Thread.class, "ofVirtual|ofPlatform|startVirtualThread"))
        .orShould()
        .dependOnClassesThat()
        .areAssignableTo(Executors.class)
        .orShould()
        .dependOnClassesThat()
        .areAssignableTo(ThreadPoolExecutor.class)
        .orShould()
        .dependOnClassesThat()
        .areAssignableTo(ScheduledThreadPoolExecutor.class)
        .orShould()
        .dependOnClassesThat()
        .areAssignableTo(ForkJoinPool.class)
        .orShould()
        .dependOnClassesThat()
        .areAssignableTo(AbstractExecutorService.class)
        .orShould()
        .dependOnClassesThat()
        .areAssignableTo(Timer.class)
        .orShould()
        .implement(ThreadFactory.class);
  }

  /** Code that blocks under a lock uses {@code ReentrantLock}: a monitor pins a virtual thread. */
  @Test
  void noSynchronizedMethodsWhereVirtualThreadsBlock() {
    noSynchronizedMethodsWhereVirtualThreadsBlockRule().check(classes);
  }

  private static ArchRule noSynchronizedMethodsWhereVirtualThreadsBlockRule() {
    return noMethods()
        .that()
        .areDeclaredInClassesThat()
        .resideInAnyPackage(ROOT + ".core.concurrent..", ROOT + ".core.io..", ROOT + ".app.ipc..")
        .should()
        .haveModifier(JavaModifier.SYNCHRONIZED);
  }

  @Test
  void styledTextContentOnlyInTheEditorView() {
    styledTextContentOnlyInTheEditorViewRule().check(classes);
  }

  private static ArchRule styledTextContentOnlyInTheEditorViewRule() {
    return noClasses()
        .that()
        .resideOutsideOfPackage(ROOT + ".ui.swt.editor..")
        .should()
        .implement("org.eclipse.swt.custom.StyledTextContent");
  }

  /** Every edit goes through a controller. */
  @Test
  void viewsNeverEditADocument() {
    viewsNeverEditADocumentRule().check(classes);
  }

  private static ArchRule viewsNeverEditADocumentRule() {
    return noClasses()
        .that()
        .resideInAPackage(ROOT + ".ui.swt..")
        .should()
        .callMethodWhere(callNamed(ROOT + ".core.document.Document", "apply|undo|redo"));
  }

  /** Grammar files are the only XML QCaret parses; the parser setup lives in one package. */
  @Test
  void xmlParsersOnlyInSyntax() {
    xmlParsersOnlyInSyntaxRule().check(classes);
  }

  private static ArchRule xmlParsersOnlyInSyntaxRule() {
    return noClasses()
        .that()
        .resideOutsideOfPackage(ROOT + ".core.syntax..")
        .should()
        .dependOnClassesThat()
        .haveFullyQualifiedName("javax.xml.parsers.DocumentBuilderFactory")
        .orShould()
        .dependOnClassesThat()
        .haveFullyQualifiedName("javax.xml.parsers.SAXParserFactory")
        .orShould()
        .dependOnClassesThat()
        .haveFullyQualifiedName("javax.xml.stream.XMLInputFactory");
  }

  /**
   * The getters only a menu item's check or enabled state reads; {@code CommandStates} maps them.
   */
  @Test
  void viewsTakeCheckStateFromCommandStates() {
    viewsTakeCheckStateFromCommandStatesRule().check(classes);
  }

  private static ArchRule viewsTakeCheckStateFromCommandStatesRule() {
    return noClasses()
        .that()
        .resideInAPackage(ROOT + ".ui.swt..")
        .should()
        .callMethodWhere(callNamed(ROOT + ".app.theme.ThemeController", "mode"))
        .orShould()
        .callMethodWhere(callNamed(ROOT + ".app.controller.DocumentController", "wrap|count"))
        .orShould()
        .callMethodWhere(callNamed(ROOT + ".app.log.LogFiles", "attached"))
        .orShould()
        .callMethodWhere(
            callNamed(
                ROOT + ".core.document.Document",
                "isReadOnly|isDirty|canUndo|canRedo|encoding|lineEndings"))
        .because("CommandStates computes menu and status state");
  }

  // Each rule reports the fixture class that breaks it.

  private static final String VIEW_FIXTURES = ROOT + ".ui.swt.archfixture.ViewViolations$";
  private static final String CONTROLLER_FIXTURES =
      ROOT + ".app.controller.archfixture.ControllerViolations$";

  @Test
  void moduleRulesFailOnTheirFixtures() throws ClassNotFoundException {
    assertViolations(
        swtOnlyInViewsAndLauncherRule(),
        CONTROLLER_FIXTURES + "UsesSwt",
        "org.eclipse.swt.graphics.Point");
    assertViolations(
        noSwtInternalsRule(),
        VIEW_FIXTURES + "UsesSwtInternals",
        "org.eclipse.swt.internal.Library");
    List<ArchRule> layering = layeringRules();
    assertViolations(layering.get(0), ROOT + ".core.archfixture.CoreViolations$UsesApp", "UiText");
    assertViolations(layering.get(1), CONTROLLER_FIXTURES + "UsesView", "SwtKeys");
    assertViolations(
        layering.get(2),
        ROOT + ".app.view.archfixture.ViewContractViolations$UsesController",
        "ApplicationController");
    assertViolations(
        layering.get(3),
        ROOT + ".app.editor.archfixture.EditorStateViolations$UsesController",
        "DocumentController");
    assertViolations(
        layering.get(4), VIEW_FIXTURES + "UsesBackEnd", "SafePaths", "!Selection.caret");
    assertViolations(
        coreLayersRule(),
        new String[] {ROOT + ".core.text.archfixture.TextViolations$UsesBuffer"},
        new Class<?>[] {io.github.ajdailey.qcaret.core.buffer.TextSnapshot.class},
        "TextSnapshot");
    assertViolations(
        utilitiesDependOnNoOtherPackageRule(),
        ROOT + ".core.util.archfixture.UtilViolations$UsesText",
        "LineEnding");
    assertViolations(
        noPackageCyclesRule(),
        new String[] {
          ROOT + ".app.search.archfixture.CycleStart", ROOT + ".app.session.archfixture.CycleEnd"
        },
        new Class<?>[0],
        "Cycle detected");
    assertEquals(
        List.of("UNUSED"),
        unusedConstants(
            new ClassFileImporter()
                .importClasses(
                    Class.forName(ROOT + ".launcher.archfixture.FixtureText"),
                    Class.forName(ROOT + ".launcher.archfixture.FixtureText$Reader")),
            Class.forName(ROOT + ".launcher.archfixture.FixtureText")));
  }

  @Test
  void securityRulesFailOnTheirFixtures() throws ClassNotFoundException {
    assertViolations(logbackOnlyInLauncherRule(), CONTROLLER_FIXTURES + "UsesLogback", "Level");
    List<ArchRule> network = noOutboundNetworkRules();
    assertViolations(
        network.get(0), CONTROLLER_FIXTURES + "Connects", "Socket", "openStream", "java.net.http");
    assertViolations(network.get(1), CONTROLLER_FIXTURES + "Connects", "SocketChannel");
    assertViolations(
        noProcessesDeserializationOrWeakRandomRule(),
        CONTROLLER_FIXTURES + "RunsAndDecodes",
        "ProcessBuilder",
        "Runtime.exec",
        "ObjectInputStream",
        "XMLDecoder",
        "java.util.Random");
    assertViolations(
        onlyMainExitsRule(), CONTROLLER_FIXTURES + "Exits", "System.exit", "Runtime.halt");
    assertViolations(
        xmlParsersOnlyInSyntaxRule(), CONTROLLER_FIXTURES + "ParsesXml", "DocumentBuilderFactory");
  }

  @Test
  void threadRulesFailOnTheirFixtures() throws ClassNotFoundException {
    assertViolations(noSyncExecRule(), VIEW_FIXTURES + "PostsToDisplay", "syncExec", "!asyncExec");
    assertViolations(
        onlyTheDispatcherPostsToTheDisplayRule(),
        VIEW_FIXTURES + "PostsToDisplay",
        "asyncExec",
        "timerExec");
    assertViolations(
        viewsNeverBlockRule(),
        VIEW_FIXTURES + "Blocks",
        "Future.get",
        "Thread.sleep",
        "Object.wait",
        "Lock.lock",
        "Condition.await",
        "Semaphore.acquire",
        "BlockingQueue.put",
        "CountDownLatch.await",
        "OperationHandle.await",
        "java.nio.channels");
    assertViolations(
        controllersNeverWaitRule(),
        CONTROLLER_FIXTURES + "Waits",
        "Future.get",
        "Thread.sleep",
        "CountDownLatch.await",
        "OperationHandle.await");
    assertViolations(
        controllersReadNoSystemPropertiesRule(),
        CONTROLLER_FIXTURES + "ReadsPlatform",
        "getProperty");
    assertViolations(
        controllersBlockOnlyOnWorkersRule(),
        CONTROLLER_FIXTURES + "BlocksOnTheUiThread",
        "Files.readString",
        "UserGuide.writeCopy",
        "!onWorker");
    assertViolations(
        onlyWorkersCreatesThreadsRule(),
        CONTROLLER_FIXTURES + "MakesThreads",
        "java.lang.Thread",
        "java.util.Timer",
        "Executors",
        "ThreadFactory");
    assertViolations(
        noSynchronizedMethodsWhereVirtualThreadsBlockRule(),
        ROOT + ".core.io.archfixture.IoViolations$Synchronized",
        "write()");
  }

  @Test
  void viewRulesFailOnTheirFixtures() throws ClassNotFoundException {
    assertViolations(
        styledTextContentOnlyInTheEditorViewRule(),
        VIEW_FIXTURES + "ImplementsContent",
        "StyledTextContent");
    assertViolations(
        viewsNeverEditADocumentRule(), VIEW_FIXTURES + "EditsDocument", "apply", "undo", "redo");
    assertViolations(
        viewsTakeCheckStateFromCommandStatesRule(),
        VIEW_FIXTURES + "ReadsCheckState",
        "ThemeController.mode",
        "DocumentController.wrap",
        "Document.isReadOnly",
        "!palette");
  }

  /**
   * Checks a rule against one fixture class: every fragment appears in the failure report, and no
   * fragment prefixed {@code !} does.
   */
  private static void assertViolations(ArchRule rule, String fixture, String... fragments)
      throws ClassNotFoundException {
    assertViolations(rule, new String[] {fixture}, new Class<?>[0], fragments);
  }

  /** As above, importing several fixture classes and the classes they depend on. */
  private static void assertViolations(
      ArchRule rule, String[] fixtures, Class<?>[] also, String... fragments)
      throws ClassNotFoundException {
    List<Class<?>> types = new ArrayList<Class<?>>(List.of(also));
    for (String fixture : fixtures) {
      types.add(Class.forName(fixture));
    }
    JavaClasses imported = new ClassFileImporter().importClasses(types);
    EvaluationResult result = rule.evaluate(imported);
    String report = String.join("\n", result.getFailureReport().getDetails());
    String name = String.join(", ", fixtures);
    assertTrue(result.hasViolation(), name);
    for (String fragment : fragments) {
      if (fragment.startsWith("!")) {
        assertFalse(report.contains(fragment.substring(1)), name + ": " + report);
      } else {
        assertTrue(report.contains(fragment), name + " lacks " + fragment + ": " + report);
      }
    }
  }

  /** Matches {@code java.util.Random} and its subtypes except {@code SecureRandom}. */
  private static DescribedPredicate<JavaClass> weakRandom() {
    return new DescribedPredicate<JavaClass>("assignable to Random but not SecureRandom") {
      @Override
      public boolean test(JavaClass type) {
        return type.isAssignableTo(Random.class) && !type.isAssignableTo(SecureRandom.class);
      }
    };
  }

  /** Matches a call to a method of {@code owner} or a subtype whose name matches the pattern. */
  private static DescribedPredicate<JavaMethodCall> call(
      final Class<?> owner, final String namePattern) {
    return new DescribedPredicate<JavaMethodCall>(
        "call " + owner.getSimpleName() + "." + namePattern) {
      @Override
      public boolean test(JavaMethodCall call) {
        return call.getTarget().getName().matches(namePattern)
            && call.getTarget().getOwner().isAssignableTo(owner);
      }
    };
  }

  /** Matches a constructor call creating {@code type} or a subtype. */
  private static DescribedPredicate<JavaConstructorCall> newInstanceOf(final Class<?> type) {
    return new DescribedPredicate<JavaConstructorCall>("new " + type.getSimpleName()) {
      @Override
      public boolean test(JavaConstructorCall call) {
        return call.getTarget().getOwner().isAssignableTo(type);
      }
    };
  }

  /** Matches a call to a method of the named class whose name matches the pattern. */
  private static DescribedPredicate<JavaMethodCall> callNamed(
      final String owner, final String namePattern) {
    return new DescribedPredicate<JavaMethodCall>("call " + owner + "." + namePattern) {
      @Override
      public boolean test(JavaMethodCall call) {
        return call.getTarget().getName().matches(namePattern)
            && call.getTarget().getOwner().getName().equals(owner);
      }
    };
  }

  /**
   * Matches a blocking file or settings call, or a call to a {@link WorkerThread} method, made from
   * a method that is neither a {@link WorkerThread} method nor the {@code run} of an anonymous
   * {@code Runnable}.
   */
  private static DescribedPredicate<JavaMethodCall> blockingCallOffAWorker() {
    return new DescribedPredicate<JavaMethodCall>("blocking call off a worker") {
      @Override
      public boolean test(JavaMethodCall call) {
        String owner = call.getTarget().getOwner().getName();
        String name = call.getTarget().getName();
        boolean blocking =
            owner.equals("java.nio.file.Files")
                || owner.equals(ROOT + ".core.io.AtomicFiles")
                || owner.equals(ROOT + ".core.io.SafePaths")
                || (owner.equals(ROOT + ".app.config.ConfigStore") && name.matches("load|save"))
                || (owner.equals(ROOT + ".app.about.AboutInfo") && name.equals("licenseText"));
        boolean worker =
            call.getTarget().resolveMember().isPresent()
                && call.getTarget().resolveMember().get().isAnnotatedWith(WorkerThread.class);
        if (!blocking && !worker) {
          return false;
        }
        JavaCodeUnit origin = call.getOrigin();
        if (origin.isAnnotatedWith(WorkerThread.class)) {
          return false;
        }
        JavaClass from = origin.getOwner();
        return !(origin.getName().equals("run")
            && from.isAnonymousClass()
            && from.isAssignableTo(Runnable.class));
      }
    };
  }
}
