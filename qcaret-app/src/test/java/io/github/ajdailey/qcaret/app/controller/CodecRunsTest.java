package io.github.ajdailey.qcaret.app.controller;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import io.github.ajdailey.qcaret.app.command.CommandId;
import io.github.ajdailey.qcaret.app.editor.DisplayProjection;
import io.github.ajdailey.qcaret.app.i18n.UiText;
import io.github.ajdailey.qcaret.app.view.ManualUiDispatcher;
import io.github.ajdailey.qcaret.core.concurrent.CancellationToken;
import io.github.ajdailey.qcaret.core.concurrent.Workers;
import io.github.ajdailey.qcaret.core.document.Document;
import io.github.ajdailey.qcaret.core.document.EditBatch;
import io.github.ajdailey.qcaret.core.document.Selection;
import io.github.ajdailey.qcaret.core.document.SelectionModel;
import io.github.ajdailey.qcaret.core.document.SelectionSet;
import io.github.ajdailey.qcaret.core.document.UndoLimits;
import io.github.ajdailey.qcaret.core.text.LineEnding;
import io.github.ajdailey.qcaret.core.text.TextEncoding;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicReference;
import java.util.function.BooleanSupplier;
import java.util.function.Consumer;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

/**
 * Edit &gt; Encode/Decode and the added line operations through the editor controller: a worker run
 * with the busy cursor, one undo step, a refused or stale result left with a notice, Esc and
 * read-only. Text marks a caret with {@code |} and a range with <code>{</code> and <code>}</code>.
 */
class CodecRunsTest {
  private static final Duration BOUND = Duration.ofSeconds(20);

  private ManualUiDispatcher ui;
  private FakeView view;
  private Workers workers;
  private final AtomicReference<Throwable> failure = new AtomicReference<Throwable>();
  private final List<String> notices = new ArrayList<String>();
  private Document document;
  private SelectionModel selections;
  private EditorController editor;
  private FakeView.FakeTab tab;

  @BeforeEach
  void setUp() {
    ui = new ManualUiDispatcher();
    view = new FakeView(ui);
    workers =
        new Workers(
            1,
            new Thread.UncaughtExceptionHandler() {
              @Override
              public void uncaughtException(Thread thread, Throwable error) {
                failure.set(error);
              }
            });
  }

  @AfterEach
  void tearDown() {
    if (editor != null) {
      editor.dispose();
    }
    workers.close();
    assertNull(failure.get());
  }

  private void open(String marked) {
    if (editor != null) {
      editor.dispose();
    }
    StringBuilder text = new StringBuilder();
    List<Selection> list = new ArrayList<Selection>();
    int anchor = -1;
    for (char c : marked.toCharArray()) {
      switch (c) {
        case '|' -> list.add(Selection.caret(text.length()));
        case '{' -> anchor = text.length();
        case '}' -> list.add(Selection.of(anchor, text.length()));
        default -> text.append(c);
      }
    }
    document =
        Document.of(
            text.toString(),
            TextEncoding.of(StandardCharsets.UTF_8, false),
            LineEnding.LF,
            UndoLimits.DEFAULTS);
    selections = document.newSelectionModel();
    DisplayProjection projection =
        new DisplayProjection(
            document, DisplayProjection.layout(document.text(), 0, new CancellationToken()));
    editor =
        new EditorController(
            projection,
            selections,
            view,
            workers,
            ui,
            new Runnable() {
              @Override
              public void run() {
                // No save runs.
              }
            });
    editor.setNoticeListener(
        new Consumer<String>() {
          @Override
          public void accept(String message) {
            notices.add(message);
          }
        });
    tab = (FakeView.FakeTab) view.addTab(projection, selections, editor, null, 0);
    editor.attach(tab);
    selections.set(SelectionSet.of(list, 0));
    ui.runPending();
  }

  private String marked() {
    String text = document.text().toString();
    SelectionSet set = selections.selections();
    StringBuilder out = new StringBuilder();
    for (int i = 0; i <= text.length(); i++) {
      for (Selection s : set.selections()) {
        if (s.isEmpty() && s.caret() == i) {
          out.append('|');
        } else if (!s.isEmpty() && s.start() == i) {
          out.append('{');
        } else if (!s.isEmpty() && s.end() == i) {
          out.append('}');
        }
      }
      if (i < text.length()) {
        out.append(text.charAt(i));
      }
    }
    return out.toString();
  }

  /** Runs a command and waits until its busy cursor goes idle. */
  private void run(CommandId command) throws InterruptedException {
    editor.execute(command);
    ui.runPending();
    assertTrue(
        ui.runUntil(
            new BooleanSupplier() {
              @Override
              public boolean getAsBoolean() {
                List<Boolean> busy = tab.shown.busy;
                return busy.isEmpty() || !busy.get(busy.size() - 1);
              }
            },
            BOUND),
        "the command ended");
    ui.runPending();
  }

