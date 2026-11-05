package io.github.ajdailey.qcaret.app.command;

import java.util.ArrayList;
import java.util.Collections;
import java.util.EnumMap;
import java.util.List;
import java.util.Map;
import java.util.TreeMap;

/**
 * Keys the user set in place of a command's defaults. {@code qcaret.conf} holds one key per
 * command, {@code shortcut.<COMMAND>}, with {@link KeyStroke#stored()} strokes separated by spaces;
 * an empty value leaves the command without a key. Immutable.
 */
public final class ShortcutOverrides {
  /** No overrides. */
  public static final ShortcutOverrides NONE =
      new ShortcutOverrides(new EnumMap<CommandId, List<KeyStroke>>(CommandId.class));

  /** Most keys kept for one command. */
  public static final int MAX_PER_COMMAND = 8;

  /** The key prefix in {@code qcaret.conf}. */
  public static final String PREFIX = "shortcut.";

  private final Map<CommandId, List<KeyStroke>> overrides;

  private ShortcutOverrides(Map<CommandId, List<KeyStroke>> overrides) {
    this.overrides = overrides;
  }

  /**
   * Returns a command's keys.
   *
   * @param command the command
   * @return the strokes that replace its defaults, or null when it keeps them
   */
  public List<KeyStroke> strokes(CommandId command) {
    return overrides.get(command);
  }

  /**
   * Returns a copy with a command's keys replaced.
   *
   * @param command the command
   * @param strokes its keys; at most {@link #MAX_PER_COMMAND} are kept
   * @return the copy
   */
  public ShortcutOverrides with(CommandId command, List<KeyStroke> strokes) {
    Map<CommandId, List<KeyStroke>> copy = new EnumMap<CommandId, List<KeyStroke>>(CommandId.class);
    copy.putAll(overrides);
    copy.put(
        command,
        List.copyOf(
            strokes.size() > MAX_PER_COMMAND ? strokes.subList(0, MAX_PER_COMMAND) : strokes));
    return new ShortcutOverrides(copy);
  }

  /**
   * Returns a copy where a command keeps its default keys.
   *
   * @param command the command
   * @return the copy
   */
  public ShortcutOverrides without(CommandId command) {
    Map<CommandId, List<KeyStroke>> copy = new EnumMap<CommandId, List<KeyStroke>>(CommandId.class);
    copy.putAll(overrides);
    copy.remove(command);
    return new ShortcutOverrides(copy);
  }

  /**
   * Reads overrides. A key naming no command stays out of the result and of {@code refused}: a
   * later version may know it. A value holding a stroke that does not parse or that {@link
   * KeyStroke#types()} is refused whole.
   *
   * @param keys {@code shortcut.*} keys and their values
   * @param refused receives the keys refused
   * @return the overrides
   */
  public static ShortcutOverrides parse(Map<String, String> keys, List<String> refused) {
    Map<CommandId, List<KeyStroke>> read = new EnumMap<CommandId, List<KeyStroke>>(CommandId.class);
    for (CommandId command : CommandId.values()) {
      String key = PREFIX + command.name();
      String value = keys.get(key);
      if (value != null) {
        List<KeyStroke> strokes = strokes(value);
        if (strokes == null) {
          refused.add(key);
        } else {
          read.put(command, strokes);
        }
      }
    }
    return read.isEmpty() ? NONE : new ShortcutOverrides(read);
  }

  /** Returns the strokes of a value, or null when one is refused or there are too many. */
  private static List<KeyStroke> strokes(String value) {
    List<KeyStroke> strokes = new ArrayList<KeyStroke>();
    for (String text : value.strip().split("\\s+")) {
      if (text.isEmpty()) {
        continue;
      }
      KeyStroke stroke = KeyStroke.parse(text);
      if (stroke == null || stroke.types() || strokes.size() == MAX_PER_COMMAND) {
        return null;
      }
      strokes.add(stroke);
    }
    return Collections.unmodifiableList(strokes);
  }

  /**
   * Returns the overrides as {@code qcaret.conf} keys.
   *
   * @return keys and values, sorted
   */
  public Map<String, String> keys() {
    Map<String, String> keys = new TreeMap<String, String>();
    for (Map.Entry<CommandId, List<KeyStroke>> entry : overrides.entrySet()) {
      StringBuilder value = new StringBuilder();
      for (KeyStroke stroke : entry.getValue()) {
        if (value.length() > 0) {
          value.append(' ');
        }
        value.append(stroke.stored());
      }
      keys.put(PREFIX + entry.getKey().name(), value.toString());
    }
    return keys;
  }

  @Override
  public boolean equals(Object other) {
    return other instanceof ShortcutOverrides o && overrides.equals(o.overrides);
  }

  @Override
  public int hashCode() {
    return overrides.hashCode();
  }
}
