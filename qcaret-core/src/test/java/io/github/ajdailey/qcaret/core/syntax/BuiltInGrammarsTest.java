package io.github.ajdailey.qcaret.core.syntax;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.List;
import org.junit.jupiter.api.Test;

/** One sample per built-in grammar, with the tokens it must produce. */
class BuiltInGrammarsTest {
  /** Asserts that every expected {@code TYPE:text} token occurs, in order. */
  private static void assertTokens(String grammar, String text, String... expected) {
    List<String> actual = Tokens.of(grammar, text);
    int at = 0;
    for (String e : expected) {
      int found = actual.subList(at, actual.size()).indexOf(e);
      assertTrue(found >= 0, grammar + ": no " + e + " after token " + at + " in " + actual);
      at += found + 1;
    }
  }

  private static void assertAbsent(String grammar, String text, String token) {
    List<String> actual = Tokens.of(grammar, text);
    assertFalse(actual.contains(token), grammar + ": unexpected " + token + " in " + actual);
  }

  @Test
  void everyBuiltInGrammarLoadsAndCompiles() {
    assertEquals(GrammarSet.BUILT_IN.size(), Tokens.BUILT_IN.byName().size());
    for (String id : GrammarSet.BUILT_IN) {
      assertTrue(Tokens.BUILT_IN.syntax(id) != null, id);
    }
    assertEquals("plain", Tokens.BUILT_IN.byName().get(0).id());
  }

  @Test
  void plainTextHasNoTokens() {
    assertEquals(List.of(), Tokens.of("plain", "class Mock { // \"x\" 42 }"));
  }

  @Test
  void java() {
    assertTokens(
        "java",
        "@Override\npublic final class Mock<T> extends Base { /* a\n b */ int n = 0x1F + 2.5e3f;"
            + " String s = \"x\\n\"; char c = '\\''; boolean f = true; // end\n"
            + " String block = \"\"\"\n  text \\\"\n  \"\"\"; static final int MAX_SIZE = 1; run(); }",
        "PREPROCESSOR:@Override",
        "KEYWORD:public",
        "KEYWORD:class",
        "TYPE:Mock",
        "OPERATOR:<",
        "KEYWORD:extends",
        "TYPE:Base",
        "DELIMITER:{",
        "COMMENT:/* a",
        "COMMENT: b */",
        "TYPE:int",
        "NUMBER:0x1F",
        "NUMBER:2.5e3f",
        "STRING:\"x",
        "ESCAPE:\\n",
        "STRING:\"",
        "STRING:'",
        "ESCAPE:\\'",
        "CONSTANT:true",
        "COMMENT:// end",
        "STRING:\"\"\"",
        "STRING:  text ",
        "ESCAPE:\\\"",
        "STRING:  \"\"\"",
        "CONSTANT:MAX_SIZE",
        "FUNCTION:run");
  }

  @Test
  void c() {
    assertTokens(
        "c",
        "#include <stdio.h>\n#define LIMIT 10\nstatic int main(void) {\n"
            + "  unsigned long n = 42UL; /* x */\n  printf(\"%d\\n\", n); return NULL == 0;\n}",
        "PREPROCESSOR:#include",
        "STRING:<stdio.h>",
        "PREPROCESSOR:#define",
        "CONSTANT:LIMIT",
        "KEYWORD:static",
        "TYPE:int",
        "FUNCTION:main",
        "TYPE:void",
        "TYPE:unsigned",
        "TYPE:long",
        "NUMBER:42UL",
        "COMMENT:/* x */",
        "FUNCTION:printf",
        "STRING:\"%d",
        "ESCAPE:\\n",
        "KEYWORD:return",
        "CONSTANT:NULL");
  }

