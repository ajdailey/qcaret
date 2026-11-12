package io.github.ajdailey.qcaret.ui.swt;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertTrue;

import io.github.ajdailey.qcaret.app.about.TipList;
import io.github.ajdailey.qcaret.app.command.CommandId;
import io.github.ajdailey.qcaret.app.command.Keymap;
import io.github.ajdailey.qcaret.app.config.ConfigStore;
import io.github.ajdailey.qcaret.app.config.Preferences;
import io.github.ajdailey.qcaret.app.config.SettingsLocation;
import io.github.ajdailey.qcaret.app.config.SettingsMode;
import io.github.ajdailey.qcaret.app.controller.ApplicationController;
import io.github.ajdailey.qcaret.app.theme.Palette;
import io.github.ajdailey.qcaret.app.theme.ThemeMode;
import io.github.ajdailey.qcaret.app.view.WindowGeometry;
import io.github.ajdailey.qcaret.core.concurrent.MemoryBudget;
import io.github.ajdailey.qcaret.core.concurrent.Workers;
import io.github.ajdailey.qcaret.ui.swt.platform.PlatformTraits;
import java.nio.file.Path;
import java.util.concurrent.atomic.AtomicReference;
import org.eclipse.swt.SWT;
import org.eclipse.swt.widgets.Button;
import org.eclipse.swt.widgets.Display;
import org.eclipse.swt.widgets.Event;
import org.eclipse.swt.widgets.Shell;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/** Help > Tip of the Day in a real window: Next Tip, the check box, one dialog, Esc. */
@Tag("swt")
class TipDialogSurfaceTest {
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
    window.place(new WindowGeometry.Request(null, null, 800, 500), null);
    window.open();
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

  private static void select(Button button) {
    button.notifyListeners(SWT.Selection, new Event());
  }

  @Test
  void theDialogShowsTipsAndWritesTheCheckBox() {
    assertFalse(window.promptShown());
    controller.execute(CommandId.TIP_OF_THE_DAY);
    TipDialog dialog = window.tipDialog();
    Keymap keymap = controller.keymap();
    assertEquals(TipList.text(0, keymap), dialog.tip());
    assertFalse(dialog.startup().getSelection());
    assertFalse(window.promptShown(), "the dialog is modeless");
    assertEquals(dialog.shell().getImages().length, window.shell().getImages().length);

    select(dialog.next());
    assertEquals(TipList.text(1, keymap), dialog.tip());
    assertEquals(2, controller.preferences().tips().next());

    dialog.startup().setSelection(true);
    select(dialog.startup());
    assertTrue(controller.preferences().tips().onStartup());

    controller.execute(CommandId.TIP_OF_THE_DAY);
    assertSame(dialog, window.tipDialog());
    assertEquals(TipList.text(1, keymap), dialog.tip());

    Event escape = new Event();
    escape.detail = SWT.TRAVERSE_ESCAPE;
    dialog.shell().notifyListeners(SWT.Traverse, escape);
    assertTrue(dialog.shell().isDisposed());
    controller.execute(CommandId.TIP_OF_THE_DAY);
    assertTrue(window.tipDialog() != dialog);
    assertEquals(TipList.text(2, keymap), window.tipDialog().tip());
    assertTrue(window.tipDialog().startup().getSelection());
  }

  @Test
  void aModalDialogCountsAsAPrompt() {
    Shell modal = Dialogs.shell(window.shell(), "mock", true);
    modal.open();
    assertTrue(window.promptShown());
    modal.dispose();
    assertFalse(window.promptShown());
  }
}
