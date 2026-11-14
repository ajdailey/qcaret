package io.github.ajdailey.qcaret.ui.swt;

import io.github.ajdailey.qcaret.app.i18n.UiText;
import io.github.ajdailey.qcaret.app.view.PreferencesForm;
import org.eclipse.swt.SWT;
import org.eclipse.swt.layout.GridData;
import org.eclipse.swt.widgets.Button;
import org.eclipse.swt.widgets.Composite;
import org.eclipse.swt.widgets.Label;

/**
 * The Preferences dialog's View tab: what a new window starts with, then what every window shows.
 */
final class PreferencesViewTab {
  private final Button wordWrap;
  private final Button whitespace;
  private final Button lineEndings;
  private final Button overview;
  private final Button lineNumbers;
  private final Button workspaceBrowser;
  private final Button toolbar;
  private final Button rightMargin;
  private final Button indentGuides;
  private final Button lineChanges;

  /** Fills a two-column page. */
  PreferencesViewTab(Composite page) {
    heading(page, UiText.PREFERENCES_VIEW_NEW_WINDOWS);
    wordWrap = check(page, UiText.PREFERENCES_VIEW_WORD_WRAP);
    whitespace = check(page, UiText.PREFERENCES_VIEW_WHITESPACE);
    lineEndings = check(page, UiText.PREFERENCES_VIEW_LINE_ENDINGS);
    overview = check(page, UiText.PREFERENCES_VIEW_OVERVIEW);
    lineNumbers = check(page, UiText.PREFERENCES_VIEW_LINE_NUMBERS);
    workspaceBrowser = check(page, UiText.PREFERENCES_VIEW_WORKSPACE_BROWSER);
    heading(page, UiText.PREFERENCES_VIEW_ALL_WINDOWS);
    toolbar = check(page, UiText.PREFERENCES_VIEW_TOOLBAR);
    rightMargin = check(page, UiText.PREFERENCES_VIEW_RIGHT_MARGIN);
    indentGuides = check(page, UiText.PREFERENCES_VIEW_INDENT_GUIDES);
    lineChanges = check(page, UiText.PREFERENCES_VIEW_LINE_CHANGES);
  }

  private static void heading(Composite page, UiText text) {
    Label label = new Label(page, SWT.NONE);
    label.setText(text.text());
    label.setLayoutData(new GridData(SWT.FILL, SWT.CENTER, true, false, 2, 1));
  }

  private static Button check(Composite page, UiText label) {
    Button button = SwtThemeService.check(page);
    button.setText(label.text());
    GridData data = new GridData(SWT.FILL, SWT.CENTER, true, false, 2, 1);
    data.horizontalIndent = 16;
    button.setLayoutData(data);
    return button;
  }

  /** Sets the boxes. */
  void show(PreferencesForm.ViewChecks c) {
    wordWrap.setSelection(c.wordWrap());
    whitespace.setSelection(c.whitespace());
    lineEndings.setSelection(c.lineEndings());
    overview.setSelection(c.overview());
    lineNumbers.setSelection(c.lineNumbers());
    workspaceBrowser.setSelection(c.workspaceBrowser());
    toolbar.setSelection(c.toolbar());
    rightMargin.setSelection(c.rightMargin());
    indentGuides.setSelection(c.indentGuides());
    lineChanges.setSelection(c.lineChanges());
  }

  /** Returns the boxes. */
  PreferencesForm.ViewChecks checks() {
    return new PreferencesForm.ViewChecks(
        wordWrap.getSelection(),
        whitespace.getSelection(),
        lineEndings.getSelection(),
        overview.getSelection(),
        lineNumbers.getSelection(),
        workspaceBrowser.getSelection(),
        toolbar.getSelection(),
        rightMargin.getSelection(),
        indentGuides.getSelection(),
        lineChanges.getSelection());
  }

  Button wordWrap() {
    return wordWrap;
  }

  Button toolbar() {
    return toolbar;
  }
}
