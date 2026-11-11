package io.github.ajdailey.qcaret.ui.swt;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotSame;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assumptions.assumeTrue;

import io.github.ajdailey.qcaret.app.theme.Palette;
import org.eclipse.swt.SWT;
import org.eclipse.swt.graphics.Image;
import org.eclipse.swt.layout.RowLayout;
import org.eclipse.swt.widgets.Display;
import org.eclipse.swt.widgets.Shell;
import org.eclipse.swt.widgets.Text;
import org.eclipse.swt.widgets.ToolBar;
import org.eclipse.swt.widgets.ToolItem;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;

/** The one-item icon tool bar, its tab stop and the palette ink. */
@Tag("swt")
class ToolIconsTest {
  @Test
  void anItemSitsAloneOnAFlatToolBar() {
    Display display = new Display();
    try {
      Shell shell = new Shell(display);
      ToolIcons icons = new ToolIcons(shell);
      ToolItem item = icons.item(shell, ToolIcons.HOME, "Home Folder");
      ToolBar bar = item.getParent();
      assertSame(shell, bar.getParent());
      assertEquals(1, bar.getItemCount());
      assertTrue((bar.getStyle() & SWT.FLAT) != 0);
      assertSame(icons.get(ToolIcons.HOME), item.getImage());
      assertEquals("Home Folder", item.getToolTipText());
      assertEquals("", item.getText());
    } finally {
      display.dispose();
    }
  }

  /** Each one-item tool bar is a tab stop, so Tab reaches every icon from a field before them. */
  @Test
  void tabReachesEachItem() {
    Display display = new Display();
    try {
      Shell shell = new Shell(display);
      shell.setLayout(new RowLayout());
      Text field = new Text(shell, SWT.SINGLE | SWT.BORDER);
      ToolIcons icons = new ToolIcons(shell);
      ToolItem home = icons.item(shell, ToolIcons.HOME, "Home Folder");
      ToolItem parent = icons.item(shell, ToolIcons.PARENT, "Parent Folder");
      shell.pack();
      shell.open();
      shell.forceActive();
      field.setFocus();
      assumeTrue(field.isFocusControl(), "the shell is not the active window");
      assertTrue(field.traverse(SWT.TRAVERSE_TAB_NEXT));
      assertSame(home.getParent(), display.getFocusControl());
      assertTrue(home.getParent().traverse(SWT.TRAVERSE_TAB_NEXT));
      assertSame(parent.getParent(), display.getFocusControl());
    } finally {
      display.dispose();
    }
  }

  @Test
  void aPaletteSetTakesThePalettesInk() {
    Display display = new Display();
    try {
      Shell shell = new Shell(display);
      ToolIcons icons = new ToolIcons(shell, Palette.LIGHT);
      ToolItem item = new ToolItem(new ToolBar(shell, SWT.FLAT), SWT.PUSH);
      icons.apply(item, ToolIcons.CLOSE);
      assertFalse(icons.darkBackground());
      Image light = item.getImage();

      icons.follow(Palette.DARK);
      assertTrue(icons.darkBackground());
      assertNotSame(light, item.getImage());
      assertTrue(light.isDisposed());

      icons.reink();
      assertTrue(icons.darkBackground(), "the native background does not reach a palette set");
    } finally {
      display.dispose();
    }
  }
}
