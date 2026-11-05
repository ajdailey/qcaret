package io.github.ajdailey.qcaret.core.edit;

import io.github.ajdailey.qcaret.core.buffer.TextContent;
import io.github.ajdailey.qcaret.core.document.BlockRow;
import io.github.ajdailey.qcaret.core.document.BlockSelection;
import io.github.ajdailey.qcaret.core.document.Columns;
import io.github.ajdailey.qcaret.core.document.EditBatch;
import io.github.ajdailey.qcaret.core.document.Replacement;
import io.github.ajdailey.qcaret.core.document.Selection;
import io.github.ajdailey.qcaret.core.document.SelectionSet;
import java.util.ArrayList;
import java.util.List;

/**
 * Edits in a {@link BlockSelection}, Notepad++ column mode: typing replaces each row's range and
 * pads a row that ends left of the block with spaces, so the text lands in the block's column. The
 * block stays a block, as wide as nothing (a tall caret) after the column of the typed text.
 */
public final class BlockEdits {
  private BlockEdits() {}

  /**
   * A block edit's batch and the block after it.
   *
   * @param batch the replacements
   * @param after the block after the batch
   */
  public record Result(EditBatch batch, BlockSelection after) {}

  /**
   * Replaces each row's range with typed text.
   *
   * @param text the text
   * @param block the block
   * @param tabWidth columns per tab stop
   * @param typed the typed chars, without a line break
   * @param kind {@link EditBatch.Kind#TYPING} for a keystroke, else {@link EditBatch.Kind#COMMAND}
   * @return the edit
   */
  public static Result type(
      TextContent text, BlockSelection block, int tabWidth, String typed, EditBatch.Kind kind) {
    int left = left(block);
    List<Replacement> out = new ArrayList<Replacement>();
    for (BlockRow row : block.rows(text, tabWidth)) {
      out.add(
          new Replacement(row.start(), row.end() - row.start(), " ".repeat(row.padding()) + typed));
    }
    String laidOut = " ".repeat(left) + typed;
    int column = Columns.column(laidOut, laidOut.length(), tabWidth);
    return new Result(EditBatch.of(kind, out), moved(block, column, column));
  }

  /**
   * Backspace: deletes each row's range, or with a block as wide as nothing, the char before it on
   * every row whose line reaches the block's column.
   *
   * @param text the text
   * @param block the block
   * @param tabWidth columns per tab stop
   * @return the edit, or null at column 0 of an empty-width block
   */
  public static Result backspace(TextContent text, BlockSelection block, int tabWidth) {
    int left = left(block);
    if (left < right(block)) {
      return delete(text, block, tabWidth);
    }
    if (left == 0) {
      return null;
    }
    List<Replacement> out = new ArrayList<Replacement>();
    for (BlockRow row : block.rows(text, tabWidth)) {
      if (row.padding() == 0 && row.start() > text.offsetOfLine(row.line())) {
        int from = Moves.left(text, row.start());
        out.add(new Replacement(from, row.start() - from, ""));
      }
    }
    return new Result(
        EditBatch.of(EditBatch.Kind.BACKSPACE, out), moved(block, left - 1, left - 1));
  }

  /**
   * Delete: deletes each row's range, or with a block as wide as nothing, the char after it on
   * every row whose line extends past the block's column.
   *
   * @param text the text
   * @param block the block
   * @param tabWidth columns per tab stop
   * @return the edit
   */
  public static Result delete(TextContent text, BlockSelection block, int tabWidth) {
    int left = left(block);
    boolean empty = left == right(block);
    List<Replacement> out = new ArrayList<Replacement>();
    for (BlockRow row : block.rows(text, tabWidth)) {
      int end = row.end();
      if (empty && row.padding() == 0 && row.start() < Lines.end(text, row.line())) {
        end = Moves.right(text, row.start());
      }
      if (end > row.start()) {
        out.add(new Replacement(row.start(), end - row.start(), ""));
      }
    }
    EditBatch.Kind kind = empty ? EditBatch.Kind.DELETE : EditBatch.Kind.COMMAND;
    return new Result(EditBatch.of(kind, out), moved(block, left, left));
  }

  /**
   * Replaces row i's range with {@code parts.get(i)}, padded as typing pads; the carets land after
   * each part, out of block mode.
   *
   * @param text the text
   * @param block the block
   * @param tabWidth columns per tab stop
   * @param parts one text per row, top first
   * @return the edit
   * @throws IllegalArgumentException when the count differs from the rows
   */
  public static Edit paste(
      TextContent text, BlockSelection block, int tabWidth, List<String> parts) {
    List<BlockRow> rows = block.rows(text, tabWidth);
    if (parts.size() != rows.size()) {
      throw new IllegalArgumentException(parts.size() + " parts for " + rows.size() + " rows");
    }
    List<Replacement> out = new ArrayList<Replacement>(rows.size());
    List<Selection> after = new ArrayList<Selection>(rows.size());
    int primary = 0;
    int shift = 0;
    for (int i = 0; i < rows.size(); i++) {
      BlockRow row = rows.get(i);
      String inserted = " ".repeat(row.padding()) + parts.get(i);
      out.add(new Replacement(row.start(), row.end() - row.start(), inserted));
      after.add(Selection.caret(row.start() + shift + inserted.length()));
      shift += inserted.length() - (row.end() - row.start());
      if (row.line() == block.caretLine()) {
        primary = i;
      }
    }
    return new Edit(EditBatch.of(EditBatch.Kind.COMMAND, out), SelectionSet.of(after, primary));
  }

  /**
   * Returns each row's text, top first; a row past its line's end is empty.
   *
   * @param text the text
   * @param block the block
   * @param tabWidth columns per tab stop
   * @return the rows' text
   */
  public static List<String> rowsText(TextContent text, BlockSelection block, int tabWidth) {
    List<String> out = new ArrayList<String>();
    for (BlockRow row : block.rows(text, tabWidth)) {
      out.add(text.subSequence(row.start(), row.end()).toString());
    }
    return out;
  }

  private static int left(BlockSelection block) {
    return Math.min(block.anchorColumn(), block.caretColumn());
  }

  private static int right(BlockSelection block) {
    return Math.max(block.anchorColumn(), block.caretColumn());
  }

  private static BlockSelection moved(BlockSelection block, int anchorColumn, int caretColumn) {
    return new BlockSelection(block.anchorLine(), anchorColumn, block.caretLine(), caretColumn);
  }
}
