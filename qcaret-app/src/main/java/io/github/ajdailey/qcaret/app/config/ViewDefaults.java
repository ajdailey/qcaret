package io.github.ajdailey.qcaret.app.config;

/**
 * What a window shows when it opens without a session entry: a new window, and every window of a
 * start that restores no session. Each View toggle writes its key; a restored window keeps its
 * session state. Immutable.
 *
 * @param wordWrap {@code ui.wordWrap}: word wrap
 * @param whitespace {@code ui.whitespace}: the whitespace marks
 * @param lineEndings {@code ui.lineEndings}: the line-ending marks
 * @param overview {@code ui.overview}: the overview strip
 * @param lineNumbers {@code ui.lineNumbers}: the line numbers
 * @param workspaceBrowser {@code ui.workspaceBrowser}: the workspace browser
 */
public record ViewDefaults(
    boolean wordWrap,
    boolean whitespace,
    boolean lineEndings,
    boolean overview,
    boolean lineNumbers,
    boolean workspaceBrowser) {
  /** Wrap, marks and browser off; overview strip and line numbers on. */
  public static final ViewDefaults DEFAULTS =
      new ViewDefaults(false, false, false, true, true, false);

  /**
   * Returns a copy with word wrap on or off.
   *
   * @param value true for on
   * @return the copy
   */
  public ViewDefaults withWordWrap(boolean value) {
    return new ViewDefaults(
        value, whitespace, lineEndings, overview, lineNumbers, workspaceBrowser);
  }

  /**
   * Returns a copy with the whitespace marks on or off.
   *
   * @param value true for on
   * @return the copy
   */
  public ViewDefaults withWhitespace(boolean value) {
    return new ViewDefaults(wordWrap, value, lineEndings, overview, lineNumbers, workspaceBrowser);
  }

  /**
   * Returns a copy with the line-ending marks on or off.
   *
   * @param value true for on
   * @return the copy
   */
  public ViewDefaults withLineEndings(boolean value) {
    return new ViewDefaults(wordWrap, whitespace, value, overview, lineNumbers, workspaceBrowser);
  }

  /**
   * Returns a copy with the overview strip shown or hidden.
   *
   * @param value true to show it
   * @return the copy
   */
  public ViewDefaults withOverview(boolean value) {
    return new ViewDefaults(
        wordWrap, whitespace, lineEndings, value, lineNumbers, workspaceBrowser);
  }

  /**
   * Returns a copy with the line numbers shown or hidden.
   *
   * @param value true to show them
   * @return the copy
   */
  public ViewDefaults withLineNumbers(boolean value) {
    return new ViewDefaults(wordWrap, whitespace, lineEndings, overview, value, workspaceBrowser);
  }

  /**
   * Returns a copy with the workspace browser shown or hidden.
   *
   * @param value true to show it
   * @return the copy
   */
  public ViewDefaults withWorkspaceBrowser(boolean value) {
    return new ViewDefaults(wordWrap, whitespace, lineEndings, overview, lineNumbers, value);
  }
}
