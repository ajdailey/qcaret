package io.github.ajdailey.qcaret.app.view;

import io.github.ajdailey.qcaret.app.editor.DisplayProjection;
import io.github.ajdailey.qcaret.core.document.SelectionModel;
import java.nio.file.Path;
import java.util.List;
import java.util.function.Consumer;
import java.util.function.IntConsumer;

/**
 * The window's document tabs and file prompts. Every method is called on the UI thread; a prompt
 * answers through its callback, on the UI thread, possibly after the method has returned.
 */
public interface DocumentsView {
  /**
   * Adds a tab with an editor over a projection and selects it.
   *
   * @param projection the display text the editor shows
   * @param selections the editor's carets and selections
   * @param editor receives the editor's input
   * @param tab receives the tab's input
   * @param index the tab's position, from 0 to the tab count
   * @return the tab
   */
  TabView addTab(
      DisplayProjection projection,
      SelectionModel selections,
      EditorInput editor,
      TabInput tab,
      int index);

  /**
   * Reports that the selected document, a document's file, dirty, read-only, encoding or line
   * ending state, or the document count changed. The view reads menu and status state again.
   */
  void documentStateChanged();

  /** The selected document's text changed: Undo, Redo, Cut and Copy may need their state read. */
  void editStateChanged();

  /**
   * Asks whether to open a binary file as read-only ISO-8859-1 text.
   *
   * @param name the file's name
   * @param open runs when the user accepts; not run on a decline
   */
  void askOpenBinary(String name, Runnable open);

  /**
   * Asks for files to open.
   *
   * @param directory the directory the chooser starts in, or null
   * @param chosen receives the chosen files; not called when the user cancels
   */
  void chooseFilesToOpen(Path directory, Consumer<List<Path>> chosen);

  /**
   * Asks for a file to save to.
   *
   * @param directory the directory the chooser starts in, or null
   * @param name the suggested file name
   * @param chosen receives the chosen file, or null when the user cancels
   */
  void chooseSaveTarget(Path directory, String name, Consumer<Path> chosen);

  /**
   * Asks whether to save a document's changes before it closes.
   *
   * @param name the document's name
   * @param answer receives the choice
   */
  void askUnsaved(String name, Consumer<UnsavedChoice> answer);

  /**
   * Asks whether Save As may replace a file that another tab holds with unsaved changes; that tab
   * then closes without saving.
   *
   * @param name the other tab's name
   * @param replace runs when the user accepts
   * @param declined runs on any other end of the prompt
   */
  void askReplaceOpenFile(String name, Runnable replace, Runnable declined);

  /**
   * Asks for a line, or a line and a column, to move the caret to.
   *
   * @param lines the document's line count
   * @param answer receives the text entered; not called when the user cancels
   */
  void askGoToLine(int lines, Consumer<String> answer);

  /**
   * Asks for a tab width.
   *
   * @param current the width the prompt starts at
   * @param max the largest width accepted
   * @param answer receives the width; not called when the user cancels
   */
  void askTabWidth(int current, int max, IntConsumer answer);

  /**
   * Asks whether Reload from Disk may replace a document's unsaved changes.
   *
   * @param name the document's name
   * @param reload runs when the user accepts
   * @param declined runs on any other end of the prompt
   */
  void askReload(String name, Runnable reload, Runnable declined);

  /**
   * Shows running loads with a Cancel control and Esc, or updates what shows. The window stays
   * usable.
   *
   * @param text what is loading
   * @param percent how much was read, from 0 to 100
   * @param cancel runs when the user cancels
   */
  void showLoadProgress(String text, int percent, Runnable cancel);

  /** Stops showing loads. */
  void hideLoadProgress();
}
