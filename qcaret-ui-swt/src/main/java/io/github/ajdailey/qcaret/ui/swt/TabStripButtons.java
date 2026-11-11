package io.github.ajdailey.qcaret.ui.swt;

import io.github.ajdailey.qcaret.app.command.CommandId;
import io.github.ajdailey.qcaret.app.command.Keymap;
import io.github.ajdailey.qcaret.app.i18n.UiText;
import io.github.ajdailey.qcaret.app.theme.Palette;
import io.github.ajdailey.qcaret.app.theme.Rgb;
import java.util.List;
import java.util.function.Consumer;
import org.eclipse.swt.SWT;
import org.eclipse.swt.accessibility.ACC;
import org.eclipse.swt.accessibility.AccessibleAdapter;
import org.eclipse.swt.accessibility.AccessibleControlAdapter;
import org.eclipse.swt.accessibility.AccessibleControlEvent;
import org.eclipse.swt.accessibility.AccessibleEvent;
import org.eclipse.swt.custom.CTabFolder;
import org.eclipse.swt.custom.CTabFolder2Adapter;
import org.eclipse.swt.custom.CTabFolderEvent;
import org.eclipse.swt.custom.CTabFolderRenderer;
import org.eclipse.swt.custom.CTabItem;
import org.eclipse.swt.graphics.Color;
import org.eclipse.swt.graphics.GC;
import org.eclipse.swt.graphics.Image;
import org.eclipse.swt.graphics.Point;
import org.eclipse.swt.graphics.Rectangle;
import org.eclipse.swt.layout.GridData;
import org.eclipse.swt.layout.GridLayout;
import org.eclipse.swt.widgets.Canvas;
import org.eclipse.swt.widgets.Control;
import org.eclipse.swt.widgets.Event;
import org.eclipse.swt.widgets.Listener;
import org.eclipse.swt.widgets.ToolBar;

/**
 * The New, tab list and Close buttons at the right end of a {@code CTabFolder}'s strip, set as its
 * top-right control. Each button is a {@code Canvas} painting a {@link ToolIcons} image on the
 * palette's strip background, in the ink that background calls for. The list button also shows the
 * count of hidden tabs and opens a menu of every tab in strip order. The mouse wheel over the strip
 * brings hidden tabs into view.
 *
 * <p>SWT's chevron does not show: the renderer draws nothing for it and its {@code ToolBar} is held
 * at zero width, while the strip still reserves the chevron's width. A request for SWT's list
 * ({@code CTabFolder2Listener.showList}) opens this list instead.
 */
final class TabStripButtons {
  /** The list button's arrow. */
  enum ListGlyph {
    /** Points down, to the menu it opens. */
    DOWN(ToolIcons.TAB_LIST_DOWN),
    /** Points right, toward the hidden tabs. */
    RIGHT(ToolIcons.TAB_LIST_RIGHT);

    private final String icon;

    ListGlyph(String icon) {
      this.icon = icon;
    }
  }

  private static final int ICON = 16;
  private static final int PAD = 4;
  private static final int COUNT_GAP = 2;
  private static final int MAX_COUNT = 99;
  private static final int DISABLED_ALPHA = 100;
  private static final int HOT_PERCENT = 15;
  private static final String HOOKED = "qcaret.tabStrip.chevron";

  private final CTabFolder tabs;
  private final Canvas group;
  private final ToolIcons icons;
  private final StripButton create;
  private final StripButton list;
  private final StripButton close;
  private ListGlyph glyph = ListGlyph.DOWN;
  private Color text;
  private Color hot;
  private final TabList tabList;
  private int hidden;
  private boolean closeEnabled;

  /** One painted button. */
  private final class StripButton {
    final Canvas canvas;
    final Runnable action;
    String icon;
    String label = "";
    boolean inside;
    boolean pressed;

    StripButton(String icon, Runnable action) {
      this.icon = icon;
      this.action = action;
      canvas = new Canvas(group, SWT.NO_FOCUS | SWT.DOUBLE_BUFFERED);
      GridData data = new GridData(SWT.CENTER, SWT.CENTER, false, false);
      data.widthHint = ICON + 2 * PAD;
      data.heightHint = ICON + 2 * PAD;
      canvas.setLayoutData(data);
      Listener mouse =
          new Listener() {
            @Override
            public void handleEvent(Event event) {
              mouse(event);
            }
          };
      for (int type :
          new int[] {SWT.Paint, SWT.MouseEnter, SWT.MouseExit, SWT.MouseDown, SWT.MouseUp}) {
        canvas.addListener(type, mouse);
      }
      canvas.addListener(SWT.MouseVerticalWheel, wheel());
      canvas
          .getAccessible()
          .addAccessibleListener(
              new AccessibleAdapter() {
                @Override
                public void getName(AccessibleEvent event) {
                  event.result = label;
                }
              });
      canvas
          .getAccessible()
          .addAccessibleControlListener(
              new AccessibleControlAdapter() {
                @Override
                public void getRole(AccessibleControlEvent event) {
                  event.detail = ACC.ROLE_PUSHBUTTON;
                }

                @Override
                public void getDefaultAction(AccessibleControlEvent event) {
                  event.result = label;
                }
              });
    }

