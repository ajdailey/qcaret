package io.github.ajdailey.qcaret.launcher;

import static com.tngtech.archunit.lang.syntax.ArchRuleDefinition.classes;
import static com.tngtech.archunit.lang.syntax.ArchRuleDefinition.methods;
import static com.tngtech.archunit.lang.syntax.ArchRuleDefinition.noClasses;
import static com.tngtech.archunit.lang.syntax.ArchRuleDefinition.noMethods;
import static com.tngtech.archunit.library.Architectures.layeredArchitecture;
import static com.tngtech.archunit.library.dependencies.SlicesRuleDefinition.slices;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.tngtech.archunit.base.DescribedPredicate;
import com.tngtech.archunit.core.domain.JavaAccess;
import com.tngtech.archunit.core.domain.JavaCall;
import com.tngtech.archunit.core.domain.JavaClass;
import com.tngtech.archunit.core.domain.JavaClasses;
import com.tngtech.archunit.core.domain.JavaCodeUnit;
import com.tngtech.archunit.core.domain.JavaConstructorCall;
import com.tngtech.archunit.core.domain.JavaField;
import com.tngtech.archunit.core.domain.JavaMethod;
import com.tngtech.archunit.core.domain.JavaMethodCall;
import com.tngtech.archunit.core.domain.JavaModifier;
import com.tngtech.archunit.core.importer.ClassFileImporter;
import com.tngtech.archunit.core.importer.ImportOption;
import com.tngtech.archunit.core.importer.Location;
import com.tngtech.archunit.lang.ArchCondition;
import com.tngtech.archunit.lang.ArchRule;
import com.tngtech.archunit.lang.ConditionEvents;
import com.tngtech.archunit.lang.EvaluationResult;
import com.tngtech.archunit.lang.SimpleConditionEvent;
import io.github.ajdailey.qcaret.app.about.AboutInfo;
import io.github.ajdailey.qcaret.app.about.UserGuide;
import io.github.ajdailey.qcaret.app.config.ConfigStore;
import io.github.ajdailey.qcaret.app.i18n.UiText;
import io.github.ajdailey.qcaret.app.log.LogFiles;
import io.github.ajdailey.qcaret.app.recovery.RecoveryFile;
import io.github.ajdailey.qcaret.app.recovery.RecoveryStore;
import io.github.ajdailey.qcaret.app.session.SessionStore;
import io.github.ajdailey.qcaret.core.concurrent.WorkerThread;
import io.github.ajdailey.qcaret.core.concurrent.Workers;
import io.github.ajdailey.qcaret.core.document.DocumentFiles;
import io.github.ajdailey.qcaret.core.io.AtomicFiles;
import io.github.ajdailey.qcaret.core.io.SafePaths;
import io.github.ajdailey.qcaret.core.io.SafeSave;
import java.beans.XMLDecoder;
import java.io.ObjectInputStream;
import java.io.ObjectOutputStream;
import java.net.Socket;
import java.net.URL;
import java.net.URLConnection;
import java.nio.channels.FileChannel;
import java.nio.charset.Charset;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.SecureRandom;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
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
  void fileLocksOnlyInProcessLock() {
    fileLocksOnlyInProcessLockRule().check(classes);
  }

  private static ArchRule fileLocksOnlyInProcessLockRule() {
    return noClasses()
        .that()
        .doNotHaveFullyQualifiedName(ROOT + ".core.io.ProcessLock")
        .should()
        .callMethod(FileChannel.class, "tryLock")
        .orShould()
        .callMethod(FileChannel.class, "tryLock", long.class, long.class, boolean.class)
        .orShould()
        .callMethod(FileChannel.class, "lock")
        .orShould()
        .callMethod(FileChannel.class, "lock", long.class, long.class, boolean.class);
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

  /** Only the launcher configures handlers, levels or the {@code LogManager}. */
  @Test
  void julOnlyInLauncher() {
    julOnlyInLauncherRule().check(classes);
  }

  private static ArchRule julOnlyInLauncherRule() {
    return noClasses()
        .that()
        .resideOutsideOfPackage(LAUNCHER)
        .should()
        .dependOnClassesThat()
        .resideInAnyPackage("java.util.logging..", "org.slf4j.jul..");
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

  // Blocking calls in controllers.

  /** Owners and method names of the blocking calls a controller makes only on a worker. */
  private static final List<Blocking> BLOCKING =
      List.of(
          new Blocking(DocumentFiles.class, "estimate|load|save"),
          new Blocking(SafeSave.class, ".*"),
          new Blocking(Files.class, ".*"),
          new Blocking(FileChannel.class, ".*"),
          new Blocking(Path.class, "toRealPath"),
          new Blocking(Charset.class, "availableCharsets"),
          new Blocking(AtomicFiles.class, ".*"),
          new Blocking(SafePaths.class, ".*"),
          new Blocking(ConfigStore.class, "load|save"),
          new Blocking(SessionStore.class, "load|save"),
          new Blocking(RecoveryStore.class, ".*"),
          new Blocking(RecoveryFile.class, ".*"),
          new Blocking(AboutInfo.class, "collect|licenseText"),
          new Blocking(UserGuide.class, "writeCopy"),
          new Blocking(LogFiles.class, "attach|detach"),
          new Blocking(Workers.class, "close"));

  private record Blocking(Class<?> owner, String names) {}

  @Test
  void controllersBlockOnlyOnWorkers() {
    controllersBlockOnlyOnWorkersRule().check(classes);
  }

  @Test
  void workerMethodsRunOnWorkers() {
    workerMethodsRunOnWorkersRule().check(classes);
  }

  private static ArchRule controllersBlockOnlyOnWorkersRule() {
    return classes()
        .that()
        .resideInAPackage(ROOT + ".app.controller..")
        .should(
            new ArchCondition<JavaClass>(
                "make blocking calls only from @WorkerThread methods or tasks submitted to"
                    + " Workers") {
              @Override
              public void check(JavaClass type, ConditionEvents events) {
                for (JavaCall<?> call : type.getCodeUnitCallsFromSelf()) {
                  if (blocking(call) && !onWorker(call.getOrigin())) {
                    events.add(SimpleConditionEvent.violated(call, call.getDescription()));
                  }
                }
              }
            })
        .because("the UI thread never waits");
  }

  /** Annotating a UI-thread method does not pass the blocking rule: its caller fails this one. */
  private static ArchRule workerMethodsRunOnWorkersRule() {
    return methods()
        .that()
        .areAnnotatedWith(WorkerThread.class)
        .should(
            new ArchCondition<JavaMethod>(
                "be called only from @WorkerThread methods or tasks submitted to Workers") {
              @Override
              public void check(JavaMethod method, ConditionEvents events) {
                for (JavaCall<?> call : method.getCallsOfSelf()) {
                  if (!onWorker(call.getOrigin())) {
                    events.add(SimpleConditionEvent.violated(call, call.getDescription()));
                  }
                }
              }
            });
  }

  private static boolean blocking(JavaCall<?> call) {
    for (Blocking entry : BLOCKING) {
      if (call.getTargetOwner().isAssignableTo(entry.owner())
          && call.getTarget().getName().matches(entry.names())) {
        return true;
      }
    }
    return false;
  }

  /**
   * Reports whether code runs on a worker: a method annotated {@code @WorkerThread}, or {@code run}
   * of an anonymous {@code Runnable} whose creating method submits it to {@code Workers}, directly
   * or through a method of its own class that takes a {@code Runnable} and submits it. The creating
   * method must create no other anonymous {@code Runnable} and take no method reference, since
   * bytecode does not show which task it submits.
   */
  private static boolean onWorker(JavaCodeUnit origin) {
    if (origin.isAnnotatedWith(WorkerThread.class)) {
      return true;
    }
    JavaClass task = origin.getOwner();
    if (!origin.getName().equals("run") || !anonymousRunnable(task)) {
      return false;
    }
    Optional<JavaCodeUnit> creator = task.getEnclosingCodeUnit();
    if (creator.isEmpty() || !submits(creator.get(), true)) {
      return false;
    }
    int created = creator.get().getMethodReferencesFromSelf().size();
    for (JavaCall<?> call : creator.get().getCallsFromSelf()) {
      if (call.getTarget().getName().equals("<init>") && anonymousRunnable(call.getTargetOwner())) {
        created++;
      }
    }
    return created == 1;
  }

  /** Reports whether a method submits a task to {@code Workers}, directly or through a helper. */
  private static boolean submits(JavaCodeUnit code, boolean viaHelper) {
    for (JavaCall<?> call : code.getCallsFromSelf()) {
      if (call.getTargetOwner().isEquivalentTo(Workers.class)
          && call.getTarget().getName().matches("tryStart|start|executeCpu")) {
        return true;
      }
      if (viaHelper && call.getTargetOwner().equals(code.getOwner())) {
        Optional<? extends JavaCodeUnit> helper = call.getTarget().resolveMember();
        if (helper.isPresent() && takesRunnable(helper.get()) && submits(helper.get(), false)) {
          return true;
        }
      }
    }
    return false;
  }

  private static boolean takesRunnable(JavaCodeUnit code) {
    for (JavaClass type : code.getRawParameterTypes()) {
      if (type.isEquivalentTo(Runnable.class)) {
        return true;
      }
    }
    return false;
  }

  private static boolean anonymousRunnable(JavaClass type) {
    return type.isAnonymousClass() && type.isAssignableTo(Runnable.class);
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

  /** Icons come from {@code ToolIcons}, which picks the ink for the background they show on. */
  @Test
  void iconsOnlyThroughToolIcons() {
    iconsOnlyThroughToolIconsRule().check(classes);
  }

  @Test
  void iconRuleFailsOnItsFixture() throws ClassNotFoundException {
    assertViolations(
        iconsOnlyThroughToolIconsRule(),
        ROOT + ".ui.swt.archfixture.ViewViolations$SetsIcon",
        "ToolItem.setImage",
        "Button.setImage");
  }

  private static ArchRule iconsOnlyThroughToolIconsRule() {
    String widgets = "org.eclipse.swt.widgets.";
    return noClasses()
        .that()
        .doNotHaveFullyQualifiedName(ROOT + ".ui.swt.ToolIcons")
        .should()
        .callMethodWhere(callNamed(widgets + "ToolItem", "setImage"))
        .orShould()
        .callMethodWhere(callNamed(widgets + "MenuItem", "setImage"))
        .orShould()
        .callMethodWhere(callNamed(widgets + "Button", "setImage"))
        .orShould()
        .callMethodWhere(callNamed(widgets + "TableItem", "setImage"))
        .orShould()
        .callMethodWhere(callNamed(widgets + "TreeItem", "setImage"))
        .orShould()
        .callMethodWhere(callNamed(widgets + "Item", "setImage"))
        .orShould()
        .callMethodWhere(callNamed("org.eclipse.swt.custom.CTabItem", "setImage"));
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
    assertViolations(julOnlyInLauncherRule(), CONTROLLER_FIXTURES + "UsesJul", "Level");
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
        "java.nio.channels");
    assertViolations(
        fileLocksOnlyInProcessLockRule(),
        ROOT + ".core.io.archfixture.IoViolations$Locks",
        "FileChannel.tryLock",
        "FileChannel.lock");
    assertViolations(
        controllersNeverWaitRule(),
        CONTROLLER_FIXTURES + "Waits",
        "Future.get",
        "Thread.sleep",
        "CountDownLatch.await");
    assertViolations(
        controllersReadNoSystemPropertiesRule(),
        CONTROLLER_FIXTURES + "ReadsPlatform",
        "getProperty");
    assertViolations(
        controllersBlockOnlyOnWorkersRule(),
        CONTROLLER_FIXTURES + "BlocksOnTheUiThread",
        "Files.readString",
        "UserGuide.writeCopy",
        "Files.isSymbolicLink",
        "Files.isReadable",
        "!Files.size",
        "!Files.isDirectory");
    assertViolations(
        workerMethodsRunOnWorkersRule(),
        CONTROLLER_FIXTURES + "BlocksOnTheUiThread",
        "uiCallsWorker",
        "!workerCallsWorker");
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
   * Checks a rule against one fixture class and the classes nested in it: every fragment appears in
   * the failure report, and no fragment prefixed {@code !} does.
   */
  private static void assertViolations(ArchRule rule, String fixture, String... fragments)
      throws ClassNotFoundException {
    assertViolations(rule, new String[] {fixture}, new Class<?>[0], fragments);
  }

  /**
   * As above, importing several fixture classes, the given classes and the classes nested in each.
   */
  private static void assertViolations(
      ArchRule rule, String[] fixtures, Class<?>[] also, String... fragments)
      throws ClassNotFoundException {
    List<Class<?>> types = new ArrayList<Class<?>>(List.of(also));
    for (String fixture : fixtures) {
      types.add(Class.forName(fixture));
    }
    final List<String> files = new ArrayList<String>();
    List<String> packages = new ArrayList<String>();
    for (Class<?> type : types) {
      files.add("/" + type.getName().replace('.', '/'));
      packages.add(type.getPackageName());
    }
    JavaClasses imported =
        new ClassFileImporter()
            .withImportOption(
                new ImportOption() {
                  @Override
                  public boolean includes(Location location) {
                    for (String file : files) {
                      if (location.contains(file + ".class") || location.contains(file + "$")) {
                        return true;
                      }
                    }
                    return false;
                  }
                })
            .importPackages(packages);
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
}
