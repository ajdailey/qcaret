package io.github.ajdailey.qcaret.ui.swt.platform;

import org.eclipse.swt.SWT;
import org.eclipse.swt.graphics.Rectangle;
import org.eclipse.swt.widgets.Canvas;
import org.eclipse.swt.widgets.Composite;
import org.eclipse.swt.widgets.Control;
import org.eclipse.swt.widgets.Event;
import org.eclipse.swt.widgets.Listener;

/**
 * Dialog tabs on cocoa: the native {@code TabFolder} ({@link NativeDialogTabs}), with keyboard
 * focus kept on a control across a tab change. SWT hides the old page, and when no ancestor of its
 * focused control takes focus AppKit's first responder becomes the window; {@code NSTabView} takes
 * no focus with Full Keyboard Access off. The next Ctrl+PageDown or Ctrl+PageUp then reaches no
 * control; SWT then reports the shell or no control as focused. A tab change that leaves no visible
 * control, the shell or {@link #sink} focused focuses the new page's first control that takes
 * focus, else {@link #sink}.
 */
final class CocoaDialogTabs implements DialogTabs {
  private final NativeDialogTabs tabs;

  /**
   * A childless canvas in the folder, below the pages, that takes focus where no page control does
   * (a page of check boxes). Its page traversals reach the folder.
   */
  private final Canvas sink;

  /**
   * Creates the folder.
   *
   * @param parent the dialog composite holding it
   */
  CocoaDialogTabs(Composite parent) {
    tabs = new NativeDialogTabs(parent);
    sink = new Canvas(tabs.control(), SWT.NONE);
    // A canvas takes focus on cocoa only while it has a key listener.
    sink.addListener(
        SWT.KeyDown,
        new Listener() {
          @Override
          public void handleEvent(Event event) {}
        });
    tabs.addSelectionListener(
        new Runnable() {
          @Override
          public void run() {
            keepFocus();
          }
        });
  }

  private void keepFocus() {
    Control focus = sink.getDisplay().getFocusControl();
    if (focus != null && focus != sink && focus != sink.getShell() && focus.isVisible()) {
      return;
    }
    int index = tabs.selection();
    // win32 selects the first item, with an event, before add() sets its page.
    Composite page = index < 0 ? null : tabs.page(index);
    if (page == null || !page.setFocus()) {
      Rectangle area = tabs.control().getClientArea();
      sink.setBounds(area.x, area.y, 1, 1);
      sink.forceFocus();
    }
  }

  /**
   * Returns the control that takes focus where no page control does; for tests.
   *
   * @return the canvas
   */
  Control sink() {
    return sink;
  }

  @Override
  public Composite control() {
    return tabs.control();
  }

  @Override
  public Composite add(String title) {
    Composite page = tabs.add(title);
    sink.moveBelow(null);
    return page;
  }

  @Override
  public Composite page(int index) {
    return tabs.page(index);
  }

  @Override
  public void select(int index) {
    tabs.select(index);
  }

  @Override
  public int selection() {
    return tabs.selection();
  }

  @Override
  public int count() {
    return tabs.count();
  }

  @Override
  public void addSelectionListener(Runnable listener) {
    tabs.addSelectionListener(listener);
  }
}
