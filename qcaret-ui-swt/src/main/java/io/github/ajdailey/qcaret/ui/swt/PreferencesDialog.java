package io.github.ajdailey.qcaret.ui.swt;

import io.github.ajdailey.qcaret.app.command.CommandGroup;
import io.github.ajdailey.qcaret.app.command.CommandId;
import io.github.ajdailey.qcaret.app.command.KeyStroke;
import io.github.ajdailey.qcaret.app.command.Keymap;
import io.github.ajdailey.qcaret.app.command.ShortcutOverrides;
import io.github.ajdailey.qcaret.app.i18n.UiText;
import io.github.ajdailey.qcaret.app.theme.ThemeMode;
import io.github.ajdailey.qcaret.app.view.AppearanceChoices;
import io.github.ajdailey.qcaret.app.view.PreferencesForm;
import io.github.ajdailey.qcaret.app.view.PreferencesInput;
import io.github.ajdailey.qcaret.ui.swt.editor.SwtKeys;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.function.Consumer;
import org.eclipse.swt.SWT;
import org.eclipse.swt.layout.GridData;
import org.eclipse.swt.layout.GridLayout;
import org.eclipse.swt.widgets.Button;
import org.eclipse.swt.widgets.Combo;
import org.eclipse.swt.widgets.Composite;
import org.eclipse.swt.widgets.Event;
import org.eclipse.swt.widgets.Label;
import org.eclipse.swt.widgets.Listener;
import org.eclipse.swt.widgets.Shell;
import org.eclipse.swt.widgets.TabFolder;
import org.eclipse.swt.widgets.TabItem;
import org.eclipse.swt.widgets.Table;
import org.eclipse.swt.widgets.TableColumn;
import org.eclipse.swt.widgets.TableItem;
import org.eclipse.swt.widgets.Text;

/**
 * Edit > Preferences: one tab per {@link PreferencesForm.Group}. OK and Apply hand the fields to
 * the {@link PreferencesInput}; a refused field shows its message above the buttons, selects its
 * tab and keeps the dialog open. Restore Defaults fills every tab and changes nothing until OK or
 * Apply. Clear History asks, then clears the saved search histories and HyperSearch's last run at
 * once, whatever OK, Apply or Cancel do afterwards. The Keys tab edits overrides on top of the
 * default keymap: the key field records one stroke, Add refuses a stroke that types or that another
 * command holds, Default Keys returns the selected command to its defaults. The theme file and font
 * family lists fill when a worker has listed them. Modeless; it returns at once.
 */
final class PreferencesDialog {
  private static final ThemeMode[] THEMES = {ThemeMode.LIGHT, ThemeMode.DARK, ThemeMode.SYSTEM};

  private final Shell shell;
  private final PreferencesInput input;
  private final List<CommandId> rows = new ArrayList<CommandId>();
  private final TabFolder folder;
  private final Label message;
  private Text completionChars;
  private Button autoClose;
  private Text tabWidth;
  private Button insertSpaces;
  private Text maxHighlightLineChars;
  private Text pollSeconds;
  private Button autoReloadClean;
  private Text recoveryIntervalSeconds;
  private Text recoveryKeepDays;
  private Button sessionSnapshot;
  private Text backupDays;
  private Text searchMaxLineChars;
  private Text excludedDirectories;
  private Combo theme;
  private Text fontSize;
  private Combo themeFile;
  private Combo fontFamily;
  private Text filter;
  private Table commands;
  private org.eclipse.swt.widgets.List keys;
  private Text capture;
  private Label keyMessage;
  private KeyStroke captured;
  private ShortcutOverrides overrides;

