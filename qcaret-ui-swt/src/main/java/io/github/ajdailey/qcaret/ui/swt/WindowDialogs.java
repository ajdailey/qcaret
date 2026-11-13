package io.github.ajdailey.qcaret.ui.swt;

import io.github.ajdailey.qcaret.app.command.Keymap;
import io.github.ajdailey.qcaret.app.i18n.UiText;
import io.github.ajdailey.qcaret.app.theme.Palette;
import io.github.ajdailey.qcaret.app.view.ChangeChoice;
import io.github.ajdailey.qcaret.app.view.ClipboardService;
import io.github.ajdailey.qcaret.app.view.Comparison;
import io.github.ajdailey.qcaret.app.view.DialogSize;
import io.github.ajdailey.qcaret.app.view.PaletteInput;
import io.github.ajdailey.qcaret.app.view.PreferencesInput;
import io.github.ajdailey.qcaret.app.view.QuickOpenInput;
import io.github.ajdailey.qcaret.app.view.RecoveryInput;
import io.github.ajdailey.qcaret.app.view.ReplaceInput;
import io.github.ajdailey.qcaret.app.view.ReportInput;
import io.github.ajdailey.qcaret.app.view.SearchForm;
import io.github.ajdailey.qcaret.app.view.TipDisplay;
import io.github.ajdailey.qcaret.app.view.TipInput;
import io.github.ajdailey.qcaret.ui.swt.search.ReplaceDialog;
import io.github.ajdailey.qcaret.ui.swt.search.ReportWindow;
import io.github.ajdailey.qcaret.ui.swt.search.RunChooser;
import java.util.ArrayList;
import java.util.List;
import java.util.function.Consumer;
import java.util.function.IntConsumer;
import org.eclipse.swt.SWT;
import org.eclipse.swt.graphics.Font;
import org.eclipse.swt.graphics.Point;
import org.eclipse.swt.graphics.Rectangle;
import org.eclipse.swt.widgets.Event;
import org.eclipse.swt.widgets.Listener;
import org.eclipse.swt.widgets.Shell;

/**
 * The main window's prompts, dialogs and tool windows: opens each over the window's shell and keeps
 * the newest of each kind for tests. {@link #rememberSize} gives a resizable window its last size.
 * Created and used on the UI thread.
 */
final class WindowDialogs {
  /** Where the resizable windows' sizes are kept. */
  interface Sizes {
    /**
     * Returns a window's remembered size.
     *
     * @param name the window's key
     * @return the size, or null when none is kept
     */
    DialogSize size(String name);

    /**
     * Keeps a window's size.
     *
     * @param name the window's key
     * @param size the size at close
     */
    void remember(String name, DialogSize size);
  }

  private static final Runnable NO_PROMPT =
      new Runnable() {
        @Override
        public void run() {
          // Nothing shows.
        }
      };

  private final Shell shell;
  private final SwtUiDispatcher dispatcher;
  private final ClipboardService clipboard;
  private final Sizes sizes;
  private TipDialog tipDialog;
  private PreferencesDialog preferencesDialog;
  private ShortcutsWindow shortcuts;
  private CommandPaletteWindow commandPalette;
  private QuickOpenWindow quickOpen;
  private BookmarkCommentDialog commentDialog;
  private FileNameDialog fileNameDialog;
  private DeleteDialog deleteDialog;
  private OpenManyDialog openManyDialog;
  private SearchDialog searchDialog;
  private ReplaceDialog replaceDialog;
  private final List<ReportWindow> reportWindows = new ArrayList<ReportWindow>();
  private ChangeDialog changeDialog;
  private RecoveryDialog recoveryDialog;
  private DocumentChooser documentChooser;
  private final List<DiffWindow> diffWindows = new ArrayList<DiffWindow>();

