package io.github.ajdailey.qcaret.ui.swt;

import io.github.ajdailey.qcaret.app.editor.Bookmarks;
import io.github.ajdailey.qcaret.app.editor.DisplayProjection;
import io.github.ajdailey.qcaret.app.editor.LineChanges;
import io.github.ajdailey.qcaret.app.editor.OverviewMarks;
import io.github.ajdailey.qcaret.app.editor.TokenSource;
import io.github.ajdailey.qcaret.app.view.EditorInput;
import io.github.ajdailey.qcaret.app.view.EditorView;
import io.github.ajdailey.qcaret.app.view.TabInput;
import io.github.ajdailey.qcaret.app.view.TabView;
import io.github.ajdailey.qcaret.app.view.ViewLayout;
import io.github.ajdailey.qcaret.core.document.SelectionListener;
import io.github.ajdailey.qcaret.core.document.SelectionModel;
import io.github.ajdailey.qcaret.ui.swt.editor.EditorPane;
import java.util.ArrayList;
import java.util.List;
import org.eclipse.swt.SWT;
import org.eclipse.swt.custom.CTabItem;
import org.eclipse.swt.custom.SashForm;
import org.eclipse.swt.widgets.Event;
import org.eclipse.swt.widgets.Listener;

/**
 * One document tab of a {@link TabStrip}: its item, which a move recreates, and one or two editor
 * views in a {@link SashForm}, one above the other or side by side. The view focused last is the
 * tab's focused view. Created and used on the UI thread.
 */
final class EditorTab implements TabView, SelectionListener {
  final SashForm sash;
  final List<EditorPane> panes = new ArrayList<EditorPane>(2);
  final List<View> views = new ArrayList<View>(2);
  final List<SelectionModel> models = new ArrayList<SelectionModel>(2);
  final TabInput input;
  EditorPane pane;
  String title = "";
  String toolTip = "";
  CTabItem item;
  private final TabStrip strip;
  private final TabStrip.Host host;

  /** A view as its controller sees it; focusing it makes it the tab's focused view. */
  private final class View implements EditorView {
    final EditorPane editor;

    View(EditorPane editor) {
      this.editor = editor;
    }

    @Override
    public void showCaret() {
      editor.showCaret();
    }

    @Override
    public int topRow() {
      return editor.topRow();
    }

    @Override
    public void scrollToRow(int row) {
      editor.scrollToRow(row);
    }

    @Override
    public void focus() {
      pane = editor;
      editor.focus();
    }

    @Override
    public void showMatches(int[] ranges) {
      editor.showMatches(ranges);
    }

    @Override
    public ViewLayout layout() {
      return editor.layout();
    }

    @Override
    public void setTabWidth(int columns) {
      editor.setTabWidth(columns);
    }

    @Override
    public void setColumnMode(boolean on) {
      editor.setColumnMode(on);
    }

    @Override
    public void setSyntax(TokenSource tokens) {
      editor.setSyntax(tokens);
    }

    @Override
    public void redrawHighlights() {
      editor.redrawHighlights();
    }

    @Override
    public void showBrackets(int[] ranges) {
      editor.showBrackets(ranges);
    }

    @Override
    public void showOccurrences(int[] ranges) {
      editor.showOccurrences(ranges);
    }

    @Override
    public void setBookmarks(Bookmarks bookmarks) {
      editor.setBookmarks(bookmarks);
    }

    @Override
    public void setMarks(boolean whitespace, boolean lineEndings) {
      editor.setMarks(whitespace, lineEndings);
    }

    @Override
    public void setBusy(boolean busy) {
      editor.setBusy(busy);
    }

    @Override
    public void showCompletions(List<String> words, int selected) {
      editor.showCompletions(words, selected);
    }

    @Override
    public void showOverview(OverviewMarks marks) {
      editor.showOverview(marks);
    }

    @Override
    public void setOverviewShown(boolean shown) {
      editor.setOverviewShown(shown);
    }

