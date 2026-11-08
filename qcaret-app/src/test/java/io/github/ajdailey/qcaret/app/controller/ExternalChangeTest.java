package io.github.ajdailey.qcaret.app.controller;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import io.github.ajdailey.qcaret.app.i18n.UiText;
import io.github.ajdailey.qcaret.app.view.ChangeChoice;
import io.github.ajdailey.qcaret.app.view.Comparison;
import io.github.ajdailey.qcaret.app.view.ManualUiDispatcher;
import io.github.ajdailey.qcaret.core.buffer.TextSnapshot;
import io.github.ajdailey.qcaret.core.concurrent.MemoryBudget;
import io.github.ajdailey.qcaret.core.concurrent.Workers;
import io.github.ajdailey.qcaret.core.document.Document;
import io.github.ajdailey.qcaret.core.document.DocumentFiles;
import io.github.ajdailey.qcaret.core.document.LoadCost;
import io.github.ajdailey.qcaret.core.document.LoadOptions;
import io.github.ajdailey.qcaret.core.io.FileBaseline;
import io.github.ajdailey.qcaret.core.testkit.Poll;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.nio.charset.Charset;
import java.nio.charset.CharsetDecoder;
import java.nio.charset.CharsetEncoder;
import java.nio.charset.StandardCharsets;
import java.nio.file.AccessDeniedException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.nio.file.attribute.FileTime;
import java.time.Duration;
import java.util.Collections;
import java.util.List;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.Semaphore;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicReference;
import java.util.function.BooleanSupplier;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Assumptions;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/**
 * External change detection against a fake view: reload of a clean document, the dirty-buffer
 * prompt, missing files, the save conflict and the comparisons. Each check runs on window
 * activation ({@link DocumentController#checkFiles}); the waits poll the UI queue with a bound and
 * never assert a state the checked side effect has not produced yet.
 */
class ExternalChangeTest {
  private static final Duration BOUND = Duration.ofSeconds(20);
  private static final Charset WINDOWS_31J = Charset.forName("windows-31j");

  private static final byte[] DECLARED =
      "<?xml version=\"1.0\" encoding=\"windows-31j\"?>\n".getBytes(StandardCharsets.US_ASCII);

  /** NEC row 13 duplicate: decodes to U+7E8A, which encodes to {@link #ENCODED_FORM}. */
  private static final byte[] NO_ROUND_TRIP = {(byte) 0xed, 0x40, '\n'};

  private static final byte[] ENCODED_FORM = {(byte) 0xfa, 0x5c, '\n'};

  /** ISO-8859-1 whose next decoder request, once armed, blocks until released. */
  private static final class Gate extends Charset {
    final AtomicBoolean armed = new AtomicBoolean();
    final CountDownLatch reached = new CountDownLatch(1);
    final CountDownLatch released = new CountDownLatch(1);

    Gate() {
      super("X-QCARET-GATE", null);
    }

    @Override
    public boolean contains(Charset other) {
      return StandardCharsets.ISO_8859_1.contains(other);
    }

    @Override
    public CharsetDecoder newDecoder() {
      if (armed.compareAndSet(true, false)) {
        reached.countDown();
        try {
          released.await(BOUND.toSeconds(), TimeUnit.SECONDS);
        } catch (InterruptedException e) {
          Thread.currentThread().interrupt();
        }
      }
      return StandardCharsets.ISO_8859_1.newDecoder();
    }

    @Override
    public CharsetEncoder newEncoder() {
      return StandardCharsets.ISO_8859_1.newEncoder();
    }
  }

  /** ISO-8859-1 whose decoder requests, while {@link #on}, each wait for one pass. */
  private static final class Turnstile extends Charset {
    final AtomicBoolean on = new AtomicBoolean();
    final Semaphore arrived = new Semaphore(0);
    final Semaphore passes = new Semaphore(0);

    Turnstile() {
      super("X-QCARET-TURNSTILE", null);
    }

    @Override
    public boolean contains(Charset other) {
      return StandardCharsets.ISO_8859_1.contains(other);
    }

    @Override
    public CharsetDecoder newDecoder() {
      if (on.get()) {
        arrived.release();
        try {
          passes.tryAcquire(BOUND.toSeconds(), TimeUnit.SECONDS);
        } catch (InterruptedException e) {
          Thread.currentThread().interrupt();
        }
      }
      return StandardCharsets.ISO_8859_1.newDecoder();
    }

    @Override
    public CharsetEncoder newEncoder() {
      return StandardCharsets.ISO_8859_1.newEncoder();
    }
  }

  @TempDir Path temp;

  private final ManualUiDispatcher ui = new ManualUiDispatcher();
  private final FakeView view = new FakeView(ui);
  private final AtomicReference<Throwable> failure = new AtomicReference<Throwable>();
  private final MemoryBudget budget = new MemoryBudget(1L << 30, 50);
  private Workers workers;
  private DocumentController documents;
  private CompareController compare;