    void setLabel(String value) {
      label = value;
      canvas.setToolTipText(value);
    }

    boolean enabled() {
      return this != close || closeEnabled;
    }

    private void mouse(Event event) {
      switch (event.type) {
        case SWT.Paint -> paint(this, event.gc);
        case SWT.MouseEnter -> {
          inside = true;
          canvas.redraw();
        }
        case SWT.MouseExit -> {
          inside = false;
          pressed = false;
          canvas.redraw();
        }
        case SWT.MouseDown -> {
          pressed = event.button == 1;
          canvas.redraw();
        }
        case SWT.MouseUp -> {
          boolean click = pressed && event.button == 1 && inside(event);
          pressed = false;
          canvas.redraw();
          if (click && enabled()) {
            action.run();
          }
        }
        default -> {}
      }
    }

    private boolean inside(Event event) {
      Point size = canvas.getSize();
      return event.x >= 0 && event.y >= 0 && event.x < size.x && event.y < size.y;
    }
  }

  /**
   * Adds the buttons to a folder's strip.
   *
   * @param tabs the folder; its top-right control and renderer are replaced
   * @param palette the palette the strip shows
   * @param commands runs New and Close
   */
  TabStripButtons(CTabFolder tabs, Palette palette, Consumer<CommandId> commands) {
    this.tabs = tabs;
    tabList = new TabList(tabs);
    group = new Canvas(tabs, SWT.NO_FOCUS);
    GridLayout layout = new GridLayout(3, false);
    layout.marginWidth = 0;
    layout.marginHeight = 0;
    layout.horizontalSpacing = 0;
    group.setLayout(layout);
    icons = new ToolIcons(group, palette);
    setPalette(palette);
    create =
        new StripButton(
            ToolIcons.TAB_NEW,
            new Runnable() {
              @Override
              public void run() {
                commands.accept(CommandId.NEW);
              }
            });
    list =
        new StripButton(
            glyph.icon,
            new Runnable() {
              @Override
              public void run() {
                tabList.show(list.canvas);
              }
            });
    close =
        new StripButton(
            ToolIcons.TAB_CLOSE,
            new Runnable() {
              @Override
              public void run() {
                commands.accept(CommandId.CLOSE);
              }
            });
    create.setLabel(CommandId.NEW.title());
    close.setLabel(CommandId.CLOSE.title());
    list.setLabel(UiText.TAB_LIST.text());
    tabs.setRenderer(new ChevronlessRenderer(tabs));
    tabs.setTopRight(group, SWT.RIGHT);
    listen();
    update();
  }

  /**
   * Draws nothing for SWT's chevron, which keeps its size: on GTK 4 a 1 px chevron left the
   * selected last tab hidden after New.
   */
  private static final class ChevronlessRenderer extends CTabFolderRenderer {
    ChevronlessRenderer(CTabFolder parent) {
      super(parent);
    }

    @Override
    protected void draw(int part, int state, Rectangle bounds, GC gc) {
      if (part != PART_CHEVRON_BUTTON) {
        super.draw(part, state, bounds, gc);
      }
    }
  }

  private void listen() {
    tabs.addListener(
        SWT.Paint,
        new Listener() {
          @Override
          public void handleEvent(Event event) {
            update();
          }
        });
    tabs.addListener(SWT.MouseVerticalWheel, wheel());
    tabs.addCTabFolder2Listener(
        new CTabFolder2Adapter() {
          @Override
          public void showList(CTabFolderEvent event) {
            event.doit = false;
            tabList.show(list.canvas);
          }
        });
  }

  private Listener wheel() {
    return new Listener() {
      @Override
      public void handleEvent(Event event) {
        if (event.count != 0) {
          event.doit = false;
          tabList.scroll(event.count > 0 ? -1 : 1);
        }
      }
    };
  }

  /**
   * Sets the strip colors: the palette's {@code gutter} background, the icon ink that calls for,
   * and the {@code tabText} count.
   *
   * @param palette the palette
   */
  void setPalette(Palette palette) {
    Rgb background = palette.gutter();
    Rgb ink = palette.tabText();
    group.setBackground(color(background));
    icons.follow(palette);
    for (Control child : group.getChildren()) {
      child.setBackground(group.getBackground());
    }
    text = color(ink);
    hot =
        new Color(
            mix(background.red(), ink.red()),
            mix(background.green(), ink.green()),
            mix(background.blue(), ink.blue()));
    redraw();
  }

