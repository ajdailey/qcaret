package io.github.ajdailey.qcaret.app.controller;

import io.github.ajdailey.qcaret.app.view.HexInput;
import io.github.ajdailey.qcaret.app.view.HexMode;
import io.github.ajdailey.qcaret.app.view.HexScroll;
import io.github.ajdailey.qcaret.app.view.HexSelection;
import io.github.ajdailey.qcaret.app.view.HexView;
import io.github.ajdailey.qcaret.app.view.TabView;
import io.github.ajdailey.qcaret.core.hex.HexRow;
import io.github.ajdailey.qcaret.core.hex.HexRows;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.function.Consumer;
import java.util.function.Predicate;

/** A {@link HexView} that records what it is asked to show; a tab's pane or a viewer window. */
final class FakeHexView implements HexView {
  final HexInput input;
  final String title;
  HexRows rows;
  HexScroll scroll;
  HexSelection selection;
  boolean dimmed;
  String status = "";
  String notice;
  String noticeAction;
  String message = "";
  final List<String> messages = new ArrayList<String>();
  String findText;
  Boolean findHex;
  Predicate<String> offsetValid;
  Consumer<String> offsetAnswer;
  int redraws;
  int focusCalls;
  HexMode mode = HexMode.TEXT;
  TabView tab;
  final AtomicInteger active = new AtomicInteger();

  FakeHexView(HexInput input, String title) {
    this.input = input;
    this.title = title;
  }

  /** Paints the visible rows as the canvas does, returning how many were cached. */
  int paint() {
    int shown = 0;
    if (rows == null || scroll == null) {
      return 0;
    }
    for (long r = scroll.topRow(); r < Math.min(rows.rowCount(), scroll.topRow() + 4); r++) {
      HexRow row = input.row(r);
      if (row != null) {
        shown++;
      }
    }
    return shown;
  }

  @Override
  public void showLayout(HexRows value) {
    rows = value;
  }

  @Override
  public void showScroll(HexScroll value) {
    scroll = value;
  }

  @Override
  public void showSelection(HexSelection value) {
    selection = value;
  }

  @Override
  public void redraw() {
    redraws++;
  }

  @Override
  public void setDimmed(boolean value) {
    dimmed = value;
  }

  @Override
  public void showStatus(String text) {
    status = text;
  }

  @Override
  public void showNotice(String text, String action) {
    notice = text;
    noticeAction = action;
  }

  @Override
  public void showMessage(String text) {
    message = text;
    messages.add(text);
  }

  @Override
  public void showFind(String text, boolean hex) {
    findText = text;
    findHex = hex;
  }

  @Override
  public void askGoToOffset(Predicate<String> valid, Consumer<String> answer) {
    offsetValid = valid;
    offsetAnswer = answer;
  }

  @Override
  public void focus() {
    focusCalls++;
  }

  @Override
  public void setActive() {
    active.incrementAndGet();
  }

  @Override
  public void setIdle() {
    active.decrementAndGet();
  }
}
