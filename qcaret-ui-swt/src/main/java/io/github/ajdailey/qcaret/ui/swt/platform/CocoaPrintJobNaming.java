package io.github.ajdailey.qcaret.ui.swt.platform;

import java.lang.reflect.Field;
import java.lang.reflect.InaccessibleObjectException;
import java.lang.reflect.Method;
import org.eclipse.swt.widgets.Shell;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * The print job name on cocoa: Core Printing's job name in {@code NSPrintInfo.sharedPrintInfo},
 * which SWT's {@code PrintDialog.open} copies for its panel. The name goes into the {@code
 * printSettings} dictionary under {@link #KEY}, through SWT's {@code internal.cocoa} bindings, as
 * SWT binds no {@code PMPrintSettingsSetJobName}.
 */
final class CocoaPrintJobNaming {
  /**
   * Core Printing's job name key, {@code com.apple.print.JobInfo.PMJobName}, with the dots as
   * underscores as {@code NSPrintInfo.printSettings} spells its keys.
   */
  static final String KEY = "com_apple_print_JobInfo_PMJobName";

  private static final Logger LOG = LoggerFactory.getLogger(CocoaPrintJobNaming.class);
  private static final String COCOA = "org.eclipse.swt.internal.cocoa.";

  private Method sharedPrintInfo;
  private Method stringWith;
  private Method selector;
  private Method send;
  private Method sendTwo;
  private Field id;
  private boolean supported = true;

  /** Resolves the members; a failure leaves the naming unsupported. */
  CocoaPrintJobNaming() {
    try {
      ClassLoader loader = Shell.class.getClassLoader();
      Class<?> os = Class.forName(COCOA + "OS", false, loader);
      sharedPrintInfo =
          Class.forName(COCOA + "NSPrintInfo", false, loader).getMethod("sharedPrintInfo");
      stringWith =
          Class.forName(COCOA + "NSString", false, loader).getMethod("stringWith", String.class);
      selector = os.getMethod("sel_registerName", String.class);
      send = os.getMethod("objc_msgSend", long.class, long.class);
      sendTwo = os.getMethod("objc_msgSend", long.class, long.class, long.class, long.class);
      id = Class.forName(COCOA + "id", false, loader).getField("id");
    } catch (ReflectiveOperationException | SecurityException | InaccessibleObjectException e) {
      fail(e);
    }
  }

  /**
   * Reports whether {@link #name} takes effect; false after a reflective call failed.
   *
   * @return true when supported
   */
  boolean supported() {
    return supported;
  }

  /**
   * Names the job the next {@code PrintDialog} opens. A failure logs one WARN, names no later job
   * and leaves printing working.
   *
   * @param job the job name
   */
  void name(String job) {
    if (!supported) {
      return;
    }
    try {
      long info = id(sharedPrintInfo.invoke(null));
      long settings = (long) send.invoke(null, info, selector("printSettings"));
      if (settings == 0) {
        throw new IllegalStateException("The shared print info has no print settings");
      }
      sendTwo.invoke(null, settings, selector("setObject:forKey:"), string(job), string(KEY));
    } catch (ReflectiveOperationException
        | IllegalArgumentException
        | IllegalStateException
        | LinkageError e) {
      fail(e);
    }
  }

  private long selector(String name) throws ReflectiveOperationException {
    return (long) selector.invoke(null, name);
  }

  private long string(String text) throws ReflectiveOperationException {
    return id(stringWith.invoke(null, text));
  }

  private long id(Object wrapper) throws ReflectiveOperationException {
    if (wrapper == null) {
      throw new IllegalStateException("A Cocoa call returned nil");
    }
    return id.getLong(wrapper);
  }

  private void fail(Throwable cause) {
    if (supported) {
      supported = false;
      LOG.warn(
          "The print job is not named: a reflective call into SWT internals failed; Save as PDF"
              + " proposes Untitled",
          cause);
    }
  }
}
