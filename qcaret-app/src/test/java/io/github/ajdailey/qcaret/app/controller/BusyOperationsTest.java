package io.github.ajdailey.qcaret.app.controller;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import io.github.ajdailey.qcaret.app.command.CommandId;
import io.github.ajdailey.qcaret.core.document.Selection;
import io.github.ajdailey.qcaret.core.document.SelectionSet;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.function.BooleanSupplier;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/**
 * Operations moved onto {@link BusyWork} or {@link LaunchCursor}: each raises the window's busy
 * cursor once and leaves it idle when its result, failure or refusal is in. {@code BusyWorkTest}
 * holds a worker to see the cursor while one runs.
 */
class BusyOperationsTest {
  @TempDir Path temp;

  private Harness harness;

  @BeforeEach
  void create() {
    harness = new Harness(temp);
  }

  @AfterEach
  void close() throws InterruptedException {
    harness.close();
  }

  /** Runs a command, waits for it to end and returns the busy operations it raised. */
  private int raised(CommandId command, BooleanSupplier ended) throws InterruptedException {
    harness.awaitIdle("before " + command);
    int before = harness.view.activations.get();
    harness.application.execute(command);
    harness.await(ended, command + " ended");
    harness.awaitIdle(command.name());
    return harness.view.activations.get() - before;
  }

  @Test
  void aboutCollectsUnderTheBusyCursor() throws InterruptedException {
    int raised =
        raised(
            CommandId.ABOUT,
            new BooleanSupplier() {
              @Override
              public boolean getAsBoolean() {
                return harness.view.about != null;
              }
            });
    assertEquals(1, raised);
  }

  /** The folder check is one operation and the launch a second, timed one. */
  @Test
  void openSettingsFolderShowsTheCursorForTheCheckAndTheLaunch() throws InterruptedException {
    int raised =
        raised(
            CommandId.OPEN_SETTINGS_FOLDER,
            new BooleanSupplier() {
              @Override
              public boolean getAsBoolean() {
                return !harness.view.launched.isEmpty();
              }
            });
    assertEquals(2, raised);
  }

  @Test
  void reloadGrammarsReadsUnderTheBusyCursor() throws InterruptedException {
    int raised =
        raised(
            CommandId.RELOAD_GRAMMARS,
            new BooleanSupplier() {
              @Override
              public boolean getAsBoolean() {
                return true;
              }
            });
    assertEquals(1, raised);
  }

  @Test
  void aSelectionChecksumHashesUnderTheBusyCursor() throws Exception {
    Path file = Files.writeString(temp.resolve("a.txt"), "abc\n");
    harness.open(file);
    EditorController editor = harness.application.documents().activeEditor();
    editor.selections().set(SelectionSet.of(List.of(Selection.of(0, 3)), 0));
    int raised =
        raised(
            CommandId.MD5_SELECTION,
            new BooleanSupplier() {
              @Override
              public boolean getAsBoolean() {
                return harness.view.clipboard != null;
              }
            });
    assertEquals(1, raised);
  }

  /** A missing folder ends the check with a notice and the cursor idle. */
  @Test
  void aMissingWorkspaceFolderEndsIdle() throws InterruptedException {
    harness.awaitIdle("start");
    int before = harness.view.activations.get();
    final int notices = harness.view.notices.size();
    harness.application.workspace().browse().go(temp.resolve("absent").toString());
    harness.await(
        new BooleanSupplier() {
          @Override
          public boolean getAsBoolean() {
            return harness.view.notices.size() > notices;
          }
        },
        "refusal notice");
    harness.awaitIdle("browse");
    assertEquals(before + 1, harness.view.activations.get());
    assertNotNull(harness.view.notices.get(notices));
  }

  @Test
  void closedWorkersRefuseWithTheCursorIdle() throws InterruptedException {
    harness.awaitIdle("start");
    assertTrue(harness.workers.close(Harness.BOUND));
    int before = harness.view.activations.get();
    harness.application.execute(CommandId.ABOUT);
    harness.application.execute(CommandId.CLEAN_UP_RECENT);
    assertEquals(before + 2, harness.view.activations.get());
    assertEquals(0, harness.view.active.get());
  }
}
