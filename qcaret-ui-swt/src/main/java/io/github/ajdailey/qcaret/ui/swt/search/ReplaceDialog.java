package io.github.ajdailey.qcaret.ui.swt.search;

import io.github.ajdailey.qcaret.app.i18n.UiText;
import io.github.ajdailey.qcaret.app.theme.Palette;
import io.github.ajdailey.qcaret.app.theme.Rgb;
import io.github.ajdailey.qcaret.app.view.PreviewLine;
import io.github.ajdailey.qcaret.app.view.ReplaceInput;
import java.util.List;
import org.eclipse.swt.SWT;
import org.eclipse.swt.custom.SashForm;
import org.eclipse.swt.custom.StyleRange;
import org.eclipse.swt.custom.StyledText;
import org.eclipse.swt.graphics.Color;
import org.eclipse.swt.graphics.Font;
import org.eclipse.swt.graphics.Point;
import org.eclipse.swt.graphics.Rectangle;
import org.eclipse.swt.layout.GridData;
import org.eclipse.swt.layout.GridLayout;
import org.eclipse.swt.layout.RowLayout;
import org.eclipse.swt.widgets.Button;
import org.eclipse.swt.widgets.Composite;
import org.eclipse.swt.widgets.Control;
import org.eclipse.swt.widgets.Event;
import org.eclipse.swt.widgets.Label;
import org.eclipse.swt.widgets.Listener;
import org.eclipse.swt.widgets.Shell;
import org.eclipse.swt.widgets.Table;
import org.eclipse.swt.widgets.TableItem;
import org.eclipse.swt.widgets.Text;

/**
 * The replace preview: the replacement text, the files and the selected file's matches as check
 * lists, the selected file's before and after view, a summary, and Apply. Modal and resizable,
 * opened without a nested event loop. Both lists are virtual tables, so a file with 100,000 matches
 * costs its visible rows. Removed lines show over a red tint of the editor background, added lines
 * over a green one.
 */
public final class ReplaceDialog {
  private static final Rgb RED = Rgb.of(0xe5484d);
  private static final Rgb GREEN = Rgb.of(0x30a46c);

  private final Shell shell;
  private final ReplaceInput input;
  private final Text with;
  private final Table files;
  private final Table matches;
  private final StyledText preview;
  private final Label summary;
  private final Button apply;
  private final Color removed;
  private final Color added;
  private final Color note;
  private final int fontSize;
  private Font font;
  private int shownFile = -1;
  private boolean applied;

