package io.github.ajdailey.qcaret.core.text;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.nio.charset.Charset;
import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;

class FileSettingsTest {
  private static final Charset CP1252 = Charset.forName("windows-1252");

  private static FileSettings of(Modeline modeline, String... pairs) {
    Map<String, String> p = new HashMap<String, String>();
    for (int i = 0; i < pairs.length; i += 2) {
      p.put(pairs[i], pairs[i + 1]);
    }
    EditorConfig.defaults(p);
    return FileSettings.of(
        new EditorConfig.Result(p, List.of(Path.of(".editorconfig")), List.of()), modeline);
  }

  /** Rows: properties, then the width and spaces they give. */
  @Test
  void widthAndIndentationTable() {
    Object[][] rows = {
      {new String[] {"indent_style", "space", "indent_size", "2", "tab_width", "8"}, 2, true},
      {new String[] {"indent_style", "tab", "indent_size", "2", "tab_width", "8"}, 8, false},
      {new String[] {"indent_style", "tab", "indent_size", "3"}, 3, false},
      {new String[] {"indent_style", "space"}, 0, true},
      {new String[] {"indent_size", "tab", "tab_width", "5"}, 5, null},
      {new String[] {"tab_width", "6"}, 6, null},
      {new String[] {"indent_size", "40"}, 0, null},
    };
    for (Object[] row : rows) {
      FileSettings s = of(Modeline.NONE, (String[]) row[0]);
      assertEquals(row[1], s.tabWidth(), String.join(",", (String[]) row[0]));
      assertEquals(row[2], s.insertSpaces(), String.join(",", (String[]) row[0]));
    }
  }

  @Test
  void aModelineReplacesEditorConfig() {
    Modeline vim = new Modeline(2, Boolean.FALSE, LineEnding.CR, CP1252, "vim");
    FileSettings s =
        of(
            vim,
            "indent_style",
            "space",
            "indent_size",
            "4",
            "end_of_line",
            "lf",
            "charset",
            "utf-8",
            "trim_trailing_whitespace",
            "true",
            "insert_final_newline",
            "false");
    assertEquals(2, s.tabWidth());
    assertEquals(Boolean.FALSE, s.insertSpaces());
    assertEquals(LineEnding.CR, s.lineEnding());
    assertEquals(TextEncoding.of(CP1252, false), s.encoding());
    assertTrue(s.trimTrailing());
    assertFalse(s.finalNewline());
    assertEquals("vim", s.modeline());
  }

  @Test
  void charsetValues() {
    assertEquals(TextEncoding.of(StandardCharsets.UTF_8, true), FileSettings.encoding("utf-8-bom"));
    assertEquals(
        TextEncoding.of(StandardCharsets.ISO_8859_1, false), FileSettings.encoding("latin1"));
    assertNull(FileSettings.encoding("koi8-r"));
    assertEquals(
        StandardCharsets.UTF_8, of(Modeline.NONE, "charset", "utf-8-bom").declaredCharset());
    assertNull(of(Modeline.NONE, "charset", "utf-16le").declaredCharset());
    assertEquals(
        TextEncoding.of(StandardCharsets.UTF_16LE, true),
        of(Modeline.NONE, "charset", "utf-16le").encoding());
  }

  @Test
  void noneIsNotAny() {
    assertFalse(FileSettings.NONE.any());
    assertFalse(FileSettings.of(EditorConfig.Result.NONE, Modeline.NONE).any());
    assertTrue(of(Modeline.NONE).any());
  }
}
