package io.github.ajdailey.qcaret.ui.swt.editor;

import io.github.ajdailey.qcaret.core.text.ControlChars;
import org.eclipse.swt.custom.StyledTextContent;
import org.eclipse.swt.custom.TextChangeListener;

/**
 * A read-only widget's content over another: {@link #getLine} gives {@link ControlChars#shown}
 * lines for layout and painting, every other method the original, so copy reads the original chars.
 */
public final class ShownContent implements StyledTextContent {
  private final StyledTextContent original;

  /**
   * Wraps a content.
   *
   * @param original the content the widget holds
   */
  public ShownContent(StyledTextContent original) {
    this.original = original;
  }

  /**
   * Returns a line with its control chars.
   *
   * @param lineIndex the line
   * @return the original line
   */
  public String original(int lineIndex) {
    return original.getLine(lineIndex);
  }

  @Override
  public String getLine(int lineIndex) {
    return ControlChars.shown(original.getLine(lineIndex));
  }

  @Override
  public void addTextChangeListener(TextChangeListener listener) {
    original.addTextChangeListener(listener);
  }

  @Override
  public void removeTextChangeListener(TextChangeListener listener) {
    original.removeTextChangeListener(listener);
  }

  @Override
  public int getCharCount() {
    return original.getCharCount();
  }

  @Override
  public int getLineAtOffset(int offset) {
    return original.getLineAtOffset(offset);
  }

  @Override
  public int getLineCount() {
    return original.getLineCount();
  }

  @Override
  public String getLineDelimiter() {
    return original.getLineDelimiter();
  }

  @Override
  public int getOffsetAtLine(int lineIndex) {
    return original.getOffsetAtLine(lineIndex);
  }

  @Override
  public String getTextRange(int start, int length) {
    return original.getTextRange(start, length);
  }

  @Override
  public void replaceTextRange(int start, int replaceLength, String text) {
    original.replaceTextRange(start, replaceLength, text);
  }

  @Override
  public void setText(String text) {
    original.setText(text);
  }
}
