package io.github.ajdailey.qcaret.ui.swt;

import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assumptions.assumeTrue;

import io.github.ajdailey.qcaret.app.command.CommandId;
import io.github.ajdailey.qcaret.app.command.Keymap;
import io.github.ajdailey.qcaret.app.config.ConfigStore;
import io.github.ajdailey.qcaret.app.config.Preferences;
import io.github.ajdailey.qcaret.app.config.SettingsLocation;
import io.github.ajdailey.qcaret.app.config.SettingsMode;
import io.github.ajdailey.qcaret.app.controller.ApplicationController;
import io.github.ajdailey.qcaret.app.i18n.UiText;
import io.github.ajdailey.qcaret.app.theme.Palette;
import io.github.ajdailey.qcaret.app.theme.ThemeMode;
import io.github.ajdailey.qcaret.core.concurrent.MemoryBudget;
import io.github.ajdailey.qcaret.core.concurrent.Workers;
import io.github.ajdailey.qcaret.ui.swt.platform.PlatformTraits;
import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.Map;
import java.util.Set;
import java.util.TreeMap;
import java.util.concurrent.atomic.AtomicReference;
import java.util.function.BooleanSupplier;
import org.eclipse.swt.SWT;
import org.eclipse.swt.graphics.Color;
import org.eclipse.swt.graphics.GC;
import org.eclipse.swt.graphics.Image;
import org.eclipse.swt.graphics.ImageData;
import org.eclipse.swt.graphics.ImageLoader;
import org.eclipse.swt.graphics.Point;
import org.eclipse.swt.graphics.RGB;
import org.eclipse.swt.graphics.Rectangle;
import org.eclipse.swt.layout.GridData;
import org.eclipse.swt.layout.GridLayout;
import org.eclipse.swt.widgets.Button;
import org.eclipse.swt.widgets.Combo;
import org.eclipse.swt.widgets.Composite;
import org.eclipse.swt.widgets.Control;
import org.eclipse.swt.widgets.Display;
import org.eclipse.swt.widgets.Group;
import org.eclipse.swt.widgets.Label;
import org.eclipse.swt.widgets.Link;
import org.eclipse.swt.widgets.List;
import org.eclipse.swt.widgets.ProgressBar;
import org.eclipse.swt.widgets.Shell;
import org.eclipse.swt.widgets.Spinner;
import org.eclipse.swt.widgets.TabFolder;
import org.eclipse.swt.widgets.TabItem;
import org.eclipse.swt.widgets.Table;
import org.eclipse.swt.widgets.TableColumn;
import org.eclipse.swt.widgets.TableItem;
import org.eclipse.swt.widgets.Text;
import org.eclipse.swt.widgets.Tree;
import org.eclipse.swt.widgets.TreeItem;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/**
 * On win32 every control class of a {@link Dialogs#shell} dialog, and About and each Preferences
 * page, draw on the dialog's side, dark under a dark startup setting and light under a light one,
 * with readable text. The check reads screen pixels, since {@code getBackground} can differ from
 * what win32 draws. Each case saves the dialog and its readings to {@code
 * target/diagnostics/win32-dialogs/}. Skipped where the screen does not show the test window.
 */
@Tag("swt")
class Win32DarkDialogsTest {
  private static final Duration BOUND = Duration.ofSeconds(10);

  /** Pixels cut from each edge of a control before reading it, past the border. */
  private static final int INSET = 3;

  /** WCAG AA contrast for normal text. */
  private static final double TEXT_CONTRAST = 4.5;

  /** Controls whose text win32 draws in a system color public API cannot set. */
  private static final Set<String> LIMITS =
      Set.of("Button check disabled", "Label disabled", "ProgressBar", "Text disabled");

  @TempDir Path temp;

  private Display display;
  private Workers workers;
  private MainWindow window;
  private ApplicationController controller;
  private final AtomicReference<Throwable> failure = new AtomicReference<Throwable>();
  private final StringBuilder report = new StringBuilder();
  private boolean dark = true;

  @AfterEach
  void close() {
    if (display == null) {
      return;
    }
    try {
      display.setDarkThemePreferred(false);
      if (workers != null) {
        workers.close();
      }
      display.dispose();
    } finally {
      assertNull(failure.get());
    }
  }

