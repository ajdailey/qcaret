package io.github.ajdailey.qcaret.core.markdown;

import io.github.ajdailey.qcaret.core.concurrent.CancellationToken;
import java.nio.file.Path;

/** A renderer and line helpers for the markdown tests. */
final class Render {
  static final MarkdownRenderer RENDERER = new MarkdownRenderer();

  private Render() {}

  static RenderedMarkdown of(String text) {
    return RENDERER.render(text, null, new CancellationToken());
  }

  static RenderedMarkdown of(String text, Path folder) {
    return RENDERER.render(text, folder, new CancellationToken());
  }

  /** An absolute folder; classification reads no file, so it need not exist. */
  static Path folder() {
    return Path.of(System.getProperty("java.io.tmpdir")).toAbsolutePath().resolve("mock-docs");
  }

  /** The text of a rendered line, without its break. */
  static String line(RenderedMarkdown r, int line) {
    int start = r.lineStart(line);
    int end = line + 1 < r.lineCount() ? r.lineStart(line + 1) - 1 : r.text().length();
    return r.text().substring(start, end);
  }

  /** The text a run covers. */
  static String covered(RenderedMarkdown r, StyleRun run) {
    return r.text().substring(run.start(), run.end());
  }

  /** The first run of a style, or null. */
  static StyleRun first(RenderedMarkdown r, InlineStyle style) {
    for (StyleRun run : r.runs()) {
      if (run.style() == style) {
        return run;
      }
    }
    return null;
  }
}
