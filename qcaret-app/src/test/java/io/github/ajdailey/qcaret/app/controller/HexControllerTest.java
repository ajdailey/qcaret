package io.github.ajdailey.qcaret.app.controller;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import io.github.ajdailey.qcaret.app.i18n.UiText;
import io.github.ajdailey.qcaret.app.view.ClipboardService;
import io.github.ajdailey.qcaret.app.view.HexCopy;
import io.github.ajdailey.qcaret.app.view.HexKey;
import io.github.ajdailey.qcaret.app.view.HexSelection;
import io.github.ajdailey.qcaret.app.view.ManualUiDispatcher;
import io.github.ajdailey.qcaret.core.concurrent.Workers;
import io.github.ajdailey.qcaret.core.hex.HexBlocks;
import io.github.ajdailey.qcaret.core.hex.HexPattern;
import io.github.ajdailey.qcaret.core.hex.HexSource;
import io.github.ajdailey.qcaret.core.hex.HexSourceChangedException;
import java.io.IOException;
import java.nio.charset.Charset;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.ArrayList;
import java.util.BitSet;
import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.function.BooleanSupplier;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;

/**
 * {@link HexController} with a fake view and an in-memory source: rows read on demand and redrawn,
 * a read for a replaced source dropped, the caret and status, Copy formats and its bound, Go to
 * Offset, Find and a changed file.
 */
class HexControllerTest {
  private static final Duration BOUND = Duration.ofSeconds(20);

  private final ManualUiDispatcher ui = new ManualUiDispatcher();
  private final List<Throwable> failures = new CopyOnWriteArrayList<Throwable>();
  private final Workers workers =
      new Workers(
          2,
          new Thread.UncaughtExceptionHandler() {
            @Override
            public void uncaughtException(Thread thread, Throwable error) {
              failures.add(error);
            }
          });
  private final Host host = new Host();

  /** Bytes {@code i & 0xff}, or a given array; reads can wait on a gate or fail as changed. */
  private static final class Source implements HexSource {
    final long size;
    final byte[] bytes;
    volatile CountDownLatch gate;
    volatile boolean changed;
    final List<Long> reads = new CopyOnWriteArrayList<Long>();

    Source(long size) {
      this.size = size;
      this.bytes = null;
    }

    Source(byte[] bytes) {
      this.size = bytes.length;
      this.bytes = bytes;
    }

    @Override
    public long size() {
      return size;
    }

    @Override
    public Charset charset() {
      return StandardCharsets.ISO_8859_1;
    }

    @Override
    public void read(long position, byte[] dst, int length, BitSet replaced) throws IOException {
      reads.add(position);
      CountDownLatch wait = gate;
      if (wait != null) {
        try {
          wait.await(20, TimeUnit.SECONDS);
        } catch (InterruptedException e) {
          Thread.currentThread().interrupt();
        }
      }
      if (changed) {
        throw new HexSourceChangedException("changed");
      }
      for (int i = 0; i < length; i++) {
        dst[i] = bytes == null ? (byte) (position + i) : bytes[(int) (position + i)];
      }
      if (replaced != null) {
        replaced.clear(0, length);
      }
    }
  }

  private static final class Host implements HexController.Host, ClipboardService {
    String clipboard;
    final List<HexSelection> moves = new ArrayList<HexSelection>();
    int changed;
    int actions;
    int closed;
    boolean focused;

    @Override
    public ClipboardService clipboard() {
      return this;
    }

    @Override
    public void selectionChanged(HexSelection selection) {
      moves.add(selection);
    }

    @Override
    public void focusChanged(boolean value) {
      focused = value;
    }

    @Override
    public void sourceChanged() {
      changed++;
    }

    @Override
    public void noticeAction() {
      actions++;
    }

    @Override
    public void viewClosed() {
      closed++;
    }

    @Override
    public void setText(String text) {
      clipboard = text;
    }

    @Override
    public String clipboardText() {
      return clipboard;
    }
  }

  @AfterEach
  void close() {
    workers.close();
    assertTrue(failures.isEmpty(), failures.toString());
  }

  private HexController controller(HexSource source, FakeHexView[] view) {
    HexController c = new HexController(workers, ui, host, true, 16);
    c.setSource(source);
    view[0] = new FakeHexView(c, "t");
    c.attach(view[0]);
    c.resized(4);
    return c;
  }

  private void await(BooleanSupplier condition, String what) throws InterruptedException {
    assertTrue(ui.runUntil(condition, BOUND), what);
  }

  private void awaitIdle(final FakeHexView view) throws InterruptedException {
    await(
        new BooleanSupplier() {
          @Override
          public boolean getAsBoolean() {
            return view.active.get() == 0;
          }
        },
        "busy cursor idle");
    ui.runPending();
  }

