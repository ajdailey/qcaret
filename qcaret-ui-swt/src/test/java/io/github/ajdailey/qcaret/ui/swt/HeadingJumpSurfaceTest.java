package io.github.ajdailey.qcaret.ui.swt;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import io.github.ajdailey.qcaret.app.cli.FileRequest;
import io.github.ajdailey.qcaret.app.command.Keymap;
import io.github.ajdailey.qcaret.app.config.ConfigStore;
import io.github.ajdailey.qcaret.app.config.Preferences;
import io.github.ajdailey.qcaret.app.config.SettingsLocation;
import io.github.ajdailey.qcaret.app.config.SettingsMode;
import io.github.ajdailey.qcaret.app.controller.ApplicationController;
import io.github.ajdailey.qcaret.app.controller.EditorController;
import io.github.ajdailey.qcaret.app.theme.Palette;
import io.github.ajdailey.qcaret.app.theme.ThemeMode;
import io.github.ajdailey.qcaret.app.view.EditorView;
import io.github.ajdailey.qcaret.app.view.MarkdownViewMode;
import io.github.ajdailey.qcaret.app.view.WindowGeometry;
import io.github.ajdailey.qcaret.core.concurrent.MemoryBudget;
import io.github.ajdailey.qcaret.core.concurrent.Workers;
import io.github.ajdailey.qcaret.ui.swt.markdown.MarkdownPane;
import io.github.ajdailey.qcaret.ui.swt.platform.PlatformTraits;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
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
 * {@code file.md#heading} in a real window: the file opens in Split with the caret on the heading,
 * the heading line at the editor's top and the preview scrolled to it.
 */
@Tag("swt")
class HeadingJumpSurfaceTest {
  private static final Duration BOUND = Duration.ofSeconds(30);

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
    window.place(new WindowGeometry.Request(null, null, 1000, 600), null);
    window.open();
    controller.loadGrammars();
    await(
        new BooleanSupplier() {
          @Override
          public boolean getAsBoolean() {
            return controller.grammars().set() != null;
          }
        },
        "grammars");
  }

  @AfterEach
  void close() {
    try {
      controller.quit();
      EventLoop.runUntil(
          display,
          new BooleanSupplier() {
            @Override
            public boolean getAsBoolean() {
              return window.shell().isDisposed();
            }
          },
          BOUND);
      display.dispose();
    } finally {
      assertNull(failure.get());
    }
  }

  private void await(BooleanSupplier condition, String what) {
    assertTrue(EventLoop.runUntil(display, condition, BOUND), what);
  }

  private int caretLine() {
    EditorController editor = controller.documents().activeEditor();
    return editor.document().text().lineAt(editor.selections().selections().primary().caret());
  }

  private int topLine() {
    EditorController editor = controller.documents().activeEditor();
    EditorView view = editor.view();
    return view == null ? -1 : editor.projection().lineOfRow(view.topRow());
  }

  @Test
  void theHeadingLandsAtTheEditorTopAndThePreviewFollows() throws Exception {
    StringBuilder text = new StringBuilder("# Doc\n\n");
    for (int i = 1; i <= 15; i++) {
      text.append("## Section ").append(i).append("\n\n");
      for (int j = 0; j < 18; j++) {
        text.append("Line ").append(j).append(" of section ").append(i).append(".\n\n");
      }
    }
    Path file = Files.writeString(temp.resolve("long.md"), text, StandardCharsets.UTF_8);
    controller.documents().open(new FileRequest(file, 0, 0, false, null, "section-10"));
    // "# Doc" and a blank line, then 38 lines per section.
    final int line = 2 + 9 * 38;
    await(
        new BooleanSupplier() {
          @Override
          public boolean getAsBoolean() {
            return controller.documents().activeEditor() != null
                && caretLine() == line
                && topLine() == line;
          }
        },
        "the editor at the heading");
    assertEquals(MarkdownViewMode.SPLIT, controller.documents().markdownMode());
    await(
        new BooleanSupplier() {
          @Override
          public boolean getAsBoolean() {
            MarkdownPane pane = window.activePreview();
            if (pane == null) {
              return false;
            }
            StyledText preview = pane.text();
            return preview.getLine(preview.getTopIndex()).startsWith("Section 10");
          }
        },
        "the preview at Section 10");
    assertEquals(line, caretLine());
    assertEquals(line, topLine());
  }
}
