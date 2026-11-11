package io.github.ajdailey.qcaret.ui.swt;

import io.github.ajdailey.qcaret.app.i18n.UiText;
import io.github.ajdailey.qcaret.app.view.WorkspaceBrowseInput;
import java.util.List;
import org.eclipse.swt.SWT;
import org.eclipse.swt.graphics.Point;
import org.eclipse.swt.graphics.Rectangle;
import org.eclipse.swt.layout.GridData;
import org.eclipse.swt.layout.GridLayout;
import org.eclipse.swt.widgets.Button;
import org.eclipse.swt.widgets.Composite;
import org.eclipse.swt.widgets.Event;
import org.eclipse.swt.widgets.Listener;
import org.eclipse.swt.widgets.Menu;
import org.eclipse.swt.widgets.MenuItem;
import org.eclipse.swt.widgets.Text;
import org.eclipse.swt.widgets.ToolItem;

/**
 * The workspace browser's navigation and filter controls over a {@link WorkspaceBrowseInput}: the
 * Parent, Home, Favorites and Workspace icon tool bar items, the path field showing the shown
 * folder (Enter shows the typed folder), the filter field (Enter applies it) and the Hidden check
 * box. The path field's tool tip says whether it shows the workspace folder; the Workspace item is
 * enabled only when it does not. Favorites opens a menu of the favorite folders with Add Shown
 * Folder and Remove Shown Folder, built at each opening.
 */
final class BrowseBar {
  private final Composite root;
  private final ToolItem parent;
  private final ToolItem home;
  private final ToolItem favorites;
  private final ToolItem workspace;
  private final Text path;
  private final Text filter;
  private final Button hidden;
  private final ToolIcons icons;
  private Menu menu;
  private WorkspaceBrowseInput input;
  private String workspaceFolder = UiText.WORKSPACE_NONE.text();

  /**
   * Creates the controls.
   *
   * @param container the dock
   * @param row the dock's icon row
   * @param icons the row's icons
   */
  BrowseBar(Composite container, Composite row, ToolIcons icons) {
    parent = icons.item(row, ToolIcons.PARENT, UiText.BROWSE_PARENT.text());
    home = icons.item(row, ToolIcons.HOME, UiText.BROWSE_HOME.text());
    favorites = icons.item(row, ToolIcons.FAVORITES, UiText.BROWSE_FAVORITES.text());
    workspace = icons.item(row, ToolIcons.WORKSPACE, UiText.BROWSE_WORKSPACE.text());
    this.icons = icons;
    root = new Composite(container, SWT.NONE);
    GridLayout layout = new GridLayout(2, false);
    layout.marginHeight = 2;
    layout.marginWidth = 0;
    layout.horizontalSpacing = 4;
    root.setLayout(layout);
    root.setLayoutData(new GridData(SWT.FILL, SWT.CENTER, true, false));
    path = new Text(root, SWT.SINGLE | SWT.BORDER);
    path.setLayoutData(field(2));
    filter = new Text(root, SWT.SINGLE | SWT.BORDER);
    filter.setMessage(UiText.BROWSE_FILTER_HINT.text());
    filter.setLayoutData(field(1));
    hidden = SwtThemeService.check(root);
    hidden.setText(UiText.BROWSE_HIDDEN.text());
    Listener listener =
        new Listener() {
          @Override
          public void handleEvent(Event event) {
            act(event.widget);
          }
        };
    for (ToolItem item : new ToolItem[] {parent, home, favorites, workspace}) {
      item.addListener(SWT.Selection, listener);
    }
    hidden.addListener(SWT.Selection, listener);
    path.addListener(SWT.DefaultSelection, listener);
    filter.addListener(SWT.DefaultSelection, listener);
    update();
  }

  /** Fills the row's width without asking for the text's width, so a long path never widens it. */
  private static GridData field(int span) {
    GridData data = new GridData(SWT.FILL, SWT.CENTER, true, false, span, 1);
    data.widthHint = 0;
    return data;
  }

  /**
   * Shows a model.
   *
   * @param browse the model, or null
   */
  void setInput(WorkspaceBrowseInput browse) {
    input = browse;
    update();
  }

  /**
   * Shows the model's folder, filter and hidden state.
   *
   * @param folder the workspace folder, or the line saying none is set
   */
  void update(String folder) {
    workspaceFolder = folder;
    update();
  }

