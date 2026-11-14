package io.github.ajdailey.qcaret.launcher;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.tngtech.archunit.core.domain.JavaAccess;
import com.tngtech.archunit.core.domain.JavaCall;
import com.tngtech.archunit.core.domain.JavaClass;
import com.tngtech.archunit.core.domain.JavaClasses;
import com.tngtech.archunit.core.domain.JavaCodeUnit;
import com.tngtech.archunit.core.importer.ClassFileImporter;
import com.tngtech.archunit.core.importer.ImportOption;
import com.tngtech.archunit.core.importer.Location;
import io.github.ajdailey.qcaret.core.concurrent.Workers;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.TreeMap;
import java.util.TreeSet;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;

/**
 * Every task {@code qcaret-app} submits to {@link Workers}, listed with what the user sees while it
 * runs. A site is the named method that submits a task directly or creates a {@code BusyWork},
 * after leaving anonymous classes. The test fails when a site is missing from {@link #SITES} or
 * listed but gone, and when a user-started operation listed as showing the busy cursor or its own
 * progress does not make the call its row names.
 */
class BusyCoverageTest {
  private static final String APP = "io.github.ajdailey.qcaret.app.";
  private static final String BUSY_WORK = APP + "controller.BusyWork";

  /** What the user sees while a site's task runs. */
  private enum Shows {
    /**
     * The window's busy cursor: a {@code BusyWork}, or the named method calls {@code setActive}.
     */
    BUSY,
    /** The editor's busy cursor: a {@code BusyWork}, or the named method calls {@code setBusy}. */
    EDITOR_BUSY,
    /** The operation's own progress: the named method makes the named call. */
    OWN_PROGRESS,
    /** User-started, but bounded by a small input; no cursor. */
    QUICK,
    /** Background upkeep the user did not start; no cursor. */
    UPKEEP
  }

  /**
   * One site.
   *
   * @param shows what the user sees
   * @param method the site, or another method, that makes {@code call}; null for none
   * @param call the call that shows the activity; null for none
   */
  private record Site(Shows shows, String method, String call) {}

  private static final Map<String, Site> SITES = new TreeMap<String, Site>();

