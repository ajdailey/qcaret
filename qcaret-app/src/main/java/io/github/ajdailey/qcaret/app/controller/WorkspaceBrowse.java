package io.github.ajdailey.qcaret.app.controller;

import io.github.ajdailey.qcaret.app.config.Preferences;
import io.github.ajdailey.qcaret.app.i18n.UiText;
import io.github.ajdailey.qcaret.app.view.MainView;
import io.github.ajdailey.qcaret.app.view.UiDispatcher;
import io.github.ajdailey.qcaret.app.view.WorkspaceBrowseInput;
import io.github.ajdailey.qcaret.core.concurrent.WorkerThread;
import io.github.ajdailey.qcaret.core.concurrent.Workers;
import io.github.ajdailey.qcaret.core.workspace.ListingOptions;
import io.github.ajdailey.qcaret.core.workspace.NameFilter;
import java.nio.file.Files;
import java.nio.file.InvalidPathException;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;

/**
 * The workspace browser's navigation, filter, sort and session restore over a {@link
 * WorkspaceController}. A folder is checked and resolved to its real path on a {@code qcaret-io-}
 * worker, then shown; a later request drops an earlier one still being checked. Favorites are
 * {@code workspace.favorite.N}, shared by every window. The filter ignores case where the
 * platform's file names do ({@link NameFilter#PLATFORM_IGNORES_CASE}). Confined to the UI thread.
 */
final class WorkspaceBrowse implements WorkspaceBrowseInput {
  private final WorkspaceController workspace;
  private final MainView view;
  private final Workers workers;
  private final UiDispatcher ui;
  private final Settings settings;
  private final boolean ignoreCase;
  private Path home;
  private ListingOptions options = ListingOptions.DEFAULTS;

  /** Counts folder requests; a check that ends after a newer request is dropped. */
  private int generation;

  WorkspaceBrowse(
      WorkspaceController workspace,
      MainView view,
      Workers workers,
      UiDispatcher ui,
      Settings settings,
      Path home,
      boolean ignoreCase) {
    this.workspace = workspace;
    this.view = view;
    this.workers = workers;
    this.ui = ui;
    this.settings = settings;
    this.home = home;
    this.ignoreCase = ignoreCase;
  }

  /** Sets the home folder, for tests. */
  void setHome(Path value) {
    home = value;
  }

  @Override
  public String shownFolder() {
    Path top = workspace.shownFolder();
    return top == null ? "" : top.toString();
  }

  @Override
  public boolean browsing() {
    return workspace.browsed() != null;
  }

  @Override
  public void go(String typed) {
    String text = typed.strip();
    if (text.isEmpty()) {
      return;
    }
    Path target;
    try {
      target = resolve(text);
    } catch (InvalidPathException e) {
      view.showNotice(UiText.BROWSE_INVALID.format(text));
      return;
    }
    show(target);
  }

  /** Resolves a typed path: {@code ~} names the home folder; a relative path the shown folder's. */
  private Path resolve(String text) {
    if (text.equals("~")) {
      return home;
    }
    if (text.startsWith("~/") || text.startsWith("~\\")) {
      return home.resolve(text.substring(2)).normalize();
    }
    Path path = Path.of(text);
    Path top = workspace.shownFolder();
    if (path.isAbsolute()) {
      return path.normalize();
    }
    return (top == null ? path.toAbsolutePath() : top.resolve(path)).normalize();
  }

  @Override
  public void parent() {
    Path top = workspace.shownFolder();
    if (top != null && top.getParent() != null) {
      show(top.getParent());
    }
  }

  @Override
  public void home() {
    show(home);
  }

  @Override
  public void back() {
    generation++;
    workspace.showFolder(null);
  }

  /** Checks a folder on a worker and shows it, or says why not. */
  private void show(final Path target) {
    final int request = ++generation;
    workers.tryStart(
        Workers.Kind.IO,
        new Runnable() {
          @Override
          public void run() {
            Path real = null;
            UiText refusal = null;
            if (!Files.exists(target)) {
              refusal = UiText.BROWSE_MISSING;
            } else if (!Files.isDirectory(target)) {
              refusal = UiText.BROWSE_NOT_FOLDER;
            } else {
              real = WorkspaceController.realFolder(target);
            }
            final Path folder = real;
            final UiText failure = refusal;
            ui.asyncExec(
                new Runnable() {
                  @Override
                  public void run() {
                    if (request != generation) {
                      return;
                    }
                    if (failure != null) {
                      view.showNotice(failure.format(target));
                    } else {
                      workspace.showFolder(folder);
                    }
                  }
                });
          }
        });
  }

