package io.github.ajdailey.qcaret.ui.swt.platform;

import org.eclipse.swt.SWT;
import org.eclipse.swt.graphics.Color;
import org.eclipse.swt.widgets.Control;

/**
 * The view layer's platform differences: one class per SWT platform, win32, cocoa, GTK 3 and GTK 4,
 * answers each question a view class asks. {@link #PLATFORM} is the running one.
 */
public interface PlatformTraits {
  /** System property SWT sets on GTK to {@code x11}, {@code wayland} or {@code unknown}. */
  String GDK_BACKEND = "org.eclipse.swt.internal.gdk.backend";

  /** The running platform's traits; GTK 4 is the {@code SWT_GTK4=1} environment SWT reads. */
  PlatformTraits PLATFORM = of(SWT.getPlatform(), "1".equals(System.getenv("SWT_GTK4")));

  /**
   * Returns a platform's traits.
   *
   * @param platform an {@link SWT#getPlatform} value
   * @param gtk4 true for GTK 4 when the platform is {@code gtk}
   * @return the traits
   * @throws IllegalArgumentException for a platform SWT does not build
   */
  static PlatformTraits of(String platform, boolean gtk4) {
    return switch (platform) {
      case "win32" -> new Win32Traits();
      case "cocoa" -> new CocoaTraits();
      case "gtk" -> gtk4 ? new Gtk4Traits() : new Gtk3Traits();
      default -> throw new IllegalArgumentException(platform);
    };
  }

  /**
   * Returns the SWT platform name.
   *
   * @return {@code win32}, {@code cocoa} or {@code gtk}
   */
  String name();

  /**
   * Returns the monospace font family of dialogs and the editor's default.
   *
   * @return Consolas on win32, Menlo on cocoa, Monospace on GTK
   */
  String monospaceFamily();

  /**
   * Returns the default monospace size.
   *
   * @return points: 12 on cocoa, 10 elsewhere
   */
  int monospaceSize();

  /**
   * Returns how the platform draws a caret image over the text.
   *
   * @return the operator
   */
  CaretBlend caretBlend();

  /**
   * Reports whether Alt and a key types the key's character, as Option does on macOS.
   *
   * @return true on cocoa
   */
  boolean typesWithAlt();

  /**
   * Reports whether the system supplies the application menu.
   *
   * @return true on cocoa
   */
  boolean systemAppMenu();

  /**
   * Reports whether an empty printer list means no printer. GTK lists printers only after every
   * print backend answers, and its dialog always offers Print to File.
   *
   * @return false on GTK
   */
  boolean checksPrinterList();

  /**
   * Reports whether a {@code Browser} can show the user guide. WebKitGTK 6.0 aborts the JVM when
   * the browser starts under GTK 4 ("Failed to fully launch dbus-proxy").
   *
   * @return false on GTK 4
   */
  boolean opensBrowser();

  /**
   * Reports whether a shell can be moved to a location; read at each placement.
   *
   * @return false on GTK 4 and on GTK 3 under Wayland
   */
  boolean positionsWindows();

  /**
   * Reports whether a placement is made again with the trim measured from the sized shell. win32's
   * {@code computeTrim} overstates the height once a menu bar is set.
   *
   * @return true on win32
   */
  boolean remeasuresTrim();

  /**
   * Reports whether a file opens in its system application by file extension.
   *
   * @return true on win32
   */
  boolean launchesByExtension();

  /**
   * Reports whether a disabled Always on Top item carries a tool tip saying the platform cannot
   * keep a window on top.
   *
   * @return true on GTK, the platform whose stacking can be unsupported
   */
  boolean explainsUnsupportedStacking();

  /**
   * Reports whether menu items show images.
   *
   * @return false on cocoa
   */
  boolean menuImages();

  /**
   * Reports whether every other item of a menu with an icon holds a blank image, since the menu
   * reserves no image column and a label moves right when its item has an image.
   *
   * @return true on GTK
   */
  boolean padsMenuImages();

  /**
   * Reports whether menu icon ink comes from the native theme's startup setting. win32 menus take
   * that setting and keep it, and the toolbar stays light under a dark one.
   *
   * @return true on win32
   */
  boolean fixedMenuInk();

  /**
   * Returns the background a control shows now. GTK refreshes {@code getBackground} only on the
   * first {@code Display.sleep} after a theme change, so a visible control is read from a pixel it
   * draws.
   *
   * @param source the control
   * @return the color
   */
  Color shownBackground(Control source);

  /**
   * Reports whether a disabled icon on a dark background takes a faded copy. GTK's disabled effect
   * brightens an image there.
   *
   * @return true on GTK
   */
  boolean fadesDisabledIcons();

  /**
   * Reports whether a tool item's picture needs its icon's size as a minimum through SWT internals,
   * because the box GTK 4 SWT wraps it in measures 0 high under GTK 4.14.
   *
   * @return true on GTK 4
   */
  boolean sizesToolPictures();

  /**
   * Reports whether {@code Display.setDarkThemePreferred} restyles native controls at once.
   * Elsewhere win32 controls read it when created and cocoa follows the system appearance.
   *
   * @return true on GTK
   */
  boolean switchesThemeLive();

  /**
   * Reports whether native controls always take the system appearance, which QCaret never sets and
   * polls for changes.
   *
   * @return true on cocoa
   */
  boolean followsSystemAppearance();

  /**
   * Reports whether a {@code Link} takes a link color raised to readable contrast, and again at
   * each theme change. Adwaita's dark link color has 3.25 contrast on its window background, and a
   * link keeps the color of its creation.
   *
   * @return true on GTK
   */
  boolean raisesLinkContrast();

  /**
   * Reports whether a check box outside a colored dialog is created with the native dark theme off.
   * The dark theme draws a check box label near-white on win32 while docks and bars stay light.
   *
   * @return true on win32
   */
  boolean lightChecksUnderDark();

  /**
   * Reports whether dialogs take QCaret's dialog colors under a dark startup setting. win32 draws a
   * dark title bar over a light dialog body.
   *
   * @return true on win32
   */
  boolean colorsDialogs();
}