  @Test
  void aMissingRowPaintsBlankThenTheReadRedraws() throws Exception {
    final FakeHexView[] v = new FakeHexView[1];
    Source source = new Source(1L << 40);
    source.gate = new CountDownLatch(1);
    HexController c = controller(source, v);
    final FakeHexView view = v[0];
    assertEquals(1L << 40, view.rows.size());
    assertEquals(16, view.rows.offsetDigits());
    assertEquals(0, view.paint());
    source.gate.countDown();
    await(
        new BooleanSupplier() {
          @Override
          public boolean getAsBoolean() {
            return view.redraws > 0;
          }
        },
        "redraw after the read");
    assertEquals(4, view.paint());
    assertEquals((byte) 0x11, c.row(1).bytes()[1]);
    c.scrollBy(1L << 34);
    assertEquals(1L << 34, view.scroll.topRow());
    assertTrue(view.scroll.maximum() > 0);
    assertNull(c.row(1L << 34));
  }

  @Test
  void aReadForAReplacedSourceIsDropped() throws Exception {
    final FakeHexView[] v = new FakeHexView[1];
    Source first = new Source(1000);
    first.gate = new CountDownLatch(1);
    HexController c = controller(first, v);
    final FakeHexView view = v[0];
    assertNull(c.row(0));
    await(
        new BooleanSupplier() {
          @Override
          public boolean getAsBoolean() {
            return !first.reads.isEmpty();
          }
        },
        "first read started");
    final Source second = new Source(2000);
    c.setSource(second);
    int redraws = view.redraws;
    first.gate.countDown();
    assertNull(c.row(0));
    await(
        new BooleanSupplier() {
          @Override
          public boolean getAsBoolean() {
            return view.redraws > redraws;
          }
        },
        "second source's read");
    assertNotNull(c.row(0));
    assertEquals(2000, view.rows.size());
  }

  @Test
  void keysClicksAndDragsMoveTheCaretAndReportIt() {
    final FakeHexView[] v = new FakeHexView[1];
    HexController c = controller(new Source(100), v);
    FakeHexView view = v[0];
    c.key(HexKey.DOWN, false);
    c.key(HexKey.RIGHT, true);
    assertEquals(new HexSelection(16, 17, false), view.selection);
    assertEquals(view.selection, host.moves.get(host.moves.size() - 1));
    assertTrue(view.status.contains("Selection 1 bytes"), view.status);
    c.clicked(40, true, false);
    c.dragged(43);
    assertEquals(new HexSelection(40, 44, true), view.selection);
    c.switchColumn();
    assertFalse(view.selection.textColumn());
    c.key(HexKey.SOURCE_END, false);
    assertEquals(3, view.scroll.topRow());
    c.select(3, 5);
    assertEquals(new HexSelection(3, 5, false), view.selection);
    assertEquals(new HexSelection(100, 100, false), host.moves.get(host.moves.size() - 1));
    c.focusChanged(true);
    assertTrue(host.focused);
    c.noticeAction();
    assertEquals(1, host.actions);
    c.attach(null);
    assertEquals(1, host.closed);
  }

  @Test
  void copyGivesHexTextOrTheOffset() throws Exception {
    byte[] bytes = "MZ\u0000\u0007ok".getBytes(StandardCharsets.ISO_8859_1);
    final FakeHexView[] v = new FakeHexView[1];
    HexController c = controller(new Source(bytes), v);
    c.select(0, 4);
    c.copy(HexCopy.HEX);
    awaitIdle(v[0]);
    assertEquals("4D 5A 00 07", host.clipboard);
    c.copy(HexCopy.TEXT);
    awaitIdle(v[0]);
    assertEquals("MZ\u0000\u0007", host.clipboard);
    c.switchColumn();
    c.select(4, 6);
    c.copy(HexCopy.FOCUSED);
    awaitIdle(v[0]);
    assertEquals("ok", host.clipboard);
    c.copy(HexCopy.OFFSET);
    assertEquals("00000006", host.clipboard);
  }

  @Test
  void aSelectionOverTheBoundIsNotCopied() {
    final FakeHexView[] v = new FakeHexView[1];
    Source source = new Source(1L << 40);
    HexController c = controller(source, v);
    c.select(0, HexCopier.MAX_BYTES + 1L);
    host.clipboard = "kept";
    c.copy(HexCopy.HEX);
    assertEquals("kept", host.clipboard);
    assertEquals(UiText.HEX_COPY_TOO_LARGE.format(16), v[0].message);
    assertTrue(source.reads.isEmpty());
  }

  @Test
  void goToOffsetAcceptsDecimalHexAndRelativeOffsets() {
    final FakeHexView[] v = new FakeHexView[1];
    HexController c = controller(new Source(300), v);
    FakeHexView view = v[0];
    c.goToOffset();
    assertTrue(view.offsetValid.test("0x100"));
    assertFalse(view.offsetValid.test("301"));
    view.offsetAnswer.accept("0x100");
    assertEquals(256, view.selection.caret());
    assertEquals(13, view.scroll.topRow());
    c.goToOffset();
    view.offsetAnswer.accept("-6");
    assertEquals(250, view.selection.caret());
  }

