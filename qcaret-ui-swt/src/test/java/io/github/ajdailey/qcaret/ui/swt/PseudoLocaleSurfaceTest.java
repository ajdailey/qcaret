package io.github.ajdailey.qcaret.ui.swt;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assumptions.assumeTrue;

import io.github.ajdailey.qcaret.app.command.CommandId;
import io.github.ajdailey.qcaret.app.command.Keymap;
import io.github.ajdailey.qcaret.app.config.ConfigStore;
import io.github.ajdailey.qcaret.app.config.Preferences;
import io.github.ajdailey.qcaret.app.config.SettingsLocation;
import io.github.ajdailey.qcaret.app.config.SettingsMode;
import io.github.ajdailey.qcaret.app.controller.ApplicationController;
import io.github.ajdailey.qcaret.app.controller.CommandStates;
import io.github.ajdailey.qcaret.app.controller.SyntaxMenu;
import io.github.ajdailey.qcaret.app.i18n.PseudoLocale;
import io.github.ajdailey.qcaret.app.i18n.UiText;
import io.github.ajdailey.qcaret.app.theme.Palette;
import io.github.ajdailey.qcaret.app.theme.ThemeMode;
import io.github.ajdailey.qcaret.app.view.WindowGeometry;
import io.github.ajdailey.qcaret.core.concurrent.MemoryBudget;
import io.github.ajdailey.qcaret.core.concurrent.Workers;
import io.github.ajdailey.qcaret.ui.swt.hex.HexPane;
import io.github.ajdailey.qcaret.ui.swt.platform.PlatformTraits;
import java.nio.charset.Charset;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Set;
import java.util.StringJoiner;
import java.util.concurrent.atomic.AtomicReference;
import java.util.function.BooleanSupplier;
import java.util.regex.Pattern;
import org.eclipse.swt.SWT;
import org.eclipse.swt.graphics.FontData;
import org.eclipse.swt.graphics.ImageData;
import org.eclipse.swt.graphics.ImageLoader;
import org.eclipse.swt.widgets.Control;
import org.eclipse.swt.widgets.Display;
import org.eclipse.swt.widgets.Event;
import org.eclipse.swt.widgets.Menu;
import org.eclipse.swt.widgets.Shell;
import org.eclipse.swt.widgets.TabFolder;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/**
 * The main window, its menus, bars and docks, and the dialogs a command opens without a file
 * prompt, in the {@code en-XA} pseudo locale: every shown text is one pseudo value and fits its
 * control. Runs in the module's {@code pseudo-locale} surefire execution and skips in any other.
 */
@Tag("swt")
class PseudoLocaleSurfaceTest {
  private static final Duration BOUND = Duration.ofSeconds(30);

  /** Dialogs reachable from a command without a file prompt. */
  private static final List<CommandId> DIALOGS =
      List.of(
          CommandId.PREFERENCES,
          CommandId.KEYBOARD_SHORTCUTS,
          CommandId.GO_TO_LINE,
          CommandId.ABOUT,
          CommandId.TIP_OF_THE_DAY,
          CommandId.COMMAND_PALETTE,
          CommandId.QUICK_OPEN,
          CommandId.HYPERSEARCH,
          CommandId.GO_TO_SYMBOL,
          CommandId.DOCUMENT_STATISTICS,
          CommandId.INSERT_NUMBER_SEQUENCE,
          CommandId.INSERT_DATE_TIME,
          CommandId.TAB_WIDTH_OTHER,
          CommandId.COMMAND_LINE_OPTIONS);

  @TempDir Path temp;

  private final AtomicReference<Throwable> failure = new AtomicReference<Throwable>();
  private Display display;
  private Workers workers;
  private MainWindow window;
  private ApplicationController controller;
  private Keymap keymap;
  private PseudoTexts texts;

