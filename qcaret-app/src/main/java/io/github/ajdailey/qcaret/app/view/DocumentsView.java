package io.github.ajdailey.qcaret.app.view;

import io.github.ajdailey.qcaret.app.editor.DisplayProjection;
import io.github.ajdailey.qcaret.core.document.SelectionModel;
import java.nio.file.Path;
import java.util.List;
import java.util.function.Consumer;

/**
 * The window's document tabs and file prompts. Every method is called on the UI thread; a prompt
 * answers through its callback, on the UI thread, possibly after the method has returned.
 */
public interface DocumentsView {
  /**
   * Adds a tab with an editor over a projection, after the other tabs, and selects it.
   *
   * @param projection the display text the editor shows
   * @param selections the editor's carets and selections
   * @param editor receives the editor's input
   * @param tab receives the tab's input
   * @return the tab
   */
  TabView addTab(
      DisplayProjection projection, SelectionModel selections, EditorInput editor, TabInput tab);

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
   * @param chosen receives the chosen file; not called when the user cancels
   */
  void chooseSaveTarget(Path directory, String name, Consumer<Path> chosen);

  /**
   * Asks whether to save a document's changes before it closes.
   *
   * @param name the document's name
   * @param answer receives the choice
   */
  void askUnsaved(String name, Consumer<UnsavedChoice> answer);
}
