package io.github.ajdailey.qcaret.ui.swt;

import io.github.ajdailey.qcaret.app.controller.ApplicationController;
import io.github.ajdailey.qcaret.app.controller.CommandStates;
import io.github.ajdailey.qcaret.app.i18n.UiText;
import java.util.List;
import org.eclipse.swt.SWT;
import org.eclipse.swt.widgets.Event;
import org.eclipse.swt.widgets.Listener;
import org.eclipse.swt.widgets.Menu;
import org.eclipse.swt.widgets.MenuItem;

/**
 * The File menu's Recent Files and Recently Closed listings from {@link CommandStates}: each
 * rebuilt above its Clear item, the first nine numbered as mnemonics, a path's {@code &} doubled.
 */
final class RecentMenus {
  /** Widget data of the Recent Files cascade's file entries. */
  private static final String RECENT = "qcaret.recent";

  /** Widget data of the Recently Closed cascade's file entries. */
  private static final String CLOSED = "qcaret.closed";

  private final ApplicationController controller;
  private final CommandStates states;

  RecentMenus(ApplicationController controller, CommandStates states) {
    this.controller = controller;
    this.states = states;
  }

  /** Lists the recent files above Clear Recent Files, the first nine numbered as mnemonics. */
  void syncRecent(MenuItem clear) {
    if (clear == null || clear.isDisposed()) {
      return;
    }
    Menu menu = clear.getParent();
    for (MenuItem item : menu.getItems()) {
      if (RECENT.equals(item.getData())) {
        item.dispose();
      }
    }
    List<String> paths = states.recentFiles();
    for (int i = 0; i < paths.size(); i++) {
      final int index = i;
      MenuItem item = new MenuItem(menu, SWT.PUSH, i);
      String text = UiText.RECENT_FILE.format(i + 1, paths.get(i).replace("&", "&&"));
      item.setText(i < 9 ? text : text.substring(1));
      item.setData(RECENT);
      item.addListener(
          SWT.Selection,
          new Listener() {
            @Override
            public void handleEvent(Event event) {
              controller.openRecent(index);
            }
          });
    }
    if (!paths.isEmpty()) {
      new MenuItem(menu, SWT.SEPARATOR, paths.size()).setData(RECENT);
    }
  }

  /** Lists the closed files above Clear Recently Closed, the first nine numbered as mnemonics. */
  void syncClosed(MenuItem clear) {
    if (clear == null || clear.isDisposed()) {
      return;
    }
    Menu menu = clear.getParent();
    for (MenuItem item : menu.getItems()) {
      if (CLOSED.equals(item.getData())) {
        item.dispose();
      }
    }
    List<String> paths = states.closedFiles();
    for (int i = 0; i < paths.size(); i++) {
      final int index = i;
      MenuItem item = new MenuItem(menu, SWT.PUSH, i);
      String text = UiText.RECENT_FILE.format(i + 1, paths.get(i).replace("&", "&&"));
      item.setText(i < 9 ? text : text.substring(1));
      item.setData(CLOSED);
      item.addListener(
          SWT.Selection,
          new Listener() {
            @Override
            public void handleEvent(Event event) {
              controller.openClosed(index);
            }
          });
    }
    if (!paths.isEmpty()) {
      new MenuItem(menu, SWT.SEPARATOR, paths.size()).setData(CLOSED);
    }
  }
}