  @Test
  void cpp() {
    assertTokens(
        "cpp",
        "namespace mock {\ntemplate <typename T> class Box final { public: auto get() const"
            + " noexcept -> T; };\nauto s = R\"(raw \\n\n)\"; std::vector<int> v{1'000};\n}",
        "KEYWORD:namespace",
        "KEYWORD:template",
        "KEYWORD:typename",
        "KEYWORD:class",
        "TYPE:Box",
        "KEYWORD:final",
        "KEYWORD:public",
        "TYPE:auto",
        "FUNCTION:get",
        "KEYWORD:const",
        "KEYWORD:noexcept",
        "STRING:R\"(raw \\n",
        "STRING:)\"",
        "TYPE:std",
        "NUMBER:1'000");
  }

  @Test
  void javascript() {
    assertTokens(
        "javascript",
        "export async function load(id) {\n  const re = /a[/]b+/gi;\n"
            + "  const msg = `Item ${id + 1} of ${count}`;\n  return null ?? undefined; // done\n}",
        "KEYWORD:export",
        "KEYWORD:async",
        "KEYWORD:function",
        "FUNCTION:load",
        "KEYWORD:const",
        "STRING: /a[/]b+/gi",
        "STRING:`Item ",
        "DELIMITER:${",
        "NUMBER:1",
        "DELIMITER:}",
        "STRING: of ",
        "STRING:`",
        "KEYWORD:return",
        "CONSTANT:null",
        "CONSTANT:undefined",
        "COMMENT:// done");
  }

  @Test
  void typescript() {
    assertTokens(
        "typescript",
        "interface Mock { readonly id: number; name?: string }\n"
            + "export type Pair<T> = [T, T];\n@Component\nclass Box implements Mock { }",
        "KEYWORD:interface",
        "TYPE:Mock",
        "KEYWORD:readonly",
        "TYPE:number",
        "TYPE:string",
        "KEYWORD:export",
        "KEYWORD:type",
        "TYPE:Pair",
        "PREPROCESSOR:@Component",
        "KEYWORD:class",
        "KEYWORD:implements");
  }

  @Test
  void python() {
    assertTokens(
        "python",
        "@dataclass\nclass Mock(Base):\n    def run(self, n=0x10):\n"
            + "        \"\"\"Doc\n        string\"\"\"\n        return f'{n}\\n' if n is not None else"
            + " len(b'x')  # note",
        "PREPROCESSOR:@dataclass",
        "KEYWORD:class",
        "TYPE:Mock",
        "KEYWORD:def",
        "FUNCTION:run",
        "KEYWORD2:self",
        "NUMBER:0x10",
        "STRING:\"\"\"Doc",
        "STRING:        string\"\"\"",
        "KEYWORD:return",
        "STRING:f'{n}",
        "ESCAPE:\\n",
        "KEYWORD:is",
        "KEYWORD:not",
        "CONSTANT:None",
        "KEYWORD2:len",
        "STRING:b'x'",
        "COMMENT:# note");
  }

  @Test
  void shell() {
    assertTokens(
        "shell",
        "#!/bin/sh\nmock_run() {\n  if [ \"$#\" -gt 0 ]; then echo \"${1:-x} $HOME\"; fi\n"
            + "  for f in *.txt; do cat 'a b' | wc -l; done # tail\n}",
        "COMMENT:#!/bin/sh",
        "FUNCTION:mock_run",
        "KEYWORD:if",
        "STRING:\"",
        "CONSTANT:$#",
        "KEYWORD:then",
        "KEYWORD2:echo",
        "CONSTANT:${1:-x}",
        "CONSTANT:$HOME",
        "KEYWORD:fi",
        "KEYWORD:for",
        "KEYWORD:do",
        "STRING:'a b'",
        "OPERATOR:|",
        "KEYWORD:done",
        "COMMENT:# tail");
  }

  @Test
  void powershell() {
    assertTokens(
        "powershell",
        "<# block\n#>\nfunction Get-Mock {\n  param([string]$Name)\n"
            + "  if ($Name -eq 'x') { Write-Output \"Hi $Name`n\" } # end\n}",
        "COMMENT:<# block",
        "COMMENT:#>",
        "KEYWORD:function",
        "FUNCTION:Get-Mock",
        "KEYWORD:param",
        "TYPE:[string]",
        "CONSTANT:$Name",
        "KEYWORD:if",
        "OPERATOR:-eq",
        "STRING:'x'",
        "FUNCTION:Write-Output",
        "STRING:\"Hi ",
        "CONSTANT:$Name",
        "ESCAPE:`n",
        "COMMENT:# end");
  }

