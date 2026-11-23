package io.github.ajdailey.qcaret.core.markdown;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;

import java.nio.file.Path;
import org.junit.jupiter.api.Test;

/** {@link LinkTarget}: classification of link and image destinations. */
class LinkTargetTest {
  private final Path folder = Render.folder();

  @Test
  void aRelativePathResolvesAgainstTheFolderWithoutQueryOrFragment() {
    LinkTarget t = LinkTarget.of("docs/My%20Notes.md?plain=1#part", folder);
    assertEquals(LinkTarget.Kind.FILE, t.kind());
    assertEquals(folder.resolve("docs").resolve("My Notes.md"), t.path());
    assertEquals("docs/My%20Notes.md?plain=1#part", t.destination());
  }

  @Test
  void aFileTargetsFragmentIsItsHeading() {
    assertEquals("part", LinkTarget.of("docs/My%20Notes.md?plain=1#part", folder).anchor());
    assertEquals("set up", LinkTarget.of("n.md#set%20up", folder).anchor());
    assertNull(LinkTarget.of("n.md#", folder).anchor());
    assertNull(LinkTarget.of("n.md", folder).anchor());
    Path absolute = folder.resolve("u.md");
    LinkTarget t = LinkTarget.of(absolute.toUri() + "#caf%C3%A9", null);
    assertEquals(absolute, t.path());
    assertEquals("café", t.anchor());
  }

  @Test
  void parentStepsAreNormalized() {
    assertEquals(folder.getParent().resolve("x.png"), LinkTarget.of("../x.png", folder).path());
  }

  @Test
  void anAbsolutePathStandsAlone() {
    Path absolute = folder.resolve("abs.png");
    LinkTarget t = LinkTarget.of(absolute.toString(), null);
    assertEquals(LinkTarget.Kind.FILE, t.kind());
    assertEquals(absolute, t.path());
  }

  @Test
  void aFileUriIsALocalFile() {
    Path absolute = folder.resolve("u.png");
    LinkTarget t = LinkTarget.of(absolute.toUri().toString(), null);
    assertEquals(LinkTarget.Kind.FILE, t.kind());
    assertEquals(absolute, t.path());
    assertNull(LinkTarget.of("file:relative", folder).path());
  }

  @Test
  void anyOtherSchemeIsAUrl() {
    assertEquals(LinkTarget.Kind.URL, LinkTarget.of("http://example.com/a", folder).kind());
    assertEquals(LinkTarget.Kind.URL, LinkTarget.of("HTTPS://example.com", folder).kind());
    assertEquals(LinkTarget.Kind.URL, LinkTarget.of("mailto:mock@example.com", folder).kind());
    assertEquals(LinkTarget.Kind.URL, LinkTarget.of("//example.com/x.png", folder).kind());
    assertNull(LinkTarget.of("http://example.com/a", folder).path());
  }

  @Test
  void aRelativePathInAnUntitledDocumentHasNoPath() {
    LinkTarget t = LinkTarget.of("rel.png", null);
    assertEquals(LinkTarget.Kind.FILE, t.kind());
    assertNull(t.path());
  }

  @Test
  void aHashIsAnAnchor() {
    LinkTarget t = LinkTarget.of("#some-heading%21", folder);
    assertEquals(LinkTarget.Kind.ANCHOR, t.kind());
    assertEquals("some-heading!", t.anchor());
    assertEquals("", LinkTarget.of("", folder).anchor());
  }

  @Test
  void malformedEscapesStayAsWritten() {
    assertEquals("a%zz", LinkTarget.decode("a%zz"));
    assertEquals("a%", LinkTarget.decode("a%"));
    assertEquals("a%ff", LinkTarget.decode("a%ff"));
    assertEquals("a+b é😀", LinkTarget.decode("a+b%20%C3%A9😀"));
  }
}
