package io.github.ajdailey.qcaret.app.controller;

import io.github.ajdailey.qcaret.app.editor.Bookmarks;
import io.github.ajdailey.qcaret.app.i18n.UiText;
import io.github.ajdailey.qcaret.app.view.MainView;
import io.github.ajdailey.qcaret.app.view.TabInput;
import io.github.ajdailey.qcaret.app.view.TabView;
import io.github.ajdailey.qcaret.core.concurrent.CancellationToken;
import io.github.ajdailey.qcaret.core.document.Document;
import io.github.ajdailey.qcaret.core.document.DocumentChange;
import io.github.ajdailey.qcaret.core.document.DocumentListener;
import io.github.ajdailey.qcaret.core.syntax.GrammarSet;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.function.Supplier;

/**
 * One open document and its tab in one window. {@link DocumentController} creates and changes it;
 * {@link DocumentSettings} and {@link DocumentsUnder} read and change it for that controller. A tab
 * move creates a new entry in the target window from the old one, sharing the document, the file
 * link and the editors; the old entry ends closed. Confined to the UI thread.
 */
final class OpenDocument implements DocumentListener {
  final Document document;
  final String bufferId;
  String untitled;
  TabInput input;

  /** The file link; a tab move shares it with the entry that takes over. */
  final OpenFile file;

  long reserved;
  EditorController editor;
  final List<EditorController> editors;
  boolean sideBySide;
  TabView tab;
  Bookmarks bookmarks;
  CancellationToken converting;
  boolean binary;
  boolean closed;
  SyntaxHighlighter syntax;
  String grammar = GrammarSet.PLAIN;
  boolean manualGrammar;
  int detecting;

  /** The running reopen of this document, or null. */
  DocumentController.Open reopen;

  /** The number of the newest open whose callback reached this tab. */
  long landed;

  final DocumentSettings.State settings = new DocumentSettings.State();

  private final MainView view;
  private final Supplier<OpenDocument> active;

  /**
   * Creates the entry of a document opened in a window.
   *
   * @param document the document
   * @param file the file link
   * @param reserved bytes reserved for the document
   * @param untitled the untitled name, or null for a document with a file
   * @param bufferId the buffer's id within the instance
   * @param view the window's view
   * @param active returns the window's selected document
   */
  OpenDocument(
      Document document,
      OpenFile file,
      long reserved,
      String untitled,
      String bufferId,
      MainView view,
      Supplier<OpenDocument> active) {
    this.document = document;
    this.file = file;
    this.reserved = reserved;
    this.editors = new ArrayList<EditorController>(2);
    this.untitled = untitled;
    this.bufferId = bufferId;
    this.view = view;
    this.active = active;
  }

  /**
   * Takes over a tab from another window. The list of editors is shared: the highlighter's repaint
   * reaches them through it.
   *
   * @param from the source window's entry
   * @param view the target window's view
   * @param active returns the target window's selected document
   */
  OpenDocument(OpenDocument from, MainView view, Supplier<OpenDocument> active) {
    this.document = from.document;
    this.bufferId = from.bufferId;
    this.file = from.file;
    this.reserved = from.reserved;
    this.editors = from.editors;
    this.untitled = from.untitled;
    this.editor = from.editor;
    this.sideBySide = from.sideBySide;
    this.bookmarks = from.bookmarks;
    this.binary = from.binary;
    this.syntax = from.syntax;
    this.grammar = from.grammar;
    this.manualGrammar = from.manualGrammar;
    this.settings.settings = from.settings.settings;
    this.view = view;
    this.active = active;
  }

  /** Returns the file name, or the untitled name without a file. */
  String name() {
    Path name = file.path == null ? null : file.path.getFileName();
    return name == null ? untitled : name.toString();
  }

  /** Shows the name and the dirty, binary, read-only and missing marks on an open tab. */
  void updateTitle() {
    if (!closed) {
      String name = name();
      String title = (document.isDirty() ? "*" : "") + name;
      if (binary) {
        title = UiText.TAB_BINARY.format(title);
      } else if (document.isReadOnly()) {
        title = UiText.TAB_READ_ONLY.format(title);
      }
      if (file.missing) {
        title = UiText.TAB_MISSING.format(title);
      }
      tab.setTitle(
          title,
          DocumentSettings.toolTip(file.path == null ? name : file.path.toString(), settings));
    }
  }

  /**
   * Sets the tab width and indentation, telling the editors of a new width.
   *
   * @param tabWidth columns per tab stop
   * @param insertSpaces true to indent with spaces
   */
  void setTabSettings(int tabWidth, boolean insertSpaces) {
    if (document.tabWidth() != tabWidth) {
      document.setTabWidth(tabWidth);
      for (EditorController e : editors) {
        e.tabWidthChanged();
      }
    }
    document.setInsertSpaces(insertSpaces);
  }

  /** Shows new per-file settings: the title and tool tip and the document state. */
  void settingsChanged() {
    updateTitle();
    view.documentStateChanged();
  }

  @Override
  public void textChanged(DocumentChange change) {
    if (this == active.get()) {
      view.editStateChanged();
    }
  }

  @Override
  public void dirtyChanged(boolean dirty) {
    updateTitle();
    view.documentStateChanged();
  }
}
