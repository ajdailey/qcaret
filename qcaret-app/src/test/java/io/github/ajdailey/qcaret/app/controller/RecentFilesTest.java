package io.github.ajdailey.qcaret.app.controller;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import io.github.ajdailey.qcaret.app.command.CommandId;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.function.BooleanSupplier;
import java.util.function.Predicate;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Assumptions;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/** File > Recent Files: newest first, one entry per real path, bounded, cleared, cleaned up. */
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

  /** Runs Clean Up and waits for its notice. */
  private String cleanUp() throws InterruptedException {
    int before = harness.view.notices.size();
    harness.application.execute(CommandId.CLEAN_UP_RECENT);
    return cleanUpNotice(before);
  }

  @Test
  void cleanUpDropsOnlyFilesThatNoLongerExist() throws Exception {
    Path kept = file("kept.txt");
    Path deleted = file("deleted.txt");
    Path folder = file("folder.txt");
    assertFalse(states.enabled(CommandId.CLEAN_UP_RECENT));
    harness.open(kept);
    harness.open(deleted);
    harness.open(folder);
    harness.application.documents().closeAll(null);
    harness.awaitIdle("close");
    Files.delete(deleted);
    Files.delete(folder);
    Files.createDirectory(folder);

    assertTrue(states.enabled(CommandId.CLEAN_UP_RECENT));
    assertEquals("Removed 2 files from Recent Files", cleanUp());
    assertEquals(List.of(kept), recent());
    assertEquals("Every file in Recent Files exists", cleanUp());
    assertEquals(List.of(kept), recent());

    Files.delete(kept);
    assertEquals("Removed 1 file from Recent Files", cleanUp());
    assertEquals(List.of(), recent());
    assertFalse(states.enabled(CommandId.CLEAN_UP_RECENT));
  }

  /** Skipped where the platform refuses links (Windows without the privilege). */
  @Test
  void aDanglingLinkIsGone() throws Exception {
    Path link = temp.resolve("dangling.txt");
    try {
      Files.createSymbolicLink(link, temp.resolve("absent.txt"));
    } catch (UnsupportedOperationException | IOException e) {
      Assumptions.abort("symbolic links unavailable: " + e);
    }
    assertTrue(RecentFiles.isGone(link));
    assertTrue(RecentFiles.isGone(temp.resolve("absent").resolve("a.txt")));
    assertFalse(RecentFiles.isGone(file("present.txt")));
  }

  /** An unmounted mount point is an empty folder, so a file listed under it reads as missing. */
  @Test
  void aFileUnderAnUnmountedMountPointIsGone() throws Exception {
    Path mountPoint = Files.createDirectory(temp.resolve("mnt"));
    assertTrue(RecentFiles.isGone(mountPoint.resolve("disk").resolve("a.txt")));
  }

  /**
   * A check that blocks, as on an unreachable share, holds a worker and not the UI thread; a second
   * Clean Up meanwhile starts nothing, and the files stay listed until the check ends.
   */
  @Test
  void aBlockedCheckLeavesTheUiThreadFree() throws Exception {
    Path a = file("a.txt");
    Path b = file("b.txt");
    harness.open(a);
    harness.open(b);
    final CountDownLatch entered = new CountDownLatch(1);
    final CountDownLatch release = new CountDownLatch(1);
    final List<String> threads = new CopyOnWriteArrayList<String>();
    harness
        .application
        .recentFiles()
        .setProbe(
            new Predicate<Path>() {
              @Override
              public boolean test(Path path) {
                threads.add(Thread.currentThread().getName());
                entered.countDown();
                try {
                  release.await(Harness.BOUND.toMillis(), TimeUnit.MILLISECONDS);
                } catch (InterruptedException e) {
                  Thread.currentThread().interrupt();
                }
                return path.getFileName().toString().equals("a.txt");
              }
            });
    int before = harness.view.notices.size();
    harness.application.execute(CommandId.CLEAN_UP_RECENT);
    assertTrue(entered.await(Harness.BOUND.toMillis(), TimeUnit.MILLISECONDS));
    harness.application.execute(CommandId.CLEAN_UP_RECENT);
    harness.ui.runPending();
    assertEquals(List.of(b, a), recent());
    assertEquals(before, harness.view.notices.size());
    release.countDown();
    assertEquals("Removed 1 file from Recent Files", cleanUpNotice(before));
    assertEquals(List.of(b), recent());
    assertEquals(2, threads.size());
    for (String name : threads) {
      assertTrue(name.startsWith("qcaret-io-"), name);
    }
  }

  /** Waits for the notice after the first {@code before}. */
  private String cleanUpNotice(final int before) throws InterruptedException {
    harness.await(
        new BooleanSupplier() {
          @Override
          public boolean getAsBoolean() {
            return harness.view.notices.size() > before;
          }
        },
        "Clean Up notice");
    return harness.view.notices.get(before);
  }
}
