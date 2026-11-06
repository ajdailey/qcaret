package io.github.ajdailey.qcaret.app.theme;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.Locale;
import java.util.logging.Handler;
import java.util.logging.Level;
import java.util.logging.LogRecord;
import java.util.logging.Logger;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class ThemeColorsTest {
  private static final Rgb MOCK = Rgb.of(0x123456);

  @TempDir Path temp;

  private final Logger logger = Logger.getLogger(ThemeColors.class.getName());
  private final List<LogRecord> warnings = new ArrayList<LogRecord>();
  private final Handler capture =
      new Handler() {
        @Override
        public void publish(LogRecord entry) {
          if (entry.getLevel().intValue() >= Level.WARNING.intValue()) {
            warnings.add(entry);
          }
        }

        @Override
        public void flush() {}

        @Override
        public void close() {}
      };

  @BeforeEach
  void listen() {
    logger.addHandler(capture);
  }

  @AfterEach
  void stop() {
    logger.removeHandler(capture);
  }

  private static Palette light(String key) {
    return ThemeColors.parse("light." + key + " = #123456\n", "mock").over(Palette.LIGHT);
  }

  @Test
  void everyPaletteColorCanBeSet() {
    assertEquals(MOCK, light("text").text());
    assertEquals(MOCK, light("background").background());
    assertEquals(MOCK, light("selection").selection());
    assertEquals(MOCK, light("selectionText").selectionText());
    assertEquals(MOCK, light("currentLine").currentLine());
    assertEquals(MOCK, light("lineNumbers").lineNumbers());
    assertEquals(MOCK, light("gutter").gutter());
    assertEquals(MOCK, light("caret").caret());
    assertEquals(MOCK, light("whitespace").whitespace());
    assertEquals(MOCK, light("findMatch").findMatch());
    assertEquals(MOCK, light("occurrence").occurrence());
    assertEquals(MOCK, light("bracket").bracket());
    assertEquals(MOCK, light("tabText").tabText());
    assertEquals(MOCK, light("overviewFind").overviewFind());
    assertEquals(MOCK, light("overviewSearch").overviewSearch());
    assertEquals(MOCK, light("bookmarkComment").bookmarkComment());
    assertEquals(MOCK, light("diffRemoved").diffRemoved());
    assertEquals(MOCK, light("diffAdded").diffAdded());
    assertEquals(MOCK, light("diffChanged").diffChanged());
  }

  @Test
  void everyTokenColorCanBeSetAndKeepsItsFontStyle() {
    for (TokenRole role : TokenRole.values()) {
      String key = "token." + role.name().toLowerCase(Locale.ROOT);
      TokenStyle style = light(key).style(role);
      TokenStyle before = Palette.LIGHT.style(role);
      assertEquals(MOCK, style.foreground(), key);
      assertEquals(before.bold(), style.bold(), key);
      assertEquals(before.italic(), style.italic(), key);
      assertEquals(before.underline(), style.underline(), key);
    }
  }

  @Test
  void eachSideTakesOnlyItsOwnColors() {
    ThemeColors colors =
        ThemeColors.parse(
            "light.text=#000001\r\ndark.text=#000002\r\n# dark.caret=#000003", "mock");
    assertEquals(Rgb.of(1), colors.over(Palette.LIGHT).text());
    assertEquals(Rgb.of(2), colors.over(Palette.DARK).text());
    assertEquals(Palette.DARK.caret(), colors.over(Palette.DARK).caret());
    assertFalse(colors.over(Palette.DARK).equals(Palette.DARK));
    assertTrue(colors.over(Palette.DARK).dark());
  }

  @Test
  void aSideWithoutColorsKeepsThePalette() {
    assertSame(Palette.DARK, ThemeColors.parse("light.text=#000001", "mock").over(Palette.DARK));
    assertSame(Palette.LIGHT, ThemeColors.NONE.over(Palette.LIGHT));
  }

  @Test
  void aBadValueLogsOneWarnNamingTheKeyAndKeepsTheBuiltInColor() {
    for (String bad : new String[] {"red", "#12345", "#1234567", "#12345g", "123456", ""}) {
      warnings.clear();
      Palette palette = ThemeColors.parse("light.caret = " + bad, "mock").over(Palette.LIGHT);
      assertEquals(Palette.LIGHT.caret(), palette.caret(), bad);
      assertEquals(1, warnings.size(), bad);
      assertTrue(warnings.get(0).getMessage().contains("light.caret"));
    }
  }

  @Test
  void aKeyRepeatedBadlyLogsOnceAndTheLastValueWins() {
    Palette palette =
        ThemeColors.parse("light.caret=x\nlight.caret=y\nlight.text=z\nlight.text=#000001", "mock")
            .over(Palette.LIGHT);
    assertEquals(Palette.LIGHT.caret(), palette.caret());
    assertEquals(Rgb.of(1), palette.text());
    assertEquals(1, warnings.size());
  }

  @Test
  void unknownKeysAndOtherLinesAreIgnored() {
    ThemeColors colors =
        ThemeColors.parse("light.mock=red\nmauve.text=#000001\ntext=#000002\nno equals\n\n", "m");
    assertSame(Palette.LIGHT, colors.over(Palette.LIGHT));
    assertEquals(0, warnings.size());
  }

  private Path themes() throws Exception {
    return Files.createDirectories(temp.resolve(ThemeColors.DIRECTORY));
  }

  @Test
  void aFileIsReadByName() throws Exception {
    Files.writeString(themes().resolve("mock.theme"), "dark.text=#000002");
    assertEquals(Rgb.of(2), ThemeColors.read(temp, "mock").over(Palette.DARK).text());
    assertEquals(0, warnings.size());
  }

  @Test
  void noNameReadsNothing() {
    assertSame(ThemeColors.NONE, ThemeColors.read(temp, ""));
    assertEquals(0, warnings.size());
  }

  @Test
  void aMissingFileWarnsOnceAndHasNoColors() {
    assertSame(ThemeColors.NONE, ThemeColors.read(temp, "absent"));
    assertEquals(1, warnings.size());
  }

  @Test
  void aFileOverTheLimitWarnsOnceAndHasNoColors() throws Exception {
    byte[] bytes = new byte[ThemeColors.MAX_FILE_BYTES + 1];
    Arrays.fill(bytes, (byte) '\n');
    byte[] color = "light.text=#000001\n".getBytes(StandardCharsets.UTF_8);
    System.arraycopy(color, 0, bytes, 0, color.length);
    Files.write(themes().resolve("large.theme"), bytes);
    assertSame(ThemeColors.NONE, ThemeColors.read(temp, "large"));
    assertEquals(1, warnings.size());
  }

  @Test
  void aFileAtTheLimitIsRead() throws Exception {
    byte[] bytes = new byte[ThemeColors.MAX_FILE_BYTES];
    Arrays.fill(bytes, (byte) '\n');
    byte[] color = "light.text=#000001\n".getBytes(StandardCharsets.UTF_8);
    System.arraycopy(color, 0, bytes, 0, color.length);
    Files.write(themes().resolve("full.theme"), bytes);
    assertEquals(Rgb.of(1), ThemeColors.read(temp, "full").over(Palette.LIGHT).text());
  }

  @Test
  void theListHoldsValidNamesSorted() throws Exception {
    Path themes = themes();
    Files.writeString(themes.resolve("b.theme"), "");
    Files.writeString(themes.resolve("a-1.theme"), "");
    Files.writeString(themes.resolve("with space.theme"), "");
    Files.writeString(themes.resolve(".hidden.theme"), "");
    Files.writeString(themes.resolve("notes.txt"), "");
    assertEquals(List.of("a-1", "b"), ThemeColors.list(temp));
  }

  @Test
  void noFolderListsNothing() {
    assertEquals(List.of(), ThemeColors.list(temp));
  }

  @Test
  void namesAreChecked() {
    assertTrue(ThemeColors.validName("Harbor_2.dark-1"));
    assertFalse(ThemeColors.validName(null));
    assertFalse(ThemeColors.validName(""));
    assertFalse(ThemeColors.validName(".."));
    assertFalse(ThemeColors.validName("a/b"));
    assertFalse(ThemeColors.validName("a\\b"));
    assertFalse(ThemeColors.validName("x".repeat(ThemeColors.MAX_NAME_CHARS + 1)));
  }
}
