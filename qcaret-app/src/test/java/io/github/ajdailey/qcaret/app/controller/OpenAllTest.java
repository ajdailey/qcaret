package io.github.ajdailey.qcaret.app.controller;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import io.github.ajdailey.qcaret.app.command.CommandId;
import io.github.ajdailey.qcaret.app.i18n.UiText;
import io.github.ajdailey.qcaret.core.io.SafePaths;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.function.BooleanSupplier;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/**
 * Open All in File > Recent Files and File > Recently Closed, over an open, a missing and a closed
 * file: the open one keeps its one tab, the missing one is named in a notice and gets no tab. A
 * Recently Closed file whose load fails stays listed.
 */
class OpenAllTest {
  @TempDir Path temp;

  private Harness harness;
  private CommandStates states;
  private Path open;
  private Path missing;
  private Path closed;

  @BeforeEach
  void create() throws Exception {
    harness = new Harness(temp);
    states = new CommandStates(harness.application);
    Path dir = Files.createDirectory(temp.resolve("work")).toRealPath();
    open = Files.writeString(dir.resolve("open.txt"), "open\n");
    missing = Files.writeString(dir.resolve("missing.txt"), "missing\n");
    closed = Files.writeString(dir.resolve("closed.txt"), "closed\n");
    assertFalse(states.enabled(CommandId.OPEN_ALL_RECENT));
    assertFalse(states.enabled(CommandId.OPEN_ALL_CLOSED));
    harness.open(open);
    harness.open(missing);
    harness.open(closed);
    documents().select(2);
    documents().closeActive();
    harness.awaitIdle("close closed.txt");
    documents().select(1);
    documents().closeActive();
    harness.awaitIdle("close missing.txt");
    Files.delete(missing);
    assertEquals(1, documents().count());
  }

  @AfterEach
  void close() throws InterruptedException {
    harness.close();
  }

  private DocumentController documents() {
    return harness.application.documents();
  }

  private void openAll(CommandId command) throws InterruptedException {
    final int notices = harness.view.notices.size();
    assertTrue(states.enabled(command));
    harness.application.execute(command);
    harness.await(
        new BooleanSupplier() {
          @Override
          public boolean getAsBoolean() {
            return harness.view.notices.size() > notices && documents().count() == 2;
          }
        },
        "Open All");
    harness.awaitIdle("Open All");
    assertEquals(2, documents().count());
    assertTrue(documents().documentAt(closed) != null);
    assertNull(documents().documentAt(missing));
    assertEquals(
        UiText.OPEN_ALL_SKIPPED.format("missing.txt"),
        harness.view.notices.get(harness.view.notices.size() - 1));
  }

  @Test
  void recentFilesOpenAllSkipsTheMissingFile() throws Exception {
    assertEquals(List.of(closed, missing, open), harness.application.recentFiles().paths());
    openAll(CommandId.OPEN_ALL_RECENT);
    assertTrue(harness.application.recentFiles().paths().contains(missing));
  }

  @Test
  void recentlyClosedOpenAllKeepsOnlyTheSkippedFile() throws Exception {
    assertEquals(List.of(missing.toString(), closed.toString()), documents().closedFiles());
    openAll(CommandId.OPEN_ALL_CLOSED);
    assertEquals(List.of(missing.toString()), documents().closedFiles());
  }

  /** The check passes a held lock file, a readable regular file, and its load then refuses it. */
  @Test
  void recentlyClosedOpenAllKeepsAFileWhoseLoadFails() throws Exception {
    final int infos = harness.view.infos.size();
    SafePaths.holdLock(closed, "held by this process");
    try {
      harness.application.execute(CommandId.OPEN_ALL_CLOSED);
      harness.await(
          new BooleanSupplier() {
            @Override
            public boolean getAsBoolean() {
              return harness.view.infos.size() > infos;
            }
          },
          "the failed load");
      harness.awaitIdle("Open All");
    } finally {
      SafePaths.releaseLock(closed);
    }
    assertEquals(1, documents().count());
    assertEquals(List.of(missing.toString(), closed.toString()), documents().closedFiles());
  }
}
