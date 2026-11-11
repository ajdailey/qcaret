package io.github.ajdailey.qcaret.app.theme;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertTrue;

import io.github.ajdailey.qcaret.app.config.ConfigStore;
import io.github.ajdailey.qcaret.app.config.Preferences;
import io.github.ajdailey.qcaret.app.config.SettingsLocation;
import io.github.ajdailey.qcaret.app.config.SettingsMode;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class ThemeControllerTest {
  @TempDir Path temp;

  /** A system setting the test flips. */
  private static final class FakeSystem implements ThemeService {
    boolean dark;
    final List<ThemeListener> listeners = new ArrayList<ThemeListener>();
    final List<Boolean> preferred = new ArrayList<Boolean>();
    boolean live = true;

    @Override
    public boolean systemDark() {
      return dark;
    }

    @Override
    public void addListener(ThemeListener listener) {
      listeners.add(listener);
    }

    @Override
    public void preferDark(boolean value) {
      preferred.add(value);
    }

    @Override
    public boolean showsNow(boolean value) {
      return live;
    }

    void flip(boolean value) {
      dark = value;
      for (ThemeListener listener : listeners) {
        listener.systemThemeChanged(value);
      }
    }
  }

  @Test
  void modesResolveToPalettes() {
    assertSame(Palette.LIGHT, ThemeController.resolve(ThemeMode.LIGHT, true));
    assertSame(Palette.DARK, ThemeController.resolve(ThemeMode.DARK, false));
    assertSame(Palette.DARK, ThemeController.resolve(ThemeMode.SYSTEM, true));
    assertSame(Palette.LIGHT, ThemeController.resolve(ThemeMode.SYSTEM, false));
  }

  @Test
  void systemModeFollowsTheSystemAndListenersSeeEachChange() {
    FakeSystem system = new FakeSystem();
    ThemeController theme = new ThemeController(system, ThemeMode.SYSTEM, EditorSchemes.FOLLOW);
    final List<Palette> seen = new ArrayList<Palette>();
    theme.addListener(
        new PaletteListener() {
          @Override
          public void paletteChanged(Palette palette) {
            seen.add(palette);
          }
        });
    assertSame(Palette.LIGHT, theme.palette());
    system.flip(true);
    system.flip(true);
    theme.setMode(ThemeMode.LIGHT);
    system.flip(false);
    theme.setMode(ThemeMode.DARK);
    assertEquals(List.of(Palette.DARK, Palette.LIGHT, Palette.DARK), seen);
    assertEquals(ThemeMode.DARK, theme.mode());
  }

  @Test
  void nativeControlsFollowEachModeChangeAndTheSystemOnlyInSystemMode() {
    FakeSystem system = new FakeSystem();
    ThemeController theme = new ThemeController(system, ThemeMode.LIGHT, EditorSchemes.FOLLOW);
    assertEquals(List.of(), system.preferred);
    theme.setMode(ThemeMode.DARK);
    system.flip(true);
    theme.setMode(ThemeMode.SYSTEM);
    system.flip(false);
    theme.setMode(ThemeMode.LIGHT);
    system.flip(true);
    assertEquals(List.of(true, true, false, false), system.preferred);
  }

  @Test
  void setModeReportsWhetherNativeControlsChangeNow() {
    FakeSystem system = new FakeSystem();
    ThemeController theme = new ThemeController(system, ThemeMode.LIGHT, EditorSchemes.FOLLOW);
    assertTrue(theme.setMode(ThemeMode.DARK));
    system.live = false;
    assertFalse(theme.setMode(ThemeMode.LIGHT));
  }

  @Test
  void themeColorsApplyToEachModeAndPushOnlyAChange() {
    FakeSystem system = new FakeSystem();
    ThemeController theme = new ThemeController(system, ThemeMode.LIGHT, EditorSchemes.FOLLOW);
    final List<Palette> seen = new ArrayList<Palette>();
    theme.addListener(
        new PaletteListener() {
          @Override
          public void paletteChanged(Palette palette) {
            seen.add(palette);
          }
        });
    ThemeColors colors = ThemeColors.parse("light.text=#000001\ndark.text=#000002", "mock");
    theme.setColors(colors);
    assertEquals(Rgb.of(1), theme.palette().text());
    theme.setColors(ThemeColors.parse("light.text=#000001", "mock"));
    assertEquals(1, seen.size());
    theme.setColors(colors);
    theme.setMode(ThemeMode.DARK);
    assertEquals(Rgb.of(2), theme.palette().text());
    assertEquals(2, seen.size());
    theme.setMode(ThemeMode.SYSTEM);
    assertEquals(Rgb.of(1), theme.palette().text());
    system.flip(true);
    assertEquals(Rgb.of(2), theme.palette().text());
    theme.setColors(ThemeColors.NONE);
    assertSame(Palette.DARK, theme.palette());
  }

  @Test
  void editorColorsPickThePaletteSideAndTheThemePicksNativeControls() {
    for (ThemeMode mode : ThemeMode.values()) {
      for (boolean osDark : new boolean[] {false, true}) {
        for (String side : EditorSchemes.names()) {
          FakeSystem system = new FakeSystem();
          system.dark = osDark;
          ThemeController theme = new ThemeController(system, mode, side);
          boolean chromeDark = mode == ThemeMode.DARK || (mode == ThemeMode.SYSTEM && osDark);
          boolean paletteDark =
              side.equals(EditorSchemes.FOLLOW) ? chromeDark : side.equals(EditorSchemes.DARK);
          String at = mode + " " + osDark + " " + side;
          assertSame(paletteDark ? Palette.DARK : Palette.LIGHT, theme.palette(), at);
          theme.setMode(mode);
          assertEquals(List.of(chromeDark), system.preferred, at);
          assertSame(paletteDark ? Palette.DARK : Palette.LIGHT, theme.palette(), at);
        }
      }
    }
  }

  @Test
  void aSystemChangeWithFixedEditorColorsMovesOnlyNativeControls() {
    FakeSystem system = new FakeSystem();
    ThemeController theme = new ThemeController(system, ThemeMode.SYSTEM, EditorSchemes.DARK);
    final List<Palette> seen = new ArrayList<Palette>();
    theme.addListener(
        new PaletteListener() {
          @Override
          public void paletteChanged(Palette palette) {
            seen.add(palette);
          }
        });
    system.flip(true);
    system.flip(false);
    assertEquals(List.of(true, false), system.preferred);
    assertEquals(List.of(), seen);
    assertSame(Palette.DARK, theme.palette());
    theme.setEditorColors(EditorSchemes.FOLLOW);
    assertEquals(List.of(Palette.LIGHT), seen);
    system.flip(true);
    assertEquals(List.of(Palette.LIGHT, Palette.DARK), seen);
  }

  @Test
  void setEditorColorsLeavesNativeControlsAlone() {
    FakeSystem system = new FakeSystem();
    ThemeController theme = new ThemeController(system, ThemeMode.LIGHT, EditorSchemes.FOLLOW);
    theme.setEditorColors(EditorSchemes.DARK);
    assertSame(Palette.DARK, theme.palette());
    assertEquals(EditorSchemes.DARK, theme.editorColors());
    theme.setEditorColors(EditorSchemes.LIGHT);
    assertSame(Palette.LIGHT, theme.palette());
    assertEquals(List.of(), system.preferred);
  }

  @Test
  void aThemeFileAppliesItsSideOfTheEditorColors() {
    FakeSystem system = new FakeSystem();
    ThemeController theme = new ThemeController(system, ThemeMode.LIGHT, EditorSchemes.DARK);
    theme.setColors(ThemeColors.parse("light.text=#000001\ndark.text=#000002", "mock"));
    assertEquals(Rgb.of(2), theme.palette().text());
    assertTrue(theme.palette().dark());
    theme.setMode(ThemeMode.DARK);
    theme.setEditorColors(EditorSchemes.LIGHT);
    assertEquals(Rgb.of(1), theme.palette().text());
    assertEquals(List.of(true), system.preferred);
  }

  @Test
  void editorSchemesListFollowFirstAndResolveAnUnknownNameAsFollow() {
    assertEquals(List.of("follow", "light", "dark"), EditorSchemes.names());
    assertFalse(EditorSchemes.known("navy"));
    assertSame(Palette.DARK, EditorSchemes.palette("navy", ThemeMode.DARK, false));
    assertSame(Palette.LIGHT, EditorSchemes.palette("navy", ThemeMode.SYSTEM, false));
  }

  @Test
  void themeRoundTripsInPortableMode() throws Exception {
    Files.writeString(temp.resolve(SettingsLocation.MARKER), "", StandardCharsets.ISO_8859_1);
    SettingsLocation location =
        SettingsLocation.resolve(
            null,
            temp.toString(),
            "Linux",
            Map.<String, String>of(),
            temp.resolve("home").toString());
    assertEquals(SettingsMode.PORTABLE, location.mode());
    ConfigStore store = new ConfigStore(location);
    Preferences loaded = store.load();
    assertEquals(ThemeMode.LIGHT, loaded.theme());
    store.save(loaded, loaded.withTheme(ThemeMode.SYSTEM));
    assertEquals(ThemeMode.SYSTEM, new ConfigStore(location).load().theme());
    List<String> lines =
        Files.readAllLines(temp.resolve(SettingsLocation.MARKER), StandardCharsets.ISO_8859_1);
    assertTrue(lines.contains("ui.theme=system"));
    assertEquals(List.of(SettingsLocation.MARKER), names(temp));
  }

  private static List<String> names(Path dir) throws java.io.IOException {
    List<String> names = new ArrayList<String>();
    try (java.nio.file.DirectoryStream<Path> entries = Files.newDirectoryStream(dir)) {
      for (Path entry : entries) {
        names.add(entry.getFileName().toString());
      }
    }
    return names;
  }
}
