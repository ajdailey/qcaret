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

/**
 * The workspace browser's navigation and filter rows over a {@link WorkspaceBrowseInput}: Parent,
 * Home, Favorites and Workspace buttons, the path field (Enter shows the typed folder), the filter
 * field (Enter applies it) and the Hidden check box. Favorites opens a menu of the favorite folders
 * with Add Shown Folder and Remove Shown Folder, built at each opening.
 */
final class BrowseBar {
  private final Composite root;
  private final Button parent;
  private final Button home;
  private final Button favorites;
  private final Button workspace;
  private final Text path;
  private final Text filter;
  private final Button hidden;
  private Menu menu;
  private WorkspaceBrowseInput input;

  /**
   * Creates the rows.
   *
   * @param container the dock
   */
  BrowseBar(Composite container) {
    root = new Composite(container, SWT.NONE);
    GridLayout layout = new GridLayout(4, false);
    layout.marginHeight = 2;
    layout.marginWidth = 0;
    layout.horizontalSpacing = 4;
    root.setLayout(layout);
    root.setLayoutData(new GridData(SWT.FILL, SWT.CENTER, true, false));
    parent = button(UiText.BROWSE_PARENT);
    home = button(UiText.BROWSE_HOME);
    favorites = button(UiText.BROWSE_FAVORITES);
    workspace = button(UiText.BROWSE_WORKSPACE);
    path = new Text(root, SWT.SINGLE | SWT.BORDER);
    path.setMessage(UiText.BROWSE_PATH_HINT.text());
    path.setLayoutData(new GridData(SWT.FILL, SWT.CENTER, true, false, 4, 1));
    filter = new Text(root, SWT.SINGLE | SWT.BORDER);
    filter.setMessage(UiText.BROWSE_FILTER_HINT.text());
    filter.setLayoutData(new GridData(SWT.FILL, SWT.CENTER, true, false, 3, 1));
    hidden = SwtThemeService.check(root);
    hidden.setText(UiText.BROWSE_HIDDEN.text());
    Listener listener =
        new Listener() {
          @Override
          public void handleEvent(Event event) {
            act(event.widget);
          }
        };
    for (Button button : new Button[] {parent, home, favorites, workspace, hidden}) {
      button.addListener(SWT.Selection, listener);
    }
    path.addListener(SWT.DefaultSelection, listener);
    filter.addListener(SWT.DefaultSelection, listener);
    update();
  }

  private Button button(UiText label) {
    Button button = new Button(root, SWT.PUSH);
    button.setText(label.text());
    return button;
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

  /** Shows the model's folder, filter and hidden state. */
  void update() {
    if (root.isDisposed()) {
      return;
    }
    boolean on = input != null;
    for (Button button : new Button[] {parent, home, favorites, hidden}) {
      button.setEnabled(on);
    }
    path.setEnabled(on);
    filter.setEnabled(on);
    workspace.setEnabled(on && input.browsing());
    path.setText(on ? input.shownFolder() : "");
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
      showFavorites(root.toDisplay(at.x, at.y + at.height));
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
   * Presses a button, for tests.
   *
   * @param label the button's label
   */
  void press(UiText label) {
    for (Button button : new Button[] {parent, home, favorites, workspace}) {
      if (button.getText().equals(label.text())) {
        act(button);
      }
    }
  }
}
