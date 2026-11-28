package io.github.ajdailey.qcaret.ui.swt;

import io.github.ajdailey.qcaret.app.command.CommandId;
import io.github.ajdailey.qcaret.app.command.Keymap;
import io.github.ajdailey.qcaret.app.command.MenuTable;
import io.github.ajdailey.qcaret.app.controller.ApplicationController;
import io.github.ajdailey.qcaret.app.controller.CommandStates;
import io.github.ajdailey.qcaret.app.controller.EncodingChoices;
import io.github.ajdailey.qcaret.app.i18n.UiText;
import io.github.ajdailey.qcaret.ui.swt.platform.MenuBehavior;
import org.eclipse.swt.SWT;
import org.eclipse.swt.graphics.Point;
import org.eclipse.swt.widgets.Control;
import org.eclipse.swt.widgets.Event;
import org.eclipse.swt.widgets.Listener;
import org.eclipse.swt.widgets.Menu;
import org.eclipse.swt.widgets.MenuItem;
import org.eclipse.swt.widgets.Shell;

/**
 * The popups of the status bar's encoding and line-ending fields, opened below the field as the
 * language field's is. The encoding popup holds {@link MenuTable#ENCODING_MENU}, the Encoding
 * menu's Reopen with Encoding and Save with Encoding cascades as {@link EncodingCascade}s; it is
 * built on the first click and kept, since its Other cascades list every charset. The line-ending
 * popup holds {@link MenuTable#LINE_ENDING_MENU} as radio items running the Encoding menu's
 * commands; each click builds it again and disposes the previous one. The preview field's popup
 * holds View > Markdown View's Basic and Browser the same way; Browser is disabled with the reason
 * where it cannot run, in its tool tip or, where menus show none, its label. Call on the UI thread.
 */
final class StatusMenus {
  /** The renderer popup's items. */
  private static final CommandId[] RENDERERS = {CommandId.PREVIEW_BASIC, CommandId.PREVIEW_BROWSER};

  private final Shell shell;
  private final MainMenuBar bar;
  private final ApplicationController controller;
  private final CommandStates states;
  private Menu encodings;
  private EncodingCascade reopen;
  private EncodingCascade saveAs;
  private Menu endings;
  private Menu renderers;
  private Keymap keymap;

  /**
   * Creates the owner; the popups build on their first show.
   *
   * @param shell the window's shell, which disposes the popups
   * @param bar the menu bar, which lists the charsets and applies enabled states
   * @param controller runs the items
   * @param keymap the keys the line-ending items show
   */
  StatusMenus(Shell shell, MainMenuBar bar, ApplicationController controller, Keymap keymap) {
    this.shell = shell;
    this.bar = bar;
    this.controller = controller;
    this.states = bar.states();
    this.keymap = keymap;
  }

  /**
   * Sets the keys the line-ending items show.
   *
   * @param bindings the key bindings
   */
  void setKeymap(Keymap bindings) {
    keymap = bindings;
  }

  /**
   * Gives the fields their tool tips and opens each popup on a left click.
   *
   * @param encoding the encoding field
   * @param lineEnding the line-ending field
   */
  void hook(final Control encoding, final Control lineEnding) {
    encoding.setToolTipText(UiText.STATUS_ENCODING_TIP.text());
    lineEnding.setToolTipText(UiText.STATUS_LINE_ENDING_TIP.text());
    encoding.addListener(
        SWT.MouseUp,
        new Listener() {
          @Override
          public void handleEvent(Event event) {
            if (event.button == 1) {
              showEncodings(encoding);
            }
          }
        });
    lineEnding.addListener(
        SWT.MouseUp,
        new Listener() {
          @Override
          public void handleEvent(Event event) {
            if (event.button == 1) {
              showLineEndings(lineEnding);
            }
          }
        });
  }

