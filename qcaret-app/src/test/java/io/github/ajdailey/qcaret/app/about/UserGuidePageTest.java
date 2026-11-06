package io.github.ajdailey.qcaret.app.about;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.File;
import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.concurrent.TimeUnit;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/** The page the build converted from {@code docs/user-guide.md}, and that conversion's refusals. */
class UserGuidePageTest {
  private static final Pattern ID = Pattern.compile("<a id=\"([^\"]+)\"></a>");
  private static final Pattern TARGET = Pattern.compile("href=\"#([^\"]*)\"");
  private static final Pattern REFERENCE = Pattern.compile("(?:href|src)=\"([^\"]*)\"");

  @TempDir Path temp;

  private static String page() throws IOException {
    try (InputStream in = UserGuide.class.getResourceAsStream(UserGuide.RESOURCE)) {
      assertNotNull(in, UserGuide.RESOURCE);
      return new String(in.readAllBytes(), StandardCharsets.UTF_8);
    }
  }

  @Test
  void everyContentsLinkNamesAHeadingAnchor() throws IOException {
    String page = page();
    Set<String> ids = new HashSet<String>();
    Matcher id = ID.matcher(page);
    while (id.find()) {
      ids.add(id.group(1));
    }
    assertTrue(ids.contains("opening-files"), "heading anchors are rendered");
    Matcher target = TARGET.matcher(page);
    int links = 0;
    while (target.find()) {
      links++;
      assertTrue(ids.contains(target.group(1)), "#" + target.group(1));
    }
    assertTrue(links >= 19, "the contents list reaches every section: " + links);
  }

  @Test
  void thePageIsSelfContained() throws IOException {
    String page = page();
    assertTrue(page.contains("<title>QCaret User Guide</title>"));
    assertTrue(page.contains("<table>"), "tables are converted");
    assertFalse(page.contains("<script"));
    int images = 0;
    Matcher reference = REFERENCE.matcher(page);
    while (reference.find()) {
      String value = reference.group(1);
      if (value.startsWith("data:image/png;base64,")) {
        images++;
      } else {
        assertTrue(value.startsWith("#"), value);
      }
    }
    assertTrue(images > 0, "images are embedded");
  }

  @Test
  void aLinkToNoHeadingFailsTheConversion() throws Exception {
    assertNotEquals(0, render("# Mock\n\n[missing](#no-such-heading)\n"));
  }

  @Test
  void aLinkLeavingThePageFailsTheConversion() throws Exception {
    assertNotEquals(0, render("# Mock\n\n[out](https://example.invalid/)\n"));
  }

  @Test
  void aHeadingLinkConverts() throws Exception {
    assertEquals(0, render("# Mock\n\n[Here](#mock)\n"));
    assertTrue(Files.readString(temp.resolve("out.html")).contains("<a id=\"mock\"></a>"));
  }

  /** Runs the build's converter on a markdown text and returns its exit code. */
  private int render(String markdown) throws Exception {
    Path source = Files.writeString(temp.resolve("in.md"), markdown);
    Path tools = Path.of("target", "guide-tools");
    String classPath =
        tools.resolve("commonmark.jar")
            + File.pathSeparator
            + tools.resolve("commonmark-tables.jar");
    Path java = Path.of(System.getProperty("java.home"), "bin", "java");
    Process process =
        new ProcessBuilder(
                List.of(
                    java.toString(),
                    "-cp",
                    classPath,
                    Path.of("src", "build", "GuideRenderer.java").toString(),
                    source.toString(),
                    temp.resolve("out.html").toString()))
            .redirectErrorStream(true)
            .redirectOutput(temp.resolve("out.log").toFile())
            .start();
    assertTrue(process.waitFor(120, TimeUnit.SECONDS), "converter ended");
    return process.exitValue();
  }
}
