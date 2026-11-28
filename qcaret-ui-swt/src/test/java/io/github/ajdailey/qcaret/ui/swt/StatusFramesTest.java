package io.github.ajdailey.qcaret.ui.swt;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assumptions.assumeTrue;

import io.github.ajdailey.qcaret.ui.swt.platform.RunningPlatform;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import org.eclipse.swt.SWT;
import org.eclipse.swt.graphics.GC;
import org.eclipse.swt.graphics.Image;
import org.eclipse.swt.graphics.ImageData;
import org.eclipse.swt.graphics.Rectangle;
import org.eclipse.swt.widgets.Composite;
import org.eclipse.swt.widgets.Control;
import org.eclipse.swt.widgets.Event;
import org.eclipse.swt.widgets.Listener;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/**
 * Reads the status bar's pixels at every point a status update can leave on screen: as each field
 * moves, resizes or paints, when the update returns, and as the event loop paints afterwards. Each
 * pixel column of the bar, the memory gauge left out, must show either what it showed before the
 * update or what it shows after it; a column showing neither is a frame mixing old and new text.
 * The frame after the update must match a full repaint. Checked on win32, where a label paints its
 * text as it is set: during a 20,000-iteration macro run the bar showed "Macro 57 of 20,00000",
 * "Macro 1,155 of 20,00C", "Ln 10178, Col 1" for Col 17 and an empty progress field for a frame.
 */
@Tag("swt")
class StatusFramesTest {
  private static final int LINES = 12_000;

  @TempDir Path temp;

  private MarkdownWindow w;
  private Composite bar;
  private boolean capturing;
  private String step = "";
  private final List<ImageData> frames = new ArrayList<ImageData>();
  private final List<String> points = new ArrayList<String>();
  private final List<String> torn = new ArrayList<String>();
  private final Listener capture =
      new Listener() {
        @Override
        public void handleEvent(Event event) {
          if (capturing
              && event.widget instanceof Control control
              && !control.isDisposed()
              && (control == bar || control.getParent() == bar)) {
            frames.add(read());
            points.add(type(event.type) + " " + control.getClass().getSimpleName());
          }
        }
      };

  @BeforeEach
  void open() {
    assumeTrue(RunningPlatform.WIN32, "a label paints as its text is set on win32");
    w = new MarkdownWindow(temp);
    bar = w.window.statusBar();
  }

  @AfterEach
  void close() {
    if (w != null) {
      w.display.removeFilter(SWT.Paint, capture);
      w.display.removeFilter(SWT.Move, capture);
      w.display.removeFilter(SWT.Resize, capture);
      w.close();
    }
  }

  @Test
  void aMacroProgressUpdateShowsNoMixedFrame() throws Exception {
    w.open("notes.txt", "plain\n", false);
    w.window.showMacroStatus(progress(1));
    settle();
    ImageData shown = read();
    w.window.showMacroStatus("");
    settle();
    assertNotEquals(columns(shown), columns(read()), "the capture sees the macro field");
    listen();
    int[] counts = {
      1, 5, 9, 10, 57, 58, 99, 100, 415, 999, 1_000, 1_155, 4_150, 9_999, 10_000, 17_717, 20_000,
      415, 57
    };
    for (int count : counts) {
      final String text = progress(count);
      update(
          text,
          new Runnable() {
            @Override
            public void run() {
              w.window.showMacroStatus(text);
            }
          });
    }
    assertEquals(List.of(), torn, "mixed frames");
  }

  @Test
  void aPositionUpdateShowsNoMixedFrame() throws Exception {
    StringBuilder text = new StringBuilder();
    for (int i = 0; i < LINES; i++) {
      text.append("x".repeat(20)).append('\n');
    }
    w.open("notes.txt", text.toString(), false);
    caret(0, 0);
    settle();
    listen();
    int[][] places = {
      {0, 0},
      {8, 16},
      {9, 0},
      {57, 16},
      {57, 1},
      {99, 4},
      {10_177, 16},
      {10_177, 0},
      {57, 16},
      {0, 0}
    };
    for (final int[] place : places) {
      update(
          "Ln " + (place[0] + 1) + ", Col " + (place[1] + 1),
          new Runnable() {
            @Override
            public void run() {
              caret(place[0], place[1]);
              w.window.showStatusNow();
            }
          });
    }
    assertEquals(List.of(), torn, "mixed frames");
  }

