package io.github.ajdailey.qcaret.ui.swt;

import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import io.github.ajdailey.qcaret.app.command.CommandId;
import io.github.ajdailey.qcaret.app.command.Keymap;
import io.github.ajdailey.qcaret.app.config.ConfigStore;
import io.github.ajdailey.qcaret.app.config.Preferences;
import io.github.ajdailey.qcaret.app.config.SettingsLocation;
import io.github.ajdailey.qcaret.app.config.SettingsMode;
import io.github.ajdailey.qcaret.app.controller.ApplicationController;
import io.github.ajdailey.qcaret.app.controller.EditorController;
import io.github.ajdailey.qcaret.app.theme.Palette;
import io.github.ajdailey.qcaret.app.theme.ThemeMode;
import io.github.ajdailey.qcaret.app.view.PreferencesForm;
import io.github.ajdailey.qcaret.app.view.SearchForm;
import io.github.ajdailey.qcaret.app.view.SearchScope;
import io.github.ajdailey.qcaret.app.view.WindowGeometry;
import io.github.ajdailey.qcaret.app.view.WorkspaceNode;
import io.github.ajdailey.qcaret.core.concurrent.MemoryBudget;
import io.github.ajdailey.qcaret.core.concurrent.Workers;
import io.github.ajdailey.qcaret.core.document.Selection;
import io.github.ajdailey.qcaret.core.document.SelectionSet;
import io.github.ajdailey.qcaret.ui.swt.platform.PlatformTraits;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.concurrent.atomic.AtomicReference;
import java.util.function.BooleanSupplier;
import org.eclipse.swt.SWT;
import org.eclipse.swt.graphics.GC;
import org.eclipse.swt.graphics.Image;
import org.eclipse.swt.graphics.ImageData;
import org.eclipse.swt.graphics.ImageLoader;
import org.eclipse.swt.graphics.Point;
import org.eclipse.swt.graphics.Rectangle;
import org.eclipse.swt.widgets.Composite;
import org.eclipse.swt.widgets.Control;
import org.eclipse.swt.widgets.Display;
import org.eclipse.swt.widgets.Event;
import org.eclipse.swt.widgets.Menu;
import org.eclipse.swt.widgets.Shell;
import org.eclipse.swt.widgets.TabFolder;
import org.eclipse.swt.widgets.Text;
import org.eclipse.swt.widgets.Tree;
import org.eclipse.swt.widgets.TreeItem;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfSystemProperty;
import org.junit.jupiter.api.io.TempDir;

/**
 * Writes the user guide's images to {@code -Dqcaret.guide.shots=<dir>} ({@code
 * tools/screenshots.sh}) from generated mock files, each painted through {@code Shell.print} except
 * the browser's, which is copied from the screen so its pop-up menu shows. The files are written to
 * {@code -Dqcaret.screenshots.files=<dir>} when given, so the status bar and the dialogs show that
 * path.
 */
@Tag("swt")
@EnabledIfSystemProperty(named = "qcaret.guide.shots", matches = ".+")
class GuideScreenshotsTest {
  private static final Duration BOUND = Duration.ofSeconds(30);

  private static final String SHELF =
      """
      package mock.inventory;

      import java.util.ArrayList;
      import java.util.List;

      /** Keeps the mock items of one shelf, newest last. */
      public final class Shelf {
        private static final int LIMIT = 0x40;
        private final List<String> items = new ArrayList<>();

        /** Adds an item unless the shelf is full. */
        public boolean add(String name) {
          if (items.size() >= LIMIT) {
            return false; // Full: the caller retries later.
          }
          items.add(name.strip());
          return true;
        }

        /** Removes an item. */
        public boolean remove(String name) {
          return items.remove(name);
        }

        /** Returns a sorted copy of the items. */
        public List<String> sorted() {
          List<String> copy = new ArrayList<>(items);
          copy.sort(null);
          return copy;
        }

        /** Returns the share of the shelf in use. */
        public double used() {
          return items.size() / (double) LIMIT;
        }
      }
      """;

  private static final String CATALOG =
      """
      package mock.inventory;

      import java.util.List;

      /** Lists the items of every mock shelf. */
      final class Catalog {
        private final List<Shelf> shelves;

        Catalog(List<Shelf> shelves) {
          this.shelves = List.copyOf(shelves);
        }

        int items() {
          int count = 0;
          for (Shelf shelf : shelves) {
            count += shelf.sorted().size();
          }
          return count;
        }
      }
      """;

  @TempDir Path temp;