  private static int mix(int from, int to) {
    return from + (to - from) * HOT_PERCENT / 100;
  }

  private static Color color(Rgb rgb) {
    return new Color(rgb.red(), rgb.green(), rgb.blue());
  }

  /**
   * Shows each command's keys in the New and Close tool tips.
   *
   * @param keymap the bindings
   */
  void setKeymap(Keymap keymap) {
    create.setLabel(tip(CommandId.NEW, keymap));
    close.setLabel(tip(CommandId.CLOSE, keymap));
  }

  private static String tip(CommandId command, Keymap keymap) {
    String keys = keymap.keysText(command);
    return keys.isEmpty() ? command.title() : command.title() + " (" + keys + ")";
  }

  /**
   * Chooses the list button's arrow.
   *
   * @param next the arrow
   */
  void setListGlyph(ListGlyph next) {
    glyph = next;
    list.icon = next.icon;
    list.canvas.redraw();
  }

  /** Returns the button group, the folder's top-right control. */
  Control control() {
    return group;
  }

  /** Returns the New, list and Close buttons, in strip order. */
  List<Canvas> buttons() {
    return List.of(create.canvas, list.canvas, close.canvas);
  }

  /** Returns the hidden count the list button shows. */
  int hiddenCount() {
    return hidden;
  }

  /** Reports whether the icons use the light ink, for a dark strip. */
  boolean lightInk() {
    return icons.darkBackground();
  }

  /** Returns the tab list the list button opens. */
  TabList tabList() {
    return tabList;
  }

  private void redraw() {
    if (group.isDisposed()) {
      return;
    }
    for (Control child : group.getChildren()) {
      child.redraw();
    }
  }

  /**
   * Reads the hidden count and the tab count after the strip lays out; a count of other width
   * resizes the list button, and the folder lays out its controls again.
   */
  private void update() {
    if (group.isDisposed()) {
      return;
    }
    hideChevron();
    int count = 0;
    for (CTabItem item : tabs.getItems()) {
      if (!item.isShowing()) {
        count++;
      }
    }
    boolean closable = tabs.getItemCount() > 0;
    if (closable != closeEnabled) {
      closeEnabled = closable;
      close.canvas.redraw();
    }
    if (count == hidden) {
      return;
    }
    hidden = count;
    list.setLabel(count == 0 ? UiText.TAB_LIST.text() : UiText.TAB_LIST_HIDDEN.format(count));
    GridData data = (GridData) list.canvas.getLayoutData();
    int width = ICON + 2 * PAD + (count == 0 ? 0 : COUNT_GAP + countWidth());
    list.canvas.redraw();
    if (data.widthHint != width) {
      data.widthHint = width;
      group.setSize(group.computeSize(SWT.DEFAULT, SWT.DEFAULT));
      group.layout(true);
    }
  }

  private int countWidth() {
    GC gc = new GC(list.canvas);
    try {
      return gc.textExtent(countText()).x;
    } finally {
      gc.dispose();
    }
  }

  private String countText() {
    return hidden > MAX_COUNT ? MAX_COUNT + "+" : Integer.toString(hidden);
  }

  /**
   * Holds SWT's chevron toolbar at zero width, which GTK hides, so no second list control shows or
   * takes a click. The folder sets its bounds again on each layout.
   */
  private void hideChevron() {
    for (Control child : tabs.getChildren()) {
      if (child instanceof ToolBar bar && bar.getData(HOOKED) == null) {
        bar.setData(HOOKED, Boolean.TRUE);
        bar.setToolTipText(UiText.TAB_LIST.text());
        bar.addListener(
            SWT.Resize,
            new Listener() {
              @Override
              public void handleEvent(Event event) {
                Point size = bar.getSize();
                if (size.x > 0) {
                  bar.setSize(0, size.y);
                }
              }
            });
        Point size = bar.getSize();
        bar.setSize(0, size.y);
      }
    }
  }

  private void paint(StripButton button, GC gc) {
    Point size = button.canvas.getSize();
    gc.setBackground(group.getBackground());
    gc.fillRectangle(0, 0, size.x, size.y);
    boolean enabled = button.enabled();
    if (enabled && (button.inside || button.pressed)) {
      gc.setBackground(hot);
      gc.fillRoundRectangle(0, 0, size.x, size.y, PAD * 2, PAD * 2);
    }
    if (!enabled) {
      gc.setAlpha(DISABLED_ALPHA);
    }
    int y = (size.y - ICON) / 2;
    Image image = icons.get(button.icon);
    if (image != null) {
      gc.drawImage(image, PAD, y);
    }
    if (button == list && hidden > 0) {
      gc.setForeground(text);
      String count = countText();
      Point extent = gc.textExtent(count);
      gc.drawString(count, PAD + ICON + COUNT_GAP, (size.y - extent.y) / 2, true);
    }
  }
}
