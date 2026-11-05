package io.github.ajdailey.qcaret.app.controller;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import io.github.ajdailey.qcaret.app.i18n.UiText;
import io.github.ajdailey.qcaret.app.view.ManualUiDispatcher;
import io.github.ajdailey.qcaret.app.view.UnsavedChoice;
import io.github.ajdailey.qcaret.core.concurrent.MemoryBudget;
import io.github.ajdailey.qcaret.core.concurrent.Workers;
import io.github.ajdailey.qcaret.core.document.LoadOptions;
import io.github.ajdailey.qcaret.core.testkit.Poll;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.List;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicReference;
import java.util.function.BooleanSupplier;
import java.util.function.Consumer;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Assumptions;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/**
 * Tabs, open, save, close and their prompts against a fake view. The save-cancel cases hold a save
 * in the encoder of a gated charset (QLogg {@code ExportCancelTest}): a cancel while it holds must
 * leave the target unchanged.
 */
class DocumentControllerTest {
  private static final Duration BOUND = Duration.ofSeconds(20);

  @TempDir Path temp;

  private ManualUiDispatcher ui;
  private FakeView view;
  private Workers workers;
  private final AtomicReference<Throwable> failure = new AtomicReference<Throwable>();
  private MemoryBudget budget = new MemoryBudget(1L << 30, 50);
  private LoadOptions options = LoadOptions.DEFAULTS;
  private DocumentController documents;

  @BeforeEach
  void setUp() {
    ui = new ManualUiDispatcher();
    view = new FakeView(ui);
    workers =
        new Workers(
            2,
            new Thread.UncaughtExceptionHandler() {
              @Override
              public void uncaughtException(Thread thread, Throwable error) {
                failure.set(error);
              }
            });
    create();
  }

  private void create() {
    documents = new DocumentController(workers, ui, view, budget, options);
  }

  @AfterEach
  void tearDown() {
    documents.dispose();
    workers.close();
    assertNull(failure.get());
    assertEquals(0, budget.reserved());
  }

  private Path file(String name, String text) throws Exception {
    Path file = temp.resolve(name);
    Files.writeString(file, text, StandardCharsets.ISO_8859_1);
    return file;
  }

  private void await(BooleanSupplier condition) throws Exception {
    assertTrue(ui.runUntil(condition, BOUND));
  }

  private BooleanSupplier tabs(final int count) {
    return new BooleanSupplier() {
      @Override
      public boolean getAsBoolean() {
        return view.tabs.size() == count && documents.loading() == 0;
      }
    };
  }

  private BooleanSupplier title(final String expected) {
    return new BooleanSupplier() {
      @Override
      public boolean getAsBoolean() {
        return view.selected != null && expected.equals(view.selected.title);
      }
    };
  }

  /**
   * Waits until every started worker ended, then runs what they posted. The CPU pool's threads stay
   * once a task ran on them, so they are not counted.
   */
  private void settle() throws Exception {
    assertTrue(
        Poll.until(
            new BooleanSupplier() {
              @Override
              public boolean getAsBoolean() {
                return workers.liveThreadNames().replaceAll("qcaret-cpu-\\* x\\d+", "").isEmpty();
              }
            },
            BOUND));
    ui.runPending();
  }

  private Path openOne(String name, String text) throws Exception {
    Path file = file(name, text);
    documents.open(List.of(file));
    await(tabs(1));
    return file;
  }

  @Test
  void openEditAndSave() throws Exception {
    Path file = openOne("a.txt", "one\r\ntwo\n");
    assertEquals("a.txt", view.selected.title);
    assertEquals(file.toRealPath().toString(), view.selected.toolTip);
    assertTrue(budget.reserved() > 0);
    documents.activeEditor().type("x");
    assertEquals("*a.txt", view.selected.title);
    documents.save();
    await(title("a.txt"));
    assertEquals("xone\r\ntwo\n", Files.readString(file, StandardCharsets.ISO_8859_1));
    documents.closeActive();
    assertTrue(view.tabs.isEmpty());
    assertNull(view.unsaved);
  }

  @Test
  void anUntitledDocumentAsksWhereToSave() throws Exception {
    documents.newDocument();
    documents.newDocument();
    assertEquals("Untitled 2", view.selected.title);
    documents.activeEditor().type("new");
    documents.save();
    assertEquals("Untitled 2", view.saveName);
    Path target = temp.resolve("new.txt");
    view.saveChooser.accept(target);
    await(title("new.txt"));
    assertEquals("new", Files.readString(target));
    assertEquals(target.toRealPath(), documents.activePath());
  }

  @Test
  void aMissingFileOpensEmptyAndASecondOpenSelectsTheFirst() throws Exception {
    Path missing = temp.resolve("missing.txt");
    documents.open(List.of(missing));
    await(tabs(1));
    assertEquals("missing.txt", view.selected.title);
    Path file = file("b.txt", "b");
    documents.open(List.of(file, file));
    await(tabs(2));
    settle();
    assertEquals(2, view.tabs.size());
  }

  /**
   * A symbolic link to {@code target} in the temp directory; aborts where links are unavailable.
   */
  private Path link(String name, Path target) throws Exception {
    Path link = temp.resolve(name);
    try {
      Files.createSymbolicLink(link, target);
    } catch (UnsupportedOperationException | IOException e) {
      Assumptions.abort("symbolic links unavailable: " + e);
    }
    return link;
  }

