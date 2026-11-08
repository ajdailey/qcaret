package io.github.ajdailey.qcaret.app.controller;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertTrue;

import io.github.ajdailey.qcaret.app.command.CommandId;
import io.github.ajdailey.qcaret.app.i18n.UiText;
import io.github.ajdailey.qcaret.app.view.WorkspaceBrowseInput;
import io.github.ajdailey.qcaret.app.view.WorkspaceNode;
import io.github.ajdailey.qcaret.core.concurrent.MemoryBudget;
import io.github.ajdailey.qcaret.core.workspace.FileIndex;
import io.github.ajdailey.qcaret.core.workspace.ListingOptions;
import io.github.ajdailey.qcaret.core.workspace.NameFilter;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.attribute.FileTime;
import java.time.Instant;
import java.time.ZoneId;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.function.BooleanSupplier;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/**
 * The workspace browser's navigation away from and back to the workspace folder, the path field's
 * refusals, favorites, the filter, hidden entries, the Size and Modified columns and their sort, a
 * listing canceled by a new folder, a second window following a rename and a Refresh, and the
 * workspace folder following a rename and cleared by a delete.
 */
class WorkspaceBrowseTest {
  @TempDir Path temp;

  private Harness harness;
  private Path root;
  private Path outside;

  @BeforeEach
  void create() throws Exception {
    harness = new Harness(temp);
    root = Files.createDirectory(temp.resolve("ws")).toRealPath();
    Files.createDirectories(root.resolve("src").resolve("deep"));
    Files.writeString(root.resolve("src").resolve("Main.java"), "class Main {}\n");
    Files.writeString(root.resolve("src").resolve("deep").resolve("Leaf.java"), "class Leaf {}\n");
    Files.write(root.resolve("notes.txt"), new byte[2_048]);
    Files.write(root.resolve("Readme.MD"), new byte[10]);
    Files.writeString(root.resolve(".hidden.txt"), "hidden\n");
    outside = Files.createDirectory(temp.resolve("outside")).toRealPath();
    Files.writeString(outside.resolve("other.txt"), "other\n");
    setRoot(harness, harness.application.workspace(), root);
    harness.application.execute(CommandId.WORKSPACE_BROWSER);
    listed(workspace(), workspace().rootNode());
  }

  @AfterEach
  void close() throws InterruptedException {
    harness.close();
  }

  private static void setRoot(Harness on, final WorkspaceController workspace, final Path folder)
      throws InterruptedException {
    workspace.set(folder);
    on.await(
        new BooleanSupplier() {
          @Override
          public boolean getAsBoolean() {
            return folder.equals(workspace.root()) && workspace.index() != null;
          }
        },
        "workspace set and indexed");
  }

  private WorkspaceController workspace() {
    return harness.application.workspace();
  }

  private WorkspaceBrowse browse() {
    return workspace().browse();
  }

  private static List<String> names(List<WorkspaceNode> nodes) {
    List<String> names = new ArrayList<String>();
    for (WorkspaceNode node : nodes) {
      names.add(node.name());
    }
    return names;
  }

  /** Waits until a directory lists something other than its loading row. */
  private List<WorkspaceNode> listed(final WorkspaceController workspace, final WorkspaceNode dir)
      throws InterruptedException {
    harness.await(
        new BooleanSupplier() {
          @Override
          public boolean getAsBoolean() {
            List<WorkspaceNode> rows = workspace.children(dir);
            return !rows.isEmpty() && rows.get(0).kind() != WorkspaceNode.Kind.LOADING;
          }
        },
        "listed " + dir.name());
    return workspace.children(dir);
  }

  /** Waits until the browser shows a folder and lists it, and returns its rows' names. */
  private List<String> shows(final Path folder) throws InterruptedException {
    harness.await(
        new BooleanSupplier() {
          @Override
          public boolean getAsBoolean() {
            return folder.toString().equals(browse().shownFolder());
          }
        },
        "shows " + folder);
    return names(listed(workspace(), workspace().rootNode()));
  }

  private static WorkspaceNode child(List<WorkspaceNode> rows, String name) {
    for (WorkspaceNode row : rows) {
      if (row.name().equals(name)) {
        return row;
      }
    }
    throw new AssertionError(name + " not in " + names(rows));
  }