  PreferencesDialog(Shell parent, PreferencesInput input) {
    this.input = input;
    shell = Dialogs.shell(parent, UiText.PREFERENCES_TITLE.text(), false);
    folder = new TabFolder(shell, SWT.TOP);
    folder.setLayoutData(new GridData(SWT.FILL, SWT.FILL, true, true));
    editor(tab(UiText.PREFERENCES_TAB_EDITOR));
    files(tab(UiText.PREFERENCES_TAB_FILES));
    search(tab(UiText.PREFERENCES_TAB_SEARCH));
    appearance(tab(UiText.PREFERENCES_TAB_APPEARANCE));
    keys(tab(UiText.PREFERENCES_TAB_KEYS));
    message = new Label(shell, SWT.WRAP);
    message.setLayoutData(new GridData(SWT.FILL, SWT.CENTER, true, false));
    Composite buttons = Dialogs.buttonRow(shell, 1);
    Dialogs.button(
        buttons,
        UiText.BUTTON_RESTORE_DEFAULTS.text(),
        new Runnable() {
          @Override
          public void run() {
            restoreDefaults();
          }
        });
    Button ok =
        Dialogs.button(
            buttons,
            UiText.BUTTON_OK.text(),
            new Runnable() {
              @Override
              public void run() {
                if (apply()) {
                  shell.dispose();
                }
              }
            });
    Dialogs.button(buttons, UiText.BUTTON_CANCEL.text(), Dialogs.closer(shell));
    Dialogs.button(
        buttons,
        UiText.BUTTON_APPLY.text(),
        new Runnable() {
          @Override
          public void run() {
            apply();
          }
        });
    shell.setDefaultButton(ok);
    show(input.form());
    input.loadChoices(
        new Consumer<AppearanceChoices>() {
          @Override
          public void accept(AppearanceChoices choices) {
            if (!shell.isDisposed()) {
              fillChoices(themeFile, choices.themeFiles());
              fillChoices(fontFamily, choices.fontFamilies());
            }
          }
        });
  }

  private Composite tab(UiText title) {
    TabItem item = new TabItem(folder, SWT.NONE);
    item.setText(title.text());
    Composite page = new Composite(folder, SWT.NONE);
    GridLayout layout = new GridLayout(2, false);
    layout.marginWidth = 10;
    layout.marginHeight = 10;
    page.setLayout(layout);
    item.setControl(page);
    return page;
  }

  private static Text field(Composite page, UiText label) {
    new Label(page, SWT.NONE).setText(label.text());
    Text text = new Text(page, SWT.SINGLE | SWT.BORDER);
    GridData data = new GridData(SWT.FILL, SWT.CENTER, true, false);
    data.widthHint = 120;
    text.setLayoutData(data);
    return text;
  }

  private static Button check(Composite page, UiText label) {
    Button button = SwtThemeService.check(page);
    button.setText(label.text());
    button.setLayoutData(new GridData(SWT.FILL, SWT.CENTER, true, false, 2, 1));
    return button;
  }

  private void editor(Composite page) {
    completionChars = field(page, UiText.PREFERENCES_COMPLETION_CHARS);
    autoClose = check(page, UiText.PREFERENCES_AUTO_CLOSE);
    maxHighlightLineChars = field(page, UiText.PREFERENCES_HIGHLIGHT_CHARS);
    tabWidth = field(page, UiText.PREFERENCES_TAB_WIDTH);
    insertSpaces = check(page, UiText.PREFERENCES_INSERT_SPACES);
  }

  private void files(Composite page) {
    pollSeconds = field(page, UiText.PREFERENCES_POLL_SECONDS);
    autoReloadClean = check(page, UiText.PREFERENCES_AUTO_RELOAD);
    recoveryIntervalSeconds = field(page, UiText.PREFERENCES_RECOVERY_SECONDS);
    recoveryKeepDays = field(page, UiText.PREFERENCES_RECOVERY_DAYS);
    sessionSnapshot = check(page, UiText.PREFERENCES_SESSION_SNAPSHOT);
  }

  private void search(Composite page) {
    searchMaxLineChars = field(page, UiText.PREFERENCES_SEARCH_LINE_CHARS);
    backupDays = field(page, UiText.PREFERENCES_BACKUP_DAYS);
    excludedDirectories = field(page, UiText.PREFERENCES_EXCLUDED_DIRECTORIES);
    Dialogs.button(
            page,
            UiText.BUTTON_CLEAR_HISTORY.text(),
            new Runnable() {
              @Override
              public void run() {
                askClearHistory();
              }
            })
        .setLayoutData(new GridData(SWT.BEGINNING, SWT.CENTER, false, false, 2, 1));
  }

