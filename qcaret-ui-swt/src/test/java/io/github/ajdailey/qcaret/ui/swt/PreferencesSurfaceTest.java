package io.github.ajdailey.qcaret.ui.swt;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assumptions.assumeTrue;

import io.github.ajdailey.qcaret.app.command.CommandId;
import io.github.ajdailey.qcaret.app.command.KeyStroke;
import io.github.ajdailey.qcaret.app.command.Keymap;
import io.github.ajdailey.qcaret.app.config.ConfigStore;
import io.github.ajdailey.qcaret.app.config.Preferences;
import io.github.ajdailey.qcaret.app.config.SettingsLocation;
import io.github.ajdailey.qcaret.app.config.SettingsMode;
import io.github.ajdailey.qcaret.app.controller.ApplicationController;
import io.github.ajdailey.qcaret.app.i18n.UiText;
import io.github.ajdailey.qcaret.app.theme.Palette;
import io.github.ajdailey.qcaret.app.theme.ThemeMode;
import io.github.ajdailey.qcaret.core.concurrent.MemoryBudget;
import io.github.ajdailey.qcaret.core.concurrent.Workers;
import io.github.ajdailey.qcaret.ui.swt.editor.SwtKeys;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.List;
import java.util.concurrent.atomic.AtomicReference;
import java.util.function.BooleanSupplier;
import org.eclipse.swt.SWT;
import org.eclipse.swt.graphics.GC;
import org.eclipse.swt.graphics.Image;
import org.eclipse.swt.graphics.ImageData;
import org.eclipse.swt.graphics.ImageLoader;
import org.eclipse.swt.graphics.Rectangle;
import org.eclipse.swt.widgets.Combo;
import org.eclipse.swt.widgets.Display;
import org.eclipse.swt.widgets.MenuItem;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/**
 * The Preferences dialog on a real window: validation, Apply, Cancel and Restore Defaults, the Keys
 * tab, a theme switch while it shows, its remembered size. With {@code -Dqcaret.screenshots=<dir>},
 * each group as a PNG.
 */