  private String lastNotice() {
    return harness.view.notices.get(harness.view.notices.size() - 1);
  }

  /** Waits for the next notice and returns it. */
  private String notice(final int before) throws InterruptedException {
    harness.await(
        new BooleanSupplier() {
          @Override
          public boolean getAsBoolean() {
            return harness.view.notices.size() > before;
          }
        },
        "notice");
    return lastNotice();
  }

  @Test
  void browsingUpAndDownLeavesTheWorkspaceFolderAndItsIndex() throws Exception {
    FileIndex index = workspace().index();
    browse().go("src");
    assertEquals(List.of("deep", "Main.java"), shows(root.resolve("src")));
    assertTrue(browse().browsing());
    assertEquals(root, workspace().root());
    assertEquals(root.toString(), workspace().title());
    browse().go("deep");
    assertEquals(List.of("Leaf.java"), shows(root.resolve("src").resolve("deep")));
    browse().parent();
    shows(root.resolve("src"));
    browse().parent();
    assertEquals(List.of("src", "notes.txt", "Readme.MD"), shows(root));
    assertFalse(browse().browsing());
    browse().parent();
    Path top = temp.toRealPath();
    assertTrue(shows(top).containsAll(List.of("outside", "ws")));
    assertTrue(browse().browsing());
    assertSame(index, workspace().index());

    browse().back();
    shows(root);
    assertFalse(browse().browsing());
    browse().setHome(outside);
    browse().home();
    assertEquals(List.of("other.txt"), shows(outside));
    browse().go("~" + outside.getFileSystem().getSeparator() + "..");
    shows(top);
    assertSame(index, workspace().index());
  }

  @Test
  void aTypedFolderOutsideTheWorkspaceTakesFileOperationsAndReveal() throws Exception {
    browse().go(outside.toString());
    assertEquals(List.of("other.txt"), shows(outside));
    final int indexed = workspace().index().size();
    workspace().files().newFile(workspace().rootNode());
    assertNotNull(harness.view.fileNameAnswer);
    harness.view.fileNameAnswer.accept("made.txt");
    harness.await(
        new BooleanSupplier() {
          @Override
          public boolean getAsBoolean() {
            return harness.application.documents().count() == 1
                && names(workspace().children(workspace().rootNode())).contains("made.txt");
          }
        },
        "made.txt listed and opened");
    assertTrue(Files.isRegularFile(outside.resolve("made.txt")));
    assertEquals(indexed, workspace().index().size());
    harness.awaitIdle("open");

    harness.view.revealedChain = null;
    workspace().revealActive();
    harness.await(
        new BooleanSupplier() {
          @Override
          public boolean getAsBoolean() {
            return harness.view.revealedChain != null;
          }
        },
        "revealed");
    assertEquals(List.of("made.txt"), names(harness.view.revealedChain));

    harness.open(root.resolve("src").resolve("Main.java"));
    workspace().revealActive();
    assertEquals("Main.java is not below the shown folder", lastNotice());
  }

  @Test
  void aMissingPathAFileAndABadPathOrFilterShowANotice() throws Exception {
    int before = harness.view.notices.size();
    Path missing = temp.resolve("missing");
    browse().go(missing.toString());
    assertEquals(missing + " does not exist", notice(before));
    before = harness.view.notices.size();
    browse().go(root.resolve("notes.txt").toString());
    assertEquals(root.resolve("notes.txt") + " is not a folder", notice(before));
    browse().go("bad\0name");
    assertEquals("bad\0name is not a valid path", lastNotice());
    browse().setFilter("*.{java");
    assertEquals("Not a valid filter: *.{java", lastNotice());
    assertEquals("", browse().filter());
    harness.ui.runPending();
    assertEquals(root.toString(), browse().shownFolder());
    assertFalse(browse().browsing());
  }

  @Test
  void favoritesAreAddedListedAndRemovedInTheSettings() throws Exception {
    assertEquals(List.of(), browse().favorites());
    browse().go(outside.toString());
    shows(outside);
    browse().addFavorite();
    browse().addFavorite();
    browse().back();
    shows(root);
    browse().addFavorite();
    assertEquals(List.of(outside.toString(), root.toString()), browse().favorites());
    assertEquals(
        List.of(outside.toString(), root.toString()),
        harness.application.settings().current().state().favorites());
    browse().go(browse().favorites().get(0));
    shows(outside);
    browse().removeFavorite();
    assertEquals(List.of(root.toString()), browse().favorites());
  }