  /** Opens the prompt before the histories are cleared. */
  ClearHistoryDialog askClearHistory() {
    ClearHistoryDialog prompt =
        new ClearHistoryDialog(
            shell,
            new Runnable() {
              @Override
              public void run() {
                input.clearHistory();
              }
            });
    prompt.open();
    return prompt;
  }

  private void appearance(Composite page) {
    new Label(page, SWT.NONE).setText(UiText.PREFERENCES_THEME.text());
    theme = new Combo(page, SWT.DROP_DOWN | SWT.READ_ONLY);
    theme.add(UiText.PREFERENCES_THEME_LIGHT.text());
    theme.add(UiText.PREFERENCES_THEME_DARK.text());
    theme.add(UiText.PREFERENCES_THEME_SYSTEM.text());
    fontSize = field(page, UiText.PREFERENCES_FONT_SIZE);
    themeFile = choice(page, UiText.PREFERENCES_THEME_FILE, UiText.PREFERENCES_THEME_FILE_NONE);
    fontFamily =
        choice(page, UiText.PREFERENCES_FONT_FAMILY, UiText.PREFERENCES_FONT_FAMILY_DEFAULT);
  }

  /** Adds a list whose first entry stands for an empty value; a worker's list fills the rest. */
  private static Combo choice(Composite page, UiText label, UiText first) {
    new Label(page, SWT.NONE).setText(label.text());
    Combo combo = new Combo(page, SWT.DROP_DOWN | SWT.READ_ONLY);
    combo.add(first.text());
    combo.select(0);
    combo.setLayoutData(new GridData(SWT.FILL, SWT.CENTER, true, false));
    return combo;
  }

  /** Selects a value, adding it when the list lacks it, so an uninstalled family stays saved. */
  private static void selectChoice(Combo combo, String value) {
    if (value.isEmpty()) {
      combo.select(0);
      return;
    }
    if (combo.indexOf(value) < 0) {
      combo.add(value);
    }
    combo.select(combo.indexOf(value));
  }

  private static String chosen(Combo combo) {
    int index = combo.getSelectionIndex();
    return index <= 0 ? "" : combo.getItem(index);
  }

  /** Replaces a list's entries after the first and keeps the selected value. */
  private static void fillChoices(Combo combo, List<String> values) {
    String keep = chosen(combo);
    combo.remove(1, combo.getItemCount() - 1);
    for (String value : values) {
      combo.add(value);
    }
    selectChoice(combo, keep);
  }