@Tag("swt")
class PreferencesSurfaceTest {
  private static final Duration BOUND = Duration.ofSeconds(10);

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
            new ConfigStore(new SettingsLocation(SettingsMode.EXPLICIT, temp)),
            Preferences.DEFAULTS,
            SwtThemeService.open(display, ThemeMode.LIGHT),
            new MemoryBudget(1L << 30, 60),
            Keymap.defaults(SwtKeys.mac(), SwtKeys.linux()),
            false);
    window.attach(controller);
    controller.documents().newDocument();
    window.open();
  }

  @AfterEach
  void close() {
    try {
      display.setDarkThemePreferred(false);
      workers.close();
      display.dispose();
    } finally {
      assertNull(failure.get());
    }
  }

  private PreferencesDialog dialog() {
    controller.execute(CommandId.PREFERENCES);
    assertTrue(
        EventLoop.runUntil(
            display,
            new BooleanSupplier() {
              @Override
              public boolean getAsBoolean() {
                return window.preferencesDialog() != null
                    && !window.preferencesDialog().shell().isDisposed();
              }
            },
            BOUND));
    return window.preferencesDialog();
  }

  @Test
  void anInvalidFieldIsNamedAndTheDialogStaysOnItsTab() {
    PreferencesDialog dialog = dialog();
    dialog.pollSeconds().setText("0");
    assertFalse(dialog.apply());
    assertEquals(1, dialog.folder().getSelectionIndex());
    assertTrue(
        dialog.message().getText().startsWith("Seconds between"), dialog.message().getText());
    assertFalse(dialog.shell().isDisposed());
    assertEquals(5, controller.preferences().options().pollSeconds());
    dialog.pollSeconds().setText("9");
    assertTrue(dialog.apply());
    assertEquals("", dialog.message().getText());
    assertEquals(9, controller.preferences().options().pollSeconds());
    dialog.shell().dispose();
  }

  @Test
  void restoreDefaultsChangesNothingUntilApplied() {
    PreferencesDialog dialog = dialog();
    dialog.pollSeconds().setText("9");
    assertTrue(dialog.apply());
    dialog.restoreDefaults();
    assertEquals("5", dialog.pollSeconds().getText());
    assertEquals(9, controller.preferences().options().pollSeconds());
    dialog.shell().dispose();
    assertEquals(9, controller.preferences().options().pollSeconds());
  }

  @Test
  void clearHistoryAsksThenClearsAtOnceAndAnOpenFindBarFollows() {
    Preferences current = controller.settings().current();
    controller
        .settings()
        .set(
            current.withState(
                current
                    .state()
                    .withHistory(List.of("needle"), List.of("/mock"), List.of("*.mock"))
                    .withReplacements(List.of("thread"))));
    controller.execute(CommandId.REPLACE);
    final Combo field = window.findBar().field();
    field.setText("typed");
    assertArrayEquals(new String[] {"needle"}, field.getItems());
    PreferencesDialog dialog = dialog();
    ClearHistoryDialog prompt = dialog.askClearHistory();
    prompt.cancel();
    assertTrue(prompt.shell().isDisposed());
    assertEquals(1, field.getItemCount());
    dialog.askClearHistory().clear();
    assertTrue(
        EventLoop.runUntil(
            display,
            new BooleanSupplier() {
              @Override
              public boolean getAsBoolean() {
                return field.getItemCount() == 0;
              }
            },
            BOUND));
    assertEquals(0, window.findBar().replaceField().getItemCount());
    assertEquals("typed", field.getText());
    dialog.shell().close();
    assertEquals(List.of(), controller.settings().current().state().texts());
    assertEquals(List.of(), controller.settings().current().state().directories());
    assertEquals(List.of(), controller.settings().current().state().masks());
    assertEquals(List.of(), controller.settings().current().state().replacements());
  }

  private boolean fileHasHistory() {
    try {
      Path conf = temp.resolve("qcaret.conf");
      return Files.exists(conf) && Files.readString(conf).contains("search.history.text.0=needle");
    } catch (IOException e) {
      return false;
    }
  }

  /**
   * A directory at {@code qcaret.conf} fails the read and the write without making the settings
   * directory read-only, which would turn saving off for the process.
   */
  @Test
  void aClearHistoryTheFileRefusesSaysSoKeepsTheSettingsAndARetryClearsIt() throws Exception {
    Preferences current = controller.settings().current();
    controller
        .settings()
        .set(
            current
                .withOptions(current.options().withCompletionChars(3))
                .withState(current.state().withHistory(List.of("needle"), List.of(), List.of())));
    await(
        new BooleanSupplier() {
          @Override
          public boolean getAsBoolean() {
            return fileHasHistory();
          }
        });
    PreferencesDialog dialog = dialog();
    Path conf = temp.resolve("qcaret.conf");
    byte[] saved = Files.readAllBytes(conf);
    Files.delete(conf);
    Path blocker = Files.createDirectory(conf).resolve("blocker");
    Files.writeString(blocker, "x");
    dialog.askClearHistory().clear();
    await(
        new BooleanSupplier() {
          @Override
          public boolean getAsBoolean() {
            return UiText.PREFERENCES_HISTORY_NOT_REMOVED.text().equals(window.noticeText());
          }
        });
    assertEquals(3, controller.preferences().options().completionChars());
    assertEquals(List.of(), controller.settings().current().state().texts());
    Files.delete(blocker);
    Files.delete(conf);
    Files.write(conf, saved);
    window.showNotice("");
    dialog.askClearHistory().clear();
    await(
        new BooleanSupplier() {
          @Override
          public boolean getAsBoolean() {
            return !fileHasHistory();
          }
        });
    assertEquals(List.of(), controller.settings().current().state().texts());
    assertEquals(3, controller.preferences().options().completionChars());
    assertEquals("", window.noticeText());
    dialog.shell().dispose();
  }

  private void await(BooleanSupplier condition) {
    assertTrue(EventLoop.runUntil(display, condition, BOUND), window.noticeText());
  }

  @Test
  void aSecondOpenRaisesTheSameDialog() {
    PreferencesDialog first = dialog();
    assertSame(first, dialog());
    first.shell().dispose();
  }

  @Test
  void aKeyIsRefusedWhenItTypesOrIsTakenAndTheCommandTakesAFreeOne() {
    PreferencesDialog dialog = dialog();
    dialog.folder().setSelection(4);
    dialog.select(CommandId.COMPARE_DOCUMENTS);
    dialog.record(new KeyStroke(0, 'k'));
    dialog.addCaptured();
    assertTrue(dialog.keyMessage().getText().contains("types text"));
    dialog.record(new KeyStroke(KeyStroke.PRIMARY, 's'));
    dialog.addCaptured();
    assertTrue(dialog.keyMessage().getText().contains("Save"), dialog.keyMessage().getText());
    KeyStroke free = new KeyStroke(KeyStroke.PRIMARY | KeyStroke.ALT, 'k');
    dialog.record(free);
    dialog.addCaptured();
    assertEquals("", dialog.keyMessage().getText());
    assertTrue(dialog.apply());
    assertEquals(free, controller.keymap().accelerator(CommandId.COMPARE_DOCUMENTS));
    MenuItem item = window.menuBar().item(CommandId.COMPARE_DOCUMENTS);
    assertEquals(SwtKeys.accelerator(free), item.getAccelerator());
    assertTrue(item.getText().contains(free.text(SwtKeys.mac())), item.getText());
    dialog.select(CommandId.COMPARE_DOCUMENTS);
    dialog.selectKey(0);
    dialog.removeSelected();
    assertTrue(dialog.apply());
    assertNull(controller.keymap().accelerator(CommandId.COMPARE_DOCUMENTS));
    dialog.shell().dispose();
  }

  @Test
  void aThemeSwitchLeavesTheDialogWorking() {
    PreferencesDialog dialog = dialog();
    dialog.theme().select(ThemeMode.DARK.ordinal());
    assertTrue(dialog.apply());
    assertSame(Palette.DARK, window.palette());
    controller.execute(CommandId.THEME_LIGHT);
    assertSame(Palette.LIGHT, window.palette());
    assertFalse(dialog.shell().isDisposed());
    dialog.restoreDefaults();
    assertTrue(dialog.apply());
    assertSame(Palette.LIGHT, window.palette());
    dialog.shell().dispose();
  }

  @Test
  void aNewSizeIsRememberedAtClose() {
    PreferencesDialog dialog = dialog();
    dialog.shell().setSize(700, 560);
    final int width = dialog.shell().getSize().x;
    dialog.shell().dispose();
    assertNotNull(controller.dialogSize("preferences"));
    assertEquals(width, controller.dialogSize("preferences").width());
    PreferencesDialog again = dialog();
    assertEquals(width, again.shell().getSize().x);
    again.shell().dispose();
  }

  @Test
  void screenshotsWhenAsked() {
    String dir = System.getProperty("qcaret.screenshots");
    assumeTrue(dir != null, "qcaret.screenshots not set");
    PreferencesDialog dialog = dialog();
    dialog.shell().forceActive();
    String[] names = {"editor", "files", "search", "appearance", "keys"};
    for (int i = 0; i < names.length; i++) {
      dialog.folder().setSelection(i);
      capture(dir, "preferences-" + names[i]);
    }
    dialog.folder().setSelection(1);
    dialog.pollSeconds().setText("0");
    dialog.apply();
    capture(dir, "preferences-invalid");
    dialog.shell().dispose();
  }

  private void pause(int millis) {
    final long until = System.nanoTime() + millis * 1_000_000L;
    EventLoop.runUntil(
        display,
        new BooleanSupplier() {
          @Override
          public boolean getAsBoolean() {
            return System.nanoTime() > until;
          }
        },
        BOUND);
  }

  /** Copies the screen to a PNG. */
  private void capture(String dir, String name) {
    pause(700);
    Rectangle bounds = display.getBounds();
    Image image = new Image(display, bounds.width, bounds.height);
    GC gc = new GC(display);
    try {
      gc.copyArea(image, bounds.x, bounds.y);
    } finally {
      gc.dispose();
    }
    ImageLoader loader = new ImageLoader();
    loader.data = new ImageData[] {image.getImageData()};
    loader.save(Path.of(dir, name + ".png").toString(), SWT.IMAGE_PNG);
    image.dispose();
  }
}