  private final AtomicReference<Throwable> failure = new AtomicReference<Throwable>();
  private Display display;
  private MainWindow window;
  private ApplicationController controller;
  private Path out;

  @Test
  void guideScreenshots() throws Exception {
    out = Files.createDirectories(Path.of(System.getProperty("qcaret.guide.shots")));
    String files = System.getProperty("qcaret.screenshots.files", "");
    Path dir = files.isEmpty() ? temp : Files.createDirectories(Path.of(files));
    Path project = mockProject(dir.resolve("inventory"));
    display = new Display();
    try (Workers workers =
        new Workers(
            2,
            new Thread.UncaughtExceptionHandler() {
              @Override
              public void uncaughtException(Thread thread, Throwable error) {
                failure.set(error);
              }
            })) {
      window = new MainWindow(display, Palette.LIGHT);
      controller =
          new ApplicationController(
              workers,
              new SwtUiDispatcher(display),
              window,
              new ConfigStore(new SettingsLocation(SettingsMode.EXPLICIT, temp.resolve("conf"))),
              Preferences.DEFAULTS,
              SwtThemeService.open(display, ThemeMode.LIGHT),
              new MemoryBudget(Runtime.getRuntime().maxMemory(), 60),
              Keymap.defaults(PlatformTraits.PLATFORM.macKeys()),
              false);
      window.attach(controller);
      controller.loadGrammars();
      window.place(new WindowGeometry.Request(0, 0, 960, 600), null);
      window.open();
      shoot(project);
      controller.documents().dispose();
    } finally {
      display.dispose();
    }
    assertNull(failure.get());
  }

  private void shoot(Path project) throws Exception {
    Path shelf = project.resolve("src").resolve("Shelf.java");
    open(project.resolve("src").resolve("Catalog.java"), 1);
    open(shelf, 2);
    controller.workspace().set(project.toRealPath());
    await(
        new BooleanSupplier() {
          @Override
          public boolean getAsBoolean() {
            return controller.workspace().root() != null;
          }
        });
    controller.execute(CommandId.WORKSPACE_BROWSER);
    controller
        .search()
        .run(
            new SearchForm(
                "items",
                false,
                true,
                false,
                SearchScope.WORKSPACE,
                "",
                "*.java",
                "",
                false,
                project.toRealPath().toString(),
                List.of(),
                List.of(),
                List.of()));
    await(
        new BooleanSupplier() {
          @Override
          public boolean getAsBoolean() {
            return controller.search().setCount() == 1 && !controller.search().running(0);
          }
        });
    controller.execute(CommandId.NEXT_RESULT);
    settle();
    controller.find().open(false);
    controller.find().setText("items");
    await(
        new BooleanSupplier() {
          @Override
          public boolean getAsBoolean() {
            return !controller.find().state().running();
          }
        });
    settle();
    save(window.shell(), "main.png");
    controller.find().close();

    controller.execute(CommandId.HYPERSEARCH);
    saveDialog("hypersearch.png");
    controller.execute(CommandId.REPLACE_IN_RESULTS);
    Text replacement = find(dialog("replace.png"), Text.class);
    assertNotNull(replacement);
    replacement.setText("entries");
    settle();
    saveDialog("replace.png");

    controller.execute(CommandId.WORKSPACE_BROWSER);
    controller.execute(CommandId.SEARCH_RESULTS);
    controller.documents().select(1);
    settle();
    EditorController editor = controller.documents().activeEditor();
    String text = editor.document().text().toString();
    List<Selection> words = new ArrayList<Selection>();
    for (int at = text.indexOf("items.");
        at >= 0 && words.size() < 3;
        at = text.indexOf("items.", at + 1)) {
      words.add(Selection.of(at, at + 5));
    }
    editor.selections().set(SelectionSet.of(words, 0));
    settle();
    save(window.shell(), "carets.png");

    editor.goTo(21, 5);
    settle();
    controller.execute(CommandId.FOLD);
    editor.goTo(26, 5);
    controller.execute(CommandId.FOLD);
    settle();
    save(window.shell(), "folds.png");
    controller.execute(CommandId.UNFOLD_ALL);

    controller.execute(CommandId.QUICK_OPEN);
    Shell quickOpen = dialog("quick-open.png");
    Text field = find(quickOpen, Text.class);
    assertNotNull(field);
    field.setText("set");
    settle();
    saveDialog("quick-open.png");

    editor.goTo(8, 2);
    editor.type("private static final int RESERVED = 2;\n  ");
    editor.goTo(16, 4);
    editor.type("// Keep a slot free.\n    ");
    settle();
    controller.execute(CommandId.COMPARE_WITH_DISK);
    settle();
    saveDialog("compare.png");

    String edited = editor.document().text().toString();
    int limit = edited.indexOf("0x40");
    editor.selections().set(SelectionSet.of(List.of(Selection.of(limit, limit + 4)), 0));
    editor.type("0x80");
    editor.goTo(13, 5);
    controller.execute(CommandId.RIGHT_MARGIN);
    controller.execute(CommandId.INDENT_GUIDES);
    controller.execute(CommandId.LINE_CHANGES);
    final EditorController marked = editor;
    await(
        new BooleanSupplier() {
          @Override
          public boolean getAsBoolean() {
            return window.activeEditor().lineChanges().revision() == marked.document().revision();
          }
        });
    settle();
    save(window.shell(), "guides.png");

    controller.execute(CommandId.PREFERENCES);
    Shell preferences = dialog("preferences.png");
    TabFolder tabs = find(preferences, TabFolder.class);
    assertNotNull(tabs);
    tabs.setSelection(PreferencesForm.Group.FILES.ordinal());
    settle();
    saveDialog("preferences.png");

    shootBrowser(project);
  }

