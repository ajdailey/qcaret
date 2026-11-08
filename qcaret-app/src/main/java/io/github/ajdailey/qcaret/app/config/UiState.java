package io.github.ajdailey.qcaret.app.config;

import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Remembered UI state in {@code qcaret.conf}, shared by every window and never set from the command
 * line. Immutable.
 *
 * @param search {@code search.*}: the find bar's and HyperSearch's toggles
 * @param texts {@code search.history.text.N}: search texts, newest first
 * @param directories {@code search.history.directory.N}: searched directories, newest first
 * @param masks {@code search.history.mask.N}: include masks, newest first
 * @param replacements {@code search.history.replace.N}: the find bar's replacements, newest first
 * @param recentGrammars {@code syntax.recent}: grammar ids chosen in the Syntax menu, newest first
 * @param dialogs {@code dialog.<name>.width} and {@code .height}: a dialog's last size
 * @param toolbar {@code ui.toolbar}: the windows show the main toolbar
 * @param rightMargin {@code ui.rightMargin}: the editors show the right margin line
 * @param lastDateFormat {@code ui.lastDateFormat}: the pattern Insert Date and Time used last;
 *     empty for none
 * @param indentGuides {@code ui.indentGuides}: the editors show indent guides
 * @param lineChanges {@code ui.lineChanges}: the editors mark the lines changed since the last load
 *     or save
 * @param favorites {@code workspace.favorite.N}: the workspace browser's favorite folders, in the
 *     order added
 */
