package io.github.ajdailey.qcaret.app.controller;

import io.github.ajdailey.qcaret.app.command.Keymap;
import io.github.ajdailey.qcaret.app.command.ShortcutOverrides;
import io.github.ajdailey.qcaret.app.config.Appearance;
import io.github.ajdailey.qcaret.app.config.Options;
import io.github.ajdailey.qcaret.app.config.Preferences;
import io.github.ajdailey.qcaret.app.i18n.UiText;
import io.github.ajdailey.qcaret.app.session.SessionRewrite;
import io.github.ajdailey.qcaret.app.view.AppearanceChoices;
import io.github.ajdailey.qcaret.app.view.MainView;
import io.github.ajdailey.qcaret.app.view.PreferencesForm;
import io.github.ajdailey.qcaret.app.view.PreferencesInput;
import io.github.ajdailey.qcaret.core.document.Document;
import java.util.function.Consumer;

/**
 * Tools > Preferences: {@link #open} reads {@code qcaret.conf} on a worker through {@link
 * Settings#refresh} and then shows the dialog; {@link #apply} checks its fields and hands them to
 * {@link Settings}, which saves on a worker. An apply that keeps the theme file reads the file
 * again, so an edit to it shows without a restart. Confined to the UI thread.
 */
final class PreferencesController implements PreferencesInput {
  private final Settings settings;
  private final MainView view;
  private final Keymap defaults;
  private final Consumer<Consumer<SessionRewrite.Result>> forgetHistory;
  private final WindowSet set;

  /** The settings the file holds after a failed clear, so a retry removes the same keys. */
  private Preferences uncleared;

  PreferencesController(
      Settings settings,
      MainView view,
      Keymap defaults,
      Consumer<Consumer<SessionRewrite.Result>> forgetHistory,
      WindowSet set) {
    this.settings = settings;
    this.view = view;
    this.defaults = defaults;
    this.forgetHistory = forgetHistory;
    this.set = set;
  }

  /** Shows the dialog once the file is read. */
  void open() {
    settings.refresh(
        new Runnable() {
          @Override
          public void run() {
            noticeUnreadable();
            view.showPreferences(PreferencesController.this);
          }
        });
  }

  @Override
  public PreferencesForm form() {
    return form(settings.current());
  }

  @Override
  public PreferencesForm defaults() {
    return form(Preferences.DEFAULTS);
  }

  private static PreferencesForm form(Preferences p) {
    Options o = p.options();
    return new PreferencesForm(
        Integer.toString(o.completionChars()),
        o.autoClose(),
        Integer.toString(o.maxHighlightLineChars()),
        Integer.toString(o.tabWidth()),
        o.insertSpaces(),
        Integer.toString(o.pollSeconds()),
        o.autoReloadClean(),
        Integer.toString(o.recoveryIntervalSeconds()),
        Integer.toString(o.recoveryKeepDays()),
        o.sessionSnapshot(),
        Integer.toString(o.backupDays()),
        Integer.toString(o.searchMaxLineChars()),
        o.excludedDirectories(),
        p.theme(),
        o.fontSize() == 0 ? "" : Integer.toString(o.fontSize()),
        p.shortcuts(),
        p.appearance().themeFile(),
        p.appearance().fontFamily(),
        Integer.toString(o.rightMargin()),
        o.dateFormat(),
        o.editorConfig(),
        o.modelines(),
        p.appearance().editorColors(),
        o.closeWindowWithLastTab());
  }

  @Override
  public void loadChoices(Consumer<AppearanceChoices> done) {
    set.loadChoices(done);
  }

  @Override
  public Keymap keymap(ShortcutOverrides overrides) {
    return defaults.withOverrides(overrides);
  }

