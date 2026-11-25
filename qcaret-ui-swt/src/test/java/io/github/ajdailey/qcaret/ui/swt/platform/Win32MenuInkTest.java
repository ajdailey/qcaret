package io.github.ajdailey.qcaret.ui.swt.platform;

import static org.junit.jupiter.api.Assertions.assertEquals;

import java.util.List;
import org.eclipse.swt.widgets.Display;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;

/**
 * {@link Win32MenuBehavior#ink} on any platform: the icons take the ink of the side the Display
 * started with, which win32 pop-up menus keep, and not the side of a later live switch.
 */
@Tag("swt")
class Win32MenuInkTest {
  private static final MenuBehavior.Ink<String> INK =
      new MenuBehavior.Ink<String>() {
        @Override
        public String live() {
          return "live";
        }

        @Override
        public String fixed(boolean dark) {
          return dark ? "light ink" : "dark ink";
        }
      };

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

  private List<String> inks(boolean start) {
    MenuBehavior menus = new Win32MenuBehavior();
    NativeTheme.set(display, start);
    String atStart = menus.ink(display, INK);
    NativeTheme.set(display, !start);
    String switched = menus.ink(display, INK);
    NativeTheme.set(display, start);
    NativeTheme.set(display, !start);
    return List.of(atStart, switched, menus.ink(display, INK));
  }

  @Test
  void aDarkStartKeepsTheLightInkAfterASwitchToLight() {
    assertEquals(List.of("light ink", "light ink", "light ink"), inks(true));
  }

  @Test
  void aLightStartKeepsTheDarkInkAfterASwitchToDark() {
    assertEquals(List.of("dark ink", "dark ink", "dark ink"), inks(false));
  }
}
