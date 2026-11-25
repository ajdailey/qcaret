package io.github.ajdailey.qcaret.ui.swt;

import static org.junit.jupiter.api.Assertions.assertTrue;

import io.github.ajdailey.qcaret.app.view.BookmarkRow;
import io.github.ajdailey.qcaret.app.view.BookmarksInput;
import io.github.ajdailey.qcaret.app.view.OutlineEntry;
import io.github.ajdailey.qcaret.app.view.OutlineInput;
import java.util.List;
import org.eclipse.swt.SWT;
import org.eclipse.swt.graphics.GC;
import org.eclipse.swt.graphics.Point;
import org.eclipse.swt.graphics.Rectangle;
import org.eclipse.swt.widgets.Composite;
import org.eclipse.swt.widgets.Control;
import org.eclipse.swt.widgets.Display;
import org.eclipse.swt.widgets.Label;
import org.eclipse.swt.widgets.Shell;
import org.eclipse.swt.widgets.Table;
import org.eclipse.swt.widgets.TableColumn;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;

/**
 * The Bookmarks and Outline docks at widths narrower than their button row and summary: the header
 * wraps within the dock and shows the whole summary, and the Bookmarks columns fit the table. Each
 * dock is sized inside a shell with no layout.
 */
@Tag("swt")
class DockFitSurfaceTest {
  private static final String FILE_SUMMARY = "f19.txt: 0 bookmarks";

  private static final String LONG_SUMMARY =
      "a-mock-document-with-a-long-name.md: 1,234 headings in the outline of this mock file";

  private Display display;
  private Shell shell;

  @BeforeEach
  void open() {
    display = new Display();
    shell = new Shell(display);
    shell.setLayout(null);
    shell.setSize(1200, 700);
    shell.open();
  }

  @AfterEach
  void close() {
    display.dispose();
  }

  private void flush() {
    while (display.readAndDispatch()) {
      // Runs queued work.
    }
  }

  private BookmarksDock bookmarks(final String summary) {
    BookmarksDock dock = new BookmarksDock(shell);
    dock.setInput(
        new BookmarksInput() {
          @Override
          public int rowCount() {
            return 0;
          }

          @Override
          public BookmarkRow row(int index) {
            throw new IndexOutOfBoundsException(index);
          }

          @Override
          public String summary() {
            return summary;
          }

          @Override
          public void goTo(int index) {}

          @Override
          public void editComment(int index) {}

          @Override
          public void remove(int index) {}

          @Override
          public void copyLine(int index) {}

          @Override
          public void clearAll() {}

          @Override
          public void hide() {}
        });
    return dock;
  }

  private OutlineDock outline(final String summary) {
    OutlineDock dock = new OutlineDock(shell, new SwtUiDispatcher(display));
    dock.setInput(
        new OutlineInput() {
          @Override
          public List<OutlineEntry> entries() {
            return List.of();
          }

          @Override
          public String summary() {
            return summary;
          }

          @Override
          public int selected() {
            return -1;
          }

          @Override
          public void goTo(int index, boolean focus) {}

          @Override
          public void hide() {}
        });
    return dock;
  }

  /** Sizes a dock and returns its header row. */
  private Composite size(Composite dock, int width) {
    dock.setBounds(0, 0, width, 400);
    dock.layout(true, true);
    flush();
    return (Composite) dock.getChildren()[0];
  }

  /**
   * Asserts that every header control lies within the dock, that the summary shows its whole text,
   * and that the control below starts under the header.
   */
  private static void assertFits(Composite dock, Composite header, String summary) {
    int slack = ScaleSlack.of(dock);
    int width = dock.getClientArea().width;
    assertTrue(header.getBounds().x + header.getBounds().width <= width + slack, "header");
    Label label = null;
    for (Control child : header.getChildren()) {
      Rectangle bounds = child.getBounds();
      assertTrue(bounds.x >= 0 && bounds.x + bounds.width <= width + slack, child + " " + bounds);
      if (child instanceof Label found && summary.equals(found.getText())) {
        label = found;
      }
    }
    assertTrue(label != null, "the summary label");
    Point size = label.getSize();
    Point whole = label.computeSize(size.x, SWT.DEFAULT);
    assertTrue(size.y + slack >= whole.y, "summary height " + size + " for " + whole);
    Point line = label.computeSize(SWT.DEFAULT, SWT.DEFAULT);
    assertTrue(size.x + slack >= Math.min(line.x, width), "summary width " + size + " for " + line);
    assertTrue(
        header.getBounds().y + header.getBounds().height + slack >= label.getBounds().y + size.y,
        "summary inside the header");
    Control below = dock.getChildren()[1];
    assertTrue(
        below.getBounds().y + slack >= header.getBounds().y + header.getBounds().height,
        "below " + below.getBounds() + ", header " + header.getBounds());
  }

  @Test
  void theBookmarksHeaderWrapsWithinTheDock() {
    BookmarksDock dock = bookmarks(FILE_SUMMARY);
    Composite root = dock.control();
    int row = root.computeSize(SWT.DEFAULT, SWT.DEFAULT).x;
    for (int width : new int[] {row + 40, row * 2 / 3, row / 3, 60}) {
      assertFits(root, size(root, width), FILE_SUMMARY);
    }
  }

  @Test
  void theOutlineHeaderStaysWithinTheDock() {
    OutlineDock dock = outline(LONG_SUMMARY);
    Composite root = dock.control();
    int row = root.computeSize(SWT.DEFAULT, SWT.DEFAULT).x;
    for (int width : new int[] {row + 40, 250, 120}) {
      assertFits(root, size(root, width), LONG_SUMMARY);
    }
  }

  /** The columns fill the table's width without a horizontal scroll bar's worth of overflow. */
  @Test
  void theBookmarksColumnsFitTheTable() {
    BookmarksDock dock = bookmarks(FILE_SUMMARY);
    Composite root = dock.control();
    for (int width : new int[] {260, 400, 900}) {
      size(root, width);
      Table table = dock.table();
      int slack = ScaleSlack.of(table);
      int client = table.getClientArea().width;
      int sum = 0;
      for (int i = 0; i < table.getColumnCount(); i++) {
        int column = table.getColumn(i).getWidth();
        assertTrue(column > 0, "column " + i + " at " + width);
        sum += column;
      }
      assertTrue(sum <= client + slack, "columns " + sum + " in " + client + " at " + width);
      int bar = table.getVerticalBar() == null ? 0 : table.getVerticalBar().getSize().x;
      assertTrue(
          sum >= client - bar - 2 - slack, "columns " + sum + " in " + client + " at " + width);
    }
  }

  /**
   * Comment and Text keep their header text whole at a dock width that holds both: win32's
   * custom-drawn dark header clips with no ellipsis (G1 at 150%: "Commer").
   */
  @Test
  void theBookmarksColumnsShowTheirWholeHeaders() {
    BookmarksDock dock = bookmarks(FILE_SUMMARY);
    Table table = dock.table();
    assertTrue((table.getColumn(0).getStyle() & SWT.LEFT) != 0, "Line left-aligned");
    for (int width : new int[] {260, 400}) {
      size(dock.control(), width);
      GC gc = new GC(table);
      try {
        for (int i = 1; i < table.getColumnCount(); i++) {
          TableColumn column = table.getColumn(i);
          int header = gc.textExtent(column.getText()).x + TableFit.HEADER_PADDING;
          assertTrue(
              column.getWidth() >= header,
              column.getText() + " " + column.getWidth() + " < " + header + " at " + width);
        }
      } finally {
        gc.dispose();
      }
    }
  }
}
