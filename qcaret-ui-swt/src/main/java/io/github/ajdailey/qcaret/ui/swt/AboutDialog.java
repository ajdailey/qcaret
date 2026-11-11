package io.github.ajdailey.qcaret.ui.swt;

import io.github.ajdailey.qcaret.app.about.AboutInfo;
import io.github.ajdailey.qcaret.app.command.CommandId;
import io.github.ajdailey.qcaret.app.i18n.UiText;
import io.github.ajdailey.qcaret.app.view.ClipboardService;
import org.eclipse.swt.SWT;
import org.eclipse.swt.graphics.Font;
import org.eclipse.swt.graphics.FontData;
import org.eclipse.swt.graphics.Image;
import org.eclipse.swt.graphics.Point;
import org.eclipse.swt.graphics.Rectangle;
import org.eclipse.swt.layout.GridData;
import org.eclipse.swt.layout.GridLayout;
import org.eclipse.swt.program.Program;
import org.eclipse.swt.widgets.Button;
import org.eclipse.swt.widgets.Composite;
import org.eclipse.swt.widgets.Control;
import org.eclipse.swt.widgets.Event;
import org.eclipse.swt.widgets.Label;
import org.eclipse.swt.widgets.Link;
import org.eclipse.swt.widgets.Listener;
import org.eclipse.swt.widgets.Shell;

/**
 * Help > About: the logo at the top left, a bold name and version beside it, then one column with
 * the description, project link, copyright and license, and the runtime facts in a smaller font;
 * Licenses and Copy Details at the bottom left, OK at the bottom right. Modal to the main window,
 * opened without a nested event loop. The link opens the fixed project URL in the OS browser, only
 * when the user activates it.
 */
final class AboutDialog {
  /** Logical size of the logo. */
  static final int LOGO_SIZE = 48;

  /** Width of the text column, in pixels. */
  private static final int TEXT_WIDTH = 420;

  /** Most re-fits after the window manager resizes the dialog. */
  private static final int MAX_FITS = 3;

  private final Shell shell;
  private final Label heading;
  private int fits;

  AboutDialog(
      Shell parent,
      final AboutInfo info,
      Icons.Variant variant,
      final Runnable showLicenses,
      final ClipboardService clipboard) {
    shell = Dialogs.shell(parent, CommandId.ABOUT.title(), true);
    GridLayout layout = (GridLayout) shell.getLayout();
    layout.numColumns = 2;
    layout.horizontalSpacing = 16;
    final Image logo = Icons.logo(shell.getDisplay(), variant, LOGO_SIZE);
    Label picture = new Label(shell, SWT.NONE);
    picture.setLayoutData(new GridData(SWT.BEGINNING, SWT.TOP, false, false));
    if (logo != null) {
      picture.setImage(logo);
    }
    Composite text = new Composite(shell, SWT.NONE);
    GridLayout textLayout = new GridLayout(1, false);
    textLayout.marginWidth = 0;
    textLayout.marginHeight = 0;
    textLayout.verticalSpacing = 10;
    text.setLayout(textLayout);
    text.setLayoutData(new GridData(SWT.FILL, SWT.TOP, true, false));
    heading = line(text, UiText.ABOUT_NAME.format(info.version()));
    final Font bold = derived(heading, 3, SWT.BOLD);
    heading.setFont(bold);
    line(text, UiText.ABOUT_TAGLINE.text());
    Link link = new Link(text, SWT.NONE);
    SwtThemeService.colorLink(link);
    link.setText("<a>" + AboutInfo.PROJECT_URL + "</a>");
    link.addListener(
        SWT.Selection,
        new Listener() {
          @Override
          public void handleEvent(Event event) {
            Program.launch(AboutInfo.PROJECT_URL);
          }
        });
    line(text, UiText.ABOUT_COPYRIGHT.text());
    line(text, UiText.ABOUT_LICENSE.text());
    Label details = line(text, String.join("\n", info.details()));
    final Font small = derived(details, -1, SWT.NORMAL);
    details.setFont(small);
    Composite buttons = new Composite(shell, SWT.NONE);
    GridLayout buttonsLayout = new GridLayout(3, false);
    buttonsLayout.marginWidth = 0;
    buttonsLayout.marginHeight = 0;
    buttons.setLayout(buttonsLayout);
    buttons.setLayoutData(new GridData(SWT.FILL, SWT.CENTER, true, false, 2, 1));
    Dialogs.button(buttons, UiText.BUTTON_LICENSES.text(), showLicenses);
    Dialogs.button(
        buttons,
        UiText.BUTTON_COPY_DETAILS.text(),
        new Runnable() {
          @Override
          public void run() {
            clipboard.setText(String.join(System.lineSeparator(), info.details()));
          }
        });
    Button ok = Dialogs.button(buttons, UiText.BUTTON_OK.text(), Dialogs.closer(shell));
    ok.setLayoutData(new GridData(SWT.END, SWT.CENTER, true, false));
    shell.setDefaultButton(ok);
    shell.addListener(
        SWT.Dispose,
        new Listener() {
          @Override
          public void handleEvent(Event event) {
            bold.dispose();
            small.dispose();
            if (logo != null) {
              logo.dispose();
            }
          }
        });
  }

  private static Label line(Composite parent, String value) {
    Label label = new Label(parent, SWT.WRAP);
    label.setText(value);
    GridData data = new GridData(SWT.FILL, SWT.TOP, true, false);
    data.widthHint = TEXT_WIDTH;
    label.setLayoutData(data);
    return label;
  }

  /** Creates a font from a control's font, resized by points; the caller disposes it. */
  private static Font derived(Control control, int points, int style) {
    FontData[] data = control.getFont().getFontData();
    for (FontData each : data) {
      each.setHeight(Math.max(6, each.getHeight() + points));
      each.setStyle(style);
    }
    return new Font(control.getDisplay(), data);
  }

  void open() {
    Dialogs.open(shell, SWT.DEFAULT, SWT.DEFAULT);
    fit();
    shell.addListener(
        SWT.Resize,
        new Listener() {
          @Override
          public void handleEvent(Event event) {
            if (fits < MAX_FITS) {
              fit();
            }
          }
        });
  }

  /**
   * Grows the dialog where its client area is smaller than its content needs. {@code pack} sizes
   * the window from the toolkit's frame estimate; the window manager reports the real frame only
   * once the shell shows, so each {@code SWT.Resize} measures again, up to {@value #MAX_FITS}
   * times.
   */
  void fit() {
    if (shell.isDisposed()) {
      return;
    }
    Rectangle area = shell.getClientArea();
    Point outer = shell.computeSize(SWT.DEFAULT, SWT.DEFAULT, true);
    Rectangle trim = shell.computeTrim(0, 0, 0, 0);
    int width = Math.max(0, outer.x - trim.width - area.width);
    int height = Math.max(0, outer.y - trim.height - area.height);
    if (width > 0 || height > 0) {
      fits++;
      Point size = shell.getSize();
      shell.setSize(size.x + width, size.y + height);
    }
  }

  Shell shell() {
    return shell;
  }

  Label heading() {
    return heading;
  }
}
