package io.github.ajdailey.qcaret.core.markdown;

import java.util.Arrays;

/** A growable int array. */
final class Ints {
  private int[] data = new int[64];
  private int size;

  void add(int value) {
    if (size == data.length) {
      data = Arrays.copyOf(data, size * 2);
    }
    data[size++] = value;
  }

  int get(int index) {
    return data[index];
  }

  int size() {
    return size;
  }

  int[] toArray() {
    return Arrays.copyOf(data, size);
  }
}