  static {
    // The window's busy cursor through BusyWork.
    String[] wrapped = {
      "controller.ChecksumRun#save",
      "controller.DocumentEncodings#convert",
      "controller.DroppedFiles#open",
      "controller.FileReload#reload",
      "controller.GrammarLibrary#reload",
      "controller.HelpController#openContainingFolder",
      "controller.HelpController#openFolder",
      "controller.HelpController#reportIssue",
      "controller.HelpController#setDebugLogFile",
      "controller.HelpController#showAbout",
      "controller.HelpController#showGuide",
      "controller.HelpController#showLicenses",
      "controller.MemoryIndicator#collect",
      "controller.NavigationHistory#go",
      "controller.OpenAll#open",
      "controller.RecentFiles#cleanUp",
      "controller.ReplaceController#previewDocument",
      "controller.ReplaceController#previewFile",
      "controller.ReplaceRestore#choose",
      "controller.ResultsNavigation#check",
      "controller.SaveAndReload#saveAsTarget",
      "controller.SaveCleanup#start",
      "controller.SaveCopy#resolve",
      "controller.SaveCopy#resolved",
      "controller.SearchController#run",
      "controller.SelectionChecksum#copy",
      "controller.SelectionDelete#delete",
      "controller.SelectionDelete#deleteNow",
      "controller.WorkspaceBrowse#show",
      "controller.WorkspaceController#openInDefaultApplication",
      "controller.WorkspaceController#set",
      "controller.WorkspaceFiles#create",
      "controller.WorkspaceFiles#renameTo"
    };
    for (String site : wrapped) {
      row(site, Shows.BUSY, null, null);
    }
    // The window's busy cursor raised by hand: the operation spans several tasks, or posts its
    // result under a lock.
    row("controller.BusyWork#submit", Shows.BUSY, null, "setActive");
    row(
        "controller.CompareController#build",
        Shows.BUSY,
        "controller.CompareController#compare",
        "setActive");
    row("controller.CompareController#compareFiles", Shows.BUSY, null, "setActive");
    row("controller.CompareController#compareWithDisk", Shows.BUSY, null, "setActive");
    row("controller.DocumentOpener#open", Shows.BUSY, null, "setActive");
    row(
        "controller.DocumentOpener#read",
        Shows.BUSY,
        "controller.DocumentOpener#open",
        "setActive");
    row("controller.SaveAndReload#write", Shows.BUSY, null, "setActive");
    // The editor's busy cursor.
    row("controller.EditorController#addNextOccurrence", Shows.EDITOR_BUSY, null, null);
    row("controller.EditorController#matchingBracket", Shows.EDITOR_BUSY, null, null);
    row("controller.FoldScheduling#requestFoldAll", Shows.EDITOR_BUSY, null, null);
    row("controller.LineTransformRuns#transformLines", Shows.EDITOR_BUSY, null, "setBusy");
    row("controller.SelectionCommands#startWorker", Shows.EDITOR_BUSY, null, "busy");
    // Their own progress.
    row(
        "controller.BrowserListings#list",
        Shows.OWN_PROGRESS,
        "controller.BrowserListings#children",
        "WORKSPACE_LOADING");
    row(
        "controller.ChecksumRun#start",
        Shows.OWN_PROGRESS,
        "controller.ChecksumRun#progress",
        "showProgress");
    row(
        "controller.DocumentSymbols#start",
        Shows.OWN_PROGRESS,
        "controller.SymbolRows#summary",
        "SYMBOL_SCANNING");
    row(
        "controller.FileIndexHolder#build",
        Shows.OWN_PROGRESS,
        "controller.QuickOpen#summary",
        "QUICK_OPEN_INDEXING");
    row(
        "controller.FindController#run",
        Shows.OWN_PROGRESS,
        "controller.FindController#state",
        "finding");
    row(
        "controller.PrintController#launch",
        Shows.OWN_PROGRESS,
        "controller.PrintController#showProgress",
        "showPrintProgress");
    row("controller.StatisticsController#refresh", Shows.OWN_PROGRESS, null, "showProgress");
    String results = "controller.ResultsDockModel#set";
    row("search.FileRerun#searchDocument", Shows.OWN_PROGRESS, results, "SEARCH_RERUNNING");
    row("search.FileRerun#searchFile", Shows.OWN_PROGRESS, results, "SEARCH_RERUNNING");
    row("search.HyperSearch#searchDocument", Shows.OWN_PROGRESS, results, "SEARCH_RUNNING");
    row("search.HyperSearch#searchFolder", Shows.OWN_PROGRESS, results, "SEARCH_RUNNING");
    String report = "controller.ReplaceController$Report#summary";
    row("search.ReplaceRun#replace", Shows.OWN_PROGRESS, report, "REPLACE_REPORT_PROGRESS");
    row("search.ReplaceRun#replaceAll", Shows.OWN_PROGRESS, report, "REPLACE_REPORT_PROGRESS");
    row("search.ReplaceRun#restore", Shows.OWN_PROGRESS, report, "REPLACE_REPORT_PROGRESS");
    // User-started with a small input, or rerun on each keystroke in their own popup or dialog.
    String[] quick = {
      "controller.DateTimeInsert#choose",
      "controller.QuickOpen#matchOnWorker",
      "controller.SessionController#forgetSearches",
      "controller.Settings#refresh",
      "controller.TextChecksum#textChanged",
      "controller.WindowSet#loadChoices",
      "controller.WordCompletion#request"
    };
    for (String site : quick) {
      row(site, Shows.QUICK, null, null);
    }
    String[] upkeep = {
      "controller.ApplicationController#loadEncodings",
      "controller.BookmarkList#read",
      "controller.DocumentGrammars#detect",
      "controller.EditorHighlights#highlightBrackets",
      "controller.EditorHighlights#highlightOccurrences",
      "controller.FileSettingsReader#readLater",
      "controller.FindHighlights#highlight",
      "controller.FoldScheduling#scheduleFolds",
      "controller.FoldScheduling#scheduleRewrap",
      "controller.GrammarLibrary#load",
      "controller.LineChangesUpdater#compute",
      "controller.MemoryIndicator#read",
      "controller.OverviewUpdater#compute",
      "controller.QuitSequence#quit",
      "controller.RecoveredBaseline#read",
      "controller.RecoveryController#submit",
      "controller.SessionController#save",
      "controller.Settings#startSave",
      "controller.SyntaxHighlighter#submit",
      "controller.WindowSet#loadTheme",
      "controller.WorkspaceBrowse#restoreFolders",
      "ipc.InstanceServer#accept",
      "ipc.InstanceServer#start",
      "log.EventLoopWatchdog#start"
    };
    for (String site : upkeep) {
      row(site, Shows.UPKEEP, null, null);
    }
  }

