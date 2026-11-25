package io.github.ajdailey.qcaret.ui.swt.platform;

import io.github.ajdailey.qcaret.app.command.CommandId;
import java.util.Set;
import org.eclipse.swt.graphics.Image;
import org.eclipse.swt.widgets.Display;
import org.eclipse.swt.widgets.Menu;
import org.eclipse.swt.widgets.MenuItem;
import org.eclipse.swt.widgets.Shell;

/**
 * Menu differences, one implementation per platform, from {@link PlatformTraits#menuBehavior}: menu
 * icons and their ink, the image a disabled icon shows on a dark background, the Window menu's own
 * controls, the macOS application menu, the main menus on tool windows, and where a disabled item
 * names its reason. Call on the UI thread.
 *
 * <table>
 *   <caption>Implementation per platform</caption>
 *   <tr><th>Platform</th><th>Class</th><th>Menu icons</th><th>Reason</th><th>System menus</th>
 *   </tr>
 *   <tr><td>win32</td><td>{@link Win32MenuBehavior}</td><td>startup ink</td><td>tool tip</td>
 *       <td>none</td></tr>
 *   <tr><td>cocoa</td><td>{@link CocoaMenuBehavior}</td><td>none</td><td>tool tip</td>
 *       <td>application menu; Minimize and Zoom; main menus on tool windows</td></tr>
 *   <tr><td>GTK 3</td><td>{@link Gtk3MenuBehavior}</td><td>blank-padded, live ink</td>
 *       <td>tool tip</td><td>none</td></tr>
 *   <tr><td>GTK 4</td><td>{@link Gtk4MenuBehavior}</td><td>as GTK 3</td><td>label</td>
 *       <td>none</td></tr>
 * </table>
 */
public interface MenuBehavior {
  /** The running platform's menu behavior. */
  MenuBehavior PLATFORM = PlatformTraits.PLATFORM.menuBehavior();

  /**
   * Shows the icons of a menu's push items and of its cascades. cocoa menus show no images. GTK 3
   * reserves no image column, so on GTK every other item of a menu with an icon holds a blank
   * image; win32 reserves the column.
   *
   * @param menu the menu
   * @param images sets each item's image
   */
  void decorate(Menu menu, ItemImages images);

  /**
   * Creates a window's menu icon set. win32 menus take the native setting of their window's
   * creation and keep it, while the toolbar stays light, so there the ink is fixed; elsewhere it
   * follows the toolbar's.
   *
   * @param display the Display
   * @param ink creates the set
   * @param <T> the set's type
   * @return the set
   */
  <T> T ink(Display display, Ink<T> ink);

  /**
   * Returns the image a disabled menu item on a dark background shows. GTK's disabled effect
   * brightens an image on a dark background, so there it is the faded copy; elsewhere the plain
   * image, which the platform's disabled effect draws. Tool items fade through {@link
   * ChromeColors.Icons#fadeDisabled}.
   *
   * @param plain the icon
   * @param faded creates the faded copy
   * @return {@code plain} or the faded copy
   */
  Image disabledOnDark(Image plain, FadedImage faded);

  /**
   * Puts Minimize ({@code Cmd+M}) and Zoom, then a separator, at the top of a Window menu on cocoa,
   * where SWT adds neither to an application-defined Window menu. Zoom toggles the maximized state.
   * Elsewhere adds nothing.
   *
   * @param menu the Window menu
   * @param shell the window they act on
   */
  void addWindowControls(Menu menu, Shell shell);

  /**
   * Gives a tool window its owner's menus on cocoa, where the menu bar shows the key window's menus
   * and a window without a menu bar shows only the application menu. Does nothing elsewhere, where
   * a menu bar draws inside its window.
   *
   * @param tool a modeless window a main window owns
   * @param menus builds the owner's menus on the tool window
   */
  void toolWindowMenus(Shell tool, ToolMenus menus);

  /**
   * Hooks the system's application menu on cocoa: About, Preferences and Quit, a quit request from
   * the system ({@code SWT.Close}, vetoed: Cmd-Q, logout) and {@code SWT.OpenDocument}. Elsewhere
   * hooks nothing. Call once.
   *
   * @param display the Display
   * @param actions runs what the menu and the system request
   */
  void hookAppMenu(Display display, AppMenuActions actions);

  /**
   * Returns the commands the system's application menu holds, which the regular menus leave out.
   *
   * @param display the Display
   * @return About, Preferences and Quit on cocoa when SWT gives a system menu; otherwise none
   */
  Set<CommandId> systemCommands(Display display);

  /**
   * Returns an item's label. SWT drops menu item tool tips on GTK 4, so there a disabled item's
   * label carries its reason.
   *
   * @param label the command's label
   * @param reason why the item is disabled, or null
   * @return the label to show
   */
  String label(String label, String reason);

  /**
   * Shows why an item is disabled: in its tool tip, or on GTK 4 in its label.
   *
   * @param item the item
   * @param label the command's label, without key text
   * @param reason why the item is disabled, or null to clear it
   */
  void showReason(MenuItem item, String label, String reason);

  /**
   * Creates a menu icon set.
   *
   * @param <T> the set's type
   */
  interface Ink<T> {
    /**
     * Creates a set whose ink follows the toolbar's background.
     *
     * @return the set
     */
    T live();

    /**
     * Creates a set of fixed ink.
     *
     * @param dark true for the light ink
     * @return the set
     */
    T fixed(boolean dark);
  }

  /** Sets menu item images. */
  interface ItemImages {
    /**
     * Shows a push item's icon.
     *
     * @param item the item, its command as widget data
     * @return false when the item has no icon
     */
    boolean apply(MenuItem item);

    /**
     * Shows a blank image of the icon's size.
     *
     * @param item the item
     */
    void applyBlank(MenuItem item);
  }

  /** Creates an icon's faded copy. */
  interface FadedImage {
    /**
     * Returns the faded copy.
     *
     * @return the image
     */
    Image get();
  }

  /** Runs what the application menu and the system request. */
  interface AppMenuActions {
    /**
     * Runs an application-menu item.
     *
     * @param id the item's {@link MenuItem#getID()}: {@code SWT.ID_ABOUT}, {@code
     *     SWT.ID_PREFERENCES} or {@code SWT.ID_QUIT}
     */
    void select(int id);

    /** Quits on the system's request, with the unsaved-changes prompts. */
    void quit();

    /**
     * Opens a file the system names.
     *
     * @param file the event's text, possibly null or not a path
     */
    void open(String file);
  }

  /** Builds a main window's menus on one of its tool windows. */
  interface ToolMenus {
    /**
     * Sets the owner's menus as the tool window's menu bar; Close closes the tool window and every
     * other item acts on the owner.
     *
     * @param tool the tool window
     */
    void build(Shell tool);
  }
}