  /**
   * Shows the encoding popup below a field.
   *
   * @param below the field
   * @return the popup, already visible
   */
  Menu showEncodings(Control below) {
    if (encodings == null || encodings.isDisposed()) {
      encodings = new Menu(shell, SWT.POP_UP);
      reopen = new EncodingCascade(bar, shell, controller, true);
      saveAs = new EncodingCascade(bar, shell, controller, false);
      for (UiText label : MenuTable.ENCODING_MENU) {
        (label == UiText.MENU_REOPEN_ENCODING ? reopen : saveAs).build(encodings, label);
      }
    }
    EncodingChoices choices = bar.encodings();
    if (choices != null) {
      reopen.fill(choices);
      saveAs.fill(choices);
    }
    reopen.sync(states.reopenEnabled(), "");
    saveAs.sync(states.saveEncodingEnabled(), states.saveEncoding());
    return show(encodings, below);
  }

  /**
   * Shows the line-ending popup below a field, the document's uniform ending checked.
   *
   * @param below the field
   * @return the popup, already visible
   */
  Menu showLineEndings(Control below) {
    if (endings != null && !endings.isDisposed()) {
      endings.dispose();
    }
    endings = new Menu(shell, SWT.POP_UP);
    for (final CommandId command : MenuTable.LINE_ENDING_MENU) {
      MenuItem item = new MenuItem(endings, SWT.RADIO);
      String keys = keymap.menuKeyText(command);
      item.setText(keys.isEmpty() ? command.label() : command.label() + "\t" + keys);
      item.setData(command);
      item.setEnabled(states.enabled(command));
      item.setSelection(states.checked(command));
      item.addListener(
          SWT.Selection,
          new Listener() {
            @Override
            public void handleEvent(Event event) {
              if (((MenuItem) event.widget).getSelection()) {
                controller.execute(command);
              }
            }
          });
    }
    return show(endings, below);
  }

  /**
   * Gives the preview field its tool tip and opens the renderer popup on a left click while it
   * names a renderer.
   *
   * @param preview the preview field
   */
  void hookPreview(final StatusField preview) {
    preview.setToolTipText(UiText.STATUS_PREVIEW_TIP.text());
    preview.addListener(
        SWT.MouseUp,
        new Listener() {
          @Override
          public void handleEvent(Event event) {
            if (event.button == 1 && !preview.getText().isEmpty()) {
              showRenderers(preview);
            }
          }
        });
  }

  /**
   * Shows the renderer popup below a field, the renderer shown checked.
   *
   * @param below the field
   * @return the popup, already visible
   */
  Menu showRenderers(Control below) {
    if (renderers != null && !renderers.isDisposed()) {
      renderers.dispose();
    }
    renderers = new Menu(shell, SWT.POP_UP);
    for (final CommandId command : RENDERERS) {
      MenuItem item = new MenuItem(renderers, SWT.RADIO);
      String reason = states.enabled(command) ? null : states.unavailable(command);
      String label = command.label();
      label = MenuBehavior.PLATFORM.label(label, reason);
      String keys = keymap.menuKeyText(command);
      item.setText(keys.isEmpty() ? label : label + "\t" + keys);
      item.setToolTipText(reason);
      item.setData(command);
      item.setEnabled(states.enabled(command));
      item.setSelection(states.checked(command));
      item.addListener(
          SWT.Selection,
          new Listener() {
            @Override
            public void handleEvent(Event event) {
              if (((MenuItem) event.widget).getSelection()) {
                controller.execute(command);
              }
            }
          });
    }
    return show(renderers, below);
  }

  /**
   * Returns the last renderer popup, for tests.
   *
   * @return the popup, or null before the first show
   */
  Menu rendererPopup() {
    return renderers;
  }

  /**
   * Returns the encoding popup, for tests.
   *
   * @return the popup, or null before the first show
   */
  Menu encodingPopup() {
    return encodings;
  }

  /**
   * Returns the last line-ending popup, for tests.
   *
   * @return the popup, or null before the first show
   */
  Menu lineEndingPopup() {
    return endings;
  }

  private static Menu show(Menu popup, Control below) {
    Point at = below.toDisplay(0, below.getSize().y);
    popup.setLocation(at.x, at.y);
    popup.setVisible(true);
    return popup;
  }
}