  @Test
  void aFileOpenedThroughALinkSelectsItsTab() throws Exception {
    Path file = openOne("a.txt", "a");
    Path link = link("link.txt", file);
    final AtomicBoolean shown = new AtomicBoolean();
    documents.open(
        link,
        new Consumer<EditorController>() {
          @Override
          public void accept(EditorController editor) {
            shown.set(editor == documents.activeEditor());
          }
        });
    await(
        new BooleanSupplier() {
          @Override
          public boolean getAsBoolean() {
            return shown.get() && documents.loading() == 0;
          }
        });
    documents.open(List.of(link));
    await(tabs(1));
    settle();
    assertEquals(1, view.tabs.size());
  }

  @Test
  void saveAsThroughALinkToAChangedFileReportsTheChange() throws Exception {
    Path file = openOne("a.txt", "a");
    Path link = link("link.txt", file);
    Files.writeString(file, "changed on disk", StandardCharsets.ISO_8859_1);
    documents.saveAs();
    view.saveChooser.accept(link);
    await(
        new BooleanSupplier() {
          @Override
          public boolean getAsBoolean() {
            return !view.infos.isEmpty();
          }
        });
    settle();
    assertEquals(
        List.of(UiText.SAVE_TITLE.text() + ": " + UiText.SAVE_CHANGED_ON_DISK.format("a.txt")),
        view.infos);
    assertEquals("changed on disk", Files.readString(file, StandardCharsets.ISO_8859_1));
  }

  @Test
  void closingADirtyDocumentAsks() throws Exception {
    Path file = openOne("c.txt", "c");
    documents.activeEditor().type("1");
    documents.closeActive();
    assertEquals("c.txt", view.unsavedName);
    view.unsaved.accept(UnsavedChoice.CANCEL);
    assertEquals(1, view.tabs.size());
    documents.closeActive();
    view.unsaved.accept(UnsavedChoice.SAVE);
    await(tabs(0));
    assertEquals("1c", Files.readString(file));
    documents.newDocument();
    documents.activeEditor().type("discard me");
    documents.closeActive();
    view.unsaved.accept(UnsavedChoice.DISCARD);
    assertTrue(view.tabs.isEmpty());
  }

  @Test
  void closeAllStopsAtCancel() throws Exception {
    documents.newDocument();
    documents.newDocument();
    documents.activeEditor().type("x");
    documents.newDocument();
    final boolean[] done = new boolean[1];
    Runnable then =
        new Runnable() {
          @Override
          public void run() {
            done[0] = true;
          }
        };
    documents.closeAll(then);
    assertEquals(2, view.tabs.size());
    view.unsaved.accept(UnsavedChoice.CANCEL);
    assertFalse(done[0]);
    assertEquals(2, view.tabs.size());
    documents.closeAll(then);
    view.unsaved.accept(UnsavedChoice.DISCARD);
    assertTrue(done[0]);
    assertTrue(view.tabs.isEmpty());
  }

  @Test
  void escCancelsARunningSaveAndASecondSaveShowsANotice() throws Exception {
    GatedCharset gate = new GatedCharset();
    options = LoadOptions.DEFAULTS.withForced(gate);
    create();
    Path file = openOne("d.txt", "before");
    documents.activeEditor().type("x");
    gate.armEncoder();
    documents.save();
    assertTrue(gate.awaitHeld());
    documents.save();
    assertEquals(1, view.infos.size());
    assertTrue(view.infos.get(0).contains("still being saved"), view.infos.toString());
    documents.activeEditor().cancel();
    gate.release();
    settle();
    assertEquals("before", Files.readString(file));
    assertEquals("*d.txt", view.selected.title);
    documents.save();
    await(title("d.txt"));
    assertEquals("xbefore", Files.readString(file));
  }

  @Test
  void closingTheTabCancelsARunningSave() throws Exception {
    GatedCharset gate = new GatedCharset();
    options = LoadOptions.DEFAULTS.withForced(gate);
    create();
    Path file = openOne("e.txt", "before");
    documents.activeEditor().type("x");
    gate.armEncoder();
    documents.save();
    assertTrue(gate.awaitHeld());
    documents.closeActive();
    view.unsaved.accept(UnsavedChoice.DISCARD);
    gate.release();
    settle();
    assertEquals("before", Files.readString(file));
    assertTrue(view.tabs.isEmpty());
  }

  @Test
  void aFileOverTheBudgetIsRefusedWithTheHeapSetting() throws Exception {
    budget = new MemoryBudget(1000, 100);
    create();
    documents.open(List.of(file("big.txt", "x".repeat(600))));
    await(
        new BooleanSupplier() {
          @Override
          public boolean getAsBoolean() {
            return !view.infos.isEmpty();
          }
        });
    assertTrue(view.infos.get(0).contains("-Xmx1g"), view.infos.get(0));
    assertTrue(view.tabs.isEmpty());
  }

  @Test
  void wrapAppliesToEveryEditor() throws Exception {
    openOne("f.txt", "f");
    documents.toggleWrap();
    assertTrue(documents.wrap());
    documents.newDocument();
    assertTrue(documents.activeEditor().projection().wrap());
    assertNotNull(view.tabs.get(0).projection);
    assertTrue(view.tabs.get(0).projection.wrap());
  }
}
