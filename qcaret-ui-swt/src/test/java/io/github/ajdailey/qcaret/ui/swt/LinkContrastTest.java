package io.github.ajdailey.qcaret.ui.swt;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import org.eclipse.swt.graphics.RGB;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;

/** {@link SwtThemeService#readable} on measured theme colors. */
@Tag("swt")
class LinkContrastTest {
  private static final double READABLE = 4.5;
  private static final RGB ADWAITA_DARK_GROUND = new RGB(53, 53, 53);
  private static final RGB ADWAITA_DARK_LINK = new RGB(53, 132, 228);

  @Test
  void anUnreadableLinkLightensOnADarkGround() {
    RGB moved = SwtThemeService.readable(ADWAITA_DARK_LINK, ADWAITA_DARK_GROUND);
    assertTrue(SwtThemeService.contrast(moved, ADWAITA_DARK_GROUND) >= READABLE);
    assertTrue(moved.red > ADWAITA_DARK_LINK.red);
  }

  @Test
  void anUnreadableLinkDarkensOnALightGround() {
    RGB ground = new RGB(246, 245, 244);
    RGB link = new RGB(120, 170, 240);
    RGB moved = SwtThemeService.readable(link, ground);
    assertTrue(SwtThemeService.contrast(moved, ground) >= READABLE);
    assertTrue(moved.red < link.red);
  }

  @Test
  void aReadableLinkKeepsItsColor() {
    RGB link = new RGB(26, 95, 180);
    RGB ground = new RGB(246, 245, 244);
    assertEquals(link, SwtThemeService.readable(link, ground));
  }
}
