package io.github.ajdailey.qcaret.ui.swt;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

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
import io.github.ajdailey.qcaret.core.document.Selection;
import io.github.ajdailey.qcaret.core.document.SelectionSet;
import io.github.ajdailey.qcaret.ui.swt.platform.PlatformTraits;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.List;
import java.util.concurrent.atomic.AtomicReference;
import java.util.function.BooleanSupplier;
import org.eclipse.swt.SWT;
import org.eclipse.swt.custom.StyledText;
import org.eclipse.swt.graphics.Point;
import org.eclipse.swt.widgets.Display;
import org.eclipse.swt.widgets.Event;
import org.eclipse.swt.widgets.Listener;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/**
 * Selections the widget extends from the mouse and from Shift+arrow keys keep their anchor, in both
 * directions and over several steps.
 */
@Tag("swt")
class MouseSelectionSurfaceTest {
  private static final Duration BOUND = Duration.ofSeconds(30);
  private static final String TEXT = "alpha\nbravo\ncharlie\n";

  @TempDir Path temp;

  private Display display;
  private Workers workers;
  private MainWindow window;
  private ApplicationController controller;
  private final AtomicReference<Throwable> failure = new AtomicReference<Throwable>();
  private boolean posting;

  @BeforeEach
  void open() throws Exception {
    display = new Display();
    // A real pointer over the text sends a motion without a button, which ends a posted drag;
    // added first, this filter runs before the editor's and drops it.
    display.addFilter(
        SWT.MouseMove,
        new Listener() {
          @Override
          public void handleEvent(Event event) {
            if (!posting) {
              event.type = SWT.None;
            }
          }
        });
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
            Keymap.defaults(PlatformTraits.PLATFORM.macKeys(), PlatformTraits.PLATFORM.linuxKeys()),
            false);
    controller.setLogging(new RecentLog(50, 200), LogFiles.NONE);
    window.attach(controller);
    window.place(new WindowGeometry.Request(null, null, 700, 500), null);
    window.open();
    Path file = Files.writeString(temp.resolve("a.txt"), TEXT, StandardCharsets.UTF_8);
    controller.documents().open(List.of(file));
    assertTrue(
        EventLoop.runUntil(
            display,
            new BooleanSupplier() {
              @Override
              public boolean getAsBoolean() {
                return window.tabs().getItemCount() == 1 && controller.documents().loading() == 0;
              }
            },
            BOUND),
        "opened a.txt");
    drain();
  }

  @AfterEach
  void close() {
    try {
      controller.documents().dispose();
      workers.close();
      display.dispose();
    } finally {
      assertNull(failure.get());
    }
  }

  private void drain() {
    while (display.readAndDispatch()) {
      // Runs queued work.
    }
  }

  private EditorController editor() {
    return controller.documents().activeEditor();
  }

  private Point at(int offset) {
    Point point = window.activeText().getLocationAtOffset(offset);
    return new Point(point.x + 1, point.y + 2);
  }

  private void mouse(int type, int offset, int stateMask) {
    Point point = at(offset);
    Event event = new Event();
    event.button = type == SWT.MouseMove ? 0 : 1;
    event.count = 1;
    event.x = point.x;
    event.y = point.y;
    event.stateMask = stateMask;
    posting = true;
    try {
      window.activeText().notifyListeners(type, event);
    } finally {
      posting = false;
    }
    drain();
  }

  /** Presses at the first offset, moves through the rest with a dispatch after each, releases. */
  private void drag(int... offsets) {
    mouse(SWT.MouseDown, offsets[0], 0);
    for (int i = 1; i < offsets.length; i++) {
      mouse(SWT.MouseMove, offsets[i], SWT.BUTTON1);
    }
    mouse(SWT.MouseUp, offsets[offsets.length - 1], SWT.BUTTON1);
  }

  private void key(int keyCode, int stateMask) {
    Event event = new Event();
    event.keyCode = keyCode;
    event.stateMask = stateMask;
    window.activeText().notifyListeners(SWT.KeyDown, event);
    drain();
  }

  /** Asserts the controller's only selection and the widget's selection and caret. */
  private void assertSelected(int anchor, int caret) {
    List<Selection> selections = editor().selections().selections().selections();
    assertEquals(1, selections.size());
    assertEquals(anchor, selections.get(0).anchor());
    assertEquals(caret, selections.get(0).caret());
    StyledText text = window.activeText();
    assertEquals(new Point(Math.min(anchor, caret), Math.max(anchor, caret)), text.getSelection());
    assertEquals(caret, text.getCaretOffset());
  }

  @Test
  void anUpwardDragAcrossLinesKeepsItsAnchor() {
    drag(15, 8, 2);
    assertSelected(15, 2);
  }

  @Test
  void aDownwardDragAcrossLinesKeepsItsAnchor() {
    drag(2, 8, 15);
    assertSelected(2, 15);
  }

  @Test
  void anUpwardDragWithinALineKeepsItsAnchor() {
    drag(17, 15, 13);
    assertSelected(17, 13);
  }

  @Test
  void aDragBackOntoItsAnchorAndPastItSelectsFromTheAnchor() {
    drag(15, 8, 15, 18);
    assertSelected(15, 18);
  }

  @Test
  void aShiftClickBeforeTheCaretSelectsBackToIt() {
    editor().selections().set(SelectionSet.caret(15));
    drain();
    mouse(SWT.MouseDown, 2, SWT.SHIFT);
    mouse(SWT.MouseUp, 2, SWT.SHIFT | SWT.BUTTON1);
    assertSelected(15, 2);
  }

  @Test
  void shiftLeftExtendsBackwardOverSeveralSteps() {
    editor().selections().set(SelectionSet.caret(15));
    drain();
    for (int i = 0; i < 4; i++) {
      key(SWT.ARROW_LEFT, SWT.SHIFT);
    }
    assertSelected(15, 11);
  }

  @Test
  void shiftUpExtendsBackwardOverSeveralLines() {
    editor().selections().set(SelectionSet.caret(15));
    drain();
    key(SWT.ARROW_UP, SWT.SHIFT);
    key(SWT.ARROW_UP, SWT.SHIFT);
    assertSelected(15, 3);
  }
}
