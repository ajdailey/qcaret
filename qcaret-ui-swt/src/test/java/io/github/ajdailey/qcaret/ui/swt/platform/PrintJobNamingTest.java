package io.github.ajdailey.qcaret.ui.swt.platform;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.lang.reflect.Method;
import org.eclipse.swt.widgets.Display;
import org.eclipse.swt.widgets.Shell;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.DisabledOnOs;
import org.junit.jupiter.api.condition.EnabledOnOs;
import org.junit.jupiter.api.condition.OS;

/**
 * {@link CocoaPrintJobNaming}: only cocoa's {@link PrintSupport} names the job, and on macOS the
 * SWT internals it reflects on resolve. A failing macOS test after an SWT upgrade names the members
 * to update.
 */
@Tag("swt")
class PrintJobNamingTest {
  private static final String COCOA = "org.eclipse.swt.internal.cocoa.";

  @Test
  @DisabledOnOs(OS.MAC)
  void onlyCocoaNamesTheJob() {
    assertFalse(PrintSupport.PLATFORM instanceof CocoaPrintSupport);
  }

  /**
   * The name lands in the shared print info and survives what {@code PrintDialog.open} does before
   * the panel: a copy, then {@code updateFromPMPrintSettings}.
   */
  @Test
  @EnabledOnOs(OS.MAC)
  void cocoaMembersResolveAndTheCopiedPrintInfoCarriesTheName() throws Exception {
    Display display = new Display();
    try {
      assertTrue(PrintSupport.PLATFORM instanceof CocoaPrintSupport);
      CocoaPrintJobNaming naming = new CocoaPrintJobNaming();
      assertTrue(naming.supported());
      naming.name("d1d");
      assertTrue(naming.supported());
      assertEquals("d1d", copiedJobName());
    } finally {
      display.dispose();
    }
  }

  private static String copiedJobName() throws Exception {
    ClassLoader loader = Shell.class.getClassLoader();
    Class<?> os = Class.forName(COCOA + "OS", false, loader);
    Method selector = os.getMethod("sel_registerName", String.class);
    Method send = os.getMethod("objc_msgSend", long.class, long.class);
    Method sendOne = os.getMethod("objc_msgSend", long.class, long.class, long.class);
    Class<?> nsString = Class.forName(COCOA + "NSString", false, loader);
    Class<?> nsId = Class.forName(COCOA + "id", false, loader);
    Object shared =
        Class.forName(COCOA + "NSPrintInfo", false, loader)
            .getMethod("sharedPrintInfo")
            .invoke(null);
    long info = nsId.getField("id").getLong(shared);
    long copy = (long) send.invoke(null, info, selector.invoke(null, "copy"));
    try {
      send.invoke(null, copy, selector.invoke(null, "updateFromPMPrintSettings"));
      long settings = (long) send.invoke(null, copy, selector.invoke(null, "printSettings"));
      Object key =
          nsString.getMethod("stringWith", String.class).invoke(null, CocoaPrintJobNaming.KEY);
      long value =
          (long)
              sendOne.invoke(
                  null,
                  settings,
                  selector.invoke(null, "objectForKey:"),
                  nsId.getField("id").getLong(key));
      if (value == 0) {
        return null;
      }
      Object string = nsString.getConstructor(long.class).newInstance(value);
      return (String) nsString.getMethod("getString").invoke(string);
    } finally {
      send.invoke(null, copy, selector.invoke(null, "release"));
    }
  }
}
