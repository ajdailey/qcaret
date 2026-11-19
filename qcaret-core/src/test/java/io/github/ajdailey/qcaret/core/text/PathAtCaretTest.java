package io.github.ajdailey.qcaret.core.text;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import org.junit.jupiter.api.Test;

/** Open Path at Caret's path extraction through {@link PathAtCaret}; {@code |} marks the caret. */
class PathAtCaretTest {
  private static PathAtCaret.Found find(String marked) {
    int caret = marked.indexOf('|');
    return PathAtCaret.find(marked.substring(0, caret) + marked.substring(caret + 1), caret);
  }

  private static String path(String marked) {
    PathAtCaret.Found found = find(marked);
    return found == null ? null : found.path();
  }

  @Test
  void anUnquotedRunStopsAtSpacesAndPunctuation() {
    assertEquals("src/Main.java", path("see src/Ma|in.java for it"));
    assertEquals("src/Main.java", path("see |src/Main.java"));
    assertEquals("src/Main.java", path("see src/Main.java| now"));
    assertEquals("a.txt", path("(a.t|xt)"));
    assertEquals("a.txt", path("[a.txt|],"));
    assertEquals("b/c.xml", path("--file=b/c|.xml;"));
    assertEquals("x.md", path("<x.m|d>"));
    assertEquals("notes.txt", path("Read notes.txt|."));
    assertEquals("../up/x", path("../u|p/x"));
    assertNull(find("a  |  b"));
    assertNull(find("|"));
    assertNull(find("(|)"));
    assertNull(find(".|"));
  }

  @Test
  void quotesTakeEverythingBetweenThem() {
    assertEquals("My Documents/a b.txt", path("open \"My Docu|ments/a b.txt\" now"));
    assertEquals("x y", path("'x |y'"));
    assertEquals("z w", path("`z w|`"));
    assertEquals("second", path("\"first\" and \"sec|ond\""));
    assertEquals("and", path("\"first\" a|nd \"second\""));
  }

  @Test
  void aLineAndColumnSuffixSplitsOff() {
    PathAtCaret.Found line = find("at a.java:1|2 failed");
    assertEquals("a.java", line.path());
    assertEquals(12, line.line());
    assertEquals(0, line.column());
    PathAtCaret.Found both = find("a.ja|va:12:5:");
    assertEquals("a.java", both.path());
    assertEquals(12, both.line());
    assertEquals(5, both.column());
    PathAtCaret.Found quoted = find("\"a b.txt:3|\"");
    assertEquals("a b.txt", quoted.path());
    assertEquals(3, quoted.line());
    assertEquals("a.txt:x", path("a.txt|:x"));
    assertEquals("a.txt:99999999999", path("a.txt:|99999999999"));
  }

  @Test
  void windowsDrivesAndUncPathsStayWhole() {
    PathAtCaret.Found drive = find("C:\\Users\\mock\\a|.txt:7");
    assertEquals("C:\\Users\\mock\\a.txt", drive.path());
    assertEquals(7, drive.line());
    assertEquals("C:/w/b.txt", path("C:/w/b|.txt"));
    assertEquals("C:12", path("C:1|2"));
    assertEquals("\\\\server\\share\\c.log", path("x \\\\server\\sh|are\\c.log y"));
    assertEquals("C:\\Program Files\\x.ini", path("\"C:\\Program Files\\x|.ini\""));
  }

  @Test
  void aUrlIsMarkedAndAPathIsNot() {
    assertTrue(find("go to https://example.inv|alid/x").url());
    assertTrue(find("file:///tmp/|x").url());
    assertTrue(find("MAILTO:mock@exa|mple.invalid").url());
    assertFalse(find("C:/x|").url());
    assertFalse(find("a.txt:|3").url());
    assertFalse(find("x+y.txt|").url());
  }
}
