package io.github.ajdailey.qcaret.app.controller;

import static org.junit.jupiter.api.Assertions.assertEquals;

import io.github.ajdailey.qcaret.app.cli.FileRequest;
import io.github.ajdailey.qcaret.app.command.CommandId;
import io.github.ajdailey.qcaret.app.i18n.UiText;
import io.github.ajdailey.qcaret.app.view.MarkdownViewMode;
import io.github.ajdailey.qcaret.app.view.QuickOpenInput;
import io.github.ajdailey.qcaret.core.document.SelectionSet;
import io.github.ajdailey.qcaret.core.markdown.RenderedMarkdown;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.function.BooleanSupplier;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/**
 * {@code file.md#heading} from every entry point: the command line and its handoff, a preview link,
 * Open Path at Caret and Quick Open. The caret lands on the heading line, that line is the editor's
 * top row and the preview scrolls to it.
 */
class HeadingJumpTest {
  /** Headings on lines 0, 4 ({@code part-two}) and 8 ({@code part-two-1}). */
  private static final String TEXT =
      "# Mock\n\nintro\n\n## Part two\n\nbody\n\n## Part two\n\nmore\n";

  @TempDir Path temp;

  private Harness harness;
  private DocumentController documents;
  private Path root;

  @BeforeEach
  void create() throws Exception {
    harness = new Harness(temp);
    documents = harness.application.documents();
    root = Files.createDirectory(temp.resolve("ws")).toRealPath();
    final GrammarLibrary library = harness.application.grammars();
    harness.application.loadGrammars();
    harness.await(
        new BooleanSupplier() {
          @Override
          public boolean getAsBoolean() {
            return library.set() != null;
          }
        },
        "grammars read");
  }

  @AfterEach
  void close() throws InterruptedException {
    documents.dispose();
    harness.close();
  }

  private Path write(String name, String text) throws Exception {
    return Files.writeString(root.resolve(name), text, StandardCharsets.UTF_8);
  }

  private int caretLine() {
    EditorController editor = documents.activeEditor();
    return editor.document().text().lineAt(editor.selections().selections().primary().caret());
  }

  private void awaitCaretLine(final int line) throws InterruptedException {
    harness.await(
        new BooleanSupplier() {
          @Override
          public boolean getAsBoolean() {
            return documents.activeEditor() != null && caretLine() == line;
          }
        },
        "caret on line " + line);
    harness.awaitIdle("jump");
    assertEquals(line, caretLine());
    assertEquals(line, harness.view.selected.topRow);
  }

  private void awaitNotice(final String notice) throws InterruptedException {
    harness.await(
        new BooleanSupplier() {
          @Override
          public boolean getAsBoolean() {
            List<String> notices = harness.view.notices;
            return !notices.isEmpty() && notices.get(notices.size() - 1).equals(notice);
          }
        },
        notice);
    harness.awaitIdle("notice");
  }

  /** Waits for the selected tab's preview to show and to scroll to a source line. */
  private void awaitPreviewAt(final int sourceLine) throws InterruptedException {
    final FakeMarkdownPane pane = shownPane();
    harness.await(
        new BooleanSupplier() {
          @Override
          public boolean getAsBoolean() {
            if (pane.shown.isEmpty() || pane.scrolls.isEmpty()) {
              return false;
            }
            RenderedMarkdown r = pane.shown.get(pane.shown.size() - 1).rendered();
            return pane.scrolls.get(pane.scrolls.size() - 1) == r.renderedLine(sourceLine);
          }
        },
        "preview at source line " + sourceLine);
  }

  /** Waits for the selected tab's preview to show a render. */
  private FakeMarkdownPane shownPane() throws InterruptedException {
    final FakeView.FakeTab tab = harness.view.selected;
    harness.await(
        new BooleanSupplier() {
          @Override
          public boolean getAsBoolean() {
            return tab.markdown != null && !tab.markdown.shown.isEmpty();
          }
        },
        "preview shown");
    return tab.markdown;
  }

  private void open(FileRequest file, int count) throws InterruptedException {
    documents.open(file);
    awaitCount(count);
  }

  private void awaitCount(final int count) throws InterruptedException {
    harness.await(
        new BooleanSupplier() {
          @Override
          public boolean getAsBoolean() {
            return documents.count() == count;
          }
        },
        count + " tabs");
  }

  @Test
  void aCommandLineHeadingPutsTheLineAtTheTopAndThePreviewFollows() throws Exception {
    Path file = write("a.md", TEXT);
    open(new FileRequest(file, 0, 0, false, null, "part-two-1"), 1);
    awaitCaretLine(8);
    assertEquals(MarkdownViewMode.SPLIT, documents.markdownMode());
    awaitPreviewAt(8);
  }