  @Override
  public List<String> favorites() {
    return settings.current().state().favorites();
  }

  @Override
  public void addFavorite() {
    String top = shownFolder();
    List<String> list = new ArrayList<String>(favorites());
    if (!top.isEmpty() && !list.contains(top)) {
      list.add(top);
      setFavorites(list);
    }
  }

  @Override
  public void removeFavorite() {
    List<String> list = new ArrayList<String>(favorites());
    if (list.remove(shownFolder())) {
      setFavorites(list);
    }
  }

  private void setFavorites(List<String> list) {
    Preferences current = settings.current();
    settings.set(current.withState(current.state().withFavorites(list)));
  }

  @Override
  public String filter() {
    return options.filter().text();
  }

  @Override
  public void setFilter(String globs) {
    NameFilter filter;
    try {
      filter = NameFilter.parse(globs, ignoreCase);
    } catch (IllegalArgumentException e) {
      view.showNotice(UiText.BROWSE_BAD_FILTER.format(globs.strip()));
      return;
    }
    ListingOptions o = options;
    setOptions(new ListingOptions(filter, o.hidden(), o.order(), o.descending()));
  }

  @Override
  public boolean hiddenShown() {
    return options.hidden();
  }

  @Override
  public void setHiddenShown(boolean shown) {
    ListingOptions o = options;
    setOptions(new ListingOptions(o.filter(), shown, o.order(), o.descending()));
  }

  @Override
  public Column sortColumn() {
    return switch (options.order()) {
      case NAME -> Column.NAME;
      case SIZE -> Column.SIZE;
      case MODIFIED -> Column.MODIFIED;
    };
  }

  @Override
  public boolean descending() {
    return options.descending();
  }

  @Override
  public void sort(Column column, boolean descending) {
    ListingOptions.Order order =
        switch (column) {
          case NAME -> ListingOptions.Order.NAME;
          case SIZE -> ListingOptions.Order.SIZE;
          case MODIFIED -> ListingOptions.Order.MODIFIED;
        };
    ListingOptions o = options;
    setOptions(new ListingOptions(o.filter(), o.hidden(), order, descending));
  }

  /** Returns the filter, hidden entries and sort. */
  ListingOptions options() {
    return options;
  }

  /** Sets the filter, hidden entries and sort, and lists every listed directory again. */
  private void setOptions(ListingOptions next) {
    if (!next.equals(options)) {
      options = next;
      workspace.relistAll();
    }
  }

  /**
   * Puts back a session's filter and hidden entries before anything is listed; a filter that does
   * not parse lists every file.
   *
   * @param globs the filter
   * @param hidden true to list hidden entries
   */
  void restore(String globs, boolean hidden) {
    NameFilter filter;
    try {
      filter = NameFilter.parse(globs, ignoreCase);
    } catch (IllegalArgumentException e) {
      filter = NameFilter.ALL;
    }
    options = new ListingOptions(filter, hidden, options.order(), options.descending());
  }

  /**
   * Puts back a session's folders and browser: the real paths are resolved on a {@code qcaret-io-}
   * worker and a folder that is gone is dropped ({@link WorkspaceController#restored}).
   *
   * @param folder the workspace folder, empty for none
   * @param browsedFolder the folder the browser showed instead, empty for none
   * @param browser true to show the browser
   * @param expanded the expanded directories, parents first
   */
  void restoreFolders(
      String folder, String browsedFolder, final boolean browser, final List<Path> expanded) {
    final Path given = absolute(folder);
    final Path away = absolute(browsedFolder);
    if (given == null && away == null) {
      workspace.restored(null, null, browser, expanded);
      return;
    }
    workers.tryStart(
        Workers.Kind.IO,
        new Runnable() {
          @Override
          public void run() {
            final Path real = folder(given);
            final Path other = folder(away);
            ui.asyncExec(
                new Runnable() {
                  @Override
                  public void run() {
                    workspace.restored(real, other, browser, expanded);
                  }
                });
          }
        });
  }

  /** Returns a folder's real path; null for none or one that is not a directory. */
  @WorkerThread
  private static Path folder(Path given) {
    return given != null && Files.isDirectory(given) ? WorkspaceController.realFolder(given) : null;
  }

  /** Returns a session's folder as an absolute normalized path; null for none or a bad one. */
  private static Path absolute(String folder) {
    if (folder.isEmpty()) {
      return null;
    }
    try {
      return Path.of(folder).toAbsolutePath().normalize();
    } catch (InvalidPathException e) {
      return null;
    }
  }
}