  @Test
  void everyControlClassReadsOnTheDarkBody() {
    assumeTrue("win32".equals(SWT.getPlatform()), "win32 dark dialogs");
    display = new Display();
    SwtThemeService.open(display, ThemeMode.DARK);
    Shell parent = new Shell(display, SWT.SHELL_TRIM);
    parent.setBounds(40, 40, 300, 200);
    parent.open();
    Shell dialog = Dialogs.shell(parent, "Dialog", true);
    ((GridLayout) dialog.getLayout()).numColumns = 3;
    Map<String, Control> controls = new HashMap<String, Control>();
    Composite composite = new Composite(dialog, SWT.NONE);
    composite.setLayout(new GridLayout(1, false));
    new Label(composite, SWT.NONE).setText("In a composite");
    controls.put("Composite", composite);
    controls.put("Label", label(dialog, "Label text", true));
    controls.put("Label disabled", label(dialog, "Disabled label", false));
    Link link = new Link(dialog, SWT.NONE);
    link.setText("Plain <a>link text</a>");
    controls.put("Link", link);
    Button push = new Button(dialog, SWT.PUSH);
    push.setText("Push");
    controls.put("Button push", push);
    Button check = SwtThemeService.check(dialog);
    check.setText("Check (service)");
    controls.put("Button check service", check);
    Button plain = new Button(dialog, SWT.CHECK);
    plain.setText("Check (plain)");
    controls.put("Button check plain", plain);
    Button disabled = new Button(dialog, SWT.CHECK);
    disabled.setText("Check disabled");
    disabled.setEnabled(false);
    controls.put("Button check disabled", disabled);
    Button radio = new Button(dialog, SWT.RADIO);
    radio.setText("Radio");
    controls.put("Button radio", radio);
    controls.put("Text", text(dialog, SWT.BORDER, true));
    controls.put("Text disabled", text(dialog, SWT.BORDER, false));
    controls.put("Text read-only", text(dialog, SWT.BORDER | SWT.READ_ONLY, true));
    Combo readOnly = new Combo(dialog, SWT.READ_ONLY);
    readOnly.setItems("Read-only combo", "Two");
    readOnly.select(0);
    controls.put("Combo read-only", readOnly);
    Combo dropDown = new Combo(dialog, SWT.DROP_DOWN);
    dropDown.setItems("Drop-down combo", "Two");
    dropDown.select(0);
    controls.put("Combo drop-down", dropDown);
    List list = new List(dialog, SWT.BORDER | SWT.V_SCROLL);
    list.setItems("List one", "List two", "List three", "List four", "List five", "List six");
    list.setLayoutData(new GridData(SWT.FILL, SWT.FILL, false, false, 1, 1));
    controls.put("List", list);
    Table table = new Table(dialog, SWT.BORDER | SWT.FULL_SELECTION | SWT.V_SCROLL);
    table.setHeaderVisible(true);
    TableColumn column = new TableColumn(table, SWT.NONE);
    column.setText("Header");
    column.setWidth(120);
    for (int i = 0; i < 6; i++) {
      new TableItem(table, SWT.NONE).setText("Table row " + i);
    }
    table.setLayoutData(new GridData(160, 90));
    controls.put("Table", table);
    Tree tree = new Tree(dialog, SWT.BORDER);
    TreeItem root = new TreeItem(tree, SWT.NONE);
    root.setText("Tree root");
    new TreeItem(root, SWT.NONE).setText("Tree child");
    root.setExpanded(true);
    tree.setLayoutData(new GridData(160, 90));
    controls.put("Tree", tree);
    Group group = new Group(dialog, SWT.NONE);
    group.setText("Group title");
    group.setLayout(new GridLayout(1, false));
    new Label(group, SWT.NONE).setText("In a group");
    controls.put("Group", group);
    Spinner spinner = new Spinner(dialog, SWT.BORDER);
    spinner.setSelection(4);
    controls.put("Spinner", spinner);
    ProgressBar bar = new ProgressBar(dialog, SWT.HORIZONTAL);
    bar.setSelection(40);
    controls.put("ProgressBar", bar);
    TabFolder folder = new TabFolder(dialog, SWT.TOP);
    folder.setLayoutData(new GridData(SWT.FILL, SWT.FILL, true, true, 3, 1));
    for (String title : new String[] {"First tab", "Second tab"}) {
      TabItem item = new TabItem(folder, SWT.NONE);
      item.setText(title);
      Composite page = new Composite(folder, SWT.NONE);
      page.setLayout(new GridLayout(2, false));
      new Label(page, SWT.NONE).setText("Label on " + title);
      Button box = SwtThemeService.check(page);
      box.setText("Check on " + title);
      text(page, SWT.BORDER, true);
      item.setControl(page);
      if (controls.get("TabFolder page") == null) {
        controls.put("TabFolder page", page);
      }
    }
    controls.put("TabFolder", folder);
    Composite buttons = Dialogs.buttonRow(dialog, 3);
    Dialogs.button(buttons, "OK", Dialogs.closer(dialog));
    Dialogs.open(dialog, SWT.DEFAULT, SWT.DEFAULT);
    dialog.forceActive();
    settle();
    ScreenProbe.assumeShows(dialog);
    settle();
    Rectangle bounds = dialog.getBounds();
    Point client = dialog.toDisplay(0, 0);
    ImageData whole = capture(bounds);
    int titleRow = Math.max(1, (client.y - bounds.y) / 2);
    report.append("title bar row=" + titleRow + " " + hex(rowMode(whole, titleRow)) + "\n");
    RGB title = rowMode(whole, titleRow);
    report.append("title bar " + hex(title) + "\n");
    java.util.List<String> unreadable = new ArrayList<String>();
    check("Shell", dialog, true, unreadable);
    for (Map.Entry<String, Control> entry : new TreeMap<String, Control>(controls).entrySet()) {
      check(entry.getKey(), entry.getValue(), !LIMITS.contains(entry.getKey()), unreadable);
    }
    save("controls-dark", whole);
    assertTrue(ToolIcons.dark(new Color(title)), "title bar " + hex(title));
    assertTrue(unreadable.isEmpty(), "unreadable " + unreadable + "\n" + report);
  }

