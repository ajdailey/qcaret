package io.github.ajdailey.qcaret.ui.swt;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertTrue;

import io.github.ajdailey.qcaret.app.command.CommandId;
import io.github.ajdailey.qcaret.app.command.Keymap;
import io.github.ajdailey.qcaret.app.config.ConfigStore;
import io.github.ajdailey.qcaret.app.config.Preferences;
import io.github.ajdailey.qcaret.app.config.SettingsLocation;
import io.github.ajdailey.qcaret.app.config.SettingsMode;
import io.github.ajdailey.qcaret.app.controller.ApplicationController;
import io.github.ajdailey.qcaret.app.controller.WindowSet;
import io.github.ajdailey.qcaret.app.theme.Palette;
import io.github.ajdailey.qcaret.app.theme.ThemeMode;
import io.github.ajdailey.qcaret.app.view.ChangeChoice;
import io.github.ajdailey.qcaret.app.view.QuickOpenInput;
import io.github.ajdailey.qcaret.app.view.QuickOpenRow;
import io.github.ajdailey.qcaret.app.view.WindowGeometry;
import io.github.ajdailey.qcaret.core.concurrent.MemoryBudget;
import io.github.ajdailey.qcaret.core.concurrent.Workers;
import io.github.ajdailey.qcaret.ui.swt.platform.PlatformTraits;
import java.nio.file.Path;
import java.time.Duration;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.function.BooleanSupplier;
import java.util.function.Consumer;
import java.util.function.IntConsumer;
import org.eclipse.swt.SWT;
import org.eclipse.swt.widgets.Display;
import org.eclipse.swt.widgets.Shell;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/**
 * The main window's prompts and tool windows under Xvfb: the tab width and reload prompts, the
 * windows a second request reuses or replaces, and the answers prompts give once the shell is
 * disposed.
 */
@Tag("swt")
class WindowDialogsGuiTest {
  private static final Duration BOUND = Duration.ofSeconds(20);

  private static final Runnable NOTHING =
      new Runnable() {
        @Override
        public void run() {
          // No answer.
        }
      };

  @TempDir Path temp;

  private final List<Throwable> failures = new CopyOnWriteArrayList<Throwable>();
  private Display display;
  private Workers workers;
  private WindowSet set;
  private MainWindow window;
  private ApplicationController controller;

  @BeforeEach
  void open() {
    display = new Display();
    workers =
        new Workers(
            2,
            new Thread.UncaughtExceptionHandler() {
              @Override
              public void uncaughtException(Thread thread, Throwable error) {
                failures.add(error);
              }
            });
    set =
        new WindowSet(
            workers,
            new SwtUiDispatcher(display),
            new ConfigStore(new SettingsLocation(SettingsMode.EXPLICIT, temp.resolve("conf"))),
            Preferences.DEFAULTS,
            SwtThemeService.open(display, ThemeMode.LIGHT),
            new MemoryBudget(Runtime.getRuntime().maxMemory(), 60),
            Keymap.defaults(PlatformTraits.PLATFORM.macKeys()),
            false);
    window = new MainWindow(display, Palette.LIGHT);
    controller = new ApplicationController(set, window);
    window.attach(controller);
    window.place(new WindowGeometry.Request(0, 0, 620, 420), null);
    window.open();
  }

  @AfterEach
  void close() {
    set.quit();
    runUntil(
        new BooleanSupplier() {
          @Override
          public boolean getAsBoolean() {
            return window.shell().isDisposed();
          }
        });
    display.dispose();
    assertTrue(failures.isEmpty(), failures.toString());
  }

  private boolean runUntil(BooleanSupplier condition) {
    return EventLoop.runUntil(display, condition, BOUND);
  }

  /** Disposes every modal dialog over the window. */
  private void closePrompts() {
    for (Shell child : window.shell().getShells()) {
      if ((child.getStyle() & SWT.APPLICATION_MODAL) != 0) {
        child.dispose();
      }
    }
  }

  private boolean promptShown() {
    return runUntil(
        new BooleanSupplier() {
          @Override
          public boolean getAsBoolean() {
            return window.promptShown();
          }
        });
  }

  @Test
  void theTabWidthAndReloadPromptsOpenModal() {
    window.askTabWidth(
        4,
        16,
        new IntConsumer() {
          @Override
          public void accept(int width) {
            // Not answered.
          }
        });
    assertTrue(promptShown());
    closePrompts();
    assertFalse(window.promptShown());
    window.askReload("first.txt", NOTHING, NOTHING);
    assertTrue(promptShown());
    closePrompts();
    assertFalse(window.promptShown());
  }

