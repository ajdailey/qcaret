package io.github.ajdailey.qcaret.app.controller;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import io.github.ajdailey.qcaret.app.command.CommandId;
import io.github.ajdailey.qcaret.app.config.NewLineEnding;
import io.github.ajdailey.qcaret.app.config.Options;
import io.github.ajdailey.qcaret.app.config.Preferences;
import io.github.ajdailey.qcaret.app.config.ViewDefaults;
import io.github.ajdailey.qcaret.app.view.PreferencesForm;
import io.github.ajdailey.qcaret.app.view.PreferencesInput;
import io.github.ajdailey.qcaret.core.document.Document;
import io.github.ajdailey.qcaret.core.text.LineEnding;
import io.github.ajdailey.qcaret.core.text.TextEncoding;
import java.io.IOException;
import java.nio.charset.Charset;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.function.BooleanSupplier;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/**
 * {@code editor.newLineEnding} and {@code editor.newEncoding} on every document with nothing to
 * detect, and the Preferences dialog's View tab and new-file lists.
 */
class NewFileDefaultsTest {
  @TempDir Path temp;

  private Harness harness;

  @BeforeEach
  void create() throws IOException {
    Files.createDirectory(temp.resolve("settings"));
    harness = new Harness(temp);
  }

  @AfterEach
  void close() throws InterruptedException {
    harness.close();
  }

  private void setNewFile(NewLineEnding ending, String encoding) {
    Preferences current = harness.application.preferences();
    Options o = current.options();
    harness
        .application
        .settings()
        .set(
            current.withOptions(
                new Options(
                    o.completionChars(),
                    o.autoClose(),
                    o.maxHighlightLineChars(),
                    o.fontSize(),
                    o.pollSeconds(),
                    o.autoReloadClean(),
                    o.backupDays(),
                    o.searchMaxLineChars(),
                    o.excludedDirectories(),
                    o.recoveryIntervalSeconds(),
                    o.recoveryKeepDays(),
                    o.sessionSnapshot(),
                    o.tabWidth(),
                    o.insertSpaces(),
                    o.rightMargin(),
                    o.dateFormat(),
                    o.editorConfig(),
                    o.modelines(),
                    o.closeWindowWithLastTab(),
                    o.closeShowsLastUsed(),
                    ending,
                    encoding,
                    "")));
  }

  private Document newDocument() {
    harness.application.execute(CommandId.NEW);
    return harness.application.documents().activeDocument();
  }

  private static void assertNewFile(LineEnding ending, String charset, Document d) {
    assertEquals(ending, d.lineEnding());
    assertEquals(TextEncoding.of(Charset.forName(charset), false), d.encoding());
  }

  @Test
  void newTakesEachLineEndingAndEncoding() {
    assertNewFile(LineEnding.LF, "UTF-8", newDocument());
    setNewFile(NewLineEnding.CRLF, "windows-1252");
    assertNewFile(LineEnding.CRLF, "windows-1252", newDocument());
    setNewFile(NewLineEnding.LF, "UTF-16LE");
    assertNewFile(LineEnding.LF, "UTF-16LE", newDocument());
    setNewFile(NewLineEnding.PLATFORM, "Shift_JIS");
    LineEnding platform = "\r\n".equals(System.lineSeparator()) ? LineEnding.CRLF : LineEnding.LF;
    assertNewFile(platform, "Shift_JIS", newDocument());
  }

  @Test
  void platformFollowsTheLineSeparator() {
    assertEquals(LineEnding.CRLF, NewLineEnding.PLATFORM.ending("\r\n"));
    assertEquals(LineEnding.LF, NewLineEnding.PLATFORM.ending("\n"));
    assertEquals(LineEnding.CRLF, NewLineEnding.CRLF.ending("\n"));
    assertEquals(LineEnding.LF, NewLineEnding.LF.ending("\r\n"));
  }

