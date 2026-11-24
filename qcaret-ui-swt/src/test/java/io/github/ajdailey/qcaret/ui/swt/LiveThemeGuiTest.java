package io.github.ajdailey.qcaret.ui.swt;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNotSame;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertTrue;

import io.github.ajdailey.qcaret.app.command.CommandId;
import io.github.ajdailey.qcaret.app.command.Keymap;
import io.github.ajdailey.qcaret.app.config.ConfigStore;
import io.github.ajdailey.qcaret.app.config.Preferences;
import io.github.ajdailey.qcaret.app.config.SettingsLocation;
import io.github.ajdailey.qcaret.app.config.SettingsMode;
import io.github.ajdailey.qcaret.app.controller.ApplicationController;
import io.github.ajdailey.qcaret.app.controller.EditorController;
import io.github.ajdailey.qcaret.app.controller.WindowSet;
import io.github.ajdailey.qcaret.app.theme.Palette;
import io.github.ajdailey.qcaret.app.theme.ThemeMode;
import io.github.ajdailey.qcaret.app.view.FindState;
import io.github.ajdailey.qcaret.app.view.MarkdownViewMode;
import io.github.ajdailey.qcaret.app.view.WindowGeometry;
import io.github.ajdailey.qcaret.core.concurrent.MemoryBudget;
import io.github.ajdailey.qcaret.core.concurrent.Workers;
import io.github.ajdailey.qcaret.core.document.Document;
import io.github.ajdailey.qcaret.core.document.SelectionSet;
import io.github.ajdailey.qcaret.ui.swt.platform.PlatformTraits;
import java.lang.reflect.Field;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.ArrayList;
import java.util.Collection;
import java.util.List;
import java.util.Map;
import java.util.TreeMap;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.function.BooleanSupplier;
import org.eclipse.swt.SWT;
import org.eclipse.swt.graphics.Cursor;
import org.eclipse.swt.graphics.DeviceData;
import org.eclipse.swt.graphics.Rectangle;
import org.eclipse.swt.widgets.Display;
import org.eclipse.swt.widgets.Shell;
import org.eclipse.swt.widgets.ToolItem;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/**
 * A live theme switch that rebuilds the main windows, forced on every platform through the win32
 * theme following: the state each window carries, the deferral behind a dialog, and flat counts
 * over repeated switches.
 */
@Tag("swt")
class LiveThemeGuiTest {
  private static final Duration BOUND = Duration.ofSeconds(30);

  private static final boolean GTK = "gtk".equals(SWT.getPlatform());

  @TempDir Path temp;

  private final List<Throwable> failures = new CopyOnWriteArrayList<Throwable>();
  private final List<MainWindowSlot> slots = new ArrayList<MainWindowSlot>();
  private Display display;
  private Workers workers;
  private WindowSet set;
  private WindowViews views;
  private boolean dark;

  @BeforeEach
  void open() {
    DeviceData data = new DeviceData();
    data.tracking = true;
    display = new Display(data);
    workers =
        new Workers(
            2,
            new Thread.UncaughtExceptionHandler() {
              @Override
              public void uncaughtException(Thread thread, Throwable error) {
                failures.add(error);
              }
            });
    SwtThemeService theme =
        SwtThemeService.open(
            display, ThemeMode.LIGHT, PlatformTraits.of("win32", false).themeFollow(display));
    set =
        new WindowSet(
            workers,
            new SwtUiDispatcher(display),
            new ConfigStore(new SettingsLocation(SettingsMode.EXPLICIT, temp.resolve("conf"))),
            Preferences.DEFAULTS.withTheme(ThemeMode.LIGHT),
            theme,
            new MemoryBudget(Runtime.getRuntime().maxMemory(), 60),
            Keymap.defaults(PlatformTraits.PLATFORM.macKeys()),
            false);
    views =
        new WindowViews(
            display,
            new WindowViews.Factory() {
              @Override
              public MainWindow create(ApplicationController controller) {
                MainWindow window = new MainWindow(display, controller.theme().palette());
                window.attach(controller);
                return window;
              }
            });
    theme.setWindowRebuild(views);
    set.setFactory(
        new WindowSet.Factory() {
          @Override
          public ApplicationController create(
              WindowSet owner, WindowGeometry.Request at, boolean maximized) {
            return window(owner, at);
          }
        });
  }

  private ApplicationController window(WindowSet owner, WindowGeometry.Request at) {
    MainWindow window = new MainWindow(display, Palette.LIGHT);
    MainWindowSlot slot = new MainWindowSlot(window);
    slots.add(slot);
    ApplicationController created = new ApplicationController(owner, slot);
    window.attach(created);
    views.add(slot, created);
    Integer x = at.x() == null ? Integer.valueOf(40 + 60 * (slots.size() - 1)) : at.x();
    window.place(new WindowGeometry.Request(x, 30, 760, 520), null);
    window.open();
    return created;
  }

