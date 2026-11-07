package io.github.ajdailey.qcaret.app.controller;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertTrue;

import io.github.ajdailey.qcaret.app.command.CommandId;
import io.github.ajdailey.qcaret.app.i18n.UiText;
import io.github.ajdailey.qcaret.app.session.Session;
import io.github.ajdailey.qcaret.app.view.ManualUiDispatcher;
import io.github.ajdailey.qcaret.app.view.UnsavedChoice;
import io.github.ajdailey.qcaret.core.concurrent.MemoryBudget;
import io.github.ajdailey.qcaret.core.concurrent.Workers;
import io.github.ajdailey.qcaret.core.document.Document;
import io.github.ajdailey.qcaret.core.document.LoadOptions;
import io.github.ajdailey.qcaret.core.testkit.Poll;
import java.io.IOException;
import java.lang.ref.WeakReference;
import java.nio.charset.Charset;
import java.nio.charset.CharsetDecoder;
import java.nio.charset.CharsetEncoder;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.ArrayList;
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
   * Waits until every started blocking worker ended, then runs what they posted. The CPU pool's
   * threads stay once a task ran on them, so they are not counted, and a CPU task can post after
   * this returns.
   */
  private void settle() throws Exception {
    assertTrue(
        Poll.until(
            new BooleanSupplier() {
              @Override
              public boolean getAsBoolean() {
                return workers
                    .liveThreadNames()
                    .replaceAll("qcaret-cpu-\\* x\\d+|qcaret-watch-\\d+", "")
                    .replaceAll("[, ]", "")
                    .isEmpty();
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
    // Dirty, so the file monitor never reloads the buffer before Save As checks the file.
    documents.activeEditor().type("1");
    Path link = link("link.txt", file);
    Files.writeString(file, "changed on disk", StandardCharsets.ISO_8859_1);
    documents.saveAs();
    view.saveChooser.accept(link);
    await(
        new BooleanSupplier() {
          @Override
          public boolean getAsBoolean() {
            return view.changePrompts.contains("save a.txt");
          }
        });
    settle();
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
  void aSaveAfterACanceledSaveWaitsForItsWriter() throws Exception {
    GatedCharset gate = gatedSaves();
    Path file = openOne("d.txt", "before");
    Files.createLink(temp.resolve("link.txt"), file);
    documents.activeEditor().type("x");
    gate.armEncoder();
    documents.save();
    assertTrue(gate.awaitHeld());
    documents.cancelSave();
    documents.activeEditor().type("y");
    documents.save();
    Thread.sleep(300);
    assertTrue(Files.size(file) < "yxbefore".length(), "a second writer ran beside the first");
    gate.release();
    settle();
    assertEquals("xybefore", Files.readString(file));
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

  private GatedCharset gated() {
    GatedCharset gate = new GatedCharset();
    gate.arm();
    documents.dispose();
    options = LoadOptions.DEFAULTS.withForced(gate);
    create();
    return gate;
  }

  private BooleanSupplier loadShown() {
    return new BooleanSupplier() {
      @Override
      public boolean getAsBoolean() {
        return view.loadCancel != null;
      }
    };
  }

  private BooleanSupplier noLoad() {
    return new BooleanSupplier() {
      @Override
      public boolean getAsBoolean() {
        return documents.loading() == 0;
      }
    };
  }

  @Test
  void aCanceledLoadLeavesNoTabAndFreesItsReservation() throws Exception {
    Path file = file("a.txt", "mock\n");
    GatedCharset gate = gated();
    documents.open(List.of(file));
    await(loadShown());
    assertEquals(UiText.LOAD_PROGRESS.format("a.txt"), view.loadText);
    assertTrue(gate.awaitHeld());
    assertTrue(budget.reserved() > 0);
    view.loadCancel.run();
    assertNull(view.loadText);
    gate.release();
    await(noLoad());
    settle();
    assertTrue(view.tabs.isEmpty());
    assertEquals(0, budget.reserved());
    documents.open(List.of(file));
    await(tabs(1));
  }

  @Test
  void aCanceledRestoreEndsThatTabWithoutReportingAFailure() throws Exception {
    Path file = file("a.txt", "mock\n");
    GatedCharset gate = gated();
    final List<String> failed = new ArrayList<String>();
    final int[] done = new int[1];
    documents.restore(
        new Session.Tab(
            file.toString(),
            "",
            "",
            "",
            "",
            false,
            4,
            false,
            List.of(),
            0,
            false,
            "",
            "",
            List.of(),
            false),
        new Consumer<String>() {
          @Override
          public void accept(String name) {
            failed.add(name);
          }
        },
        new Runnable() {
          @Override
          public void run() {
            done[0]++;
          }
        });
    await(loadShown());
    assertTrue(gate.awaitHeld());
    view.loadCancel.run();
    gate.release();
    await(
        new BooleanSupplier() {
          @Override
          public boolean getAsBoolean() {
            return done[0] == 1;
          }
        });
    assertTrue(failed.isEmpty());
    assertTrue(view.tabs.isEmpty());
  }

  @Test
  void aCanceledReopenKeepsTheDocumentAndItsTabCanMove() throws Exception {
    Path file = openOne("a.txt", "mock\n");
    GatedCharset gate = new GatedCharset();
    gate.arm();
    documents.reopenWithEncoding(gate);
    await(loadShown());
    assertTrue(gate.awaitHeld());
    assertFalse(documents.canMove(documents.activeDocument()));
    view.loadCancel.run();
    gate.release();
    await(noLoad());
    settle();
    assertEquals(1, view.tabs.size());
    assertEquals(file.toRealPath(), documents.activePath());
    assertTrue(documents.canMove(documents.activeDocument()));
  }

  private BooleanSupplier held(final GatedCharset gate) {
    return new BooleanSupplier() {
      @Override
      public boolean getAsBoolean() {
        return gate.held();
      }
    };
  }

  /** Opens a file with a charset whose next encoder holds the save that asks for it. */
  private GatedCharset openHoldingTheNextSave(String name, String text) throws Exception {
    GatedCharset gate = gatedSaves();
    openOne(name, text);
    documents.activeEditor().type("x");
    gate.armEncoder();
    return gate;
  }

  /** Forces a charset that holds a save once armed; a load's round-trip check asks for encoders. */
  private GatedCharset gatedSaves() {
    GatedCharset gate = new GatedCharset();
    options = LoadOptions.DEFAULTS.withForced(gate);
    documents.dispose();
    create();
    return gate;
  }

  @Test
  void anEditDuringTheCloseSaveKeepsTheTabAndAsksAgain() throws Exception {
    GatedCharset gate = openHoldingTheNextSave("a.txt", "before");
    final List<String> cleaned = new ArrayList<String>();
    documents.setBufferCleaned(
        new Consumer<String>() {
          @Override
          public void accept(String id) {
            cleaned.add(id);
          }
        });
    documents.closeActive();
    Consumer<UnsavedChoice> first = view.unsaved;
    first.accept(UnsavedChoice.SAVE);
    assertTrue(gate.awaitHeld());
    documents.activeEditor().type("y");
    gate.release();
    settle();
    assertEquals("xbefore", Files.readString(temp.resolve("a.txt")));
    assertEquals(1, view.tabs.size(), "the tab closed holding an edit the file lacks");
    assertEquals("xybefore", documents.activeDocument().text().toString());
    assertTrue(cleaned.isEmpty(), "the buffer's recovery file went");
    assertTrue(view.unsaved != first, "the close did not ask again");
    view.unsaved.accept(UnsavedChoice.DISCARD);
    assertTrue(view.tabs.isEmpty());
  }

  @Test
  void closeAllWaitsForTheAnswerAboutAnEditDuringItsSave() throws Exception {
    GatedCharset gate = openHoldingTheNextSave("a.txt", "before");
    final int[] ended = new int[1];
    documents.closeAll(
        new Runnable() {
          @Override
          public void run() {
            ended[0]++;
          }
        });
    view.unsaved.accept(UnsavedChoice.SAVE);
    assertTrue(gate.awaitHeld());
    documents.activeEditor().type("y");
    gate.release();
    settle();
    assertEquals(0, ended[0], "closeAll ended before the second answer");
    assertEquals(1, view.tabs.size());
    view.unsaved.accept(UnsavedChoice.DISCARD);
    assertEquals(1, ended[0]);
    assertTrue(view.tabs.isEmpty());
  }

  /** Save As from b.txt onto a.txt, held in the write, with an edit in a.txt's tab meanwhile. */
  private void saveAsOntoATabThatIsEditedMeanwhile() throws Exception {
    GatedCharset gate = gatedSaves();
    openOne("b.txt", "mock\n");
    documents.activeEditor().type("x");
    Path a = file("a.txt", "mock\n");
    documents.open(List.of(a));
    await(tabs(2));
    documents.select(0);
    gate.armEncoder();
    documents.saveAs();
    view.saveChooser.accept(a.toRealPath());
    await(held(gate));
    documents.select(1);
    documents.activeEditor().type("y");
    view.unsaved = null;
    gate.release();
    settle();
  }

  @Test
  void anEditInTheReplacedTabDuringSaveAsAsksBeforeItCloses() throws Exception {
    saveAsOntoATabThatIsEditedMeanwhile();
    assertEquals(2, view.tabs.size(), "the replaced tab closed holding an edit");
    assertNotNull(view.unsaved, "the replaced tab was not asked about");
    assertEquals("a.txt", view.unsavedName);
    view.unsaved.accept(UnsavedChoice.DISCARD);
    assertEquals(1, view.tabs.size());
    assertEquals("xmock\n", documents.activeDocument().text().toString());
  }

  @Test
  void aCancelAtTheReplacedTabKeepsItsEdit() throws Exception {
    saveAsOntoATabThatIsEditedMeanwhile();
    view.unsaved.accept(UnsavedChoice.CANCEL);
    assertEquals(2, view.tabs.size());
    assertEquals("xmock\n", Files.readString(temp.resolve("a.txt")));
    assertEquals("xmock\n", documents.activeDocument().text().toString());
    documents.select(1);
    assertEquals("ymock\n", documents.activeDocument().text().toString());
  }

  /** Starts a reopen and holds its load. */
  private GatedCharset holdAReopen() throws Exception {
    GatedCharset gate = new GatedCharset();
    gate.arm();
    documents.reopenWithEncoding(gate);
    await(loadShown());
    assertTrue(gate.awaitHeld());
    return gate;
  }

  @Test
  void aTabClosedDuringItsReopenStaysClosed() throws Exception {
    openOne("a.txt", "mock\n");
    GatedCharset gate = holdAReopen();
    documents.closeActive();
    assertEquals(0, view.tabs.size());
    gate.release();
    await(noLoad());
    settle();
    assertEquals(0, view.tabs.size(), "the reopen's load brought the closed tab back");
    assertEquals(0, budget.reserved());
  }

  @Test
  void anEditSavedDuringAReopenStaysInTheBuffer() throws Exception {
    Path file = openOne("a.txt", "mock\n");
    long held = budget.reserved();
    GatedCharset gate = holdAReopen();
    documents.activeEditor().type("x");
    documents.save();
    await(title("a.txt"));
    assertEquals("xmock\n", Files.readString(file));
    gate.release();
    await(noLoad());
    settle();
    assertEquals(
        "xmock\n",
        documents.activeDocument().text().toString(),
        "the load read before the save replaced the buffer");
    assertEquals(held, budget.reserved());
  }

  @Test
  void aSaveAsDuringAReopenKeepsTheDocumentUnderItsNewName() throws Exception {
    openOne("a.txt", "mock\n");
    Document before = documents.activeDocument();
    GatedCharset gate = holdAReopen();
    assertFalse(documents.canMove(before));
    documents.saveAs();
    Path target = temp.resolve("b.txt");
    view.saveChooser.accept(target);
    await(title("b.txt"));
    assertFalse(documents.canMove(before), "the renamed tab lost its running reopen");
    gate.release();
    await(noLoad());
    settle();
    assertEquals(1, view.tabs.size(), "the reopen's load added a tab for the old name");
    assertSame(before, documents.activeDocument());
    assertTrue(documents.canMove(before));
  }

  @Test
  void aReopenOfADeletedFileShowsTheFailureAndAddsNoTab() throws Exception {
    Path file = openOne("a.txt", "mock\n");
    Document before = documents.activeDocument();
    Files.delete(file);
    documents.reopenWithEncoding(StandardCharsets.ISO_8859_1);
    await(
        new BooleanSupplier() {
          @Override
          public boolean getAsBoolean() {
            return !view.infos.isEmpty() || view.tabs.size() != 1;
          }
        });
    settle();
    assertEquals(1, view.tabs.size(), "a second tab holds the same path");
    assertSame(before, documents.activeDocument());
    assertTrue(view.infos.get(0).contains("a.txt"), view.infos.toString());
  }

  @Test
  void aSecondReopenDuringTheFirstDoesNothing() throws Exception {
    openOne("a.txt", "mock\n");
    GatedCharset gate = holdAReopen();
    documents.reopenWithDetection();
    documents.closeActive();
    gate.release();
    await(noLoad());
    settle();
    assertEquals(0, view.tabs.size(), "a reopen outlived the closed tab");
  }

  /** Starts a reopen with every byte of the budget taken, so the load is refused. */
  private void refuseAReopen() throws Exception {
    long free = budget.available();
    assertTrue(budget.reserve(free));
    try {
      documents.reopenWithEncoding(StandardCharsets.ISO_8859_1);
      await(refused());
      settle();
    } finally {
      budget.release(free);
    }
  }

  private BooleanSupplier refused() {
    return new BooleanSupplier() {
      @Override
      public boolean getAsBoolean() {
        return !view.infos.isEmpty();
      }
    };
  }

  @Test
  void aRefusedReopenLeavesTheTabMovable() throws Exception {
    openOne("a.txt", "mock\n");
    refuseAReopen();
    assertTrue(documents.canMove(documents.activeDocument()), "the tab stays busy");
  }

  @Test
  void anOpenAfterARefusedReopenSelectsTheTabAndKeepsItsDocument() throws Exception {
    Path file = openOne("a.txt", "mock\n");
    Document before = documents.activeDocument();
    refuseAReopen();
    documents.open(List.of(file));
    await(tabs(1));
    settle();
    assertSame(before, documents.activeDocument(), "the open replaced the document");
  }

  @Test
  void aRefusedOpenDropsItsCallback() throws Exception {
    Path file = file("a.txt", "mock\n");
    final boolean[] called = new boolean[1];
    long free = budget.available();
    assertTrue(budget.reserve(free));
    try {
      documents.open(
          file,
          new Consumer<EditorController>() {
            @Override
            public void accept(EditorController editor) {
              called[0] = true;
            }
          });
      await(refused());
      settle();
    } finally {
      budget.release(free);
    }
    documents.open(List.of(file));
    await(tabs(1));
    settle();
    assertFalse(called[0], "the refused open's callback ran on a later open");
  }

  /** A charset whose decoder fails with an unchecked error, as a load out of memory does. */
  private static final class FailingCharset extends Charset {
    FailingCharset() {
      super("X-QCARET-TEST-FAIL", null);
    }

    @Override
    public boolean contains(Charset other) {
      return false;
    }

    @Override
    public CharsetDecoder newDecoder() {
      throw new IllegalStateException("mock unchecked load failure");
    }

    @Override
    public CharsetEncoder newEncoder() {
      return StandardCharsets.ISO_8859_1.newEncoder();
    }
  }

  @Test
  void anUncheckedLoadFailureEndsTheTabsRestoreAndReportsIt() throws Exception {
    documents.dispose();
    options = LoadOptions.DEFAULTS.withForced(new FailingCharset());
    create();
    Path file = file("a.txt", "mock\n");
    final List<String> failed = new ArrayList<String>();
    final int[] done = new int[1];
    documents.restore(
        new Session.Tab(
            file.toString(),
            "",
            "",
            "",
            "",
            false,
            4,
            false,
            List.of(),
            0,
            false,
            "",
            "",
            List.of(),
            false),
        new Consumer<String>() {
          @Override
          public void accept(String name) {
            failed.add(name);
          }
        },
        new Runnable() {
          @Override
          public void run() {
            done[0]++;
          }
        });
    await(
        new BooleanSupplier() {
          @Override
          public boolean getAsBoolean() {
            return failure.get() != null;
          }
        });
    settle();
    assertEquals(1, done[0], "the restore of this tab never ends");
    assertEquals(List.of("a.txt"), failed);
    assertEquals(0, budget.reserved());
    assertTrue(failure.getAndSet(null) instanceof IllegalStateException);
  }

  @Test
  void anUncheckedLoadFailureShowsAFailedOpen() throws Exception {
    documents.dispose();
    options = LoadOptions.DEFAULTS.withForced(new FailingCharset());
    create();
    documents.open(List.of(file("a.txt", "mock\n")));
    await(refused());
    settle();
    assertEquals(
        List.of(
            UiText.OPEN_TITLE.text()
                + ": "
                + UiText.OPEN_FAILED.format("a.txt", UiText.OPEN_STOPPED.text())),
        view.infos);
    assertTrue(view.tabs.isEmpty());
    assertEquals(0, view.active.get());
    assertTrue(failure.getAndSet(null) instanceof IllegalStateException);
  }

  /**
   * Runs UI tasks and calls for a collection until the reference clears: a CPU task that outlives
   * {@link #settle} queues a UI task holding the closed document's editor, and the queue holds it
   * until it runs.
   */
  private void assertCollected(WeakReference<Document> reference) throws Exception {
    assertTrue(
        ui.runUntil(
            new BooleanSupplier() {
              @Override
              public boolean getAsBoolean() {
                System.gc();
                return reference.get() == null;
              }
            },
            Duration.ofSeconds(5)),
        "the closed document is still reachable");
  }

  @Test
  void aClosedDocumentAWordCompletionReadIsCollected() throws Exception {
    documents.open(List.of(file("a.txt", "mockword other\n")));
    await(tabs(1));
    documents.open(List.of(file("b.txt", "mockword other\n")));
    await(tabs(2));
    documents.select(0);
    ui.runPending();
    WeakReference<Document> reference = new WeakReference<Document>(documents.activeDocument());
    documents.activeEditor().type("mockw");
    documents.activeEditor().execute(CommandId.COMPLETE_WORD);
    await(
        new BooleanSupplier() {
          @Override
          public boolean getAsBoolean() {
            return view.selected.title.startsWith("*")
                && !view.selected.shown.completions.isEmpty();
          }
        });
    documents.activeEditor().execute(CommandId.UNDO);
    ui.runPending();
    documents.closeActive();
    assertEquals(1, view.tabs.size());
    settle();
    assertCollected(reference);
  }

  @Test
  void loadsRunningTogetherShowAsOneAndCancelTogether() throws Exception {
    Path a = file("a.txt", "mock\n");
    Path b = file("b.txt", "mock\n");
    GatedCharset gate = gated();
    documents.open(List.of(a, b));
    await(
        new BooleanSupplier() {
          @Override
          public boolean getAsBoolean() {
            return UiText.LOAD_PROGRESS_FILES.format(2).equals(view.loadText);
          }
        });
    view.loadCancel.run();
    gate.release();
    await(noLoad());
    settle();
    assertTrue(view.tabs.isEmpty());
    assertEquals(0, budget.reserved());
  }
}