  @Test
  void aboutAndPreferencesReadOnTheDarkBody() {
    dialogs(ThemeMode.DARK, "dark");
  }

  @Test
  void aboutAndPreferencesKeepALightStart() {
    dialogs(ThemeMode.LIGHT, "light");
  }

  /**
   * Checks that a control draws on a ground of the dialog's side and, when its text must read, that
   * its strongest ink reaches the text contrast.
   */
  private void check(String label, Control control, boolean text, java.util.List<String> bad) {
    Reading reading = measure(label, control);
    if (reading == null) {
      return;
    }
    if (ToolIcons.dark(new Color(reading.ground())) != dark
        || (text && reading.contrast() < TEXT_CONTRAST)) {
      bad.add(label);
    }
  }

  private void dialogs(ThemeMode mode, String name) {
    assumeTrue("win32".equals(SWT.getPlatform()), "win32 dark dialogs");
    dark = mode == ThemeMode.DARK;
    display = new Display();
    workers =
        new Workers(
            1,
            new Thread.UncaughtExceptionHandler() {
              @Override
              public void uncaughtException(Thread thread, Throwable error) {
                failure.set(error);
              }
            });
    SwtThemeService system = SwtThemeService.open(display, mode);
    window = new MainWindow(display, mode == ThemeMode.DARK ? Palette.DARK : Palette.LIGHT);
    controller =
        new ApplicationController(
            workers,
            new SwtUiDispatcher(display),
            window,
            new ConfigStore(new SettingsLocation(SettingsMode.EXPLICIT, temp)),
            Preferences.DEFAULTS.withTheme(mode),
            system,
            new MemoryBudget(1L << 30, 60),
            Keymap.defaults(PlatformTraits.PLATFORM.macKeys()),
            false);
    window.attach(controller);
    controller.documents().newDocument();
    window.open();
    window.shell().forceActive();
    settle();
    ScreenProbe.assumeShows(window.shell());
    for (Shell notice : window.shell().getShells()) {
      notice.dispose();
    }
    java.util.List<String> unreadable = new ArrayList<String>();
    controller.execute(CommandId.ABOUT);
    Shell about = owned(CommandId.ABOUT.title());
    about.forceActive();
    settle();
    walkCheck(about, unreadable);
    save("about-" + name, capture(about.getBounds()));
    about.dispose();
    settle();
    controller.execute(CommandId.PREFERENCES);
    Shell preferences = owned(UiText.PREFERENCES_TITLE.text());
    preferences.forceActive();
    TabFolder folder = window.preferencesDialog().folder();
    for (int i = 0; i < folder.getItemCount(); i++) {
      folder.setSelection(i);
      settle();
      walkCheck(folder.getItem(i).getControl(), unreadable);
      save("preferences-" + i + "-" + name, capture(preferences.getBounds()));
      report.setLength(0);
    }
    assertTrue(unreadable.isEmpty(), "unreadable " + unreadable);
  }

  private Shell owned(final String title) {
    final Shell[] found = new Shell[1];
    EventLoop.runUntil(
        display,
        new BooleanSupplier() {
          @Override
          public boolean getAsBoolean() {
            for (Shell shell : window.shell().getShells()) {
              if (title.equals(shell.getText())) {
                found[0] = shell;
              }
            }
            return found[0] != null;
          }
        },
        BOUND);
    assertNotNull(found[0], title);
    return found[0];
  }

  /**
   * Checks each control of a dialog page; text-bearing controls must read, except disabled ones and
   * the limits.
   */
  private void walkCheck(Control control, java.util.List<String> bad) {
    String name = control.getClass().getSimpleName();
    boolean text =
        control.isEnabled()
            && (control instanceof Label || control instanceof Link || control instanceof Button)
            && !(control instanceof Label label && label.getText().isEmpty());
    check(name + " " + describe(control), control, text, bad);
    if (control instanceof Composite composite && !(control instanceof Table)) {
      for (Control child : composite.getChildren()) {
        walkCheck(child, bad);
      }
    }
  }