  private void keys(Composite page) {
    page.setLayout(new GridLayout(1, false));
    filter = new Text(page, SWT.SEARCH | SWT.ICON_CANCEL | SWT.BORDER);
    filter.setMessage(UiText.SHORTCUTS_FILTER.text());
    filter.setLayoutData(new GridData(SWT.FILL, SWT.CENTER, true, false));
    commands = new Table(page, SWT.BORDER | SWT.FULL_SELECTION | SWT.V_SCROLL | SWT.SINGLE);
    commands.setHeaderVisible(true);
    commands.setLinesVisible(true);
    GridData tableData = new GridData(SWT.FILL, SWT.FILL, true, true);
    tableData.heightHint = 220;
    commands.setLayoutData(tableData);
    for (UiText title :
        new UiText[] {UiText.SHORTCUTS_GROUP, UiText.SHORTCUTS_COMMAND, UiText.SHORTCUTS_KEYS}) {
      new TableColumn(commands, SWT.LEFT).setText(title.text());
    }
    Composite editor = new Composite(page, SWT.NONE);
    editor.setLayout(new GridLayout(4, false));
    editor.setLayoutData(new GridData(SWT.FILL, SWT.CENTER, true, false));
    keys = new org.eclipse.swt.widgets.List(editor, SWT.BORDER | SWT.SINGLE | SWT.V_SCROLL);
    GridData keysData = new GridData(SWT.FILL, SWT.FILL, true, false, 1, 2);
    keysData.heightHint = 56;
    keys.setLayoutData(keysData);
    capture = new Text(editor, SWT.BORDER | SWT.READ_ONLY);
    capture.setMessage(UiText.PREFERENCES_KEY_CAPTURE.text());
    GridData captureData = new GridData(SWT.FILL, SWT.CENTER, true, false, 3, 1);
    captureData.widthHint = 160;
    capture.setLayoutData(captureData);
    Dialogs.button(
        editor,
        UiText.BUTTON_ADD_KEY.text(),
        new Runnable() {
          @Override
          public void run() {
            addCaptured();
          }
        });
    Dialogs.button(
        editor,
        UiText.BUTTON_REMOVE_KEY.text(),
        new Runnable() {
          @Override
          public void run() {
            removeSelected();
          }
        });
    Dialogs.button(
        editor,
        UiText.BUTTON_RESET_KEY.text(),
        new Runnable() {
          @Override
          public void run() {
            CommandId command = selected();
            if (command != null) {
              setOverrides(overrides.without(command));
            }
          }
        });
    keyMessage = new Label(page, SWT.WRAP);
    keyMessage.setLayoutData(new GridData(SWT.FILL, SWT.CENTER, true, false));
    capture.addListener(
        SWT.KeyDown,
        new Listener() {
          @Override
          public void handleEvent(Event event) {
            KeyStroke stroke = SwtKeys.stroke(event.stateMask, event.keyCode, event.character);
            if (stroke != null) {
              event.doit = false;
              record(stroke);
            }
          }
        });
    capture.addListener(
        SWT.Traverse,
        new Listener() {
          @Override
          public void handleEvent(Event event) {
            if (event.detail == SWT.TRAVERSE_ESCAPE || event.detail == SWT.TRAVERSE_RETURN) {
              event.doit = false;
            }
          }
        });
    filter.addListener(
        SWT.Modify,
        new Listener() {
          @Override
          public void handleEvent(Event event) {
            fill();
          }
        });
    commands.addListener(
        SWT.Selection,
        new Listener() {
          @Override
          public void handleEvent(Event event) {
            showKeys();
          }
        });
  }

  /** Puts a form's values in the fields. */
  private void show(PreferencesForm form) {
    completionChars.setText(form.completionChars());
    autoClose.setSelection(form.autoClose());
    maxHighlightLineChars.setText(form.maxHighlightLineChars());
    tabWidth.setText(form.tabWidth());
    insertSpaces.setSelection(form.insertSpaces());
    pollSeconds.setText(form.pollSeconds());
    autoReloadClean.setSelection(form.autoReloadClean());
    recoveryIntervalSeconds.setText(form.recoveryIntervalSeconds());
    recoveryKeepDays.setText(form.recoveryKeepDays());
    sessionSnapshot.setSelection(form.sessionSnapshot());
    backupDays.setText(form.backupDays());
    searchMaxLineChars.setText(form.searchMaxLineChars());
    excludedDirectories.setText(form.excludedDirectories());
    theme.select(form.theme().ordinal());
    fontSize.setText(form.fontSize());
    selectChoice(themeFile, form.themeFile());
    selectChoice(fontFamily, form.fontFamily());
    setOverrides(form.shortcuts());
    for (TableColumn column : commands.getColumns()) {
      column.pack();
    }
  }

  private PreferencesForm form() {
    return new PreferencesForm(
        completionChars.getText(),
        autoClose.getSelection(),
        maxHighlightLineChars.getText(),
        tabWidth.getText(),
        insertSpaces.getSelection(),
        pollSeconds.getText(),
        autoReloadClean.getSelection(),
        recoveryIntervalSeconds.getText(),
        recoveryKeepDays.getText(),
        sessionSnapshot.getSelection(),
        backupDays.getText(),
        searchMaxLineChars.getText(),
        excludedDirectories.getText(),
        THEMES[theme.getSelectionIndex()],
        fontSize.getText(),
        overrides,
        chosen(themeFile),
        chosen(fontFamily));
  }

  /** Fills every tab with the defaults; nothing changes until OK or Apply. */
  void restoreDefaults() {
    show(input.defaults());
    message.setText("");
  }