  @BeforeEach
  void setUp() {
    workers =
        new Workers(
            2,
            new Thread.UncaughtExceptionHandler() {
              @Override
              public void uncaughtException(Thread thread, Throwable error) {
                failure.set(error);
              }
            });
    documents = new DocumentController(workers, ui, view, budget, LoadOptions.DEFAULTS);
    compare = new CompareController(documents, workers, ui, view, budget);
    documents.setCompare(compare);
  }

  @AfterEach
  void tearDown() throws Exception {
    compare.dispose();
    documents.dispose();
    assertTrue(
        Poll.until(
            new BooleanSupplier() {
              @Override
              public boolean getAsBoolean() {
                return !documents.watching();
              }
            },
            BOUND));
    workers.close();
    assertNull(failure.get());
    assertEquals(0, budget.reserved());
  }

  private void await(BooleanSupplier condition, String what) throws Exception {
    assertTrue(ui.runUntil(condition, BOUND), what);
  }

  /** Waits until no load, save, reload or comparison is active and runs what they posted. */
  private void idle() throws Exception {
    await(
        new BooleanSupplier() {
          @Override
          public boolean getAsBoolean() {
            return view.active.get() == 0 && documents.loading() == 0;
          }
        },
        "idle");
    ui.runPending();
  }

  private Path write(String name, String text) throws IOException {
    return Files.writeString(temp.resolve(name), text, StandardCharsets.UTF_8);
  }

  /** Writes a file in one step, so a monitor pass cannot count it as two changes. */
  private void replace(Path file, byte[] bytes) throws IOException {
    Path next = Files.write(temp.resolve("next.tmp"), bytes);
    long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(5);
    while (true) {
      try {
        Files.move(next, file, StandardCopyOption.ATOMIC_MOVE, StandardCopyOption.REPLACE_EXISTING);
        return;
      } catch (AccessDeniedException e) {
        // Windows refuses the move while a monitor pass has the file open.
        if (System.nanoTime() > deadline) {
          throw e;
        }
        Thread.onSpinWait();
      }
    }
  }

  private static byte[] concat(byte[] first, byte[] second) throws IOException {
    ByteArrayOutputStream out = new ByteArrayOutputStream();
    out.write(first);
    out.write(second);
    return out.toByteArray();
  }

  private static byte[] utf16(String text) throws IOException {
    return concat(new byte[] {(byte) 0xff, (byte) 0xfe}, text.getBytes(StandardCharsets.UTF_16LE));
  }

  /** Returns bytes the binary detector refuses; each mark gives another version of the file. */
  private static byte[] binary(int mark) {
    byte[] bytes = new byte[256];
    bytes[10] = (byte) mark;
    return bytes;
  }

  private static String guardNotice() {
    return UiText.OPEN_READ_ONLY.format("a.xml", WINDOWS_31J.displayName());
  }

  private int failedReloads() {
    String start = UiText.RELOAD_FAILED.format("a.txt", "").trim();
    int shown = 0;
    for (String notice : view.notices) {
      shown += notice.startsWith(start) ? 1 : 0;
    }
    return shown;
  }

  /** Replaces the window's controllers with ones that read every file in a {@link Gate}. */
  private Gate gated() {
    Gate gate = new Gate();
    readWith(gate);
    return gate;
  }

  /** Replaces the window's controllers with ones that read every file in {@code charset}. */
  private void readWith(Charset charset) {
    compare.dispose();
    documents.dispose();
    documents =
        new DocumentController(workers, ui, view, budget, LoadOptions.DEFAULTS.withForced(charset));
    compare = new CompareController(documents, workers, ui, view, budget);
    documents.setCompare(compare);
  }

  private void awaitHeld(final Gate gate) throws Exception {
    await(
        new BooleanSupplier() {
          @Override
          public boolean getAsBoolean() {
            return gate.reached.getCount() == 0;
          }
        },
        "the read holds");
  }

  /** Changes a file and waits until the automatic reload that follows holds in its read. */
  private void holdCheckedReload(Gate gate, String name, String text) throws Exception {
    gate.armed.set(true);
    write(name, text);
    documents.checkFiles();
    awaitHeld(gate);
  }

  private void awaitPrompts(final int count) throws Exception {
    await(
        new BooleanSupplier() {
          @Override
          public boolean getAsBoolean() {
            return view.changePrompts.size() == count;
          }
        },
        count + " prompts");
  }

  private Document open(Path file) throws Exception {
    final int before = view.tabs.size();
    documents.open(List.of(file));
    await(
        new BooleanSupplier() {
          @Override
          public boolean getAsBoolean() {
            return view.tabs.size() == before + 1;
          }
        },
        "opened");
    idle();
    return documents.activeDocument();
  }

  /** Activates the window and waits until the document holds the text. */
  private void checkUntilText(final Document document, final String text) throws Exception {
    documents.checkFiles();
    await(
        new BooleanSupplier() {
          @Override
          public boolean getAsBoolean() {
            return document.text().toString().equals(text);
          }
        },
        "reloaded to " + text);
    idle();
  }

  private void checkUntilPrompts(final int count) throws Exception {
    documents.checkFiles();
    await(
        new BooleanSupplier() {
          @Override
          public boolean getAsBoolean() {
            return view.changePrompts.size() == count;
          }
        },
        count + " prompts");
  }

