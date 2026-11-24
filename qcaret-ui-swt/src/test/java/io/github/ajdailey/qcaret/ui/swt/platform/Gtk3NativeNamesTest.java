package io.github.ajdailey.qcaret.ui.swt.platform;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assumptions.assumeTrue;

import org.eclipse.swt.SWT;
import org.eclipse.swt.graphics.Image;
import org.eclipse.swt.layout.FillLayout;
import org.eclipse.swt.widgets.Display;
import org.eclipse.swt.widgets.Shell;
import org.eclipse.swt.widgets.ToolBar;
import org.eclipse.swt.widgets.ToolItem;
import org.eclipse.swt.widgets.Tree;
import org.eclipse.swt.widgets.TreeItem;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledOnOs;
import org.junit.jupiter.api.condition.OS;

/**
 * {@link Gtk3NativeNames}: the SWT internals it reflects on still exist in this SWT, and a name it
 * sets is the name AT-SPI reads. A failing resolution test after an SWT upgrade names the class to
 * update.
 */
@Tag("swt")
class Gtk3NativeNamesTest {
  @Test
  @EnabledOnOs(OS.LINUX)
  void membersResolve() {
    assertTrue(new Gtk3NativeNames().supported());
  }

  @Test
  void onlyGtk3NamesThroughAtk() {
    if (RunningPlatform.GTK && !RunningPlatform.GTK4) {
      assertInstanceOf(Gtk3NativeNames.class, NativeNames.PLATFORM);
    } else {
      assertInstanceOf(NoNativeNames.class, NativeNames.PLATFORM);
    }
  }

  @Test
  void anIconItemAndATreeReadTheirNames() throws Exception {
    assumeTrue(RunningPlatform.GTK && !RunningPlatform.GTK4, "ATK names: GTK 3 only");
    Display display = new Display();
    Image image = new Image(display, 16, 16);
    try {
      Shell shell = new Shell(display);
      shell.setLayout(new FillLayout(SWT.VERTICAL));
      ToolBar bar = new ToolBar(shell, SWT.FLAT);
      ToolItem item = new ToolItem(bar, SWT.PUSH);
      item.setImage(image);
      item.setToolTipText("Mock (Ctrl+M)");
      Tree tree = new Tree(shell, SWT.SINGLE);
      new TreeItem(tree, SWT.NONE).setText("Mock Heading");
      shell.open();
      assertFalse("Mock Tool".equals(AtkNames.of(item)));
      assertFalse("Mock Outline \u00e9".equals(AtkNames.of(tree)));
      Gtk3NativeNames names = new Gtk3NativeNames();
      names.name(item, "Mock Tool");
      names.name(tree, "Mock Outline \u00e9");
      assertTrue(names.supported());
      assertEquals("Mock Tool", AtkNames.of(item));
      assertEquals("Mock Outline \u00e9", AtkNames.of(tree));
    } finally {
      image.dispose();
      display.dispose();
    }
  }
}
