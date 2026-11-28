package io.github.ajdailey.qcaret.ui.swt.platform;

import io.github.ajdailey.qcaret.app.view.UiDispatcher;
import org.eclipse.swt.SWT;
import org.eclipse.swt.graphics.Color;
import org.eclipse.swt.graphics.GC;
import org.eclipse.swt.graphics.Image;
import org.eclipse.swt.graphics.ImageData;
import org.eclipse.swt.graphics.RGB;
import org.eclipse.swt.widgets.Composite;
import org.eclipse.swt.widgets.Control;
import org.eclipse.swt.widgets.Display;
import org.eclipse.swt.widgets.Shell;

/** What GTK 3 and GTK 4 share. */
abstract class GtkTraits implements PlatformTraits {
  /** Side of the image a visible control is drawn into; the corner holds no item. */
  private static final int SAMPLE = 2;

  /** System property SWT sets on GTK to the GTK version. */
  static final String GTK_VERSION = "org.eclipse.swt.internal.gtk.version";

  @Override
  public final String name() {
    return "gtk";
  }

  @Override
  public final String monospaceFamily() {
    return "Monospace";
  }

  @Override
  public final int monospaceSize() {
    return 10;
  }

  @Override
  public final String toolkitVersion() {
    String version = System.getProperty(GTK_VERSION);
    return version == null ? "" : "GTK " + version;
  }

  @Override
  public final Color shownBackground(Control source) {
    if (!source.isVisible()) {
      return source.getBackground();
    }
    Image image = new Image(source.getDisplay(), SAMPLE, SAMPLE);
    GC gc = new GC(image);
    try {
      if (!source.print(gc)) {
        return source.getBackground();
      }
      ImageData data = image.getImageData();
      RGB rgb = data.palette.getRGB(data.getPixel(SAMPLE - 1, SAMPLE - 1));
      return new Color(rgb);
    } finally {
      gc.dispose();
      image.dispose();
    }
  }

  @Override
  public final boolean macKeys() {
    return false;
  }

  @Override
  public final boolean linuxKeys() {
    return true;
  }

  @Override
  public final int busyCursor() {
    return SWT.CURSOR_APPSTARTING;
  }

  @Override
  public final int layoutZoom(Control control) {
    return 100;
  }

  @Override
  public final ChromeColors chromeColors(Display display) {
    return new GtkChromeColors();
  }

  @Override
  public final DialogColors dialogColors() {
    return NativeDialogColors.INSTANCE;
  }

  @Override
  public final DialogTabs dialogTabs(Composite parent) {
    return new NativeDialogTabs(parent);
  }

  @Override
  public final ThemeFollow themeFollow(Display display) {
    return new GtkThemeFollow(display);
  }

  @Override
  public final FullScreen fullScreen(Shell shell, UiDispatcher ui) {
    return new NativeFullScreen(shell);
  }

  @Override
  public final LaunchDocuments launchDocuments() {
    return new NativeLaunchDocuments();
  }

  @Override
  public final CaretPlacement caretPlacement() {
    return new NativeCaretPlacement();
  }

  @Override
  public final ComboListSelection comboListSelection() {
    return new NativeComboListSelection();
  }

  @Override
  public final ListSelection listSelection() {
    return new NativeListSelection();
  }
}
