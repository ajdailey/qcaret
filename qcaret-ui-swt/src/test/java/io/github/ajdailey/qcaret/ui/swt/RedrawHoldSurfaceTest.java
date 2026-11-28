package io.github.ajdailey.qcaret.ui.swt;

import static org.junit.jupiter.api.Assertions.assertEquals;
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
import io.github.ajdailey.qcaret.app.theme.Palette;
import io.github.ajdailey.qcaret.app.theme.ThemeMode;
import io.github.ajdailey.qcaret.app.view.WindowGeometry;
import io.github.ajdailey.qcaret.core.concurrent.MemoryBudget;
import io.github.ajdailey.qcaret.core.concurrent.Workers;
import io.github.ajdailey.qcaret.ui.swt.editor.EditorPane;
import io.github.ajdailey.qcaret.ui.swt.platform.PlatformTraits;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.List;
import java.util.concurrent.atomic.AtomicReference;
import java.util.function.BooleanSupplier;
import org.eclipse.swt.custom.StyledText;
import org.eclipse.swt.widgets.Display;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/**
 * The editor pane's redraw hold: a held pane takes text changes but leaves the widget's caret where
 * it was, and the release moves it to the model's caret.
 */
@Tag("swt")
class RedrawHoldSurfaceTest {
  private static final Duration BOUND = Duration.ofSeconds(60);
  private static final String TEXT = "first line\nsecond line\nthird line\n";

  @TempDir Path temp;

  private Display display;
  private Workers workers;
  private MainWindow window;
  private ApplicationController controller;
  private final AtomicReference<Throwable> failure = new AtomicReference<Throwable>();

  @BeforeEach
  void open() {
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
            Keymap.defaults(PlatformTraits.PLATFORM.macKeys()),
            false);
    window.attach(controller);
    window.place(new WindowGeometry.Request(null, null, 900, 400), null);
    window.open();
  }

  @AfterEach
  void close() {
    try {
      controller.documents().dispose();
      workers.close();
    } finally {
      display.dispose();
      assertNull(failure.get());
    }
  }

  @Test
  void aHeldPaneSyncsTheCaretOnRelease() throws Exception {
    Path file = Files.writeString(temp.resolve("hold.txt"), TEXT, StandardCharsets.UTF_8);
    controller.documents().open(List.of(file));
    assertTrue(
        EventLoop.runUntil(
            display,
            new BooleanSupplier() {
              @Override
              public boolean getAsBoolean() {
                return window.tabs().getItemCount() == 1
                    && controller.documents().loading() == 0
                    && window.activeText().getSize().x > 0;
              }
            },
            BOUND),
        "opened");
    EditorPane pane = window.activeEditor();
    StyledText text = pane.text();
    EditorController editor = controller.documents().activeEditor();
    assertEquals(0, text.getCaretOffset());

    pane.holdRedraw(true);
    editor.execute(CommandId.DOCUMENT_END);
    editor.type("x");
    assertEquals(TEXT + "x", text.getText(), "a held pane takes the text change");
    assertEquals(0, text.getCaretOffset(), "a held pane leaves the widget's caret");

    pane.holdRedraw(false);
    assertEquals(TEXT.length() + 1, text.getCaretOffset());
  }
}
