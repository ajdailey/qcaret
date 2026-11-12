package io.github.ajdailey.qcaret.launcher;

import io.github.ajdailey.qcaret.app.about.UserGuide;
import io.github.ajdailey.qcaret.app.cli.StartupRequest;
import io.github.ajdailey.qcaret.app.command.CommandId;
import io.github.ajdailey.qcaret.app.config.ConfigStore;
import io.github.ajdailey.qcaret.app.config.SettingsLocation;
import io.github.ajdailey.qcaret.app.controller.ApplicationController;
import io.github.ajdailey.qcaret.app.log.LogFiles;
import io.github.ajdailey.qcaret.core.concurrent.Workers;
import io.github.ajdailey.qcaret.ui.swt.MainWindow;
import java.io.PrintStream;
import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.concurrent.TimeUnit;
import org.eclipse.swt.widgets.Composite;
import org.eclipse.swt.widgets.Control;
import org.eclipse.swt.widgets.Display;
import org.eclipse.swt.widgets.Label;

/**
 * A child process for {@code PortableGuideTest}: a portable run over the bundle named by the first
 * argument runs User Guide once the window shows, then quits when the main window shows a notice
 * naming the guide's copy, a second shell opens, or 30 s pass. Prints {@link #OUTCOME} and {@link
 * #NOTICE}, {@link #WINDOW} or {@link #TIMEOUT}, and exits with the run's status.
 */
public final class PortableGuideMain {
  static final String OUTCOME = "outcome ";
  static final String NOTICE = "notice";
  static final String WINDOW = "window";
  static final String TIMEOUT = "timeout";

  private static final long BOUND_NANOS = TimeUnit.SECONDS.toNanos(30);

  private PortableGuideMain() {}

  /**
   * Runs the portable instance.
   *
   * @param args the bundle directory, which holds {@code qcaret.conf}
   */
  public static void main(String[] args) {
    String os = System.getProperty("os.name");
    SettingsLocation location =
        SettingsLocation.resolve(
            null, args[0], os, System.getenv(), System.getProperty("user.home"));
    PlatformSetup.apply(os, location);
    ConfigStore config = new ConfigStore(location);
    final String[] outcome = {TIMEOUT};
    Gui.Observer observer =
        new Gui.Observer() {
          @Override
          public void opened(
              final MainWindow window, final ApplicationController controller, Workers workers) {
            final Display display = window.shell().getDisplay();
            final long deadline = System.nanoTime() + BOUND_NANOS;
            display.timerExec(
                20,
                new Runnable() {
                  private boolean asked;

                  @Override
                  public void run() {
                    if (!asked && window.shell().isVisible()) {
                      asked = true;
                      controller.execute(CommandId.USER_GUIDE);
                    }
                    if (display.getShells().length > 1) {
                      outcome[0] = WINDOW;
                    } else if (asked && names(window.shell(), UserGuide.FILE)) {
                      outcome[0] = NOTICE;
                    }
                    if (!TIMEOUT.equals(outcome[0]) || System.nanoTime() > deadline) {
                      controller.quit();
                    } else {
                      display.timerExec(20, this);
                    }
                  }
                });
          }
        };
    StartupRequest request = new StartupRequest(List.of(), null, null, null, null, null, false);
    PrintStream err = new PrintStream(System.err, true, StandardCharsets.UTF_8);
    int status = new Gui(request, config, config.load(), err, observer, LogFiles.NONE).run();
    System.out.println(OUTCOME + outcome[0]);
    System.out.flush();
    System.exit(status);
  }

  /** Reports whether a label under the composite shows text containing the name. */
  private static boolean names(Composite parent, String name) {
    for (Control child : parent.getChildren()) {
      if (child instanceof Label label && label.getText().contains(name)) {
        return true;
      }
      if (child instanceof Composite composite && names(composite, name)) {
        return true;
      }
    }
    return false;
  }
}