  /** Shows the model's folder, filter and hidden state. */
  void update() {
    if (root.isDisposed()) {
      return;
    }
    boolean on = input != null;
    for (ToolItem item : new ToolItem[] {parent, home, favorites, workspace}) {
      item.setEnabled(on && (item != workspace || input.browsing()));
      icons.showState(item);
    }
    hidden.setEnabled(on);
    path.setEnabled(on);
    filter.setEnabled(on);
    String shown = on ? input.shownFolder() : "";
    path.setText(shown);
    boolean none = workspaceFolder.equals(UiText.WORKSPACE_NONE.text());
    path.setMessage(none ? workspaceFolder : UiText.BROWSE_PATH_HINT.text());
    String tip = null;
    if (!shown.isEmpty()) {
      if (!input.browsing()) {
        tip = UiText.BROWSE_PATH_WORKSPACE.text();
      } else {
        tip = none ? workspaceFolder : UiText.BROWSE_PATH_OTHER.format(workspaceFolder);
      }
    }
    path.setToolTipText(tip);
    filter.setText(on ? input.filter() : "");
    hidden.setSelection(on && input.hiddenShown());
  }

  private void act(Object widget) {
    if (input == null) {
      return;
    }
    if (widget == parent) {
      input.parent();
    } else if (widget == home) {
      input.home();
    } else if (widget == workspace) {
      input.back();
    } else if (widget == favorites) {
      Rectangle at = favorites.getBounds();
      showFavorites(favorites.getParent().toDisplay(at.x, at.y + at.height));
    } else if (widget == hidden) {
      input.setHiddenShown(hidden.getSelection());
    } else if (widget == path) {
      input.go(path.getText());
    } else if (widget == filter) {
      input.setFilter(filter.getText());
    }
  }

  /**
   * Fills the Favorites menu and shows it.
   *
   * @param at the menu's top left corner in display coordinates
   */
  void showFavorites(Point at) {
    fillFavorites();
    menu.setLocation(at);
    menu.setVisible(true);
  }

  /** Builds the Favorites menu's items from the model. */
  void fillFavorites() {
    if (menu == null) {
      menu = new Menu(root.getShell(), SWT.POP_UP);
    }
    for (MenuItem item : menu.getItems()) {
      item.dispose();
    }
    final List<String> folders = input.favorites();
    String shown = input.shownFolder();
    if (folders.isEmpty()) {
      MenuItem none = new MenuItem(menu, SWT.PUSH);
      none.setText(UiText.BROWSE_NO_FAVORITES.text());
      none.setEnabled(false);
    }
    for (final String folder : folders) {
      MenuItem item = new MenuItem(menu, SWT.PUSH);
      item.setText(folder.replace("&", "&&"));
      item.addListener(
          SWT.Selection,
          new Listener() {
            @Override
            public void handleEvent(Event event) {
              input.go(folder);
            }
          });
    }
    new MenuItem(menu, SWT.SEPARATOR);
    MenuItem add = new MenuItem(menu, SWT.PUSH);
    add.setText(UiText.BROWSE_ADD_FAVORITE.text());
    add.setEnabled(!shown.isEmpty() && !folders.contains(shown));
    add.addListener(
        SWT.Selection,
        new Listener() {
          @Override
          public void handleEvent(Event event) {
            input.addFavorite();
          }
        });
    MenuItem remove = new MenuItem(menu, SWT.PUSH);
    remove.setText(UiText.BROWSE_REMOVE_FAVORITE.text());
    remove.setEnabled(folders.contains(shown));
    remove.addListener(
        SWT.Selection,
        new Listener() {
          @Override
          public void handleEvent(Event event) {
            input.removeFavorite();
          }
        });
  }

  /**
   * Returns the Favorites menu, for tests.
   *
   * @return the menu, or null before the first opening
   */
  Menu favoritesMenu() {
    return menu;
  }

  /**
   * Returns the path field, for tests.
   *
   * @return the field
   */
  Text path() {
    return path;
  }

  /**
   * Returns the filter field, for tests.
   *
   * @return the field
   */
  Text filter() {
    return filter;
  }

  /**
   * Returns the Hidden check box, for tests.
   *
   * @return the check box
   */
  Button hidden() {
    return hidden;
  }

  /**
   * Presses a tool bar item, for tests.
   *
   * @param label the item's label
   */
  void press(UiText label) {
    for (ToolItem item : new ToolItem[] {parent, home, favorites, workspace}) {
      if (label.text().equals(item.getToolTipText())) {
        act(item);
      }
    }
  }

  /**
   * Returns the Workspace item, for tests.
   *
   * @return the item
   */
  ToolItem workspaceItem() {
    return workspace;
  }
}
