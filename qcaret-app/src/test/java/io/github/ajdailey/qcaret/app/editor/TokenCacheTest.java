package io.github.ajdailey.qcaret.app.editor;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;

import io.github.ajdailey.qcaret.app.theme.TokenRole;
import io.github.ajdailey.qcaret.core.buffer.TextSnapshot;
import io.github.ajdailey.qcaret.core.document.Document;
import io.github.ajdailey.qcaret.core.document.DocumentChange;
import io.github.ajdailey.qcaret.core.document.DocumentListener;
import io.github.ajdailey.qcaret.core.document.EditBatch;
import io.github.ajdailey.qcaret.core.document.UndoLimits;
import io.github.ajdailey.qcaret.core.syntax.TokenType;
import io.github.ajdailey.qcaret.core.text.LineEnding;
import io.github.ajdailey.qcaret.core.text.TextEncoding;
import java.nio.charset.StandardCharsets;
import org.junit.jupiter.api.Test;

/** The painted tokens through edits, and the role of each token type. */
class TokenCacheTest {
  private static final int[] LINE = {0, 3, 1, 4, 9, 2};

  /** Applies one edit and shifts the cache through it. */
  private static void edit(final TokenCache cache, final Document document, EditBatch batch) {
    final TextSnapshot before = document.text();
    DocumentListener listener =
        new DocumentListener() {
          @Override
          public void textChanged(DocumentChange change) {
            cache.apply(change.edits(), before, document.text().lineCount());
          }

          @Override
          public void dirtyChanged(boolean dirty) {
            // Not used.
          }
        };
    document.addListener(listener);
    document.apply(batch);
    document.removeListener(listener);
  }

  private static Document document(String text) {
    return Document.of(
        text, TextEncoding.of(StandardCharsets.UTF_8, false), LineEnding.LF, UndoLimits.DEFAULTS);
  }

  @Test
  void everyTokenTypeHasTheRoleOfItsName() {
    for (TokenType type : TokenType.values()) {
      assertEquals(type.name(), TokenRole.values()[TokenCache.role(type.ordinal())].name());
    }
  }

  @Test
  void typingInALineMovesAndStretchesItsTokens() {
    TokenCache cache = new TokenCache(10);
    Document document = document("abc defgh\nnext");
    cache.put(0, LINE);
    cache.put(1, new int[] {0, 4, 3});
    edit(cache, document, EditBatch.replace(5, 0, "XY"));
    assertArrayEquals(new int[] {0, 3, 1, 4, 11, 2}, cache.get(0));
    edit(cache, document, EditBatch.replace(0, 1, ""));
    assertArrayEquals(new int[] {0, 2, 1, 3, 10, 2}, cache.get(0));
    assertArrayEquals(new int[] {0, 4, 3}, cache.get(1));
  }

  @Test
  void aBreakSplitsTheLineAndMovesTheLinesBelow() {
    TokenCache cache = new TokenCache(10);
    Document document = document("abc defgh\nnext");
    cache.put(0, LINE);
    cache.put(1, new int[] {0, 4, 3});
    edit(cache, document, EditBatch.replace(6, 0, "\n\n  "));
    assertArrayEquals(new int[] {0, 3, 1, 4, 6, 2}, cache.get(0));
    assertNull(cache.get(1));
    assertArrayEquals(new int[] {2, 5, 2}, cache.get(2));
    assertArrayEquals(new int[] {0, 4, 3}, cache.get(3));
  }

  @Test
  void removingABreakDropsTheJoinedLines() {
    TokenCache cache = new TokenCache(10);
    Document document = document("a\nb\nc\nd");
    for (int line = 0; line < 4; line++) {
      cache.put(line, new int[] {0, 1, line});
    }
    edit(cache, document, EditBatch.replace(1, 3, ""));
    assertNull(cache.get(0));
    assertArrayEquals(new int[] {0, 1, 3}, cache.get(1));
    assertEquals(1, cache.size());
  }

  @Test
  void aCrAndLfThatJoinDropEveryLine() {
    TokenCache cache = new TokenCache(10);
    Document document = document("a\rb\nc");
    cache.put(2, new int[] {0, 1, 1});
    edit(cache, document, EditBatch.replace(2, 0, "\n"));
    assertEquals(0, cache.size());
  }

  @Test
  void theLeastRecentlyUsedLineGoesFirst() {
    TokenCache cache = new TokenCache(2);
    cache.put(0, LINE);
    cache.put(1, LINE);
    cache.get(0);
    cache.put(2, LINE);
    assertNull(cache.get(1));
    assertArrayEquals(LINE, cache.get(0));
    cache.remove(0, 5);
    assertEquals(0, cache.size());
  }
}