  private static void row(String site, Shows shows, String method, String call) {
    SITES.put(site, new Site(shows, method == null ? site : method, call));
  }

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
            .importPackages(APP.substring(0, APP.length() - 1));
  }

  @Test
  void everyWorkerSiteIsListed() {
    Map<String, Boolean> found = sites();
    assertEquals(new TreeSet<String>(SITES.keySet()), new TreeSet<String>(found.keySet()));
  }

  @Test
  void listedOperationsShowTheirActivity() {
    Map<String, Boolean> found = sites();
    List<String> silent = new ArrayList<String>();
    for (Map.Entry<String, Site> e : SITES.entrySet()) {
      Site site = e.getValue();
      boolean wrapped = Boolean.TRUE.equals(found.get(e.getKey()));
      boolean shown;
      if (site.shows() == Shows.QUICK || site.shows() == Shows.UPKEEP) {
        shown = true;
      } else if (site.call() == null) {
        shown = wrapped;
      } else {
        shown = calls(site.method(), site.call());
      }
      if (!shown) {
        silent.add(e.getKey());
      }
    }
    assertTrue(silent.isEmpty(), "show no activity: " + silent);
  }

  /** The check fails on a method that does not make the call. */
  @Test
  void theCallCheckFindsAMissingCall() {
    assertTrue(calls("controller.DocumentOpener#open", "setActive"));
    assertFalse(calls("controller.DateTimeInsert#choose", "setActive"));
  }

  /** Maps each site to true when it creates a {@code BusyWork}. */
  private static Map<String, Boolean> sites() {
    Map<String, Boolean> found = new TreeMap<String, Boolean>();
    for (JavaClass type : classes) {
      for (JavaCall<?> call : type.getCodeUnitCallsFromSelf()) {
        JavaClass target = call.getTargetOwner();
        String name = call.getTarget().getName();
        boolean submits =
            target.isEquivalentTo(Workers.class) && name.matches("tryStart|start|executeCpu");
        boolean wraps =
            name.equals("<init>") && target.isAnonymousClass() && target.isAssignableTo(BUSY_WORK);
        if (submits || wraps) {
          String key = key(call.getOrigin());
          found.put(key, wraps || Boolean.TRUE.equals(found.get(key)));
        }
      }
    }
    return found;
  }

  /** Names the named method that holds code, leaving anonymous and local classes. */
  private static String key(JavaCodeUnit code) {
    JavaCodeUnit named = code;
    while (named.getOwner().isAnonymousClass() || named.getOwner().isLocalClass()) {
      Optional<JavaCodeUnit> enclosing = named.getOwner().getEnclosingCodeUnit();
      if (enclosing.isEmpty()) {
        break;
      }
      named = enclosing.get();
    }
    return named.getOwner().getName().substring(APP.length()) + "#" + named.getName();
  }

  /**
   * Reports whether a method, or an anonymous class inside it, calls a method or reads a field of
   * that name.
   */
  private static boolean calls(String method, String call) {
    for (JavaClass type : classes) {
      for (JavaAccess<?> c : type.getAccessesFromSelf()) {
        if (c.getTarget().getName().equals(call) && key(c.getOrigin()).equals(method)) {
          return true;
        }
      }
    }
    return false;
  }
}
