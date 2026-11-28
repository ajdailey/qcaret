package io.github.ajdailey.qcaret.ui.swt;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assumptions.assumeTrue;

import io.github.ajdailey.qcaret.ui.swt.platform.RunningPlatform;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import org.eclipse.swt.SWT;
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
 * A status field whose text changes width moves the fields after it, and the status bar paints
 * before the update returns, with no paint left for the event loop: a paint inside {@link
 * StatusUpdates#layout}, from a later update the loop runs, counts as painted at once; any other
 * names its nearest QCaret caller. On win32 a label paints its new text as it is set; a paint left
 * for later showed the old text beside the new for a frame during a macro run ("Col 1717
 * UTF-8`-8CRLFLF"). Checked on win32, where {@code Control.update} paints at once.
 */
@Tag("swt")
class StatusPaintTest {
  @TempDir Path temp;

  private MarkdownWindow w;
  private final List<String> paints = new ArrayList<String>();
  private final Listener paint =
      new Listener() {
        @Override
        public void handleEvent(Event event) {
          String caller = "";
          for (StackTraceElement frame : new Throwable().getStackTrace()) {
            String type = frame.getClassName();
            if (type.equals(StatusUpdates.class.getName())
                && frame.getMethodName().equals("layout")) {
              return;
            }
            if (caller.isEmpty()
                && type.startsWith("io.github.ajdailey.qcaret.")
                && !type.startsWith(StatusPaintTest.class.getName())) {
              caller = frame.getClassName() + "." + frame.getMethodName();
            }
          }
          paints.add(event.widget + " from " + caller);
        }
      };

  @BeforeEach
  void open() {
    assumeTrue(RunningPlatform.WIN32, "paints at once on win32");
    w = new MarkdownWindow(temp);
  }

  @AfterEach
  void close() {
    if (w != null) {
      w.close();
    }
  }

  @Test
  void aNarrowerPositionPaintsTheStatusBarAtOnce() throws Exception {
    w.open("notes.txt", "x".repeat(2000) + "\n", false);
    caret(1717);
    settle();
    listen();
    caret(0);
    w.window.showStatusNow();
    w.flush();
    assertEquals(List.of(), paints, "paints after the update");
  }

  @Test
  void aShorterMacroStatusPaintsTheStatusBarAtOnce() throws Exception {
    w.open("notes.txt", "plain\n", false);
    w.window.showMacroStatus("Macro 4,150 of 20,000");
    settle();
    listen();
    w.window.showMacroStatus("Macro 415 of 20,000");
    w.flush();
    assertEquals(List.of(), paints, "paints after the update");
  }

  private void caret(int offset) {
    w.controller.documents().activeEditor().selectFromView(offset, offset);
  }

  private void settle() {
    w.flush();
    w.window.showStatusNow();
    w.display.update();
    w.flush();
  }

  private void listen() {
    Composite bar = w.window.statusBar();
    bar.addListener(SWT.Paint, paint);
    for (Control child : bar.getChildren()) {
      child.addListener(SWT.Paint, paint);
    }
  }
}
