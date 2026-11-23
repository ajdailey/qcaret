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

  /** The running platform's traits; on GTK, those of the GTK version SWT loaded. */
  PlatformTraits PLATFORM = LoadedToolkit.traits();

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
   * Reports whether a disabled Always on Top item carries a tool tip saying the platform cannot
   * keep a window on top.
   *
   * @return true on GTK, the platform whose stacking can be unsupported
   */
  boolean explainsUnsupportedStacking();

  /**
   * Reports whether Always on Top goes through GTK 3's X11 calls; read when the stacking is chosen.
   *
   * @return true on GTK 3 when the GDK backend is {@code x11}
   */
  boolean stacksOnX11();

  /**
   * Returns the native toolkit version SWT records, for the About text.
   *
   * @return the GTK version, or null on win32 and cocoa or when SWT recorded none
   */
  String toolkitVersion();

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
   * Reports whether native controls read the dark theme setting only when created, so a system
   * theme start sets it from the system setting.
   *
   * @return true on win32
   */
  boolean themesAtCreation();

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

  /**
   * Reports whether the Workspace Browser's tree, its header and its fields take the palette's
   * colors under a dark palette. win32 SWT draws a native {@code Tree} and {@code Text} light under
   * the dark theme.
   *
   * @return true on win32
   */
  boolean colorsTreesUnderDark();

  /**
   * Reports whether the main window's toolbar row, status bar, docks, find bar and preview notices
   * take the palette's colors under a dark palette. win32 SWT draws a {@code ToolBar}, {@code
   * Composite}, {@code Label} and {@code Link} light under the dark theme.
   *
   * @return true on win32
   */
  boolean colorsChromeUnderDark();

  /**
   * Reports whether key names follow macOS.
   *
   * @return true on cocoa
   */
  boolean macKeys();

  /**
   * Reports whether the keymap follows Linux, where IBus takes Ctrl+Shift+U.
   *
   * @return true on GTK
   */
  boolean linuxKeys();

  /**
   * Reports whether SWT's {@code PrintDialog} opens. On GTK 4 SWT 3.135's {@code PrintDialog.open}
   * throws {@code UnsatisfiedLinkError} for {@code gtk_dialog_run}.
   *
   * @return false on GTK 4
   */
  boolean opensPrintDialog();

  /**
   * Returns the system cursor over a busy window or editor, an arrow with a busy mark. SWT 3.135
   * cocoa shows {@code CURSOR_APPSTARTING} as a plain arrow and {@code CURSOR_WAIT} as AppKit's
   * {@code busyButClickableCursor}.
   *
   * @return {@code SWT.CURSOR_WAIT} on cocoa, {@code SWT.CURSOR_APPSTARTING} elsewhere
   */
  int busyCursor();

  /**
   * Reports whether the system hands a launch its files as {@code SWT.OpenDocument} events, with no
   * file arguments: Finder's Open With and a Dock drop on macOS.
   *
   * @return true on cocoa
   */
  boolean handsFilesAsEvents();

  /**
   * Reports whether the system light or dark setting is polled. cocoa SWT sends no {@code
   * SWT.Settings} for an appearance change; win32 SWT sends it 2 s after {@code WM_SETTINGCHANGE}.
   *
   * @return true on win32 and cocoa
   */
  boolean pollsSystemTheme();

  /**
   * Reports whether the Window menu carries Minimize ({@code Cmd+M}) and Zoom, which SWT does not
   * add to an application-defined Window menu.
   *
   * @return true on cocoa
   */
  boolean windowMenuControls();

  /**
   * Reports whether a screen reader takes a custom control's label from its accessible description
   * and needs an explicit role: VoiceOver reads a {@code Canvas} without a role as {@code
   * SWTComposite} with a blank description and does not read the accessible name.
   *
   * @return true on cocoa
   */
  boolean labelsByDescription();

  /**
   * Reports whether the print dialog offers no Selection scope. Windows 11's dialog offers All
   * pages and a custom range; opened with {@code PrinterData.SELECTION} it shows All pages and
   * returns Selection.
   *
   * @return true on win32
   */
  boolean printDialogOmitsSelection();

  /**
   * Reports whether full screen moves the window to a space of its own: the system animates the
   * change after {@code Shell.setFullScreen} returns, the window's title bar button also enters and
   * leaves it, and the system menu bar shows when the pointer reaches the top edge. Elsewhere the
   * window keeps its menu bar and only {@code setFullScreen} changes the state.
   *
   * @return true on cocoa
   */
  boolean fullScreenSpace();

  /**
   * Reports whether a menu item shows its tool tip. SWT drops menu item tool tips on GTK 4, so a
   * disabled item there names its reason in its label.
   *
   * @return false on GTK 4
   */
  boolean showsMenuToolTips();

  /**
   * Reports whether {@code Browser.setUrl("about:blank#id")} after {@code setText} scrolls the same
   * page to the id. On win32, SWT's Edge shows {@code setText} content in a temporary file's page,
   * so the call would navigate away from it. On cocoa, SWT escapes the {@code #} as {@code %23}, so
   * the URL has no fragment and WebKit loads it as another page.
   *
   * @return false on win32 and cocoa
   */
  boolean scrollsBrowserToFragments();

  /**
   * Reports whether a menu accelerator's key reaches the focused control before the menu item runs,
   * and that control's default action then also runs: SWT on GTK 4 runs accelerators from the
   * window's shortcut controller, after the focused widget, so a {@code StyledText} inserts
   * Ctrl+Shift+M's CR. A key the focused control consumes never reaches the shortcut.
   *
   * @return true on GTK 4
   */
  boolean acceleratorsFollowFocusControl();

  /**
   * Reports whether a click on a link to {@code about:blank}, such as {@code href=""} on a {@code
   * setText} page, replaces the page with an empty one without a {@code LocationListener.changing}
   * event to cancel: WebKitGTK asks no navigation policy for {@code about:blank}. The {@code
   * changed} and {@code completed} events follow.
   *
   * @return true on GTK
   */
  boolean blankLinksReplacePage();

  /**
   * Reports whether a native widget's accessible name goes on its ATK object through SWT internals,
   * since SWT asks no Java listener for it ({@link NativeNames}).
   *
   * @return true on GTK 3
   */
  boolean namesThroughAtk();

  /**
   * Reports whether the Markdown preview follows every navigation it cancels, with no click or key
   * on the browser. SWT's Edge with JavaScript off reports no mouse or key event to the {@code
   * Browser}, so no link would follow on a gesture; with no script, meta refresh made inert and the
   * content policy, the page starts no navigation by itself.
   *
   * @return true on win32
   */
  boolean followsLinksWithoutGesture();

  /**
   * Reports whether a key pressed in a {@code Browser} reaches its {@code KeyDown} listeners and no
   * menu accelerator runs: SWT's Edge receives keys in WebView2's process and sends them to the
   * {@code Browser} from {@code AcceleratorKeyPressed}, so the window's native accelerators never
   * see them. A key a listener consumes is marked handled, and WebView2 does not act on it.
   *
   * @return true on win32
   */
  boolean browserKeysSkipAccelerators();
}