  @Override
  public PreferencesForm.Problem apply(PreferencesForm form) {
    Checked completion =
        number(
            form.completionChars(),
            0,
            Options.MAX_COMPLETION_CHARS,
            UiText.PREFERENCES_COMPLETION_CHARS,
            PreferencesForm.Group.EDITOR);
    Checked highlight =
        number(
            form.maxHighlightLineChars(),
            Options.MIN_HIGHLIGHT_CHARS,
            Options.MAX_HIGHLIGHT_CHARS,
            UiText.PREFERENCES_HIGHLIGHT_CHARS,
            PreferencesForm.Group.EDITOR);
    Checked tabWidth =
        number(
            form.tabWidth(),
            1,
            Document.MAX_TAB_WIDTH,
            UiText.PREFERENCES_TAB_WIDTH,
            PreferencesForm.Group.EDITOR);
    Checked margin =
        number(
            form.rightMargin(),
            1,
            Options.MAX_RIGHT_MARGIN,
            UiText.PREFERENCES_RIGHT_MARGIN,
            PreferencesForm.Group.EDITOR);
    Checked poll =
        number(
            form.pollSeconds(),
            Options.MIN_POLL_SECONDS,
            Options.MAX_POLL_SECONDS,
            UiText.PREFERENCES_POLL_SECONDS,
            PreferencesForm.Group.FILES);
    Checked recoverySeconds =
        number(
            form.recoveryIntervalSeconds(),
            Options.MIN_RECOVERY_SECONDS,
            Options.MAX_RECOVERY_SECONDS,
            UiText.PREFERENCES_RECOVERY_SECONDS,
            PreferencesForm.Group.FILES);
    Checked recoveryDays =
        number(
            form.recoveryKeepDays(),
            Options.MIN_RECOVERY_DAYS,
            Options.MAX_RECOVERY_DAYS,
            UiText.PREFERENCES_RECOVERY_DAYS,
            PreferencesForm.Group.FILES);
    Checked days =
        number(
            form.backupDays(),
            Options.MIN_BACKUP_DAYS,
            Options.MAX_BACKUP_DAYS,
            UiText.PREFERENCES_BACKUP_DAYS,
            PreferencesForm.Group.SEARCH);
    Checked lineChars =
        number(
            form.searchMaxLineChars(),
            Options.MIN_SEARCH_LINE_CHARS,
            Options.MAX_SEARCH_LINE_CHARS,
            UiText.PREFERENCES_SEARCH_LINE_CHARS,
            PreferencesForm.Group.SEARCH);
    Checked font =
        form.fontSize().isBlank()
            ? new Checked(0, null)
            : number(
                form.fontSize(),
                Options.MIN_FONT_SIZE,
                Options.MAX_FONT_SIZE,
                UiText.PREFERENCES_FONT_SIZE,
                PreferencesForm.Group.APPEARANCE);
    Checked[] fields = {
      completion,
      highlight,
      tabWidth,
      margin,
      poll,
      recoverySeconds,
      recoveryDays,
      days,
      lineChars,
      font
    };
    for (Checked checked : fields) {
      if (checked.problem() != null) {
        return checked.problem();
      }
    }
    String dateFormat = form.dateFormat().strip();
    if (!Options.validDateFormat(dateFormat)) {
      return new PreferencesForm.Problem(
          PreferencesForm.Group.EDITOR,
          UiText.PREFERENCES_BAD_DATE_FORMAT.format(label(UiText.PREFERENCES_DATE_FORMAT)));
    }
    String excluded = form.excludedDirectories().strip();
    if (!Options.validDirectories(excluded)) {
      return new PreferencesForm.Problem(
          PreferencesForm.Group.SEARCH,
          UiText.PREFERENCES_BAD_DIRECTORIES.format(
              UiText.PREFERENCES_EXCLUDED_DIRECTORIES.text()));
    }
    Preferences next =
        settings
            .current()
            .withTheme(form.theme())
            .withShortcuts(form.shortcuts())
            .withAppearance(
                new Appearance(form.themeFile(), form.fontFamily(), form.editorColors()))
            .withOptions(
                new Options(
                    completion.value(),
                    form.autoClose(),
                    highlight.value(),
                    font.value(),
                    poll.value(),
                    form.autoReloadClean(),
                    days.value(),
                    lineChars.value(),
                    excluded,
                    recoverySeconds.value(),
                    recoveryDays.value(),
                    form.sessionSnapshot(),
                    tabWidth.value(),
                    form.insertSpaces(),
                    margin.value(),
                    dateFormat,
                    form.editorConfig(),
                    form.modelines(),
                    form.closeWindowWithLastTab()));
    boolean sameThemeFile = form.themeFile().equals(settings.current().appearance().themeFile());
    settings.set(
        next,
        new Consumer<Boolean>() {
          @Override
          public void accept(Boolean saved) {
            if (!saved) {
              view.showNotice(UiText.PREFERENCES_NOT_SAVED.text());
            }
          }
        });
    if (sameThemeFile) {
      set.loadTheme();
    }
    return null;
  }

  @Override
  public void clearHistory() {
    forgetHistory.accept(
        new Consumer<SessionRewrite.Result>() {
          @Override
          public void accept(SessionRewrite.Result result) {
            if (result == SessionRewrite.Result.FAILED) {
              view.showNotice(UiText.PREFERENCES_HISTORY_NOT_REMOVED.text());
            } else if (result == SessionRewrite.Result.OWNED_ELSEWHERE) {
              view.showNotice(UiText.PREFERENCES_HISTORY_SESSION_OWNED.text());
            }
          }
        });
    settings.refresh(
        new Runnable() {
          @Override
          public void run() {
            noticeUnreadable();
            removeHistory();
          }
        });
  }

  private void noticeUnreadable() {
    if (settings.takeUnreadable()) {
      view.showNotice(UiText.PREFERENCES_FILE_UNREADABLE.text());
    }
  }

  private void removeHistory() {
    Preferences current = settings.current();
    Preferences cleared = current.withState(current.state().withoutHistory());
    final Preferences file = uncleared == null ? current : uncleared;
    if (file.equals(cleared)) {
      return;
    }
    settings.setFrom(
        file,
        cleared,
        new Consumer<Boolean>() {
          @Override
          public void accept(Boolean saved) {
            uncleared = saved ? null : file;
            if (!saved) {
              view.showNotice(UiText.PREFERENCES_HISTORY_NOT_REMOVED.text());
            }
          }
        });
  }

  /** A field's whole number, or why it was refused. */
  private record Checked(int value, PreferencesForm.Problem problem) {}

  private static Checked number(
      String text, int min, int max, UiText field, PreferencesForm.Group group) {
    try {
      long value = Long.parseLong(text.strip());
      if (value >= min && value <= max) {
        return new Checked((int) value, null);
      }
    } catch (NumberFormatException e) {
      // Reported below.
    }
    return new Checked(
        0,
        new PreferencesForm.Problem(
            group, UiText.PREFERENCES_BAD_NUMBER.format(label(field), min, max)));
  }

  private static String label(UiText field) {
    return field.text().replace("&", "");
  }
}