  @BeforeEach
  void open() {
    assumeTrue(PseudoLocale.LOCALE.equals(Locale.getDefault()), "another surefire execution");
    display = new Display();
    workers =
        new Workers(
            2,
            new Thread.UncaughtExceptionHandler() {
              @Override
              public void uncaughtException(Thread thread, Throwable error) {
                failure.set(error);
              }
            });
    keymap = Keymap.defaults(PlatformTraits.PLATFORM.macKeys());
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
            keymap,
            false);
    window.attach(controller);
    window.place(new WindowGeometry.Request(0, 0, 1100, 700), null);
    window.open();
  }

  @AfterEach
  void close() {
    if (display == null) {
      return;
    }
    try {
      controller.documents().dispose();
      workers.close();
      display.dispose();
    } finally {
      assertEquals(null, failure.get());
    }
  }

  /** Shown text that is data, each with its reason. */
  private List<PseudoTexts.Exemption> exemptions() {
    Set<String> keys = new HashSet<String>();
    for (CommandId command : CommandId.values()) {
      String text = keymap.keysText(command);
      if (!text.isEmpty()) {
        keys.add(text);
        keys.addAll(Arrays.asList(text.split(", ")));
      }
    }
    StringJoiner keyNames = new StringJoiner("|");
    for (String key : keys) {
      keyNames.add(Pattern.quote(key));
    }
    StringJoiner charsets = new StringJoiner("|");
    for (String charset : Charset.availableCharsets().keySet()) {
      charsets.add(Pattern.quote(charset));
    }
    StringJoiner grammars = new StringJoiner("|");
    for (SyntaxMenu.Category category : window.menuBar().states().syntaxMenu().categories()) {
      for (CommandStates.SyntaxChoice choice : category.choices()) {
        grammars.add(Pattern.quote(choice.name()));
      }
    }
    StringJoiner fonts = new StringJoiner("|");
    for (boolean scalable : new boolean[] {true, false}) {
      for (FontData font : display.getFontList(null, scalable)) {
        fonts.add(Pattern.quote(font.getName()));
      }
    }
    return List.of(
        new PseudoTexts.Exemption(
            "file names", "the test's files", Pattern.compile("mock\\.txt|blob\\.bin")),
        new PseudoTexts.Exemption(
            "paths", "a file's location", Pattern.compile("(/|[A-Za-z]:\\\\).*")),
        new PseudoTexts.Exemption(
            "web addresses", "the project's address", Pattern.compile("https://\\S+")),
        new PseudoTexts.Exemption(
            "key names", "keys as the platform writes them", Pattern.compile(keyNames.toString())),
        new PseudoTexts.Exemption(
            "encoding names", "Java charset names", Pattern.compile(charsets.toString())),
        new PseudoTexts.Exemption(
            "grammar names",
            "names the grammar files define",
            Pattern.compile(grammars.toString())),
        new PseudoTexts.Exemption(
            "font names", "the system's font families", Pattern.compile(fonts.toString())),
        new PseudoTexts.Exemption(
            "SWT text",
            "SWT translates its own tool tips",
            Pattern.compile(Pattern.quote(SWT.getMessage("SWT_ShowList")))),
        new PseudoTexts.Exemption(
            "dates", "dates in the locale's short format", Pattern.compile("[0-9]+/[0-9]+/[0-9]+")),
        new PseudoTexts.Exemption(
            "numbers",
            "values, counts and offsets",
            Pattern.compile("(?=.*[0-9])[0-9A-Fa-f.,:% ]+")));
  }

  private void await(BooleanSupplier condition, String what) {
    assertTrue(EventLoop.runUntil(display, condition, BOUND), what);
  }

  /** Runs the event loop for a fixed turn, for layouts that signal nothing when done. */
  private void settle() {
    final long end = System.nanoTime() + Duration.ofMillis(300).toNanos();
    EventLoop.runUntil(
        display,
        new BooleanSupplier() {
          @Override
          public boolean getAsBoolean() {
            return System.nanoTime() >= end;
          }
        },
        BOUND);
  }

  private void openFile(Path file) {
    final int before = window.tabs().getItemCount();
    controller.documents().open(List.of(file));
    await(
        new BooleanSupplier() {
          @Override
          public boolean getAsBoolean() {
            return window.tabs().getItemCount() == before + 1
                && controller.documents().loading() == 0;
          }
        },
        "opened " + file.getFileName());
  }

  @Test
  void everyShownTextIsOnePseudoValueThatFits() throws Exception {
    assertEquals(PseudoLocale.translate("QCaret"), UiText.WINDOW_TITLE.text());
    final Path root = Files.createDirectories(temp.resolve("work")).toRealPath();
    Path file =
        Files.writeString(root.resolve("mock.txt"), "alpha\nbeta\n", StandardCharsets.UTF_8);
    controller.loadGrammars();
    openFile(file);
    await(
        new BooleanSupplier() {
          @Override
          public boolean getAsBoolean() {
            return !window.menuBar().states().syntaxMenu().categories().isEmpty();
          }
        },
        "grammars");
    texts =
        new PseudoTexts(
            exemptions(),
            List.of(
                new PseudoTexts.Exemption(
                    "texts holding a path",
                    "a path of any length is cut at the control's edge in every locale",
                    Pattern.compile(".*(/[^/ ]+/|[A-Za-z]:\\\\).*"))));
    controller.workspace().set(root);
    await(
        new BooleanSupplier() {
          @Override
          public boolean getAsBoolean() {
            return root.equals(controller.workspace().root());
          }
        },
        "workspace");
    controller.execute(CommandId.WORKSPACE_BROWSER);
    await(
        new BooleanSupplier() {
          @Override
          public boolean getAsBoolean() {
            return window.workspaceDock().texts(null).equals(List.of("mock.txt"));
          }
        },
        "Workspace Browser rows");
    controller.execute(CommandId.BOOKMARKS_LIST);
    controller.execute(CommandId.FIND);
    controller.execute(CommandId.HEX_SPLIT);
    await(
        new BooleanSupplier() {
          @Override
          public boolean getAsBoolean() {
            return !window.hexViews().panes().isEmpty();
          }
        },
        "hex pane");
    settle();
    texts.shell("main window", window.shell());
    heading("hex pane", window.hexViews().panes().get(0));
    shot("main window", window.shell());
    popup("encoding popup", window.statusMenus().showEncodings(window.statusBar()));
    popup("line ending popup", window.statusMenus().showLineEndings(window.statusBar()));

    for (CommandId command : DIALOGS) {
      dialog(command);
    }
    hexViewer();

    StringJoiner report = new StringJoiner("\n");
    for (String finding : texts.findings()) {
      report.add(finding);
    }
    assertEquals(0, texts.findings().size(), report.toString());
  }

  /** Runs a command, checks each window it opens, and closes them. */
  private void dialog(CommandId command) {
    final Set<Shell> before = new HashSet<Shell>(Arrays.asList(display.getShells()));
    controller.execute(command);
    final List<Shell> opened = new ArrayList<Shell>();
    await(
        new BooleanSupplier() {
          @Override
          public boolean getAsBoolean() {
            opened.clear();
            for (Shell shell : display.getShells()) {
              if (!before.contains(shell) && shell.isVisible()) {
                opened.add(shell);
              }
            }
            return !opened.isEmpty();
          }
        },
        command + " window");
    settle();
    for (Shell shell : opened) {
      check(command.name(), shell);
      shot(command.name(), shell);
      shell.dispose();
    }
    settle();
  }

  /**
   * Saves a window's pixels as {@code <name>.png} in the folder the {@code qcaret.pseudo.shots}
   * property names; without it, does nothing.
   */
  private void shot(String name, Shell shell) {
    String folder = System.getProperty("qcaret.pseudo.shots");
    if (folder == null) {
      return;
    }
    ImageData data = ControlPrint.of(display, shell);
    if (data != null) {
      ImageLoader loader = new ImageLoader();
      loader.data = new ImageData[] {data};
      loader.save(Path.of(folder, name + ".png").toString(), SWT.IMAGE_PNG);
    }
  }

  /** Checks a status bar popup and closes it. */
  private void popup(String where, Menu menu) {
    texts.menu(where, menu);
    menu.setVisible(false);
    settle();
  }

  /** Checks a window, then each page of its tab folders. */
  private void check(String where, Shell shell) {
    texts.shell(where, shell);
    for (TabFolder folder : folders(shell, new ArrayList<TabFolder>())) {
      for (int page = 0; page < folder.getItemCount(); page++) {
        folder.setSelection(page);
        folder.notifyListeners(SWT.Selection, new Event());
        settle();
        texts.control(where + " page " + page, folder.getItem(page).getControl());
      }
    }
  }

  private static List<TabFolder> folders(Control control, List<TabFolder> found) {
    if (control instanceof TabFolder folder) {
      found.add(folder);
    }
    if (control instanceof org.eclipse.swt.widgets.Composite composite) {
      for (Control child : composite.getChildren()) {
        folders(child, found);
      }
    }
    return found;
  }

  /** The offset column's heading, drawn on the canvas, fits the column's cells. */
  private void heading(String where, HexPane pane) {
    String heading = UiText.HEX_HEADER_OFFSET.text();
    texts.text(where + " heading", heading);
    if (heading.length() > pane.rows().offsetColumn()) {
      texts
          .findings()
          .add(
              where + " heading: clipped, needs " + heading.length() + " cells: '" + heading + "'");
    }
  }

  /** The binary prompt and the Hex Viewer its default button opens. */
  private void hexViewer() throws Exception {
    final Path file = temp.resolve("blob.bin");
    Files.write(file, new byte[] {(byte) 0x89, 'P', 'N', 'G', 0, 0, 0, 13, 'I', 'H', 'D', 'R'});
    final Set<Shell> before = new HashSet<Shell>(Arrays.asList(display.getShells()));
    controller.documents().open(List.of(file));
    final Shell[] prompt = new Shell[1];
    await(
        new BooleanSupplier() {
          @Override
          public boolean getAsBoolean() {
            for (Shell shell : window.shell().getShells()) {
              if (!before.contains(shell) && shell.isVisible()) {
                prompt[0] = shell;
                return true;
              }
            }
            return false;
          }
        },
        "binary prompt");
    settle();
    check("binary prompt", prompt[0]);
    prompt[0].getDefaultButton().notifyListeners(SWT.Selection, new Event());
    await(
        new BooleanSupplier() {
          @Override
          public boolean getAsBoolean() {
            List<HexWindow> windows = window.hexViews().windows();
            return windows.size() == 1 && windows.get(0).pane().rows().size() == 12;
          }
        },
        "Hex Viewer");
    settle();
    HexWindow viewer = window.hexViews().windows().get(0);
    check("Hex Viewer", viewer.shell());
    heading("Hex Viewer", viewer.pane());
    shot("Hex Viewer", viewer.shell());
    viewer.shell().close();
  }
}