  /**
   * Hands the fields to the controller.
   *
   * @return false when a field was refused: its tab shows and the message says why
   */
  boolean apply() {
    PreferencesForm.Problem problem = input.apply(form());
    message.setText(problem == null ? "" : problem.message());
    if (problem != null) {
      folder.setSelection(problem.group().ordinal());
    }
    shell.layout(true, true);
    return problem == null;
  }

  /** Shows a stroke in the key field. */
  void record(KeyStroke stroke) {
    captured = stroke;
    capture.setText(stroke.text(SwtKeys.mac()));
  }

  private Keymap working() {
    return input.keymap(overrides);
  }

  private void setOverrides(ShortcutOverrides next) {
    overrides = next;
    keyMessage.setText("");
    fill();
  }

  /** Adds the recorded key to the selected command unless it types or another command holds it. */
  void addCaptured() {
    CommandId command = selected();
    if (command == null || captured == null) {
      return;
    }
    String keyText = captured.text(SwtKeys.mac());
    Keymap current = working();
    CommandId holder = current.holder(captured, command);
    if (captured.types() || holder != null) {
      keyMessage.setText(
          captured.types()
              ? UiText.PREFERENCES_KEY_TYPES.format(keyText)
              : UiText.PREFERENCES_KEY_TAKEN.format(keyText, holder.title()));
      keyMessage.getParent().layout(true);
      return;
    }
    List<KeyStroke> list = new ArrayList<KeyStroke>(current.strokes(command));
    if (!list.contains(captured)) {
      list.add(captured);
      setOverrides(overrides.with(command, list));
    }
    captured = null;
    capture.setText("");
  }

  /** Removes the selected key from the selected command. */
  void removeSelected() {
    CommandId command = selected();
    int index = keys.getSelectionIndex();
    if (command != null && index >= 0) {
      List<KeyStroke> list = new ArrayList<KeyStroke>(working().strokes(command));
      list.remove(index);
      setOverrides(overrides.with(command, list));
    }
  }

  private CommandId selected() {
    int index = commands.getSelectionIndex();
    return index < 0 || index >= rows.size() ? null : rows.get(index);
  }

  /** Lists every command matching the filter with its keys; a changed one is marked. */
  private void fill() {
    CommandId keep = selected();
    commands.removeAll();
    rows.clear();
    Keymap current = working();
    String needle = filter.getText().toLowerCase(Locale.ROOT);
    for (CommandGroup group : CommandGroup.values()) {
      for (CommandId command : CommandId.values()) {
        if (command.group() != group) {
          continue;
        }
        String title =
            overrides.strokes(command) == null
                ? command.title()
                : UiText.PREFERENCES_KEY_CHANGED.format(command.title());
        String[] row = {group.title(), title, current.keysText(command)};
        if (String.join(" ", row).toLowerCase(Locale.ROOT).contains(needle)) {
          new TableItem(commands, SWT.NONE).setText(row);
          rows.add(command);
          if (command == keep) {
            commands.setSelection(rows.size() - 1);
          }
        }
      }
    }
    showKeys();
  }

  private void showKeys() {
    keys.removeAll();
    CommandId command = selected();
    if (command != null) {
      for (KeyStroke stroke : working().strokes(command)) {
        keys.add(stroke.text(SwtKeys.mac()));
      }
    }
  }

  /** Selects a command's row, for tests. */
  void select(CommandId command) {
    commands.setSelection(rows.indexOf(command));
    showKeys();
  }

  /** Selects one of the selected command's keys, for tests. */
  void selectKey(int index) {
    keys.select(index);
  }

  void open() {
    Dialogs.open(shell, 640, 520);
  }

  Shell shell() {
    return shell;
  }

  TabFolder folder() {
    return folder;
  }

  Label message() {
    return message;
  }

  Label keyMessage() {
    return keyMessage;
  }

  Text pollSeconds() {
    return pollSeconds;
  }

  Text excludedDirectories() {
    return excludedDirectories;
  }

  Combo theme() {
    return theme;
  }

  Combo themeFile() {
    return themeFile;
  }

  Combo fontFamily() {
    return fontFamily;
  }
}
