package io.github.ajdailey.qcaret.core.workspace;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import io.github.ajdailey.qcaret.core.concurrent.CancellationToken;
import io.github.ajdailey.qcaret.core.util.os.OsTraits;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.attribute.FileTime;
import java.util.ArrayList;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.function.Executable;
import org.junit.jupiter.api.io.TempDir;

/**
 * {@link NameFilter}, {@link HomeFolder} and {@link DirectoryListing} with {@link ListingOptions}.
 */
class ListingOptionsTest {
  private static final WalkRules RULES = WalkRules.of(".git");

  @TempDir Path temp;

  /**
   * Builds {@code src/}, {@code .git/}, {@code .config/}, {@code big.java} (3,000 bytes, oldest),
   * {@code Notes.MD} (10 bytes), {@code small.java} (1 byte, newest) and {@code .hidden.java}.
   */
  private Path tree() throws IOException {
    Path root = Files.createDirectory(temp.resolve("root"));
    Files.createDirectory(root.resolve("src"));
    Files.createDirectory(root.resolve(".git"));
    Files.createDirectory(root.resolve(".config"));
    write(root.resolve("big.java"), 3_000, 1_000_000L);
    write(root.resolve("Notes.MD"), 10, 2_000_000L);
    write(root.resolve("small.java"), 1, 3_000_000L);
    write(root.resolve(".hidden.java"), 2, 4_000_000L);
    Files.setLastModifiedTime(root.resolve("src"), FileTime.fromMillis(5_000_000L));
    Files.setLastModifiedTime(root.resolve(".config"), FileTime.fromMillis(500_000L));
    return root;
  }

  private static void write(Path file, int bytes, long modified) throws IOException {
    Files.write(file, new byte[bytes]);
    Files.setLastModifiedTime(file, FileTime.fromMillis(modified));
  }

  private static List<String> names(DirectoryListing listing) {
    List<String> names = new ArrayList<String>();
    for (DirectoryListing.Entry entry : listing.entries()) {
      names.add(entry.name());
    }
    return names;
  }

  private static DirectoryListing read(Path root, ListingOptions options) throws IOException {
    return DirectoryListing.read(root, RULES, options, new CancellationToken());
  }

  @Test
  void aFilterMatchesFileNamesWithGlobsAndCaseAsAsked() {
    NameFilter exact = NameFilter.parse(" *.java; *.md ;", false);
    assertEquals("*.java; *.md ;", exact.text());
    assertTrue(exact.matches("Main.java"));
    assertTrue(exact.matches("a.md"));
    assertFalse(exact.matches("Notes.MD"));
    assertFalse(exact.matches("Main.java.bak"));
    NameFilter folded = NameFilter.parse("*.java;*.md", true);
    assertTrue(folded.matches("Notes.MD"));
    assertTrue(folded.matches("MAIN.JAVA"));
    assertNotEquals(exact, NameFilter.parse(" *.java; *.md ;", true));
    assertEquals(exact, NameFilter.parse("*.java; *.md ;", false));

    NameFilter shapes = NameFilter.parse("?.txt;[a-c]x.*;[!0-9]y;*.{xml,xsd};a+b(1).$", false);
    assertTrue(shapes.matches("a.txt"));
    assertFalse(shapes.matches("ab.txt"));
    assertTrue(shapes.matches("bx.log"));
    assertFalse(shapes.matches("dx.log"));
    assertTrue(shapes.matches("zy"));
    assertFalse(shapes.matches("7y"));
    assertTrue(shapes.matches("pom.xsd"));
    assertFalse(shapes.matches("pom.xml5"));
    assertTrue(shapes.matches("a+b(1).$"));

    assertTrue(NameFilter.parse(" ; ", false).matches("anything"));
    assertEquals(NameFilter.ALL, NameFilter.parse("", true));
    for (final String bad : new String[] {"[ab", "*.{xml", "[]"}) {
      assertThrows(
          IllegalArgumentException.class,
          new Executable() {
            @Override
            public void execute() {
              NameFilter.parse(bad, false);
            }
          },
          bad);
    }
  }

  @Test
  void windowsAndMacosIgnoreCase() {
    assertTrue(OsTraits.of("Windows 11").fileNamesIgnoreCase());
    assertTrue(OsTraits.of("Mac OS X").fileNamesIgnoreCase());
    assertFalse(OsTraits.of("Linux").fileNamesIgnoreCase());
    assertFalse(OsTraits.of(null).fileNamesIgnoreCase());
    assertEquals(OsTraits.current().fileNamesIgnoreCase(), NameFilter.PLATFORM_IGNORES_CASE);
  }

  @Test
  void homeIsTheSavedHomeWhereSetElseUserHome() {
    Path userHome = temp.resolve("settings");
    Path home = temp.resolve("me");
    assertEquals(home, HomeFolder.choose(home.toString(), userHome.toString()));
    assertEquals(userHome, HomeFolder.choose(null, userHome.toString()));
    assertEquals(userHome, HomeFolder.choose(" ", userHome.toString()));
  }

  @Test
  void theFilterLeavesDirectoriesAndHiddenEntriesShowOnlyWhenAsked() throws IOException {
    Path root = tree();
    NameFilter java = NameFilter.parse("*.java", false);
    assertEquals(
        List.of("src", "big.java", "small.java"),
        names(read(root, new ListingOptions(java, false, ListingOptions.Order.NAME, false))));
    assertEquals(
        List.of(".config", "src", ".hidden.java", "big.java", "small.java"),
        names(read(root, new ListingOptions(java, true, ListingOptions.Order.NAME, false))));
    assertEquals(
        List.of("src", "big.java", "Notes.MD", "small.java"),
        names(read(root, ListingOptions.DEFAULTS)));
  }

  @Test
  void entriesCarrySizeAndTimeAndSortByEitherWithDirectoriesFirst() throws IOException {
    Path root = tree();
    DirectoryListing listing =
        read(root, new ListingOptions(NameFilter.ALL, true, ListingOptions.Order.SIZE, false));
    assertEquals(
        List.of(".config", "src", "small.java", ".hidden.java", "Notes.MD", "big.java"),
        names(listing));
    DirectoryListing.Entry big = listing.entries().get(5);
    assertEquals(3_000, big.size());
    assertEquals(1_000_000L, big.modified());
    assertEquals(
        List.of("src", ".config", "big.java", "Notes.MD", ".hidden.java", "small.java"),
        names(
            read(root, new ListingOptions(NameFilter.ALL, true, ListingOptions.Order.SIZE, true))));
    assertEquals(
        List.of(".config", "src", "big.java", "Notes.MD", "small.java", ".hidden.java"),
        names(
            read(
                root,
                new ListingOptions(NameFilter.ALL, true, ListingOptions.Order.MODIFIED, false))));
    assertEquals(
        List.of("src", ".config", ".hidden.java", "small.java", "Notes.MD", "big.java"),
        names(
            read(
                root,
                new ListingOptions(NameFilter.ALL, true, ListingOptions.Order.MODIFIED, true))));
    assertEquals(
        List.of("src", "small.java", "Notes.MD", "big.java"),
        names(
            read(
                root, new ListingOptions(NameFilter.ALL, false, ListingOptions.Order.NAME, true))));
  }
}