public record UiState(
    SearchToggles search,
    List<String> texts,
    List<String> directories,
    List<String> masks,
    List<String> replacements,
    List<String> recentGrammars,
    Map<String, Size> dialogs,
    boolean toolbar,
    boolean rightMargin,
    String lastDateFormat,
    boolean indentGuides,
    boolean lineChanges,
    List<String> favorites) {
  /** Entries per search history. */
  public static final int MAX_HISTORY = 20;

  /** Longest history entry kept. */
  public static final int MAX_ENTRY_CHARS = 1000;

  /** Recent grammars kept. */
  public static final int MAX_RECENT_GRAMMARS = 5;

  /** Most dialog sizes kept. */
  public static final int MAX_DIALOGS = 32;

  /** Nothing remembered. */
  public static final UiState DEFAULTS =
      new UiState(
          SearchToggles.DEFAULTS,
          List.of(),
          List.of(),
          List.of(),
          List.of(),
          List.of(),
          Map.of(),
          true,
          false,
          "",
          false,
          false,
          List.of());

  /**
   * A dialog's size in pixels.
   *
   * @param width at least 1
   * @param height at least 1
   */
  public record Size(int width, int height) {
    /** Checks the components. */
    public Size {
      if (width < 1 || height < 1) {
        throw new IllegalArgumentException("size must be positive");
      }
    }
  }

  /** Checks the components and copies the collections, keeping what the limits allow. */
  public UiState {
    if (search == null) {
      throw new IllegalArgumentException("search is required");
    }
    texts = limited(texts, MAX_HISTORY);
    directories = limited(directories, MAX_HISTORY);
    masks = limited(masks, MAX_HISTORY);
    replacements = limited(replacements, MAX_HISTORY);
    recentGrammars = limited(recentGrammars, MAX_RECENT_GRAMMARS);
    dialogs = Collections.unmodifiableMap(new LinkedHashMap<String, Size>(dialogs));
    if (lastDateFormat == null) {
      throw new IllegalArgumentException("lastDateFormat is required");
    }
    favorites = limited(favorites, MAX_HISTORY);
  }

  private static List<String> limited(List<String> list, int max) {
    return List.copyOf(list.size() > max ? list.subList(0, max) : list);
  }

  /**
   * Returns a list with a value first and without its older copy, cut to {@link #MAX_HISTORY}.
   *
   * @param history newest first
   * @param value the value; a blank one or one over {@link #MAX_ENTRY_CHARS} chars is left out
   * @return the list
   */
  public static List<String> remember(List<String> history, String value) {
    if (value.isBlank() || value.length() > MAX_ENTRY_CHARS) {
      return history;
    }
    List<String> list = new ArrayList<String>(history);
    list.remove(value);
    list.add(0, value);
    return list;
  }

  /**
   * Returns a copy with other search toggles.
   *
   * @param value the toggles
   * @return the copy
   */
  public UiState withSearch(SearchToggles value) {
    return new UiState(
        value,
        texts,
        directories,
        masks,
        replacements,
        recentGrammars,
        dialogs,
        toolbar,
        rightMargin,
        lastDateFormat,
        indentGuides,
        lineChanges,
        favorites);
  }

  /**
   * Returns a copy with other search histories.
   *
   * @param textList search texts, newest first
   * @param directoryList directories, newest first
   * @param maskList include masks, newest first
   * @return the copy
   */
  public UiState withHistory(
      List<String> textList, List<String> directoryList, List<String> maskList) {
    return new UiState(
        search,
        textList,
        directoryList,
        maskList,
        replacements,
        recentGrammars,
        dialogs,
        toolbar,
        rightMargin,
        lastDateFormat,
        indentGuides,
        lineChanges,
        favorites);
  }

  /**
   * Returns a copy with another replacement history.
   *
   * @param value replacements, newest first
   * @return the copy
   */
  public UiState withReplacements(List<String> value) {
    return new UiState(
        search,
        texts,
        directories,
        masks,
        value,
        recentGrammars,
        dialogs,
        toolbar,
        rightMargin,
        lastDateFormat,
        indentGuides,
        lineChanges,
        favorites);
  }

  /**
   * Returns a copy with every {@code search.history.*.N} list empty.
   *
   * @return the copy
   */
  public UiState withoutHistory() {
    return withHistory(List.of(), List.of(), List.of()).withReplacements(List.of());
  }

  /**
   * Returns a copy with other recent grammars.
   *
   * @param value ids, newest first
   * @return the copy
   */
  public UiState withRecentGrammars(List<String> value) {
    return new UiState(
        search,
        texts,
        directories,
        masks,
        replacements,
        value,
        dialogs,
        toolbar,
        rightMargin,
        lastDateFormat,
        indentGuides,
        lineChanges,
        favorites);
  }

  /**
   * Returns a copy with the main toolbar shown or hidden.
   *
   * @param value true to show it
   * @return the copy
   */
  public UiState withToolbar(boolean value) {
    return new UiState(
        search,
        texts,
        directories,
        masks,
        replacements,
        recentGrammars,
        dialogs,
        value,
        rightMargin,
        lastDateFormat,
        indentGuides,
        lineChanges,
        favorites);
  }

  /**
   * Returns a copy with a dialog's size; the oldest entry goes past {@link #MAX_DIALOGS}.
   *
   * @param name the dialog's name, {@code [A-Za-z0-9]+}
   * @param size its size
   * @return the copy
   */
  public UiState withDialog(String name, Size size) {
    if (!name.matches("[A-Za-z0-9]+")) {
      throw new IllegalArgumentException("dialog name");
    }
    Map<String, Size> copy = new LinkedHashMap<String, Size>(dialogs);
    copy.remove(name);
    copy.put(name, size);
    while (copy.size() > MAX_DIALOGS) {
      copy.remove(copy.keySet().iterator().next());
    }
    return new UiState(
        search,
        texts,
        directories,
        masks,
        replacements,
        recentGrammars,
        copy,
        toolbar,
        rightMargin,
        lastDateFormat,
        indentGuides,
        lineChanges,
        favorites);
  }

  /**
   * Returns a copy with the right margin line shown or hidden.
   *
   * @param value true to show it
   * @return the copy
   */
  public UiState withRightMargin(boolean value) {
    return new UiState(
        search,
        texts,
        directories,
        masks,
        replacements,
        recentGrammars,
        dialogs,
        toolbar,
        value,
        lastDateFormat,
        indentGuides,
        lineChanges,
        favorites);
  }

  /**
   * Returns a copy with another last date and time pattern.
   *
   * @param value the pattern, empty for none
   * @return the copy
   */
  public UiState withLastDateFormat(String value) {
    return new UiState(
        search,
        texts,
        directories,
        masks,
        replacements,
        recentGrammars,
        dialogs,
        toolbar,
        rightMargin,
        value,
        indentGuides,
        lineChanges,
        favorites);
  }

  /**
   * Returns a copy with the indent guides shown or hidden.
   *
   * @param value true to show them
   * @return the copy
   */
  public UiState withIndentGuides(boolean value) {
    return new UiState(
        search,
        texts,
        directories,
        masks,
        replacements,
        recentGrammars,
        dialogs,
        toolbar,
        rightMargin,
        lastDateFormat,
        value,
        lineChanges,
        favorites);
  }

  /**
   * Returns a copy with the modified-line marks shown or hidden.
   *
   * @param value true to show them
   * @return the copy
   */
  public UiState withLineChanges(boolean value) {
    return new UiState(
        search,
        texts,
        directories,
        masks,
        replacements,
        recentGrammars,
        dialogs,
        toolbar,
        rightMargin,
        lastDateFormat,
        indentGuides,
        value,
        favorites);
  }

  /**
   * Returns a copy with other favorite folders.
   *
   * @param value the paths, in the order added
   * @return the copy
   */
  public UiState withFavorites(List<String> value) {
    return new UiState(
        search,
        texts,
        directories,
        masks,
        replacements,
        recentGrammars,
        dialogs,
        toolbar,
        rightMargin,
        lastDateFormat,
        indentGuides,
        lineChanges,
        value);
  }
}