  /**
   * Creates the dialog.
   *
   * @param parent the main window
   * @param input the preview's model
   * @param palette the colors the tints derive from
   * @param fontName the monospace font family
   * @param fontSize its size in points
   */
  public ReplaceDialog(
      Shell parent, ReplaceInput input, Palette palette, String fontName, int fontSize) {
    this.input = input;
    removed = color(mix(palette.background(), RED));
    added = color(mix(palette.background(), GREEN));
    note = color(palette.lineNumbers());
    shell = new Shell(parent, SWT.SHELL_TRIM | SWT.APPLICATION_MODAL);
    shell.setText(UiText.REPLACE_TITLE.text());
    GridLayout layout = new GridLayout(2, false);
    layout.marginWidth = 12;
    layout.marginHeight = 12;
    layout.verticalSpacing = 8;
    shell.setLayout(layout);
    Label header = new Label(shell, SWT.NONE);
    header.setText(input.title().replace("&", "&&"));
    header.setLayoutData(new GridData(SWT.FILL, SWT.CENTER, true, false, 2, 1));
    Label withLabel = new Label(shell, SWT.NONE);
    withLabel.setText(UiText.REPLACE_WITH_LABEL.text());
    with = new Text(shell, SWT.SINGLE | SWT.BORDER);
    with.setText(input.replacement());
    with.setLayoutData(new GridData(SWT.FILL, SWT.CENTER, true, false));
    with.addListener(
        SWT.Modify,
        new Listener() {
          @Override
          public void handleEvent(Event event) {
            ReplaceDialog.this.input.setReplacement(with.getText());
          }
        });
    SashForm sash = new SashForm(shell, SWT.VERTICAL);
    sash.setLayoutData(new GridData(SWT.FILL, SWT.FILL, true, true, 2, 1));
    SashForm lists = new SashForm(sash, SWT.HORIZONTAL);
    files = checkList(lists, UiText.REPLACE_FILES_LABEL);
    matches = checkList(lists, UiText.REPLACE_MATCHES_LABEL);
    lists.setWeights(new int[] {1, 1});
    Composite lower = column(sash, UiText.REPLACE_PREVIEW_LABEL);
    preview =
        new StyledText(lower, SWT.MULTI | SWT.READ_ONLY | SWT.BORDER | SWT.V_SCROLL | SWT.H_SCROLL);
    preview.setLayoutData(new GridData(SWT.FILL, SWT.FILL, true, true));
    this.fontSize = fontSize;
    font = new Font(shell.getDisplay(), fontName, fontSize, SWT.NORMAL);
    preview.setFont(font);
    sash.setWeights(new int[] {2, 3});
    summary = new Label(shell, SWT.WRAP);
    summary.setLayoutData(new GridData(SWT.FILL, SWT.CENTER, true, false, 2, 1));
    Composite buttons = new Composite(shell, SWT.NONE);
    RowLayout row = new RowLayout(SWT.HORIZONTAL);
    row.marginLeft = 0;
    row.marginRight = 0;
    row.spacing = 8;
    buttons.setLayout(row);
    buttons.setLayoutData(new GridData(SWT.END, SWT.CENTER, true, false, 2, 1));
    apply = new Button(buttons, SWT.PUSH);
    apply.setText(UiText.REPLACE_APPLY.text());
    apply.addListener(
        SWT.Selection,
        new Listener() {
          @Override
          public void handleEvent(Event event) {
            applyPressed();
          }
        });
    Button cancel = new Button(buttons, SWT.PUSH);
    cancel.setText(UiText.BUTTON_CANCEL.text());
    cancel.addListener(
        SWT.Selection,
        new Listener() {
          @Override
          public void handleEvent(Event event) {
            shell.dispose();
          }
        });
    listen();
    shell.addListener(
        SWT.Dispose,
        new Listener() {
          @Override
          public void handleEvent(Event event) {
            font.dispose();
            if (!applied) {
              ReplaceDialog.this.input.closed();
            }
          }
        });
  }

  private static Color color(Rgb rgb) {
    return new Color(rgb.red(), rgb.green(), rgb.blue());
  }

  private static Rgb mix(Rgb base, Rgb tint) {
    return new Rgb(
        (base.red() * 3 + tint.red()) / 4,
        (base.green() * 3 + tint.green()) / 4,
        (base.blue() * 3 + tint.blue()) / 4);
  }

  private static Composite column(Composite parent, UiText label) {
    Composite column = new Composite(parent, SWT.NONE);
    GridLayout layout = new GridLayout(1, false);
    layout.marginWidth = 0;
    layout.marginHeight = 0;
    column.setLayout(layout);
    Label title = new Label(column, SWT.NONE);
    title.setText(label.text());
    return column;
  }

  private static Table checkList(Composite parent, UiText label) {
    Composite column = column(parent, label);
    Table table =
        new Table(column, SWT.VIRTUAL | SWT.CHECK | SWT.SINGLE | SWT.FULL_SELECTION | SWT.BORDER);
    table.setLayoutData(new GridData(SWT.FILL, SWT.FILL, true, true));
    return table;
  }

  private void listen() {
    files.addListener(
        SWT.SetData,
        new Listener() {
          @Override
          public void handleEvent(Event event) {
            TableItem item = (TableItem) event.item;
            if (event.index >= input.fileCount()) {
              return;
            }
            item.setText(input.file(event.index).replace("&", "&&"));
            item.setChecked(input.fileChecked(event.index));
          }
        });
    matches.addListener(
        SWT.SetData,
        new Listener() {
          @Override
          public void handleEvent(Event event) {
            TableItem item = (TableItem) event.item;
            if (shownFile >= 0
                && shownFile < input.fileCount()
                && event.index < input.matchCount(shownFile)) {
              item.setText(input.match(shownFile, event.index).text());
              item.setChecked(input.matchChecked(shownFile, event.index));
            }
          }
        });
    files.addListener(
        SWT.Selection,
        new Listener() {
          @Override
          public void handleEvent(Event event) {
            int at = files.indexOf((TableItem) event.item);
            if (event.detail == SWT.CHECK) {
              input.setFileChecked(at, ((TableItem) event.item).getChecked());
            } else if (at != shownFile) {
              showFile(at);
              input.select(at);
            }
          }
        });
    matches.addListener(
        SWT.Selection,
        new Listener() {
          @Override
          public void handleEvent(Event event) {
            if (event.detail == SWT.CHECK && shownFile >= 0) {
              input.setMatchChecked(
                  shownFile,
                  matches.indexOf((TableItem) event.item),
                  ((TableItem) event.item).getChecked());
            }
          }
        });
  }

