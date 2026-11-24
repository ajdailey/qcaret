package io.github.ajdailey.qcaret.ui.swt.platform;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertSame;

import org.eclipse.swt.SWT;
import org.eclipse.swt.accessibility.ACC;
import org.eclipse.swt.accessibility.AccessibleControlEvent;
import org.eclipse.swt.accessibility.AccessibleEvent;
import org.eclipse.swt.widgets.Canvas;
import org.eclipse.swt.widgets.Display;
import org.eclipse.swt.widgets.Shell;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;

/**
 * The name, the description listener and the group role a screen reader reads for a control. Which
 * listeners the platform adds ({@link AccessibleLabels}) shows only to a screen reader: VoiceOver
 * on macOS (G2).
 */
@Tag("swt")
class AccessibleNameTest {
  private Display display;
  private Shell shell;

  @BeforeEach
  void open() {
    display = new Display();
    shell = new Shell(display);
  }

  @AfterEach
  void close() {
    display.dispose();
  }

  private static String name(Canvas control, AccessibleName name) {
    AccessibleEvent event = new AccessibleEvent(control.getAccessible());
    event.childID = ACC.CHILDID_SELF;
    name.getName(event);
    return event.result;
  }

  private static String description(Canvas control, AccessibleName name) {
    AccessibleEvent event = new AccessibleEvent(control.getAccessible());
    event.childID = ACC.CHILDID_SELF;
    name.description().getDescription(event);
    return event.result;
  }

  @Test
  void theNameAndItsDescriptionFollowEachChange() {
    Canvas canvas = new Canvas(shell, SWT.NONE);
    AccessibleName name = AccessibleName.attach(canvas, "mock view");
    assertEquals("mock view", name(canvas, name));
    assertEquals("mock view", description(canvas, name));
    name.set("mock other");
    assertEquals("mock other", name(canvas, name));
    assertEquals("mock other", description(canvas, name));
    assertSame(name, AccessibleName.of(canvas));
    Canvas kept = new Canvas(shell, SWT.NONE);
    assertSame(AccessibleName.attachKeepingRole(kept, "mock kept"), AccessibleName.of(kept));
    assertNull(AccessibleName.of(new Canvas(shell, SWT.NONE)));
  }

  @Test
  void cocoaReportsTheGroupRole() {
    Canvas canvas = new Canvas(shell, SWT.NONE);
    AccessibleControlEvent role = new AccessibleControlEvent(canvas.getAccessible());
    role.childID = ACC.CHILDID_SELF;
    role.detail = -1;
    CocoaAccessibleLabels.GROUP_ROLE.getRole(role);
    assertEquals(ACC.ROLE_GROUP, role.detail);
    PlatformTraits.of("cocoa", false)
        .accessibleLabels()
        .describe(canvas, AccessibleName.attach(new Canvas(shell, SWT.NONE), "mock").description());
  }
}
