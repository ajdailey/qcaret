package io.github.ajdailey.qcaret.ui.swt.platform;

import io.github.ajdailey.qcaret.app.view.UiDispatcher;
import org.eclipse.swt.SWT;
import org.eclipse.swt.graphics.Color;
import org.eclipse.swt.widgets.Composite;
import org.eclipse.swt.widgets.Control;
import org.eclipse.swt.widgets.Display;
import org.eclipse.swt.widgets.Shell;

/**
 * The view layer's platform differences: one class per SWT platform, win32, cocoa, GTK 3 and GTK 4.
 * A value a view class needs (a font, a size, a cursor, the key names) is a method here; behavior
 * that differs is a seam interface, whose implementation for the platform a method here returns.
 * {@link #PLATFORM} is the running one.
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

  // Values.

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
   * Returns the native toolkit version SWT records, for the About text.
   *
   * @return the GTK version, or null on win32 and cocoa or when SWT recorded none
   */
  String toolkitVersion();

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
   * Returns the system cursor over a busy window or editor, an arrow with a busy mark. SWT 3.135
   * cocoa shows {@code CURSOR_APPSTARTING} as a plain arrow and {@code CURSOR_WAIT} as AppKit's
   * {@code busyButClickableCursor}.
   *
   * @return {@code SWT.CURSOR_WAIT} on cocoa, {@code SWT.CURSOR_APPSTARTING} elsewhere
   */
  int busyCursor();

  // Seams.

  /**
   * Creates a main window's chrome colors.
   *
   * @param display the Display, after the theme service records the native start
   * @return {@link Win32ChromeColors} on win32, else {@link NativeChromeColors}
   */
  ChromeColors chromeColors(Display display);

  /**
   * Returns the dialog colors and check box creation.
   *
   * @return {@link Win32DialogColors} on win32, else {@link NativeDialogColors}
   */
  DialogColors dialogColors();

  /**
   * Creates a dialog's tab folder. On win32 this reads {@link DialogColors#nativeDark}.
   *
   * @param parent the dialog composite holding it
   * @return {@link Win32DialogTabs} on win32 under the dark native setting, {@link CocoaDialogTabs}
   *     on cocoa, else {@link NativeDialogTabs}
   */
  DialogTabs dialogTabs(Composite parent);

  /**
   * Creates how native controls follow the theme on a Display.
   *
   * @param display the Display
   * @return the platform's {@link ThemeFollow}
   */
  ThemeFollow themeFollow(Display display);

  /**
   * Returns the menu differences.
   *
   * @return the platform's {@link MenuBehavior}
   */
  MenuBehavior menuBehavior();

  /**
   * Returns how tool items keep their icon's size.
   *
   * @return {@link Gtk4ToolItemSizing} on GTK 4, else {@link NativeToolItemSizing}
   */
  ToolItemSizing toolItemSizing();

  /**
   * Returns the key handling differences.
   *
   * @return the platform's {@link KeyBehavior}
   */
  KeyBehavior keyBehavior();

  /**
   * Returns the embedded browser differences.
   *
   * @return the platform's {@link BrowserBehavior}
   */
  BrowserBehavior browserBehavior();

  /**
   * Returns the print differences.
   *
   * @return the platform's {@link PrintSupport}
   */
  PrintSupport printSupport();

  /**
   * Creates a main window's full screen state.
   *
   * @param shell the main window's shell
   * @param ui runs the cocoa transition's timer
   * @return {@link CocoaFullScreen} on cocoa, else {@link NativeFullScreen}
   */
  FullScreen fullScreen(Shell shell, UiDispatcher ui);

  /**
   * Creates the Always on Top stacking; GTK 3 reads {@link #GDK_BACKEND} here.
   *
   * @return the platform's {@link WindowStacking}
   */
  WindowStacking stacking();

  /**
   * Returns how a main window is placed.
   *
   * @return the platform's {@link WindowPlacement}
   */
  WindowPlacement windowPlacement();

  /**
   * Returns how native widgets take the names a screen reader reads.
   *
   * @return the platform's {@link NativeNames}
   */
  NativeNames nativeNames();

  /**
   * Returns how controls named through a listener are labeled.
   *
   * @return {@link CocoaAccessibleLabels} on cocoa, else {@link NativeAccessibleLabels}
   */
  AccessibleLabels accessibleLabels();

  /**
   * Returns how the user guide shows in the application.
   *
   * @return {@link NoticeGuideDisplay} on GTK 4, else {@link BrowserGuideDisplay}
   */
  GuideDisplay guideDisplay();

  /**
   * Returns the open-document events a launch receives.
   *
   * @return {@link CocoaLaunchDocuments} on cocoa, else {@link NativeLaunchDocuments}
   */
  LaunchDocuments launchDocuments();
}