  @Test
  void theFilterAppliesToFilesAndHiddenEntriesShowWhenAsked() throws Exception {
    final WorkspaceNode src = child(workspace().children(workspace().rootNode()), "src");
    workspace().expand(src);
    listed(workspace(), src);
    browse().setFilter(" *.txt ");
    assertEquals("*.txt", browse().filter());
    awaitRows(workspace().rootNode(), List.of("src", "notes.txt"));
    awaitRows(src, List.of("deep"));
    browse().setHiddenShown(true);
    assertTrue(browse().hiddenShown());
    awaitRows(workspace().rootNode(), List.of("src", ".hidden.txt", "notes.txt"));
    browse().setHiddenShown(false);
    browse().setFilter("*.md");
    awaitRows(
        workspace().rootNode(),
        NameFilter.PLATFORM_IGNORES_CASE ? List.of("src", "Readme.MD") : List.of("src"));
    browse().setFilter("");
    awaitRows(src, List.of("deep", "Main.java"));
    assertEquals(ListingOptions.DEFAULTS, browse().options());
  }

  private void awaitRows(final WorkspaceNode dir, final List<String> rows)
      throws InterruptedException {
    harness.await(
        new BooleanSupplier() {
          @Override
          public boolean getAsBoolean() {
            return names(workspace().children(dir)).equals(rows);
          }
        },
        dir.name() + " lists " + rows);
  }

  @Test
  void rowsCarrySizeAndModifiedAndSortByEither() throws Exception {
    Files.setLastModifiedTime(root.resolve("notes.txt"), FileTime.fromMillis(1_000_000_000L));
    Files.setLastModifiedTime(root.resolve("Readme.MD"), FileTime.fromMillis(2_000_000_000L));
    workspace().refresh();
    final WorkspaceNode top = workspace().rootNode();
    harness.await(
        new BooleanSupplier() {
          @Override
          public boolean getAsBoolean() {
            return !workspace().indexing()
                && child(workspace().children(top), "notes.txt")
                    .modified()
                    .equals(modified(1_000_000_000L));
          }
        },
        "refreshed");
    WorkspaceNode notes = child(workspace().children(top), "notes.txt");
    assertEquals(BrowserRows.size(2_048, Locale.getDefault()), notes.size());
    assertEquals("", child(workspace().children(top), "src").size());
    assertEquals(
        modified(2_000_000_000L), child(workspace().children(top), "Readme.MD").modified());

    browse().sort(WorkspaceBrowseInput.Column.SIZE, true);
    assertEquals(WorkspaceBrowseInput.Column.SIZE, browse().sortColumn());
    assertTrue(browse().descending());
    awaitRows(top, List.of("src", "notes.txt", "Readme.MD"));
    browse().sort(WorkspaceBrowseInput.Column.MODIFIED, true);
    awaitRows(top, List.of("src", "Readme.MD", "notes.txt"));
    browse().sort(WorkspaceBrowseInput.Column.NAME, true);
    awaitRows(top, List.of("src", "Readme.MD", "notes.txt"));
    browse().sort(WorkspaceBrowseInput.Column.NAME, false);
    awaitRows(top, List.of("src", "notes.txt", "Readme.MD"));
  }

  private static String modified(long millis) {
    return BrowserRows.dates(Locale.getDefault(), ZoneId.systemDefault())
        .format(Instant.ofEpochMilli(millis));
  }

  @Test
  void sizesUseBinaryUnitsWithOneDecimal() {
    assertEquals("0 B", BrowserRows.size(0, Locale.ROOT));
    assertEquals("1023 B", BrowserRows.size(1_023, Locale.ROOT));
    assertEquals("1.0 KiB", BrowserRows.size(1_024, Locale.ROOT));
    assertEquals("1.5 KiB", BrowserRows.size(1_536, Locale.ROOT));
    assertEquals("5.0 MiB", BrowserRows.size(5L << 20, Locale.ROOT));
    assertEquals("3.0 GiB", BrowserRows.size(3L << 30, Locale.ROOT));
    assertEquals("5120.0 GiB", BrowserRows.size(5L << 40, Locale.ROOT));
    assertEquals("1,5 KiB", BrowserRows.size(1_536, Locale.GERMANY));
  }