  @Test
  void batch() {
    assertTokens(
        "batch",
        "@echo off\nREM mock script\n:: note\nsetlocal EnableDelayedExpansion\n:loop\n"
            + "if exist \"%~1\" (\n  set COUNT=!COUNT!\n  goto loop\n)",
        "OPERATOR:@",
        "KEYWORD2:echo",
        "COMMENT:REM mock script",
        "COMMENT::: note",
        "KEYWORD:setlocal",
        "FUNCTION::loop",
        "KEYWORD:if",
        "KEYWORD:exist",
        "CONSTANT:%~1",
        "DELIMITER:(",
        "KEYWORD2:set",
        "CONSTANT:!COUNT!",
        "KEYWORD:goto");
  }

  @Test
  void sql() {
    assertTokens(
        "sql",
        "SELECT name, COUNT(*) AS n -- total\nFROM \"Mock Table\" WHERE id = :id AND note <> 'it''s'"
            + "\n/* x */ GROUP BY name HAVING n > 1.5;\nCREATE TABLE t (id INTEGER NOT NULL);",
        "KEYWORD:SELECT",
        "KEYWORD2:COUNT",
        "KEYWORD:AS",
        "COMMENT:-- total",
        "KEYWORD:FROM",
        "ATTRIBUTE:\"Mock Table\"",
        "KEYWORD:WHERE",
        "CONSTANT::id",
        "STRING:'it",
        "ESCAPE:''",
        "STRING:s'",
        "COMMENT:/* x */",
        "NUMBER:1.5",
        "KEYWORD:CREATE",
        "TYPE:INTEGER",
        "CONSTANT:NULL");
  }

  @Test
  void json() {
    assertTokens(
        "json",
        "{\n  \"name\": \"mock\\u0041\",\n  \"items\": [1, -2.5e3, true, null],\n  \"bad\": \"\\q\"\n}",
        "DELIMITER:{",
        "ATTRIBUTE:\"name\"",
        "DELIMITER::",
        "STRING:\"mock",
        "ESCAPE:\\u0041",
        "NUMBER:1",
        "NUMBER:-2.5e3",
        "CONSTANT:true",
        "CONSTANT:null",
        "INVALID:\\q");
  }

  @Test
  void yaml() {
    assertTokens(
        "yaml",
        "%YAML 1.2\n---\nname: mock # note\nlist:\n  - key: \"a\\tb\"\n    on: yes\n"
            + "  - &anchor 42\n  - *anchor\ntext: |\n  plain on value\n",
        "PREPROCESSOR:%YAML 1.2",
        "PREPROCESSOR:---",
        "ATTRIBUTE:name",
        "COMMENT:# note",
        "ATTRIBUTE:list",
        "DELIMITER:-",
        "ATTRIBUTE:key",
        "STRING:\"a",
        "ESCAPE:\\t",
        "ATTRIBUTE:on",
        "CONSTANT:yes",
        "CONSTANT:&anchor",
        "NUMBER:42",
        "CONSTANT:*anchor",
        "ATTRIBUTE:text",
        "OPERATOR:|");
    assertAbsent("yaml", "text: plain on value", "CONSTANT:on");
  }

  @Test
  void xml() {
    assertTokens(
        "xml",
        "<?xml version=\"1.0\"?>\n<!-- note -->\n<root id='a&amp;b'>\n  <item/><![CDATA[x<y]]>&lt;\n"
            + "</root>",
        "PREPROCESSOR:<?xml ",
        "ATTRIBUTE:version",
        "COMMENT:<!-- note -->",
        "TAG:<root",
        "ATTRIBUTE:id",
        "OPERATOR:=",
        "STRING:'a",
        "ESCAPE:&amp;",
        "TAG:>",
        "TAG:<item/>",
        "STRING:<![CDATA[x<y]]>",
        "ESCAPE:&lt;",
        "TAG:</root>");
  }

