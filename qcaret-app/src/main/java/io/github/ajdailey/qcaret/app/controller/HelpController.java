package io.github.ajdailey.qcaret.app.controller;

import io.github.ajdailey.qcaret.app.about.AboutInfo;
import io.github.ajdailey.qcaret.app.about.UserGuide;
import io.github.ajdailey.qcaret.app.cli.CommandLineParser;
import io.github.ajdailey.qcaret.app.command.Keymap;
import io.github.ajdailey.qcaret.app.config.SettingsLocation;
import io.github.ajdailey.qcaret.app.i18n.UiText;
import io.github.ajdailey.qcaret.app.log.LogFiles;
import io.github.ajdailey.qcaret.app.log.RecentLog;
import io.github.ajdailey.qcaret.app.view.MainView;
import io.github.ajdailey.qcaret.app.view.UiDispatcher;
import io.github.ajdailey.qcaret.core.concurrent.Workers;
import io.github.ajdailey.qcaret.core.io.SafePaths;
import io.github.ajdailey.qcaret.core.text.LogText;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.function.BooleanSupplier;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * The main window's Help commands: Keyboard Shortcuts, Command-Line Options, the user guide, Show
 * Log and its warning marker, Debug Log File, the log and settings folders, Report an Issue, About
 * and the license notices. Confined to the UI thread; each file or jar read runs on a {@code
 * qcaret-lifecycle-} worker, and its result is shown only while the window is open. Report an Issue
 * is the only command that opens a web address, and only when the user runs it.
 */
final class HelpController {
  private static final Logger LOG = LoggerFactory.getLogger(HelpController.class);

  private final Workers workers;
  private final UiDispatcher ui;
  private final MainView view;
  private final SettingsLocation location;
  private Keymap keymap;
  private final BooleanSupplier closing;
  private final WindowSet set;
  private RecentLog recent;
  private LogFiles files = LogFiles.NONE;

  /**
   * Creates the Help commands of a window.
   *
   * @param workers runs file and jar reads
   * @param ui receives their results
   * @param view the window
   * @param location the settings directory
   * @param keymap the bindings Keyboard Shortcuts lists
   * @param closing true once the window is closing; results arriving later are dropped
   * @param set holds which warnings Show Log has shown
   */
  HelpController(
      Workers workers,
      UiDispatcher ui,
      MainView view,
      SettingsLocation location,
      Keymap keymap,
      BooleanSupplier closing,
      WindowSet set) {
    this.workers = workers;
    this.ui = ui;
    this.view = view;
    this.location = location;
    this.keymap = keymap;
    this.closing = closing;
    this.set = set;
  }

  /** Sets the bindings Keyboard Shortcuts lists. */
  void setKeymap(Keymap value) {
    keymap = value;
  }

  /**
   * Connects Show Log to the recent log lines and Debug Log File to the log file.
   *
   * @param lines the recent lines
   * @param logFiles the debug log file
   */
  void setLogging(RecentLog lines, LogFiles logFiles) {
    recent = lines;
    files = logFiles;
  }

  /**
   * Reports whether a warning arrived after Show Log last opened.
   *
   * @return true when the marker shows
   */
  boolean newWarnings() {
    return set.newWarnings();
  }

  /**
   * Reports whether the log goes to the debug log file.
   *
   * @return true while the file is attached
   */
  boolean debugLogFile() {
    return files.attached();
  }

  /** Shows the key bindings. */
  void showShortcuts() {
    view.showKeyboardShortcuts(keymap);
  }

  /** Shows the command-line usage. */
  void showCommandLine() {
    view.showText(UiText.COMMAND_LINE_TITLE.text(), CommandLineParser.usage());
  }

  /** Shows the recent log lines and clears the warning marker. */
  void showLog() {
    List<String> lines = recent == null ? List.of() : recent.snapshot();
    set.warningsSeen();
    view.showLog(
        lines.isEmpty()
            ? UiText.SHOW_LOG_EMPTY.text()
            : String.join(System.lineSeparator(), lines));
  }

  /**
   * Opens or closes the debug log file on a worker, then shows a notice. The check mark follows the
   * file, so it stays clear when the file does not open.
   *
   * @param on true to open it
   * @param done runs on the UI thread once the file is opened or closed
   */
  void setDebugLogFile(final boolean on, final Runnable done) {
    final LogFiles target = files;
    start(
        "Debug log file not changed",
        new Runnable() {
          @Override
          public void run() {
            String failure = null;
            if (on) {
              try {
                target.attach();
              } catch (IOException e) {
                failure = LogText.oneLine(e.getMessage());
                LOG.warn("Cannot open the debug log file: {}", failure);
              }
            } else {
              target.detach();
            }
            final String notice =
                failure != null
                    ? UiText.NOTICE_LOG_FILE_FAILED.format(failure)
                    : on
                        ? UiText.NOTICE_LOG_FILE_ON.format(target.directory())
                        : UiText.NOTICE_LOG_FILE_OFF.text();
            post(
                new Runnable() {
                  @Override
                  public void run() {
                    view.showNotice(notice);
                    done.run();
                  }
                });
          }
        });
  }

  /** Opens the debug log file's folder, creating it owner-only when missing. */
  void openLogFolder() {
    openFolder(files.directory());
  }

  /** Opens the settings folder, creating it owner-only when missing. */
  void openSettingsFolder() {
    openFolder(location.directory());
  }