  /**
   * Creates the dialogs' owner for a main window.
   *
   * @param shell the main window's shell
   * @param dispatcher the window's dispatcher
   * @param clipboard the clipboard the Keyboard Shortcuts window copies to
   * @param sizes the remembered sizes
   */
  WindowDialogs(Shell shell, SwtUiDispatcher dispatcher, ClipboardService clipboard, Sizes sizes) {
    this.shell = shell;
    this.dispatcher = dispatcher;
    this.clipboard = clipboard;
    this.sizes = sizes;
  }

  void askGoToLine(int lines, Consumer<String> answer) {
    if (!shell.isDisposed()) {
      new GoToDialog(shell, lines, answer).open();
    }
  }

  void askTabWidth(int current, int max, IntConsumer answer) {
    if (!shell.isDisposed()) {
      new TabWidthDialog(shell, current, max, answer).open();
    }
  }

  void askReload(String name, Runnable reload, Runnable declined) {
    if (shell.isDisposed()) {
      declined.run();
      return;
    }
    new ReloadDialog(shell, name, reload, declined).open();
  }

  void showKeyboardShortcuts(Keymap bindings) {
    if (shell.isDisposed()) {
      return;
    }
    if (shortcuts == null || shortcuts.shell().isDisposed()) {
      shortcuts = new ShortcutsWindow(shell, bindings, clipboard);
      shortcuts.open();
      rememberSize(shortcuts.shell(), "shortcuts");
    } else {
      shortcuts.shell().setActive();
    }
  }

  /** Shows new bindings in an open Keyboard Shortcuts window. */
  void setKeymap(Keymap bindings) {
    if (shortcuts != null && !shortcuts.shell().isDisposed()) {
      shortcuts.setKeymap(bindings);
    }
  }

  void showPreferences(PreferencesInput input) {
    if (shell.isDisposed()) {
      return;
    }
    if (preferencesDialog != null && !preferencesDialog.shell().isDisposed()) {
      preferencesDialog.shell().setActive();
      return;
    }
    preferencesDialog = new PreferencesDialog(shell, input);
    preferencesDialog.open();
    rememberSize(preferencesDialog.shell(), "preferences");
  }

  /**
   * Gives an open dialog its remembered size, within the monitor; a new size is kept at close.
   *
   * @param dialog the dialog's shell, open
   * @param name the key the size is kept under
   */
  void rememberSize(final Shell dialog, final String name) {
    DialogSize size = sizes.size(name);
    if (size != null) {
      Rectangle area = dialog.getMonitor().getClientArea();
      dialog.setSize(Math.min(size.width(), area.width), Math.min(size.height(), area.height));
    }
    final Point opened = dialog.getSize();
    dialog.addListener(
        SWT.Dispose,
        new Listener() {
          @Override
          public void handleEvent(Event event) {
            Point closed = dialog.getSize();
            if (!closed.equals(opened)) {
              sizes.remember(name, new DialogSize(closed.x, closed.y));
            }
          }
        });
  }

  void showCommandPalette(PaletteInput input) {
    if (shell.isDisposed()) {
      return;
    }
    if (commandPalette != null && !commandPalette.shell().isDisposed()) {
      commandPalette.shell().dispose();
    }
    commandPalette = new CommandPaletteWindow(shell, input, dispatcher);
    commandPalette.open();
    rememberSize(commandPalette.shell(), "palette");
  }

  void showRecovery(RecoveryInput input) {
    if (!shell.isDisposed()) {
      recoveryDialog = new RecoveryDialog(shell, input);
      recoveryDialog.open();
    }
  }

  void recoveryChanged() {
    if (recoveryDialog != null) {
      recoveryDialog.refresh();
    }
  }

  void showQuickOpen(QuickOpenInput input) {
    if (shell.isDisposed()) {
      input.close();
      return;
    }
    if (quickOpen != null && !quickOpen.shell().isDisposed()) {
      quickOpen.shell().dispose();
    }
    quickOpen = new QuickOpenWindow(shell, input, dispatcher);
    quickOpen.open();
    rememberSize(quickOpen.shell(), "quickOpen");
  }

