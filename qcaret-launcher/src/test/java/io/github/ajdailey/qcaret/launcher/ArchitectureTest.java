package io.github.ajdailey.qcaret.launcher;

import static com.tngtech.archunit.lang.syntax.ArchRuleDefinition.noClasses;
import static com.tngtech.archunit.lang.syntax.ArchRuleDefinition.noMethods;
import static com.tngtech.archunit.library.Architectures.layeredArchitecture;
import static com.tngtech.archunit.library.dependencies.SlicesRuleDefinition.slices;
import static org.junit.jupiter.api.Assertions.assertEquals;

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
    noClasses()
        .that()
        .resideOutsideOfPackages(ROOT + ".ui.swt..", LAUNCHER)
        .should()
        .dependOnClassesThat()
        .resideInAPackage("org.eclipse.swt..")
        .check(classes);
  }

  /**
   * Only SWT's public API is used, so the same code runs on GTK3 and GTK4 and on every platform's
   * SWT build. The internal namespace holds the backend bindings.
   */
  @Test
  void noSwtInternals() {
    noClasses()
        .should()
        .dependOnClassesThat()
        .resideInAPackage("org.eclipse.swt.internal..")
        .check(classes);
  }

  @Test
  void layering() {
    noClasses()
        .that()
        .resideInAPackage(CORE)
        .should()
        .dependOnClassesThat()
        .resideInAnyPackage(APP, UI, LAUNCHER)
        .check(classes);
    noClasses()
        .that()
        .resideInAPackage(APP)
        .should()
        .dependOnClassesThat()
        .resideInAnyPackage(UI, LAUNCHER)
        .check(classes);
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
            ROOT + ".app.ipc..")
        .check(classes);
    noClasses()
        .that()
        .resideInAPackage(ROOT + ".app.editor..")
        .should()
        .dependOnClassesThat()
        .resideInAPackage(ROOT + ".app.controller..")
        .allowEmptyShould(true)
        .check(classes);
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
            ROOT + ".app.ipc..")
        .check(classes);
  }

  /**
   * Core layers, lowest first: util, concurrent, io, text, buffer, document, then edit, syntax,
   * search and diff, which do not use each other; watch and workspace sit on io. A layer uses only
   * the layers below it. A layer with no classes yet is allowed.
   */
  @Test
  void coreLayers() {
    layeredArchitecture()
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
        .mayNotBeAccessedByAnyLayer()
        .check(classes);
  }

  @Test
  void utilitiesDependOnNoOtherPackage() {
    noClasses()
        .that()
        .resideInAPackage(ROOT + ".core.util..")
        .should()
        .dependOnClassesThat(
            JavaClass.Predicates.resideInAPackage(ROOT + "..")
                .and(JavaClass.Predicates.resideOutsideOfPackage(ROOT + ".core.util..")))
        .check(classes);
  }

  @Test
  void everyUiTextIsUsed() {
    JavaClass text = classes.get(UiText.class);
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
    assertEquals(List.of(), unused);
  }

  @Test
  void noPackageCycles() {
    slices().matching(ROOT + ".(**)").should().beFreeOfCycles().check(classes);
  }

  @Test
  void logbackOnlyInLauncher() {
    noClasses()
        .that()
        .resideOutsideOfPackage(LAUNCHER)
        .should()
        .dependOnClassesThat()
        .resideInAPackage("ch.qos.logback..")
        .check(classes);
  }

  @Test
  void noOutboundNetwork() {
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
        .callMethod(URL.class, "openStream")
        .check(classes);
    noClasses()
        .that()
        .resideOutsideOfPackage(ROOT + ".app.ipc..")
        .should()
        .dependOnClassesThat()
        .haveFullyQualifiedName("java.nio.channels.SocketChannel")
        .orShould()
        .dependOnClassesThat()
        .haveFullyQualifiedName("java.nio.channels.ServerSocketChannel")
        .check(classes);
  }

  @Test
  void noProcessesDeserializationOrWeakRandom() {
    noClasses()
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
        .dependOnClassesThat(weakRandom())
        .check(classes);
  }

  @Test
  void onlyMainExits() {
    noClasses()
        .that()
        .doNotHaveFullyQualifiedName(Main.class.getName())
        .should()
        .callMethod(System.class, "exit", int.class)
        .orShould()
        .callMethod(Runtime.class, "halt", int.class)
        .check(classes);
  }

  @Test
  void noSyncExec() {
    noClasses()
        .should()
        .callMethod("org.eclipse.swt.widgets.Display", "syncExec", Runnable.class.getName())
        .check(classes);
  }

  @Test
  void onlyTheDispatcherPostsToTheDisplay() {
    noClasses()
        .that()
        .doNotHaveFullyQualifiedName(ROOT + ".ui.swt.SwtUiDispatcher")
        .should()
        .callMethod("org.eclipse.swt.widgets.Display", "asyncExec", Runnable.class.getName())
        .orShould()
        .callMethod("org.eclipse.swt.widgets.Display", "timerExec", "int", Runnable.class.getName())
        .check(classes);
  }

  @Test
  void viewsNeverBlock() {
    noClasses()
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
        .resideInAPackage("java.nio.channels..")
        .check(classes);
  }

  @Test
  void controllersNeverWait() {
    noClasses()
        .that()
        .resideInAnyPackage(ROOT + ".app.controller..", ROOT + ".app.command..")
        .should()
        .callMethodWhere(call(Future.class, "get"))
        .orShould()
        .callMethodWhere(call(Thread.class, "join|sleep"))
        .orShould()
        .callMethodWhere(call(OperationHandle.class, "await"))
        .orShould()
        .callMethodWhere(call(CountDownLatch.class, "await"))
        .check(classes);
  }

  /** Controllers take platform facts from their callers, so tests can supply any platform. */
  @Test
  void controllersReadNoSystemProperties() {
    noClasses()
        .that()
        .resideInAPackage(ROOT + ".app.controller..")
        .should()
        .callMethod(System.class, "getProperty", String.class)
        .orShould()
        .callMethod(System.class, "getProperty", String.class, String.class)
        .check(classes);
  }

  /**
   * In a controller, a blocking file or settings call, or a call to a {@link WorkerThread} method,
   * comes from a {@link WorkerThread} method or a worker task's {@code run}.
   */
  @Test
  void controllersBlockOnlyOnWorkers() {
    noClasses()
        .that()
        .resideInAPackage(ROOT + ".app.controller..")
        .should()
        .callMethodWhere(blockingCallOffAWorker())
        .check(classes);
  }

  @Test
  void onlyWorkersCreatesThreads() {
    noClasses()
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
        .implement(ThreadFactory.class)
        .check(classes);
  }

  /** Code that blocks under a lock uses {@code ReentrantLock}: a monitor pins a virtual thread. */
  @Test
  void noSynchronizedMethodsWhereVirtualThreadsBlock() {
    noMethods()
        .that()
        .areDeclaredInClassesThat()
        .resideInAnyPackage(ROOT + ".core.concurrent..", ROOT + ".core.io..", ROOT + ".app.ipc..")
        .should()
        .haveModifier(JavaModifier.SYNCHRONIZED)
        .check(classes);
  }

  @Test
  void styledTextContentOnlyInTheEditorView() {
    noClasses()
        .that()
        .resideOutsideOfPackage(ROOT + ".ui.swt.editor..")
        .should()
        .implement("org.eclipse.swt.custom.StyledTextContent")
        .check(classes);
  }

  /** Every edit goes through a controller. */
  @Test
  void viewsNeverEditADocument() {
    noClasses()
        .that()
        .resideInAPackage(ROOT + ".ui.swt..")
        .should()
        .callMethodWhere(callNamed(ROOT + ".core.document.Document", "apply|undo|redo"))
        .check(classes);
  }

  /** Grammar files are the only XML QCaret parses; the parser setup lives in one package. */
  @Test
  void xmlParsersOnlyInSyntax() {
    noClasses()
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
        .haveFullyQualifiedName("javax.xml.stream.XMLInputFactory")
        .check(classes);
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
