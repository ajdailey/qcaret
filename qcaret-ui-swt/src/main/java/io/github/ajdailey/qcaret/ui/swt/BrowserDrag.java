package io.github.ajdailey.qcaret.ui.swt;

import io.github.ajdailey.qcaret.app.view.WorkspaceNode;
import java.util.ArrayList;
import java.util.List;
import org.eclipse.swt.dnd.DND;
import org.eclipse.swt.dnd.DragSource;
import org.eclipse.swt.dnd.DragSourceAdapter;
import org.eclipse.swt.dnd.DragSourceEvent;
import org.eclipse.swt.dnd.FileTransfer;
import org.eclipse.swt.dnd.Transfer;
import org.eclipse.swt.widgets.Tree;
import org.eclipse.swt.widgets.TreeItem;

/**
 * Makes a workspace browser tree a {@link FileTransfer} drag source carrying the selected files'
 * paths. It offers Copy only and handles no {@code dragFinished}, so the file stays when a target
 * reports Move, which a GTK 3 target can (measured). Folders and the rows that are not entries are
 * left out, and a selection without a file starts no drag. A drop on a QCaret window reaches the
 * shell's file drop target, which opens the files.
 */
final class BrowserDrag {
  private final Tree tree;
  private final DragSource source;

  /**
   * Makes the tree a drag source.
   *
   * @param tree the browser tree; each item's data is its {@link WorkspaceNode}
   */
  BrowserDrag(Tree tree) {
    this.tree = tree;
    source = new DragSource(tree, DND.DROP_COPY);
    source.setTransfer(new Transfer[] {FileTransfer.getInstance()});
    source.addDragListener(
        new DragSourceAdapter() {
          @Override
          public void dragStart(DragSourceEvent event) {
            event.doit = data().length > 0;
          }

          @Override
          public void dragSetData(DragSourceEvent event) {
            event.data = data();
          }
        });
  }

  /**
   * Returns the drag source, for tests.
   *
   * @return the source
   */
  DragSource source() {
    return source;
  }

  /**
   * Returns the paths of the selected files, the drag's data.
   *
   * @return the paths, in selection order; empty when no file is selected, and no drag starts
   */
  String[] data() {
    List<String> files = new ArrayList<String>();
    for (TreeItem item : tree.getSelection()) {
      if (item.getData() instanceof WorkspaceNode node && node.kind() == WorkspaceNode.Kind.FILE) {
        files.add(node.path().toString());
      }
    }
    return files.toArray(new String[0]);
  }
}