  /**
   * Activates the window twice and waits for the second check of every file, which starts after the
   * first ends: a pass in progress may have read a file before it changed.
   */
  private void checkAndSettle() throws Exception {
    for (int i = 0; i < 2; i++) {
      final long passes = documents.checkPasses();
      documents.checkFiles();
      await(
          new BooleanSupplier() {
            @Override
            public boolean getAsBoolean() {
              return documents.checkPasses() > passes;
            }
          },
          "a check of every file");
    }
    idle();
  }

  @Test
  void aCleanDocumentReloadsAsOneUndoStepKeepingTheCaretLine() throws Exception {
    Path file = write("a.txt", "one\ntwo\nthree\n");
    Document document = open(file);
    documents.activeEditor().goTo(2, 2);
    write("a.txt", "zero\none\ntwo\nTHREE\n");
    checkUntilText(document, "zero\none\ntwo\nTHREE\n");
    assertFalse(document.isDirty());
    TextSnapshot text = document.text();
    int caret = documents.activeEditor().selections().selections().primary().caret();
    assertEquals(2, text.lineAt(caret));
    assertEquals(2, caret - text.offsetOfLine(2));
    documents.activeEditor().undo();
    assertEquals("one\ntwo\nthree\n", document.text().toString());
    assertTrue(document.isDirty());
    assertTrue(view.changePrompts.isEmpty());
  }

  @Test
  void aCompositionUpdateAfterAReloadKeepsTheReloadedText() throws Exception {
    Path file = write("a.txt", "one\ntwo\n");
    Document document = open(file);
    EditorController editor = documents.activeEditor();
    editor.goTo(2, 2);
    editor.preeditFromView("k");
    write("a.txt", "one\nTWO\nthree\n");
    checkUntilText(document, "one\nTWO\nthree\n");
    editor.preeditFromView("ka");
    editor.preeditFromView("");
    assertEquals("one\nTWO\nthree\n", document.text().toString());
    assertFalse(document.isDirty());
  }

  @Test
  void aCleanDocumentAsksWhenAutoReloadIsOff() throws Exception {
    Path file = write("a.txt", "one\n");
    Document document = open(file);
    documents.setAutoReloadClean(false);
    write("a.txt", "two\n");
    checkUntilPrompts(1);
    assertEquals("one\n", document.text().toString());
    view.changeAnswer.accept(ChangeChoice.KEEP);
  }

  @Test
  void aDirtyDocumentAsksAndKeepMineMakesTheSaveAsk() throws Exception {
    Path file = write("a.txt", "one\n");
    Document document = open(file);
    documents.activeEditor().type("mine ");
    write("a.txt", "theirs\n");
    checkUntilPrompts(1);
    assertEquals("a.txt", view.changePrompts.get(0));
    view.changeAnswer.accept(ChangeChoice.KEEP);
    checkAndSettle();
    assertEquals(1, view.changePrompts.size());
    assertEquals("mine one\n", document.text().toString());
    documents.save();
    await(
        new BooleanSupplier() {
          @Override
          public boolean getAsBoolean() {
            return view.changePrompts.size() == 2;
          }
        },
        "save conflict");
    assertEquals("save a.txt", view.changePrompts.get(1));
    assertEquals("theirs\n", Files.readString(file));
    view.changeAnswer.accept(ChangeChoice.OVERWRITE);
    idle();
    assertEquals("mine one\n", Files.readString(file));
    assertFalse(document.isDirty());
  }

  /** A file that never holds still is reported anyway, once the waits run out. */
  @Test
  void aFileThatKeepsChangingIsStillReported() throws Exception {
    final Path file = write("a.txt", "one\n");
    final Document document = open(file);
    final AtomicBoolean writing = new AtomicBoolean(true);
    workers.start(
        Workers.Kind.IO,
        new Runnable() {
          @Override
          public void run() {
            int line = 0;
            while (writing.get()) {
              try {
                Files.writeString(file, "line " + line++ + "\n", StandardCharsets.UTF_8);
                Thread.sleep(ExternalChanges.QUIET_MILLIS / 4);
              } catch (IOException | InterruptedException e) {
                return;
              }
            }
          }
        });
    try {
      documents.checkFiles();
      await(
          new BooleanSupplier() {
            @Override
            public boolean getAsBoolean() {
              return document.text().toString().startsWith("line ");
            }
          },
          "reloaded while the file changes");
    } finally {
      writing.set(false);
    }
    idle();
  }

  @Test
  void reloadOfADirtyDocumentKeepsTheEditsInUndo() throws Exception {
    Path file = write("a.txt", "one\n");
    Document document = open(file);
    documents.activeEditor().type("mine ");
    write("a.txt", "theirs\n");
    checkUntilPrompts(1);
    view.changeAnswer.accept(ChangeChoice.RELOAD);
    await(
        new BooleanSupplier() {
          @Override
          public boolean getAsBoolean() {
            return document.text().toString().equals("theirs\n");
          }
        },
        "reloaded");
    idle();
    assertFalse(document.isDirty());
    documents.activeEditor().undo();
    assertEquals("mine one\n", document.text().toString());
  }

