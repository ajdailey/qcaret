package io.github.ajdailey.qcaret.app.controller;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import io.github.ajdailey.qcaret.app.command.CommandId;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.function.BooleanSupplier;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Assumptions;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/** File > Recent Files: newest first, one entry per real path, bounded, cleared. */
class RecentFilesTest {
  @TempDir Path temp;

  private Harness harness;
  private CommandStates states;

  @BeforeEach
  void create() {
    harness = new Harness(temp);
    states = new CommandStates(harness.application);
  }

  @AfterEach
  void close() throws InterruptedException {
    harness.close();
  }

  private Path file(String name) throws IOException {
    return Files.writeString(temp.resolve(name), name + "\n").toRealPath();
  }

  private List<Path> recent() {
    return harness.application.recentFiles().paths();
  }

  @Test
  void filesAreListedNewestFirstOnceEachAndOpenAgain() throws Exception {
    Path a = file("a.txt");
    Path b = file("b.txt");
    assertFalse(states.enabled(CommandId.CLEAR_RECENT_FILES));
    harness.open(a);
    harness.open(b);
    assertEquals(List.of(b, a), recent());
    harness.application.documents().open(List.of(a));
    harness.awaitIdle("select a");
    assertEquals(List.of(a, b), recent());
    assertEquals(List.of(a.toString(), b.toString()), states.recentFiles());

    harness.application.documents().closeAll(null);
    harness.awaitIdle("close");
    harness.application.openRecent(1);
    harness.await(
        new BooleanSupplier() {
          @Override
          public boolean getAsBoolean() {
            return harness.application.documents().count() == 1;
          }
        },
        "reopened b");
    assertEquals(b, harness.application.documents().activePath());
    assertEquals(List.of(b, a), recent());

    assertTrue(states.enabled(CommandId.CLEAR_RECENT_FILES));
    harness.application.execute(CommandId.CLEAR_RECENT_FILES);
    assertEquals(List.of(), recent());
    assertFalse(states.enabled(CommandId.CLEAR_RECENT_FILES));
  }

  /** Skipped where the platform refuses links (Windows without the privilege). */
  @Test
  void aFileOpenedThroughALinkIsOneEntry() throws Exception {
    Path a = file("a.txt");
    Path link = temp.resolve("link.txt");
    try {
      Files.createSymbolicLink(link, a);
    } catch (UnsupportedOperationException | IOException e) {
      Assumptions.abort("symbolic links unavailable: " + e);
    }
    harness.open(link);
    harness.application.documents().closeAll(null);
    harness.awaitIdle("close");
    harness.open(a);
    assertEquals(List.of(a), recent());
  }

  @Test
  void theListKeepsItsLimit() throws Exception {
    for (int i = 0; i < RecentFiles.LIMIT + 3; i++) {
      harness.open(file("f" + i + ".txt"));
    }
    List<Path> paths = recent();
    assertEquals(RecentFiles.LIMIT, paths.size());
    assertEquals(file("f" + (RecentFiles.LIMIT + 2) + ".txt"), paths.get(0));
    assertEquals(file("f3.txt"), paths.get(RecentFiles.LIMIT - 1));
  }
}
