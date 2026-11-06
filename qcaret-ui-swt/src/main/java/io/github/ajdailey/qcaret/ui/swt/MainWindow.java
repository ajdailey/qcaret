package io.github.ajdailey.qcaret.ui.swt;

import io.github.ajdailey.qcaret.app.about.AboutInfo;
import io.github.ajdailey.qcaret.app.command.CommandId;
import io.github.ajdailey.qcaret.app.command.KeyStroke;
import io.github.ajdailey.qcaret.app.command.Keymap;
import io.github.ajdailey.qcaret.app.command.MenuTable;
import io.github.ajdailey.qcaret.app.controller.ApplicationController;
import io.github.ajdailey.qcaret.app.controller.CommandStates;
import io.github.ajdailey.qcaret.app.controller.EncodingChoices;
import io.github.ajdailey.qcaret.app.editor.Bookmarks;
import io.github.ajdailey.qcaret.app.editor.DisplayProjection;
import io.github.ajdailey.qcaret.app.editor.OverviewMarks;
import io.github.ajdailey.qcaret.app.editor.TokenSource;
import io.github.ajdailey.qcaret.app.i18n.UiText;
import io.github.ajdailey.qcaret.app.theme.Palette;
import io.github.ajdailey.qcaret.app.theme.PaletteListener;
import io.github.ajdailey.qcaret.app.view.BookmarksInput;
import io.github.ajdailey.qcaret.app.view.BusyCounter;
import io.github.ajdailey.qcaret.app.view.ChangeChoice;
import io.github.ajdailey.qcaret.app.view.Comparison;
import io.github.ajdailey.qcaret.app.view.DialogSize;
import io.github.ajdailey.qcaret.app.view.EditorInput;
import io.github.ajdailey.qcaret.app.view.EditorView;
import io.github.ajdailey.qcaret.app.view.MainView;
import io.github.ajdailey.qcaret.app.view.PaletteInput;
import io.github.ajdailey.qcaret.app.view.PreferencesInput;
import io.github.ajdailey.qcaret.app.view.QuickOpenInput;
import io.github.ajdailey.qcaret.app.view.RecoveryInput;
import io.github.ajdailey.qcaret.app.view.ReplaceInput;
import io.github.ajdailey.qcaret.app.view.ReportInput;
import io.github.ajdailey.qcaret.app.view.ResultsInput;
import io.github.ajdailey.qcaret.app.view.SearchForm;
import io.github.ajdailey.qcaret.app.view.StatusFields;
import io.github.ajdailey.qcaret.app.view.TabInput;
import io.github.ajdailey.qcaret.app.view.TabView;
import io.github.ajdailey.qcaret.app.view.UnsavedChoice;
import io.github.ajdailey.qcaret.app.view.ViewLayout;
import io.github.ajdailey.qcaret.app.view.WindowGeometry;
import io.github.ajdailey.qcaret.app.view.WorkspaceInput;
import io.github.ajdailey.qcaret.app.view.WorkspaceNode;
import io.github.ajdailey.qcaret.core.document.SelectionListener;
import io.github.ajdailey.qcaret.core.document.SelectionModel;
import io.github.ajdailey.qcaret.ui.swt.editor.EditorPane;
import io.github.ajdailey.qcaret.ui.swt.editor.SwtKeys;
import io.github.ajdailey.qcaret.ui.swt.search.ReplaceDialog;
import io.github.ajdailey.qcaret.ui.swt.search.ReportWindow;
import io.github.ajdailey.qcaret.ui.swt.search.ResultsDock;
import io.github.ajdailey.qcaret.ui.swt.search.RunChooser;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.function.Consumer;
import org.eclipse.swt.SWT;
import org.eclipse.swt.custom.CTabFolder;
import org.eclipse.swt.custom.CTabFolder2Adapter;
import org.eclipse.swt.custom.CTabFolderEvent;
import org.eclipse.swt.custom.CTabItem;
import org.eclipse.swt.custom.SashForm;
import org.eclipse.swt.custom.StyledText;
import org.eclipse.swt.dnd.Clipboard;
import org.eclipse.swt.dnd.TextTransfer;
import org.eclipse.swt.dnd.Transfer;
import org.eclipse.swt.graphics.Color;
import org.eclipse.swt.graphics.Font;
import org.eclipse.swt.graphics.Image;
import org.eclipse.swt.graphics.Point;
import org.eclipse.swt.graphics.Rectangle;
import org.eclipse.swt.layout.GridData;
import org.eclipse.swt.layout.GridLayout;
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
import org.eclipse.swt.widgets.MenuItem;
import org.eclipse.swt.widgets.Monitor;
import org.eclipse.swt.widgets.Shell;

