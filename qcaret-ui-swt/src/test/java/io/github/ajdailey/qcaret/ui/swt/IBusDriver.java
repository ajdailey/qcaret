package io.github.ajdailey.qcaret.ui.swt;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import io.github.ajdailey.qcaret.app.command.CommandId;
import io.github.ajdailey.qcaret.app.command.Keymap;
import io.github.ajdailey.qcaret.app.config.ConfigStore;
import io.github.ajdailey.qcaret.app.config.Preferences;
import io.github.ajdailey.qcaret.app.config.SettingsLocation;
import io.github.ajdailey.qcaret.app.config.SettingsMode;
import io.github.ajdailey.qcaret.app.controller.ApplicationController;
import io.github.ajdailey.qcaret.app.controller.EditorController;
import io.github.ajdailey.qcaret.app.log.LogFiles;
import io.github.ajdailey.qcaret.app.log.RecentLog;
import io.github.ajdailey.qcaret.app.theme.Palette;
import io.github.ajdailey.qcaret.app.theme.ThemeMode;
import io.github.ajdailey.qcaret.app.view.WindowGeometry;
import io.github.ajdailey.qcaret.core.concurrent.MemoryBudget;
import io.github.ajdailey.qcaret.core.concurrent.Workers;
import io.github.ajdailey.qcaret.core.document.BlockSelection;
import io.github.ajdailey.qcaret.core.document.Document;
import io.github.ajdailey.qcaret.core.document.Selection;
import io.github.ajdailey.qcaret.core.document.SelectionSet;
import io.github.ajdailey.qcaret.ui.swt.editor.SwtKeys;
import java.io.BufferedWriter;
import java.io.IOException;
import java.io.PrintWriter;
import java.io.StringWriter;
import java.lang.reflect.InvocationTargetException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.atomic.AtomicReference;
import java.util.function.BooleanSupplier;
import org.eclipse.swt.SWT;
import org.eclipse.swt.custom.StyledText;
import org.eclipse.swt.widgets.Display;
import org.eclipse.swt.widgets.Event;
import org.eclipse.swt.widgets.IME;
import org.eclipse.swt.widgets.Listener;

/**
 * The JVM {@link IBusSession} starts inside its X server and input method. Opens a real {@link
 * MainWindow}, sends X key events through {@code xdotool} and writes one {@link #ROW} line per
 * check, each a method named in {@link #rows}. Under Cangjie 5, {@code a} composes {@link #SUN},
 * {@code a a} {@link #BRIGHT}, {@code a a d} {@link #PAIR} and {@code h q i} {@link #I}; Space
 * commits and Escape cancels.
 */
final class IBusDriver {
  static final String READY = "READY";
  static final String UNAVAILABLE = "UNAVAILABLE";
  static final String ROW = "ROW";
  static final String PASS = "pass";

  private static final String SUN = "日";
  private static final String BRIGHT = "昌";
  private static final String I = "我";
  private static final String PAIR = new String(Character.toChars(0x23201));
  private static final Duration BOUND = Duration.ofSeconds(15);
  private static final long QUIET_NANOS = 200_000_000L;

  private final Path temp;
  private final BufferedWriter results;
  private final List<String> trace = new ArrayList<String>();
  private final AtomicReference<Throwable> failure = new AtomicReference<Throwable>();
  private Display display;
  private Workers workers;
  private MainWindow window;
  private ApplicationController controller;
  private StyledText text;
  private Path file;
  private int files;

  private IBusDriver(Path temp, BufferedWriter results) {
    this.temp = temp;
    this.results = results;
  }

  /** Returns the name of each check. */
  static List<String> rows() {
    return List.of(
        "compositionShowsAtTheCaret",
        "commitIsOneUndoStep",
        "cancelRestoresTheText",
        "compositionShowsBeforeASelection",
        "cancelKeepsTheSelection",
        "commitReplacesTheSelection",
        "commitsFollowingEachOtherStayOnce",
        "pairCommitsWhole",
        "eventsMatchEditingSurfaceTest",
        "commitReachesEveryCaret",
        "pairCommitsToEveryBlockRow",
        "compositionOnAWrappedRow",
        "reloadDuringAComposition",
        "focusLeavingCommitsOnce",
        "closeDuringAComposition");
  }

  /**
   * Runs every check.
   *
   * @param args the private directory and the result file
   * @throws Exception when the window cannot open or the result file cannot be written
   */
  public static void main(String[] args) throws Exception {
    try (BufferedWriter results =
        Files.newBufferedWriter(Path.of(args[1]), StandardCharsets.UTF_8)) {
      new IBusDriver(Path.of(args[0]), results).run();
    }
    System.exit(0);
  }