  /** Opens a document's folder; a missing one shows a notice and is not created. */
  void openContainingFolder(final Path folder) {
    start(
        "Folder not opened",
        new Runnable() {
          @Override
          public void run() {
            final boolean exists = Files.isDirectory(folder);
            post(
                new Runnable() {
                  @Override
                  public void run() {
                    if (exists) {
                      launch(folder);
                    } else {
                      view.showNotice(UiText.NOTICE_FOLDER_MISSING.format(folder));
                    }
                  }
                });
          }
        });
  }

  private void openFolder(final Path folder) {
    start(
        "Folder not opened",
        new Runnable() {
          @Override
          public void run() {
            Path created = null;
            String failure = null;
            try {
              created = SafePaths.settingsDirectory(folder);
            } catch (IOException e) {
              failure = LogText.oneLine(e.getMessage());
              LOG.warn("Cannot create {}: {}", LogText.oneLine(folder), failure);
            }
            final Path target = created;
            final String notice =
                failure == null ? null : UiText.NOTICE_FOLDER_FAILED.format(folder, failure);
            post(
                new Runnable() {
                  @Override
                  public void run() {
                    if (target != null) {
                      launch(target);
                    } else {
                      view.showNotice(notice);
                    }
                  }
                });
          }
        });
  }

  /**
   * Collects the About details on a worker, puts them on the clipboard and opens the project's
   * issue page in the system browser.
   */
  void reportIssue() {
    final String toolkit = view.toolkitDescription();
    start(
        "Issue page not opened",
        new Runnable() {
          @Override
          public void run() {
            final AboutInfo info = AboutInfo.collect(toolkit, location);
            post(
                new Runnable() {
                  @Override
                  public void run() {
                    view.setText(String.join(System.lineSeparator(), info.details()));
                    if (view.launchUrl(AboutInfo.ISSUES_URL)) {
                      view.showNotice(UiText.NOTICE_ISSUE_DETAILS.text());
                    } else {
                      LOG.warn("No application opened the issue page");
                      view.showNotice(UiText.NOTICE_LAUNCH_FAILED.text());
                    }
                  }
                });
          }
        });
  }

  /**
   * Writes the user guide to the settings cache folder on a worker, then shows it in a browser
   * window, else as {@link #showGuideElsewhere}.
   */
  void showGuide() {
    final Path settings = location.directory();
    start(
        "User guide not shown",
        new Runnable() {
          @Override
          public void run() {
            Path copy = null;
            String failure = null;
            try {
              copy = UserGuide.writeCopy(settings);
            } catch (IOException e) {
              failure = UiText.NOTICE_GUIDE_FAILED.format(LogText.oneLine(e.getMessage()));
              LOG.warn("Cannot write the user guide: {}", LogText.oneLine(e.getMessage()));
            }
            final Path guide = copy;
            final String notice = failure;
            post(
                new Runnable() {
                  @Override
                  public void run() {
                    if (guide == null) {
                      view.showNotice(notice);
                    } else if (!view.showGuide(guide)) {
                      showGuideElsewhere(guide);
                    }
                  }
                });
          }
        });
  }

  /**
   * Opens the user guide's copy in the application the system opens HTML with, else names the file
   * in a notice. Does nothing once the window is closing.
   *
   * @param guide the guide's copy
   */
  void showGuideElsewhere(Path guide) {
    if (closing.getAsBoolean()) {
      return;
    }
    if (!view.launch(guide)) {
      LOG.warn("No application opened {}", LogText.oneLine(guide));
      view.showNotice(UiText.NOTICE_GUIDE_AT.format(guide));
    }
  }

  /** Collects the About facts on a worker, then shows the dialog. */
  void showAbout() {
    final String toolkit = view.toolkitDescription();
    start(
        "About not shown",
        new Runnable() {
          @Override
          public void run() {
            final AboutInfo info = AboutInfo.collect(toolkit, location);
            post(
                new Runnable() {
                  @Override
                  public void run() {
                    view.showAbout(info);
                  }
                });
          }
        });
  }

  /** Reads the license notices from the jar on a worker, then shows them. */
  void showLicenses() {
    start(
        "Licenses not shown",
        new Runnable() {
          @Override
          public void run() {
            String text;
            try {
              text = AboutInfo.licenseText();
            } catch (IOException e) {
              text = UiText.LICENSES_READ_FAILED.format(LogText.oneLine(e.getMessage()));
            }
            final String shown = text;
            post(
                new Runnable() {
                  @Override
                  public void run() {
                    view.showText(UiText.LICENSES_TITLE.text(), shown);
                  }
                });
          }
        });
  }

  private void launch(Path target) {
    if (!view.launch(target)) {
      LOG.warn("No application opened {}", LogText.oneLine(target));
      view.showNotice(UiText.NOTICE_LAUNCH_FAILED.text());
    }
  }

  /** Starts a {@code LIFECYCLE} worker; when workers are closed, logs {@code refused} at DEBUG. */
  private void start(String refused, Runnable task) {
    if (!workers.tryStart(Workers.Kind.LIFECYCLE, task)) {
      LOG.debug("{}: workers are closed", LogText.oneLine(refused));
    }
  }

  /** Posts a view update that runs only while the window is open. */
  private void post(final Runnable update) {
    ui.asyncExec(
        new Runnable() {
          @Override
          public void run() {
            if (!closing.getAsBoolean()) {
              update.run();
            }
          }
        });
  }
}
