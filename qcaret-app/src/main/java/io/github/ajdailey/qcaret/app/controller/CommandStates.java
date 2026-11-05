package io.github.ajdailey.qcaret.app.controller;

import io.github.ajdailey.qcaret.app.command.CommandGroup;
import io.github.ajdailey.qcaret.app.command.CommandId;
import io.github.ajdailey.qcaret.app.i18n.UiText;
import io.github.ajdailey.qcaret.app.theme.ThemeMode;
import io.github.ajdailey.qcaret.app.view.StatusFields;
import io.github.ajdailey.qcaret.app.view.TabInput;
import io.github.ajdailey.qcaret.core.buffer.LineView;
import io.github.ajdailey.qcaret.core.buffer.TextSnapshot;
import io.github.ajdailey.qcaret.core.document.Columns;
import io.github.ajdailey.qcaret.core.document.Document;
import io.github.ajdailey.qcaret.core.document.Selection;
import io.github.ajdailey.qcaret.core.document.SelectionSet;
import io.github.ajdailey.qcaret.core.syntax.Grammar;
import io.github.ajdailey.qcaret.core.syntax.GrammarSet;
import io.github.ajdailey.qcaret.core.text.LineEnding;
import io.github.ajdailey.qcaret.core.text.LineEndingStats;
import io.github.ajdailey.qcaret.core.text.TextEncoding;
import java.util.ArrayList;
import java.util.EnumSet;
import java.util.List;
import java.util.Set;

/**
 * The checked and enabled state of each command's menu item, the entries of the menus filled from
 * the controllers, and the status bar's fields, read from an {@link ApplicationController} on every
 * call. Views read this state here and never from controller getters. Call on the UI thread.
 *
 * <p>A command that acts on the selected document is disabled without one. A binary file's view
 * disables every command that would write or edit it; a read-only document disables the edits and
 * the line-ending conversions. Reopen Closed Tab needs a closed file, not a document. The fold
 * commands need fold regions in the focused view, the unfold commands a collapsed one. Next,
 * Previous and Clear Bookmarks need a bookmark. HyperSearch runs without a document; Next and
 * Previous Result need a match in a retained set. A disabled menu item's accelerator does not fire.
 */
public final class CommandStates {
  /** Commands that run without a document. */
  public static final Set<CommandId> WINDOW_COMMANDS =
      EnumSet.of(
          CommandId.NEW,
          CommandId.OPEN,
          CommandId.QUIT,
          CommandId.WORD_WRAP,
          CommandId.ZOOM_IN,
          CommandId.ZOOM_OUT,
          CommandId.ZOOM_RESET,
          CommandId.THEME_LIGHT,
          CommandId.THEME_DARK,
          CommandId.THEME_SYSTEM,
          CommandId.RELOAD_GRAMMARS,
          CommandId.SHOW_WHITESPACE,
          CommandId.SHOW_LINE_ENDINGS,
          CommandId.COMMAND_PALETTE,
          CommandId.SET_WORKSPACE,
          CommandId.HYPERSEARCH,
          CommandId.NEXT_RESULT,
          CommandId.PREVIOUS_RESULT,
          CommandId.SEARCH_RESULTS,
          CommandId.OVERVIEW_STRIP);

  /** Commands that change the text. */
  public static final Set<CommandId> EDITS =
      EnumSet.of(
          CommandId.UNDO,
          CommandId.REDO,
          CommandId.CUT,
          CommandId.PASTE,
          CommandId.NEWLINE,
          CommandId.DELETE_BACK,
          CommandId.DELETE_FORWARD,
          CommandId.LINE_ENDING_CRLF,
          CommandId.LINE_ENDING_LF,
          CommandId.LINE_ENDING_CR,
          CommandId.DUPLICATE,
          CommandId.DELETE_LINE,
          CommandId.MOVE_LINES_UP,
          CommandId.MOVE_LINES_DOWN,
          CommandId.JOIN_LINES,
          CommandId.UPPER_CASE,
          CommandId.LOWER_CASE,
          CommandId.INDENT,
          CommandId.OUTDENT,
          CommandId.TOGGLE_LINE_COMMENT,
          CommandId.TOGGLE_BLOCK_COMMENT,
          CommandId.INSERT_TAB,
          CommandId.REPLACE,
          CommandId.SORT_LINES_ASCENDING,
          CommandId.SORT_LINES_DESCENDING,
          CommandId.SORT_LINES_IGNORE_CASE,
          CommandId.REMOVE_DUPLICATE_LINES,
          CommandId.TRIM_TRAILING_WHITESPACE,
          CommandId.COMPLETE_WORD);

  /**
   * A grammar as the Syntax menu lists it.
   *
   * @param id the grammar's id
   * @param name its name
   * @param checked true for the selected document's grammar
   */
  public record SyntaxChoice(String id, String name, boolean checked) {}

  /**
   * An open document as the Window menu lists it.
   *
   * @param text its path, or its title when it has no file
   * @param shown true for the selected document
   */
  public record OpenedDocument(String text, boolean shown) {}