/**
 * The main window: a tab per document, each holding one {@link EditorPane} or two in a {@link
 * SashForm}, over the {@link ResultsDock}, hidden until a HyperSearch shows it, beside the {@link
 * BookmarksDock}, hidden until Bookmarks List shows it, with the {@link WorkspaceDock} on the left,
 * hidden until View > Workspace Browser shows it; the {@link MainMenuBar} built from {@link
 * MenuTable} and the {@link Keymap}, the {@link FindBar} and a status bar. The editors take their
 * colors from the {@link Palette}; native controls follow {@link SwtThemeService}. {@link
 * ToolIcons} owned by the window re-ink on the UI turn after a palette change, once native controls
 * have restyled.
 *
 * <p>Tab close buttons and a middle click ask the controller to close; dragging a tab within the
 * strip moves it, and a release off the strip moves it to another window ({@link #dropTab}). A
 * tab's item cannot change position, so a move disposes and recreates it, and the {@code Selection}
 * that {@code CTabFolder} sends for a neighbor while the selected item is disposed is ignored
 * (QLogg UI-14). One context menu serves every tab; each right click replaces its items. The file
 * choosers are native dialogs and run a nested event loop; the unsaved-changes and binary-file
 * prompts do not.
 *
 * <p>Menu enablement syncs on {@link #documentStateChanged} and when the shell is activated, so an
 * accelerator works without a menu having been shown. The status bar shows the selected document's
 * {@link StatusFields}, read once per UI turn however many caret moves or state changes arrive in
 * it; a notice; and a Warnings link to Show Log while a warning is unseen. A Display key filter
 * runs window bindings that are not accelerators. {@link #setFontSize} replaces the editor font in
 * every editor in place, without rebuilding the menus or the window, and keeps the focus.
 *
 * <p>{@link #setActive} and {@link #setIdle} count overlapping operations through a {@link
 * BusyCounter}: while any is active the shell shows {@code SWT.CURSOR_APPSTARTING}, which keeps the
 * window usable.
 *
 * <p>{@link #place} sizes and positions the shell before {@link #open}; the normal bounds are kept
 * on every unmaximized move and resize for {@link #windowBounds}. Dialogs open without a nested
 * event loop; a text window opened over a modal dialog is modal and owned by it, because GTK gives
 * input only to the newest modal shell.
 */
public final class MainWindow implements MainView {
  /** System property SWT sets on GTK to {@code x11}, {@code wayland} or {@code unknown}. */
  static final String GDK_BACKEND = "org.eclipse.swt.internal.gdk.backend";

  /** System property SWT sets on GTK to the GTK version. */
  static final String GTK_VERSION = "org.eclipse.swt.internal.gtk.version";

  /** How long a notice stays in the status bar. */
  static final int NOTICE_MILLIS = 8000;

  /** Shell data naming the shell's main window, for a tab dropped on it. */
  private static final String WINDOW = "qcaret.window";

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
  private final SwtUiDispatcher dispatcher;
  private final Composite statusBar;
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
  private Font font;
  private ApplicationController controller;
  private MainMenuBar menuBar;
  private Keymap keymap;
  private PreferencesDialog preferencesDialog;
  private Menu tabMenu;
  private TextWindow logWindow;
  private ShortcutsWindow shortcuts;
  private CommandPaletteWindow commandPalette;
  private boolean statusPending;
  private long noticeGeneration;
  private PaletteListener paletteListener;
  private Palette palette;
  private Clipboard clipboard;
  private WindowGeometry.Request normalBounds = new WindowGeometry.Request(null, null, null, null);
  private CTabItem dragged;
  private boolean movingTab;
  private PointerProbe pointer =
      new PointerProbe() {
        @Override
        public Control controlAt(Point point) {
          return shell.getDisplay().getCursorControl();
        }
      };
  private FindBar findBar;
  private final SashForm body;
  private final SashForm side;
  private WorkspaceDock workspaceDock;
  private QuickOpenWindow quickOpen;
  private SashForm docks;
  private ResultsDock resultsDock;
  private BookmarksDock bookmarksDock;
  private BookmarkCommentDialog commentDialog;
  private SearchDialog searchDialog;
  private ReplaceDialog replaceDialog;
  private final List<ReportWindow> reportWindows = new ArrayList<ReportWindow>();
  private RunChooser runChooser;
  private ChangeDialog changeDialog;
  private RecoveryDialog recoveryDialog;
  private DocumentChooser documentChooser;
  private final List<DiffWindow> diffWindows = new ArrayList<DiffWindow>();

