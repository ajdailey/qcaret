package io.github.ajdailey.qcaret.ui.swt;

import io.github.ajdailey.qcaret.app.i18n.UiText;
import io.github.ajdailey.qcaret.app.view.SearchForm;
import io.github.ajdailey.qcaret.app.view.SearchScope;
import java.util.List;
import java.util.function.Consumer;
import org.eclipse.swt.SWT;
import org.eclipse.swt.layout.GridData;
import org.eclipse.swt.layout.GridLayout;
import org.eclipse.swt.widgets.Button;
import org.eclipse.swt.widgets.Combo;
import org.eclipse.swt.widgets.Composite;
import org.eclipse.swt.widgets.DirectoryDialog;
import org.eclipse.swt.widgets.Event;
import org.eclipse.swt.widgets.Label;
import org.eclipse.swt.widgets.Listener;
import org.eclipse.swt.widgets.Shell;
import org.eclipse.swt.widgets.Text;

/**
 * Search > HyperSearch: the text with its history, Match Case, Whole Word and Regex, the scope, the
 * directory with its history and a Browse button, the include masks with their history, the exclude
 * masks and Hidden Files. The directory fields are enabled for the directory scopes, the masks for
 * every folder scope. Search passes the fields to the answer and closes; Cancel and Esc close
 * without an answer. Opens without a nested event loop; Browse runs the native folder chooser,
 * which has one.
 */
final class SearchDialog {
  private final Shell shell;
  private final Combo text;
  private final Button matchCase;
  private final Button wholeWord;
  private final Button regex;
  private final Combo scope;
  private final Combo directory;
  private final Button browse;
  private final Combo include;
  private final Text exclude;
  private final Button hidden;
  private final SearchForm form;

  SearchDialog(Shell parent, SearchForm form, final Consumer<SearchForm> run) {
    this.form = form;
    shell = Dialogs.shell(parent, UiText.SEARCH_TITLE.text(), true);
    Composite fields = new Composite(shell, SWT.NONE);
    GridLayout layout = new GridLayout(3, false);
    layout.marginWidth = 0;
    layout.marginHeight = 0;
    fields.setLayout(layout);
    fields.setLayoutData(new GridData(SWT.FILL, SWT.FILL, true, true));
    text = combo(fields, UiText.FIND_LABEL, form.textHistory(), form.text(), 2);
    Composite options = new Composite(fields, SWT.NONE);
    options.setLayout(new GridLayout(3, false));
    options.setLayoutData(new GridData(SWT.FILL, SWT.CENTER, true, false, 3, 1));
    matchCase = check(options, UiText.FIND_MATCH_CASE, form.caseSensitive());
    wholeWord = check(options, UiText.FIND_WHOLE_WORD, form.wholeWord());
    regex = check(options, UiText.FIND_REGEX, form.regex());
    label(fields, UiText.SEARCH_SCOPE_LABEL);
    scope = new Combo(fields, SWT.DROP_DOWN | SWT.READ_ONLY);
    for (SearchScope s : SearchScope.values()) {
      scope.add(s.label());
    }
    scope.select(form.scope().ordinal());
    scope.setLayoutData(new GridData(SWT.FILL, SWT.CENTER, true, false, 2, 1));
    Label workspace = new Label(fields, SWT.NONE);
    workspace.setText(
        form.workspace().isEmpty()
            ? UiText.SEARCH_WORKSPACE_NONE.text()
            : UiText.SEARCH_WORKSPACE.format(form.workspace()).replace("&", "&&"));
    workspace.setLayoutData(new GridData(SWT.FILL, SWT.CENTER, true, false, 3, 1));
    directory = combo(fields, UiText.SEARCH_DIRECTORY_LABEL, form.directoryHistory(), "", 1);
    directory.setText(form.directory());
    browse = new Button(fields, SWT.PUSH);
    browse.setText(UiText.SEARCH_BROWSE.text());
    browse.addListener(
        SWT.Selection,
        new Listener() {
          @Override
          public void handleEvent(Event event) {
            browse();
          }
        });
    include = combo(fields, UiText.SEARCH_INCLUDE_LABEL, form.maskHistory(), form.include(), 2);
    label(fields, UiText.SEARCH_EXCLUDE_LABEL);
    exclude = new Text(fields, SWT.SINGLE | SWT.BORDER);
    exclude.setText(form.exclude());
    exclude.setLayoutData(new GridData(SWT.FILL, SWT.CENTER, true, false, 2, 1));
    hidden = check(fields, UiText.SEARCH_HIDDEN, form.hidden());
    ((GridData) hidden.getLayoutData()).horizontalSpan = 3;
    scope.addListener(
        SWT.Selection,
        new Listener() {
          @Override
          public void handleEvent(Event event) {
            syncScope();
          }
        });
    syncScope();
    Composite buttons = Dialogs.buttonRow(shell, 1);
    Button search =
        Dialogs.button(
            buttons,
            UiText.SEARCH_RUN.text(),
            new Runnable() {
              @Override
              public void run() {
                SearchForm chosen = fields();
                shell.dispose();
                run.accept(chosen);
              }
            });
    Dialogs.button(buttons, UiText.BUTTON_CANCEL.text(), Dialogs.closer(shell));
    shell.setDefaultButton(search);
  }

