package io.github.ajdailey.qcaret.app.macro;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import io.github.ajdailey.qcaret.app.command.CommandId;
import io.github.ajdailey.qcaret.app.command.KeyStroke;
import io.github.ajdailey.qcaret.core.edit.NumberSequence;
import java.util.ArrayList;
import java.util.List;
import java.util.Random;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.function.Executable;

/**
 * Macro text: every step kind round-trips, strings with control chars, CR, LF, tabs, quotes and
 * surrogate pairs included; one bad line refuses the whole text; generated step lists round-trip.
 */
class MacroFormatTest {
  private static final MacroStep.Options OFF = new MacroStep.Options(false, false, false);
  private static final MacroStep.Options ON = new MacroStep.Options(true, true, true);

  /** A string with every char class the escapes cover. */
  private static final String AWKWARD = "a\"b\\c\r\n\t\u0000\u001f\u007f \uD83D\uDE00 /\u00e9";

  private static List<MacroStep> everyKind() {
    return List.of(
        new MacroStep.Command(CommandId.CARET_DOWN),
        new MacroStep.Command(CommandId.UNDO),
        new MacroStep.Typed(AWKWARD),
        new MacroStep.Insert("\u3042\u3044"),
        new MacroStep.Complete("alphabet"),
        new MacroStep.Find("a.b", MacroStep.Direction.FORWARD, OFF),
        new MacroStep.Find("x", MacroStep.Direction.BACKWARD, ON),
        new MacroStep.Find(AWKWARD, MacroStep.Direction.INCREMENTAL, OFF),
        new MacroStep.Replace("f", "", ON, false),
        new MacroStep.Replace("(a)", "$1\n" + AWKWARD, OFF, true),
        new MacroStep.GoToLine(120, 1),
        new MacroStep.GoToLine(3, 7),
        new MacroStep.TabWidth(6),
        new MacroStep.DateTime("yyyy-MM-dd'T'HH:mm"),
        new MacroStep.Sequence(new NumberSequence(-5, 3, 4, NumberSequence.Base.HEX_UPPER)),
        new MacroStep.Sequence(new NumberSequence(1, 1, 0, NumberSequence.Base.DECIMAL)));
  }

  @Test
  void everyStepKindRoundTrips() throws Exception {
    Macro macro = new Macro("m", KeyStroke.parse("Mod+Shift+1"), everyKind());
    String text = MacroFormat.write(macro);
    assertTrue(text.startsWith(MacroFormat.HEADER + "\nkey Mod+Shift+1\n"), text);
    assertTrue(text.contains("\ngo-to-line 120\ngo-to-line 3:7\n"), text);
    assertTrue(text.contains("\nnumber-sequence start=-5 step=3 pad=4 base=hexUpper\n"), text);
    assertEquals(-1, text.indexOf('\r'));
    assertEquals(macro, MacroFormat.parse(text, "m"));
    assertEquals(macro, MacroFormat.parse(text.replace("\n", "\r\n"), "m"));
  }

  @Test
  void commentsBlankLinesAndAByteOrderMarkAreSkipped() throws Exception {
    Macro macro =
        MacroFormat.parse(
            "\uFEFFqcaret-macro 1\n\n# a comment\n  command CARET_LEFT  \n\ttype \"a b\"\n", null);
    assertNull(macro.key());
    assertEquals(
        List.of(new MacroStep.Command(CommandId.CARET_LEFT), new MacroStep.Typed("a b")),
        macro.steps());
  }

  @Test
  void oneBadLineRefusesTheWholeText() {
    String good = MacroFormat.HEADER + "\ncommand CARET_LEFT\n";
    String[] bad = {
      "command NOT_A_COMMAND",
      "command SAVE",
      "command FIND_NEXT",
      "jump 3",
      "type unquoted",
      "type \"open",
      "type \"bad \\x escape\"",
      "type \"lone \\ud83d\"",
      "type \"\"",
      "find \"a\" sideways case=on word=on regex=on",
      "find \"a\" forward case=yes word=on regex=on",
      "find \"a\" forward word=on case=on regex=on",
      "replace \"a\" case=on word=on regex=on",
      "go-to-line 0",
      "go-to-line 3:0",
      "go-to-line 1:2:3",
      "tab-width 17",
      "tab-width x",
      "date-time \"{\"",
      "number-sequence start=1 step=1 pad=65 base=decimal",
      "number-sequence start=1 step=1 pad=1 base=octal",
      "number-sequence start=99999999999999999999 step=1 pad=1 base=decimal",
      "key Mod+",
      "key Mod+1 Mod+2",
      "\"quoted\" verb",
      "type \"a\"b",
    };
    for (final String line : bad) {
      final String text = good + line + "\ncommand CARET_RIGHT\n";
      MacroFormat.Refusal refusal =
          assertThrows(
              MacroFormat.Refusal.class,
              new Executable() {
                @Override
                public void execute() throws Throwable {
                  MacroFormat.parse(text, null);
                }
              },
              line);
      assertEquals(3, refusal.line(), line);
    }
  }

