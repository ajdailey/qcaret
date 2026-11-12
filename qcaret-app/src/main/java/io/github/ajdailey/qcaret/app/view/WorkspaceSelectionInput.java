package io.github.ajdailey.qcaret.app.view;

import io.github.ajdailey.qcaret.app.i18n.UiText;
import java.util.Arrays;
import java.util.Collections;
import java.util.List;

/**
 * The workspace browser's row menu and keys over the tree's selection, as its view calls them. The
 * view passes the selected rows and the focused row; an empty selection stands for the shown
 * folder. Called on the UI thread.
 */
public interface WorkspaceSelectionInput {
  /** A row menu item or key, with its menu label. */
  enum Action {
    /** Opens every selected file. */
    OPEN(UiText.WORKSPACE_OPEN),
    /** Opens every selected file in the system's application. */
    OPEN_DEFAULT(UiText.WORKSPACE_OPEN_DEFAULT),
    /** Makes the focused directory the workspace folder. */
    SET_FOLDER(UiText.WORKSPACE_SET_FOLDER),
    /** Copies every selected path, one per line. */
    COPY_PATH(UiText.WORKSPACE_COPY_PATH),
    /** Searches the one selected folder, or the one selected file's folder. */
    SEARCH_FOLDER(UiText.WORKSPACE_SEARCH_FOLDER),
    /** Creates a file in the focused directory. */
    NEW_FILE(UiText.WORKSPACE_NEW_FILE),
    /** Creates a folder in the focused directory. */
    NEW_FOLDER(UiText.WORKSPACE_NEW_FOLDER),
    /** Renames the one selected entry. */
    RENAME(UiText.WORKSPACE_RENAME),
    /** Deletes every selected entry after one prompt. */
    DELETE(UiText.WORKSPACE_DELETE);

    private final UiText label;

    Action(UiText label) {
      this.label = label;
    }

    /**
     * Returns the menu label.
     *
     * @return the label, with {@code &} before the mnemonic
     */
    public UiText label() {
      return label;
    }
  }

  /** The row menu in order; null is a separator. */
  List<Action> MENU =
      Collections.unmodifiableList(
          Arrays.asList(
              Action.OPEN,
              Action.OPEN_DEFAULT,
              null,
              Action.NEW_FILE,
              Action.NEW_FOLDER,
              Action.RENAME,
              Action.DELETE,
              null,
              Action.COPY_PATH,
              Action.SEARCH_FOLDER,
              Action.SET_FOLDER));

  /**
   * Reports whether an action applies to a selection.
   *
   * @param action the action
   * @param selected the selected rows, in tree order; empty for the shown folder
   * @param focused the focused row among them, or null for the first
   * @return true when it applies
   */
  boolean enabled(Action action, List<WorkspaceNode> selected, WorkspaceNode focused);

  /**
   * Runs an action on a selection; does nothing where {@link #enabled} is false.
   *
   * @param action the action
   * @param selected the selected rows, in tree order; empty for the shown folder
   * @param focused the focused row among them, or null for the first
   */
  void run(Action action, List<WorkspaceNode> selected, WorkspaceNode focused);
}
