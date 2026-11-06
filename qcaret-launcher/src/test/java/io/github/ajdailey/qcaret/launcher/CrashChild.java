package io.github.ajdailey.qcaret.launcher;

import io.github.ajdailey.qcaret.app.cli.StartupRequest;
import io.github.ajdailey.qcaret.app.config.ConfigStore;
import io.github.ajdailey.qcaret.app.config.SettingsLocation;
import io.github.ajdailey.qcaret.app.config.SettingsMode;
import io.github.ajdailey.qcaret.app.controller.ApplicationController;
import io.github.ajdailey.qcaret.app.log.LogFiles;
import io.github.ajdailey.qcaret.core.concurrent.Workers;
import io.github.ajdailey.qcaret.ui.swt.MainWindow;
import java.io.PrintStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.util.List;

/**
 * The process {@link AbnormalShutdownTest} kills: QCaret on a settings directory, with text typed
 * into the untitled document. It runs until the test ends it, and exits 1 if the event loop ends.
 */
public final class CrashChild {
  private CrashChild() {}

  /**
   * Runs the application.
   *
   * @param args the settings directory, then the text to type
   */
  public static void main(String[] args) {
    Path settings = Path.of(args[0]);
    final String text = args[1];
    ConfigStore config = new ConfigStore(new SettingsLocation(SettingsMode.EXPLICIT, settings));
    StartupRequest request = new StartupRequest(List.of(), 800, 500, null, null, null, false);
    Gui.Observer observer =
        new Gui.Observer() {
          @Override
          public void opened(MainWindow window, ApplicationController controller, Workers workers) {
            controller.documents().activeEditor().type(text);
          }
        };
    PrintStream err = new PrintStream(System.err, true, StandardCharsets.UTF_8);
    new Gui(request, config, config.load(), err, observer, LogFiles.NONE).run();
    System.exit(1);
  }
}