  @Test
  void aNewerChangeUpdatesThePendingPrompt() throws Exception {
    Path file = write("a.txt", "one\n");
    Document document = open(file);
    documents.activeEditor().type("mine ");
    write("a.txt", "first change\n");
    checkUntilPrompts(1);
    write("a.txt", "second change, longer\n");
    checkAndSettle();
    assertEquals(1, view.changePrompts.size());
    view.changeAnswer.accept(ChangeChoice.RELOAD);
    await(
        new BooleanSupplier() {
          @Override
          public boolean getAsBoolean() {
            return document.text().toString().equals("second change, longer\n");
          }
        },
        "reloaded the newer change");
    idle();
  }

  @Test
  void aDeletedFileStaysOpenMissingAndDirtyUntilItReturns() throws Exception {
    Path file = write("a.txt", "one\n");
    final Document document = open(file);
    Files.delete(file);
    documents.checkFiles();
    await(
        new BooleanSupplier() {
          @Override
          public boolean getAsBoolean() {
            return document.isDirty();
          }
        },
        "marked missing");
    assertTrue(view.selected.title.contains("missing"), view.selected.title);
    assertEquals("one\n", document.text().toString());
    write("a.txt", "one\n");
    documents.checkFiles();
    await(
        new BooleanSupplier() {
          @Override
          public boolean getAsBoolean() {
            return !document.isDirty();
          }
        },
        "the same bytes returned");
    assertEquals("a.txt", view.selected.title);
    assertTrue(view.changePrompts.isEmpty());
  }

  @Test
  void aMissingFileIsWrittenBySaveWithoutAPrompt() throws Exception {
    Path file = write("a.txt", "one\n");
    final Document document = open(file);
    Files.delete(file);
    documents.checkFiles();
    await(
        new BooleanSupplier() {
          @Override
          public boolean getAsBoolean() {
            return document.isDirty();
          }
        },
        "marked missing");
    documents.save();
    idle();
    assertEquals("one\n", Files.readString(file));
    assertFalse(document.isDirty());
    assertEquals("a.txt", view.selected.title);
  }

  @Test
  void aTouchWithTheSameBytesNeitherPromptsNorBlocksTheSave() throws Exception {
    Path file = write("a.txt", "one\n");
    Document document = open(file);
    documents.activeEditor().type("x");
    FileTime later = FileTime.fromMillis(Files.getLastModifiedTime(file).toMillis() + 5000);
    Files.setLastModifiedTime(file, later);
    checkAndSettle();
    documents.save();
    idle();
    assertTrue(view.changePrompts.isEmpty(), view.changePrompts.toString());
    assertEquals("xone\n", Files.readString(file));
    assertFalse(document.isDirty());
  }

  @Test
  void anOwnSaveIsNotAnExternalChange() throws Exception {
    Path file = write("a.txt", "one\n");
    Document document = open(file);
    for (int i = 0; i < 5; i++) {
      documents.activeEditor().type("x");
      documents.save();
      documents.checkFiles();
      idle();
    }
    checkAndSettle();
    assertTrue(view.changePrompts.isEmpty(), view.changePrompts.toString());
    assertEquals("xxxxxone\n", document.text().toString());
    assertEquals("xxxxxone\n", Files.readString(file));
  }

  @Test
  void aSpanTheBudgetCannotHoldLeavesTheTextAndReportsIt() throws Exception {
    Path file = write("a.txt", "x");
    Document document = open(file);
    long rest = budget.available();
    assertTrue(budget.reserve(rest));
    try {
      write("a.txt", "y".repeat(1 << 20));
      checkAndSettle();
      await(
          new BooleanSupplier() {
            @Override
            public boolean getAsBoolean() {
              return view.notices.contains(UiText.RELOAD_TOO_LARGE.format("a.txt"));
            }
          },
          "notice");
      assertEquals("x", document.text().toString());
      assertFalse(document.isDirty());
    } finally {
      budget.release(rest);
    }
  }

  @Test
  void aRefusedReloadRetriesOnlyOnANewChange() throws Exception {
    Path file = write("a.txt", "x");
    Document document = open(file);
    String refused = UiText.RELOAD_TOO_LARGE.format("a.txt");
    long rest = budget.available();
    assertTrue(budget.reserve(rest));
    boolean reserved = true;
    try {
      write("a.txt", "y".repeat(1 << 20));
      checkAndSettle();
      checkAndSettle();
      checkAndSettle();
      assertEquals(1, Collections.frequency(view.notices, refused), view.notices.toString());

      write("a.txt", "z".repeat(1 << 20));
      checkAndSettle();
      checkAndSettle();
      assertEquals(2, Collections.frequency(view.notices, refused), view.notices.toString());

      budget.release(rest);
      reserved = false;
      checkAndSettle();
      assertEquals("x", document.text().toString());

      write("a.txt", "new\n");
      checkAndSettle();
      assertEquals("new\n", document.text().toString());
      assertEquals(2, Collections.frequency(view.notices, refused), view.notices.toString());
    } finally {
      if (reserved) {
        budget.release(rest);
      }
    }
  }

