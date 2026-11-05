package io.github.ajdailey.qcaret.app.controller;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import io.github.ajdailey.qcaret.app.editor.TokenCache;
import io.github.ajdailey.qcaret.app.theme.TokenRole;
import io.github.ajdailey.qcaret.app.view.ManualUiDispatcher;
import io.github.ajdailey.qcaret.core.buffer.TextSnapshot;
import io.github.ajdailey.qcaret.core.concurrent.CancellationToken;
import io.github.ajdailey.qcaret.core.concurrent.Workers;
import io.github.ajdailey.qcaret.core.document.Document;
import io.github.ajdailey.qcaret.core.document.EditBatch;
import io.github.ajdailey.qcaret.core.document.UndoLimits;
import io.github.ajdailey.qcaret.core.syntax.GrammarReader;
import io.github.ajdailey.qcaret.core.syntax.GrammarSet;
import io.github.ajdailey.qcaret.core.syntax.Syntax;
import io.github.ajdailey.qcaret.core.syntax.SyntaxEngine;
import io.github.ajdailey.qcaret.core.testkit.Poll;
import io.github.ajdailey.qcaret.core.text.LineEnding;
import io.github.ajdailey.qcaret.core.text.TextEncoding;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.function.BooleanSupplier;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

/**
 * The highlighter on real workers: tokens arrive for the views' lines, edits shift them at once, a
 * result for an older revision does not overwrite newer text, and the fold regions follow.
 */
class SyntaxHighlighterTest {
  private static final GrammarSet GRAMMARS =
      GrammarSet.of(
          GrammarSet.readBuiltIns(new GrammarReader()), Map.of(), new ArrayList<String>());
  private static final Duration BOUND = Duration.ofSeconds(20);
  private static final Object VIEW = new Object();

  private final ManualUiDispatcher ui = new ManualUiDispatcher();
  private final List<Throwable> failures = new CopyOnWriteArrayList<Throwable>();
  private final AtomicInteger repaints = new AtomicInteger();
  private Workers workers;
  private Document document;
  private SyntaxHighlighter highlighter;

  @BeforeEach
  void create() {
    workers =
        new Workers(
            2,
            new Thread.UncaughtExceptionHandler() {
              @Override
              public void uncaughtException(Thread thread, Throwable error) {
                failures.add(error);
              }
            });
    StringBuilder text = new StringBuilder();
    for (int i = 0; i < 3000; i++) {
      text.append("class Mock").append(i).append(" {\n  int n = ").append(i).append(";\n}\n");
    }
    document =
        Document.of(
            text,
            TextEncoding.of(StandardCharsets.UTF_8, false),
            LineEnding.LF,
            UndoLimits.DEFAULTS);
    highlighter =
        new SyntaxHighlighter(
            document,
            workers,
            ui,
            new Runnable() {
              @Override
              public void run() {
                ui.checkUiThread();
                repaints.incrementAndGet();
              }
            },
            SyntaxEngine.DEFAULT_MAX_LINE_CHARS);
  }

  @AfterEach
  void close() {
    highlighter.dispose();
    workers.close();
    assertTrue(failures.isEmpty(), failures.toString());
  }

  private void await(BooleanSupplier condition, String what) throws InterruptedException {
    assertTrue(ui.runUntil(condition, BOUND), what);
  }

  /** Waits for the worker, then runs the outputs it posted, until neither has more to do. */
  private void awaitIdle() throws InterruptedException {
    BooleanSupplier idle =
        new BooleanSupplier() {
          @Override
          public boolean getAsBoolean() {
            return !highlighter.busy();
          }
        };
    do {
      assertTrue(Poll.until(idle, BOUND), "highlighter idle");
    } while (ui.runPending() > 0 || highlighter.busy());
  }

  /** Returns a line's tokens from a new engine over the current text. */
  private int[] expected(int line) {
    SyntaxEngine fresh = new SyntaxEngine(GRAMMARS.syntax("java"), document.text(), 10_000);
    long far = System.nanoTime() + Duration.ofHours(1).toNanos();
    while (!fresh.idle()) {
      fresh.run(far, new CancellationToken());
    }
    fresh.request(line, line);
    return TokenCache.toRoles(fresh.run(far, new CancellationToken()).tokens()[0]);
  }