  /**
   * One document tab: its item, which a move recreates, and one or two editor views in a {@link
   * SashForm}, one above the other or side by side.
   */
  private final class Tab implements TabView, SelectionListener {
    final SashForm sash;
    final List<EditorPane> panes = new ArrayList<EditorPane>(2);
    final List<View> views = new ArrayList<View>(2);
    final List<SelectionModel> models = new ArrayList<SelectionModel>(2);
    final TabInput input;
    EditorPane pane;
    String title = "";
    String toolTip = "";
    CTabItem item;

    /** A view as its controller sees it; focusing it makes it the tab's focused view. */
    private final class View implements EditorView {
      final EditorPane editor;

      View(EditorPane editor) {
        this.editor = editor;
      }

      @Override
      public void showCaret() {
        editor.showCaret();
      }

      @Override
      public int topRow() {
        return editor.topRow();
      }

      @Override
      public void scrollToRow(int row) {
        editor.scrollToRow(row);
      }

      @Override
      public void focus() {
        pane = editor;
        editor.focus();
      }

      @Override
      public void showMatches(int[] ranges) {
        editor.showMatches(ranges);
      }

      @Override
      public ViewLayout layout() {
        return editor.layout();
      }

      @Override
      public void setTabWidth(int columns) {
        editor.setTabWidth(columns);
      }

      @Override
      public void setColumnMode(boolean on) {
        editor.setColumnMode(on);
      }

      @Override
      public void setSyntax(TokenSource tokens) {
        editor.setSyntax(tokens);
      }

      @Override
      public void redrawHighlights() {
        editor.redrawHighlights();
      }

      @Override
      public void showBrackets(int[] ranges) {
        editor.showBrackets(ranges);
      }

      @Override
      public void showOccurrences(int[] ranges) {
        editor.showOccurrences(ranges);
      }

      @Override
      public void setBookmarks(Bookmarks bookmarks) {
        editor.setBookmarks(bookmarks);
      }

      @Override
      public void setMarks(boolean whitespace, boolean lineEndings) {
        editor.setMarks(whitespace, lineEndings);
      }

      @Override
      public void setBusy(boolean busy) {
        editor.setBusy(busy);
      }

      @Override
      public void showCompletions(List<String> words, int selected) {
        editor.showCompletions(words, selected);
      }

      @Override
      public void showOverview(OverviewMarks marks) {
        editor.showOverview(marks);
      }

      @Override
      public void setOverviewShown(boolean shown) {
        editor.setOverviewShown(shown);
      }
    }

    Tab(SashForm sash, TabInput input, int index) {
      this.sash = sash;
      this.input = input;
      this.item = newItem(this, index);
    }

    /** Adds a view; its focus selects it as the tab's focused view. */
    EditorPane add(
        DisplayProjection projection, SelectionModel selections, final EditorInput editor) {
      final EditorPane added =
          new EditorPane(sash, projection, selections, editor, keymap, font, palette, dispatcher);
      added
          .text()
          .addListener(
              SWT.FocusIn,
              new Listener() {
                @Override
                public void handleEvent(Event event) {
                  if (pane != added && panes.contains(added)) {
                    pane = added;
                    input.viewFocused(editor);
                  }
                }
              });
      panes.add(added);
      views.add(new View(added));
      models.add(selections);
      selections.addListener(this);
      if (pane == null) {
        pane = added;
      }
      return added;
    }

    @Override
    public void selectionsChanged(SelectionModel model) {
      if (!item.isDisposed() && tabs.getSelection() == item) {
        scheduleStatus();
      }
    }

    @Override
    public void setTitle(String value, String tip) {
      title = value;
      toolTip = tip;
      if (!item.isDisposed()) {
        item.setText(value.replace("&", "&&"));
        item.setToolTipText(tip);
      }
    }

    @Override
    public void select() {
      if (!item.isDisposed()) {
        tabs.setSelection(item);
        pane.focus();
        input.activated();
      }
    }

    @Override
    public void showCaret() {
      pane.showCaret();
    }