  @Test
  void aSaveAfterARefusedReloadStillAsks() throws Exception {
    Path file = write("a.txt", "x");
    open(file);
    long rest = budget.available();
    assertTrue(budget.reserve(rest));
    try {
      write("a.txt", "y".repeat(1 << 20));
      checkAndSettle();
      await(
          new BooleanSupplier() {
            @Override
            public boolean getAsBoolean() {
              return view.notices.contains(UiText.RELOAD_TOO_LARGE.format("a.txt"));
            }
          },
          "notice");
    } finally {
      budget.release(rest);
    }
    documents.activeEditor().type("m");
    documents.save();
    await(
        new BooleanSupplier() {
          @Override
          public boolean getAsBoolean() {
            return view.changePrompts.contains("save a.txt");
          }
        },
        "save conflict");
    assertEquals("y".repeat(1 << 20), Files.readString(file));
  }

  /** An ASCII head admits the file; a wide char per leaf past it costs 2 bytes per char. */
  @Test
  void compareRefusesAFileWhoseTextOutgrowsTheBudget() throws Exception {
    Path file = write("a.txt", "one\n");
    open(file);
    documents.activeEditor().type("mine ");
    write("a.txt", "a".repeat(1 << 17) + "\n" + (("a".repeat(1000) + "中\n").repeat(4000)));
    LoadCost cost = DocumentFiles.estimate(file, LoadOptions.DEFAULTS.limits());
    long rest = budget.available() - cost.reserveBytes() - 100;
    assertTrue(budget.reserve(rest));
    try {
      checkUntilPrompts(1);
      view.changeAnswer.accept(ChangeChoice.COMPARE);
      await(
          new BooleanSupplier() {
            @Override
            public boolean getAsBoolean() {
              return view.notices.contains(UiText.COMPARE_TOO_LARGE.format("a.txt"));
            }
          },
          "notice");
      assertTrue(view.comparisons.isEmpty());
      checkAndSettle();
      view.changeAnswer.accept(ChangeChoice.KEEP);
    } finally {
      budget.release(rest);
    }
  }

  @Test
  void compareFromThePromptShowsTheFileBesideTheBuffer() throws Exception {
    Path file = write("a.txt", "one\ntwo\n");
    open(file);
    documents.activeEditor().type("mine ");
    write("a.txt", "one\nthree\n");
    checkUntilPrompts(1);
    view.changeAnswer.accept(ChangeChoice.COMPARE);
    await(
        new BooleanSupplier() {
          @Override
          public boolean getAsBoolean() {
            return view.comparisons.size() == 1;
          }
        },
        "comparison");
    Comparison c = view.comparisons.get(0);
    assertEquals("one\nthree\n", c.leftText());
    assertEquals("mine one\ntwo\n", c.rightText());
    assertEquals(
        List.of(Comparison.Row.CHANGED, Comparison.Row.CHANGED, Comparison.Row.SAME), c.rows());
    checkAndSettle();
    assertEquals(1, view.changePrompts.size());
    view.changeAnswer.accept(ChangeChoice.KEEP);
  }

  @Test
  void compareDocumentsAsksForTheOtherDocument() throws Exception {
    open(write("a.txt", "same\nleft\n"));
    open(write("b.txt", "same\nright\nadded\n"));
    compare.compareDocuments();
    assertEquals(1, view.documentChoices.size());
    view.documentChooser.accept(0);
    await(
        new BooleanSupplier() {
          @Override
          public boolean getAsBoolean() {
            return view.comparisons.size() == 1;
          }
        },
        "comparison");
    Comparison c = view.comparisons.get(0);
    assertEquals("b.txt", c.leftTitle());
    assertEquals("a.txt", c.rightTitle());
    assertEquals(
        List.of(
            Comparison.Row.SAME,
            Comparison.Row.CHANGED,
            Comparison.Row.REMOVED,
            Comparison.Row.SAME),
        c.rows());
    assertEquals(List.of(1), c.changes());
  }

  @Test
  void aRetargetedLinkReloadsTheNewTarget() throws Exception {
    Path a = write("a.txt", "first\n");
    Path b = write("b.txt", "second\n");
    Path link = temp.resolve("link.txt");
    try {
      Files.createSymbolicLink(link, a);
    } catch (UnsupportedOperationException | IOException e) {
      Assumptions.abort("symbolic links unavailable: " + e);
    }
    Document document = open(link);
    Files.delete(link);
    Files.createSymbolicLink(link, b);
    checkUntilText(document, "second\n");
    assertEquals(b.toRealPath(), documents.activePath());
    documents.activeEditor().type("x");
    documents.save();
    idle();
    assertEquals("xsecond\n", Files.readString(b));
    assertEquals("first\n", Files.readString(a));
  }

