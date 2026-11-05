package io.github.ajdailey.qcaret.core.syntax;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import io.github.ajdailey.qcaret.core.concurrent.CancellationToken;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;

/** Detection order and user grammars over the built-in ones. */
class GrammarSetTest {
  private static String detect(GrammarSet set, String name, String firstLine) {
    return set.detect(name, firstLine, new CancellationToken());
  }

  @Test
  void detectsByFileNameThenExtensionThenFirstLine() {
    GrammarSet set = Tokens.BUILT_IN;
    assertEquals("java", detect(set, "Mock.java", ""));
    assertEquals("html", detect(set, "INDEX.HTM", ""));
    assertEquals("xml", detect(set, "pom.xml", "<project>"));
    assertEquals("shell", detect(set, ".bashrc", ""));
    assertEquals("ini", detect(set, ".editorconfig", ""));
    assertEquals("shell", detect(set, "mock-run", "#!/usr/bin/env bash"));
    assertEquals("python", detect(set, "tool", "#!/usr/bin/python3"));
    assertEquals("javascript", detect(set, "serve", "#!/usr/bin/env node"));
    assertEquals("xml", detect(set, "data", "<?xml version=\"1.0\"?>"));
    assertEquals("html", detect(set, null, "<!DOCTYPE html>"));
    assertEquals("markdown", detect(set, "notes.md", "#!/bin/sh"));
    assertEquals(GrammarSet.PLAIN, detect(set, "README", "hello"));
    assertEquals(GrammarSet.PLAIN, detect(set, "trailing.", ""));
  }

  @Test
  void detectsTheAddedLanguages() {
    GrammarSet set = Tokens.BUILT_IN;
    String[][] cases = {
      {"main.go", "", "go"},
      {"lib.rs", "", "rust"},
      {"run", "#!/usr/bin/env rust-script", "rust"},
      {"Program.cs", "", "csharp"},
      {"build.csx", "", "csharp"},
      {"App.kt", "", "kotlin"},
      {"build.gradle.kts", "", "kotlin"},
      {"tool", "#!/usr/bin/env kotlin", "kotlin"},
      {"app.rb", "", "ruby"},
      {"Rakefile", "", "ruby"},
      {"Gemfile", "", "ruby"},
      {"tool", "#!/usr/bin/env ruby", "ruby"},
      {"index.php", "", "php"},
      {"page", "<?php echo 1;", "php"},
      {"tool", "#!/usr/bin/env php", "php"},
      {"mock.pl", "", "perl"},
      {"Mock.pm", "", "perl"},
      {"basic.t", "", "perl"},
      {"tool", "#!/usr/bin/perl -w", "perl"},
      {"init.lua", "", "lua"},
      {"tool", "#!/usr/bin/env lua5.4", "lua"},
      {"Build.groovy", "", "groovy"},
      {"build.gradle", "", "groovy"},
      {"Jenkinsfile", "", "groovy"},
      {"tool", "#!/usr/bin/env groovy", "groovy"},
      {"Main.scala", "", "scala"},
      {"tool", "#!/usr/bin/env -S scala-cli shebang", "scala"},
      {"App.swift", "", "swift"},
      {"main.dart", "", "dart"},
      {"plot.r", "", "r"},
      {"plot.R", "", "r"},
      {".Rprofile", "", "r"},
      {"tool", "#!/usr/bin/env Rscript", "r"},
      {"Main.hs", "", "haskell"},
      {"tool", "#!/usr/bin/env runghc", "haskell"},
      {"Mock.m", "", "objectivec"},
      {"Mock.mm", "", "objectivec"},
      {"Mock.h", "", "c"},
      {"Module1.vb", "", "vbnet"}
    };
    for (String[] c : cases) {
      assertEquals(c[2], detect(set, c[0], c[1]), c[0] + " " + c[1]);
    }
  }

  @Test
  void anExtensionBelongsToOneBuiltInGrammar() {
    Map<String, String> owners = new LinkedHashMap<String, String>();
    for (String id : GrammarSet.BUILT_IN) {
      for (String extension : Tokens.BUILT_IN.get(id).extensions()) {
        String other = owners.put(extension, id);
        assertTrue(other == null, extension + " in " + other + " and " + id);
      }
    }
  }

  @Test
  void aUserGrammarReplacesTheBuiltInWithItsIdAndIsDetectedFirst() throws Exception {
    Map<String, Grammar> users = new LinkedHashMap<String, Grammar>();
    users.put(
        "java.xml",
        Tokens.read(
            "<grammar id=\"java\" name=\"My Java\"><detect><extension>java</extension>"
                + "</detect><context id=\"main\"/></grammar>"));
    users.put(
        "mock.xml",
        Tokens.read(
            "<grammar id=\"mock\" name=\"Mock\"><detect><extension>md</extension>"
                + "</detect><context id=\"main\"/></grammar>"));
    List<String> errors = new ArrayList<String>();
    GrammarSet set = GrammarSet.of(GrammarSet.readBuiltIns(new GrammarReader()), users, errors);
    assertEquals(List.of(), errors);
    assertEquals("My Java", set.get("java").name());
    assertEquals("mock", detect(set, "a.md", ""));
    assertEquals(GrammarSet.BUILT_IN.size() + 1, set.byName().size());
  }

  @Test
  void aUserGrammarThatDoesNotCompileIsLeftOutAndTheBuiltInStays() throws Exception {
    Map<String, Grammar> users = new LinkedHashMap<String, Grammar>();
    users.put(
        "java.xml",
        Tokens.read(
            "<grammar id=\"java\" name=\"Broken\"><context id=\"main\">"
                + "<match token=\"string\" regex=\"(a)\\1\"/></context></grammar>"));
    List<String> errors = new ArrayList<String>();
    GrammarSet set = GrammarSet.of(GrammarSet.readBuiltIns(new GrammarReader()), users, errors);
    assertEquals(1, errors.size());
    assertTrue(errors.get(0).startsWith("java.xml: "), errors.get(0));
    assertEquals("Java", set.get("java").name());
    assertTrue(set.syntax("java") != null);
  }

  @Test
  void aRunawayFirstLineRegexDoesNotMatch() throws Exception {
    Map<String, Grammar> users = new LinkedHashMap<String, Grammar>();
    users.put(
        "mock.xml",
        Tokens.read(
            "<grammar id=\"mock\" name=\"Mock\"><detect><firstLine>(.*a){12}$</firstLine>"
                + "</detect><context id=\"main\"/></grammar>"));
    GrammarSet set =
        GrammarSet.of(GrammarSet.readBuiltIns(new GrammarReader()), users, new ArrayList<String>());
    long started = System.nanoTime();
    assertEquals(GrammarSet.PLAIN, detect(set, null, "a".repeat(32) + "!"));
    assertTrue(System.nanoTime() - started < 5_000_000_000L);
  }
}