  @Test
  void htmlEmbedsJavaScriptAndCss() {
    assertTokens(
        "html",
        "<!DOCTYPE html>\n<style>\nbody { color: #fff; }\n</style>\n"
            + "<script type=\"module\">let s = \"</p>\";\nif (s) { run(); }\n</script>\n"
            + "<p class=x>Hi &amp; bye</p>",
        "PREPROCESSOR:<!DOCTYPE html>",
        "TAG:<style>",
        "TAG:body",
        "ATTRIBUTE:color",
        "CONSTANT:#fff",
        "TAG:</style>",
        "TAG:<script",
        "ATTRIBUTE:type",
        "STRING:\"module\"",
        "KEYWORD:let",
        "STRING:\"</p>\"",
        "KEYWORD:if",
        "FUNCTION:run",
        "TAG:</script>",
        "TAG:<p",
        "ATTRIBUTE:class",
        "ESCAPE:&amp;",
        "TAG:</p>");
  }

  @Test
  void anEmptyScriptElementEndsTheEmbed() {
    assertEquals(
        List.of(
            "TAG:<script",
            "ATTRIBUTE:src",
            "OPERATOR:=",
            "STRING:\"a.js\"",
            "TAG:></script><b>",
            "TAG:</b>"),
        Tokens.of("html", "<script src=\"a.js\"></script><b>x</b>"));
  }

  @Test
  void css() {
    assertTokens(
        "css",
        "@media screen {\n  #main > .item:hover::after, a[href] {\n    margin: -1.5em 10%;"
            + " color: rgb(0 0 0) !important;\n    background: url(mock.png); --gap: 4px;\n  }\n}",
        "PREPROCESSOR:@media",
        "TAG:screen",
        "DELIMITER:{",
        "CONSTANT:#main",
        "OPERATOR:>",
        "TYPE:.item",
        "KEYWORD2::hover::after",
        "OPERATOR:,",
        "TAG:a",
        "ATTRIBUTE:[href]",
        "ATTRIBUTE:margin",
        "NUMBER:-1.5em",
        "NUMBER:10%",
        "FUNCTION:rgb",
        "KEYWORD:!important",
        "FUNCTION:url",
        "LINK:mock.png",
        "ATTRIBUTE:--gap",
        "NUMBER:4px");
  }

  @Test
  void markdown() {
    assertTokens(
        "markdown",
        "# Title\n\nSome **bold** and *it* text with `code` and [link](http://example.invalid).\n"
            + "- item\n> quote\n```java\nint x;\n```\n<!-- note -->\n***",
        "HEADING:# Title",
        "EMPHASIS:**bold**",
        "EMPHASIS:*it*",
        "STRING:`code`",
        "LINK:[link](http://example.invalid)",
        "DELIMITER:-",
        "DELIMITER:>",
        "STRING:```java",
        "STRING:int x;",
        "STRING:```",
        "COMMENT:<!-- note -->",
        "DELIMITER:***");
  }

  @Test
  void properties() {
    assertTokens(
        "properties",
        "# comment\n! also\nmock.key = first \\\n    second\nkey\\ two:value\\u0041\\\\\nnext=1",
        "COMMENT:# comment",
        "COMMENT:! also",
        "ATTRIBUTE:mock.key",
        "STRING: = first \\",
        "STRING:    second",
        "ATTRIBUTE:key\\ two",
        "STRING::value",
        "ESCAPE:\\u0041\\\\",
        "ATTRIBUTE:next",
        "STRING:=1");
  }

  @Test
  void ini() {
    assertTokens(
        "ini",
        "; comment\n[mock section]\nname = \"value\"\nenabled=true\ncount = 42\n# other",
        "COMMENT:; comment",
        "KEYWORD:[mock section]",
        "ATTRIBUTE:name",
        "OPERATOR:=",
        "STRING:\"value\"",
        "ATTRIBUTE:enabled",
        "CONSTANT:true",
        "NUMBER:42",
        "COMMENT:# other");
  }
}