  @Test
  void aMissingFileAnEmptyFileAndALineWithoutABreakTakeTheDefaults() throws Exception {
    setNewFile(NewLineEnding.CRLF, "ISO-8859-15");
    harness.open(temp.resolve("missing.txt"));
    assertNewFile(LineEnding.CRLF, "ISO-8859-15", harness.application.documents().activeDocument());
    harness.open(Files.write(temp.resolve("empty.txt"), new byte[0]));
    assertNewFile(LineEnding.CRLF, "ISO-8859-15", harness.application.documents().activeDocument());
    harness.open(Files.writeString(temp.resolve("line.txt"), "one line"));
    Document line = harness.application.documents().activeDocument();
    assertEquals(LineEnding.CRLF, line.lineEnding());
    assertEquals("UTF-8", line.encoding().charset().name());
    harness.open(Files.writeString(temp.resolve("lines.txt"), "a\nb\n"));
    assertEquals(LineEnding.LF, harness.application.documents().activeDocument().lineEnding());
  }

  @Test
  void theLastTabsUntitledReplacementTakesTheDefaults() throws Exception {
    harness.open(Files.writeString(temp.resolve("a.txt"), "a\n"));
    assertEquals(1, harness.application.documents().count());
    setNewFile(NewLineEnding.CRLF, "windows-1250");
    harness.application.execute(CommandId.CLOSE);
    harness.ui.runPending();
    assertEquals(1, harness.application.documents().count());
    assertNewFile(
        LineEnding.CRLF, "windows-1250", harness.application.documents().activeDocument());
  }

  private PreferencesInput preferences() throws InterruptedException {
    harness.view.preferences = null;
    harness.application.execute(CommandId.PREFERENCES);
    harness.await(
        new BooleanSupplier() {
          @Override
          public boolean getAsBoolean() {
            return harness.view.preferences != null;
          }
        },
        "dialog");
    return harness.view.preferences;
  }

  private static PreferencesForm with(
      PreferencesForm f, String ending, String encoding, PreferencesForm.ViewChecks view) {
    return new PreferencesForm(
        f.completionChars(),
        f.autoClose(),
        f.maxHighlightLineChars(),
        f.tabWidth(),
        f.insertSpaces(),
        f.pollSeconds(),
        f.autoReloadClean(),
        f.recoveryIntervalSeconds(),
        f.recoveryKeepDays(),
        f.sessionSnapshot(),
        f.backupDays(),
        f.searchMaxLineChars(),
        f.excludedDirectories(),
        f.theme(),
        f.fontSize(),
        f.shortcuts(),
        f.themeFile(),
        f.fontFamily(),
        f.rightMargin(),
        f.dateFormat(),
        f.editorConfig(),
        f.modelines(),
        f.editorColors(),
        f.closeWindowWithLastTab(),
        f.closeShowsLastUsed(),
        ending,
        encoding,
        view,
        "");
  }

  @Test
  void theDialogShowsAndAppliesTheViewTabAndTheNewFileLists() throws Exception {
    PreferencesInput input = preferences();
    PreferencesForm f = input.form();
    assertEquals("lf", f.newLineEnding());
    assertEquals("UTF-8", f.newEncoding());
    assertEquals(
        new PreferencesForm.ViewChecks(
            false, false, false, true, true, false, true, false, false, false, false),
        f.view());
    PreferencesForm.ViewChecks flipped =
        new PreferencesForm.ViewChecks(
            true, true, true, false, false, true, false, true, true, true, true);
    assertNull(input.apply(with(f, "crlf", "KOI8-R", flipped)));
    Preferences p = harness.application.preferences();
    assertEquals(new ViewDefaults(true, true, true, false, false, true), p.state().view());
    assertFalse(p.state().toolbar());
    assertTrue(p.state().rightMargin());
    assertTrue(p.state().indentGuides());
    assertTrue(p.state().lineChanges());
    assertTrue(p.state().memoryIndicator());
    assertEquals(Boolean.TRUE, harness.view.memoryShown);
    assertEquals(NewLineEnding.CRLF, p.options().newLineEnding());
    assertEquals("KOI8-R", p.options().newEncoding());
    assertEquals(Boolean.FALSE, harness.view.toolbarShown);
    assertTrue(harness.view.indentGuides);
    DocumentController documents = harness.application.documents();
    assertFalse(documents.wrap());
    assertFalse(documents.whitespaceShown());
    assertTrue(documents.overviewShown());
    assertFalse(harness.application.workspace().shown());
    assertEquals(flipped, preferences().form().view());
    assertEquals("crlf", input.form().newLineEnding());
    assertEquals(f, input.defaults());
    assertNewFile(LineEnding.CRLF, "KOI8-R", newDocument());
  }

