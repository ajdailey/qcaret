package io.github.ajdailey.qcaret.launcher;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import io.github.ajdailey.qcaret.app.cli.FileRequest;
import io.github.ajdailey.qcaret.app.cli.StartupRequest;
import io.github.ajdailey.qcaret.app.command.CommandId;
import io.github.ajdailey.qcaret.app.config.ConfigStore;
import io.github.ajdailey.qcaret.app.config.Preferences;
import io.github.ajdailey.qcaret.app.config.SettingsLocation;
import io.github.ajdailey.qcaret.app.config.SettingsMode;
import io.github.ajdailey.qcaret.app.controller.ApplicationController;
import io.github.ajdailey.qcaret.app.controller.WindowSet;
import io.github.ajdailey.qcaret.app.i18n.UiText;
import io.github.ajdailey.qcaret.app.log.LogFiles;
import io.github.ajdailey.qcaret.core.concurrent.Workers;
import io.github.ajdailey.qcaret.ui.swt.MainWindow;
import java.io.PrintStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.function.BooleanSupplier;
import org.eclipse.swt.widgets.Display;
import org.eclipse.swt.widgets.Shell;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/**
 * Quit with a dirty document in each of two main windows, answered in the real Save, Don't Save or
 * Cancel dialogs of the composed application.
 */
@Tag("swt")
class QuitPromptsGuiTest {
  private static final Duration BOUND = Duration.ofSeconds(20);

  @TempDir Path temp;

  private final List<String> asked = new ArrayList<String>();

  /** Waits for the next prompt, or for every window to close; returns the prompt or null. */
  private static Shell prompt(final Display display, final WindowSet set) {
    final String title = UiText.UNSAVED_TITLE.text();
    assertTrue(
        UiLoop.runUntil(
            display,
            new BooleanSupplier() {
              @Override
              public boolean getAsBoolean() {
                return !UiLoop.shells(display, title).isEmpty() || set.windows().isEmpty();
              }
            },
            BOUND),
        "no prompt and windows open");
    List<Shell> shown = UiLoop.shells(display, title);
    assertTrue(shown.size() <= 1, shown.size() + " prompts");
    return shown.isEmpty() ? null : shown.get(0);
  }

  private static void awaitLoaded(Display display, final ApplicationController window) {
    assertTrue(
        UiLoop.runUntil(
            display,
            new BooleanSupplier() {
              @Override
              public boolean getAsBoolean() {
                return window.documents().count() >= 1 && window.documents().loading() == 0;
              }
            },
            BOUND),
        "loaded");
  }

  /** Answers Cancel to the first prompt, then Save for a.txt and Don't Save for b.txt. */
  private void script(MainWindow main, ApplicationController first, Path b) {
    Display display = main.shell().getDisplay();
    WindowSet set = first.windowSet();
    awaitLoaded(display, first);
    first.documents().activeEditor().type("x");
    first.execute(CommandId.NEW_WINDOW);
    assertEquals(2, set.windows().size());
    ApplicationController second = set.windows().get(1);
    second.documents().open(List.of(b));
    assertTrue(
        UiLoop.runUntil(
            display,
            new BooleanSupplier() {
              @Override
              public boolean getAsBoolean() {
                return second.documents().loading() == 0
                    && second.documents().activeEditor().document().text().toString().equals("b\n");
              }
            },
            BOUND),
        "b.txt shown");
    second.documents().activeEditor().type("y");

    first.execute(CommandId.QUIT);
    Shell prompt = prompt(display, set);
    asked.add("cancel " + UiLoop.labels(prompt).strip());
    assertTrue(UiLoop.press(prompt, UiText.BUTTON_CANCEL.text()));
    assertTrue(prompt.isDisposed());
    assertFalse(set.isQuitting());
    assertEquals(2, set.windows().size());
    assertTrue(first.documents().activeEditor().document().isDirty());
    assertTrue(second.documents().activeEditor().document().isDirty());
    assertTrue(UiLoop.shells(display, UiText.UNSAVED_TITLE.text()).isEmpty());

    first.execute(CommandId.QUIT);
    prompt = prompt(display, set);
    while (prompt != null) {
      String label = UiLoop.labels(prompt).strip();
      boolean save = label.contains("a.txt");
      asked.add((save ? "save " : "discard ") + label);
      String button = save ? UiText.BUTTON_SAVE.text() : UiText.BUTTON_DONT_SAVE.text();
      assertTrue(UiLoop.press(prompt, button));
      prompt = prompt(display, set);
    }
  }

  @Test
  void aQuitAsksForEachWindowAndCancelSaveAndDontSaveTakeEffect() throws Exception {
    Path settings = Files.createDirectory(temp.resolve("settings"));
    Path a = Files.writeString(temp.resolve("a.txt"), "a\n");
    final Path b = Files.writeString(temp.resolve("b.txt"), "b\n");
    Gui.Observer observer =
        new Gui.Observer() {
          @Override
          public void opened(MainWindow window, ApplicationController first, Workers workers) {
            // A failed assertion leaves the event loop, and Gui.run ends with it.
            script(window, first, b);
          }
        };
    StartupRequest request =
        new StartupRequest(List.of(new FileRequest(a)), 800, 500, null, null, null, false);
    PrintStream err = new PrintStream(System.err, true, StandardCharsets.UTF_8);
    assertEquals(
        0,
        new Gui(
                request,
                new ConfigStore(new SettingsLocation(SettingsMode.EXPLICIT, settings)),
                Preferences.DEFAULTS,
                err,
                observer,
                LogFiles.NONE)
            .run());
    assertEquals(3, asked.size(), asked.toString());
    assertTrue(asked.get(0).startsWith("cancel "), asked.toString());
    List<String> answers = List.of(asked.get(1).split(" ")[0], asked.get(2).split(" ")[0]);
    assertTrue(answers.contains("save") && answers.contains("discard"), asked.toString());
    assertEquals("xa\n", Files.readString(a));
    assertEquals("b\n", Files.readString(b));
  }
}
