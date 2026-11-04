package io.github.ajdailey.qcaret.app.controller;

import io.github.ajdailey.qcaret.app.about.AboutInfo;
import io.github.ajdailey.qcaret.app.view.MainView;
import io.github.ajdailey.qcaret.app.view.ManualUiDispatcher;
import io.github.ajdailey.qcaret.app.view.WindowGeometry;
import java.util.ArrayList;
import java.util.List;

/** Records what the controller shows; every call fails off the dispatcher's UI thread. */
final class FakeView implements MainView {
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
}