  @AfterEach
  void close() {
    set.quit();
    runUntil(
        new BooleanSupplier() {
          @Override
          public boolean getAsBoolean() {
            for (MainWindowSlot slot : slots) {
              if (!slot.window().shell().isDisposed()) {
                return false;
              }
            }
            return true;
          }
        });
    display.setDarkThemePreferred(false);
    display.dispose();
    workers.close();
    assertTrue(failures.isEmpty(), failures.toString());
  }

  private boolean runUntil(BooleanSupplier condition) {
    return EventLoop.runUntil(display, condition, BOUND);
  }

  private ApplicationController first() {
    return window(set, new WindowGeometry.Request(null, null, null, null));
  }

  private void open(final ApplicationController controller, String... names) throws Exception {
    final int expected = controller.documents().count() + names.length;
    List<Path> files = new ArrayList<Path>();
    for (String name : names) {
      StringBuilder text = new StringBuilder();
      for (int i = 1; i <= 300; i++) {
        text.append(name).append(" line ").append(i).append('\n');
      }
      files.add(Files.writeString(temp.resolve(name), text.toString(), StandardCharsets.UTF_8));
    }
    controller.documents().open(files);
    assertTrue(
        runUntil(
            new BooleanSupplier() {
              @Override
              public boolean getAsBoolean() {
                return controller.documents().count() == expected
                    && controller.documents().loading() == 0;
              }
            }),
        "files opened");
  }

  /** Switches the theme through the settings and waits until no window waits for its rebuild. */
  private void toggle() {
    dark = !dark;
    final int before = views.rebuilds();
    set.settings().set(set.settings().current().withTheme(dark ? ThemeMode.DARK : ThemeMode.LIGHT));
    assertTrue(
        runUntil(
            new BooleanSupplier() {
              @Override
              public boolean getAsBoolean() {
                return views.rebuilds() > before && !views.pending();
              }
            }),
        "windows rebuilt");
  }

  private void settle() {
    final long end = System.nanoTime() + 300_000_000L;
    runUntil(
        new BooleanSupplier() {
          @Override
          public boolean getAsBoolean() {
            return System.nanoTime() > end;
          }
        });
  }

  private static List<String> titles(MainWindow window) {
    List<String> list = new ArrayList<String>();
    for (int i = 0; i < window.tabs().getItemCount(); i++) {
      list.add(window.tabs().getItem(i).getText());
    }
    return list;
  }

  @Test
  void aSwitchRebuildsTheWindowAndCarriesItsState() throws Exception {
    ApplicationController controller = first();
    open(controller, "one.txt", "two.txt", "three.txt");
    controller.documents().select(1);
    EditorController editor = controller.documents().activeEditor();
    Document document = editor.document();
    editor.selections().set(SelectionSet.caret(40));
    editor.type("XYZ");
    editor.selectFromView(30, 60);
    editor.view().scrollToRow(120);
    controller.documents().split(true);
    EditorController focused = controller.documents().activeEditor();
    controller.find().open(true);
    controller.find().setText("line 7");
    controller.find().setReplacement("LINE");
    controller.execute(CommandId.WORKSPACE_BROWSER);
    controller.execute(CommandId.BOOKMARKS_LIST);
    settle();
    MainWindowSlot slot = slots.get(0);
    MainWindow old = slot.window();
    List<String> titles = titles(old);
    Rectangle bounds = old.shell().getBounds();
    int topRow = editor.view().topRow();
    FindState find = controller.find().state();
    slot.setActive();

    toggle();

    MainWindow now = slot.window();
    assertNotSame(old, now);
    assertTrue(old.shell().isDisposed());
    assertTrue(SwtThemeService.nativeDark(display));
    assertEquals(titles, titles(now));
    assertEquals(1, now.tabs().getSelectionIndex());
    assertSame(document, controller.documents().activeEditor().document());
    assertSame(focused, controller.documents().activeEditor());
    assertEquals(2, now.activeEditors().size());
    assertTrue(document.isDirty());
    assertEquals(30, editor.selections().selections().primary().start());
    assertEquals(60, editor.selections().selections().primary().end());
    assertTrue(
        runUntil(
            new BooleanSupplier() {
              @Override
              public boolean getAsBoolean() {
                return editor.view().topRow() == topRow;
              }
            }),
        "top row " + topRow + " -> " + editor.view().topRow());
    assertEquals(find, controller.find().state());
    assertTrue(now.findBar().control().getVisible());
    assertTrue(now.workspaceShown());
    assertTrue(now.bookmarksShown());
    assertEquals(bounds, now.shell().getBounds());
    assertEquals(1, slot.busy().active());
    assertNotNull(now.shell().getCursor());
    slot.setIdle();
    assertTrue(
        runUntil(
            new BooleanSupplier() {
              @Override
              public boolean getAsBoolean() {
                return now.shell().getCursor() == null;
              }
            }),
        "busy cursor cleared");
    editor.execute(CommandId.UNDO);
    assertFalse(document.text().toString().contains("XYZ"));
  }