  void quickOpenChanged() {
    if (quickOpen != null) {
      quickOpen.changed();
    }
  }

  void showSearchDialog(SearchForm form, Consumer<SearchForm> run) {
    if (shell.isDisposed()) {
      return;
    }
    if (searchDialog != null && !searchDialog.shell().isDisposed()) {
      searchDialog.shell().dispose();
    }
    searchDialog = new SearchDialog(shell, form, run);
    searchDialog.open();
  }

  void askBookmarkComment(int line, String current, int maxLength, Consumer<String> answer) {
    if (!shell.isDisposed()) {
      commentDialog = new BookmarkCommentDialog(shell, line, current, maxLength, answer);
      commentDialog.open();
    }
  }

  void askFileName(
      String title, String message, String name, int selectEnd, Consumer<String> answer) {
    if (shell.isDisposed()) {
      answer.accept(null);
      return;
    }
    fileNameDialog = new FileNameDialog(shell, title, message, name, selectEnd, answer);
    fileNameDialog.open();
  }

  void askDelete(String message, Runnable delete) {
    if (!shell.isDisposed()) {
      deleteDialog = new DeleteDialog(shell, message, delete);
      deleteDialog.open();
    }
  }

  void askOpenMany(int count, Runnable open) {
    if (!shell.isDisposed()) {
      openManyDialog = new OpenManyDialog(shell, count, open);
      openManyDialog.open();
    }
  }

  /**
   * Opens the replace preview, closing an open one.
   *
   * @param input the preview's controller
   * @param palette the palette the preview's text paints with
   * @param fontName the editor font family
   */
  void showReplacePreview(ReplaceInput input, Palette palette, String fontName) {
    if (shell.isDisposed()) {
      return;
    }
    if (replaceDialog != null && !replaceDialog.shell().isDisposed()) {
      replaceDialog.shell().dispose();
    }
    replaceDialog =
        new ReplaceDialog(
            Dialogs.shell(
                shell, UiText.REPLACE_TITLE.text(), SWT.SHELL_TRIM | SWT.APPLICATION_MODAL),
            input,
            palette,
            fontName,
            Dialogs.monospaceSize());
    replaceDialog.open();
    rememberSize(replaceDialog.shell(), "replace");
  }

  void replacePreviewChanged() {
    if (replaceDialog != null && !replaceDialog.shell().isDisposed()) {
      replaceDialog.refresh();
    }
  }

  void showReplaceReport(ReportInput input) {
    if (shell.isDisposed()) {
      return;
    }
    ReportWindow window =
        new ReportWindow(
            Dialogs.shell(shell, input.title().replace("&", "&&"), SWT.SHELL_TRIM), input);
    reportWindows.add(window);
    window.open();
    rememberSize(window.shell(), "report");
  }

  void replaceReportChanged(ReportInput input) {
    for (int i = reportWindows.size() - 1; i >= 0; i--) {
      ReportWindow window = reportWindows.get(i);
      if (window.shell().isDisposed()) {
        reportWindows.remove(i);
      } else if (window.input() == input) {
        window.refresh();
      }
    }
  }

  void chooseReplaceRun(List<String> runs, Consumer<Integer> chosen) {
    if (!shell.isDisposed()) {
      Shell dialog =
          Dialogs.shell(
              shell,
              UiText.RESTORE_CHOOSE_TITLE.text(),
              SWT.DIALOG_TRIM | SWT.RESIZE | SWT.APPLICATION_MODAL);
      new RunChooser(dialog, runs, chosen).open();
    }
  }

  void askExternalChange(String name, boolean saving, Consumer<ChangeChoice> answer) {
    if (shell.isDisposed()) {
      answer.accept(saving ? ChangeChoice.CANCEL : ChangeChoice.KEEP);
      return;
    }
    changeDialog = new ChangeDialog(shell, name, saving, answer);
    changeDialog.open();
  }

