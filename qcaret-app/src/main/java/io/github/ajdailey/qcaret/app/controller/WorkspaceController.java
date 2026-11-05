package io.github.ajdailey.qcaret.app.controller;

import io.github.ajdailey.qcaret.app.i18n.UiText;
import io.github.ajdailey.qcaret.app.view.MainView;
import java.nio.file.Path;
import java.util.function.Consumer;

/**
 * The window's workspace folder: one root, set with File > Set Workspace Folder and kept for the
 * run. HyperSearch's workspace scope searches its tree. Confined to the UI thread.
 */
public final class WorkspaceController {
  private final MainView view;
  private Path root;

  /**
   * Creates the controller without a folder.
   *
   * @param view asks for the folder
   */
  public WorkspaceController(MainView view) {
    this.view = view;
  }

  /**
   * Returns the folder.
   *
   * @return the absolute, normalized folder, or null when none is set
   */
  public Path root() {
    return root;
  }

  /** Asks for a folder and makes it the workspace. */
  public void choose() {
    view.chooseFolder(
        root,
        new Consumer<Path>() {
          @Override
          public void accept(Path folder) {
            set(folder);
          }
        });
  }

  /**
   * Makes a folder the workspace and says so in the status bar.
   *
   * @param folder the folder
   */
  public void set(Path folder) {
    root = folder.toAbsolutePath().normalize();
    view.showNotice(UiText.WORKSPACE_SET.format(root));
  }
}