  @Test
  void theMarkdownSwitchShowsSplitAfterARebuild() throws Exception {
    final ApplicationController controller = first();
    controller.loadGrammars();
    Files.writeString(temp.resolve("notes.md"), "# Title\n\n## Section\n\ntext\n");
    controller.documents().open(List.of(temp.resolve("notes.md")));
    assertTrue(
        runUntil(
            new BooleanSupplier() {
              @Override
              public boolean getAsBoolean() {
                return controller.documents().activeMarkdown();
              }
            }),
        "Markdown tab");
    assertTrue(controller.documents().setMarkdownMode(MarkdownViewMode.SPLIT));
    settle();

    toggle();
    settle();

    assertEquals(MarkdownViewMode.SPLIT, controller.documents().markdownMode());
    ToolItem[] modes = slots.get(0).window().markdownSwitch().items();
    assertFalse(modes[0].isDisposed());
    assertEquals(CommandId.MARKDOWN_SPLIT, modes[1].getData());
    assertFalse(modes[0].getSelection(), "Source");
    assertTrue(modes[1].getSelection(), "Split");
    assertNotNull(slots.get(0).window().activePreview());
  }

  @Test
  void aModalPromptDefersOnlyItsOwnWindow() throws Exception {
    ApplicationController controller = first();
    open(controller, "prompt.txt");
    first();
    MainWindow waiting = slots.get(0).window();
    MainWindow other = slots.get(1).window();
    controller.execute(CommandId.GO_TO_LINE);
    assertTrue(waiting.promptShown());

    dark = true;
    set.settings().set(set.settings().current().withTheme(ThemeMode.DARK));
    assertTrue(
        runUntil(
            new BooleanSupplier() {
              @Override
              public boolean getAsBoolean() {
                return slots.get(1).window() != other;
              }
            }),
        "the other window rebuilt");
    settle();
    assertSame(waiting, slots.get(0).window());
    assertTrue(views.pending());

    for (Shell child : waiting.shell().getShells()) {
      child.close();
    }
    assertTrue(
        runUntil(
            new BooleanSupplier() {
              @Override
              public boolean getAsBoolean() {
                return !views.pending();
              }
            }),
        "the waiting window rebuilt");
    assertNotSame(waiting, slots.get(0).window());
    assertEquals(1, slots.get(0).window().tabs().getItemCount());
  }

  @Test
  void preferencesDefersItsWindowAndSwitchingBackLeavesItAlone() throws Exception {
    ApplicationController controller = first();
    MainWindow waiting = slots.get(0).window();
    controller.execute(CommandId.PREFERENCES);
    assertTrue(
        runUntil(
            new BooleanSupplier() {
              @Override
              public boolean getAsBoolean() {
                return waiting.preferencesDialog() != null;
              }
            }),
        "Preferences open");

    set.settings().set(set.settings().current().withTheme(ThemeMode.DARK));
    settle();
    assertTrue(views.pending());
    set.settings().set(set.settings().current().withTheme(ThemeMode.LIGHT));
    settle();
    assertFalse(views.pending());
    waiting.preferencesDialog().shell().close();
    settle();

    assertSame(waiting, slots.get(0).window());
    assertEquals(0, views.rebuilds());
  }

  @Test
  void countsStayFlatOverRepeatedSwitches() throws Exception {
    ApplicationController controller = first();
    open(controller, "a.txt", "b.txt", "c.txt");
    controller.documents().split(false);
    toggle();
    toggle();
    settle();
    System.gc();
    Map<String, Integer> before = counts();
    for (int i = 0; i < 10; i++) {
      toggle();
    }
    settle();
    System.gc();
    Map<String, Integer> after = counts();
    assertEquals(before, after);
    assertEquals(12, views.rebuilds());
  }

  private Map<String, Integer> counts() throws Exception {
    Map<String, Integer> map = new TreeMap<String, Integer>();
    int shells = 0;
    for (Shell shell : display.getShells()) {
      if (!shell.isDisposed()) {
        shells++;
      }
    }
    map.put("shells", shells);
    for (Object resource : display.getDeviceData().objects) {
      // GTK SWT moves its cached system cursors to Display.resources at each GTK theme change
      // (saveResources) and disposes them with the Display.
      if (resource != null && !(GTK && resource instanceof Cursor)) {
        String key = "resource " + resource.getClass().getSimpleName();
        map.put(key, map.getOrDefault(key, 0) + 1);
      }
    }
    map.put("settings listeners", size(set.settings(), "listeners"));
    map.put("palette listeners", size(set.theme(), "listeners"));
    ApplicationController controller = set.windows().get(0);
    for (int i = 0; i < controller.documents().count(); i++) {
      controller.documents().select(i);
      Document document = controller.documents().activeEditor().document();
      map.put("document " + i + " listeners", size(document, "listeners"));
      map.put("document " + i + " selection models", size(document, "models"));
    }
    return map;
  }

  private static int size(Object owner, String name) throws Exception {
    Class<?> type = owner.getClass();
    while (type != null) {
      try {
        Field field = type.getDeclaredField(name);
        field.setAccessible(true);
        Object value = field.get(owner);
        return value instanceof Collection<?> c ? c.size() : -1;
      } catch (NoSuchFieldException e) {
        type = type.getSuperclass();
      }
    }
    throw new NoSuchFieldException(name);
  }
}