  @Test
  void theHeaderAndOneStepAreRequired() {
    for (final String text :
        new String[] {
          "",
          "command CARET_LEFT\n",
          "qcaret-macro 2\ncommand CARET_LEFT\n",
          "qcaret-macro 1\n# none\n"
        }) {
      assertThrows(
          MacroFormat.Refusal.class,
          new Executable() {
            @Override
            public void execute() throws Throwable {
              MacroFormat.parse(text, null);
            }
          },
          text);
    }
  }

  @Test
  void aSecondKeyLineIsRefused() {
    assertThrows(
        MacroFormat.Refusal.class,
        new Executable() {
          @Override
          public void execute() throws Throwable {
            MacroFormat.parse("qcaret-macro 1\nkey F2\nkey F3\ncommand CARET_LEFT\n", null);
          }
        });
  }

  @Test
  void aLoneSurrogateIsNotWritten() {
    final Macro macro = new Macro(null, null, List.of(new MacroStep.Typed("a\uD83Db")));
    assertThrows(
        IllegalArgumentException.class,
        new Executable() {
          @Override
          public void execute() {
            MacroFormat.write(macro);
          }
        });
  }

  @Test
  void generatedStepListsRoundTrip() throws Exception {
    Random random = new Random(7);
    List<CommandId> recordable = new ArrayList<CommandId>();
    for (CommandId command : CommandId.values()) {
      if (Recordability.of(command) == Recordability.COMMAND) {
        recordable.add(command);
      }
    }
    for (int round = 0; round < 200; round++) {
      List<MacroStep> steps = new ArrayList<MacroStep>();
      int n = 1 + random.nextInt(30);
      for (int i = 0; i < n; i++) {
        steps.add(randomStep(random, recordable));
      }
      Macro macro = new Macro(null, null, steps);
      assertEquals(macro, MacroFormat.parse(MacroFormat.write(macro), null), "round " + round);
    }
  }

  private static MacroStep randomStep(Random random, List<CommandId> recordable) {
    MacroStep.Options options =
        new MacroStep.Options(random.nextBoolean(), random.nextBoolean(), random.nextBoolean());
    return switch (random.nextInt(10)) {
      case 0 -> new MacroStep.Command(recordable.get(random.nextInt(recordable.size())));
      case 1 -> new MacroStep.Typed(randomText(random, 1));
      case 2 -> new MacroStep.Insert(randomText(random, 1));
      case 3 -> new MacroStep.Complete(randomText(random, 1));
      case 4 ->
          new MacroStep.Find(
              randomText(random, 1), MacroStep.Direction.values()[random.nextInt(3)], options);
      case 5 ->
          new MacroStep.Replace(
              randomText(random, 1), randomText(random, 0), options, random.nextBoolean());
      case 6 -> new MacroStep.GoToLine(1 + random.nextInt(1000), 1 + random.nextInt(80));
      case 7 -> new MacroStep.TabWidth(1 + random.nextInt(16));
      case 8 -> new MacroStep.DateTime(random.nextBoolean() ? "HH:mm" : "'q\"'yyyy");
      default ->
          new MacroStep.Sequence(
              new NumberSequence(
                  random.nextLong(),
                  random.nextLong(),
                  random.nextInt(NumberSequence.MAX_WIDTH + 1),
                  NumberSequence.Base.values()[random.nextInt(3)]));
    };
  }

  /** Returns text from a pool of awkward chars, whole surrogate pairs only. */
  private static String randomText(Random random, int min) {
    String[] pool = {
      "a", "Z", " ", "\"", "\\", "\r", "\n", "\t", "\u0001", "#", "=", "\uD83D\uDE00", "\u00e9", "/"
    };
    StringBuilder out = new StringBuilder();
    int n = min + random.nextInt(8);
    for (int i = 0; i < n; i++) {
      out.append(pool[random.nextInt(pool.length)]);
    }
    return out.toString();
  }
}
