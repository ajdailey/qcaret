package io.github.ajdailey.qcaret.core.text;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;

import java.nio.charset.Charset;
import java.nio.charset.StandardCharsets;
import org.junit.jupiter.api.Test;

class ModelinesTest {
  private static Modeline parse(String text) {
    String tail = text.substring(Math.max(0, text.length() - Modelines.TAIL_CHARS));
    return Modelines.parse(text, tail);
  }

  private static String lines(int count) {
    return "x\n".repeat(count);
  }

  private static void assertModeline(
      int width, Boolean spaces, LineEnding ending, String charset, String kind, Modeline m) {
    assertEquals(width, m.tabWidth(), "width");
    assertEquals(spaces, m.insertSpaces(), "spaces");
    assertEquals(ending, m.lineEnding(), "ending");
    assertEquals(charset, m.charset() == null ? null : m.charset().name(), "charset");
    assertEquals(kind, m.kind(), "kind");
  }

  @Test
  void vimSetFormAndPlainForm() {
    assertModeline(
        4,
        true,
        LineEnding.CRLF,
        "ISO-8859-1",
        "vim",
        parse("/* vim: set ts=8 sw=4 et ff=dos fenc=latin1 : */\nint x;\n"));
    assertModeline(8, false, null, null, "vim", parse("# vi:ts=8:noet\n"));
    assertModeline(
        2, true, LineEnding.CR, null, "vim", parse("a\n# vim600: sts=2 sw=4 et ff=mac\n"));
    assertModeline(3, null, null, null, "vim", parse("x ex: tabstop=3\n"));
  }

  @Test
  void vimNeedsABlankBeforeAndASetFormNeedsItsColon() {
    assertEquals(Modeline.NONE, parse("navi: ts=4\n"));
    assertEquals(Modeline.NONE, parse("ex: ts=4\n"));
    assertEquals(Modeline.NONE, parse("# vim: set ts=4\n"));
    assertEquals(Modeline.NONE, parse("# vim: this is prose\n"));
  }

  @Test
  void aModelineCountsInTheFirstAndLastFiveLinesOnly() {
    String line = "# vim: ts=3\n";
    assertEquals(3, parse(line + lines(20)).tabWidth());
    assertEquals(3, parse(lines(4) + line + lines(20)).tabWidth());
    assertEquals(Modeline.NONE, parse(lines(5) + line + lines(20)));
    assertEquals(3, parse(lines(20) + line).tabWidth());
    assertEquals(3, parse(lines(20) + line + lines(4)).tabWidth());
    assertEquals(Modeline.NONE, parse(lines(20) + line + lines(5)));
  }

  @Test
  void emacsFirstLineAfterAShebangAndLocalVariables() {
    assertModeline(
        4,
        true,
        LineEnding.CRLF,
        "UTF-8",
        "emacs",
        parse(
            "#!/bin/sh\n# -*- mode: sh; tab-width: 4; indent-tabs-mode: nil; coding: utf-8-dos -*-\n"));
    assertEquals(Modeline.NONE, parse("x\n# -*- tab-width: 4 -*-\n"));
    assertEquals(Modeline.NONE, parse("# -*- c -*-\n"));
    String block =
        "/* Local Variables: */\n"
            + "/* tab-width: 6 */\n"
            + "/* indent-tabs-mode: t */\n"
            + "/* coding: latin-1 */\n"
            + "/* End: */\n";
    assertModeline(6, false, null, "ISO-8859-1", "emacs", parse(lines(30) + block));
    assertEquals(Modeline.NONE, parse(lines(30) + block.replace("/* End: */\n", "")));
  }

  @Test
  void vimReplacesEmacsAndValuesOutOfRangeAreIgnored() {
    assertModeline(
        2, false, null, null, "vim, emacs", parse("# -*- tab-width: 4 -*-\n# vim: ts=2 noet\n"));
    assertModeline(0, true, null, null, "vim", parse("# vim: ts=40 sw=0 et\n"));
  }

  @Test
  void theCodingLineIsReadFromTheFirstTwoLines() {
    assertEquals("utf-8", Modelines.codingLine("#!/usr/bin/python\n# -*- coding: utf-8 -*-\n"));
    assertEquals("latin-1", Modelines.codingLine("# vim: set fileencoding=latin-1 :\n"));
    assertNull(Modelines.codingLine("a\nb\n# coding: utf-8\n"));
    assertEquals(StandardCharsets.ISO_8859_1, Modelines.charset("latin-1"));
    assertEquals(Charset.forName("windows-1252"), Modelines.charset("windows-1252"));
    assertNull(Modelines.charset("no-such-charset"));
  }
}
