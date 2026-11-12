package io.github.ajdailey.qcaret.app.controller;

import io.github.ajdailey.qcaret.app.view.MainView;
import io.github.ajdailey.qcaret.app.view.WorkspaceNode;
import io.github.ajdailey.qcaret.app.view.WorkspaceSelectionInput;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;

/**
 * The workspace browser's row menu and keys over several selected rows. Open, Open in Default
 * Application and Copy Path take every selected file (and, for Copy Path, directory), and Open in
 * Default Application asks first for more than {@value #OPEN_UNASKED} files; Delete takes every
 * selected entry below the shown folder through one prompt ({@link SelectionDelete}); Search in
 * Folder and Rename take a single row; Set as Workspace Folder, New File and New Folder take the
 * focused row. An empty selection stands for the shown folder. Confined to the UI thread.
 */
final class WorkspaceSelection implements WorkspaceSelectionInput {
  /** Files Open in Default Application opens without asking. */
  static final int OPEN_UNASKED = 10;

  private final WorkspaceController workspace;
  private final MainView view;
  private final DocumentController documents;

  WorkspaceSelection(WorkspaceController workspace, MainView view, DocumentController documents) {
    this.workspace = workspace;
    this.view = view;
    this.documents = documents;
  }

  @Override
  public boolean enabled(Action action, List<WorkspaceNode> selected, WorkspaceNode focused) {
    WorkspaceNode root = workspace.rootNode();
    List<WorkspaceNode> rows = selected.isEmpty() && root != null ? List.of(root) : selected;
    if (rows.isEmpty()) {
      return false;
    }
    WorkspaceNode focus = focused != null && rows.contains(focused) ? focused : rows.get(0);
    boolean single = rows.size() == 1;
    return switch (action) {
      case OPEN, OPEN_DEFAULT -> !files(rows).isEmpty();
      case SET_FOLDER -> focus.expands() && (below(focus) || browsing());
      case NEW_FILE, NEW_FOLDER -> focus.expands();
      case COPY_PATH -> !paths(rows).isEmpty();
      case SEARCH_FOLDER -> single && entryOrFolder(focus);
      case RENAME -> single && entry(focus) && below(focus);
      case DELETE -> !entries(rows).isEmpty();
    };
  }

  @Override
  public void run(Action action, List<WorkspaceNode> selected, WorkspaceNode focused) {
    if (!enabled(action, selected, focused)) {
      return;
    }
    List<WorkspaceNode> rows = selected.isEmpty() ? List.of(workspace.rootNode()) : selected;
    WorkspaceNode focus = focused != null && rows.contains(focused) ? focused : rows.get(0);
    switch (action) {
      case OPEN -> documents.open(paths(files(rows)));
      case OPEN_DEFAULT -> openInDefaultApplications(files(rows));
      case SET_FOLDER -> workspace.setWorkspace(focus);
      case COPY_PATH -> view.setText(String.join(System.lineSeparator(), text(paths(rows))));
      case SEARCH_FOLDER -> workspace.files().searchInFolder(focus);
      case NEW_FILE -> workspace.files().newFile(focus);
      case NEW_FOLDER -> workspace.files().newFolder(focus);
      case RENAME -> workspace.files().rename(focus);
      case DELETE -> workspace.files().deleteAll(entries(rows));
    }
  }

  /** Opens files in their default applications, asking first above {@link #OPEN_UNASKED}. */
  private void openInDefaultApplications(final List<WorkspaceNode> files) {
    Runnable open =
        new Runnable() {
          @Override
          public void run() {
            for (WorkspaceNode file : files) {
              workspace.openInDefaultApplication(file);
            }
          }
        };
    if (files.size() > OPEN_UNASKED) {
      view.askOpenMany(files.size(), open);
    } else {
      open.run();
    }
  }

  private boolean below(WorkspaceNode node) {
    WorkspaceNode root = workspace.rootNode();
    return root == null || !node.equals(root);
  }

  private boolean browsing() {
    WorkspaceBrowse browse = workspace.browse();
    return browse != null && browse.browsing();
  }

  private static boolean entryOrFolder(WorkspaceNode node) {
    return node.kind() == WorkspaceNode.Kind.FILE || node.expands();
  }

  private static boolean entry(WorkspaceNode node) {
    return entryOrFolder(node) || node.kind() == WorkspaceNode.Kind.CYCLE;
  }

  private static List<WorkspaceNode> files(List<WorkspaceNode> rows) {
    List<WorkspaceNode> files = new ArrayList<WorkspaceNode>();
    for (WorkspaceNode row : rows) {
      if (row.kind() == WorkspaceNode.Kind.FILE) {
        files.add(row);
      }
    }
    return files;
  }

  /** Returns the file and directory rows' paths. */
  private static List<Path> paths(List<WorkspaceNode> rows) {
    List<Path> paths = new ArrayList<Path>();
    for (WorkspaceNode row : rows) {
      if (entryOrFolder(row)) {
        paths.add(row.path());
      }
    }
    return paths;
  }

  private static List<String> text(List<Path> paths) {
    List<String> text = new ArrayList<String>();
    for (Path path : paths) {
      text.add(path.toString());
    }
    return text;
  }

  /** Returns the entry rows below the shown folder. */
  private List<WorkspaceNode> entries(List<WorkspaceNode> rows) {
    List<WorkspaceNode> entries = new ArrayList<WorkspaceNode>();
    for (WorkspaceNode row : rows) {
      if (entry(row) && below(row)) {
        entries.add(row);
      }
    }
    return entries;
  }
}
