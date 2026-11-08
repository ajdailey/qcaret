package io.github.ajdailey.qcaret.core.text;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assumptions.assumeTrue;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.FileSystemException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.attribute.PosixFilePermissions;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class EditorConfigTest {
  @TempDir Path dir;

  private static void write(Path file, String text) throws IOException {
    Files.createDirectories(file.getParent());
    Files.writeString(file, text, StandardCharsets.UTF_8);
  }

  private Path project() throws IOException {
    write(dir.resolve(".editorconfig"), "root = true\n[*]\nindent_style = tab\ntab_width = 8\n");
    return dir;
  }

  @Test
  void nearerFilesAndLaterSectionsWinAndUnsetRemoves() throws IOException {
    Path sub = project().resolve("a").resolve("b");
    write(
        dir.resolve("a").resolve(".editorconfig"),
        "[*.c]\nindent_style = space\nindent_size = 4\n[b/*.c]\nindent_size = 2\n");
    write(sub.resolve(".editorconfig"), "[*]\ntab_width = unset\ncharset = UTF-8\n");
    EditorConfig.Result r = EditorConfig.resolve(sub.resolve("x.c"));
    assertEquals(
        Map.of("indent_style", "space", "indent_size", "2", "tab_width", "2", "charset", "utf-8"),
        r.properties());
    assertEquals(
        List.of(
            sub.resolve(".editorconfig"),
            dir.resolve("a").resolve(".editorconfig"),
            dir.resolve(".editorconfig")),
        r.files());
    assertEquals(List.of(), r.problems());
  }

  @Test
  void theWalkStopsAtRootTrue() throws IOException {
    write(dir.resolve(".editorconfig"), "[*]\nend_of_line = crlf\n");
    write(dir.resolve("p").resolve(".editorconfig"), "root=TRUE\n[*]\nindent_size = 3\n");
    EditorConfig.Result r = EditorConfig.resolve(dir.resolve("p").resolve("x.txt"));
    assertEquals(Map.of("indent_size", "3", "tab_width", "3"), r.properties());
  }

  @Test
  void aMalformedOversizedOrNonUtf8FileContributesNothingNotEvenRoot() throws IOException {
    Path p = project().resolve("p");
    write(p.resolve(".editorconfig"), "root = true\n[*]\nthis line is not a property\n");
    EditorConfig.Result r = EditorConfig.resolve(p.resolve("x.txt"));
    assertEquals(
        Map.of("indent_style", "tab", "indent_size", "8", "tab_width", "8"), r.properties());
    assertEquals(1, r.problems().size());
    assertTrue(r.problems().get(0).contains("Line 3"), r.problems().get(0));

    Files.write(p.resolve(".editorconfig"), new byte[EditorConfig.MAX_BYTES + 1]);
    r = EditorConfig.resolve(p.resolve("x.txt"));
    assertEquals("8", r.properties().get("tab_width"));
    assertEquals(1, r.problems().size());

    Files.writeString(p.resolve(".editorconfig"), "\n".repeat(EditorConfig.MAX_LINES));
    assertEquals(1, EditorConfig.resolve(p.resolve("x.txt")).problems().size());

    Files.write(
        p.resolve(".editorconfig"), new byte[] {'[', '*', ']', '\n', 'a', '=', (byte) 0xff});
    assertEquals(1, EditorConfig.resolve(p.resolve("x.txt")).problems().size());
  }

  @Test
  void anUnreadableFileContributesNothing() throws IOException {
    Path p = project().resolve("p");
    Path config = p.resolve(".editorconfig");
    write(config, "[*]\nindent_size = 3\n");
    try {
      Files.setPosixFilePermissions(config, PosixFilePermissions.fromString("---------"));
    } catch (UnsupportedOperationException e) {
      assumeTrue(false, "no POSIX permissions");
    }
    try {
      assumeTrue(!Files.isReadable(config), "running as a user who reads every file");
      EditorConfig.Result r = EditorConfig.resolve(p.resolve("x.txt"));
      assertEquals("8", r.properties().get("tab_width"));
      assertEquals(1, r.problems().size());
    } finally {
      Files.setPosixFilePermissions(config, PosixFilePermissions.fromString("rw-------"));
    }
  }

  @Test
  void aDirectoryNamedEditorConfigIsSkippedAndALinkIsFollowed() throws IOException {
    Path p = project().resolve("p");
    Files.createDirectories(p.resolve(".editorconfig"));
    EditorConfig.Result r = EditorConfig.resolve(p.resolve("x.txt"));
    assertEquals("8", r.properties().get("tab_width"));
    assertEquals(List.of(), r.problems());

    Path q = dir.resolve("q");
    Path target = dir.resolve("shared.conf");
    write(target, "[*]\nindent_size = 5\n");
    Files.createDirectories(q);
    try {
      Files.createSymbolicLink(q.resolve(".editorconfig"), target);
    } catch (UnsupportedOperationException | FileSystemException e) {
      assumeTrue(false, "no symbolic links");
    }
    assertEquals("5", EditorConfig.resolve(q.resolve("x.txt")).properties().get("indent_size"));
  }

  @Test
  void aSectionAnchoredByASlashMatchesBelowItsOwnDirectoryOnly() throws IOException {
    write(dir.resolve(".editorconfig"), "root = true\n[/src/*.java]\nindent_size = 2\n");
    assertEquals(
        "2",
        EditorConfig.resolve(dir.resolve("src").resolve("A.java")).properties().get("indent_size"));
    assertEquals(
        Map.of(),
        EditorConfig.resolve(dir.resolve("x").resolve("src").resolve("A.java")).properties());
  }
}
