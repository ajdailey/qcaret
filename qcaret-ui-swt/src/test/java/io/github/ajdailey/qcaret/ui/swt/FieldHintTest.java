package io.github.ajdailey.qcaret.ui.swt;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.time.Duration;
import java.util.function.BooleanSupplier;
import org.eclipse.swt.SWT;
import org.eclipse.swt.graphics.Color;
import org.eclipse.swt.graphics.GC;
import org.eclipse.swt.graphics.Image;
import org.eclipse.swt.graphics.ImageData;
import org.eclipse.swt.graphics.Point;
import org.eclipse.swt.graphics.RGB;
import org.eclipse.swt.layout.GridData;
import org.eclipse.swt.layout.GridLayout;
import org.eclipse.swt.widgets.Button;
import org.eclipse.swt.widgets.Display;
import org.eclipse.swt.widgets.Shell;
import org.eclipse.swt.widgets.Text;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledOnOs;
import org.junit.jupiter.api.condition.OS;

/**
 * A {@link FieldHint} with a color draws the hint in it over an empty unfocused field, read from
 * the screen, and draws nothing once the field has text or focus.
 */
@Tag("swt")
@EnabledOnOs(OS.WINDOWS)
class FieldHintTest {
  private static final Duration BOUND = Duration.ofSeconds(5);
  private static final RGB HINT = new RGB(255, 0, 0);

  private Display display;
  private Shell shell;
  private Text field;
  private Button other;

  @BeforeEach
  void open() {
    display = new Display();
    shell = new Shell(display);
    shell.setLayout(new GridLayout(1, false));
    other = new Button(shell, SWT.PUSH);
    other.setText("other");
    field = new Text(shell, SWT.SINGLE | SWT.BORDER);
    field.setLayoutData(new GridData(200, SWT.DEFAULT));
    field.setBackground(new Color(0, 0, 0));
    FieldHint hint = new FieldHint(field);
    hint.setHint("WWWW");
    hint.setColor(new Color(HINT));
    assertEquals("", field.getMessage());
    shell.setSize(400, 240);
    shell.open();
    other.setFocus();
  }

  @AfterEach
  void close() {
    display.dispose();
  }

  /** Returns whether a screen read of the field shows a pixel near the hint's red. */
  private boolean showsHint() {
    display.update();
    Point origin = field.toDisplay(0, 0);
    Point size = field.getSize();
    Image image = new Image(display, size.x, size.y);
    GC gc = new GC(display);
    ImageData data;
    try {
      gc.copyArea(image, origin.x, origin.y);
      data = Pixels.read(image, shell);
    } finally {
      gc.dispose();
      image.dispose();
    }
    for (int y = 0; y < data.height; y++) {
      for (int x = 0; x < data.width; x++) {
        RGB rgb = data.palette.getRGB(data.getPixel(x, y));
        if (rgb.red > 200 && rgb.green < 60 && rgb.blue < 60) {
          return true;
        }
      }
    }
    return false;
  }

  private boolean within(final boolean shown) {
    return EventLoop.runUntil(
        display,
        new BooleanSupplier() {
          @Override
          public boolean getAsBoolean() {
            return showsHint() == shown;
          }
        },
        BOUND);
  }

  @Test
  void theHintShowsOnlyWhileTheFieldIsEmptyAndUnfocused() {
    ScreenProbe.assumeShows(shell);
    assertTrue(within(true), "hint over the empty field");
    field.setText("x");
    assertTrue(within(false), "no hint over text");
    field.setText("");
    assertTrue(within(true), "hint again");
    field.setFocus();
    assertTrue(within(false), "no hint while focused");
  }
}