  @Test
  void aFileLargerThanEveryBufferReloads() throws Exception {
    StringBuilder text = new StringBuilder();
    while (text.length() < (3 << 20)) {
      text.append("line ").append(text.length()).append('\n');
    }
    Path file = write("large.txt", text.toString());
    Document document = open(file);
    String changed = text + "last\n";
    write("large.txt", changed);
    checkUntilText(document, changed);
    assertFalse(document.isDirty());
  }

  /** A reload that brings in bytes a save would change makes the document read-only, as an open. */
  @Test
  void aReloadKeepsTheRoundTripGuard() throws Exception {
    Path file = temp.resolve("a.xml");
    Files.write(file, concat(DECLARED, "abc\n".getBytes(StandardCharsets.US_ASCII)));
    Document document = open(file);
    assertFalse(document.isReadOnly());
    byte[] changed = concat(DECLARED, NO_ROUND_TRIP);
    replace(file, changed);
    checkUntilText(document, new String(changed, WINDOWS_31J));
    assertEquals(WINDOWS_31J, document.encoding().charset());
    assertTrue(document.isReadOnly());
    assertFalse(document.isDirty());
    assertTrue(view.notices.contains(guardNotice()), view.notices.toString());
  }

  /** ED 40 and FA 5C decode to one char, so only the guard tells the two files apart. */
  @Test
  void aReloadWithTheSameTextKeepsTheRoundTripGuard() throws Exception {
    Path file = temp.resolve("a.xml");
    Files.write(file, concat(DECLARED, ENCODED_FORM));
    final Document document = open(file);
    assertFalse(document.isReadOnly());
    replace(file, concat(DECLARED, NO_ROUND_TRIP));
    documents.checkFiles();
    await(
        new BooleanSupplier() {
          @Override
          public boolean getAsBoolean() {
            return document.isReadOnly();
          }
        },
        "the guard");
  }

  @Test
  void aReloadBackToBytesThatRoundTripClearsTheGuard() throws Exception {
    Path file = temp.resolve("a.xml");
    Files.write(file, concat(DECLARED, NO_ROUND_TRIP));
    Document document = open(file);
    assertTrue(document.isReadOnly());
    byte[] clean = concat(DECLARED, "abc\n".getBytes(StandardCharsets.US_ASCII));
    replace(file, clean);
    checkUntilText(document, new String(clean, WINDOWS_31J));
    assertFalse(document.isReadOnly());
  }

  @Test
  void theGuardsEndKeepsTheUsersReadOnlyChoice() throws Exception {
    Path file = temp.resolve("a.xml");
    byte[] clean = concat(DECLARED, "abc\n".getBytes(StandardCharsets.US_ASCII));
    Files.write(file, clean);
    Document document = open(file);
    documents.toggleReadOnly();
    assertTrue(document.isReadOnly());
    byte[] changed = concat(DECLARED, NO_ROUND_TRIP);
    replace(file, changed);
    checkUntilText(document, new String(changed, WINDOWS_31J));
    replace(file, clean);
    checkUntilText(document, new String(clean, WINDOWS_31J));
    assertTrue(document.isReadOnly());
  }

  @Test
  void theGuardsEndKeepsAReadOnlyStateTheUserSetSince() throws Exception {
    Path file = temp.resolve("a.xml");
    Files.write(file, concat(DECLARED, NO_ROUND_TRIP));
    Document document = open(file);
    documents.toggleReadOnly();
    documents.toggleReadOnly();
    assertTrue(document.isReadOnly());
    byte[] clean = concat(DECLARED, "abc\n".getBytes(StandardCharsets.US_ASCII));
    replace(file, clean);
    checkUntilText(document, new String(clean, WINDOWS_31J));
    assertTrue(document.isReadOnly());
  }

  /** Turning Read-Only off accepted the conversion of the earlier bytes, not of these. */
  @Test
  void aReloadOfOtherSuchBytesGuardsAfterTheUserClearedReadOnly() throws Exception {
    Path file = temp.resolve("a.xml");
    Files.write(file, concat(DECLARED, NO_ROUND_TRIP));
    Document document = open(file);
    documents.toggleReadOnly();
    assertFalse(document.isReadOnly());
    byte[] changed = concat(concat(DECLARED, NO_ROUND_TRIP), NO_ROUND_TRIP);
    replace(file, changed);
    checkUntilText(document, new String(changed, WINDOWS_31J));
    assertTrue(document.isReadOnly());
  }

  @Test
  void typingDuringAnAutomaticReloadStaysAndAsks() throws Exception {
    Gate gate = gated();
    Path file = write("a.txt", "one\n");
    Document document = open(file);
    holdCheckedReload(gate, "a.txt", "theirs\n");
    documents.activeEditor().type("mine ");
    gate.released.countDown();
    awaitPrompts(1);
    idle();
    assertEquals("a.txt", view.changePrompts.get(0));
    assertEquals("mine one\n", document.text().toString());
    assertTrue(document.isDirty());
    view.changeAnswer.accept(ChangeChoice.KEEP);
    checkAndSettle();
    assertEquals(1, view.changePrompts.size());
    documents.save();
    awaitPrompts(2);
    assertEquals("save a.txt", view.changePrompts.get(1));
    assertEquals("theirs\n", Files.readString(file));
  }

