package io.github.ajdailey.qcaret.ui.swt;

import io.github.ajdailey.qcaret.app.command.CommandId;
import io.github.ajdailey.qcaret.ui.swt.platform.MenuBehavior;
import io.github.ajdailey.qcaret.ui.swt.platform.PlatformTraits;
import org.eclipse.swt.SWT;
import org.eclipse.swt.widgets.Control;
import org.eclipse.swt.widgets.Menu;
import org.eclipse.swt.widgets.MenuItem;

/**
 * The icons of a window's menu items: {@link #icon} maps a command to its icon, and {@link
 * #decorate} shows them on the push items of a built menu. Check, radio, cascade and listing items
 * show none. The ink, the padding and whether menus show images at all differ by platform ({@link
 * MenuBehavior}). GTK 4 SWT ignores {@code MenuItem.setImage}. Call on the UI thread.
 */
final class MenuIcons {
  private final ToolIcons icons;
  private final MenuBehavior platform;

  /**
   * Creates the icons of the running platform.
   *
   * @param owner the control whose background the theme shows; the images are disposed with it
   */
  MenuIcons(Control owner) {
    this(owner, PlatformTraits.PLATFORM.menuBehavior());
  }

  /**
   * Creates the icons of a platform.
   *
   * @param owner the control whose background the theme shows; the images are disposed with it
   * @param platform the platform's menu behavior
   */
  MenuIcons(final Control owner, MenuBehavior platform) {
    this.platform = platform;
    icons =
        platform.ink(
            owner.getDisplay(),
            new MenuBehavior.Ink<ToolIcons>() {
              @Override
              public ToolIcons live() {
                return new ToolIcons(owner);
              }

              @Override
              public ToolIcons fixed(boolean dark) {
                return new ToolIcons(owner, dark);
              }
            });
  }

  /**
   * Returns a command's icon.
   *
   * @param command the command
   * @return a {@link ToolIcons} name, or null for a command without one
   */
  static String icon(CommandId command) {
    return switch (command) {
      case NEW -> ToolIcons.NEW;
      case OPEN -> ToolIcons.OPEN;
      case SAVE -> ToolIcons.SAVE;
      case SAVE_AS -> ToolIcons.SAVE_AS;
      case SAVE_ALL -> ToolIcons.SAVE_ALL;
      case PRINT, PRINT_MARKDOWN -> ToolIcons.PRINT;
      case EXPORT_HTML -> ToolIcons.EXPORT_HTML;
      case RELOAD -> ToolIcons.RELOAD;
      case CLOSE -> ToolIcons.CLOSE;
      case QUIT -> ToolIcons.QUIT;
      case UNDO -> ToolIcons.UNDO;
      case REDO -> ToolIcons.REDO;
      case CUT -> ToolIcons.CUT;
      case COPY -> ToolIcons.COPY;
      case PASTE -> ToolIcons.PASTE;
      case SELECT_ALL -> ToolIcons.SELECT_ALL;
      case PREFERENCES -> ToolIcons.PREFERENCES;
      case GO_TO_LINE -> ToolIcons.GO_TO_LINE;
      case ZOOM_IN -> ToolIcons.ZOOM_IN;
      case ZOOM_OUT -> ToolIcons.ZOOM_OUT;
      case FIND -> ToolIcons.SEARCH;
      case FIND_NEXT -> ToolIcons.FIND_NEXT;
      case FIND_PREVIOUS -> ToolIcons.FIND_PREVIOUS;
      case REPLACE -> ToolIcons.REPLACE;
      case HYPERSEARCH -> ToolIcons.HYPERSEARCH;
      case USER_GUIDE -> ToolIcons.HELP;
      case ABOUT -> ToolIcons.ABOUT;
      default -> null;
    };
  }

  /**
   * Shows the icons of a menu and of its cascades; call once its items exist. An item's {@link
   * CommandId} is its widget data.
   *
   * @param menu the menu
   */
  void decorate(Menu menu) {
    platform.decorate(
        menu,
        new MenuBehavior.ItemImages() {
          @Override
          public boolean apply(MenuItem item) {
            String name = pushIcon(item);
            return name != null && icons.apply(item, name);
          }

          @Override
          public void applyBlank(MenuItem item) {
            icons.applyBlank(item);
          }
        });
  }

  private static String pushIcon(MenuItem item) {
    boolean push = (item.getStyle() & SWT.PUSH) != 0;
    return push && item.getData() instanceof CommandId command ? icon(command) : null;
  }

  /**
   * Shows the image of an item's enabled state; call after changing it.
   *
   * @param item the item
   */
  void showState(MenuItem item) {
    icons.showState(item);
  }

  /** Picks the ink again; see {@link ToolIcons#reink}. */
  void reink() {
    icons.reink();
  }

  /**
   * Returns the image set.
   *
   * @return the icons
   */
  ToolIcons icons() {
    return icons;
  }
}
