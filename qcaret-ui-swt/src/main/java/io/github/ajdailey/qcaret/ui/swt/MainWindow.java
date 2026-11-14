package io.github.ajdailey.qcaret.ui.swt;

import io.github.ajdailey.qcaret.app.about.AboutInfo;
import io.github.ajdailey.qcaret.app.command.CommandId;
import io.github.ajdailey.qcaret.app.command.KeyStroke;
import io.github.ajdailey.qcaret.app.command.Keymap;
import io.github.ajdailey.qcaret.app.command.MenuTable;
import io.github.ajdailey.qcaret.app.controller.ApplicationController;
import io.github.ajdailey.qcaret.app.controller.CommandStates;
import io.github.ajdailey.qcaret.app.controller.EncodingChoices;
import io.github.ajdailey.qcaret.app.editor.DisplayProjection;
import io.github.ajdailey.qcaret.app.i18n.UiText;
import io.github.ajdailey.qcaret.app.theme.Palette;
import io.github.ajdailey.qcaret.app.theme.PaletteListener;
import io.github.ajdailey.qcaret.app.view.BookmarksInput;
import io.github.ajdailey.qcaret.app.view.BusyCounter;
import io.github.ajdailey.qcaret.app.view.ChangeChoice;
import io.github.ajdailey.qcaret.app.view.ChecksumDisplay;
import io.github.ajdailey.qcaret.app.view.ChecksumInput;
import io.github.ajdailey.qcaret.app.view.Comparison;
import io.github.ajdailey.qcaret.app.view.DialogSize;
import io.github.ajdailey.qcaret.app.view.EditorInput;
import io.github.ajdailey.qcaret.app.view.HashTextDisplay;
import io.github.ajdailey.qcaret.app.view.HashTextInput;
import io.github.ajdailey.qcaret.app.view.MainView;
import io.github.ajdailey.qcaret.app.view.PaletteInput;
import io.github.ajdailey.qcaret.app.view.PreferencesInput;
import io.github.ajdailey.qcaret.app.view.PrintTarget;
import io.github.ajdailey.qcaret.app.view.QuickOpenInput;
import io.github.ajdailey.qcaret.app.view.RecoveryInput;
import io.github.ajdailey.qcaret.app.view.ReplaceInput;
import io.github.ajdailey.qcaret.app.view.ReportInput;
import io.github.ajdailey.qcaret.app.view.ResultsInput;
import io.github.ajdailey.qcaret.app.view.SearchForm;
import io.github.ajdailey.qcaret.app.view.StatisticsDisplay;
import io.github.ajdailey.qcaret.app.view.StatisticsInput;
import io.github.ajdailey.qcaret.app.view.StatusFields;
import io.github.ajdailey.qcaret.app.view.TabInput;
import io.github.ajdailey.qcaret.app.view.TabView;
import io.github.ajdailey.qcaret.app.view.TipDisplay;
import io.github.ajdailey.qcaret.app.view.TipInput;
import io.github.ajdailey.qcaret.app.view.UnsavedChoice;
import io.github.ajdailey.qcaret.app.view.WindowGeometry;
import io.github.ajdailey.qcaret.app.view.WorkspaceInput;
import io.github.ajdailey.qcaret.app.view.WorkspaceNode;
import io.github.ajdailey.qcaret.core.document.SelectionModel;
import io.github.ajdailey.qcaret.ui.swt.editor.EditorPane;
import io.github.ajdailey.qcaret.ui.swt.editor.SwtKeys;
import io.github.ajdailey.qcaret.ui.swt.platform.PlatformTraits;
import io.github.ajdailey.qcaret.ui.swt.search.ReplaceDialog;
import io.github.ajdailey.qcaret.ui.swt.search.ReportWindow;
import io.github.ajdailey.qcaret.ui.swt.search.ResultsDock;
import java.nio.file.InvalidPathException;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.function.Consumer;
import java.util.function.IntConsumer;
import java.util.function.Supplier;
import org.eclipse.swt.SWT;
import org.eclipse.swt.SWTError;
import org.eclipse.swt.SWTException;
import org.eclipse.swt.custom.CTabFolder;
import org.eclipse.swt.custom.CTabItem;
import org.eclipse.swt.custom.SashForm;
import org.eclipse.swt.custom.StyledText;
import org.eclipse.swt.dnd.Clipboard;
import org.eclipse.swt.dnd.DND;
import org.eclipse.swt.dnd.DropTarget;
import org.eclipse.swt.dnd.DropTargetAdapter;
import org.eclipse.swt.dnd.DropTargetEvent;
import org.eclipse.swt.dnd.FileTransfer;
import org.eclipse.swt.dnd.TextTransfer;
import org.eclipse.swt.dnd.Transfer;
import org.eclipse.swt.graphics.Color;
import org.eclipse.swt.graphics.Font;
import org.eclipse.swt.graphics.Image;
import org.eclipse.swt.graphics.Point;
import org.eclipse.swt.graphics.Rectangle;
import org.eclipse.swt.layout.GridData;
import org.eclipse.swt.layout.GridLayout;
import org.eclipse.swt.printing.PrintDialog;
import org.eclipse.swt.printing.Printer;
import org.eclipse.swt.printing.PrinterData;
import org.eclipse.swt.program.Program;
import org.eclipse.swt.widgets.Composite;
import org.eclipse.swt.widgets.Control;
import org.eclipse.swt.widgets.DirectoryDialog;
import org.eclipse.swt.widgets.Display;
import org.eclipse.swt.widgets.Event;
import org.eclipse.swt.widgets.FileDialog;
import org.eclipse.swt.widgets.Label;
import org.eclipse.swt.widgets.Link;
import org.eclipse.swt.widgets.Listener;
import org.eclipse.swt.widgets.Menu;
import org.eclipse.swt.widgets.Monitor;
import org.eclipse.swt.widgets.Shell;
import org.eclipse.swt.widgets.ToolBar;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * The main window: the {@link TabStrip}, one tab per document, over the {@link ResultsDock}, hidden
 * until a HyperSearch shows it, beside the {@link BookmarksDock}, hidden until Bookmarks List shows
 * it, with the {@link WorkspaceDock} on the left, hidden until View > Workspace Browser shows it;
 * the {@link MainMenuBar} built from {@link MenuTable} and the {@link Keymap}, the {@link
 * MainToolBar} above the rest, the {@link FindBar} and a status bar. Every editor shares the {@link
 * EditorMenu}. The editors take their colors from the {@link Palette}; native controls follow
 * {@link SwtThemeService}. {@link ToolIcons} owned by the window re-ink on the UI turn after a
 * palette change, once native controls have restyled.
 *
 * <p>{@link WindowDialogs} opens the prompts and dialogs. The file choosers are native dialogs and
 * run a nested event loop; the unsaved-changes and binary-file prompts do not.
 *
 * <p>Menu enablement syncs on {@link #documentStateChanged} and when the shell is activated, so an
 * accelerator works without a menu having been shown; {@link CommandStates#EDIT_STATE} also syncs
 * on each status read. The status bar shows the selected document's {@link StatusFields}, read once
 * per UI turn however many caret moves or state changes arrive in it; a notice; and a Warnings link
 * to Show Log while a warning is unseen. A Display key filter runs window bindings that are not
 * accelerators. {@link #setFontSize} replaces the editor font in every editor in place, without
 * rebuilding the menus or the window, and keeps the focus.
 *
 * <p>{@link #setActive} and {@link #setIdle} count overlapping operations through a {@link
 * BusyCounter}: while any is active the shell shows {@code SWT.CURSOR_APPSTARTING}, which keeps the
 * window usable.
 *
 * <p>Files dropped on the window open a tab each ({@link #dropped}); the shell is the only drop
 * target, and a drag offering no file never reaches it. The title follows the selected document
 * with the status fields. A {@link ProgressRow} above the status bar shows loads past a short
 * delay.
 *
 * <p>{@link #place} sizes and positions the shell before {@link #open}; the normal bounds are kept
 * on every unmaximized move and resize for {@link #windowBounds}. Dialogs open without a nested
 * event loop; a text window opened over a modal dialog is modal and owned by it, because GTK gives
 * input only to the newest modal shell.
 */
public final class MainWindow implements MainView {
  private static final Logger LOG = LoggerFactory.getLogger(MainWindow.class);

  /** System property SWT sets on GTK to {@code x11}, {@code wayland} or {@code unknown}. */
  static final String GDK_BACKEND = PlatformTraits.GDK_BACKEND;

  /** System property SWT sets on GTK to the GTK version. */
  static final String GTK_VERSION = "org.eclipse.swt.internal.gtk.version";

  /** How long a notice stays in the status bar. */
  static final int NOTICE_MILLIS = 8000;

  /** Finds the control under a released tab drag. */
  interface PointerProbe {
    /**
     * Returns the control under a point.
     *
     * @param point the point in display coordinates
     * @return the control, or null outside every QCaret shell
     */
    Control controlAt(Point point);
  }

  private final Shell shell;
  private final CTabFolder tabs;
  private final List<Image> icons;
  private final ToolIcons toolIcons;
  private final MenuIcons menuIcons;
  private final SwtUiDispatcher dispatcher;

  private final Composite statusBar;
  private ProgressRow printBar;
  private Supplier<PrinterData> printerChooser;
  private Supplier<String> pdfChooser;
  private final Label position;
  private final Label selection;
  private final Label encoding;
  private final Label lineEnding;
  private final Label language;
  private final Label readOnly;
  private final Label notice;
  private final Link warnings;
  private final BusyCounter busy;
  private final int baseFontSize;
  private final List<TextWindow> textWindows = new ArrayList<TextWindow>();
  private int fontPoints;
  private String fontName;
  private Font font;
  private final TabStrip tabStrip;
  private final WindowDialogs dialogs;
  private ApplicationController controller;
  private MainMenuBar menuBar;
  private MainToolBar mainToolBar;
  private TabStripButtons tabButtons;
  private EditorMenu editorMenu;
  private final ToolBar toolBar;
  private boolean lineNumbersShown = true;
  private int rightMargin;
  private boolean indentGuides;
  private boolean lineChangesShown;
  private StatisticsDialog statisticsDialog;
  private HashTextDialog hashTextDialog;
  private ChecksumDialog checksumDialog;
  private DateFormatChooser dateFormatChooser;
  private Keymap keymap;
  private TabMenu tabMenu;
  private TextWindow logWindow;
  private GuideWindow guide;
  private boolean statusPending;
  private long noticeGeneration;
  private PaletteListener paletteListener;
  private Palette palette;
  private Clipboard clipboard;
  private WindowGeometry.Request normalBounds = new WindowGeometry.Request(null, null, null, null);
  private FindBar findBar;
  private ProgressRow loadBar;
  private final SashForm body;
  private final SashForm side;
  private WorkspaceDock workspaceDock;
  private BrowserSideWidth browserWidth;
  private SashForm docks;
  private ResultsDock resultsDock;
  private BookmarksDock bookmarksDock;

  /**
   * Creates the window, not yet visible.
   *
   * @param display the Display
   * @param palette the palette the text view starts with
   */
  public MainWindow(Display display, Palette palette) {
    this.palette = palette;
    this.dispatcher = new SwtUiDispatcher(display);
    shell = new Shell(display, SWT.SHELL_TRIM);
    shell.setText(UiText.WINDOW_TITLE.text());
    icons = Icons.load(display, Icons.Variant.DARK);
    shell.setImages(icons.toArray(new Image[0]));
    GridLayout layout = new GridLayout(1, false);
    layout.marginWidth = 0;
    layout.marginHeight = 0;
    layout.verticalSpacing = 0;
    shell.setLayout(layout);
    toolBar = new ToolBar(shell, SWT.FLAT | SWT.HORIZONTAL);
    toolBar.setLayoutData(new GridData(SWT.FILL, SWT.CENTER, true, false));
    side = new SashForm(shell, SWT.HORIZONTAL);
    side.setLayoutData(new GridData(SWT.FILL, SWT.FILL, true, true));
    body = new SashForm(side, SWT.VERTICAL);
    tabStrip =
        new TabStrip(
            body,
            dispatcher,
            new TabStrip.Host() {
              @Override
              public EditorPane newPane(
                  SashForm sash,
                  DisplayProjection projection,
                  SelectionModel selections,
                  EditorInput editor) {
                // The current palette; the constructor's palette parameter is the first one.
                EditorPane added =
                    new EditorPane(
                        sash,
                        projection,
                        selections,
                        editor,
                        keymap,
                        font,
                        MainWindow.this.palette,
                        dispatcher,
                        lineNumbersShown);
                added.setRightMargin(rightMargin);
                added.setIndentGuides(indentGuides);
                added.setLineChangesShown(lineChangesShown);
                return added;
              }

              @Override
              public void showEditorMenu(EditorPane pane, EditorInput editor, Event event) {
                MainWindow.this.showEditorMenu(pane, editor, event);
              }

              @Override
              public void scheduleStatus() {
                MainWindow.this.scheduleStatus();
              }

              @Override
              public ApplicationController controller() {
                return controller;
              }

              @Override
              public TabMenu tabMenu() {
                return tabMenu;
              }
            });
    tabs = tabStrip.folder();
    dialogs =
        new WindowDialogs(
            shell,
            dispatcher,
            this,
            new WindowDialogs.Sizes() {
              @Override
              public DialogSize size(String name) {
                return controller.dialogSize(name);
              }

              @Override
              public void remember(String name, DialogSize size) {
                controller.rememberDialogSize(name, size);
              }
            });
    baseFontSize = Dialogs.monospaceSize();
    fontPoints = baseFontSize;
    fontName = Dialogs.monospaceName();
    font = new Font(display, fontName, fontPoints, SWT.NORMAL);
    statusBar = new Composite(shell, SWT.NONE);
    statusBar.setLayoutData(new GridData(SWT.FILL, SWT.CENTER, true, false));
    GridLayout statusLayout = new GridLayout(8, false);
    statusLayout.marginHeight = 1;
    statusLayout.marginWidth = 6;
    statusLayout.horizontalSpacing = 14;
    statusBar.setLayout(statusLayout);
    notice = new Label(statusBar, SWT.NONE);
    notice.setLayoutData(new GridData(SWT.FILL, SWT.CENTER, true, false));
    warnings = new Link(statusBar, SWT.NONE);
    SwtThemeService.colorLink(warnings);
    warnings.setText("<a>" + UiText.STATUS_WARNINGS.text() + "</a>");
    warnings.setToolTipText(UiText.STATUS_WARNINGS_TIP.text());
    GridData warningsData = new GridData(SWT.END, SWT.CENTER, false, false);
    warningsData.exclude = true;
    warnings.setLayoutData(warningsData);
    warnings.setVisible(false);
    warnings.addListener(
        SWT.Selection,
        new Listener() {
          @Override
          public void handleEvent(Event event) {
            if (controller != null) {
              controller.execute(CommandId.SHOW_LOG);
            }
          }
        });
    readOnly = statusLabel();
    position = statusLabel();
    selection = statusLabel();
    encoding = statusLabel();
    lineEnding = statusLabel();
    language = statusLabel();
    language.setToolTipText(UiText.STATUS_LANGUAGE_TIP.text());
    language.addListener(
        SWT.MouseUp,
        new Listener() {
          @Override
          public void handleEvent(Event event) {
            if (menuBar != null && event.button == 1) {
              menuBar.showSyntaxMenu(language);
            }
          }
        });
    busy =
        new BusyCounter(
            dispatcher,
            new BusyCounter.Indicator() {
              @Override
              public void busyChanged(boolean active) {
                if (!shell.isDisposed()) {
                  shell.setCursor(
                      active ? shell.getDisplay().getSystemCursor(SWT.CURSOR_APPSTARTING) : null);
                }
              }
            });
    toolIcons = new ToolIcons(toolBar);
    menuIcons = new MenuIcons(toolBar);
    applyPalette(palette);
    trackWindowState();
    acceptDroppedFiles();
    shell.addListener(
        SWT.Dispose,
        new Listener() {
          @Override
          public void handleEvent(Event event) {
            if (controller != null) {
              controller.theme().removeListener(paletteListener);
            }
            font.dispose();
            for (Image image : icons) {
              image.dispose();
            }
            if (clipboard != null) {
              clipboard.dispose();
            }
          }
        });
  }

  /**
   * Connects the window to its controller: builds the menu bar, starts listing the charsets, routes
   * close requests to {@link ApplicationController#closeWindow}, and follows the controller's
   * palette. Call once, before {@link #open} and before the first tab.
   *
   * @param owner the controller
   */
  public void attach(ApplicationController owner) {
    controller = owner;
    keymap = owner.keymap();
    menuBar = new MainMenuBar(shell, owner, menuIcons);
    tabMenu = new TabMenu(shell, owner, menuBar.states());
    menuBar.build(keymap);
    mainToolBar = new MainToolBar(toolBar, toolIcons, owner, menuBar.states(), this::focusEditor);
    mainToolBar.setKeymap(keymap);
    setToolbarShown(owner.toolbarShown());
    editorMenu = new EditorMenu(shell, menuBar.states(), menuIcons, keymap);
    int fontSize = owner.fontSize();
    if (fontSize != 0) {
      setFontSize(fontSize);
    }
    setFontFamily(owner.fontFamily());
    owner.loadEncodings(
        new EncodingChoices.Listener() {
          @Override
          public void loaded(EncodingChoices choices) {
            if (!shell.isDisposed()) {
              menuBar.encodingsLoaded(choices);
            }
          }
        });
    tabButtons =
        new TabStripButtons(
            tabs,
            palette,
            new Consumer<CommandId>() {
              @Override
              public void accept(CommandId command) {
                controller.execute(command);
              }
            });
    tabButtons.setKeymap(keymap);
    dispatchWindowKeys();
    findBar = new FindBar(shell, owner.find());
    findBar.control().moveAbove(statusBar);
    loadBar = new ProgressRow(shell, true);
    loadBar.control().moveAbove(statusBar);
    printBar = new ProgressRow(shell, false);
    printBar.control().moveAbove(statusBar);
    docks = new SashForm(body, SWT.HORIZONTAL);
    resultsDock = new ResultsDock(docks, palette);
    bookmarksDock = new BookmarksDock(docks);
    resultsDock.control().setVisible(false);
    bookmarksDock.control().setVisible(false);
    docks.setWeights(new int[] {3, 2});
    body.setWeights(new int[] {3, 1});
    body.setMaximizedControl(tabs);
    workspaceDock = new WorkspaceDock(side, toolBar);
    workspaceDock.control().moveAbove(body);
    browserWidth = new BrowserSideWidth(side, workspaceDock.buttons());
    side.setMaximizedControl(body);
    if (menuBar.states().checked(CommandId.WORKSPACE_BROWSER)) {
      showWorkspace(owner.workspace(), true);
    }
    shell.addListener(
        SWT.Activate,
        new Listener() {
          @Override
          public void handleEvent(Event event) {
            syncCommands();
            controller.windowActivated();
          }
        });
    onCloseRequest(
        new Runnable() {
          @Override
          public void run() {
            controller.closeWindow();
          }
        });
    paletteListener =
        new PaletteListener() {
          @Override
          public void paletteChanged(Palette next) {
            applyPalette(next);
          }
        };
    controller.theme().addListener(paletteListener);
    applyPalette(controller.theme().palette());
  }

  /**
   * Opens files dropped on the window. A drop always copies: accepting a move would have the source
   * delete the file.
   */
  private void acceptDroppedFiles() {
    DropTarget target = new DropTarget(shell, DND.DROP_COPY | DND.DROP_MOVE | DND.DROP_DEFAULT);
    target.setTransfer(FileTransfer.getInstance());
    target.addDropListener(
        new DropTargetAdapter() {
          @Override
          public void dragEnter(DropTargetEvent event) {
            event.detail = DND.DROP_COPY;
          }

          @Override
          public void dragOperationChanged(DropTargetEvent event) {
            event.detail = DND.DROP_COPY;
          }

          @Override
          public void drop(DropTargetEvent event) {
            if (event.data instanceof String[] names) {
              dropped(names);
            }
          }
        });
  }

  /**
   * Opens dropped files, as a drop does; a path that does not parse shows why.
   *
   * @param names the dropped paths as the platform gives them
   */
  void dropped(String[] names) {
    if (controller == null) {
      return;
    }
    List<Path> paths = new ArrayList<Path>();
    for (String name : names) {
      try {
        paths.add(Path.of(name));
      } catch (InvalidPathException e) {
        showInfo(UiText.OPEN_TITLE.text(), UiText.OPEN_FAILED.format(name, e.getReason()));
      }
    }
    controller.openDropped(paths);
  }

  @Override
  public void showLoadProgress(String text, int percent, Runnable cancel) {
    if (!shell.isDisposed()) {
      loadBar.show(text, percent, cancel);
    }
  }

  @Override
  public void hideLoadProgress() {
    if (!shell.isDisposed()) {
      loadBar.hide();
    }
  }

  /**
   * Returns the row showing running loads.
   *
   * @return the row
   */
  ProgressRow loadBar() {
    return loadBar;
  }

  /**
   * Returns the status bar.
   *
   * @return the bar
   */
  Composite statusBar() {
    return statusBar;
  }

  private Label statusLabel() {
    Label label = new Label(statusBar, SWT.NONE);
    label.setLayoutData(new GridData(SWT.END, SWT.CENTER, false, false));
    return label;
  }

  private void applyPalette(Palette next) {
    palette = next;
    tabStrip.colorTabs(next);
    if (tabButtons != null) {
      tabButtons.setPalette(next);
    }
    if (resultsDock != null) {
      resultsDock.setPalette(next);
    }
    dialogs.setPalette(next);
    for (EditorPane pane : allPanes()) {
      pane.setPalette(next);
    }
    // Native controls restyle after the theme call returns; re-ink the icons on the next turn.
    dispatcher.asyncExec(
        new Runnable() {
          @Override
          public void run() {
            if (!shell.isDisposed()) {
              toolIcons.reink();
              menuIcons.reink();
              if (workspaceDock != null) {
                workspaceDock.reink();
              }
            }
          }
        });
  }

  /**
   * Returns the palette the text view paints with.
   *
   * @return the palette
   */
  public Palette palette() {
    return palette;
  }

  /**
   * Returns the selected tab's editor.
   *
   * @return the editor, or null without a tab
   */
  public EditorPane activeEditor() {
    return tabStrip.activeEditor();
  }

  /**
   * Returns the selected tab's text widget.
   *
   * @return the widget, or null without a tab
   */
  public StyledText activeText() {
    EditorPane pane = activeEditor();
    return pane == null ? null : pane.text();
  }

  /**
   * Returns the tab strip.
   *
   * @return the folder holding one item per document
   */
  CTabFolder tabs() {
    return tabs;
  }

  @Override
  public TabView addTab(
      DisplayProjection projection,
      SelectionModel selections,
      EditorInput editor,
      TabInput input,
      int index) {
    return tabStrip.addTab(projection, selections, editor, input, index);
  }

  @Override
  public void documentStateChanged() {
    if (menuBar != null && !shell.isDisposed()) {
      syncCommands();
    }
    scheduleStatus();
  }

  @Override
  public void editStateChanged() {
    scheduleStatus();
  }

  /** Enables the menu items and the toolbar's buttons from the command states. */
  private void syncCommands() {
    menuBar.syncEnablement();
    mainToolBar.sync();
  }

  private void focusEditor() {
    EditorPane pane = activeEditor();
    if (pane != null) {
      pane.focus();
    }
  }

  /**
   * Opens the editor menu over a view. The pointer's point first places the caret, unless it lies
   * in a selection; a menu opened from the keyboard shows below the caret and moves nothing.
   */
  private void showEditorMenu(EditorPane pane, EditorInput input, Event event) {
    pane.focus();
    Point at = new Point(event.x, event.y);
    if (event.detail == SWT.MENU_KEYBOARD) {
      at = pane.menuLocation();
    } else {
      pane.menuAt(event.x, event.y);
    }
    editorMenu.show(input, at.x, at.y);
  }

  /**
   * Returns the editor menu.
   *
   * @return the menu's owner, or null before {@link #attach}
   */
  EditorMenu editorMenu() {
    return editorMenu;
  }

  /**
   * Returns the main toolbar.
   *
   * @return the toolbar's owner, or null before {@link #attach}
   */
  MainToolBar mainToolBar() {
    return mainToolBar;
  }

  /**
   * Returns the buttons at the end of the tab strip.
   *
   * @return the buttons, or null before {@link #attach}
   */
  TabStripButtons tabButtons() {
    return tabButtons;
  }

  @Override
  public void setToolbarShown(boolean shown) {
    if (shell.isDisposed() || toolBar.getVisible() == shown) {
      return;
    }
    toolBar.setVisible(shown);
    ((GridData) toolBar.getLayoutData()).exclude = !shown;
    shell.layout(true);
    toolIcons.reink();
    menuIcons.reink();
  }

  @Override
  public void setLineNumbersShown(boolean shown) {
    lineNumbersShown = shown;
    for (EditorPane pane : allPanes()) {
      pane.setLineNumbersShown(shown);
    }
  }

  @Override
  public void setRightMargin(int column) {
    rightMargin = column;
    for (EditorPane pane : allPanes()) {
      pane.setRightMargin(column);
    }
  }

  @Override
  public void setIndentGuides(boolean shown) {
    indentGuides = shown;
    for (EditorPane pane : allPanes()) {
      pane.setIndentGuides(shown);
    }
  }

  @Override
  public void setLineChangesShown(boolean shown) {
    lineChangesShown = shown;
    for (EditorPane pane : allPanes()) {
      pane.setLineChangesShown(shown);
    }
  }

  /** Returns every editor of every tab. */
  private List<EditorPane> allPanes() {
    return tabStrip.allPanes();
  }

  @Override
  public StatisticsDisplay showStatistics(StatisticsInput input) {
    if (shell.isDisposed()) {
      return StatisticsDialog.NONE;
    }
    statisticsDialog = new StatisticsDialog(shell, input);
    statisticsDialog.open();
    return statisticsDialog;
  }

  @Override
  public HashTextDisplay showHashText(String title, String note, HashTextInput input) {
    if (shell.isDisposed()) {
      return HashTextDialog.NONE;
    }
    hashTextDialog = new HashTextDialog(shell, title, note, input);
    hashTextDialog.open();
    return hashTextDialog;
  }

  @Override
  public ChecksumDisplay showChecksums(
      String title, String note, String chooseLabel, ChecksumInput input) {
    if (shell.isDisposed()) {
      return ChecksumDialog.NONE;
    }
    checksumDialog = new ChecksumDialog(shell, title, note, chooseLabel, input);
    checksumDialog.open();
    return checksumDialog;
  }

  /**
   * Returns the newest Tools > Generate dialog.
   *
   * @return the dialog, or null before one opened
   */
  HashTextDialog hashTextDialog() {
    return hashTextDialog;
  }

  /**
   * Returns the newest checksum list dialog.
   *
   * @return the dialog, or null before one opened
   */
  ChecksumDialog checksumDialog() {
    return checksumDialog;
  }

  @Override
  public void chooseDateFormat(List<String> rows, int selected, Consumer<Integer> chosen) {
    if (!shell.isDisposed()) {
      dateFormatChooser = new DateFormatChooser(shell, rows, selected, chosen);
      dateFormatChooser.open();
    }
  }

  /**
   * Returns the newest Document Statistics dialog.
   *
   * @return the dialog, or null before one opened
   */
  StatisticsDialog statisticsDialog() {
    return statisticsDialog;
  }

  /**
   * Returns the newest date format chooser.
   *
   * @return the dialog, or null before one opened
   */
  DateFormatChooser dateFormatChooser() {
    return dateFormatChooser;
  }

  /** Shows the status fields on a later UI turn; calls before then share that one read. */
  private void scheduleStatus() {
    if (statusPending || shell.isDisposed()) {
      return;
    }
    statusPending = true;
    dispatcher.asyncExec(
        new Runnable() {
          @Override
          public void run() {
            statusPending = false;
            showStatus();
          }
        });
  }

  /** Shows the title and the status fields now, before the event loop runs. */
  public void showStatusNow() {
    showStatus();
  }

  private void showStatus() {
    if (shell.isDisposed() || menuBar == null) {
      return;
    }
    menuBar.syncEditState();
    mainToolBar.syncEditState();
    String title = menuBar.states().windowTitle();
    if (!title.equals(shell.getText())) {
      shell.setText(title);
    }
    StatusFields fields = menuBar.states().status();
    boolean changed = set(position, fields.position());
    changed |= set(selection, fields.selection());
    changed |= set(encoding, fields.encoding());
    changed |= set(lineEnding, fields.lineEnding());
    changed |= set(language, fields.language());
    changed |= set(readOnly, fields.readOnly());
    if (changed) {
      statusBar.layout(true);
    }
  }

  private static boolean set(Label label, String text) {
    if (label.getText().equals(text)) {
      return false;
    }
    label.setText(text.replace("&", "&&"));
    return true;
  }

  /**
   * Sets how a released tab drag finds the control under it; for tests.
   *
   * @param probe the probe
   */
  void setPointerProbe(PointerProbe probe) {
    tabStrip.setPointerProbe(probe);
  }

  /**
   * Opens the context menu of the tab at an index; for tests.
   *
   * @param index the tab index
   * @return the menu
   */
  Menu showTabMenu(int index) {
    return tabStrip.showTabMenu(index);
  }

  /**
   * Recreates a tab at another index, as a drag within the strip does.
   *
   * @param source the dragged item
   * @param index the drop index
   */
  void moveTab(CTabItem source, int index) {
    tabStrip.moveTab(source, index);
  }

  @Override
  public void chooseFilesToOpen(Path directory, Consumer<List<Path>> chosen) {
    if (shell.isDisposed()) {
      return;
    }
    FileDialog dialog = new FileDialog(shell, SWT.OPEN | SWT.MULTI);
    if (directory != null) {
      dialog.setFilterPath(directory.toString());
    }
    if (dialog.open() == null) {
      return;
    }
    List<Path> paths = new ArrayList<Path>();
    for (String name : dialog.getFileNames()) {
      paths.add(Path.of(dialog.getFilterPath(), name));
    }
    chosen.accept(paths);
  }

  @Override
  public void chooseSaveTarget(Path directory, String name, Consumer<Path> chosen) {
    if (shell.isDisposed()) {
      chosen.accept(null);
      return;
    }
    FileDialog dialog = new FileDialog(shell, SWT.SAVE);
    dialog.setOverwrite(true);
    dialog.setFileName(name);
    if (directory != null) {
      dialog.setFilterPath(directory.toString());
    }
    String chosenPath = dialog.open();
    chosen.accept(chosenPath == null ? null : Path.of(chosenPath));
  }

  @Override
  public void choosePrinter(Consumer<PrintTarget> chosen) {
    if (shell.isDisposed()) {
      return;
    }
    PrinterData data;
    Printer printer;
    try {
      if (PlatformTraits.PLATFORM.checksPrinterList() && Printer.getPrinterList().length == 0) {
        showNotice(UiText.PRINT_NO_PRINTER.text());
        return;
      }
      if (printerChooser != null) {
        data = printerChooser.get();
      } else if (PlatformTraits.PLATFORM.opensPrintDialog()) {
        data = new PrintDialog(shell).open();
      } else {
        String file = pdfChooser != null ? pdfChooser.get() : PdfPrinter.choose(shell);
        data = file == null ? null : PdfPrinter.printer(file);
      }
      if (data == null) {
        return;
      }
      printer = new Printer(data);
    } catch (SWTError | SWTException | IllegalArgumentException e) {
      LOG.warn("The printer did not open", e);
      showNotice(UiText.PRINT_NO_PRINTER.text());
      return;
    }
    String family = font.getFontData()[0].getName();
    chosen.accept(new PrintJob(printer, data, family, shell.getDisplay()));
  }

  /** Replaces the print dialog; the supplier returns what the dialog would. */
  void setPrinterChooser(Supplier<PrinterData> chooser) {
    printerChooser = chooser;
  }

  /** Replaces the PDF save dialog where {@code PrintDialog} does not open. */
  void setPdfChooser(Supplier<String> chooser) {
    pdfChooser = chooser;
  }

  @Override
  public void showPrintProgress(String text, int percent, Runnable cancel) {
    if (!shell.isDisposed()) {
      printBar.show(text, percent, cancel);
    }
  }

  @Override
  public void hidePrintProgress() {
    if (!shell.isDisposed()) {
      printBar.hide();
    }
  }

  /** Returns the print row. */
  ProgressRow printBar() {
    return printBar;
  }

  @Override
  public void askUnsaved(String name, Consumer<UnsavedChoice> answer) {
    if (shell.isDisposed()) {
      answer.accept(UnsavedChoice.CANCEL);
      return;
    }
    new UnsavedDialog(shell, name, answer).open();
  }

  @Override
  public void askReplaceOpenFile(String name, Runnable replace, Runnable declined) {
    if (shell.isDisposed()) {
      declined.run();
      return;
    }
    new ReplaceOpenDialog(shell, name, replace, declined).open();
  }

  @Override
  public void askOpenBinary(String name, Runnable open) {
    if (!shell.isDisposed()) {
      new BinaryDialog(shell, name, open).open();
    }
  }

  @Override
  public void setActive() {
    busy.setActive();
  }

  @Override
  public void setIdle() {
    busy.setIdle();
  }

  /**
   * Returns the operation counter behind the busy cursor.
   *
   * @return the counter
   */
  BusyCounter busy() {
    return busy;
  }

  @Override
  public void showNotice(String message) {
    if (notice.isDisposed()) {
      return;
    }
    notice.setText(message.replace("&", "&&"));
    statusBar.layout(new Control[] {notice});
    final long shown = ++noticeGeneration;
    dispatcher.timerExec(
        NOTICE_MILLIS,
        new Runnable() {
          @Override
          public void run() {
            if (shown == noticeGeneration && !notice.isDisposed()) {
              notice.setText("");
            }
          }
        });
  }

  @Override
  public void showLog(String text) {
    if (shell.isDisposed()) {
      return;
    }
    if (logWindow == null || logWindow.shell().isDisposed()) {
      Shell owner = modalOwner();
      logWindow = textWindow(owner, UiText.SHOW_LOG_TITLE.text(), text);
      logWindow.open();
      logWindow.showStart();
    } else {
      logWindow.setContent(text);
      logWindow.shell().setActive();
    }
  }

  @Override
  public void showWarningMarker(boolean shown) {
    if (warnings.isDisposed() || warnings.getVisible() == shown) {
      return;
    }
    warnings.setVisible(shown);
    ((GridData) warnings.getLayoutData()).exclude = !shown;
    statusBar.layout(true);
  }

  @Override
  public boolean launch(Path target) {
    return Program.launch(target.toString());
  }

  @Override
  public boolean launchUrl(String url) {
    return Program.launch(url);
  }

  /**
   * Shows the user guide in a {@link GuideWindow}. Where {@link PlatformTraits#opensBrowser} is
   * false (GTK 4) it shows a notice naming the file instead and never creates a {@code Browser}.
   */
  @Override
  public boolean showGuide(Path file) {
    if (shell.isDisposed()) {
      return true;
    }
    if (!PlatformTraits.PLATFORM.opensBrowser()) {
      showNotice(UiText.NOTICE_GUIDE_AT.format(file));
      return true;
    }
    if (guide != null && !guide.shell().isDisposed()) {
      guide.show(file);
      return true;
    }
    GuideWindow created = GuideWindow.create(shell, file, this::guideFailed);
    if (created == null) {
      return false;
    }
    guide = created;
    created.open();
    if (!created.shell().isDisposed()) {
      dialogs.rememberSize(created.shell(), "guide");
    }
    return true;
  }

  private void guideFailed(Path file) {
    if (controller != null && !shell.isDisposed()) {
      controller.showGuideElsewhere(file);
    }
  }

  /**
   * Returns the user guide window.
   *
   * @return the window, or null before the guide was shown in one
   */
  GuideWindow guideWindow() {
    return guide;
  }

  @Override
  public int defaultFontSize() {
    return baseFontSize;
  }

  @Override
  public void setFontSize(int points) {
    if (shell.isDisposed()) {
      return;
    }
    fontPoints = points == 0 ? baseFontSize : points;
    replaceFont();
  }

  @Override
  public void setFontFamily(String family) {
    if (shell.isDisposed()) {
      return;
    }
    String next = EditorFonts.family(shell.getDisplay(), family);
    if (next.equals(fontName)) {
      return;
    }
    fontName = next;
    replaceFont();
    dialogs.setFont(next, font);
    for (TextWindow window : textWindows) {
      window.setFontName(next);
    }
  }

  private void replaceFont() {
    Font old = font;
    font = new Font(shell.getDisplay(), fontName, fontPoints, SWT.NORMAL);
    for (EditorPane pane : allPanes()) {
      pane.setFont(font);
    }
    old.dispose();
    if (menuBar != null) {
      syncCommands();
    }
  }

  /**
   * Returns the editor font.
   *
   * @return the font every editor uses
   */
  Font editorFont() {
    return font;
  }

  /**
   * Returns the find bar.
   *
   * @return the bar, or null before {@link #attach}
   */
  FindBar findBar() {
    return findBar;
  }

  /**
   * Returns the selected tab's editor views.
   *
   * @return the views, empty without a tab
   */
  List<EditorPane> activeEditors() {
    return tabStrip.activeEditors();
  }

  /**
   * Returns the menu bar.
   *
   * @return the menu bar's owner
   */
  MainMenuBar menuBar() {
    return menuBar;
  }

  /**
   * Returns the status bar's fields as shown, joined by {@code |}.
   *
   * @return read-only marker, position, selection, encoding, line endings and language
   */
  String statusText() {
    return readOnly.getText()
        + "|"
        + position.getText()
        + "|"
        + selection.getText()
        + "|"
        + encoding.getText()
        + "|"
        + lineEnding.getText()
        + "|"
        + language.getText();
  }

  /**
   * Returns the status bar's notice.
   *
   * @return the notice, empty when none shows
   */
  String noticeText() {
    return notice.getText();
  }

  /**
   * Reports whether the status bar shows the warning marker.
   *
   * @return true while shown
   */
  boolean warningMarkerShown() {
    return !warnings.isDisposed() && warnings.getVisible();
  }

  /**
   * Returns the log window.
   *
   * @return the window, or null before Show Log
   */
  TextWindow logWindow() {
    return logWindow;
  }

  /**
   * Runs window bindings that are not a menu item's accelerator, such as a command's second window
   * key, from a Display filter while this window has a key event. A disabled command does not run.
   */
  private void dispatchWindowKeys() {
    final Display display = shell.getDisplay();
    final Listener filter =
        new Listener() {
          @Override
          public void handleEvent(Event event) {
            if (controller == null
                || !(event.widget instanceof Control control)
                || control.isDisposed()
                || control.getShell() != shell) {
              return;
            }
            KeyStroke stroke = SwtKeys.stroke(event.stateMask, event.keyCode, event.character);
            CommandId command = stroke == null ? null : keymap.windowCommand(stroke);
            if (command != null) {
              event.type = SWT.None;
              event.doit = false;
              if (menuBar.states().enabled(command)) {
                controller.execute(command);
              }
            }
          }
        };
    display.addFilter(SWT.KeyDown, filter);
    shell.addListener(
        SWT.Dispose,
        new Listener() {
          @Override
          public void handleEvent(Event event) {
            display.removeFilter(SWT.KeyDown, filter);
          }
        });
  }

  /**
   * Returns the icons drawn on the window's native controls.
   *
   * @return the icon set
   */
  ToolIcons toolIcons() {
    return toolIcons;
  }

  /**
   * Returns the menu icons.
   *
   * @return the icons
   */
  MenuIcons menuIcons() {
    return menuIcons;
  }

  /**
   * Sizes and positions the window from a request; call before {@link #open}. The position is not
   * applied where the window system ignores it: Wayland, and SWT on GTK 4, which has no window
   * move.
   *
   * @param request command-line geometry; null fields are not given
   * @param persisted geometry from the previous session, or null
   * @return the placement applied
   */
  public WindowGeometry.Placement place(
      WindowGeometry.Request request, WindowGeometry.Request persisted) {
    Display display = shell.getDisplay();
    List<WindowGeometry.Screen> screens = new ArrayList<WindowGeometry.Screen>();
    for (Monitor monitor : display.getMonitors()) {
      screens.add(screen(monitor));
    }
    WindowGeometry.Screen primary = screen(display.getPrimaryMonitor());
    Rectangle trim = shell.computeTrim(0, 0, 100, 100);
    boolean positionSupported = PlatformTraits.PLATFORM.positionsWindows();
    WindowGeometry.Placement placement =
        WindowGeometry.place(
            request,
            persisted,
            screens,
            primary,
            trim.width - 100,
            trim.height - 100,
            positionSupported);
    apply(placement);
    if (PlatformTraits.PLATFORM.remeasuresTrim()) {
      Point size = shell.getSize();
      Rectangle client = shell.getClientArea();
      int trimWidth = size.x - client.width;
      int trimHeight = size.y - client.height;
      if (trimWidth != trim.width - 100 || trimHeight != trim.height - 100) {
        placement =
            WindowGeometry.place(
                request, persisted, screens, primary, trimWidth, trimHeight, positionSupported);
        apply(placement);
      }
    }
    return placement;
  }

  private void apply(WindowGeometry.Placement placement) {
    WindowGeometry.Rect bounds = placement.bounds();
    if (placement.setLocation()) {
      shell.setBounds(bounds.x(), bounds.y(), bounds.width(), bounds.height());
    } else {
      shell.setSize(bounds.width(), bounds.height());
    }
  }

  private static WindowGeometry.Screen screen(Monitor monitor) {
    return new WindowGeometry.Screen(rect(monitor.getBounds()), rect(monitor.getClientArea()));
  }

  private static WindowGeometry.Rect rect(Rectangle r) {
    return new WindowGeometry.Rect(r.x, r.y, r.width, r.height);
  }

  /** Keeps the normal bounds whenever the window moves or resizes unmaximized. */
  private void trackWindowState() {
    Listener track =
        new Listener() {
          @Override
          public void handleEvent(Event event) {
            if (shell.isDisposed() || shell.getMaximized() || shell.getMinimized()) {
              return;
            }
            Point location = shell.getLocation();
            Rectangle client = shell.getClientArea();
            if (client.width > 0 && client.height > 0) {
              normalBounds =
                  new WindowGeometry.Request(location.x, location.y, client.width, client.height);
            }
          }
        };
    shell.addListener(SWT.Move, track);
    shell.addListener(SWT.Resize, track);
  }

  /** Shows the window. */
  public void open() {
    shell.open();
  }

  /**
   * Returns the SWT shell, for the event loop and tests.
   *
   * @return the shell
   */
  public Shell shell() {
    return shell;
  }

  /**
   * Routes window-system close requests to a handler instead of closing.
   *
   * @param handler runs on the UI thread for each close request
   */
  public void onCloseRequest(final Runnable handler) {
    shell.addListener(
        SWT.Close,
        new Listener() {
          @Override
          public void handleEvent(Event event) {
            event.doit = false;
            handler.run();
          }
        });
  }

  @Override
  public void hide() {
    if (!shell.isDisposed()) {
      shell.setVisible(false);
    }
  }

  @Override
  public void toFront() {
    if (shell.isDisposed()) {
      return;
    }
    if (shell.getMinimized()) {
      shell.setMinimized(false);
    }
    shell.forceActive();
  }

  @Override
  public void dispose() {
    if (!shell.isDisposed()) {
      shell.dispose();
    }
  }

  @Override
  public void showInfo(String title, String message) {
    if (!shell.isDisposed()) {
      new ErrorDialog(shell, title, message).open();
    }
  }

  @Override
  public void showText(String title, String value) {
    if (shell.isDisposed()) {
      return;
    }
    Shell owner = modalOwner();
    textWindow(owner, title, value).open();
  }

  /** Creates a text window that follows the font family while it is open. */
  private TextWindow textWindow(Shell owner, String title, String value) {
    final TextWindow window = new TextWindow(owner, title, value, this, owner != shell, fontName);
    textWindows.add(window);
    window
        .shell()
        .addListener(
            SWT.Dispose,
            new Listener() {
              @Override
              public void handleEvent(Event event) {
                textWindows.remove(window);
              }
            });
    return window;
  }

  /** Returns the innermost visible modal dialog over the main window, or the main window. */
  private Shell modalOwner() {
    Shell owner = shell;
    boolean found = true;
    while (found) {
      found = false;
      for (Shell child : owner.getShells()) {
        if (child.isVisible() && (child.getStyle() & SWT.APPLICATION_MODAL) != 0) {
          owner = child;
          found = true;
          break;
        }
      }
    }
    return owner;
  }

  @Override
  public void showAbout(AboutInfo info) {
    if (shell.isDisposed()) {
      return;
    }
    Runnable licenses =
        new Runnable() {
          @Override
          public void run() {
            controller.showLicenses();
          }
        };
    new AboutDialog(shell, info, logoVariant(Dialogs.background(shell)), licenses, this).open();
  }

  /**
   * Picks the About logo for a dialog background. Native chrome can differ from the palette (cocoa,
   * win32 before a restart, a GTK theme with no dark variant), so the background decides, not the
   * theme setting.
   *
   * @param background the dialog's background
   * @return the navy-tile logo on a dark background, the light logo otherwise
   */
  static Icons.Variant logoVariant(Color background) {
    return ToolIcons.dark(background) ? Icons.Variant.DARK : Icons.Variant.LIGHT;
  }

  @Override
  public String toolkitDescription() {
    String gtk = System.getProperty(GTK_VERSION);
    return "SWT "
        + SWT.getVersion()
        + " "
        + PlatformTraits.PLATFORM.name()
        + (gtk == null ? "" : " (GTK " + gtk + ")");
  }

  @Override
  public WindowGeometry.Request windowBounds() {
    return normalBounds;
  }

  @Override
  public boolean windowMaximized() {
    return !shell.isDisposed() && shell.getMaximized();
  }

  @Override
  public void setText(String value) {
    if (shell.isDisposed()) {
      return;
    }
    if (clipboard == null) {
      clipboard = new Clipboard(shell.getDisplay());
    }
    clipboard.setContents(new Object[] {value}, new Transfer[] {TextTransfer.getInstance()});
  }

  @Override
  public String clipboardText() {
    if (shell.isDisposed()) {
      return null;
    }
    if (clipboard == null) {
      clipboard = new Clipboard(shell.getDisplay());
    }
    Object value = clipboard.getContents(TextTransfer.getInstance());
    return value instanceof String string ? string : null;
  }

  // Editing prompts and Keyboard Shortcuts.

  @Override
  public void askGoToLine(int lines, Consumer<String> answer) {
    dialogs.askGoToLine(lines, answer);
  }

  @Override
  public void askTabWidth(int current, int max, IntConsumer answer) {
    dialogs.askTabWidth(current, max, answer);
  }

  @Override
  public void askReload(String name, Runnable reload, Runnable declined) {
    dialogs.askReload(name, reload, declined);
  }

  @Override
  public void showKeyboardShortcuts(Keymap bindings) {
    dialogs.showKeyboardShortcuts(bindings);
  }

  /**
   * Returns the Keyboard Shortcuts window.
   *
   * @return the window, or null before it opened
   */
  ShortcutsWindow shortcutsWindow() {
    return dialogs.shortcutsWindow();
  }

  // Preferences.

  @Override
  public void showPreferences(PreferencesInput input) {
    dialogs.showPreferences(input);
  }

  /**
   * Returns the Preferences dialog.
   *
   * @return the dialog, or null before it opened
   */
  PreferencesDialog preferencesDialog() {
    return dialogs.preferencesDialog();
  }

  @Override
  public void setKeymap(Keymap bindings) {
    if (shell.isDisposed()) {
      return;
    }
    keymap = bindings;
    menuBar.build(bindings);
    mainToolBar.setKeymap(bindings);
    tabButtons.setKeymap(bindings);
    editorMenu.setKeymap(bindings);
    for (EditorPane pane : allPanes()) {
      pane.setKeymap(bindings);
    }
    dialogs.setKeymap(bindings);
  }

  // Command palette.

  @Override
  public void showCommandPalette(PaletteInput input) {
    dialogs.showCommandPalette(input);
  }

  /**
   * Returns the command palette.
   *
   * @return the palette, or null before it opened
   */
  CommandPaletteWindow commandPalette() {
    return dialogs.commandPalette();
  }

  // Workspace browser and Quick Open.

  @Override
  public void showWorkspace(WorkspaceInput input, boolean shown) {
    if (shell.isDisposed() || workspaceDock == null) {
      return;
    }
    workspaceDock.setInput(input);
    side.setMaximizedControl(shown ? null : body);
    browserWidth.fit();
    side.layout(true);
    if (shown) {
      workspaceDock.reink();
    }
  }

  @Override
  public void workspaceChanged(WorkspaceNode directory) {
    if (workspaceDock != null && !shell.isDisposed()) {
      workspaceDock.changed(directory);
    }
  }

  @Override
  public int[] sideWeights() {
    return side.getWeights();
  }

  @Override
  public void setSideWeights(int[] weights) {
    if (browserWidth == null) {
      side.setWeights(weights);
    } else {
      browserWidth.restore(weights);
    }
  }

  @Override
  public int[] dockWeights() {
    return body.getWeights();
  }

  @Override
  public void setDockWeights(int[] weights) {
    body.setWeights(weights);
  }

  @Override
  public int[] resultsWeights() {
    return docks.getWeights();
  }

  @Override
  public void setResultsWeights(int[] weights) {
    docks.setWeights(weights);
  }

  @Override
  public List<WorkspaceNode> expandedWorkspace() {
    return workspaceDock == null ? List.of() : workspaceDock.expanded();
  }

  @Override
  public void expandWorkspace(WorkspaceNode directory) {
    if (workspaceDock != null && !shell.isDisposed()) {
      workspaceDock.expand(directory);
    }
  }

  @Override
  public void showRecovery(RecoveryInput input) {
    dialogs.showRecovery(input);
  }

  @Override
  public void recoveryChanged() {
    dialogs.recoveryChanged();
  }

  /**
   * Returns the recovery dialog.
   *
   * @return the dialog last opened, or null
   */
  RecoveryDialog recoveryDialog() {
    return dialogs.recoveryDialog();
  }

  @Override
  public void revealWorkspace(List<WorkspaceNode> chain) {
    if (workspaceDock != null && !shell.isDisposed()) {
      workspaceDock.reveal(chain);
    }
  }

  @Override
  public void showQuickOpen(QuickOpenInput input) {
    dialogs.showQuickOpen(input);
  }

  @Override
  public void quickOpenChanged() {
    dialogs.quickOpenChanged();
  }

  @Override
  public boolean launchesByExtension() {
    return PlatformTraits.PLATFORM.launchesByExtension();
  }

  /**
   * Returns the workspace browser.
   *
   * @return the dock, or null before {@link #attach}
   */
  WorkspaceDock workspaceDock() {
    return workspaceDock;
  }

  /**
   * Reports whether the workspace browser shows.
   *
   * @return true while shown
   */
  boolean workspaceShown() {
    return workspaceDock != null && side.getMaximizedControl() == null;
  }

  /**
   * Returns the Quick Open popup opened last.
   *
   * @return the popup, or null before one opened
   */
  QuickOpenWindow quickOpen() {
    return dialogs.quickOpen();
  }

  // HyperSearch.

  @Override
  public void showSearchDialog(SearchForm form, Consumer<SearchForm> run) {
    dialogs.showSearchDialog(form, run);
  }

  /**
   * Returns the HyperSearch dialog.
   *
   * @return the dialog, or null before it opened
   */
  SearchDialog searchDialog() {
    return dialogs.searchDialog();
  }

  @Override
  public void chooseFolder(Path start, Consumer<Path> chosen) {
    if (shell.isDisposed()) {
      return;
    }
    DirectoryDialog dialog = new DirectoryDialog(shell);
    if (start != null) {
      dialog.setFilterPath(start.toString());
    }
    String folder = dialog.open();
    if (folder != null) {
      chosen.accept(Path.of(folder));
    }
  }

  @Override
  public void showResults(ResultsInput results, boolean shown) {
    if (shell.isDisposed() || resultsDock == null) {
      return;
    }
    resultsDock.setInput(results);
    resultsDock.control().setVisible(shown);
    layoutDocks();
  }

  /** Lays out the shown docks below the tabs; the tabs fill the body while no dock shows. */
  private void layoutDocks() {
    boolean any = resultsDock.control().getVisible() || bookmarksDock.control().getVisible();
    body.setMaximizedControl(any ? null : tabs);
    docks.layout(true);
  }

  @Override
  public void showBookmarks(BookmarksInput input, boolean shown) {
    if (shell.isDisposed() || bookmarksDock == null) {
      return;
    }
    bookmarksDock.setInput(input);
    bookmarksDock.control().setVisible(shown);
    layoutDocks();
  }

  @Override
  public void bookmarksChanged() {
    if (bookmarksDock != null && !shell.isDisposed()) {
      bookmarksDock.refresh();
    }
  }

  @Override
  public void askBookmarkComment(int line, String current, int maxLength, Consumer<String> answer) {
    dialogs.askBookmarkComment(line, current, maxLength, answer);
  }

  @Override
  public void askFileName(
      String title, String message, String name, int selectEnd, Consumer<String> answer) {
    dialogs.askFileName(title, message, name, selectEnd, answer);
  }

  @Override
  public void askDelete(String message, Runnable delete) {
    dialogs.askDelete(message, delete);
  }

  @Override
  public void askOpenMany(int count, Runnable open) {
    dialogs.askOpenMany(count, open);
  }

  /**
   * Returns the last Open in Default Application prompt for many files.
   *
   * @return the dialog, or null before one opened
   */
  OpenManyDialog openManyDialog() {
    return dialogs.openManyDialog();
  }

  @Override
  public void resultsChanged(boolean structure) {
    if (resultsDock != null && !shell.isDisposed()) {
      resultsDock.refresh(structure);
    }
  }

  @Override
  public void revealResult(int set, int file, int match) {
    if (resultsDock != null && !shell.isDisposed()) {
      resultsDock.reveal(set, file, match);
    }
  }

  @Override
  public void showReplacePreview(ReplaceInput input) {
    dialogs.showReplacePreview(input, palette, fontName);
  }

  @Override
  public void replacePreviewChanged() {
    dialogs.replacePreviewChanged();
  }

  /**
   * Returns the replace preview.
   *
   * @return the dialog, or null before it opened
   */
  ReplaceDialog replaceDialog() {
    return dialogs.replaceDialog();
  }

  @Override
  public void showReplaceReport(ReportInput input) {
    dialogs.showReplaceReport(input);
  }

  @Override
  public void replaceReportChanged(ReportInput input) {
    dialogs.replaceReportChanged(input);
  }

  /**
   * Returns the open report windows, oldest first.
   *
   * @return the windows
   */
  List<ReportWindow> reportWindows() {
    return dialogs.reportWindows();
  }

  @Override
  public void chooseReplaceRun(List<String> runs, Consumer<Integer> chosen) {
    dialogs.chooseReplaceRun(runs, chosen);
  }

  /**
   * Returns the results dock.
   *
   * @return the dock, or null before {@link #attach}
   */
  ResultsDock resultsDock() {
    return resultsDock;
  }

  /**
   * Reports whether the results dock shows.
   *
   * @return true while shown
   */
  boolean resultsShown() {
    return resultsDock.control().getVisible() && body.getMaximizedControl() == null;
  }

  /**
   * Returns the Bookmarks list dock.
   *
   * @return the dock, or null before {@link #attach}
   */
  BookmarksDock bookmarksDock() {
    return bookmarksDock;
  }

  /**
   * Returns the bookmark comment prompt opened last.
   *
   * @return the dialog, or null before one opened
   */
  BookmarkCommentDialog commentDialog() {
    return dialogs.commentDialog();
  }

  /**
   * Returns the name dialog opened last.
   *
   * @return the dialog, or null before one opened
   */
  FileNameDialog fileNameDialog() {
    return dialogs.fileNameDialog();
  }

  /**
   * Returns the delete prompt opened last.
   *
   * @return the dialog, or null before one opened
   */
  DeleteDialog deleteDialog() {
    return dialogs.deleteDialog();
  }

  /**
   * Reports whether the Bookmarks list shows.
   *
   * @return true while shown
   */
  boolean bookmarksShown() {
    return bookmarksDock.control().getVisible() && body.getMaximizedControl() == null;
  }

  @Override
  public void askExternalChange(String name, boolean saving, Consumer<ChangeChoice> answer) {
    dialogs.askExternalChange(name, saving, answer);
  }

  @Override
  public void chooseDocument(List<String> names, Consumer<Integer> chosen) {
    dialogs.chooseDocument(names, chosen);
  }

  @Override
  public void showComparison(Comparison comparison) {
    dialogs.showComparison(comparison, palette, font);
  }

  /**
   * Returns the newest external-change prompt.
   *
   * @return the dialog, or null before one opened
   */
  ChangeDialog changeDialog() {
    return dialogs.changeDialog();
  }

  /**
   * Returns the newest Compare Documents chooser.
   *
   * @return the dialog, or null before one opened
   */
  DocumentChooser documentChooser() {
    return dialogs.documentChooser();
  }

  /**
   * Returns the open comparison windows, oldest first.
   *
   * @return the windows
   */
  List<DiffWindow> diffWindows() {
    return dialogs.diffWindows();
  }

  @Override
  public boolean onTopSupported() {
    return WindowStacking.PLATFORM.supported();
  }

  @Override
  public void setOnTop(boolean onTop) {
    WindowStacking.PLATFORM.setOnTop(shell, onTop);
  }

  @Override
  public TipDisplay showTips(TipInput input) {
    return dialogs.showTips(input);
  }

  /**
   * Returns the newest Tip of the Day dialog.
   *
   * @return the dialog, or null before one opened
   */
  TipDialog tipDialog() {
    return dialogs.tipDialog();
  }

  @Override
  public boolean promptShown() {
    return !shell.isDisposed() && modalOwner() != shell;
  }

  @Override
  public Runnable askUnfinishedCopies(List<String> targets, Runnable quit, Runnable wait) {
    return dialogs.askUnfinishedCopies(targets, quit, wait);
  }
}
