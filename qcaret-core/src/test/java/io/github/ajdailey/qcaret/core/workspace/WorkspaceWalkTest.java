package io.github.ajdailey.qcaret.core.workspace;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assumptions.assumeTrue;

import io.github.ajdailey.qcaret.core.concurrent.CancellationToken;
import io.github.ajdailey.qcaret.core.concurrent.MemoryBudget;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CancellationException;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.function.Executable;
import org.junit.jupiter.api.io.TempDir;

/** {@link DirectoryListing} and {@link FileIndex} over a temp tree. */
class WorkspaceWalkTest {
  private static final WalkRules RULES = WalkRules.of(".git; .svn ;.hg");

  @TempDir Path temp;

  /**
   * Builds {@code b.txt}, {@code A.txt}, {@code src/Main.java}, {@code src/deep/Leaf.java}, {@code
   * .hidden/x.txt}, {@code .dot.txt} and {@code .git/config}.
   */
  private Path tree() throws IOException {
    Path root = Files.createDirectory(temp.resolve("root")).toRealPath();
    Files.writeString(root.resolve("b.txt"), "b");
    Files.writeString(root.resolve("A.txt"), "a");
    Files.createDirectories(root.resolve("src").resolve("deep"));
    Files.writeString(root.resolve("src").resolve("Main.java"), "m");
    Files.writeString(root.resolve("src").resolve("deep").resolve("Leaf.java"), "l");
    Files.createDirectories(root.resolve(".hidden"));
    Files.writeString(root.resolve(".hidden").resolve("x.txt"), "x");
    Files.writeString(root.resolve(".dot.txt"), "d");
    Files.createDirectories(root.resolve(".git"));
    Files.writeString(root.resolve(".git").resolve("config"), "c");
    return root;
  }

  private static List<String> names(DirectoryListing listing) {
    List<String> names = new ArrayList<String>();
    for (DirectoryListing.Entry entry : listing.entries()) {
      names.add(entry.name() + ":" + entry.kind());
    }
    return names;
  }

  private static List<String> paths(FileIndex index) {
    List<String> paths = new ArrayList<String>();
    for (int i = 0; i < index.size(); i++) {
      paths.add(index.path(i));
    }
    paths.sort(null);
    return paths;
  }

  private static boolean link(Path link, Path target) {
    try {
      Files.createSymbolicLink(link, target);
      return true;
    } catch (UnsupportedOperationException | IOException e) {
      return false;
    }
  }

  @Test
  void aListingPutsDirectoriesFirstAndSkipsHiddenAndExcludedEntries() throws IOException {
    Path root = tree();
    Files.createDirectory(root.resolve("zeta"));
    DirectoryListing listing = DirectoryListing.read(root, RULES, new CancellationToken());
    assertEquals(
        List.of("src:DIRECTORY", "zeta:DIRECTORY", "A.txt:FILE", "b.txt:FILE"), names(listing));
    assertFalse(listing.truncated());
  }

  @Test
  void aListingOfAMissingDirectoryThrows() {
    final Path missing = temp.resolve("missing");
    assertThrows(
        IOException.class,
        new Executable() {
          @Override
          public void execute() throws IOException {
            DirectoryListing.read(missing, RULES, new CancellationToken());
          }
        });
  }

  /** Skipped where the platform refuses links (Windows without the privilege). */
  @Test
  void aLinkToAnAncestorListsAsACycleAndALinkToAFileAsAFile() throws IOException {
    Path root = tree();
    assumeTrue(link(root.resolve("src").resolve("up"), root), "symbolic links unavailable");
    assumeTrue(link(root.resolve("src").resolve("main.link"), root.resolve("src/Main.java")));
    assumeTrue(link(root.resolve("src").resolve("other"), root.resolve("src/deep")));
    DirectoryListing listing =
        DirectoryListing.read(root.resolve("src"), RULES, new CancellationToken());
    assertEquals(
        List.of(
            "deep:DIRECTORY", "other:DIRECTORY", "up:CYCLE", "Main.java:FILE", "main.link:FILE"),
        names(listing));

    FileIndex index =
        FileIndex.build(root, RULES, FileIndex.MAX_FILES, new MemoryBudget(1L << 30, 60), token());
    assertEquals(
        List.of("A.txt", "b.txt", "src/Main.java", "src/deep/Leaf.java", "src/main.link"),
        paths(index));
  }

  private static CancellationToken token() {
    return new CancellationToken();
  }

  @Test
  void theIndexHoldsRelativePathsAndChargesTheBudget() throws IOException {
    Path root = tree();
    MemoryBudget budget = new MemoryBudget(1L << 30, 60);
    FileIndex index = FileIndex.build(root, RULES, FileIndex.MAX_FILES, budget, token());
    assertEquals(List.of("A.txt", "b.txt", "src/Main.java", "src/deep/Leaf.java"), paths(index));
    assertEquals(FileIndex.Cut.NONE, index.cut());
    long expected = 0;
    for (String path : paths(index)) {
      expected += FileIndex.ENTRY_BYTES + 2L * path.length();
    }
    assertEquals(expected, index.charged());
    assertEquals(expected, budget.reserved());
    for (int i = 0; i < index.size(); i++) {
      assertTrue(Files.isRegularFile(index.resolve(i)), index.path(i));
      assertTrue(index.resolve(i).startsWith(root));
    }
  }

  @Test
  void theIndexStopsAtItsLimitOrARefusedReservation() throws IOException {
    Path root = tree();
    MemoryBudget budget = new MemoryBudget(1L << 30, 60);
    FileIndex limited = FileIndex.build(root, RULES, 2, budget, token());
    assertEquals(2, limited.size());
    assertEquals(FileIndex.Cut.LIMIT, limited.cut());
    assertEquals(limited.charged(), budget.reserved());

    MemoryBudget small = new MemoryBudget(1000, 20);
    FileIndex refused = FileIndex.build(root, RULES, FileIndex.MAX_FILES, small, token());
    assertEquals(FileIndex.Cut.MEMORY, refused.cut());
    assertTrue(refused.size() < 4, "files " + refused.size());
    assertEquals(refused.charged(), small.reserved());
  }

  @Test
  void aCanceledBuildReturnsItsCharge() throws IOException {
    final Path root = tree();
    final MemoryBudget budget = new MemoryBudget(1L << 30, 60);
    final CancellationToken token = new CancellationToken();
    token.cancel();
    assertThrows(
        CancellationException.class,
        new Executable() {
          @Override
          public void execute() {
            FileIndex.build(root, RULES, FileIndex.MAX_FILES, budget, token);
          }
        });
    assertEquals(0, budget.reserved());
  }

  @Test
  void anUnreadableRootGivesAnEmptyIndex() {
    MemoryBudget budget = new MemoryBudget(1L << 30, 60);
    FileIndex index =
        FileIndex.build(temp.resolve("missing"), RULES, FileIndex.MAX_FILES, budget, token());
    assertEquals(0, index.size());
    assertEquals(0, budget.reserved());
  }
}
