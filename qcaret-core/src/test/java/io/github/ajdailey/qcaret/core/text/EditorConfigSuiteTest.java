package io.github.ajdailey.qcaret.core.text;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;

import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.TreeMap;
import org.junit.jupiter.api.DynamicTest;
import org.junit.jupiter.api.TestFactory;
import org.junit.jupiter.api.function.Executable;

/**
 * The glob, parser and property cases of editorconfig-core-test, run on {@link EditorConfigFile},
 * {@link EditorConfigGlob} and {@link EditorConfig#defaults}. The fixtures and {@code cases.tsv}
 * are under {@code editorconfig/} in the test resources, with the suite's license.
 */
class EditorConfigSuiteTest {
  private static final String DIR = "editorconfig/";

  @TestFactory
  List<DynamicTest> coreTestCases() throws IOException {
    List<DynamicTest> tests = new ArrayList<DynamicTest>();
    for (String line : read("cases.tsv").split("\n")) {
      if (line.isEmpty() || line.startsWith("#")) {
        continue;
      }
      final String[] fields = line.split("\t", -1);
      final Map<String, String> expected = new TreeMap<String, String>();
      for (int i = 3; i < fields.length; i++) {
        int equals = fields[i].indexOf('=');
        expected.put(fields[i].substring(0, equals), fields[i].substring(equals + 1));
      }
      tests.add(
          DynamicTest.dynamicTest(
              fields[0],
              new Executable() {
                @Override
                public void execute() throws Exception {
                  assertEquals(expected, properties(read(fields[1]), fields[2]), fields[0]);
                }
              }));
    }
    assertEquals(173, tests.size());
    return tests;
  }

  /** Resolves one file's properties against one {@code .editorconfig} text. */
  static Map<String, String> properties(String config, String path) throws Exception {
    EditorConfigFile file = EditorConfigFile.parse(config);
    Map<String, String> found = new LinkedHashMap<String, String>();
    for (EditorConfigFile.Section section : file.sections()) {
      if (section.glob().matches("/" + path)) {
        found.putAll(section.properties());
      }
    }
    EditorConfig.defaults(found);
    return new TreeMap<String, String>(found);
  }

  private static String read(String name) throws IOException {
    try (InputStream in = EditorConfigSuiteTest.class.getResourceAsStream(DIR + name)) {
      assertNotNull(in, name);
      return new String(in.readAllBytes(), StandardCharsets.UTF_8);
    }
  }
}