  /**
   * The enabled items of a tab's context menu; Close and Close All are always enabled.
   *
   * @param closeOthers another tab is open
   * @param file the tab has a file: the path items
   */
  public record TabItems(boolean closeOthers, boolean file) {}

  private final ApplicationController application;

  /**
   * Reads state from a controller.
   *
   * @param application the window's controller
   */
  public CommandStates(ApplicationController application) {
    this.application = application;
  }

  /**
   * Reports whether a command's item is checked.
   *
   * @param command any command
   * @return false for a command that is not {@link CommandId#check()}
   */
  public boolean checked(CommandId command) {
    ThemeMode theme = application.theme().mode();
    return switch (command) {
      case WORD_WRAP -> application.documents().wrap();
      case THEME_LIGHT -> theme == ThemeMode.LIGHT;
      case THEME_DARK -> theme == ThemeMode.DARK;
      case THEME_SYSTEM -> theme == ThemeMode.SYSTEM;
      case DEBUG_LOG_FILE -> application.debugLogFile();
      case SPLIT_HORIZONTAL ->
          application.documents().activeSplit() == DocumentController.Split.HORIZONTAL;
      case SPLIT_VERTICAL ->
          application.documents().activeSplit() == DocumentController.Split.VERTICAL;
      case LINE_ENDING_CRLF -> uniformEnding() == LineEnding.CRLF;
      case LINE_ENDING_LF -> uniformEnding() == LineEnding.LF;
      case LINE_ENDING_CR -> uniformEnding() == LineEnding.CR;
      case TAB_WIDTH_2, TAB_WIDTH_4, TAB_WIDTH_8 -> tabWidthChecked(command);
      case INDENT_WITH_SPACES -> document() != null && document().insertSpaces();
      case READ_ONLY -> document() != null && document().isReadOnly();
      case COLUMN_MODE ->
          application.documents().activeEditor() != null
              && application.documents().activeEditor().columnMode();
      case SHOW_WHITESPACE -> application.documents().whitespaceShown();
      case SHOW_LINE_ENDINGS -> application.documents().lineEndingsShown();
      case SEARCH_RESULTS -> application.search().resultsShown();
      case OVERVIEW_STRIP -> application.documents().overviewShown();
      default -> false;
    };
  }

  /**
   * Reports whether a command's item is enabled.
   *
   * @param command any command
   * @return true unless the command needs a document, a file or a state the document lacks
   */
  public boolean enabled(CommandId command) {
    if (command == CommandId.REOPEN_CLOSED) {
      return application.documents().canReopenClosed();
    }
    if (WINDOW_COMMANDS.contains(command) || command.group() == CommandGroup.HELP) {
      return switch (command) {
        case ZOOM_IN -> application.fontStep() < ApplicationController.MAX_FONT_STEP;
        case ZOOM_OUT -> application.fontStep() > ApplicationController.MIN_FONT_STEP;
        case NEXT_RESULT, PREVIOUS_RESULT -> application.search().hasResults();
        default -> true;
      };
    }
    DocumentController documents = application.documents();
    Document document = documents.activeDocument();
    if (document == null) {
      return false;
    }
    boolean binary = documents.activeBinary();
    if (command == CommandId.TOGGLE_LINE_COMMENT || command == CommandId.TOGGLE_BLOCK_COMMENT) {
      Grammar.Comments comments = documents.activeEditor().comments();
      boolean block = comments.hasBlock();
      return !document.isReadOnly()
          && (command == CommandId.TOGGLE_BLOCK_COMMENT ? block : block || comments.line() != null);
    }
    if (EDITS.contains(command)) {
      return !document.isReadOnly();
    }
    return switch (command) {
      case SAVE, SAVE_AS, READ_ONLY -> !binary;
      case SAVE_ALL -> documents.anyDirty();
      case NEXT_TAB, PREVIOUS_TAB, CLOSE_OTHERS -> documents.count() > 1;
      case UNSPLIT, FOCUS_OTHER_VIEW -> documents.activeSplit() != DocumentController.Split.NONE;
      case FOLD, TOGGLE_FOLD, FOLD_ALL ->
          documents.activeEditor() != null
              && documents.activeEditor().projection().foldRegionCount() > 0;
      case UNFOLD, UNFOLD_ALL ->
          documents.activeEditor() != null
              && documents.activeEditor().projection().collapsedCount() > 0;
      case NEXT_BOOKMARK, PREVIOUS_BOOKMARK, CLEAR_BOOKMARKS ->
          documents.activeBookmarks().length > 0;
      default -> true;
    };
  }