  private static Label label(Composite parent, UiText text) {
    Label label = new Label(parent, SWT.NONE);
    label.setText(text.text());
    return label;
  }

  private static Combo combo(
      Composite parent, UiText text, List<String> history, String value, int span) {
    label(parent, text);
    Combo combo = new Combo(parent, SWT.DROP_DOWN);
    for (String item : history) {
      combo.add(item);
    }
    combo.setText(value);
    GridData data = new GridData(SWT.FILL, SWT.CENTER, true, false, span, 1);
    data.widthHint = 380;
    combo.setLayoutData(data);
    return combo;
  }

  private static Button check(Composite parent, UiText text, boolean selected) {
    Button check = new Button(parent, SWT.CHECK);
    check.setText(text.text());
    check.setSelection(selected);
    check.setLayoutData(new GridData(SWT.BEGINNING, SWT.CENTER, false, false));
    return check;
  }

  private SearchScope selectedScope() {
    int at = scope.getSelectionIndex();
    return at < 0 ? SearchScope.CURRENT_DOCUMENT : SearchScope.values()[at];
  }

  private void syncScope() {
    SearchScope chosen = selectedScope();
    boolean directories = chosen == SearchScope.DIRECTORY || chosen == SearchScope.TREE;
    directory.setEnabled(directories);
    browse.setEnabled(directories);
    include.setEnabled(chosen.folder());
    exclude.setEnabled(chosen.folder());
    hidden.setEnabled(chosen.folder());
  }

  private void browse() {
    DirectoryDialog chooser = new DirectoryDialog(shell);
    chooser.setFilterPath(directory.getText());
    String chosen = chooser.open();
    if (chosen != null && !directory.isDisposed()) {
      directory.setText(chosen);
    }
  }

  /** Reads the fields. */
  SearchForm fields() {
    return new SearchForm(
        text.getText(),
        regex.getSelection(),
        matchCase.getSelection(),
        wholeWord.getSelection(),
        selectedScope(),
        directory.getText(),
        include.getText(),
        exclude.getText(),
        hidden.getSelection(),
        form.workspace(),
        form.textHistory(),
        form.directoryHistory(),
        form.maskHistory());
  }

  void open() {
    Dialogs.open(shell, SWT.DEFAULT, SWT.DEFAULT);
    text.setFocus();
  }

  Shell shell() {
    return shell;
  }

  Combo text() {
    return text;
  }

  Combo scope() {
    return scope;
  }

  Combo directory() {
    return directory;
  }

  Combo include() {
    return include;
  }

  /** Presses Search, for tests. */
  void search() {
    shell.getDefaultButton().notifyListeners(SWT.Selection, new Event());
  }
}