  @Test
  void undoReturnsTextTypedDuringAReloadTheUserThenAccepted() throws Exception {
    Gate gate = gated();
    Path file = write("a.txt", "one\n");
    Document document = open(file);
    holdCheckedReload(gate, "a.txt", "theirs\n");
    documents.activeEditor().type("mine ");
    gate.released.countDown();
    awaitPrompts(1);
    idle();
    view.changeAnswer.accept(ChangeChoice.RELOAD);
    idle();
    assertEquals("theirs\n", document.text().toString());
    assertFalse(document.isDirty());
    documents.activeEditor().undo();
    assertEquals("mine one\n", document.text().toString());
    assertTrue(document.isDirty());
  }

  @Test
  void anEditUndoneDuringAnAutomaticReloadStillReloads() throws Exception {
    Gate gate = gated();
    Path file = write("a.txt", "one\n");
    Document document = open(file);
    holdCheckedReload(gate, "a.txt", "theirs\n");
    documents.activeEditor().type("mine ");
    documents.activeEditor().undo();
    assertFalse(document.isDirty());
    gate.released.countDown();
    idle();
    ui.runPending();
    idle();
    assertEquals("theirs\n", document.text().toString());
    assertFalse(document.isDirty());
    assertTrue(view.changePrompts.isEmpty());
  }

  @Test
  void typingDuringReloadFromDiskOfACleanDocumentStaysAndAsks() throws Exception {
    final Gate gate = gated();
    Path file = write("a.txt", "one\n");
    Document document = open(file);
    gate.armed.set(true);
    documents.reloadFromDisk();
    awaitHeld(gate);
    documents.activeEditor().type("mine ");
    gate.released.countDown();
    await(
        new BooleanSupplier() {
          @Override
          public boolean getAsBoolean() {
            return view.reloadAccept != null;
          }
        },
        "the reload prompt");
    idle();
    assertEquals("a.txt", view.reloadName);
    assertEquals("mine one\n", document.text().toString());
    view.reloadDecline.run();
  }

  @Test
  void aReloadTheUserConfirmedReplacesEditsMadeDuringItsRead() throws Exception {
    Gate gate = gated();
    Path file = write("a.txt", "one\n");
    Document document = open(file);
    documents.activeEditor().type("mine ");
    write("a.txt", "theirs\n");
    checkUntilPrompts(1);
    gate.armed.set(true);
    view.changeAnswer.accept(ChangeChoice.RELOAD);
    awaitHeld(gate);
    documents.activeEditor().type("more ");
    gate.released.countDown();
    idle();
    ui.runPending();
    idle();
    assertEquals("theirs\n", document.text().toString());
    assertFalse(document.isDirty());
    assertEquals(1, view.changePrompts.size());
  }

  /** The file holds valid UTF-16LE either way; only detection reads it as what it now is. */
  @Test
  void aReloadDetectsAnEncodingChangedByAnotherProgram() throws Exception {
    Path file = temp.resolve("a.txt");
    Files.write(file, utf16("one\n"));
    Document document = open(file);
    assertEquals(StandardCharsets.UTF_16LE, document.encoding().charset());
    replace(file, "abcd\n123".getBytes(StandardCharsets.UTF_8));
    checkUntilText(document, "abcd\n123");
    assertEquals(StandardCharsets.UTF_8, document.encoding().charset());
    assertFalse(document.isDirty());
  }

  @Test
  void aChosenCharsetSurvivesAReload() throws Exception {
    Path file = write("a.txt", "one\n");
    final Document opened = open(file);
    documents.reopenWithEncoding(StandardCharsets.ISO_8859_1);
    await(
        new BooleanSupplier() {
          @Override
          public boolean getAsBoolean() {
            return documents.activeDocument() != opened;
          }
        },
        "reopened");
    idle();
    Document document = documents.activeDocument();
    assertEquals(StandardCharsets.ISO_8859_1, document.encoding().charset());
    replace(file, "café\n".getBytes(StandardCharsets.UTF_8));
    checkUntilText(document, "cafÃ©\n");
    assertEquals(StandardCharsets.ISO_8859_1, document.encoding().charset());
  }

  @Test
  void aCharsetChosenForSavingSurvivesAReload() throws Exception {
    Path file = write("a.txt", "one\n");
    final Document document = open(file);
    documents.saveWithEncoding(StandardCharsets.ISO_8859_1);
    documents.save();
    await(
        new BooleanSupplier() {
          @Override
          public boolean getAsBoolean() {
            return !document.isDirty();
          }
        },
        "saved");
    idle();
    replace(file, "caf\u00e9\n".getBytes(StandardCharsets.UTF_8));
    checkUntilText(document, "caf\u00c3\u00a9\n");
    assertEquals(StandardCharsets.ISO_8859_1, document.encoding().charset());
  }