  /** Saves the browser with a filter, both columns and the row menu open on a file. */
  private void shootBrowser(Path project) throws IOException {
    controller.execute(CommandId.WORKSPACE_BROWSER);
    window.setSideWeights(new int[] {2, 3});
    final WorkspaceDock dock = window.workspaceDock();
    dock.bar().filter().setText("*.java;*.yaml");
    dock.bar().filter().notifyListeners(SWT.DefaultSelection, new Event());
    final WorkspaceNode src =
        new WorkspaceNode(project.toRealPath().resolve("src"), "src", WorkspaceNode.Kind.DIRECTORY);
    await(
        new BooleanSupplier() {
          @Override
          public boolean getAsBoolean() {
            return dock.texts(null).contains("src/") && !dock.texts(null).contains("notes.txt");
          }
        });
    dock.expand(src);
    await(
        new BooleanSupplier() {
          @Override
          public boolean getAsBoolean() {
            return dock.texts(src).contains("Shelf.java");
          }
        });
    settle();
    Tree tree = dock.tree();
    TreeItem shelf = item(tree.getItems(), "Shelf.java");
    assertNotNull(shelf);
    tree.setSelection(shelf);
    Event detect = new Event();
    detect.detail = SWT.MENU_KEYBOARD;
    tree.notifyListeners(SWT.MenuDetect, detect);
    Menu menu = tree.getMenu();
    assertNotNull(menu);
    assertTrue(menu.getItem(0).getEnabled());
    Rectangle row = shelf.getBounds();
    menu.setLocation(
        tree.toDisplay(row.x + row.width + 8, row.y + tree.getHeaderHeight() + row.height / 2));
    menu.setVisible(true);
    settle();
    capture("browser.png");
    menu.setVisible(false);
    settle();
  }

  /** Returns the row with a text at any depth. */
  private static TreeItem item(TreeItem[] items, String text) {
    for (TreeItem item : items) {
      if (item.getText().equals(text)) {
        return item;
      }
      TreeItem found = item(item.getItems(), text);
      if (found != null) {
        return found;
      }
    }
    return null;
  }

  /**
   * Copies the main window's area of the screen to a PNG, so an open pop-up menu shows, without the
   * black rows and columns the shell's estimated trim adds past the window.
   */
  private void capture(String name) {
    Rectangle bounds = window.shell().getBounds();
    Image image = new Image(display, bounds.width, bounds.height);
    GC gc = new GC(display);
    try {
      gc.copyArea(image, bounds.x, bounds.y);
    } finally {
      gc.dispose();
    }
    try {
      ImageData data = image.getImageData();
      int height = contentHeight(data);
      int width = contentWidth(data);
      Image cut = new Image(display, width, height);
      GC cutGc = new GC(cut);
      try {
        cutGc.drawImage(image, 0, 0, width, height, 0, 0, width, height);
        ImageLoader loader = new ImageLoader();
        loader.data = new ImageData[] {cut.getImageData()};
        loader.save(out.resolve(name).toString(), SWT.IMAGE_PNG);
      } finally {
        cutGc.dispose();
        cut.dispose();
      }
    } finally {
      image.dispose();
    }
  }

