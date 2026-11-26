package io.github.ajdailey.qcaret.ui.swt.platform;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.List;
import org.eclipse.swt.widgets.Display;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledOnOs;
import org.junit.jupiter.api.condition.OS;

/**
 * {@link Win32MenuThemes}: the foreign-function names it resolves exist on this JDK, on win32
 * uxtheme ordinal 136 links and a flush runs, and the menu side follows a flush. A failure after a
 * JDK or Windows upgrade names the member that went away.
 */
@Tag("swt")
class Win32MenuThemesTest {
  private Display display;

  @BeforeEach
  void open() {
    display = new Display();
  }

  @AfterEach
  void close() {
    display.setDarkThemePreferred(false);
    display.dispose();
  }

  @Test
  void theForeignFunctionNamesResolve() throws ReflectiveOperationException {
    new Win32MenuThemes(Win32MenuThemes.FOREIGN);
  }

  @Test
  @EnabledOnOs(OS.WINDOWS)
  void flushMenuThemesLinksAndRuns() throws ReflectiveOperationException {
    assertNotEquals(0L, new Win32MenuThemes(Win32MenuThemes.FOREIGN).link());
    NativeTheme.set(display, false);
    NativeTheme.set(display, true);
    assertTrue(Win32MenuThemes.flush(display, true));
    assertTrue(Win32MenuThemes.shownDark(display));
    NativeTheme.set(display, false);
    assertTrue(Win32MenuThemes.flush(display, false));
    assertEquals(false, Win32MenuThemes.shownDark(display));
  }

  /** Menus show the start side until a flush, then the flushed side. */
  @Test
  void theShownSideFollowsAFlush() {
    NativeTheme.set(display, true);
    boolean atStart = Win32MenuThemes.shownDark(display);
    NativeTheme.set(display, false);
    boolean switched = Win32MenuThemes.shownDark(display);
    Win32MenuThemes.shown(display, false);
    boolean flushed = Win32MenuThemes.shownDark(display);
    assertEquals(List.of(true, true, false), List.of(atStart, switched, flushed));
  }
}
