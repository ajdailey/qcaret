package io.github.ajdailey.qcaret.app.controller;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import io.github.ajdailey.qcaret.app.command.Keymap;
import io.github.ajdailey.qcaret.app.config.SettingsLocation;
import io.github.ajdailey.qcaret.app.config.SettingsMode;
import io.github.ajdailey.qcaret.app.i18n.UiText;
import io.github.ajdailey.qcaret.app.view.ManualUiDispatcher;
import io.github.ajdailey.qcaret.core.concurrent.Workers;
import java.nio.file.Path;
import java.util.concurrent.atomic.AtomicReference;
import java.util.function.BooleanSupplier;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/** {@link HelpController} built from the warning state alone, without the window set. */
class HelpControllerTest {
  @TempDir Path temp;

  @Test
  void showLogReadsAndClearsTheWarningState() {
    ManualUiDispatcher ui = new ManualUiDispatcher();
    FakeView view = new FakeView(ui);
    final boolean[] warned = {true};
    final AtomicReference<Throwable> failure = new AtomicReference<Throwable>();
    try (Workers workers =
        new Workers(
            1,
            new Thread.UncaughtExceptionHandler() {
              @Override
              public void uncaughtException(Thread thread, Throwable error) {
                failure.set(error);
              }
            })) {
      HelpController help =
          new HelpController(
              workers,
              ui,
              view,
              new SettingsLocation(SettingsMode.EXPLICIT, temp),
              Keymap.defaults(false),
              new BooleanSupplier() {
                @Override
                public boolean getAsBoolean() {
                  return false;
                }
              },
              new BooleanSupplier() {
                @Override
                public boolean getAsBoolean() {
                  return warned[0];
                }
              },
              new Runnable() {
                @Override
                public void run() {
                  warned[0] = false;
                }
              });
      assertTrue(help.newWarnings());
      help.showLog();
      assertFalse(help.newWarnings());
      assertEquals(UiText.SHOW_LOG_EMPTY.text(), view.log);
    }
    assertNull(failure.get());
  }
}