  @Test
  void anApplyKeepsAViewMenuToggleMadeWhileTheDialogShowsAndAChangedBoxWins() throws Exception {
    PreferencesInput input = preferences();
    PreferencesForm f = input.form();
    harness.application.execute(CommandId.WORD_WRAP);
    harness.application.execute(CommandId.TOOLBAR);
    harness.application.execute(CommandId.INDENT_GUIDES);
    harness.application.execute(CommandId.MEMORY_INDICATOR);
    PreferencesForm.ViewChecks v = f.view();
    PreferencesForm.ViewChecks changed =
        new PreferencesForm.ViewChecks(
            v.wordWrap(),
            !v.whitespace(),
            v.lineEndings(),
            v.overview(),
            v.lineNumbers(),
            v.workspaceBrowser(),
            v.toolbar(),
            !v.rightMargin(),
            !v.indentGuides(),
            v.lineChanges(),
            v.memoryIndicator());
    assertNull(input.apply(with(f, f.newLineEnding(), f.newEncoding(), changed)));
    Preferences p = harness.application.preferences();
    assertTrue(p.state().view().wordWrap());
    assertFalse(p.state().toolbar());
    assertEquals(Boolean.FALSE, harness.view.toolbarShown);
    assertTrue(p.state().view().whitespace());
    assertTrue(p.state().rightMargin());
    assertTrue(p.state().indentGuides());
    assertTrue(p.state().memoryIndicator());
    assertEquals(Boolean.TRUE, harness.view.memoryShown);
    harness.application.execute(CommandId.WORD_WRAP);
    assertNull(input.apply(with(f, f.newLineEnding(), f.newEncoding(), changed)));
    assertFalse(harness.application.preferences().state().view().wordWrap());
    assertTrue(harness.application.preferences().state().view().whitespace());
  }

  @Test
  void restoreDefaultsWritesEveryBoxItChanges() throws Exception {
    PreferencesInput input = preferences();
    PreferencesForm f = input.form();
    PreferencesForm.ViewChecks flipped =
        new PreferencesForm.ViewChecks(
            true, true, true, false, false, true, false, true, true, true, true);
    assertNull(input.apply(with(f, f.newLineEnding(), f.newEncoding(), flipped)));
    harness.application.execute(CommandId.LINE_CHANGES);
    assertNull(input.apply(input.defaults()));
    Preferences p = harness.application.preferences();
    assertEquals(ViewDefaults.DEFAULTS, p.state().view());
    assertTrue(p.state().toolbar());
    assertFalse(p.state().rightMargin());
    assertFalse(p.state().indentGuides());
    assertFalse(p.state().lineChanges());
    assertFalse(p.state().memoryIndicator());
    assertEquals(Boolean.FALSE, harness.view.memoryShown);
  }

  @Test
  void aValueOffTheListsIsRefused() throws Exception {
    PreferencesInput input = preferences();
    PreferencesForm f = input.form();
    PreferencesForm.Problem ending = input.apply(with(f, "cr", "UTF-8", f.view()));
    assertEquals(PreferencesForm.Group.EDITOR, ending.group());
    assertTrue(ending.message().startsWith("Line ending"), ending.message());
    PreferencesForm.Problem encoding = input.apply(with(f, "lf", "utf8", f.view()));
    assertEquals(PreferencesForm.Group.EDITOR, encoding.group());
    assertTrue(encoding.message().startsWith("Encoding"), encoding.message());
    assertEquals(Options.DEFAULTS, harness.application.preferences().options());
  }
}