  private void show(int fromLine, int toLine) {
    TextSnapshot text = document.text();
    highlighter.viewportChanged(VIEW, text.offsetOfLine(fromLine), text.offsetOfLine(toLine));
  }

  @Test
  void theViewsLinesArriveAndTheRegionsFollowTheWholePass() throws InterruptedException {
    highlighter.setSyntax(GRAMMARS.syntax("java"));
    show(0, 40);
    await(
        new BooleanSupplier() {
          @Override
          public boolean getAsBoolean() {
            return highlighter.tokens(40) != null;
          }
        },
        "view tokens");
    assertEquals(TokenRole.KEYWORD.ordinal(), highlighter.tokens(0)[2]);
    int shown = repaints.get();
    assertTrue(shown > 0);
    awaitIdle();
    assertTrue(repaints.get() <= shown + 1, "the pass below the view repainted it");
    final long revision = document.revision();
    assertEquals(3000, highlighter.regions(document.text(), revision).size());
    assertNull(highlighter.regions(document.text(), revision + 1));
  }

  @Test
  void anEditShiftsTheTokensAtOnceAndTheResultMatchesANewPass() throws InterruptedException {
    highlighter.setSyntax(GRAMMARS.syntax("java"));
    show(0, 40);
    awaitIdle();
    int[] before = highlighter.tokens(1);
    assertNotNull(before);
    document.apply(EditBatch.replace(document.text().offsetOfLine(1) + 2, 0, "final "));
    assertNotNull(highlighter.tokens(1), "the shifted tokens paint at once");
    document.apply(EditBatch.replace(0, 0, "/*\n"));
    document.apply(EditBatch.replace(document.text().offsetOfLine(5), 0, "*/"));
    show(0, 40);
    awaitIdle();
    for (int line = 0; line <= 40; line++) {
      int[] tokens = highlighter.tokens(line);
      if (tokens != null) {
        assertArrayEquals(expected(line), tokens, "line " + line);
      }
    }
    assertArrayEquals(expected(2), highlighter.tokens(2));
  }

  @Test
  void anOutputForAnOlderRevisionIsNotApplied() throws InterruptedException {
    highlighter.setSyntax(GRAMMARS.syntax("java"));
    show(0, 40);
    awaitIdle();
    document.apply(EditBatch.replace(0, 0, "/*"));
    // The worker's outputs for this revision wait on the UI queue while a newer edit lands.
    assertTrue(
        Poll.until(
            new BooleanSupplier() {
              @Override
              public boolean getAsBoolean() {
                return !highlighter.busy();
              }
            },
            BOUND),
        "outputs posted");
    document.apply(EditBatch.replace(0, 2, ""));
    ui.runPending();
    awaitIdle();
    assertArrayEquals(expected(1), highlighter.tokens(1));
    assertArrayEquals(expected(0), highlighter.tokens(0));
  }

  @Test
  void aChangedLineLimitHighlightsTheDocumentAgain() throws InterruptedException {
    highlighter.setSyntax(GRAMMARS.syntax("java"));
    show(0, 10);
    awaitIdle();
    assertEquals(TokenRole.KEYWORD.ordinal(), highlighter.tokens(0)[2]);
    highlighter.setMaxLineChars(1);
    show(0, 10);
    awaitIdle();
    int[] plain = highlighter.tokens(0);
    assertTrue(plain == null || plain.length == 0);
    highlighter.setMaxLineChars(10_000);
    show(0, 10);
    awaitIdle();
    assertEquals(TokenRole.KEYWORD.ordinal(), highlighter.tokens(0)[2]);
  }

  @Test
  void plainTextHasNoTokensAndDisposeStopsTheWork() throws InterruptedException {
    highlighter.setSyntax(null);
    show(0, 10);
    awaitIdle();
    assertEquals(null, highlighter.tokens(0));
    Syntax java = GRAMMARS.syntax("java");
    highlighter.setSyntax(java);
    highlighter.dispose();
    awaitIdle();
    assertFalse(highlighter.busy());
    assertEquals(null, highlighter.tokens(0));
  }
}