  private void showFile(int file) {
    shownFile = file;
    matches.setItemCount(file < 0 ? 0 : input.matchCount(file));
    matches.clearAll();
  }

  private void applyPressed() {
    if (input.apply()) {
      applied = true;
      shell.dispose();
    }
  }

  /**
   * Shows the preview in another family.
   *
   * @param fontName the family
   */
  public void setFontName(String fontName) {
    Font old = font;
    font = new Font(shell.getDisplay(), fontName, fontSize, SWT.NORMAL);
    preview.setFont(font);
    old.dispose();
  }

  /** Opens the dialog over its parent, showing the first file. */
  public void open() {
    refresh();
    if (input.fileCount() > 0) {
      files.setSelection(0);
      showFile(0);
    }
    Point size = shell.computeSize(820, 640);
    Rectangle owner = shell.getParent().getBounds();
    shell.setBounds(
        owner.x + Math.max(0, (owner.width - size.x) / 2),
        owner.y + Math.max(0, (owner.height - size.y) / 2),
        size.x,
        size.y);
    shell.open();
    with.setFocus();
    with.selectAll();
  }

  /** Reads the model again: file rows, the shown file's match rows, the view and the summary. */
  public void refresh() {
    if (shell.isDisposed()) {
      return;
    }
    files.setItemCount(input.fileCount());
    files.clearAll();
    if (shownFile >= 0 && shownFile < input.fileCount()) {
      matches.setItemCount(input.matchCount(shownFile));
      matches.clearAll();
    }
    showPreview(input.preview());
    summary.setText(input.summary().replace("&", "&&"));
    apply.setEnabled(input.canApply());
    shell.layout(new Control[] {summary});
  }

  private void showPreview(List<PreviewLine> lines) {
    if (lines == null) {
      String computing = UiText.REPLACE_COMPUTING.text();
      preview.setText(computing);
      preview.setStyleRange(new StyleRange(0, computing.length(), note, null));
      return;
    }
    StringBuilder text = new StringBuilder();
    int width = 1;
    for (PreviewLine line : lines) {
      width = Math.max(width, String.valueOf(line.line()).length());
    }
    for (PreviewLine line : lines) {
      if (text.length() > 0) {
        text.append('\n');
      }
      switch (line.kind()) {
        case SAME -> text.append(pad(line.line(), width)).append("   ").append(line.text());
        case REMOVED -> text.append(pad(line.line(), width)).append(" - ").append(line.text());
        case ADDED -> text.append(pad(line.line(), width)).append(" + ").append(line.text());
        case GAP -> text.append(" ".repeat(width)).append("  ...");
        case NOTE -> text.append(line.text());
      }
    }
    preview.setText(text.toString());
    for (int i = 0; i < lines.size(); i++) {
      PreviewLine.Kind kind = lines.get(i).kind();
      Color background =
          kind == PreviewLine.Kind.REMOVED
              ? removed
              : kind == PreviewLine.Kind.ADDED ? added : null;
      if (background != null) {
        preview.setLineBackground(i, 1, background);
      }
    }
  }

  private static String pad(int number, int width) {
    String text = String.valueOf(number);
    return " ".repeat(Math.max(0, width - text.length())) + text;
  }

  /**
   * Returns the shell.
   *
   * @return the shell
   */
  public Shell shell() {
    return shell;
  }

  /**
   * Returns the before and after view's text, for tests.
   *
   * @return the text
   */
  public String previewText() {
    return preview.getText();
  }

  /**
   * Sets the replacement field, for tests.
   *
   * @param text the replacement
   */
  public void setReplacement(String text) {
    with.setText(text);
  }

  /**
   * Unchecks or checks a match of the shown file as a click on its box does, for tests.
   *
   * @param match the match's row
   * @param checked the new state
   */
  public void checkMatch(int match, boolean checked) {
    TableItem item = matches.getItem(match);
    item.getText();
    item.setChecked(checked);
    Event event = new Event();
    event.item = item;
    event.detail = SWT.CHECK;
    matches.notifyListeners(SWT.Selection, event);
  }

  /** Presses Apply, for tests. */
  public void pressApply() {
    applyPressed();
  }
}