  private static String progress(int count) {
    return String.format(Locale.US, "Macro %,d of 20,000", count);
  }

  private void caret(int line, int column) {
    int offset = line * 21 + column;
    w.controller.documents().activeEditor().selectFromView(offset, offset);
  }

  /** Runs one update, reads the frames it leaves, and records each frame that mixes. */
  private void update(String what, Runnable action) {
    settle();
    ImageData before = read();
    frames.clear();
    points.clear();
    step = what;
    capturing = true;
    try {
      action.run();
      frames.add(read());
      points.add("returned");
      w.flush();
    } finally {
      capturing = false;
    }
    ImageData after = read();
    bar.redraw();
    for (Control child : bar.getChildren()) {
      child.redraw();
    }
    bar.update();
    for (Control child : bar.getChildren()) {
      child.update();
    }
    ImageData repainted = read();
    int wrong = firstDifferent(after, repainted, null);
    if (wrong >= 0) {
      torn.add(step + ": final frame differs from a repaint at x=" + wrong);
    }
    for (int i = 0; i < frames.size(); i++) {
      int x = firstDifferent(frames.get(i), before, after);
      if (x >= 0) {
        torn.add(step + ": " + points.get(i) + " at x=" + x + " " + owner(x));
      }
    }
    System.out.println(
        "StatusFramesTest " + step + ": " + frames.size() + " frames, torn so far " + torn.size());
  }

  /** Returns the first column of a frame equal to neither image, or -1; the gauge is skipped. */
  private int firstDifferent(ImageData frame, ImageData one, ImageData other) {
    Rectangle gauge = w.window.memoryGauge().control().getBounds();
    int width = Math.min(frame.width, one.width);
    for (int x = 0; x < width; x++) {
      if (x >= gauge.x && x < gauge.x + gauge.width) {
        continue;
      }
      if (!sameColumn(frame, one, x) && (other == null || !sameColumn(frame, other, x))) {
        return x;
      }
    }
    return -1;
  }

  private static boolean sameColumn(ImageData a, ImageData b, int x) {
    int height = Math.min(a.height, b.height);
    for (int y = 0; y < height; y++) {
      if (a.palette.getRGB(a.getPixel(x, y)).hashCode()
          != b.palette.getRGB(b.getPixel(x, y)).hashCode()) {
        return false;
      }
    }
    return true;
  }

  private static List<Integer> columns(ImageData data) {
    List<Integer> hashes = new ArrayList<Integer>();
    for (int x = 0; x < data.width; x++) {
      int hash = 0;
      for (int y = 0; y < data.height; y++) {
        hash = 31 * hash + data.palette.getRGB(data.getPixel(x, y)).hashCode();
      }
      hashes.add(hash);
    }
    return hashes;
  }

  private String owner(int x) {
    for (Control child : bar.getChildren()) {
      Rectangle bounds = child.getBounds();
      if (child.getVisible() && x >= bounds.x && x < bounds.x + bounds.width) {
        return child.getClass().getSimpleName() + " " + bounds;
      }
    }
    return "bar";
  }

  private static String type(int type) {
    return switch (type) {
      case SWT.Paint -> "Paint";
      case SWT.Move -> "Move";
      case SWT.Resize -> "Resize";
      default -> String.valueOf(type);
    };
  }

  /** Reads the bar's pixels from its window, the bits the next composed frame shows. */
  private ImageData read() {
    Rectangle size = bar.getBounds();
    Image image = new Image(w.display, Math.max(1, size.width), Math.max(1, size.height));
    try {
      GC gc = new GC(bar);
      try {
        gc.copyArea(image, 0, 0);
      } finally {
        gc.dispose();
      }
      return Pixels.read(image, bar);
    } finally {
      image.dispose();
    }
  }

  private void listen() {
    w.display.addFilter(SWT.Paint, capture);
    w.display.addFilter(SWT.Move, capture);
    w.display.addFilter(SWT.Resize, capture);
  }

  private void settle() {
    w.flush();
    w.window.showStatusNow();
    w.display.update();
    w.flush();
  }
}