  /** Returns the image's width without the columns at its right that match its last column. */
  private static int contentWidth(ImageData data) {
    int[] last = column(data, data.width - 1);
    for (int x = data.width - 2; x > 0; x--) {
      if (!Arrays.equals(last, column(data, x))) {
        return x + 1;
      }
    }
    return data.width;
  }

  private static int[] column(ImageData data, int x) {
    int[] pixels = new int[data.height];
    for (int y = 0; y < data.height; y++) {
      pixels[y] = data.getPixel(x, y);
    }
    return pixels;
  }

  private void open(Path file, final int count) {
    controller.documents().open(List.of(file));
    await(
        new BooleanSupplier() {
          @Override
          public boolean getAsBoolean() {
            return window.tabs().getItemCount() >= count && controller.documents().loading() == 0;
          }
        });
    settle();
  }

  private static Path mockProject(Path root) throws IOException {
    Files.createDirectories(root.resolve("src"));
    Files.createDirectories(root.resolve("config"));
    Files.writeString(root.resolve("src").resolve("Shelf.java"), SHELF, StandardCharsets.UTF_8);
    Files.writeString(root.resolve("src").resolve("Catalog.java"), CATALOG, StandardCharsets.UTF_8);
    Files.writeString(
        root.resolve("config").resolve("settings.yaml"),
        "# Mock service settings\nservice:\n  name: mock-shelf\n  port: 8080\n",
        StandardCharsets.UTF_8);
    Files.writeString(
        root.resolve("config").resolve("reset.sql"),
        "-- Mock table\nDELETE FROM mock_items;\n",
        StandardCharsets.UTF_8);
    Files.writeString(root.resolve("notes.txt"), "Mock notes.\n", StandardCharsets.UTF_8);
    return root;
  }

  private void await(BooleanSupplier condition) {
    assertTrue(EventLoop.runUntil(display, condition, BOUND));
  }

  /** Runs the event loop for about a second, so queued view updates reach the widgets. */
  private void settle() {
    final long until = System.nanoTime() + 1_000_000_000L;
    await(
        new BooleanSupplier() {
          @Override
          public boolean getAsBoolean() {
            return System.nanoTime() > until;
          }
        });
  }

  /** Returns the newest visible shell other than the main window. */
  private Shell dialog(String name) {
    settle();
    Shell dialog = null;
    for (Shell shell : display.getShells()) {
      if (shell != window.shell() && shell.isVisible()) {
        dialog = shell;
      }
    }
    assertNotNull(dialog, name);
    return dialog;
  }

  /** Saves the newest shell other than the main window, then closes it. */
  private void saveDialog(String name) {
    Shell dialog = dialog(name);
    save(dialog, name);
    dialog.dispose();
    settle();
  }

  /** Returns the first control of a type below a composite. */
  private static <T extends Control> T find(Composite parent, Class<T> type) {
    for (Control child : parent.getChildren()) {
      if (type.isInstance(child)) {
        return type.cast(child);
      }
      if (child instanceof Composite composite) {
        T found = find(composite, type);
        if (found != null) {
          return found;
        }
      }
    }
    return null;
  }

  /**
   * Paints a shell into an image through the public print API and writes a PNG, without the blank
   * rows and columns the shell's estimated trim leaves below and right of its content.
   */
  private void save(Shell shell, String name) {
    while (display.readAndDispatch()) {
      // Flush pending paints so the image shows the latest rows.
    }
    Point size = shell.getSize();
    Image image = new Image(display, size.x, size.y);
    GC gc = new GC(image);
    try {
      shell.print(gc);
      ImageData data = image.getImageData();
      int width = contentWidth(data);
      int height = contentHeight(data);
      Image cut = new Image(display, width, height);
      GC cutGc = new GC(cut);
      try {
        cutGc.drawImage(image, 0, 0, width, height, 0, 0, width, height);
        ImageLoader loader = new ImageLoader();
        loader.data = new ImageData[] {cut.getImageData()};
        loader.save(out.resolve(name).toString(), SWT.IMAGE_PNG);
      } finally {
        cutGc.dispose();
        cut.dispose();
      }
    } finally {
      gc.dispose();
      image.dispose();
    }
  }

  /** Returns the image's height without the rows at its bottom that match its last row. */
  private static int contentHeight(ImageData data) {
    int[] last = new int[data.width];
    int[] row = new int[data.width];
    data.getPixels(0, data.height - 1, data.width, last, 0);
    for (int y = data.height - 2; y > 0; y--) {
      data.getPixels(0, y, data.width, row, 0);
      if (!Arrays.equals(last, row)) {
        return y + 1;
      }
    }
    return data.height;
  }
}