    @Override
    public void close() {
      for (SelectionModel model : models) {
        model.removeListener(this);
      }
      if (!item.isDisposed()) {
        item.dispose();
      }
      for (EditorPane p : panes) {
        p.dispose();
      }
      if (!sash.isDisposed()) {
        sash.dispose();
      }
      CTabItem next = tabs.getSelection();
      if (next != null && next.getData() instanceof Tab shown) {
        shown.pane.focus();
      }
    }

    @Override
    public EditorView firstView() {
      return views.get(0);
    }

    @Override
    public EditorView split(
        DisplayProjection projection,
        SelectionModel selections,
        EditorInput editor,
        boolean sideBySide) {
      sash.setOrientation(sideBySide ? SWT.HORIZONTAL : SWT.VERTICAL);
      add(projection, selections, editor);
      sash.setWeights(new int[] {1, 1});
      sash.layout(true);
      View added = views.get(views.size() - 1);
      added.focus();
      return added;
    }

    @Override
    public void setSideBySide(boolean sideBySide) {
      sash.setOrientation(sideBySide ? SWT.HORIZONTAL : SWT.VERTICAL);
    }

    @Override
    public int[] splitWeights() {
      return sash.getWeights();
    }

    @Override
    public void setSplitWeights(int[] weights) {
      sash.setWeights(weights);
      sash.layout(true);
    }

