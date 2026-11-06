package io.github.ajdailey.qcaret.app.controller;

import io.github.ajdailey.qcaret.app.command.Keymap;
import io.github.ajdailey.qcaret.app.command.ShortcutOverrides;
import io.github.ajdailey.qcaret.app.config.Options;
import io.github.ajdailey.qcaret.app.config.Preferences;
import io.github.ajdailey.qcaret.app.i18n.UiText;
import io.github.ajdailey.qcaret.app.view.MainView;
import io.github.ajdailey.qcaret.app.view.PreferencesForm;
import io.github.ajdailey.qcaret.app.view.PreferencesInput;
import java.util.function.Consumer;

/**
 * Edit > Preferences: {@link #open} reads {@code qcaret.conf} on a worker through {@link
 * Settings#refresh} and then shows the dialog; {@link #apply} checks its fields and hands them to
 * {@link Settings}, which saves on a worker. Confined to the UI thread.
 */
final class PreferencesController implements PreferencesInput {
  private final Settings settings;
  private final MainView view;
  private final Keymap defaults;
  private final SearchController search;

  /** The settings the file holds after a failed clear, so a retry removes the same keys. */
  private Preferences uncleared;

  PreferencesController(
      Settings settings, MainView view, Keymap defaults, SearchController search) {
    this.settings = settings;
    this.view = view;
    this.defaults = defaults;
    this.search = search;
  }

  /** Shows the dialog once the file is read. */
  void open() {
    settings.refresh(
        new Runnable() {
          @Override
          public void run() {
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
        Integer.toString(o.pollSeconds()),
        o.autoReloadClean(),
        Integer.toString(o.backupDays()),
        Integer.toString(o.searchMaxLineChars()),
        o.excludedDirectories(),
        p.theme(),
        o.fontSize() == 0 ? "" : Integer.toString(o.fontSize()),
        p.shortcuts());
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
    Checked poll =
        number(
            form.pollSeconds(),
            Options.MIN_POLL_SECONDS,
            Options.MAX_POLL_SECONDS,
            UiText.PREFERENCES_POLL_SECONDS,
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
    for (Checked checked : new Checked[] {completion, highlight, poll, days, lineChars, font}) {
      if (checked.problem() != null) {
        return checked.problem();
      }
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
                    excluded));
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
    return null;
  }

  @Override
  public void clearHistory() {
    search.forgetLastRun();
    settings.refresh(
        new Runnable() {
          @Override
          public void run() {
            removeHistory();
          }
        });
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