  @Test
  void aFileAlreadyOpenMovesToTheHeading() throws Exception {
    Path file = write("a.md", TEXT);
    harness.open(file);
    open(new FileRequest(file, 0, 0, false, null, "part-two"), 1);
    awaitCaretLine(4);
  }

  @Test
  void aLineWinsOverAHeading() throws Exception {
    Path file = write("a.md", TEXT);
    open(new FileRequest(file, 3, 0, false, null, "part-two"), 1);
    harness.awaitIdle("open");
    assertEquals(2, caretLine());
  }

  @Test
  void anUnknownIdOpensAtTheTopWithANotice() throws Exception {
    Path file = write("a.md", TEXT);
    harness.open(file);
    documents.activeEditor().selections().set(SelectionSet.caret(TEXT.length()));
    documents.open(new FileRequest(file, 0, 0, false, null, "absent"));
    awaitNotice(UiText.MARKDOWN_NO_HEADING.format("absent", "a.md"));
    assertEquals(0, caretLine());
  }

  @Test
  void aFileThatIsNotMarkdownIgnoresTheHeadingWithTheNotice() throws Exception {
    Path file = write("a.txt", TEXT);
    harness.open(file);
    documents.activeEditor().selections().set(SelectionSet.caret(TEXT.indexOf("intro")));
    documents.open(new FileRequest(file, 0, 0, false, null, "part-two"));
    awaitNotice(UiText.MARKDOWN_NO_HEADING.format("part-two", "a.txt"));
    assertEquals(2, caretLine());
  }

  @Test
  void previewLinksOpenTheOtherFileAtItsHeading() throws Exception {
    Path other = write("b.md", TEXT);
    write("a.md", "[go](b.md#part%2Dtwo)\n");
    harness.open(root.resolve("a.md"));
    FakeMarkdownPane pane = shownPane();
    RenderedMarkdown r = pane.shown.get(pane.shown.size() - 1).rendered();
    pane.input.clicked(r.links().get(0).start());
    awaitCount(2);
    awaitCaretLine(4);
    assertEquals(other, documents.activePath());
    documents.selectNext(true);
    harness.awaitIdle("select a.md");
    pane.input.linkFollowed(MarkdownLinks.previewFileUrl(other, "part-two-1"));
    awaitCaretLine(8);
    assertEquals(2, documents.count());
  }

  @Test
  void aFileNamedWithTheFragmentWinsOverTheHeading() throws Exception {
    write("b.md", TEXT);
    Path named = write("b.md#part-two", "named\n");
    write("a.md", "[go](b.md#part-two)\n");
    harness.open(root.resolve("a.md"));
    FakeMarkdownPane pane = shownPane();
    pane.input.clicked(pane.shown.get(pane.shown.size() - 1).rendered().links().get(0).start());
    awaitCount(2);
    harness.awaitIdle("open");
    assertEquals(named, documents.activePath());
  }

  @Test
  void openPathAtCaretFollowsTheHeading() throws Exception {
    write("b.md", TEXT);
    write("notes.txt", "see b.md#part-two-1 there\n");
    harness.open(root.resolve("notes.txt"));
    documents.activeEditor().selections().set(SelectionSet.caret(6));
    final int before = harness.view.activations.get();
    harness.application.execute(CommandId.OPEN_PATH_AT_CARET);
    harness.await(
        new BooleanSupplier() {
          @Override
          public boolean getAsBoolean() {
            return harness.view.activations.get() > before && harness.view.active.get() == 0;
          }
        },
        "path checked");
    awaitCount(2);
    awaitCaretLine(8);
    assertEquals(root.resolve("b.md"), documents.activePath());
  }

  @Test
  void quickOpenFollowsTheHeading() throws Exception {
    write("guide.md", TEXT);
    final WorkspaceController workspace = harness.application.workspace();
    workspace.set(root);
    harness.await(
        new BooleanSupplier() {
          @Override
          public boolean getAsBoolean() {
            return workspace.index() != null;
          }
        },
        "indexed");
    harness.application.execute(CommandId.QUICK_OPEN);
    QuickOpenInput input = harness.view.quickOpen;
    input.filter("guide#part%2Dtwo");
    input.close();
    input.choose(0);
    awaitCount(1);
    awaitCaretLine(4);
    assertEquals(
        new QuickOpen.Query("guide", 0, 0, null, "part-two"),
        QuickOpen.Query.parse("guide#part%2Dtwo"));
    assertEquals(new QuickOpen.Query("C#/Program", 0, 0), QuickOpen.Query.parse("C#/Program"));
  }
}
