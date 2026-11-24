package io.github.ajdailey.qcaret.app.controller;

import io.github.ajdailey.qcaret.app.view.DocumentsView;
import io.github.ajdailey.qcaret.app.view.EditorView;
import io.github.ajdailey.qcaret.app.view.TabView;
import java.util.ArrayList;
import java.util.List;

/**
 * A window's tabs built in a replacement view one tab per {@link #step}, while each tab stays bound
 * to the view the window shows, for {@link DocumentController#rebindViews}. A staged pane follows
 * its document and selections; its {@link StagedInput} keeps the reports that would reach the
 * editor until the rebind.
 *
 * <p>Each step first closes the staged views of a tab that closed, moved to another window, was
 * split or unsplit, or moved out of the staged tabs' order; a later step stages it again. A tab
 * opened during staging is staged at its place among the staged tabs. The rebind adds views in the
 * target for a tab still without staged views. Confined to the UI thread.
 */
public final class StagedTabs {
  /** A tab's views in the target view. */
  private record Staged(
      OpenDocument document,
      TabView tab,
      List<EditorController> editors,
      List<EditorView> views,
      List<StagedInput> inputs) {}

  private final DocumentsView target;
  private final List<OpenDocument> documents;

  /** In the target's tab order, which is the order of {@link #documents}. */
  private final List<Staged> staged = new ArrayList<Staged>();

  /**
   * Creates the staging of a window's tabs.
   *
   * @param target the replacement view
   * @param documents the window's open documents, read at each step
   */
  StagedTabs(DocumentsView target, List<OpenDocument> documents) {
    this.target = target;
    this.documents = documents;
  }

  /**
   * Returns the replacement view.
   *
   * @return the view
   */
  DocumentsView target() {
    return target;
  }

  /**
   * Stages the first open tab that has no staged views.
   *
   * @return true when every open tab has staged views
   */
  public boolean step() {
    prune();
    for (OpenDocument d : documents) {
      if (find(d) == null) {
        stage(d);
        break;
      }
    }
    return staged.size() == documents.size();
  }

  /** Closes every staged view. */
  public void cancel() {
    for (Staged s : staged) {
      s.tab().close();
    }
    staged.clear();
  }

  /**
   * Binds a tab's editors to its staged views and forwards their kept reports.
   *
   * @param d the tab
   * @return false when the tab has no staged views
   */
  boolean bind(OpenDocument d) {
    Staged s = find(d);
    if (s == null) {
      return false;
    }
    d.tab = s.tab();
    for (int i = 0; i < s.editors().size(); i++) {
      s.editors().get(i).attach(s.views().get(i));
      s.inputs().get(i).open();
    }
    if (s.editors().size() > 1) {
      d.tab.setSideBySide(d.sideBySide);
    }
    return true;
  }

  /**
   * Closes the staged views of tabs that closed, left, changed their panes or stand out of order.
   */
  void prune() {
    int last = -1;
    for (Staged s : new ArrayList<Staged>(staged)) {
      int at = documents.indexOf(s.document());
      if (at <= last || s.document().closed || !s.editors().equals(s.document().editors)) {
        s.tab().close();
        staged.remove(s);
      } else {
        last = at;
      }
    }
  }

  private Staged find(OpenDocument d) {
    for (Staged s : staged) {
      if (s.document() == d) {
        return s;
      }
    }
    return null;
  }

  private void stage(OpenDocument d) {
    int at = documents.indexOf(d);
    int index = 0;
    while (index < staged.size() && documents.indexOf(staged.get(index).document()) < at) {
      index++;
    }
    List<EditorController> editors = new ArrayList<EditorController>(d.editors);
    List<EditorView> views = new ArrayList<EditorView>();
    List<StagedInput> inputs = new ArrayList<StagedInput>();
    EditorController first = editors.get(0);
    StagedInput input = new StagedInput(first);
    inputs.add(input);
    TabView tab = target.addTab(first.projection(), first.selections(), input, d.input, index);
    views.add(tab.firstView());
    for (int i = 1; i < editors.size(); i++) {
      EditorController e = editors.get(i);
      input = new StagedInput(e);
      inputs.add(input);
      views.add(tab.split(e.projection(), e.selections(), input, d.sideBySide));
    }
    staged.add(index, new Staged(d, tab, editors, views, inputs));
  }
}