    @Override
    public void unsplit(EditorView kept) {
      for (int i = panes.size() - 1; i >= 0; i--) {
        if (views.get(i) != kept) {
          models.remove(i).removeListener(this);
          views.remove(i);
          panes.remove(i).dispose();
        }
      }
      pane = panes.get(0);
      sash.layout(true);
      pane.focus();
    }
  }

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
    shell.setData(WINDOW, this);
    shell.setText(UiText.WINDOW_TITLE.text());
    icons = Icons.load(display, Icons.Variant.DARK);
    shell.setImages(icons.toArray(new Image[0]));
    GridLayout layout = new GridLayout(1, false);
    layout.marginWidth = 0;
    layout.marginHeight = 0;
    layout.verticalSpacing = 0;
    shell.setLayout(layout);
    side = new SashForm(shell, SWT.HORIZONTAL);
    side.setLayoutData(new GridData(SWT.FILL, SWT.FILL, true, true));
    body = new SashForm(side, SWT.VERTICAL);
    tabs = new CTabFolder(body, SWT.TOP | SWT.FLAT);
    baseFontSize = Dialogs.monospaceSize();
    font = new Font(display, Dialogs.monospaceName(), baseFontSize, SWT.NORMAL);
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
    toolIcons = new ToolIcons(shell);
    applyPalette(palette);
    trackWindowState();
    shell.addListener(
        SWT.Dispose,
        new Listener() {
          @Override
          public void handleEvent(Event event) {
            if (controller != null) {
              controller.theme().removeListener(paletteListener);
            }
            font.dispose();
            if (tabMenu != null) {
              tabMenu.dispose();
            }
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
    menuBar = new MainMenuBar(shell, owner);
    menuBar.build(keymap);
    int fontSize = owner.fontSize();
    if (fontSize != 0) {
      setFontSize(fontSize);
    }
    owner.loadEncodings(
        new EncodingChoices.Listener() {
          @Override
          public void loaded(EncodingChoices choices) {
            if (!shell.isDisposed()) {
              menuBar.encodingsLoaded(choices);
            }
          }
        });
    listenToTabs();
    dispatchWindowKeys();
    findBar = new FindBar(shell, owner.find());
    findBar.control().moveAbove(statusBar);
    docks = new SashForm(body, SWT.HORIZONTAL);
    resultsDock = new ResultsDock(docks, palette);
    bookmarksDock = new BookmarksDock(docks);
    resultsDock.control().setVisible(false);
    bookmarksDock.control().setVisible(false);
    docks.setWeights(new int[] {3, 2});
    body.setWeights(new int[] {3, 1});
    body.setMaximizedControl(tabs);
    workspaceDock = new WorkspaceDock(side);
    workspaceDock.control().moveAbove(body);
    side.setWeights(new int[] {1, 3});
    side.setMaximizedControl(body);
    shell.addListener(
        SWT.Activate,
        new Listener() {
          @Override
          public void handleEvent(Event event) {
            menuBar.syncEnablement();
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

  private Label statusLabel() {
    Label label = new Label(statusBar, SWT.NONE);
    label.setLayoutData(new GridData(SWT.END, SWT.CENTER, false, false));
    return label;
  }

  private void applyPalette(Palette next) {
    palette = next;
    if (resultsDock != null) {
      resultsDock.setPalette(next);
    }
    for (DiffWindow window : diffWindows) {
      window.setPalette(next);
    }
    for (CTabItem item : tabs.getItems()) {
      if (item.getData() instanceof Tab tab) {
        for (EditorPane pane : tab.panes) {
          pane.setPalette(next);
        }
      }
    }
    // Native controls restyle after the theme call returns; re-ink the icons on the next turn.
    dispatcher.asyncExec(
        new Runnable() {
          @Override
          public void run() {
            if (!shell.isDisposed()) {
              toolIcons.reink();
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
    CTabItem item = tabs.getSelection();
    return item != null && item.getData() instanceof Tab tab ? tab.pane : null;
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
    Tab tab =
        new Tab(new SashForm(tabs, SWT.VERTICAL), input, Math.min(index, tabs.getItemCount()));
    EditorPane pane = tab.add(projection, selections, editor);
    tabs.setSelection(tab.item);
    pane.focus();
    return tab;
  }

  @Override
  public void documentStateChanged() {
    if (menuBar != null && !shell.isDisposed()) {
      menuBar.syncEnablement();
    }
    scheduleStatus();
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

  private void showStatus() {
    if (shell.isDisposed() || menuBar == null) {
      return;
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

  private CTabItem newItem(Tab tab, int index) {
    CTabItem item = new CTabItem(tabs, SWT.CLOSE, index);
    item.setControl(tab.sash);
    item.setData(tab);
    item.setText(tab.title.replace("&", "&&"));
    item.setToolTipText(tab.toolTip);
    return item;
  }

  /** Tab selection, close buttons, middle clicks and drags go to the controller. */
  private void listenToTabs() {
    tabs.addListener(
        SWT.Selection,
        new Listener() {
          @Override
          public void handleEvent(Event event) {
            if (!movingTab && event.item != null && event.item.getData() instanceof Tab tab) {
              tab.pane.focus();
              tab.input.activated();
              scheduleStatus();
            }
          }
        });
    tabs.addCTabFolder2Listener(
        new CTabFolder2Adapter() {
          @Override
          public void close(CTabFolderEvent event) {
            event.doit = false;
            if (event.item.getData() instanceof Tab tab) {
              tab.input.closeRequested();
            }
          }
        });
    tabs.addListener(
        SWT.MouseDown,
        new Listener() {
          @Override
          public void handleEvent(Event event) {
            dragged = event.button == 1 ? tabs.getItem(new Point(event.x, event.y)) : null;
          }
        });
    tabs.addListener(
        SWT.MenuDetect,
        new Listener() {
          @Override
          public void handleEvent(Event event) {
            CTabItem item = tabs.getItem(tabs.toControl(event.x, event.y));
            if (item != null && item.getData() instanceof Tab tab) {
              event.doit = false;
              showTabMenu(tab, event.x, event.y);
            }
          }
        });
    tabs.addListener(
        SWT.MouseUp,
        new Listener() {
          @Override
          public void handleEvent(Event event) {
            CTabItem target = tabs.getItem(new Point(event.x, event.y));
            if (event.button == 2) {
              if (target != null && target.getData() instanceof Tab tab) {
                tab.input.closeRequested();
              }
              return;
            }
            CTabItem source = dragged;
            dragged = null;
            if (source == null || source.isDisposed()) {
              return;
            }
            if (target == null) {
              Point point = tabs.toDisplay(event.x, event.y);
              dropTab((Tab) source.getData(), point, pointer.controlAt(point));
            } else if (target != source) {
              moveTab(source, tabs.indexOf(target));
            }
          }
        });
  }

  /**
   * Sets how a released tab drag finds the control under it; for tests.
   *
   * @param probe the probe
   */
  void setPointerProbe(PointerProbe probe) {
    pointer = probe;
  }

  /**
   * Ends a tab drag released off this window's tabs. Over another main window the tab moves there:
   * before the tab under the point, else after the last tab. Outside every QCaret shell and outside
   * this window's bounds it moves to a new window with its top-left corner at the point. A release
   * anywhere else does nothing.
   */
  private void dropTab(Tab tab, Point point, Control under) {
    if (under == null) {
      if (!shell.getBounds().contains(point)) {
        controller.moveTabToNewWindow(tab.input, point.x, point.y);
      }
      return;
    }
    Shell other = under.getShell();
    if (other == shell
        || !(other.getData(WINDOW) instanceof MainWindow target)
        || target.controller == null) {
      return;
    }
    int index = target.tabs.getItemCount();
    CTabItem item = target.tabs.getItem(target.tabs.toControl(point));
    if (item != null) {
      index = target.tabs.indexOf(item);
    }
    controller.moveTab(tab.input, target.controller, index);
  }

  /**
   * Opens a tab's context menu: Close, Close Others, Close All, then the path items. One menu
   * serves every tab; each call replaces its items.
   *
   * @param tab the tab
   * @param x display x
   * @param y display y
   * @return the menu, already visible
   */
  Menu showTabMenu(final Tab tab, int x, int y) {
    if (tabMenu == null || tabMenu.isDisposed()) {
      tabMenu = new Menu(shell, SWT.POP_UP);
    }
    for (MenuItem old : tabMenu.getItems()) {
      old.dispose();
    }
    CommandStates.TabItems state = menuBar.states().tabItems(tab.input);
    tabMenuItem(
        UiText.COMMAND_CLOSE,
        true,
        new Runnable() {
          @Override
          public void run() {
            tab.input.closeRequested();
          }
        });
    tabMenuItem(
        UiText.COMMAND_CLOSE_OTHERS,
        state.closeOthers(),
        new Runnable() {
          @Override
          public void run() {
            tab.input.closeOthersRequested();
          }
        });
    tabMenuItem(
        UiText.COMMAND_CLOSE_ALL,
        true,
        new Runnable() {
          @Override
          public void run() {
            controller.execute(CommandId.CLOSE_ALL);
          }
        });
    new MenuItem(tabMenu, SWT.SEPARATOR);
    tabMenuItem(
        UiText.COMMAND_MOVE_TO_NEW_WINDOW,
        true,
        new Runnable() {
          @Override
          public void run() {
            controller.moveTabToNewWindow(tab.input, null, null);
          }
        });
    new MenuItem(tabMenu, SWT.SEPARATOR);
    tabMenuItem(
        UiText.TAB_COPY_PATH,
        state.file(),
        new Runnable() {
          @Override
          public void run() {
            tab.input.copyPathRequested(false);
          }
        });
    tabMenuItem(
        UiText.TAB_COPY_FILE_NAME,
        state.file(),
        new Runnable() {
          @Override
          public void run() {
            tab.input.copyPathRequested(true);
          }
        });
    tabMenu.setLocation(x, y);
    tabMenu.setVisible(true);
    return tabMenu;
  }

  private void tabMenuItem(UiText label, boolean enabled, final Runnable action) {
    MenuItem item = new MenuItem(tabMenu, SWT.PUSH);
    item.setText(label.text());
    item.setEnabled(enabled);
    item.addListener(
        SWT.Selection,
        new Listener() {
          @Override
          public void handleEvent(Event event) {
            action.run();
          }
        });
  }

  /**
   * Opens the context menu of the tab at an index; for tests.
   *
   * @param index the tab index
   * @return the menu
   */
  Menu showTabMenu(int index) {
    Point at = tabs.toDisplay(0, 0);
    return showTabMenu((Tab) tabs.getItem(index).getData(), at.x, at.y);
  }

  /**
   * Recreates a tab at another index. Disposing the selected item makes {@code CTabFolder} select a
   * neighbor and send {@code Selection}; the listener ignores it while {@link #movingTab} is set.
   *
   * @param source the dragged item
   * @param index the drop index
   */
  void moveTab(CTabItem source, int index) {
    Tab tab = (Tab) source.getData();
    boolean selected = tabs.getSelection() == source;
    movingTab = true;
    try {
      source.dispose();
      tab.item = newItem(tab, index);
      if (selected) {
        tabs.setSelection(tab.item);
      }
    } finally {
      movingTab = false;
    }
    tab.input.moved(index);
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
      return;
    }
    FileDialog dialog = new FileDialog(shell, SWT.SAVE);
    dialog.setOverwrite(true);
    dialog.setFileName(name);
    if (directory != null) {
      dialog.setFilterPath(directory.toString());
    }
    String chosenPath = dialog.open();
    if (chosenPath != null) {
      chosen.accept(Path.of(chosenPath));
    }
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
  public void askReplaceOpenFile(String name, Runnable replace) {
    if (!shell.isDisposed()) {
      new ReplaceOpenDialog(shell, name, replace).open();
    }
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
      logWindow = new TextWindow(owner, UiText.SHOW_LOG_TITLE.text(), text, this, owner != shell);
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

  @Override
  public int defaultFontSize() {
    return baseFontSize;
  }

  @Override
  public void setFontSize(int points) {
    if (shell.isDisposed()) {
      return;
    }
    Font old = font;
    font =
        new Font(
            shell.getDisplay(),
            Dialogs.monospaceName(),
            points == 0 ? baseFontSize : points,
            SWT.NORMAL);
    for (CTabItem item : tabs.getItems()) {
      if (item.getData() instanceof Tab tab) {
        for (EditorPane pane : tab.panes) {
          pane.setFont(font);
        }
      }
    }
    old.dispose();
    if (menuBar != null) {
      menuBar.syncEnablement();
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
    CTabItem item = tabs.getSelection();
    return item != null && item.getData() instanceof Tab tab
        ? new ArrayList<EditorPane>(tab.panes)
        : new ArrayList<EditorPane>();
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
    boolean positionSupported =
        !"wayland".equals(System.getProperty(GDK_BACKEND))
            && !"1".equals(System.getenv("SWT_GTK4"));
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
    // On win32, computeTrim overstates the height once a menu bar is set; place again with the
    // trim measured from the sized shell.
    if ("win32".equals(SWT.getPlatform())) {
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
    new TextWindow(owner, title, value, this, owner != shell).open();
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
    new AboutDialog(shell, info, logoVariant(shell.getBackground()), licenses, this).open();
  }

  /**
   * Picks the About logo for a dialog background. Native chrome can differ from the palette (cocoa,
   * win32 before a restart), so the background decides, not the theme setting.
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
        + SWT.getPlatform()
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
    if (!shell.isDisposed()) {
      new GoToDialog(shell, lines, answer).open();
    }
  }

  @Override
  public void showKeyboardShortcuts(Keymap bindings) {
    if (shell.isDisposed()) {
      return;
    }
    if (shortcuts == null || shortcuts.shell().isDisposed()) {
      shortcuts = new ShortcutsWindow(shell, bindings, this);
      shortcuts.open();
      rememberSize(shortcuts.shell(), "shortcuts");
    } else {
      shortcuts.shell().setActive();
    }
  }

  /**
   * Returns the Keyboard Shortcuts window.
   *
   * @return the window, or null before it opened
   */
  ShortcutsWindow shortcutsWindow() {
    return shortcuts;
  }

  // Preferences and dialog sizes.

  @Override
  public void showPreferences(PreferencesInput input) {
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
   * Returns the Preferences dialog.
   *
   * @return the dialog, or null before it opened
   */
  PreferencesDialog preferencesDialog() {
    return preferencesDialog;
  }

  @Override
  public void setKeymap(Keymap bindings) {
    if (shell.isDisposed()) {
      return;
    }
    keymap = bindings;
    menuBar.build(bindings);
    for (CTabItem item : tabs.getItems()) {
      if (item.getData() instanceof Tab tab) {
        for (EditorPane pane : tab.panes) {
          pane.setKeymap(bindings);
        }
      }
    }
    if (shortcuts != null && !shortcuts.shell().isDisposed()) {
      shortcuts.setKeymap(bindings);
    }
  }

  /** Gives an open dialog its remembered size, within the monitor; a new size is kept at close. */
  private void rememberSize(final Shell dialog, final String name) {
    DialogSize size = controller.dialogSize(name);
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
              controller.rememberDialogSize(name, new DialogSize(closed.x, closed.y));
            }
          }
        });
  }

  // Command palette.

  @Override
  public void showCommandPalette(PaletteInput input) {
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

  /**
   * Returns the command palette.
   *
   * @return the palette, or null before it opened
   */
  CommandPaletteWindow commandPalette() {
    return commandPalette;
  }

  // Workspace browser and Quick Open.

  @Override
  public void showWorkspace(WorkspaceInput input, boolean shown) {
    if (shell.isDisposed() || workspaceDock == null) {
      return;
    }
    workspaceDock.setInput(input);
    side.setMaximizedControl(shown ? null : body);
    side.layout(true);
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
    side.setWeights(weights);
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
    if (!shell.isDisposed()) {
      recoveryDialog = new RecoveryDialog(shell, input);
      recoveryDialog.open();
    }
  }

  @Override
  public void recoveryChanged() {
    if (recoveryDialog != null) {
      recoveryDialog.refresh();
    }
  }

  /**
   * Returns the recovery dialog.
   *
   * @return the dialog last opened, or null
   */
  RecoveryDialog recoveryDialog() {
    return recoveryDialog;
  }

  @Override
  public void revealWorkspace(List<WorkspaceNode> chain) {
    if (workspaceDock != null && !shell.isDisposed()) {
      workspaceDock.reveal(chain);
    }
  }

  @Override
  public void showQuickOpen(QuickOpenInput input) {
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

  @Override
  public void quickOpenChanged() {
    if (quickOpen != null) {
      quickOpen.changed();
    }
  }

  @Override
  public boolean launchesByExtension() {
    return "win32".equals(SWT.getPlatform());
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
    return quickOpen;
  }

  // HyperSearch.

  @Override
  public void showSearchDialog(SearchForm form, Consumer<SearchForm> run) {
    if (shell.isDisposed()) {
      return;
    }
    if (searchDialog != null && !searchDialog.shell().isDisposed()) {
      searchDialog.shell().dispose();
    }
    searchDialog = new SearchDialog(shell, form, run);
    searchDialog.open();
  }

  /**
   * Returns the HyperSearch dialog.
   *
   * @return the dialog, or null before it opened
   */
  SearchDialog searchDialog() {
    return searchDialog;
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
    if (!shell.isDisposed()) {
      commentDialog = new BookmarkCommentDialog(shell, line, current, maxLength, answer);
      commentDialog.open();
    }
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
    if (shell.isDisposed()) {
      return;
    }
    if (replaceDialog != null && !replaceDialog.shell().isDisposed()) {
      replaceDialog.shell().dispose();
    }
    replaceDialog =
        new ReplaceDialog(shell, input, palette, Dialogs.monospaceName(), Dialogs.monospaceSize());
    replaceDialog.open();
    rememberSize(replaceDialog.shell(), "replace");
  }

  @Override
  public void replacePreviewChanged() {
    if (replaceDialog != null && !replaceDialog.shell().isDisposed()) {
      replaceDialog.refresh();
    }
  }

  /**
   * Returns the replace preview.
   *
   * @return the dialog, or null before it opened
   */
  ReplaceDialog replaceDialog() {
    return replaceDialog;
  }

  @Override
  public void showReplaceReport(ReportInput input) {
    if (shell.isDisposed()) {
      return;
    }
    ReportWindow window = new ReportWindow(shell, input);
    reportWindows.add(window);
    window.open();
    rememberSize(window.shell(), "report");
  }

  @Override
  public void replaceReportChanged(ReportInput input) {
    for (int i = reportWindows.size() - 1; i >= 0; i--) {
      ReportWindow window = reportWindows.get(i);
      if (window.shell().isDisposed()) {
        reportWindows.remove(i);
      } else if (window.input() == input) {
        window.refresh();
      }
    }
  }

  /**
   * Returns the open report windows, oldest first.
   *
   * @return the windows
   */
  List<ReportWindow> reportWindows() {
    List<ReportWindow> open = new ArrayList<ReportWindow>();
    for (ReportWindow window : reportWindows) {
      if (!window.shell().isDisposed()) {
        open.add(window);
      }
    }
    return open;
  }

  @Override
  public void chooseReplaceRun(List<String> runs, Consumer<Integer> chosen) {
    if (!shell.isDisposed()) {
      runChooser = new RunChooser(shell, runs, chosen);
      runChooser.open();
    }
  }

  /**
   * Returns the Restore Replace Run chooser.
   *
   * @return the dialog, or null before it opened
   */
  RunChooser runChooser() {
    return runChooser;
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
    return commentDialog;
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
    if (shell.isDisposed()) {
      answer.accept(saving ? ChangeChoice.CANCEL : ChangeChoice.KEEP);
      return;
    }
    changeDialog = new ChangeDialog(shell, name, saving, answer);
    changeDialog.open();
  }

  @Override
  public void chooseDocument(List<String> names, Consumer<Integer> chosen) {
    if (!shell.isDisposed()) {
      documentChooser = new DocumentChooser(shell, names, chosen);
      documentChooser.open();
    }
  }

  @Override
  public void showComparison(Comparison comparison) {
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

  /**
   * Returns the newest external-change prompt.
   *
   * @return the dialog, or null before one opened
   */
  ChangeDialog changeDialog() {
    return changeDialog;
  }

  /**
   * Returns the newest Compare Documents chooser.
   *
   * @return the dialog, or null before one opened
   */
  DocumentChooser documentChooser() {
    return documentChooser;
  }

  /**
   * Returns the open comparison windows, oldest first.
   *
   * @return the windows
   */
  List<DiffWindow> diffWindows() {
    return new ArrayList<DiffWindow>(diffWindows);
  }
}