  void chooseDocument(List<String> names, Consumer<Integer> chosen) {
    if (!shell.isDisposed()) {
      documentChooser = new DocumentChooser(shell, names, chosen);
      documentChooser.open();
    }
  }

  /**
   * Opens a comparison window that follows the palette and the font family while it is open.
   *
   * @param comparison the comparison
   * @param palette the palette it starts with
   * @param font the editor font
   */
  void showComparison(Comparison comparison, Palette palette, Font font) {
    if (shell.isDisposed()) {
      return;
    }
    final DiffWindow window = new DiffWindow(shell, comparison, palette, font.getFontData());
    diffWindows.add(window);
    window
        .shell()
        .addListener(
            SWT.Dispose,
            new Listener() {
              @Override
              public void handleEvent(Event event) {
                diffWindows.remove(window);
              }
            });
    window.open();
    rememberSize(window.shell(), "compare");
  }

  /** Repaints the open comparison windows with a palette. */
  void setPalette(Palette next) {
    for (DiffWindow window : diffWindows) {
      window.setPalette(next);
    }
  }

  /**
   * Gives the open comparison windows and the replace preview a new font family.
   *
   * @param name the family
   * @param font the editor font in that family
   */
  void setFont(String name, Font font) {
    for (DiffWindow window : diffWindows) {
      window.setFontData(font.getFontData());
    }
    if (replaceDialog != null && !replaceDialog.shell().isDisposed()) {
      replaceDialog.setFontName(name);
    }
  }

  TipDisplay showTips(TipInput input) {
    if (shell.isDisposed()) {
      return TipDialog.NONE;
    }
    tipDialog = new TipDialog(shell, input);
    tipDialog.open();
    return tipDialog;
  }

  Runnable askUnfinishedCopies(List<String> targets, Runnable quit, Runnable wait) {
    if (shell.isDisposed()) {
      quit.run();
      return NO_PROMPT;
    }
    final UnfinishedCopiesDialog dialog = new UnfinishedCopiesDialog(shell, targets, quit, wait);
    dialog.open();
    return new Runnable() {
      @Override
      public void run() {
        dialog.close();
      }
    };
  }

  /** Returns the open report windows, oldest first. */
  List<ReportWindow> reportWindows() {
    List<ReportWindow> open = new ArrayList<ReportWindow>();
    for (ReportWindow window : reportWindows) {
      if (!window.shell().isDisposed()) {
        open.add(window);
      }
    }
    return open;
  }

  /** Returns the open comparison windows, oldest first. */
  List<DiffWindow> diffWindows() {
    return new ArrayList<DiffWindow>(diffWindows);
  }

  // The newest dialog of each kind, or null before one opened.

  TipDialog tipDialog() {
    return tipDialog;
  }

  PreferencesDialog preferencesDialog() {
    return preferencesDialog;
  }

  ShortcutsWindow shortcutsWindow() {
    return shortcuts;
  }

  CommandPaletteWindow commandPalette() {
    return commandPalette;
  }

  QuickOpenWindow quickOpen() {
    return quickOpen;
  }

  BookmarkCommentDialog commentDialog() {
    return commentDialog;
  }

  FileNameDialog fileNameDialog() {
    return fileNameDialog;
  }

  DeleteDialog deleteDialog() {
    return deleteDialog;
  }

  OpenManyDialog openManyDialog() {
    return openManyDialog;
  }

  SearchDialog searchDialog() {
    return searchDialog;
  }

  ReplaceDialog replaceDialog() {
    return replaceDialog;
  }

  ChangeDialog changeDialog() {
    return changeDialog;
  }

  RecoveryDialog recoveryDialog() {
    return recoveryDialog;
  }

  DocumentChooser documentChooser() {
    return documentChooser;
  }
}
