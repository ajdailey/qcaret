package io.github.ajdailey.qcaret.app.cli;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertSame;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/** {@link HeadingFiles}: {@code file.md#heading} on the command line. */
class HeadingFilesTest {
  @TempDir Path temp;

  private StartupRequest parse(String... args) throws CommandLineException {
    return HeadingFiles.resolve(new CommandLineParser(temp).parse(args).request());
  }

  @Test
  void theHeadingIsSplitOffAndKeepsTheOptions() throws Exception {
    Files.writeString(temp.resolve("guide.md"), "# Mock\n", StandardCharsets.UTF_8);
    StartupRequest request = parse("-r", "-e", "UTF-8", "guide.md#markdown%2Dpreview");
    assertEquals(
        List.of(new FileRequest(temp.resolve("guide.md"), 0, 0, true, "UTF-8", "markdown-preview")),
        request.files());
  }

  @Test
  void anExistingFileNamedWithTheHashWins() throws Exception {
    Files.writeString(temp.resolve("n.md"), "# Mock\n", StandardCharsets.UTF_8);
    Files.writeString(temp.resolve("n.md#1"), "x", StandardCharsets.UTF_8);
    StartupRequest request = new CommandLineParser(temp).parse(new String[] {"n.md#1"}).request();
    assertSame(request, HeadingFiles.resolve(request));
  }

  @Test
  void aMissingFileKeepsItsWholeName() throws Exception {
    assertEquals(
        List.of(new FileRequest(temp.resolve("new.md#part"))), parse("new.md#part").files());
  }
}
