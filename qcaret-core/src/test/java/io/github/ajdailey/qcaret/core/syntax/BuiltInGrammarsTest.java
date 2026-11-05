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

  @Test
  void go() {
    assertTokens(
        "go",
        "package main\n"
            + "import \"fmt\"\n"
            + "type Mock struct { n int }\n"
            + "func (m *Mock) Run(x float64) error {\n"
            + "\ts := `raw \\n\n"
            + "line`\n"
            + "\tr := '\\n'; v := 0x1F + 1_000i\n"
            + "\tfmt.Println(\"a\\tb\", len(s), nil) // done\n"
            + "\treturn nil\n"
            + "}",
        "KEYWORD:package",
        "STRING:\"fmt\"",
        "KEYWORD:type",
        "TYPE:Mock",
        "KEYWORD:func",
        "FUNCTION:Run",
        "TYPE:float64",
        "TYPE:error",
        "STRING:`raw \\n",
        "STRING:line`",
        "ESCAPE:\\n",
        "NUMBER:0x1F",
        "NUMBER:1_000i",
        "FUNCTION:Println",
        "ESCAPE:\\t",
        "KEYWORD2:len",
        "CONSTANT:nil",
        "COMMENT:// done");
  }

  @Test
  void rust() {
    assertTokens(
        "rust",
        "#[derive(Debug, Clone)]\n"
            + "pub struct Mock<'a> { name: &'a str }\n"
            + "/* outer /* inner */ still */\n"
            + "fn main() -> u32 {\n"
            + "    let s = r#\"raw \"quoted\" \"#; let b = br##\"x\"#y\"##;\n"
            + "    let c = 'x'; let e = \"tab\\t\";\n"
            + "    println!(\"{}\", s); let n = 1_000u32 + 0xffi64;\n"
            + "    let r#match = MAX_SIZE; Some(n)\n"
            + "}",
        "PREPROCESSOR:#[derive(Debug, Clone)]",
        "KEYWORD:pub",
        "KEYWORD:struct",
        "TYPE:Mock",
        "KEYWORD2:'a",
        "COMMENT:/* outer /* inner */ still */",
        "KEYWORD:fn",
        "FUNCTION:main",
        "TYPE:u32",
        "STRING:r#\"raw \"quoted\" \"#",
        "STRING:br##\"x\"#y\"##",
        "STRING:'x'",
        "ESCAPE:\\t",
        "FUNCTION:println!",
        "NUMBER:1_000u32",
        "NUMBER:0xffi64",
        "CONSTANT:MAX_SIZE",
        "TYPE:Some");
  }

  @Test
  void csharp() {
    assertTokens(
        "csharp",
        "[Serializable]\n"
            + "public sealed class Mock : IDisposable {\n"
            + "#region Fields\n"
            + "    private readonly string name = \"a\\tb\";\n"
            + "    var path = @\"C:\\mock\\\"\"x\";\n"
            + "    var msg = $\"Item {count + 1} of {{total}}\";\n"
            + "    var raw = \"\"\"\n"
            + "      text \"quoted\"\n"
            + "      \"\"\";\n"
            + "    public async Task<int> RunAsync() => 0x1F + 2.5m; // done\n"
            + "#endregion\n"
            + "}",
        "PREPROCESSOR:[Serializable",
        "KEYWORD:sealed",
        "TYPE:IDisposable",
        "PREPROCESSOR:#region",
        "TYPE:string",
        "ESCAPE:\\t",
        "STRING:@\"C:\\mock\\",
        "ESCAPE:\"\"",
        "STRING:$\"Item ",
        "DELIMITER:{",
        "NUMBER:1",
        "DELIMITER:}",
        "ESCAPE:{{",
        "STRING:\"\"\"",
        "STRING:      text \"quoted\"",
        "STRING:      \"\"\"",
        "KEYWORD:async",
        "TYPE:Task",
        "FUNCTION:RunAsync",
        "NUMBER:2.5m",
        "COMMENT:// done",
        "PREPROCESSOR:#endregion");
  }

  @Test
  void kotlin() {
    assertTokens(
        "kotlin",
        "@file:JvmName(\"Mock\")\n"
            + "package mock\n"
            + "/* a /* nested */ b */\n"
            + "data class Box<T>(val item: T) {\n"
            + "    fun show(): String = \"Item $item and ${item.hashCode() + 1}\\n\"\n"
            + "    val raw = \"\"\"\n"
            + "        raw $item\n"
            + "    \"\"\"\n"
            + "    val n = 0xFF_FFL; val c = '\\u0041'\n"
            + "    override fun toString() = if (item == null) \"none\" else show()\n"
            + "}",
        "PREPROCESSOR:@file:JvmName",
        "COMMENT:/* a /* nested */ b */",
        "KEYWORD:data",
        "TYPE:Box",
        "KEYWORD:fun",
        "FUNCTION:show",
        "STRING:\"Item ",
        "CONSTANT:$item",
        "DELIMITER:${",
        "FUNCTION:hashCode",
        "DELIMITER:}",
        "ESCAPE:\\n",
        "STRING:\"\"\"",
        "CONSTANT:$item",
        "STRING:    \"\"\"",
        "NUMBER:0xFF_FFL",
        "ESCAPE:\\u0041",
        "KEYWORD:override",
        "CONSTANT:null");
  }

  @Test
  void ruby() {
    assertTokens(
        "ruby",
        "#!/usr/bin/env ruby\n"
            + "require 'json'\n"
            + "class Mock < Base\n"
            + "  attr_reader :name\n"
            + "  def initialize(name)\n"
            + "    @name = name; $count += 1\n"
            + "    text = <<~EOS\n"
            + "      Hello #{name}\n"
            + "    EOS\n"
            + "    list = %w[a b c]; re = /ab+c/i\n"
            + "    puts \"Name: #{@name}\\n\" if name.nil? || defined?(x)\n"
            + "    { key: 1, 'b' => 2.5r }\n"
            + "  end\n"
            + "end\n"
            + "=begin\n"
            + "doc\n"
            + "=end",
        "COMMENT:#!/usr/bin/env ruby",
        "KEYWORD2:require",
        "STRING:'json'",
        "TYPE:Mock",
        "CONSTANT::name",
        "FUNCTION:initialize",
        "KEYWORD2:@name",
        "KEYWORD2:$count",
        "STRING:<<~EOS",
        "STRING:      Hello ",
        "DELIMITER:#{",
        "STRING:    EOS",
        "STRING:%w[a b c]",
        "STRING: /ab+c/i",
        "DELIMITER:#{",
        "KEYWORD2:@name",
        "ESCAPE:\\n",
        "KEYWORD:if",
        "KEYWORD:defined?",
        "CONSTANT:key:",
        "NUMBER:2.5r",
        "KEYWORD:end",
        "COMMENT:=begin",
        "COMMENT:doc",
        "COMMENT:=end");
  }

  @Test
  void php() {
    assertTokens(
        "php",
        "<?php\n"
            + "#[Attribute]\n"
            + "final class Mock {\n"
            + "    public function run(int $n): string {\n"
            + "        $text = \"Count {$this->n} and $n\\n\"; // done\n"
            + "        $raw = <<<'EOT'\n"
            + "        no $interp\n"
            + "        EOT;\n"
            + "        return strtoupper('a\\'b') . 0x1F;\n"
            + "    }\n"
            + "}\n"
            + "?>\n"
            + "<p class=\"x\">Hello <?= $name ?></p>",
        "PREPROCESSOR:<?php",
        "PREPROCESSOR:#[Attribute]",
        "KEYWORD:final",
        "KEYWORD:function",
        "TYPE:int",
        "KEYWORD2:$n",
        "STRING:\"Count ",
        "DELIMITER:{",
        "KEYWORD2:$this",
        "DELIMITER:}",
        "KEYWORD2:$n",
        "ESCAPE:\\n",
        "COMMENT:// done",
        "STRING:<<<'EOT'",
        "STRING:        no $interp",
        "STRING:        EOT",
        "FUNCTION:strtoupper",
        "ESCAPE:\\'",
        "NUMBER:0x1F",
        "PREPROCESSOR:?>",
        "TAG:<p",
        "ATTRIBUTE:class",
        "STRING:\"x\"",
        "PREPROCESSOR:<?=",
        "KEYWORD2:$name",
        "PREPROCESSOR:?>",
        "TAG:</p>");
  }

  @Test
  void perl() {
    assertTokens(
        "perl",
        "#!/usr/bin/perl\n"
            + "use strict;\n"
            + "my @items = qw(a b c);\n"
            + "my %h = (key => 1);\n"
            + "sub run {\n"
            + "    my ($self, $n) = @_;\n"
            + "    $n =~ s/foo/bar/g; my $re = qr{ab+c}i;\n"
            + "    print <<\"END\";\n"
            + "Hello $n\n"
            + "END\n"
            + "    return $#items + 0x1F;\n"
            + "}\n"
            + "=pod\n"
            + "Docs\n"
            + "=cut\n"
            + "__END__\n"
            + "data",
        "COMMENT:#!/usr/bin/perl",
        "KEYWORD:use",
        "KEYWORD2:@items",
        "STRING:qw(a b c)",
        "KEYWORD2:%h",
        "KEYWORD:sub",
        "FUNCTION:run",
        "KEYWORD2:@_",
        "STRING:s/foo/bar/g",
        "STRING:qr{ab+c}i",
        "KEYWORD2:print",
        "STRING:<<\"END\";",
        "STRING:Hello ",
        "KEYWORD2:$n",
        "STRING:END",
        "KEYWORD2:$#items",
        "NUMBER:0x1F",
        "COMMENT:=pod",
        "COMMENT:=cut",
        "COMMENT:__END__",
        "COMMENT:data");
  }

  @Test
  void lua() {
    assertTokens(
        "lua",
        "local Mock = {}\n"
            + "--[==[ block\n"
            + "comment ]] still ]==]\n"
            + "function Mock.run(n)\n"
            + "  local s = [[raw\n"
            + "text]] .. \"a\\tb\"\n"
            + "  if n ~= nil then return 0x1F end\n"
            + "  local x <const> = 1.5e3\n"
            + "  ::done::\n"
            + "  print(#s, true)\n"
            + "end",
        "KEYWORD:local",
        "COMMENT:--[==[ block",
        "COMMENT:comment ]] still ]==]",
        "KEYWORD:function",
        "FUNCTION:Mock.run",
        "STRING:[[raw",
        "STRING:text]]",
        "OPERATOR:..",
        "ESCAPE:\\t",
        "OPERATOR:~=",
        "CONSTANT:nil",
        "KEYWORD:then",
        "NUMBER:0x1F",
        "KEYWORD:end",
        "PREPROCESSOR:<const>",
        "NUMBER:1.5e3",
        "PREPROCESSOR:::done::",
        "KEYWORD2:print",
        "CONSTANT:true");
  }

  @Test
  void groovy() {
    assertTokens(
        "groovy",
        "#!/usr/bin/env groovy\n"
            + "@Grab('mock')\n"
            + "def name = \"world\"\n"
            + "pipeline {\n"
            + "    stages { stage('Build') { steps { sh \"echo ${name} $name\" } } }\n"
            + "}\n"
            + "def re = /ab+c/; def ds = $/a/b$$/$\n"
            + "def multi = '''raw\n"
            + "text'''\n"
            + "int n = 0x1F + 10G",
        "COMMENT:#!/usr/bin/env groovy",
        "PREPROCESSOR:@Grab",
        "KEYWORD:def",
        "STRING:\"world\"",
        "FUNCTION:stage",
        "STRING:\"echo ",
        "DELIMITER:${",
        "DELIMITER:}",
        "CONSTANT:$name",
        "STRING: /ab+c/",
        "STRING:$/a/b",
        "ESCAPE:$$",
        "STRING:/$",
        "STRING:'''raw",
        "STRING:text'''",
        "TYPE:int",
        "NUMBER:10G");
  }

  @Test
  void scala() {
    assertTokens(
        "scala",
        "package mock\n"
            + "/* a /* nested */ b */\n"
            + "@main def run(): Unit =\n"
            + "  val name = \"world\"\n"
            + "  println(s\"Hello $name and ${name.length + 1}\")\n"
            + "  val raw = \"\"\"a \"q\" b\"\"\"\n"
            + "  val c = 'x'\n"
            + "  given Ordering[Int] = Ordering.Int\n"
            + "  case class Box[T](item: T) extends Serializable\n"
            + "  if true then 0x1FL else 2.5",
        "COMMENT:/* a /* nested */ b */",
        "PREPROCESSOR:@main",
        "KEYWORD:def",
        "FUNCTION:run",
        "TYPE:Unit",
        "STRING:s\"Hello ",
        "CONSTANT:$name",
        "DELIMITER:${",
        "NUMBER:1",
        "DELIMITER:}",
        "STRING:\"\"\"a \"q\" b\"\"\"",
        "STRING:'x'",
        "KEYWORD:given",
        "KEYWORD:case",
        "KEYWORD:class",
        "TYPE:Box",
        "KEYWORD:extends",
        "KEYWORD:then",
        "NUMBER:0x1FL");
  }

  @Test
  void swift() {
    assertTokens(
        "swift",
        "import Foundation\n"
            + "/* a /* nested */ b */\n"
            + "@MainActor\n"
            + "struct Mock: Codable {\n"
            + "    let raw = #\"raw \\n \"quoted\" \\#(x)\"#\n"
            + "    var text = \"Item \\(count(of: list) + 1) done\\n\"\n"
            + "    func run() async throws -> Int { return 0x1F }\n"
            + "}\n"
            + "#if DEBUG\n"
            + "let block = \"\"\"\n"
            + "  multi \\(name)\n"
            + "  \"\"\"\n"
            + "#endif",
        "KEYWORD:import",
        "COMMENT:/* a /* nested */ b */",
        "PREPROCESSOR:@MainActor",
        "KEYWORD:struct",
        "TYPE:Codable",
        "STRING:#\"raw \\n \"quoted\" ",
        "ESCAPE:\\#(",
        "STRING:x)\"#",
        "STRING:\"Item ",
        "DELIMITER:\\(",
        "FUNCTION:count",
        "DELIMITER:(",
        "DELIMITER:)",
        "NUMBER:1",
        "DELIMITER:)",
        "STRING: done",
        "ESCAPE:\\n",
        "KEYWORD:func",
        "KEYWORD:async",
        "KEYWORD:throws",
        "PREPROCESSOR:#if",
        "STRING:\"\"\"",
        "DELIMITER:\\(",
        "STRING:  \"\"\"",
        "PREPROCESSOR:#endif");
  }

  @Test
  void dart() {
    assertTokens(
        "dart",
        "import 'package:mock/mock.dart';\n"
            + "/* a /* nested */ b */\n"
            + "@override\n"
            + "class Box<T> extends Base {\n"
            + "  final String name = 'Item $count and ${count + 1}\\n';\n"
            + "  var raw = r'no $interp';\n"
            + "  Future<void> run() async { return null; }\n"
            + "  late final int n = 0x1F;\n"
            + "}",
        "KEYWORD:import",
        "STRING:'package:mock/mock.dart'",
        "COMMENT:/* a /* nested */ b */",
        "PREPROCESSOR:@override",
        "KEYWORD:class",
        "TYPE:Box",
        "KEYWORD:extends",
        "STRING:'Item ",
        "CONSTANT:$count",
        "DELIMITER:${",
        "DELIMITER:}",
        "ESCAPE:\\n",
        "STRING:r'no $interp'",
        "TYPE:Future",
        "TYPE:void",
        "KEYWORD:async",
        "CONSTANT:null",
        "KEYWORD:late",
        "NUMBER:0x1F");
  }

  @Test
  void rLanguage() {
    assertTokens(
        "r",
        "#!/usr/bin/env Rscript\n"
            + "library(stats)\n"
            + "mock <- function(x, ...) {\n"
            + "  s <- r\"(raw \\n \"q\")\"\n"
            + "  t <- r\"-[x]-\"\n"
            + "  y <- x %in% c(1L, 2.5e3, NA)\n"
            + "  if (is.null(x)) return(TRUE)\n"
            + "  lapply(x, \\(v) v + 1) |> sum()\n"
            + "}",
        "COMMENT:#!/usr/bin/env Rscript",
        "KEYWORD2:library",
        "OPERATOR:<-",
        "KEYWORD:function",
        "STRING:r\"(raw \\n \"q\")\"",
        "STRING:r\"-[x]-\"",
        "OPERATOR:%in%",
        "KEYWORD2:c",
        "NUMBER:1L",
        "NUMBER:2.5e3",
        "CONSTANT:NA",
        "KEYWORD2:is.null",
        "CONSTANT:TRUE",
        "KEYWORD2:lapply",
        "OPERATOR:\\",
        "OPERATOR:|>");
  }

  @Test
  void haskell() {
    assertTokens(
        "haskell",
        "{-# LANGUAGE OverloadedStrings #-}\n"
            + "module Mock (run) where\n"
            + "{- outer {- inner -} still -}\n"
            + "import qualified Data.Map as M\n"
            + "run :: Int -> Maybe String\n"
            + "run n = case n of\n"
            + "  0 -> Nothing -- none\n"
            + "  _ -> Just (\"n=\" ++ show n ++ \"\\n\" ++ [c'])\n"
            + "  where c' = 'x'\n"
            + "x --> y = x",
        "PREPROCESSOR:{-# LANGUAGE OverloadedStrings #-}",
        "KEYWORD:module",
        "TYPE:Mock",
        "KEYWORD:where",
        "COMMENT:{- outer {- inner -} still -}",
        "KEYWORD:qualified",
        "TYPE:Data",
        "KEYWORD:as",
        "FUNCTION:run",
        "OPERATOR:::",
        "TYPE:Maybe",
        "KEYWORD:case",
        "KEYWORD:of",
        "TYPE:Nothing",
        "COMMENT:-- none",
        "STRING:\"n=\"",
        "OPERATOR:++",
        "ESCAPE:\\n",
        "STRING:'x'",
        "OPERATOR:-->");
  }

  @Test
  void objectivec() {
    assertTokens(
        "objectivec",
        "#import <Foundation/Foundation.h>\n"
            + "@interface Mock : NSObject\n"
            + "@property (nonatomic, strong) NSString *name;\n"
            + "- (instancetype)initWithName:(NSString *)name;\n"
            + "@end\n"
            + "@implementation Mock\n"
            + "- (BOOL)run { NSArray *a = @[@1, @\"x\\n\"]; return YES; } // done\n"
            + "@end",
        "PREPROCESSOR:#import",
        "STRING:<Foundation/Foundation.h>",
        "KEYWORD:@interface",
        "TYPE:NSObject",
        "KEYWORD:@property",
        "KEYWORD:nonatomic",
        "KEYWORD:strong",
        "TYPE:NSString",
        "TYPE:instancetype",
        "KEYWORD:@end",
        "KEYWORD:@implementation",
        "TYPE:BOOL",
        "OPERATOR:@",
        "STRING:@\"x",
        "ESCAPE:\\n",
        "CONSTANT:YES",
        "COMMENT:// done",
        "KEYWORD:@end");
  }

  @Test
  void vbnet() {
    assertTokens(
        "vbnet",
        "Imports System.Text\n"
            + "<Serializable>\n"
            + "Public Class Mock\n"
            + "    ' comment\n"
            + "    Private ReadOnly _name As String = \"a\"\"b\"\n"
            + "    Public Function Run(ByVal n As Integer) As Boolean\n"
            + "        Dim msg = $\"Item {n + 1} of {{all}}\"\n"
            + "        If n > &H1F Then Return True\n"
            + "        Dim d = #1/1/2024#\n"
            + "        REM old style\n"
            + "        Return CBool(Nothing)\n"
            + "    End Function\n"
            + "End Class",
        "KEYWORD:Imports",
        "PREPROCESSOR:<Serializable",
        "KEYWORD:Public",
        "KEYWORD:Class",
        "COMMENT:' comment",
        "KEYWORD:ReadOnly",
        "TYPE:String",
        "ESCAPE:\"\"",
        "KEYWORD:Function",
        "FUNCTION:Run",
        "KEYWORD:ByVal",
        "TYPE:Integer",
        "STRING:$\"Item ",
        "DELIMITER:{",
        "DELIMITER:}",
        "ESCAPE:{{",
        "NUMBER:&H1F",
        "KEYWORD:Then",
        "CONSTANT:True",
        "CONSTANT:#1/1/2024#",
        "COMMENT:REM old style",
        "KEYWORD2:CBool",
        "CONSTANT:Nothing",
        "KEYWORD:End");
  }

  @Test
  void dockerfile() {
    assertTokens(
        "dockerfile",
        "# syntax=docker/dockerfile:1\n# build\nFROM mock/base:1.0 AS build\nARG MOCK_VERSION=2\n"
            + "ENV PATH=\"/opt/mock/bin:${PATH}\"\nrun --mount=type=cache apt-get update && \\\n"
            + "    echo 'done'\nCOPY --from=build /src /app\nEXPOSE 8080/tcp\n"
            + "CMD [\"mock\", \"--serve\"]",
        "PREPROCESSOR:# syntax=docker/dockerfile:1",
        "COMMENT:# build",
        "KEYWORD:FROM",
        "KEYWORD:AS",
        "KEYWORD:ARG",
        "ATTRIBUTE:MOCK_VERSION",
        "NUMBER:2",
        "KEYWORD:ENV",
        "STRING:\"/opt/mock/bin:",
        "CONSTANT:${PATH}",
        "KEYWORD:run",
        "ATTRIBUTE:--mount",
        "OPERATOR:&&",
        "OPERATOR:\\",
        "STRING:'done'",
        "ATTRIBUTE:--from",
        "NUMBER:8080/tcp",
        "DELIMITER:[",
        "STRING:\"mock\"");
  }

  @Test
  void makefile() {
    assertTokens(
        "makefile",
        "# mock\nCC ?= cc\nSRC := $(wildcard src/*.c)\n.PHONY: all\nall: mock $(OBJ)\n"
            + "\t@echo \"$@ from $< in $${HOME}\"\n\t-$(CC) $(patsubst %.c,%.o,$(SRC))\n"
            + "ifeq ($(OS),Windows_NT)\nendif",
        "COMMENT:# mock",
        "ATTRIBUTE:CC",
        "OPERATOR:?=",
        "ATTRIBUTE:SRC",
        "OPERATOR::=",
        "DELIMITER:$(",
        "FUNCTION:wildcard",
        "DELIMITER:)",
        "FUNCTION:.PHONY",
        "FUNCTION:all",
        "CONSTANT:OBJ",
        "OPERATOR:\t@",
        "CONSTANT:$@",
        "CONSTANT:$<",
        "ESCAPE:$$",
        "OPERATOR:\t-",
        "CONSTANT:CC",
        "FUNCTION:patsubst",
        "CONSTANT:SRC",
        "DELIMITER:))",
        "KEYWORD:ifeq",
        "CONSTANT:OS",
        "KEYWORD:endif");
  }

  @Test
  void cmake() {
    assertTokens(
        "cmake",
        "cmake_minimum_required(VERSION 3.20)\n#[[ block\ncomment ]]\n"
            + "set(MOCK_SRC \"a.c;${PROJECT_SOURCE_DIR}/x.c\" CACHE STRING \"doc\")\n"
            + "if(NOT DEFINED ENV{MOCK_HOME})\n  message(STATUS \"$ENV{MOCK_HOME}\\n\")\nendif()\n"
            + "target_compile_definitions(mock PRIVATE $<$<CONFIG:Debug>:MOCK=1>)\n"
            + "option(MOCK_TESTS \"tests\" ON) # trailing",
        "FUNCTION:cmake_minimum_required",
        "KEYWORD2:VERSION",
        "NUMBER:3.20",
        "COMMENT:#[[ block",
        "COMMENT:comment ]]",
        "FUNCTION:set",
        "STRING:\"a.c;",
        "CONSTANT:${PROJECT_SOURCE_DIR}",
        "KEYWORD2:CACHE",
        "KEYWORD:if",
        "OPERATOR:NOT",
        "OPERATOR:DEFINED",
        "FUNCTION:message",
        "CONSTANT:$ENV{MOCK_HOME}",
        "ESCAPE:\\n",
        "KEYWORD:endif",
        "KEYWORD2:PRIVATE",
        "PREPROCESSOR:$<$<CONFIG:Debug>",
        "CONSTANT:ON",
        "COMMENT:# trailing");
  }

  @Test
  void toml() {
    assertTokens(
        "toml",
        "# mock\ntitle = \"Mock \\u0041\\q\"\n[server.alpha]\nip = '10.0.0.1'\n"
            + "\"quoted key\".sub = 1_000\nenabled = true\nratio = -3.5e2\nhex = 0xDEAD_BEEF\n"
            + "when = 1979-05-27T07:32:00Z\n[[products]]\npoint = { x = 1, y = inf }\n"
            + "text = \"\"\"\nline \\\nend\"\"\"\"",
        "COMMENT:# mock",
        "ATTRIBUTE:title",
        "OPERATOR:=",
        "STRING:\"Mock ",
        "ESCAPE:\\u0041",
        "INVALID:\\q",
        "KEYWORD:[server.alpha]",
        "STRING:'10.0.0.1'",
        "ATTRIBUTE:\"quoted key\".sub",
        "NUMBER:1_000",
        "CONSTANT:true",
        "NUMBER:-3.5e2",
        "NUMBER:0xDEAD_BEEF",
        "NUMBER:1979-05-27T07:32:00Z",
        "KEYWORD:[[products]]",
        "ATTRIBUTE:x",
        "ATTRIBUTE:y",
        "NUMBER:inf",
        "STRING:\"\"\"",
        "STRING:line ",
        "ESCAPE:\\",
        "STRING:end\"\"\"\"");
  }

  @Test
  void diff() {
    assertEquals(
        List.of(
            "HEADING:diff --git a/mock.txt b/mock.txt",
            "HEADING:index 1234567..89abcde 100644",
            "HEADING:--- a/mock.txt",
            "HEADING:+++ b/mock.txt",
            "KEYWORD:@@ -1,4 +1,4 @@",
            "FUNCTION: class Mock",
            "ESCAPE:-old",
            "ESCAPE:--- removed comment",
            "TYPE:+new",
            "COMMENT:\\ No newline at end of file",
            "HEADING:diff -c a/other b/other",
            "HEADING:*** a/other",
            "HEADING:--- b/other",
            "DELIMITER:***************",
            "KEYWORD:*** 1,2 ****",
            "CONSTANT:! changed",
            "KEYWORD:--- 1,2 ----",
            "TYPE:+ added"),
        Tokens.of(
            "diff",
            "diff --git a/mock.txt b/mock.txt\nindex 1234567..89abcde 100644\n--- a/mock.txt\n"
                + "+++ b/mock.txt\n@@ -1,4 +1,4 @@ class Mock\n context\n-old\n--- removed comment\n"
                + "+new\n\\ No newline at end of file\ndiff -c a/other b/other\n*** a/other\n"
                + "--- b/other\n***************\n*** 1,2 ****\n! changed\n--- 1,2 ----\n+ added"));
  }

  @Test
  void csvColorsColumnsAndKeepsAQuotedLineBreakInItsColumn() {
    assertEquals(
        List.of(
            "KEYWORD2:name",
            "DELIMITER:,",
            "TYPE:\"quoted, value\"",
            "DELIMITER:,",
            "NUMBER:count",
            "KEYWORD2:a",
            "DELIMITER:,",
            "TYPE:\"multi",
            "TYPE:line ",
            "ESCAPE:\"\"",
            "TYPE:x",
            "ESCAPE:\"\"",
            "TYPE:\"",
            "DELIMITER:,",
            "NUMBER:3",
            "DELIMITER:,",
            "PREPROCESSOR:d",
            "DELIMITER:,",
            "STRING:e",
            "DELIMITER:,",
            "KEYWORD2:f",
            "DELIMITER:,,",
            "NUMBER:h",
            "KEYWORD2:next"),
        Tokens.of(
            "csv", "name,\"quoted, value\",count\na,\"multi\nline \"\"x\"\"\",3,d,e,f,,h\nnext"));
  }

  @Test
  void tsvColorsColumns() {
    assertEquals(
        List.of(
            "KEYWORD2:name",
            "DELIMITER:\t",
            "TYPE:\"value\"",
            "DELIMITER:\t",
            "NUMBER:count",
            "KEYWORD2:a"),
        Tokens.of("tsv", "name\t\"value\"\tcount\na"));
  }

  @Test
  void log() {
    assertTokens(
        "log",
        "2026-01-02T03:04:05.678Z [main] INFO  com.example.mock.Service - Started"
            + " url=https://example.invalid/a?b=c\n"
            + "2026-01-02 03:04:05,678 ERROR [pool-1-thread-2] c.e.MockJob: id=42 user=\"mock\"\n"
            + "Jan  2 03:04:05 mockhost mockd[123]: WARNING: low disk\n"
            + "127.0.0.1 - - [02/Jan/2026:03:04:05 +0000] \"GET / HTTP/1.1\" 200 512\n"
            + "java.lang.IllegalStateException: mock failure\n"
            + "\tat com.example.mock.Service.run(Service.java:42)\n\t... 3 more\n"
            + "Caused by: java.io.IOException: closed\n"
            + "time=03:04:05 level=warn msg=\"retry\" lvl=debug\n"
            + "{\"level\":\"error\",\"msg\":\"x\"}\n"
            + "E0102 03:04:05.678901 12345 mock.go:42] failure\n"
            + "D/MockTag( 123): debug\n[TRACE] tracing [W] short",
        "NUMBER:2026-01-02T03:04:05.678Z",
        "DELIMITER:[main]",
        "TYPE:INFO",
        "FUNCTION:com.example.mock.Service",
        "ATTRIBUTE:url",
        "LINK:https://example.invalid/a?b=c",
        "NUMBER:2026-01-02 03:04:05,678",
        "INVALID:ERROR",
        "DELIMITER:[pool-1-thread-2]",
        "FUNCTION:c.e.MockJob",
        "ATTRIBUTE:id",
        "STRING:\"mock\"",
        "NUMBER:Jan  2 03:04:05",
        "ESCAPE:WARNING",
        "NUMBER:[02/Jan/2026:03:04:05 +0000]",
        "KEYWORD:java.lang.IllegalStateException",
        "COMMENT:\tat com.example.mock.Service.run(Service.java:42)",
        "COMMENT:\t... 3 more",
        "KEYWORD:Caused by:",
        "KEYWORD:java.io.IOException",
        "NUMBER:03:04:05",
        "ESCAPE:warn",
        "KEYWORD2:debug",
        "STRING:\"level\"",
        "INVALID:\"error\"",
        "INVALID:E",
        "NUMBER:03:04:05.678901",
        "KEYWORD2:D",
        "COMMENT:TRACE",
        "ESCAPE:W");
    assertAbsent("log", "no error found in Info", "INVALID:error");
  }

  @Test
  void hcl() {
    assertTokens(
        "hcl",
        "# mock\nresource \"mock_instance\" \"web\" {\n  count = var.enabled ? 2 : 0\n"
            + "  name  = \"web-${count.index}-%{ if local.x }a%{ endif }\"\n"
            + "  list  = [for s in var.items : upper(s)]\n  /* note */\n  policy = <<-EOF\n"
            + "    {\"a\": 1}\n  EOF\n}\nvariable \"size\" {\n  type = list(string)\n"
            + "  default = null\n}",
        "COMMENT:# mock",
        "KEYWORD:resource",
        "STRING:\"mock_instance\"",
        "DELIMITER:{",
        "ATTRIBUTE:count",
        "KEYWORD2:var",
        "OPERATOR:?",
        "NUMBER:2",
        "STRING:\"web-",
        "DELIMITER:${",
        "KEYWORD2:count",
        "DELIMITER:%{",
        "KEYWORD:if",
        "KEYWORD:endif",
        "KEYWORD:for",
        "KEYWORD:in",
        "FUNCTION:upper",
        "COMMENT:/* note */",
        "STRING:<<-EOF",
        "STRING:    {\"a\": 1}",
        "STRING:  EOF",
        "KEYWORD:variable",
        "TYPE:list",
        "TYPE:string",
        "CONSTANT:null");
  }

  @Test
  void protobuf() {
    assertTokens(
        "protobuf",
        "syntax = \"proto3\";\nimport \"google/protobuf/timestamp.proto\";\n// note\n"
            + "message Mock {\n  option (mock.opt) = true;\n"
            + "  repeated string names = 1 [deprecated = true];\n  map<string, int64> counts = 2;\n"
            + "  double ratio = 3; google.protobuf.Timestamp at = 4; /* x */\n}\n"
            + "service MockService {\n  rpc Get(Mock) returns (stream Mock);\n}",
        "KEYWORD:syntax",
        "STRING:\"proto3\"",
        "KEYWORD:import",
        "COMMENT:// note",
        "KEYWORD:message",
        "TYPE:Mock",
        "KEYWORD:option",
        "ATTRIBUTE:(mock.opt)",
        "CONSTANT:true",
        "KEYWORD:repeated",
        "TYPE:string",
        "NUMBER:1",
        "KEYWORD:map",
        "TYPE:int64",
        "TYPE:double",
        "TYPE:google.protobuf.Timestamp",
        "COMMENT:/* x */",
        "KEYWORD:service",
        "TYPE:MockService",
        "KEYWORD:rpc",
        "FUNCTION:Get",
        "TYPE:Mock",
        "KEYWORD:returns",
        "KEYWORD:stream");
  }

  @Test
  void graphql() {
    assertTokens(
        "graphql",
        "# mock\nquery GetMock($id: ID!, $first: Int = 10) @cached {\n"
            + "  mock(id: $id) { name ...MockFields }\n}\n\"\"\"\nBlock \\\"\"\" doc\n\"\"\"\n"
            + "type Mock implements Node {\n  status: Status! @deprecated(reason: \"a\\\"b\")\n}\n"
            + "enum Status { ACTIVE INACTIVE }",
        "COMMENT:# mock",
        "KEYWORD:query",
        "TYPE:GetMock",
        "CONSTANT:$id",
        "TYPE:ID",
        "OPERATOR:!",
        "TYPE:Int",
        "NUMBER:10",
        "PREPROCESSOR:@cached",
        "ATTRIBUTE:id",
        "OPERATOR:...",
        "TYPE:MockFields",
        "STRING:\"\"\"",
        "STRING:Block ",
        "ESCAPE:\\\"\"\"",
        "STRING: doc",
        "STRING:\"\"\"",
        "KEYWORD:type",
        "KEYWORD:implements",
        "ATTRIBUTE:status",
        "PREPROCESSOR:@deprecated",
        "ESCAPE:\\\"",
        "KEYWORD:enum",
        "CONSTANT:ACTIVE");
  }

  @Test
  void nginx() {
    assertTokens(
        "nginx",
        "# mock\nuser mock;\nhttp {\n    client_max_body_size 10m;\n    server {\n"
            + "        listen 8080;\n        location ~* \\.(png|jpg)$ {\n"
            + "            add_header X-Mock \"v=$host\"; expires 30d;\n        }\n"
            + "        if ($request_uri = /x) { return 301 https://${host}; } # done\n"
            + "        gzip on;\n    }\n}",
        "COMMENT:# mock",
        "KEYWORD:user",
        "DELIMITER:;",
        "KEYWORD:http",
        "DELIMITER:{",
        "KEYWORD:client_max_body_size",
        "NUMBER:10m",
        "KEYWORD:server",
        "KEYWORD:listen",
        "NUMBER:8080",
        "KEYWORD:location",
        "OPERATOR:~*",
        "KEYWORD:add_header",
        "STRING:\"v=",
        "CONSTANT:$host",
        "KEYWORD:expires",
        "NUMBER:30d",
        "KEYWORD:if",
        "CONSTANT:$request_uri",
        "OPERATOR:=",
        "KEYWORD:return",
        "NUMBER:301",
        "CONSTANT:${host}",
        "COMMENT:# done",
        "KEYWORD:gzip",
        "CONSTANT:on");
  }
}
