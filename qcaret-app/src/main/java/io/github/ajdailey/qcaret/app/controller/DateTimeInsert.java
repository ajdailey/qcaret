package io.github.ajdailey.qcaret.app.controller;

import io.github.ajdailey.qcaret.app.config.Preferences;
import io.github.ajdailey.qcaret.app.i18n.UiText;
import io.github.ajdailey.qcaret.app.view.MainView;
import io.github.ajdailey.qcaret.app.view.UiDispatcher;
import io.github.ajdailey.qcaret.core.concurrent.WorkerThread;
import io.github.ajdailey.qcaret.core.concurrent.Workers;
import java.time.Clock;
import java.time.ZonedDateTime;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.RejectedExecutionException;
import java.util.function.Consumer;

/**
 * Edit > Insert Date and Time: offers {@code editor.dateFormat} and {@link #COMMON}, each shown
 * with the current time, with {@code ui.lastDateFormat} selected; the chosen format, at the time of
 * the choice, replaces every selection of the selected editor as one undo step, and becomes {@code
 * ui.lastDateFormat}. The rows are formatted on a {@code qcaret-cpu-} worker, since the first
 * format of a zoned time loads the time-zone rules. Formats use the default locale. Confined to the
 * UI thread.
 */
final class DateTimeInsert {
  /** The formats offered after {@code editor.dateFormat}. */
  static final List<String> COMMON =
      List.of(
          "yyyy-MM-dd",
          "yyyy-MM-dd HH:mm:ss",
          "yyyy-MM-dd'T'HH:mm:ssXXX",
          "EEE, d MMM yyyy HH:mm:ss Z",
          "d MMMM yyyy",
          "HH:mm:ss");

  private final DocumentController documents;
  private final Settings settings;
  private final MainView view;
  private final Workers workers;
  private final UiDispatcher ui;
  private final Clock clock;

  DateTimeInsert(
      DocumentController documents,
      Settings settings,
      MainView view,
      Workers workers,
      UiDispatcher ui,
      Clock clock) {
    this.documents = documents;
    this.settings = settings;
    this.view = view;
    this.workers = workers;
    this.ui = ui;
    this.clock = clock;
  }

  /** Formats the rows on a worker, then asks for a format. */
  void choose() {
    if (documents.activeEditor() == null) {
      return;
    }
    Preferences current = settings.current();
    final List<String> patterns = patterns(current.options().dateFormat());
    final int selected = Math.max(0, patterns.indexOf(current.state().lastDateFormat()));
    try {
      workers.executeCpu(
          new Runnable() {
            @Override
            public void run() {
              List<String> rows = null;
              try {
                rows = rows(patterns, ZonedDateTime.now(clock));
              } finally {
                final List<String> shown = rows;
                ui.asyncExec(
                    new Runnable() {
                      @Override
                      public void run() {
                        if (shown != null) {
                          ask(patterns, shown, selected);
                        }
                      }
                    });
              }
            }
          });
    } catch (RejectedExecutionException e) {
      // Shutting down.
    }
  }

  private void ask(final List<String> patterns, List<String> rows, int selected) {
    view.chooseDateFormat(
        rows,
        selected,
        new Consumer<Integer>() {
          @Override
          public void accept(Integer index) {
            insert(patterns.get(index));
          }
        });
  }

  /**
   * Inserts the current time in a format at every caret of the selected editor.
   *
   * @param pattern a valid {@link DateTimeFormatter} pattern
   */
  void insert(String pattern) {
    EditorController editor = documents.activeEditor();
    if (editor == null || editor.document().isReadOnly()) {
      return;
    }
    editor.insertText(DateTimeFormatter.ofPattern(pattern).format(ZonedDateTime.now(clock)));
    Preferences current = settings.current();
    if (!pattern.equals(current.state().lastDateFormat())) {
      settings.set(current.withState(current.state().withLastDateFormat(pattern)));
    }
  }

  /**
   * Returns the formats offered: the setting first, then {@link #COMMON} without it.
   *
   * @param setting {@code editor.dateFormat}
   * @return the patterns
   */
  static List<String> patterns(String setting) {
    List<String> list = new ArrayList<String>();
    list.add(setting);
    for (String pattern : COMMON) {
      if (!pattern.equals(setting)) {
        list.add(pattern);
      }
    }
    return list;
  }

  @WorkerThread
  private static List<String> rows(List<String> patterns, ZonedDateTime now) {
    List<String> rows = new ArrayList<String>(patterns.size());
    for (String pattern : patterns) {
      rows.add(
          UiText.DATE_FORMAT_ROW.format(DateTimeFormatter.ofPattern(pattern).format(now), pattern));
    }
    return rows;
  }
}