  private static String describe(Control control) {
    if (control instanceof Label label) {
      return '"' + label.getText() + '"';
    }
    if (control instanceof Button button) {
      return '"' + button.getText() + '"';
    }
    return "";
  }

  private static Label label(Composite parent, String value, boolean enabled) {
    Label label = new Label(parent, SWT.NONE);
    label.setText(value);
    label.setEnabled(enabled);
    return label;
  }

  private static Text text(Composite parent, int style, boolean enabled) {
    Text text = new Text(parent, style);
    text.setText("Field text");
    text.setEnabled(enabled);
    text.setLayoutData(new GridData(120, SWT.DEFAULT));
    return text;
  }

  /** A control's drawn ground and the contrast of its strongest ink on it. */
  private record Reading(RGB ground, double contrast) {}

  /** Records a control's drawn ground and strongest ink beside what it reports. */
  private Reading measure(String label, Control control) {
    Rectangle area = screenArea(control);
    if (area.width <= 2 * INSET || area.height <= 2 * INSET || !control.isVisible()) {
      return null;
    }
    ImageData data = capture(area);
    RGB ground = mode(data, INSET);
    RGB ink = strongest(data, ground);
    report.append(
        String.format(
            "%s ground=%s ink=%s contrast=%.2f getBackground=%s getForeground=%s%n",
            label,
            hex(ground),
            hex(ink),
            SwtThemeService.contrast(ink, ground),
            hex(control.getBackground().getRGB()),
            hex(control.getForeground().getRGB())));
    return new Reading(ground, SwtThemeService.contrast(ink, ground));
  }

  private void settle() {
    EventLoop.runUntil(
        display,
        new BooleanSupplier() {
          @Override
          public boolean getAsBoolean() {
            return !display.readAndDispatch();
          }
        },
        BOUND);
    display.update();
  }

  private static Rectangle screenArea(Control control) {
    Point size = control.getSize();
    Point origin = control.toDisplay(0, 0);
    return new Rectangle(origin.x, origin.y, size.x, size.y);
  }

  private ImageData capture(Rectangle area) {
    Image image = new Image(display, area.width, area.height);
    GC gc = new GC(display);
    try {
      gc.copyArea(image, area.x, area.y);
      return image.getImageData();
    } finally {
      gc.dispose();
      image.dispose();
    }
  }

  private void save(String name, ImageData data) {
    try {
      Path dir = Files.createDirectories(Path.of("target", "diagnostics", "win32-dialogs"));
      ImageLoader loader = new ImageLoader();
      loader.data = new ImageData[] {data};
      loader.save(dir.resolve(name + ".png").toString(), SWT.IMAGE_PNG);
      Files.writeString(dir.resolve(name + ".txt"), report.toString(), StandardCharsets.UTF_8);
    } catch (IOException e) {
      throw new UncheckedIOException(e);
    }
  }

  private static RGB rowMode(ImageData data, int y) {
    Map<Integer, Integer> counts = new HashMap<Integer, Integer>();
    int best = 0;
    int bestCount = -1;
    for (int x = 0; x < data.width; x++) {
      int pixel = data.getPixel(x, y);
      Integer count = counts.get(pixel);
      int next = count == null ? 1 : count + 1;
      counts.put(pixel, next);
      if (next > bestCount) {
        best = pixel;
        bestCount = next;
      }
    }
    return data.palette.getRGB(best);
  }

  /** Returns the most frequent color inside the inset. */
  private static RGB mode(ImageData data, int inset) {
    Map<Integer, Integer> counts = new HashMap<Integer, Integer>();
    int best = 0;
    int bestCount = -1;
    for (int y = inset; y < data.height - inset; y++) {
      for (int x = inset; x < data.width - inset; x++) {
        int pixel = data.getPixel(x, y);
        Integer count = counts.get(pixel);
        int next = count == null ? 1 : count + 1;
        counts.put(pixel, next);
        if (next > bestCount) {
          best = pixel;
          bestCount = next;
        }
      }
    }
    return data.palette.getRGB(best);
  }

  /** Returns the pixel inside the inset with the highest contrast to the ground. */
  private static RGB strongest(ImageData data, RGB ground) {
    RGB best = ground;
    double max = 1;
    for (int y = INSET; y < data.height - INSET; y++) {
      for (int x = INSET; x < data.width - INSET; x++) {
        RGB rgb = data.palette.getRGB(data.getPixel(x, y));
        double contrast = SwtThemeService.contrast(rgb, ground);
        if (contrast > max) {
          max = contrast;
          best = rgb;
        }
      }
    }
    return best;
  }

  private static String hex(RGB rgb) {
    return String.format("#%02x%02x%02x", rgb.red, rgb.green, rgb.blue);
  }
}