    @Override
    public void showLineChanges(LineChanges changes) {
      editor.showLineChanges(changes);
    }
  }

  /**
   * Creates the tab's item at an index.
   *
   * @param strip the strip holding the tab
   * @param host the window around the strip
   * @param sash the tab's control
   * @param input the tab's controller
   * @param index the index
   */
  EditorTab(TabStrip strip, TabStrip.Host host, SashForm sash, TabInput input, int index) {
    this.strip = strip;
    this.host = host;
    this.sash = sash;
    this.input = input;
    this.item = strip.newItem(this, index);
  }

  /** Adds a view; its focus selects it as the tab's focused view. */
  EditorPane add(
      DisplayProjection projection, SelectionModel selections, final EditorInput editor) {
    final EditorPane added = host.newPane(sash, projection, selections, editor);
    added
        .text()
        .addListener(
            SWT.MenuDetect,
            new Listener() {
              @Override
              public void handleEvent(Event event) {
                event.doit = false;
                pane = added;
                host.showEditorMenu(added, editor, event);
              }
            });
    added
        .text()
        .addListener(
            SWT.FocusIn,
            new Listener() {
              @Override
              public void handleEvent(Event event) {
                if (pane != added && panes.contains(added)) {
                  pane = added;
                  input.viewFocused(editor);
                }
              }
            });
    panes.add(added);
    views.add(new View(added));
    models.add(selections);
    selections.addListener(this);
    if (pane == null) {
      pane = added;
    }
    return added;
  }

  @Override
  public void selectionsChanged(SelectionModel model) {
    if (!item.isDisposed() && strip.folder().getSelection() == item) {
      host.scheduleStatus();
    }
  }

  @Override
  public void setTitle(String value, String tip) {
    title = value;
    toolTip = tip;
    if (!item.isDisposed()) {
      item.setText(value.replace("&", "&&"));
      item.setToolTipText(tip.replace("&", "&&"));
    }
  }

  @Override
  public void select() {
    if (!item.isDisposed()) {
      strip.folder().setSelection(item);
      pane.focus();
      input.activated();
    }
  }

  @Override
  public void showCaret() {
    pane.showCaret();
  }

  @Override
  public void close() {
    for (SelectionModel model : models) {
      model.removeListener(this);
    }
    if (!item.isDisposed()) {
      strip.removeItem(item);
    }
    for (EditorPane p : panes) {
      p.release();
    }
    strip.disposeLater(sash);
    CTabItem next = strip.folder().getSelection();
    if (next != null && next.getData() instanceof EditorTab shown) {
      shown.pane.focus();
    }
  }

  @Override
  public EditorView firstView() {
    return views.get(0);
  }

  @Override
  public EditorView split(
      DisplayProjection projection,
      SelectionModel selections,
      EditorInput editor,
      boolean sideBySide) {
    sash.setOrientation(sideBySide ? SWT.HORIZONTAL : SWT.VERTICAL);
    add(projection, selections, editor);
    sash.setWeights(new int[] {1, 1});
    sash.layout(true);
    View added = views.get(views.size() - 1);
    added.focus();
    return added;
  }

  @Override
  public void setSideBySide(boolean sideBySide) {
    sash.setOrientation(sideBySide ? SWT.HORIZONTAL : SWT.VERTICAL);
  }

  @Override
  public int[] splitWeights() {
    return sash.getWeights();
  }

  @Override
  public void setSplitWeights(int[] weights) {
    sash.setWeights(weights);
    sash.layout(true);
  }

  @Override
  public void unsplit(EditorView kept) {
    for (int i = panes.size() - 1; i >= 0; i--) {
      if (views.get(i) != kept) {
        models.remove(i).removeListener(this);
        views.remove(i);
        panes.remove(i).dispose();
      }
    }
    pane = panes.get(0);
    sash.layout(true);
    pane.focus();
  }
}
