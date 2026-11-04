package io.github.ajdailey.qcaret.app.controller;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertSame;

import io.github.ajdailey.qcaret.core.text.TextEncoding;
import java.nio.charset.Charset;
import java.util.ArrayList;
import java.util.List;
import org.junit.jupiter.api.Test;

/** The Encoding menu's lists: the common charsets first, every runtime charset once. */
class EncodingChoicesTest {
  @Test
  void theCommonCharsetsComeFirstAndEveryCharsetOnce() {
    EncodingChoices choices = EncodingChoices.available();
    assertEquals(TextEncoding.MENU.size(), choices.common().size());
    assertEquals("UTF-8", choices.common().get(0).name());
    List<Charset> all = new ArrayList<Charset>(choices.common());
    for (Charset charset : choices.other()) {
      assertFalse(all.contains(charset), charset.name());
      all.add(charset);
    }
    assertEquals(Charset.availableCharsets().size(), all.size());
    assertSame(choices, EncodingChoices.available());
  }

  @Test
  void anUnsupportedCommonNameIsLeftOut() {
    List<Charset> some = List.of(Charset.forName("UTF-8"), Charset.forName("IBM437"));
    EncodingChoices choices = EncodingChoices.of(some);
    assertEquals(List.of(Charset.forName("UTF-8")), choices.common());
    assertEquals(List.of(Charset.forName("IBM437")), choices.other());
  }
}