  private void run() throws Exception {
    open();
    try {
      if (probe()) {
        write(READY);
        for (String row : rows()) {
          check(row);
        }
      } else {
        write(UNAVAILABLE + " no composition from a key event");
      }
    } finally {
      controller.documents().dispose();
      workers.close();
      display.dispose();
    }
  }

  /**
   * Waits for the first composition; the engine starts on the first focused context. A different
   * table version composes differently, and every check names this one's characters.
   */
  private boolean probe() throws Exception {
    fresh("ab");
    caret(2);
    for (int i = 0; i < 3; i++) {
      key("a");
      boolean composed =
          poll(
              new BooleanSupplier() {
                @Override
                public boolean getAsBoolean() {
                  return ("ab" + SUN).equals(document().text().toString());
                }
              });
      if (composed) {
        key("Escape");
        return true;
      }
    }
    return false;
  }

  private void check(String row) throws IOException {
    trace.clear();
    String outcome = PASS;
    try {
      IBusDriver.class.getDeclaredMethod(row).invoke(this);
      if (failure.get() != null) {
        outcome = describe(failure.get());
      }
    } catch (InvocationTargetException e) {
      outcome = describe(e.getCause());
    } catch (ReflectiveOperationException e) {
      outcome = describe(e);
    }
    write(ROW + " " + row + " " + outcome);
    for (String event : trace) {
      write("  " + event);
    }
    // A failed check can leave a composition for the next one.
    try {
      key("Escape");
      quiet();
    } catch (Exception e) {
      write("  escape failed: " + e);
    }
  }

  private static String describe(Throwable error) {
    StringWriter stack = new StringWriter();
    error.printStackTrace(new PrintWriter(stack));
    return stack.toString().replace('\n', '|');
  }

  private void write(String line) throws IOException {
    results.write(line);
    results.newLine();
    results.flush();
  }

  // Checks.

  void compositionShowsAtTheCaret() throws Exception {
    fresh("ab");
    caret(2);
    key("a");
    expect("ab" + SUN);
    assertEquals("ab" + SUN, text.getText());
    assertArrayEquals(new int[] {2, 1}, document().preeditRange());
    assertFalse(document().isDirty());
    assertFalse(document().canUndo());
  }

  void commitIsOneUndoStep() throws Exception {
    fresh("ab");
    caret(1);
    key("h");
    key("q");
    key("i");
    expect("a" + I + "b");
    key("space");
    expectCommit("a" + I + "b");
    assertTrue(document().isDirty());
    undo("ab");
    assertFalse(document().isDirty());
    assertFalse(document().canUndo());
    assertEquals(SelectionSet.caret(1), editor().selections().selections());
  }

  void cancelRestoresTheText() throws Exception {
    fresh("ab");
    caret(1);
    key("a");
    expect("a" + SUN + "b");
    key("Escape");
    expect("ab");
    quiet();
    assertEquals("ab", text.getText());
    assertNull(document().preeditRange());
    assertFalse(document().isDirty());
    assertFalse(document().canUndo());
    assertEquals(SelectionSet.caret(1), editor().selections().selections());
  }

  void compositionShowsBeforeASelection() throws Exception {
    fresh("hello world");
    select(6, 11);
    key("a");
    expect("hello " + SUN + "world");
    assertArrayEquals(new int[] {6, 1}, document().preeditRange());
    assertEquals(Selection.of(7, 12), editor().selections().selections().primary());
    assertEquals("", text.getSelectionText());
  }

  void cancelKeepsTheSelection() throws Exception {
    fresh("hello world");
    select(6, 11);
    key("a");
    expect("hello " + SUN + "world");
    key("Escape");
    expect("hello world");
    quiet();
    assertFalse(document().isDirty());
    assertEquals(Selection.of(6, 11), editor().selections().selections().primary());
    assertEquals("world", text.getSelectionText());
  }

  void commitReplacesTheSelection() throws Exception {
    fresh("hello world");
    select(6, 11);
    key("a");
    expect("hello " + SUN + "world");
    key("space");
    expectCommit("hello " + SUN);
    undo("hello world");
    assertEquals("world", text.getSelectionText());
    assertFalse(document().canUndo());
  }

  void commitsFollowingEachOtherStayOnce() throws Exception {
    fresh("xy");
    caret(1);
    key("a");
    expect("x" + SUN + "y");
    key("space");
    expectCommit("x" + SUN + "y");
    key("h");
    key("q");
    key("i");
    expect("x" + SUN + I + "y");
    key("space");
    expectCommit("x" + SUN + I + "y");
    key("a");
    key("a");
    expect("x" + SUN + I + BRIGHT + "y");
    key("space");
    expectCommit("x" + SUN + I + BRIGHT + "y");
  }