  @Test
  void aNewFolderCancelsARunningListingAndDropsItsRows() throws Exception {
    final CountDownLatch release = new CountDownLatch(1);
    final AtomicBoolean first = new AtomicBoolean(true);
    final AtomicBoolean timedOut = new AtomicBoolean();
    workspace()
        .setBeforeList(
            new Runnable() {
              @Override
              public void run() {
                if (first.getAndSet(false)) {
                  try {
                    timedOut.set(!release.await(20, TimeUnit.SECONDS));
                  } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                  }
                }
              }
            });
    browse().go(outside.toString());
    harness.await(
        new BooleanSupplier() {
          @Override
          public boolean getAsBoolean() {
            return !first.get();
          }
        },
        "outside listing started");
    browse().go(root.resolve("src").toString());
    assertEquals(List.of("deep", "Main.java"), shows(root.resolve("src")));
    final int changes = harness.view.workspaceChanges.size();
    release.countDown();
    harness.await(
        new BooleanSupplier() {
          @Override
          public boolean getAsBoolean() {
            return !harness.workers.liveThreadNames().contains("qcaret-io-");
          }
        },
        "the stale listing ended");
    harness.ui.runPending();
    assertFalse(timedOut.get());
    assertEquals(changes, harness.view.workspaceChanges.size());
    assertEquals(List.of("deep", "Main.java"), names(workspace().children(workspace().rootNode())));
  }

  @Test
  void theShownFolderFilterAndHiddenStateRoundTripThroughRestore() throws Exception {
    final WorkspaceController restored =
        new WorkspaceController(
            harness.view,
            harness.workers,
            harness.ui,
            harness.application.documents(),
            new MemoryBudget(1L << 30, 60));
    WorkspaceBrowse browse =
        new WorkspaceBrowse(
            restored,
            harness.view,
            harness.workers,
            harness.ui,
            harness.application.settings(),
            root,
            false);
    restored.setBrowse(browse);
    browse.restore("*.txt", true);
    browse.restoreFolders(root.toString(), outside.toString(), true, List.of());
    harness.await(
        new BooleanSupplier() {
          @Override
          public boolean getAsBoolean() {
            return outside.equals(restored.shownFolder()) && root.equals(restored.root());
          }
        },
        "restored");
    assertEquals(List.of("other.txt"), names(listed(restored, restored.rootNode())));
    assertEquals(outside, restored.browsed());
    restored.dispose();
  }

  private static void rename(Windows windows, ApplicationController in, Path entry, String name) {
    in.workspace()
        .files()
        .rename(new WorkspaceNode(entry, entry.getFileName().toString(), WorkspaceNode.Kind.FILE));
    windows.view(in).fileNameAnswer.accept(name);
  }

  @Test
  void anotherWindowsBrowserFollowsARenameAndARefresh() throws Exception {
    Path settings = Files.createDirectory(temp.resolve("two"));
    final Windows windows = new Windows(settings, false);
    try {
      final ApplicationController first = windows.first();
      final ApplicationController second = windows.second();
      for (ApplicationController window : List.of(first, second)) {
        window.workspace().set(root);
        window.execute(CommandId.WORKSPACE_BROWSER);
      }
      windows.await(
          new BooleanSupplier() {
            @Override
            public boolean getAsBoolean() {
              return rows(second).contains("notes.txt") && rows(first).contains("notes.txt");
            }
          },
          "both list the workspace");
      rename(windows, first, root.resolve("notes.txt"), "renamed.txt");
      windows.await(
          new BooleanSupplier() {
            @Override
            public boolean getAsBoolean() {
              return rows(second).contains("renamed.txt") && !rows(second).contains("notes.txt");
            }
          },
          "the second window lists the rename");

      Files.writeString(root.resolve("added.txt"), "added\n");
      first.workspace().refresh();
      windows.await(
          new BooleanSupplier() {
            @Override
            public boolean getAsBoolean() {
              return rows(second).contains("added.txt");
            }
          },
          "the second window lists the refreshed file");
    } finally {
      windows.close();
    }
  }

  private static void renameFolder(
      Windows windows, ApplicationController in, Path entry, String name) {
    in.workspace()
        .files()
        .rename(
            new WorkspaceNode(entry, entry.getFileName().toString(), WorkspaceNode.Kind.DIRECTORY));
    windows.view(in).fileNameAnswer.accept(name);
  }

  private static BooleanSupplier rootIs(
      final List<ApplicationController> windows, final Path folder) {
    return new BooleanSupplier() {
      @Override
      public boolean getAsBoolean() {
        for (ApplicationController window : windows) {
          WorkspaceController workspace = window.workspace();
          if (!folder.equals(workspace.root())
              || workspace.index() == null
              || !folder.equals(workspace.index().root())) {
            return false;
          }
        }
        return true;
      }
    };
  }

  @Test
  void aRenameOfTheWorkspaceFolderOrAParentMovesTheWorkspaceInEveryWindow() throws Exception {
    Path top = temp.toRealPath();
    Path parent = Files.createDirectory(top.resolve("parent"));
    Path ws = Files.createDirectory(parent.resolve("ws"));
    Files.writeString(ws.resolve("a.txt"), "a\n");
    final Windows windows = new Windows(Files.createDirectory(temp.resolve("two")), false);
    try {
      final ApplicationController first = windows.first();
      final ApplicationController second = windows.second();
      List<ApplicationController> both = List.of(first, second);
      for (ApplicationController window : both) {
        window.workspace().set(ws);
        window.execute(CommandId.WORKSPACE_BROWSER);
      }
      windows.await(rootIs(both, ws), "both windows indexed the workspace");
      first.workspace().browse().go(parent.toString());
      windows.await(
          new BooleanSupplier() {
            @Override
            public boolean getAsBoolean() {
              return rows(first).contains("ws");
            }
          },
          "the first window shows the parent");
      renameFolder(windows, first, ws, "moved");
      final Path moved = parent.resolve("moved");
      windows.await(rootIs(both, moved), "both windows moved the workspace");
      windows.await(
          new BooleanSupplier() {
            @Override
            public boolean getAsBoolean() {
              return rows(second).contains("a.txt");
            }
          },
          "the second window lists the moved folder");
      assertTrue(windows.view(second).notices.contains(UiText.WORKSPACE_MOVED.format(moved)));
      assertEquals(parent.toString(), first.workspace().browse().shownFolder());

      first.workspace().browse().go(top.toString());
      windows.await(
          new BooleanSupplier() {
            @Override
            public boolean getAsBoolean() {
              return rows(first).contains("parent");
            }
          },
          "the first window shows the temp folder");
      renameFolder(windows, first, parent, "parent2");
      windows.await(
          rootIs(both, top.resolve("parent2").resolve("moved")), "a parent rename moved it");
    } finally {
      windows.close();
    }
  }

  @Test
  void aDeleteOfTheWorkspaceFolderClearsItWithANotice() throws Exception {
    Path top = temp.toRealPath();
    browse().go(top.toString());
    shows(top);
    workspace().files().delete(new WorkspaceNode(root, "ws", WorkspaceNode.Kind.DIRECTORY));
    harness.await(
        new BooleanSupplier() {
          @Override
          public boolean getAsBoolean() {
            return harness.view.deleteAccept != null;
          }
        },
        "asked");
    harness.view.deleteAccept.run();
    harness.await(
        new BooleanSupplier() {
          @Override
          public boolean getAsBoolean() {
            return workspace().root() == null && !Files.exists(root);
          }
        },
        "the workspace cleared");
    assertTrue(harness.view.notices.contains(UiText.WORKSPACE_DELETED.format(root)));
    assertNull(workspace().index());
    assertEquals(UiText.WORKSPACE_NONE.text(), workspace().title());
    assertEquals(top.toString(), browse().shownFolder());
  }

  private static List<String> rows(ApplicationController window) {
    WorkspaceNode top = window.workspace().rootNode();
    return top == null ? List.of() : names(window.workspace().children(top));
  }
}
