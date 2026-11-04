package io.github.ajdailey.qcaret.app.view;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import io.github.ajdailey.qcaret.app.view.WindowGeometry.Placement;
import io.github.ajdailey.qcaret.app.view.WindowGeometry.Rect;
import io.github.ajdailey.qcaret.app.view.WindowGeometry.Request;
import io.github.ajdailey.qcaret.app.view.WindowGeometry.Screen;
import java.util.List;
import org.junit.jupiter.api.Test;

class WindowGeometryTest {
  private static final Screen PRIMARY =
      new Screen(new Rect(0, 0, 1920, 1080), new Rect(0, 0, 1920, 1040));
  private static final Screen RIGHT =
      new Screen(new Rect(1920, 0, 1280, 1024), new Rect(1920, 0, 1280, 1024));
  private static final List<Screen> SCREENS = List.of(PRIMARY, RIGHT);
  private static final Request NONE = new Request(null, null, null, null);

  private static Placement place(Request requested, Request persisted) {
    return WindowGeometry.place(requested, persisted, SCREENS, PRIMARY, 10, 30, true);
  }

  @Test
  void defaultSizeWithoutPosition() {
    Placement p = place(NONE, null);
    assertEquals(1034, p.bounds().width());
    assertEquals(798, p.bounds().height());
    assertFalse(p.setLocation());
  }

  @Test
  void requestedSizeAndPositionAddTrim() {
    Placement p = place(new Request(50, 40, 900, 600), null);
    assertEquals(new Rect(50, 40, 910, 630), p.bounds());
    assertTrue(p.setLocation());
  }

  @Test
  void commandLineWinsPerDimension() {
    Placement p = place(new Request(null, null, 900, null), new Request(100, 100, 400, 300));
    assertEquals(new Rect(100, 100, 910, 330), p.bounds());
  }

  @Test
  void hugeSizeClampsToWorkArea() {
    Placement p = place(new Request(null, null, 100000, 100000), null);
    assertEquals(1920, p.bounds().width());
    assertEquals(1040, p.bounds().height());
  }

  @Test
  void locationClampsInsideTheChosenMonitor() {
    Placement p = place(new Request(3000, 900, 800, 600), null);
    assertEquals(new Rect(2390, 394, 810, 630), p.bounds());
  }

  @Test
  void positionOnMissingMonitorLandsOnPrimary() {
    Placement p = place(NONE, new Request(9000, 9000, 800, 600));
    assertEquals(new Rect(1110, 410, 810, 630), p.bounds());
  }

  @Test
  void waylandSkipsPosition() {
    Placement p =
        WindowGeometry.place(new Request(50, 40, 900, 600), null, SCREENS, PRIMARY, 0, 0, false);
    assertFalse(p.setLocation());
    assertEquals(900, p.bounds().width());
  }
}
