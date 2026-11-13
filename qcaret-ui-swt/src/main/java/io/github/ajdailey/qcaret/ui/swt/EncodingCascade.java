package io.github.ajdailey.qcaret.ui.swt;

import io.github.ajdailey.qcaret.app.controller.ApplicationController;
import io.github.ajdailey.qcaret.app.controller.EncodingChoices;
import io.github.ajdailey.qcaret.app.i18n.UiText;
import java.nio.charset.Charset;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import org.eclipse.swt.SWT;
import org.eclipse.swt.widgets.Event;
import org.eclipse.swt.widgets.Listener;
import org.eclipse.swt.widgets.Menu;
import org.eclipse.swt.widgets.MenuItem;
import org.eclipse.swt.widgets.Shell;

/**
 * One of the Encoding menu's cascades, Reopen with Encoding or Save with Encoding: {@link
 * EncodingChoices#commonCharsets()}, then an Other cascade, disabled and empty until {@link #fill}.
 * Reopen with Encoding starts with Automatic Detection and its charsets are push items; Save with
 * Encoding's are radio items that run on selection only.
 */
final class EncodingCascade {
  private final MainMenuBar bar;
  private final Shell shell;
  private final ApplicationController controller;
  private final boolean reopens;
  private final Map<String, MenuItem> byName = new HashMap<String, MenuItem>();
  private final List<MenuItem> radios = new ArrayList<MenuItem>();
  private MenuItem holder;
  private MenuItem other;
  private boolean loaded;

  EncodingCascade(MainMenuBar bar, Shell shell, ApplicationController controller, boolean reopens) {
    this.bar = bar;
    this.shell = shell;
    this.controller = controller;
    this.reopens = reopens;
  }

  /** Adds the cascade to a menu, with the common charsets and an empty, disabled Other. */
  void build(Menu menu, UiText label) {
    byName.clear();
    radios.clear();
    loaded = false;
    holder = new MenuItem(menu, SWT.CASCADE);
    holder.setText(label.text());
    Menu cascade = new Menu(shell, SWT.DROP_DOWN);
    holder.setMenu(cascade);
    if (reopens) {
      MenuItem automatic = new MenuItem(cascade, SWT.PUSH);
      automatic.setText(UiText.MENU_ENCODING_AUTO.text());
      automatic.addListener(
          SWT.Selection,
          new Listener() {
            @Override
            public void handleEvent(Event event) {
              controller.reopenWithDetection();
            }
          });
      new MenuItem(cascade, SWT.SEPARATOR);
    }
    for (Charset charset : EncodingChoices.commonCharsets()) {
      item(cascade, charset);
    }
    new MenuItem(cascade, SWT.SEPARATOR);
    other = new MenuItem(cascade, SWT.CASCADE);
    other.setText(UiText.MENU_ENCODING_OTHER.text());
    other.setEnabled(false);
    other.setMenu(new Menu(shell, SWT.DROP_DOWN));
  }

  /** Fills Other with the charsets not listed above it, once per build, and enables it. */
  void fill(EncodingChoices choices) {
    if (loaded || other == null || other.isDisposed()) {
      return;
    }
    Menu all = other.getMenu();
    for (Charset charset : choices.other()) {
      if (!byName.containsKey(charset.name())) {
        item(all, charset);
      }
    }
    loaded = true;
    other.setEnabled(true);
  }

  private void item(Menu menu, final Charset charset) {
    MenuItem item = new MenuItem(menu, reopens ? SWT.PUSH : SWT.RADIO);
    item.setText(charset.name().replace("&", "&&"));
    item.addListener(
        SWT.Selection,
        new Listener() {
          @Override
          public void handleEvent(Event event) {
            if (reopens) {
              controller.reopenWithEncoding(charset);
            } else if (((MenuItem) event.widget).getSelection()) {
              controller.saveWithEncoding(charset);
            }
          }
        });
    byName.put(charset.name(), item);
    if (!reopens) {
      radios.add(item);
    }
  }

  /** Enables the cascade and, for Save with Encoding, checks the next save's charset. */
  void sync(boolean enabled, String checked) {
    if (holder == null || holder.isDisposed()) {
      return;
    }
    bar.setEnabled(holder, enabled);
    for (MenuItem item : radios) {
      if (!item.isDisposed()) {
        boolean selected = item == byName.get(checked);
        if (item.getSelection() != selected) {
          item.setSelection(selected);
        }
      }
    }
  }

  /** Returns the cascade's item in the Encoding menu, or null before {@link #build}. */
  MenuItem holder() {
    return holder;
  }

  /** Returns the Other cascade's item, or null before {@link #build}. */
  MenuItem other() {
    return other;
  }
}