  @Test
  void aSecondRequestReusesPreferencesAndShortcutsAndReplacesThePopups() {
    controller.execute(CommandId.PREFERENCES);
    assertTrue(
        runUntil(
            new BooleanSupplier() {
              @Override
              public boolean getAsBoolean() {
                return window.preferencesDialog() != null;
              }
            }));
    PreferencesDialog preferences = window.preferencesDialog();
    controller.execute(CommandId.PREFERENCES);
    assertSame(preferences, window.preferencesDialog());
    assertFalse(preferences.shell().isDisposed());
    preferences.shell().dispose();

    controller.execute(CommandId.KEYBOARD_SHORTCUTS);
    ShortcutsWindow shortcuts = window.shortcutsWindow();
    assertNotNull(shortcuts);
    window.setKeymap(Keymap.defaults(PlatformTraits.PLATFORM.macKeys()));
    controller.execute(CommandId.KEYBOARD_SHORTCUTS);
    assertSame(shortcuts, window.shortcutsWindow());
    assertFalse(shortcuts.shell().isDisposed());
    shortcuts.shell().dispose();

    controller.execute(CommandId.COMMAND_PALETTE);
    CommandPaletteWindow palette = window.commandPalette();
    controller.execute(CommandId.COMMAND_PALETTE);
    assertTrue(palette.shell().isDisposed());
    window.commandPalette().shell().dispose();

    controller.execute(CommandId.QUICK_OPEN);
    QuickOpenWindow quickOpen = window.quickOpen();
    controller.execute(CommandId.QUICK_OPEN);
    assertTrue(quickOpen.shell().isDisposed());
    window.quickOpen().shell().dispose();

    controller.execute(CommandId.HYPERSEARCH);
    SearchDialog search = window.searchDialog();
    controller.execute(CommandId.HYPERSEARCH);
    assertTrue(search.shell().isDisposed());
    window.searchDialog().shell().dispose();
  }

  @Test
  void promptsAfterTheShellIsDisposedAnswerWithoutShowing() {
    MainWindow closed = new MainWindow(display, Palette.LIGHT);
    closed.dispose();
    final List<Object> answers = new ArrayList<Object>();
    closed.askReload(
        "first.txt",
        NOTHING,
        new Runnable() {
          @Override
          public void run() {
            answers.add("declined");
          }
        });
    Consumer<Object> record =
        new Consumer<Object>() {
          @Override
          public void accept(Object answer) {
            answers.add(answer);
          }
        };
    closed.askFileName("Rename", "Name", "first.txt", 5, record::accept);
    closed.askExternalChange("first.txt", true, record::accept);
    closed.askExternalChange("first.txt", false, record::accept);
    Runnable dismiss =
        closed.askUnfinishedCopies(
            List.of("copy.txt"),
            new Runnable() {
              @Override
              public void run() {
                answers.add("quit");
              }
            },
            NOTHING);
    dismiss.run();
    closed.showQuickOpen(
        new QuickOpenInput() {
          @Override
          public String title() {
            return "";
          }

          @Override
          public void filter(String query) {
            // Not filtered.
          }

          @Override
          public List<QuickOpenRow> rows() {
            return List.of();
          }

          @Override
          public String summary() {
            return "";
          }

          @Override
          public void choose(int index) {
            // Not chosen.
          }

          @Override
          public void close() {
            answers.add("closed");
          }

          @Override
          public String initialQuery() {
            return "";
          }

          @Override
          public String detailHeading() {
            return "";
          }
        });
    assertEquals(
        Arrays.asList("declined", null, ChangeChoice.CANCEL, ChangeChoice.KEEP, "quit", "closed"),
        answers);
    assertSame(TipDialog.NONE, closed.showTips(null));
    closed.showComparison(null);
    closed.showReplacePreview(null);
    closed.showReplaceReport(null);
    closed.showSearchDialog(null, null);
    closed.showCommandPalette(null);
    closed.showPreferences(null);
    closed.showKeyboardShortcuts(null);
    assertTrue(closed.diffWindows().isEmpty());
    assertTrue(closed.reportWindows().isEmpty());
    assertNull(closed.replaceDialog());
    assertNull(closed.searchDialog());
    assertNull(closed.commandPalette());
    assertNull(closed.preferencesDialog());
    assertNull(closed.shortcutsWindow());
    assertNull(closed.quickOpen());
    assertNull(closed.fileNameDialog());
    assertNull(closed.changeDialog());
  }
}