  @Test
  void compareWithDiskReadsAFileAsAReloadDoes() throws Exception {
    Path file = temp.resolve("a.txt");
    Files.write(file, utf16("one\n"));
    open(file);
    documents.activeEditor().type("mine ");
    replace(file, "abcd\n123".getBytes(StandardCharsets.UTF_8));
    compare.compareWithDisk();
    await(
        new BooleanSupplier() {
          @Override
          public boolean getAsBoolean() {
            return view.comparisons.size() == 1;
          }
        },
        "comparison");
    assertEquals("abcd\n123", view.comparisons.get(0).leftText());
  }

  @Test
  void aFailedReloadReportsOncePerChange() throws Exception {
    Path file = write("a.txt", "one\n");
    Document document = open(file);
    replace(file, binary(1));
    checkAndSettle();
    checkAndSettle();
    checkAndSettle();
    assertEquals(1, failedReloads(), view.notices.toString());

    replace(file, binary(2));
    checkAndSettle();
    checkAndSettle();
    assertEquals(2, failedReloads(), view.notices.toString());

    replace(file, "new\n".getBytes(StandardCharsets.UTF_8));
    checkUntilText(document, "new\n");
    assertEquals(2, failedReloads(), view.notices.toString());
  }

  @Test
  void aSaveAfterAFailedReloadStillAsks() throws Exception {
    Path file = write("a.txt", "one\n");
    open(file);
    byte[] changed = binary(1);
    replace(file, changed);
    checkAndSettle();
    assertEquals(1, failedReloads(), view.notices.toString());
    documents.activeEditor().type("m");
    documents.save();
    awaitPrompts(1);
    assertEquals("save a.txt", view.changePrompts.get(0));
    assertArrayEquals(changed, Files.readAllBytes(file));
  }

  /** A buffer's recorded base is the watch's: after Keep Mine, the file the user declined. */
  @Test
  void keepMineRecordsTheDeclinedFileAsTheBuffersBase() throws Exception {
    write("a.txt", "one\n");
    open(temp.resolve("a.txt"));
    documents.activeEditor().type("mine ");
    FileBaseline opened = documents.dirtyBuffers().get(0).base();
    write("a.txt", "theirs, longer\n");
    checkUntilPrompts(1);
    view.changeAnswer.accept(ChangeChoice.KEEP);
    checkAndSettle();
    FileBaseline kept = documents.dirtyBuffers().get(0).base();
    assertFalse(opened.stamp().equals(kept.stamp()));
    assertEquals(Files.size(temp.resolve("a.txt")), kept.stamp().size());
  }

  @Test
  void reloadAtTheSaveConflictReadsTheFileAndWritesNothing() throws Exception {
    Path file = write("a.txt", "one\n");
    Document document = open(file);
    documents.activeEditor().type("mine ");
    write("a.txt", "theirs\n");
    checkUntilPrompts(1);
    view.changeAnswer.accept(ChangeChoice.KEEP);
    checkAndSettle();
    documents.save();
    awaitPrompts(2);
    assertEquals("save a.txt", view.changePrompts.get(1));
    view.changeAnswer.accept(ChangeChoice.RELOAD);
    idle();
    assertEquals("theirs\n", document.text().toString());
    assertFalse(document.isDirty());
    assertEquals("theirs\n", Files.readString(file));
  }

  @Test
  void aSaveDuringAReloadShowsANotice() throws Exception {
    Gate gate = gated();
    Path file = write("a.txt", "one\n");
    open(file);
    gate.armed.set(true);
    documents.reloadFromDisk();
    awaitHeld(gate);
    documents.save();
    String notice = UiText.SAVE_TITLE.text() + ": " + UiText.SAVE_RELOADING.format("a.txt");
    assertEquals(List.of(notice), view.infos);
    gate.released.countDown();
    idle();
  }

  /** A reload confirmed over dirty text and edited during every read stops at the last attempt. */
  @Test
  void aReloadEditedDuringEveryReadKeepsTheTextWithANotice() throws Exception {
    final Turnstile gate = new Turnstile();
    readWith(gate);
    Path file = write("a.txt", "one\n");
    Document document = open(file);
    documents.activeEditor().type("mine ");
    write("a.txt", "theirs\n");
    checkUntilPrompts(1);
    gate.on.set(true);
    view.changeAnswer.accept(ChangeChoice.RELOAD);
    final String edited = UiText.RELOAD_EDITED.format("a.txt");
    int reads = 0;
    while (true) {
      await(
          new BooleanSupplier() {
            @Override
            public boolean getAsBoolean() {
              return gate.arrived.availablePermits() > 0 || view.notices.contains(edited);
            }
          },
          "a read or the notice");
      if (!gate.arrived.tryAcquire()) {
        break;
      }
      documents.activeEditor().type("e");
      gate.passes.release();
      reads++;
    }
    gate.on.set(false);
    idle();
    assertTrue(reads >= SaveAndReload.RELOAD_ATTEMPTS, "reads: " + reads);
    assertEquals("mine " + "e".repeat(reads) + "one\n", document.text().toString());
    assertTrue(document.isDirty());
    assertEquals(1, Collections.frequency(view.notices, edited), view.notices.toString());
  }
}
