package io.github.ajdailey.qcaret.app.controller;

import io.github.ajdailey.qcaret.app.about.AboutInfo;
import io.github.ajdailey.qcaret.app.editor.DisplayProjection;
import io.github.ajdailey.qcaret.app.view.EditorInput;
import io.github.ajdailey.qcaret.app.view.MainView;
import io.github.ajdailey.qcaret.app.view.ManualUiDispatcher;
import io.github.ajdailey.qcaret.app.view.TabInput;
import io.github.ajdailey.qcaret.app.view.TabView;
import io.github.ajdailey.qcaret.app.view.UnsavedChoice;
import io.github.ajdailey.qcaret.app.view.WindowGeometry;
import io.github.ajdailey.qcaret.core.document.SelectionModel;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.function.Consumer;

/**
 * Records what the controllers show and holds each prompt's callback for the test to answer; every
 * call fails off the dispatcher's UI thread.
 */
final class FakeView implements MainView {
  /** A tab: its title and what the controller asked of it. */
  final class FakeTab implements TabView {
    final DisplayProjection projection;
    final SelectionModel selections;
    final EditorInput editor;
    final TabInput input;
    String title;
    String toolTip;
    int caretShown;
    boolean closed;

    FakeTab(
        DisplayProjection projection,
        SelectionModel selections,
        EditorInput editor,
        TabInput input) {
      this.projection = projection;
      this.selections = selections;
      this.editor = editor;
      this.input = input;
    }

    @Override
    public void setTitle(String value, String tip) {
      ui.checkUiThread();
      title = value;
      toolTip = tip;
    }

    @Override
    public void select() {
      ui.checkUiThread();
      selected = this;
      input.activated();
    }

    @Override
    public void showCaret() {
      ui.checkUiThread();
      caretShown++;
    }

    @Override
    public void close() {
      ui.checkUiThread();
      closed = true;
      tabs.remove(this);
      if (selected == this) {
        selected = tabs.isEmpty() ? null : tabs.get(tabs.size() - 1);
        if (selected != null) {
          selected.input.activated();
        }
      }
    }
  }

  final List<FakeTab> tabs = new ArrayList<FakeTab>();
  FakeTab selected;
  Consumer<List<Path>> openChooser;
  Consumer<Path> saveChooser;
  String saveName;
  Consumer<UnsavedChoice> unsaved;
  String unsavedName;
  final ManualUiDispatcher ui;
  final List<String> infos = new ArrayList<String>();
  WindowGeometry.Request bounds = new WindowGeometry.Request(null, null, null, null);
  boolean maximized;
  boolean hidden;
  boolean disposed;
  String textTitle;
  String text;
  AboutInfo about;
  String clipboard;

  FakeView(ManualUiDispatcher ui) {
    this.ui = ui;
  }

  @Override
  public void hide() {
    ui.checkUiThread();
    hidden = true;
  }

  @Override
  public void dispose() {
    ui.checkUiThread();
    disposed = true;
  }

  @Override
  public void showInfo(String title, String message) {
    ui.checkUiThread();
    infos.add(title + ": " + message);
  }

  @Override
  public void showText(String title, String value) {
    ui.checkUiThread();
    textTitle = title;
    text = value;
  }

  @Override
  public void showAbout(AboutInfo info) {
    ui.checkUiThread();
    about = info;
  }

  @Override
  public String toolkitDescription() {
    ui.checkUiThread();
    return "mock toolkit";
  }

  @Override
  public WindowGeometry.Request windowBounds() {
    ui.checkUiThread();
    return bounds;
  }

  @Override
  public boolean windowMaximized() {
    ui.checkUiThread();
    return maximized;
  }

  @Override
  public void setText(String value) {
    ui.checkUiThread();
    clipboard = value;
  }

  @Override
  public String clipboardText() {
    ui.checkUiThread();
    return clipboard;
  }

  @Override
  public TabView addTab(
      DisplayProjection projection, SelectionModel selections, EditorInput editor, TabInput tab) {
    ui.checkUiThread();
    FakeTab fake = new FakeTab(projection, selections, editor, tab);
    tabs.add(fake);
    selected = fake;
    return fake;
  }

  @Override
  public void chooseFilesToOpen(Path directory, Consumer<List<Path>> chosen) {
    ui.checkUiThread();
    openChooser = chosen;
  }

  @Override
  public void chooseSaveTarget(Path directory, String name, Consumer<Path> chosen) {
    ui.checkUiThread();
    saveName = name;
    saveChooser = chosen;
  }

  @Override
  public void askUnsaved(String name, Consumer<UnsavedChoice> answer) {
    ui.checkUiThread();
    unsavedName = name;
    unsaved = answer;
  }
}
