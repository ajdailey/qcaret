package io.github.ajdailey.qcaret.app.controller;

import io.github.ajdailey.qcaret.app.cli.FileRequest;
import io.github.ajdailey.qcaret.app.cli.StartupRequest;
import io.github.ajdailey.qcaret.app.config.WindowState;
import io.github.ajdailey.qcaret.app.i18n.UiText;
import io.github.ajdailey.qcaret.app.view.MainView;
import io.github.ajdailey.qcaret.app.view.TabInput;
import io.github.ajdailey.qcaret.app.view.WindowGeometry;
import io.github.ajdailey.qcaret.core.document.Document;
import java.util.ArrayList;
import java.util.List;

/**
 * One window's part in the multiple windows: the command line's files, tab moves out of it, the
 * Window menu's list and its placement record. The contracts are on the {@link
 * ApplicationController} methods that delegate here. Confined to the UI thread.
 */
final class WindowCommands {
  private final ApplicationController window;
  private final WindowSet set;
  private final MainView view;
  private final Settings settings;
  private final DocumentController documents;
  private final CompareController compare;

  /**
   * Creates the commands of one window.
   *
   * @param window the window
   * @param set the windows of the instance
   * @param view the window's view
   * @param settings the running settings
   * @param documents the window's documents
   * @param compare the window's comparisons
   */
  WindowCommands(
      ApplicationController window,
      WindowSet set,
      MainView view,
      Settings settings,
      DocumentController documents,
      CompareController compare) {
    this.window = window;
    this.set = set;
    this.view = view;
    this.settings = settings;
    this.documents = documents;
    this.compare = compare;
  }

  /** See {@link ApplicationController#open}. */
  void open(StartupRequest request, boolean untitled) {
    for (FileRequest file : request.files()) {
      documents.open(file);
    }
    if (untitled && request.files().isEmpty() && documents.count() == 0) {
      documents.newDocument();
    }
    if (request.diffLeft() != null) {
      compare.compareFiles(request.diffLeft(), request.diffRight());
    }
  }

  /** See {@link ApplicationController#moveTab}. */
  void moveTab(TabInput tab, ApplicationController target, int index) {
    Document document = documents.documentOf(tab);
    if (document != null) {
      set.moveDocument(window, document, target, new WindowSet.Placement(index, null, null));
    }
  }

  /** See {@link ApplicationController#moveTabToNewWindow}. */
  void moveTabToNewWindow(TabInput tab, Integer x, Integer y) {
    Document document = documents.documentOf(tab);
    if (document != null) {
      set.moveDocument(window, document, null, new WindowSet.Placement(0, x, y));
    }
  }

  /** Moves the selected tab to a new window; Move to New Window. */
  void moveActiveToNewWindow() {
    Document document = documents.activeDocument();
    if (document != null) {
      set.moveDocument(window, document, null, new WindowSet.Placement(0, null, null));
    }
  }

  /** See {@link ApplicationController#windowLabels}. */
  List<String> windowLabels() {
    List<String> labels = new ArrayList<String>();
    for (ApplicationController each : set.windows()) {
      String name = each.documents().activeName();
      labels.add(name == null ? UiText.WINDOW_EMPTY.text() : name);
    }
    return labels;
  }

  /** See {@link ApplicationController#windowIndex}. */
  int windowIndex() {
    return set.windows().indexOf(window);
  }

  /** See {@link ApplicationController#showWindow}. */
  void showWindow(int index) {
    List<ApplicationController> windows = set.windows();
    if (index >= 0 && index < windows.size()) {
      windows.get(index).toFront();
    }
  }

  /** See {@link ApplicationController#rememberWindow}. */
  void rememberWindow() {
    if (set.rememberWindow()) {
      WindowGeometry.Request bounds = view.windowBounds();
      settings.record(
          settings
              .current()
              .withWindow(
                  new WindowState(
                      bounds.x(),
                      bounds.y(),
                      bounds.width(),
                      bounds.height(),
                      view.windowMaximized())));
    }
  }
}