  private CountDownLatch holdWorker() {
    final CountDownLatch gate = new CountDownLatch(1);
    workers.executeCpu(
        new Runnable() {
          @Override
          public void run() {
            try {
              gate.await();
            } catch (InterruptedException e) {
              Thread.currentThread().interrupt();
            }
          }
        });
    return gate;
  }

  private void drainWorker() throws InterruptedException {
    CountDownLatch done = new CountDownLatch(1);
    workers.executeCpu(done::countDown);
    assertTrue(done.await(BOUND.toSeconds(), TimeUnit.SECONDS), "worker drained");
  }

  @Test
  void everyRangeConvertsOnAWorkerAsOneUndoStep() throws InterruptedException {
    open("{ab} x| {cd}");
    run(CommandId.BASE64_ENCODE);
    assertEquals("{YWI=} x| {Y2Q=}", marked());
    assertEquals(List.of(true, false), tab.shown.busy);
    assertEquals(List.of(), notices);
    run(CommandId.BASE64_DECODE);
    assertEquals("{ab} x| {cd}", marked());
    editor.undo();
    editor.undo();
    assertEquals("ab x cd", document.text().toString());
  }

  @Test
  void oneRefusedRangeLeavesEveryRangeWithANoticeNamingIt() throws InterruptedException {
    open("{%41} {%4} {%42}");
    run(CommandId.URL_DECODE);
    assertEquals("{%41} {%4} {%42}", marked());
    assertEquals(List.of(UiText.CODEC_MALFORMED_ESCAPE.format(2)), notices);
    assertEquals("Selection 2 holds a malformed escape; nothing was converted", notices.get(0));
  }

  @Test
  void eachRefusalHasItsNotice() throws InterruptedException {
    open("{\\" + "uD83D}");
    run(CommandId.JSON_UNESCAPE);
    run(CommandId.BASE64_DECODE);
    open("{/w==}");
    run(CommandId.BASE64_DECODE);
    open("{\uD83D}");
    run(CommandId.URL_ENCODE);
    assertEquals(
        List.of(
            UiText.CODEC_LONE_SURROGATE_ESCAPE.format(1),
            UiText.CODEC_NOT_BASE64.format(1),
            UiText.CODEC_NOT_UTF8.format(1),
            UiText.CODEC_UNPAIRED_SURROGATE.format(1)),
        notices);
  }

  @Test
  void caretsAloneGiveANoticeAndNoWorker() throws InterruptedException {
    open("a|b c|");
    run(CommandId.XML_ESCAPE);
    assertEquals("a|b c|", marked());
    assertEquals(List.of(UiText.CODEC_NO_SELECTION.text()), notices);
    assertEquals(List.of(), tab.shown.busy);
  }

  @Test
  void aResultForAnOlderRevisionIsDroppedWithANotice() throws InterruptedException {
    open("{<a>}");
    CountDownLatch gate = holdWorker();
    editor.execute(CommandId.XML_ESCAPE);
    document.apply(EditBatch.replace(0, 0, "x"));
    gate.countDown();
    drainWorker();
    ui.runPending();
    assertEquals("x<a>", document.text().toString());
    assertEquals(List.of(UiText.CODEC_STALE.text()), notices);
    assertEquals(List.of(true, false), tab.shown.busy);
  }

  @Test
  void escCancelsARunningConversion() throws InterruptedException {
    open("{<a>}");
    CountDownLatch gate = holdWorker();
    editor.execute(CommandId.XML_ESCAPE);
    editor.cancel();
    gate.countDown();
    drainWorker();
    ui.runPending();
    assertEquals("<a>", document.text().toString());
    assertEquals(List.of(), notices);
    assertEquals(List.of(true, false), tab.shown.busy);
  }

  @Test
  void aReadOnlyDocumentConvertsNothing() throws InterruptedException {
    open("{<a>}");
    document.setReadOnly(true);
    run(CommandId.XML_ESCAPE);
    run(CommandId.REMOVE_BLANK_LINES);
    assertEquals("<a>", document.text().toString());
    assertEquals(List.of(), tab.shown.busy);
    assertEquals(List.of(), notices);
  }

  @Test
  void theLineOperationsRunAsLineTransforms() throws InterruptedException {
    open("  10\n\n\t2\n \n-1|");
    run(CommandId.REMOVE_BLANK_LINES);
    assertEquals("  10\n\t2\n-1|", marked());
    run(CommandId.SORT_LINES_NUMERIC);
    assertEquals("-1\n\t2\n  10|", marked());
    run(CommandId.REVERSE_LINES);
    assertEquals("  10\n\t2\n-1|", marked());
    document.setTabWidth(2);
    run(CommandId.INDENT_TO_TABS);
    assertEquals("\t10\n\t2\n-1|", marked());
    run(CommandId.INDENT_TO_SPACES);
    assertEquals("  10\n  2\n-1|", marked());
    run(CommandId.TRIM_LEADING_WHITESPACE);
    assertEquals("10\n2\n-1|", marked());
    for (int i = 0; i < 6; i++) {
      editor.undo();
    }
    assertEquals("  10\n\n\t2\n \n-1", document.text().toString());
    assertEquals(List.of(), notices);
  }
}