  /**
   * Returns the entries of View > Syntax and the status bar's language popup.
   *
   * @return the menu; {@link SyntaxMenu#EMPTY} without a document or before the grammars load
   */
  public SyntaxMenu syntaxMenu() {
    DocumentController documents = application.documents();
    List<Grammar> grammars = documents.grammarChoices();
    if (documents.activeDocument() == null || grammars.isEmpty()) {
      return SyntaxMenu.EMPTY;
    }
    Grammar active = documents.activeGrammar();
    return SyntaxMenu.of(grammars, documents.recentGrammars(), active == null ? null : active.id());
  }

  /**
   * Reports whether Encoding > Reopen with Encoding is enabled.
   *
   * @return true for a clean text document with a file
   */
  public boolean reopenEnabled() {
    DocumentController documents = application.documents();
    Document document = documents.activeDocument();
    return document != null
        && documents.activePath() != null
        && !documents.activeBinary()
        && !document.isDirty();
  }

  /**
   * Reports whether Encoding > Save with Encoding is enabled.
   *
   * @return true for a document that is not a binary file's view
   */
  public boolean saveEncodingEnabled() {
    DocumentController documents = application.documents();
    return documents.activeDocument() != null && !documents.activeBinary();
  }

  /**
   * Returns the charset name Save with Encoding checks.
   *
   * @return the charset the selected document's next save writes, or empty without a document
   */
  public String saveEncoding() {
    Document document = application.documents().activeDocument();
    return document == null ? "" : document.encoding().charset().name();
  }

  /**
   * Lists the open documents in tab order.
   *
   * @return one entry per tab
   */
  public List<OpenedDocument> openedDocuments() {
    DocumentController documents = application.documents();
    int shown = documents.activeIndex();
    List<String> labels = documents.labels();
    List<OpenedDocument> list = new ArrayList<OpenedDocument>(labels.size());
    for (int i = 0; i < labels.size(); i++) {
      list.add(new OpenedDocument(labels.get(i), i == shown));
    }
    return list;
  }

  /**
   * Returns the enabled items of a tab's context menu.
   *
   * @param tab the tab's input
   * @return the state
   */
  public TabItems tabItems(TabInput tab) {
    DocumentController documents = application.documents();
    return new TabItems(documents.count() > 1, documents.hasFile(documents.indexOf(tab)));
  }

  /**
   * Returns the status bar's fields for the selected document. Costs O(column) for the caret's line
   * and O(selections) for the selection count.
   *
   * @return the fields, {@link StatusFields#NONE} without a document
   */
  public StatusFields status() {
    DocumentController documents = application.documents();
    Document document = documents.activeDocument();
    EditorController editor = documents.activeEditor();
    if (document == null || editor == null) {
      return StatusFields.NONE;
    }
    TextSnapshot text = document.text();
    SelectionSet set = editor.selections().selections();
    int caret = set.primary().caret();
    int line = text.lineAt(caret);
    LineView view = text.lineView(line, new LineView());
    int index = Math.min(caret - view.start(), view.length());
    int column = Columns.column(view, index, document.tabWidth());
    long selected = 0;
    for (Selection s : set.selections()) {
      selected += s.end() - s.start();
    }
    String selection =
        set.size() > 1
            ? UiText.STATUS_SELECTION_CARETS.format(selected, set.size())
            : selected > 0 ? UiText.STATUS_SELECTION.format(selected) : "";
    TextEncoding encoding = document.encoding();
    String charset = encoding.charset().name();
    String readOnly =
        documents.activeBinary()
            ? UiText.STATUS_BINARY.text()
            : document.isReadOnly() ? UiText.STATUS_READ_ONLY.text() : "";
    return new StatusFields(
        UiText.STATUS_POSITION.format(line + 1, column + 1),
        selection,
        encoding.bom() ? UiText.STATUS_BOM.format(charset) : charset,
        endingText(document.lineEndings(), document.lineEnding()),
        language(documents.activeGrammar()),
        readOnly);
  }

  private static String language(Grammar grammar) {
    return grammar == null || GrammarSet.PLAIN.equals(grammar.id())
        ? UiText.STATUS_PLAIN_TEXT.text()
        : grammar.name();
  }

  private static String endingText(LineEndingStats stats, LineEnding inserted) {
    if (stats.isMixed()) {
      return UiText.STATUS_MIXED.text();
    }
    return switch (stats.dominant(inserted)) {
      case CRLF -> UiText.STATUS_CRLF.text();
      case LF -> UiText.STATUS_LF.text();
      case CR -> UiText.STATUS_CR.text();
    };
  }

  private Document document() {
    return application.documents().activeDocument();
  }

  private boolean tabWidthChecked(CommandId command) {
    int width =
        switch (command) {
          case TAB_WIDTH_2 -> 2;
          case TAB_WIDTH_4 -> 4;
          default -> 8;
        };
    return document() != null && document().tabWidth() == width;
  }

  /** Returns the ending every break has, the inserted one without breaks, or null when mixed. */
  private LineEnding uniformEnding() {
    Document document = application.documents().activeDocument();
    if (document == null || document.lineEndings().isMixed()) {
      return null;
    }
    return document.lineEndings().dominant(document.lineEnding());
  }
}