  void pairCommitsWhole() throws Exception {
    fresh("ab");
    caret(2);
    key("a");
    key("a");
    key("d");
    expect("ab" + PAIR);
    key("space");
    expectCommit("ab" + PAIR);
    undo("ab");
    assertFalse(document().canUndo());
  }

  /** Pins the sequences {@code EditingSurfaceTest} sends through its IME helpers. */
  void eventsMatchEditingSurfaceTest() throws Exception {
    fresh("ab");
    caret(2);
    trace.clear();
    key("a");
    expect("ab" + SUN);
    assertEquals(List.of("selection 2 2", "changed 2 2 [" + SUN + "] 0"), List.copyOf(trace));
    trace.clear();
    key("space");
    expectCommit("ab" + SUN);
    assertEquals(
        List.of("changed 2 3 [] 0", "changed 2 2 [" + SUN + "] 1", "key 0 65e5"),
        List.copyOf(trace));
    trace.clear();
    key("a");
    expect("ab" + SUN + SUN);
    key("Escape");
    expect("ab" + SUN);
    quiet();
    assertEquals(
        List.of("selection 3 3", "changed 3 3 [" + SUN + "] 0", "changed 3 4 [] 0"),
        List.copyOf(trace));
  }

  void commitReachesEveryCaret() throws Exception {
    fresh("one\ntwo\n");
    caret(3);
    controller.execute(CommandId.ADD_CARET_BELOW);
    quiet();
    key("a");
    expect("one\ntwo" + SUN + "\n");
    key("space");
    expectCommit("one" + SUN + "\ntwo" + SUN + "\n");
    undo("one\ntwo\n");
    assertFalse(document().canUndo());
  }

  void pairCommitsToEveryBlockRow() throws Exception {
    fresh("one\ntwo\nthree\n");
    editor().selections().setBlock(new BlockSelection(0, 1, 1, 1), document().tabWidth());
    quiet();
    key("a");
    key("a");
    key("d");
    expect("one\nt" + PAIR + "wo\nthree\n");
    key("space");
    expectCommit("o" + PAIR + "ne\nt" + PAIR + "wo\nthree\n");
    undo("one\ntwo\nthree\n");
    assertFalse(document().canUndo());
  }

  void compositionOnAWrappedRow() throws Exception {
    String line = "hello ".repeat(40);
    fresh(line);
    controller.execute(CommandId.WORD_WRAP);
    quiet();
    assertTrue(text.getLineCount() > 1);
    caret(180);
    String before = line.substring(0, 180);
    String after = line.substring(180);
    key("a");
    expect(before + SUN + after);
    key("Escape");
    expect(line);
    key("a");
    key("a");
    expect(before + BRIGHT + after);
    key("space");
    assertNull(awaitNoComposition());
    quiet();
    assertEquals(before + BRIGHT + after, document().text().toString());
  }

  /** The model drops the composition; the engine still holds it and commits at the next key. */
  void reloadDuringAComposition() throws Exception {
    fresh("hello world");
    caret(11);
    key("a");
    expect("hello world" + SUN);
    Files.writeString(file, "hi", StandardCharsets.UTF_8);
    controller.execute(CommandId.RELOAD);
    expect("hi");
    text = window.activeText();
    key("space");
    expectCommit("hi" + SUN);
  }

  /** The engine commits a composition when the widget loses the focus. */
  void focusLeavingCommitsOnce() throws Exception {
    fresh("hello");
    caret(5);
    key("a");
    expect("hello" + SUN);
    controller.execute(CommandId.FIND);
    expectCommit("hello" + SUN);
    assertTrue(document().isDirty());
    text.setFocus();
    quiet();
    assertEquals("hello" + SUN, text.getText());
  }

  /** The focus leaves the closing tab's widget, so its composition ends at a closed document. */
  void closeDuringAComposition() throws Exception {
    fresh("hello");
    caret(5);
    key("a");
    expect("hello" + SUN);
    int tabs = window.tabs().getItemCount();
    controller.execute(CommandId.CLOSE);
    quiet();
    assertEquals(tabs - 1, window.tabs().getItemCount());
  }

  // Window and keys.

