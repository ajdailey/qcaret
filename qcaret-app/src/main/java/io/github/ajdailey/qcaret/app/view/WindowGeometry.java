package io.github.ajdailey.qcaret.app.view;

import java.util.List;

/**
 * Places the main window before it first opens. Per dimension the command line wins over the
 * persisted geometry, which wins over 1024 x 768; the size is the client area and the trim is added
 * to it. The monitor is the one containing the requested or persisted top-left corner, else the
 * primary. The window is clamped to that monitor's work area, size first, then location.
 */
public final class WindowGeometry {
  /** Client-area width without a requested or persisted one. */
  public static final int DEFAULT_WIDTH = 1024;

  /** Client-area height without a requested or persisted one. */
  public static final int DEFAULT_HEIGHT = 768;

  private WindowGeometry() {}

  /**
   * A rectangle in display coordinates.
   *
   * @param x left edge
   * @param y top edge
   * @param width width
   * @param height height
   */
  public record Rect(int x, int y, int width, int height) {
    boolean contains(int px, int py) {
      return px >= x && py >= y && px < x + width && py < y + height;
    }
  }

  /**
   * One monitor.
   *
   * @param bounds the whole monitor
   * @param workArea the monitor less taskbar, menu bar and Dock
   */
  public record Screen(Rect bounds, Rect workArea) {}

  /**
   * The requested geometry. Each value is null when not given.
   *
   * @param x left edge of the window
   * @param y top edge of the window
   * @param width client-area width
   * @param height client-area height
   */
  public record Request(Integer x, Integer y, Integer width, Integer height) {}

  /**
   * The result.
   *
   * @param bounds outer window bounds; x and y are meaningful only when {@code setLocation}
   * @param setLocation true when the caller sets the location; false leaves placement to the window
   *     system
   */
  public record Placement(Rect bounds, boolean setLocation) {}

  /**
   * Computes the placement.
   *
   * @param requested from the command line
   * @param persisted from the previous session, or null
   * @param screens every monitor, not empty
   * @param primary the primary monitor
   * @param trimWidth outer width minus client width
   * @param trimHeight outer height minus client height
   * @param positionSupported false where the window system ignores a requested position (Wayland)
   * @return the placement
   */
  public static Placement place(
      Request requested,
      Request persisted,
      List<Screen> screens,
      Screen primary,
      int trimWidth,
      int trimHeight,
      boolean positionSupported) {
    Request saved = persisted == null ? new Request(null, null, null, null) : persisted;
    int width = first(requested.width(), saved.width(), DEFAULT_WIDTH);
    int height = first(requested.height(), saved.height(), DEFAULT_HEIGHT);
    Integer x = requested.x() != null ? requested.x() : saved.x();
    Integer y = requested.y() != null ? requested.y() : saved.y();
    boolean positioned = x != null || y != null;

    Screen screen = primary;
    if (positioned) {
      int px = x != null ? x.intValue() : primary.workArea().x();
      int py = y != null ? y.intValue() : primary.workArea().y();
      for (Screen candidate : screens) {
        if (candidate.bounds().contains(px, py)) {
          screen = candidate;
          break;
        }
      }
    }
    Rect work = screen.workArea();
    int outerWidth = clamp(width + trimWidth, 1, Math.max(1, work.width()));
    int outerHeight = clamp(height + trimHeight, 1, Math.max(1, work.height()));
    if (!positioned) {
      return new Placement(new Rect(work.x(), work.y(), outerWidth, outerHeight), false);
    }
    int left = x != null ? x.intValue() : work.x();
    int top = y != null ? y.intValue() : work.y();
    left = clamp(left, work.x(), work.x() + work.width() - outerWidth);
    top = clamp(top, work.y(), work.y() + work.height() - outerHeight);
    return new Placement(new Rect(left, top, outerWidth, outerHeight), positionSupported);
  }

  private static int first(Integer a, Integer b, int fallback) {
    if (a != null) {
      return a.intValue();
    }
    return b != null ? b.intValue() : fallback;
  }

  private static int clamp(long value, int min, int max) {
    if (value < min) {
      return min;
    }
    return value > max ? max : (int) value;
  }
}