  @Test
  void findWalksMatchesBothWaysAndWraps() throws Exception {
    byte[] bytes = new byte[200];
    bytes[10] = 0x4d;
    bytes[11] = 0x5a;
    bytes[150] = 0x4d;
    bytes[151] = 0x5a;
    final FakeHexView[] v = new FakeHexView[1];
    HexController c = controller(new Source(bytes), v);
    FakeHexView view = v[0];
    c.find("4D 5A", true, true);
    awaitIdle(view);
    assertEquals(new HexSelection(10, 12, false), view.selection);
    assertEquals("", view.message);
    c.findAgain(true);
    awaitIdle(view);
    assertEquals(new HexSelection(150, 152, false), view.selection);
    c.findAgain(true);
    awaitIdle(view);
    assertEquals(new HexSelection(10, 12, false), view.selection);
    assertEquals(UiText.HEX_FIND_WRAPPED.text(), view.message);
    c.findAgain(false);
    awaitIdle(view);
    assertEquals(150, view.selection.start());
    assertEquals(UiText.HEX_FIND_WRAPPED.text(), view.message);
    c.find("MZ", false, false);
    awaitIdle(view);
    assertEquals(10, view.selection.start());
    c.find("4D ?? 01", true, true);
    awaitIdle(view);
    assertEquals(UiText.HEX_FIND_NOT_FOUND.text(), view.message);
    c.openFind();
    assertEquals("4D ?? 01", view.findText);
    assertTrue(view.findHex);
  }

  @Test
  void aRefusedPatternNamesWhy() {
    final FakeHexView[] v = new FakeHexView[1];
    HexController c = controller(new Source(10), v);
    c.find("4D 5", true, true);
    assertEquals(UiText.HEX_PATTERN_ODD_DIGITS.format(4), v[0].message);
    c.find("?? ??", true, true);
    assertEquals(UiText.HEX_PATTERN_WILDCARDS_ONLY.text(), v[0].message);
    c.find("", false, true);
    assertEquals(UiText.HEX_PATTERN_EMPTY.text(), v[0].message);
    StringBuilder big = new StringBuilder();
    for (int i = 0; i <= HexPattern.MAX_LENGTH; i++) {
      big.append("00");
    }
    c.find(big.toString(), true, true);
    assertEquals(UiText.HEX_PATTERN_TOO_LONG.format(HexPattern.MAX_LENGTH), v[0].message);
    c.find("€", false, true);
    assertTrue(v[0].message.startsWith("ISO-8859-1"), v[0].message);
  }

  @Test
  void escStopsARunningFind() throws Exception {
    final FakeHexView[] v = new FakeHexView[1];
    final Source source = new Source(4L * HexBlocks.BLOCK_BYTES);
    source.gate = new CountDownLatch(1);
    HexController c = controller(source, v);
    c.find("FF FE FD", true, true);
    await(
        new BooleanSupplier() {
          @Override
          public boolean getAsBoolean() {
            return !source.reads.isEmpty();
          }
        },
        "find read");
    c.cancel();
    source.gate.countDown();
    awaitIdle(v[0]);
    assertEquals("", v[0].message);
    assertEquals(0, v[0].selection.caret());
  }

  @Test
  void aChangedFileStopsReadsAndTellsTheHost() throws Exception {
    final FakeHexView[] v = new FakeHexView[1];
    Source source = new Source(100);
    source.changed = true;
    HexController c = controller(source, v);
    assertNull(c.row(0));
    await(
        new BooleanSupplier() {
          @Override
          public boolean getAsBoolean() {
            return host.changed == 1;
          }
        },
        "change reported");
    int reads = source.reads.size();
    assertNull(c.row(0));
    ui.runPending();
    assertEquals(reads, source.reads.size());
    c.notice(UiText.HEX_CHANGED_ON_DISK.text(), UiText.HEX_RELOAD.text());
    assertEquals(UiText.HEX_RELOAD.text(), v[0].noticeAction);
  }

  @Test
  void bytesPerRowAndDimmingReachTheView() {
    final FakeHexView[] v = new FakeHexView[1];
    HexController c = controller(new Source(100), v);
    c.setBytesPerRow(32);
    assertEquals(32, v[0].rows.bytesPerRow());
    assertEquals(32, c.bytesPerRow());
    c.setDimmed(true);
    assertTrue(v[0].dimmed);
    FakeHexView again = new FakeHexView(c, "t");
    c.attach(again);
    assertTrue(again.dimmed);
    assertEquals(32, again.rows.bytesPerRow());
  }
}
