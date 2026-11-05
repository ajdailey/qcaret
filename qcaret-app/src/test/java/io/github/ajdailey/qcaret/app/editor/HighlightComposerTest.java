package io.github.ajdailey.qcaret.app.editor;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;

import io.github.ajdailey.qcaret.app.theme.TokenRole;
import org.junit.jupiter.api.Test;

/** Composing tokens, find matches and secondary selections into a row's runs. */
class HighlightComposerTest {
  private static final int NONE = HighlightComposer.NONE;
  private static final int TYPE = TokenRole.TYPE.ordinal();
  private static final int NUMBER = TokenRole.NUMBER.ordinal();
  private static final int MATCH = HighlightComposer.Background.FIND_MATCH.ordinal();
  private static final int SELECTION = HighlightComposer.Background.SELECTION.ordinal();

  /** {@code int x = 1;} at offset 100: a type and a number token. */
  private static HighlightComposer composer() {
    HighlightComposer composer = new HighlightComposer();
    composer.setSyntax(
        new TokenSource() {
          @Override
          public int[] tokens(int line) {
            return line == 0 ? new int[] {0, 3, TYPE, 8, 9, NUMBER} : null;
          }
        });
    return composer;
  }

  @Test
  void tokensMatchesAndSelectionsLayerInOrder() {
    HighlightComposer composer = composer();
    composer.setMatches(new int[] {101, 105, 200, 210});
    assertArrayEquals(
        new int[] {
          0, 1, TYPE, NONE,
          1, 2, TYPE, MATCH,
          3, 2, NONE, MATCH,
          8, 1, NUMBER, SELECTION,
          9, 1, NONE, SELECTION
        },
        composer.compose(0, 100, 100, 110, new int[] {108, 110}));
  }

  @Test
  void aRowPartIsComposedRelativeToItsStart() {
    HighlightComposer composer = composer();
    assertArrayEquals(
        new int[] {0, 1, TYPE, NONE, 6, 1, NUMBER, NONE},
        composer.compose(0, 100, 102, 109, new int[0]));
  }

  @Test
  void withoutTokensOnlyMatchesShow() {
    HighlightComposer composer = composer();
    composer.setMatches(new int[] {204, 206});
    assertArrayEquals(
        new int[] {4, 2, NONE, MATCH}, composer.compose(1, 200, 200, 210, new int[0]));
    composer.setSyntax(null);
    assertArrayEquals(new int[0], composer.compose(0, 100, 100, 110, new int[0]));
  }
}