  private void open() {
    display = new Display();
    workers =
        new Workers(
            2,
            new Thread.UncaughtExceptionHandler() {
              @Override
              public void uncaughtException(Thread thread, Throwable error) {
                failure.set(error);
              }
            });
    window = new MainWindow(display, Palette.LIGHT);
    controller =
        new ApplicationController(
            workers,
            new SwtUiDispatcher(display),
            window,
            new ConfigStore(new SettingsLocation(SettingsMode.EXPLICIT, temp.resolve("conf"))),
            Preferences.DEFAULTS,
            SwtThemeService.open(display, ThemeMode.LIGHT),
            new MemoryBudget(Runtime.getRuntime().maxMemory(), 60),
            Keymap.defaults(SwtKeys.mac(), SwtKeys.linux()),
            false);
    controller.setLogging(new RecentLog(50, 200), LogFiles.NONE);
    window.attach(controller);
    window.place(new WindowGeometry.Request(null, null, 700, 500), null);
    window.open();
    display.addFilter(
        SWT.KeyDown,
        new Listener() {
          @Override
          public void handleEvent(Event event) {
            trace.add("key " + event.keyCode + " " + Integer.toHexString(event.character));
          }
        });
  }

  /** Opens a document in a new tab and gives its widget the X and GTK focus. */
  private void fresh(String content) throws Exception {
    file = temp.resolve("doc" + files++ + ".txt");
    Files.writeString(file, content, StandardCharsets.UTF_8);
    final int before = window.tabs().getItemCount();
    controller.documents().open(List.of(file));
    boolean opened =
        poll(
            new BooleanSupplier() {
              @Override
              public boolean getAsBoolean() {
                return window.tabs().getItemCount() == before + 1
                    && controller.documents().loading() == 0;
              }
            });
    assertTrue(opened, "opened " + file);
    text = window.activeText();
    final IME ime = text.getIME();
    ime.addListener(
        SWT.ImeComposition,
        new Listener() {
          @Override
          public void handleEvent(Event event) {
            if (event.detail == SWT.COMPOSITION_SELECTION) {
              trace.add("selection " + event.start + " " + event.end);
            } else if (event.detail == SWT.COMPOSITION_CHANGED) {
              trace.add(
                  "changed "
                      + event.start
                      + " "
                      + event.end
                      + " ["
                      + event.text
                      + "] "
                      + ime.getCommitCount());
            }
          }
        });
    tool("xdotool", "search", "--name", "QCaret", "windowfocus", "--sync");
    text.setFocus();
    quiet();
    assertEquals(text, display.getFocusControl());
  }

  private EditorController editor() {
    return controller.documents().activeEditor();
  }

  private Document document() {
    return editor().document();
  }

  private void caret(int offset) {
    editor().selections().set(SelectionSet.caret(offset));
    quiet();
  }

  private void select(int from, int to) {
    editor().selections().set(SelectionSet.of(List.of(Selection.of(from, to)), 0));
    quiet();
  }

  private void key(String name) throws Exception {
    tool("xdotool", "key", name);
  }

  private void undo(String expected) {
    controller.execute(CommandId.UNDO);
    quiet();
    assertEquals(expected, document().text().toString());
    assertEquals(expected, text.getText());
  }

  /** Waits for the document to read {@code expected}. */
  private void expect(final String expected) {
    boolean reached =
        poll(
            new BooleanSupplier() {
              @Override
              public boolean getAsBoolean() {
                return expected.equals(document().text().toString());
              }
            });
    assertTrue(reached, "expected [" + expected + "], read [" + document().text() + "]");
  }

  /** Waits for committed text, then for the quiet that shows it arrived once. */
  private void expectCommit(String expected) {
    expect(expected);
    quiet();
    assertEquals(expected, document().text().toString());
    assertEquals(expected, text.getText());
    assertNull(document().preeditRange());
  }

  /** Waits for the composition to end, and returns its range: null once ended. */
  private int[] awaitNoComposition() {
    poll(
        new BooleanSupplier() {
          @Override
          public boolean getAsBoolean() {
            return document().preeditRange() == null;
          }
        });
    return document().preeditRange();
  }

  /** Turns the event loop for a fixed time, for what must not happen and has no event to await. */
  private void quiet() {
    final long until = System.nanoTime() + QUIET_NANOS;
    poll(
        new BooleanSupplier() {
          @Override
          public boolean getAsBoolean() {
            return System.nanoTime() > until;
          }
        });
  }

  private boolean poll(BooleanSupplier condition) {
    return EventLoop.runUntil(display, condition, BOUND);
  }

  /** Runs a tool while the event loop keeps turning. */
  private void tool(String... command) throws Exception {
    final Process process =
        new ProcessBuilder(command)
            .redirectErrorStream(true)
            .redirectOutput(ProcessBuilder.Redirect.DISCARD)
            .start();
    boolean ended =
        poll(
            new BooleanSupplier() {
              @Override
              public boolean getAsBoolean() {
                return !process.isAlive();
              }
            });
    assertTrue(ended, command[0] + " did not end");
    assertEquals(0, process.exitValue(), String.join(" ", command));
  }
}
