package io.github.ajdailey.qcaret.core.syntax;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.ArrayList;
import java.util.List;
import java.util.Set;
import org.junit.jupiter.api.Test;

/**
 * One sample per built-in grammar with symbol rules: the definitions it lists, as {@code
 * kind:name@line}, and none from a comment or a string.
 */
class SymbolRulesTest {
  /** Built-in grammars without named definitions. */
  private static final Set<String> WITHOUT =
      Set.of("plain", "xml", "html", "json", "csv", "tsv", "log");

  private static void assertSymbols(String grammar, String text, String... expected) {
    List<String> actual = new ArrayList<String>();
    for (String s : SymbolScannerTest.scan(Tokens.BUILT_IN.syntax(grammar), text)) {
      actual.add(s.substring(0, s.lastIndexOf(':')));
    }
    assertEquals(List.of(expected), actual, grammar);
  }

  @Test
  void everyGrammarWithNamedDefinitionsHasSymbolRules() {
    for (Grammar g : Tokens.BUILT_IN.byName()) {
      assertEquals(!WITHOUT.contains(g.id()), !g.symbols().isEmpty(), g.id());
    }
    assertTrue(GrammarSet.BUILT_IN.containsAll(WITHOUT));
  }

  @Test
  void java() {
    assertSymbols(
        "java",
        "package mock;\n"
            + "/** class Hidden { void no() {} } */\n"
            + "public final class Mock<T> extends Base {\n"
            + "  private static final String S = \"class InString\";\n"
            + "  public Mock(int n) { super(n); }\n"
            + "  @Override public String toString() { return run(1); }\n"
            + "  static <K> Map<K, List<T>> group(List<K> keys) {\n"
            + "    if (keys.isEmpty()) { return new HashMap<>(); }\n"
            + "    String s = format(x);\n"
            + "    return group(keys);\n"
            + "  }\n"
            + "  interface Listener { void changed(int[] at); }\n"
            + "  enum Mode { ON, OFF }\n"
            + "  record Point(int x, int y) {}\n"
            + "  // void commented() {}\n"
            + "}\n",
        "class:Mock@2",
        "method:Mock@4",
        "method:toString@5",
        "method:group@6",
        "interface:Listener@11",
        "enum:Mode@12",
        "class:Point@13");
  }

  @Test
  void c() {
    assertSymbols(
        "c",
        "#include <stdio.h>\n"
            + "#define LIMIT 10\n"
            + "/* int hidden(void) { */\n"
            + "struct node {\n"
            + "  int value;\n"
            + "};\n"
            + "typedef struct node node_t;\n"
            + "enum color { RED };\n"
            + "static int count(const node_t *n);\n"
            + "static int count(const node_t *n)\n"
            + "{\n"
            + "  if (n) { return 1; }\n"
            + "  return printf(\"int fake(void) {\");\n"
            + "}\n"
            + "int main(void) {\n"
            + "}\n",
        "macro:LIMIT@1",
        "struct:node@3",
        "type:node_t@6",
        "enum:color@7",
        "function:count@9",
        "function:main@14");
  }

  @Test
  void cpp() {
    assertSymbols(
        "cpp",
        "namespace mock {\n"
            + "class Box final : public Base {\n"
            + " public:\n"
            + "  int get() const;\n"
            + "};\n"
            + "class Forward;\n"
            + "int Box::get() const {\n"
            + "  return 1;\n"
            + "}\n"
            + "Box::~Box() {\n"
            + "}\n"
            + "// void hidden() {\n"
            + "}\n",
        "namespace:mock@0",
        "class:Box@1",
        "function:Box::get@6",
        "function:Box::~Box@9");
  }

  @Test
  void python() {
    assertSymbols(
        "python",
        "import os\n"
            + "class Mock(Base):\n"
            + "    \"\"\"\n"
            + "    def hidden(self):\n"
            + "    \"\"\"\n"
            + "    def run(self):\n"
            + "        pass\n"
            + "    async def fetch(self):\n"
            + "        pass\n"
            + "# def commented():\n"
            + "def main():\n"
            + "    x = 'def no(): pass'\n",
        "class:Mock@1",
        "method:run@5",
        "method:fetch@7",
        "function:main@10");
  }

  @Test
  void javascript() {
    assertSymbols(
        "javascript",
        "// function hidden() {}\n"
            + "export class Mock extends Base {\n"
            + "  constructor(a) { super(a); }\n"
            + "  static async load(url) {\n"
            + "    if (url) { return 1; }\n"
            + "  }\n"
            + "  #secret() {}\n"
            + "}\n"
            + "function* walk(tree) {}\n"
            + "const add = (a, b) => a + b;\n"
            + "let s = \"function fake() {}\";\n"
            + "it('x', function () {\n"
            + "});\n",
        "class:Mock@1",
        "method:constructor@2",
        "method:load@3",
        "method:#secret@6",
        "function:walk@8",
        "function:add@9");
  }

  @Test
  void typescript() {
    assertSymbols(
        "typescript",
        "export interface Shape { area(): number; }\n"
            + "type Id<T> = string;\n"
            + "enum Mode { On }\n"
            + "namespace App.Model {\n"
            + "}\n"
            + "abstract class Square implements Shape {\n"
            + "  private area(): number {\n"
            + "    return 1;\n"
            + "  }\n"
            + "}\n"
            + "/* type Hidden = 1; */\n",
        "interface:Shape@0",
        "type:Id@1",
        "enum:Mode@2",
        "namespace:App.Model@3",
        "class:Square@5",
        "method:area@6");
  }

  @Test
  void shell() {
    assertSymbols(
        "shell",
        "#!/bin/sh\n"
            + "# deploy() {\n"
            + "deploy() {\n"
            + "  echo \"build() {\"\n"
            + "}\n"
            + "function clean {\n"
            + "}\n"
            + "function check_all() {\n"
            + "}\n",
        "function:deploy@2",
        "function:clean@5",
        "function:check_all@7");
  }

  @Test
  void powershell() {
    assertSymbols(
        "powershell",
        "# function Hidden {}\n"
            + "function Get-Mock {\n"
            + "}\n"
            + "Function global:Set-Mock($x) {}\n"
            + "filter Only-Even { $_ }\n"
            + "class Widget {\n"
            + "}\n"
            + "enum Shade { Light }\n",
        "function:Get-Mock@1",
        "function:Set-Mock@3",
        "function:Only-Even@4",
        "class:Widget@5",
        "enum:Shade@7");
  }

  @Test
  void batch() {
    assertSymbols(
        "batch",
        "@echo off\n"
            + ":: not a label\n"
            + "rem :hidden\n"
            + "goto :build\n"
            + ":build\n"
            + "  :clean_up\n"
            + "exit /b\n",
        "label:build@4",
        "label:clean_up@5");
  }

  @Test
  void sql() {
    assertSymbols(
        "sql",
        "-- create table hidden (id int);\n"
            + "CREATE TABLE IF NOT EXISTS app.orders (id INT);\n"
            + "create or replace view open_orders as select 'create table fake' from orders;\n"
            + "CREATE FUNCTION total(x int) RETURNS int AS $$ SELECT 1 $$;\n"
            + "create temporary table scratch (x int);\n"
            + "CREATE TYPE mood AS ENUM ('a');\n",
        "table:app.orders@1",
        "view:open_orders@2",
        "function:total@3",
        "table:scratch@4",
        "type:mood@5");
  }

  @Test
  void css() {
    assertSymbols(
        "css",
        "/* .hidden { } */\n"
            + "body, html {\n"
            + "  margin: 0;\n"
            + "}\n"
            + "@media screen {\n"
            + "  a:hover { color: red; }\n"
            + "}\n",
        "selector:body, html@1",
        "selector:a:hover@5");
  }

  @Test
  void markdown() {
    assertSymbols(
        "markdown",
        "# Title\n"
            + "text # not a heading\n"
            + "```\n"
            + "# inside code\n"
            + "```\n"
            + "## Install ##\n"
            + "    # indented code\n"
            + "<!-- # hidden -->\n",
        "section:Title@0",
        "section:Install@5");
  }

  @Test
  void ini() {
    assertSymbols(
        "ini",
        "; [hidden]\n[server]\nport=1\n  [ client.one ]\n",
        "section:server@1",
        "section: client.one @3");
  }

  @Test
  void properties() {
    assertSymbols(
        "properties",
        "# hidden=1\n"
            + "app.name = Mock\n"
            + "app.list = a, \\\n"
            + "    b\n"
            + "path\\ name:value\n",
        "property:app.name@1",
        "property:app.list@2",
        "property:path\\ name@4");
  }

  @Test
  void yaml() {
    assertSymbols(
        "yaml",
        "# hidden: 1\n"
            + "services:\n"
            + "  web:\n"
            + "    image: x\n"
            + "volumes: {}\n"
            + "- item: 1\n",
        "section:services@1",
        "section:volumes@4");
  }

  @Test
  void go() {
    assertSymbols(
        "go",
        "package main\n"
            + "// func hidden() {}\n"
            + "type Server struct {\n"
            + "}\n"
            + "type Handler interface {\n"
            + "}\n"
            + "type ID string\n"
            + "const Limit = 10\n"
            + "var cache = map[string]int{}\n"
            + "func (s *Server) Start() error {\n"
            + "  return nil\n"
            + "}\n"
            + "func main() {\n"
            + "  s := \"func fake() {}\"\n"
            + "}\n",
        "struct:Server@2",
        "interface:Handler@4",
        "type:ID@6",
        "constant:Limit@7",
        "variable:cache@8",
        "method:Start@9",
        "function:main@12");
  }

  @Test
  void rust() {
    assertSymbols(
        "rust",
        "mod net;\n"
            + "/* fn hidden() {} */\n"
            + "pub struct Point { x: i32 }\n"
            + "enum Shape { Circle }\n"
            + "pub trait Draw {\n"
            + "    fn draw(&self);\n"
            + "}\n"
            + "type Result<T> = std::result::Result<T, Error>;\n"
            + "pub const MAX_SIZE: usize = 10;\n"
            + "macro_rules! square {\n"
            + "}\n"
            + "impl Point {\n"
            + "    pub async fn new() -> Self { Point { x: 0 } }\n"
            + "}\n"
            + "fn main() {\n"
            + "    let s = \"fn fake() {}\";\n"
            + "}\n",
        "module:net@0",
        "struct:Point@2",
        "enum:Shape@3",
        "trait:Draw@4",
        "method:draw@5",
        "type:Result@7",
        "constant:MAX_SIZE@8",
        "macro:square@9",
        "method:new@12",
        "function:main@14");
  }

  @Test
  void csharp() {
    assertSymbols(
        "csharp",
        "namespace Mock.App\n"
            + "{\n"
            + "  // class Hidden {}\n"
            + "  public sealed class Store : IStore\n"
            + "  {\n"
            + "    public Store(int size) { }\n"
            + "    public int Count { get; private set; }\n"
            + "    public async Task<List<int>> LoadAsync(string path)\n"
            + "    {\n"
            + "      if (path == null) { return null; }\n"
            + "      var s = \"void Fake() {}\";\n"
            + "      await Save(path);\n"
            + "    }\n"
            + "  }\n"
            + "  public interface IStore { }\n"
            + "  public record struct Pair(int A, int B);\n"
            + "  enum Mode { On }\n"
            + "}\n",
        "namespace:Mock.App@0",
        "class:Store@3",
        "method:Store@5",
        "property:Count@6",
        "method:LoadAsync@7",
        "interface:IStore@14",
        "struct:Pair@15",
        "enum:Mode@16");
  }

  @Test
  void kotlin() {
    assertSymbols(
        "kotlin",
        "package mock\n"
            + "// fun hidden() {}\n"
            + "data class User(val name: String)\n"
            + "enum class Color { RED }\n"
            + "interface Repo\n"
            + "object Registry {\n"
            + "  const val LIMIT = 10\n"
            + "  fun <T> List<T>.second(): T = this[1]\n"
            + "}\n"
            + "typealias Names = List<String>\n"
            + "fun main() {\n"
            + "  println(\"fun fake() {}\")\n"
            + "}\n",
        "class:User@2",
        "enum:Color@3",
        "interface:Repo@4",
        "class:Registry@5",
        "constant:LIMIT@6",
        "function:second@7",
        "type:Names@9",
        "function:main@10");
  }

  @Test
  void ruby() {
    assertSymbols(
        "ruby",
        "# def hidden\n"
            + "module Shop\n"
            + "  class Cart < Base\n"
            + "    def self.build\n"
            + "    end\n"
            + "    def empty?\n"
            + "      \"def fake\"\n"
            + "    end\n"
            + "    def ==(other)\n"
            + "    end\n"
            + "    class << self\n"
            + "    end\n"
            + "  end\n"
            + "end\n",
        "module:Shop@1",
        "class:Cart@2",
        "method:build@3",
        "method:empty?@5",
        "method:==@8");
  }

  @Test
  void php() {
    assertSymbols(
        "php",
        "<?php\n"
            + "namespace App\\Model;\n"
            + "// function hidden() {}\n"
            + "interface Shape {}\n"
            + "trait Logs {}\n"
            + "enum Suit {}\n"
            + "final class Order {\n"
            + "  public const LIMIT = 10;\n"
            + "  public static function create(): self {\n"
            + "    $f = function ($x) { return 'function fake() {}'; };\n"
            + "  }\n"
            + "}\n"
            + "function &helper() {}\n",
        "namespace:App\\Model@1",
        "interface:Shape@3",
        "trait:Logs@4",
        "enum:Suit@5",
        "class:Order@6",
        "constant:LIMIT@7",
        "method:create@8",
        "function:helper@12");
  }

  @Test
  void perl() {
    assertSymbols(
        "perl",
        "package Mock::Shop;\n# sub hidden {}\nsub total {\n  my $s = 'sub fake {}';\n}\n",
        "module:Mock::Shop@0",
        "function:total@2");
  }

  @Test
  void lua() {
    assertSymbols(
        "lua",
        "-- function hidden() end\n"
            + "local function helper(x) end\n"
            + "function M.Shop:add(item) end\n"
            + "M.remove = function(item) end\n"
            + "local s = \"function fake() end\"\n",
        "function:helper@1",
        "function:M.Shop:add@2",
        "function:M.remove@3");
  }

  @Test
  void groovy() {
    assertSymbols(
        "groovy",
        "// class Hidden {}\n"
            + "class Build {\n"
            + "  def run(args) {\n"
            + "  }\n"
            + "  String greet(String name) {\n"
            + "    return \"def fake() {}\"\n"
            + "  }\n"
            + "}\n"
            + "trait Named {}\n"
            + "interface Task {}\n"
            + "enum Level { LOW }\n",
        "class:Build@1",
        "method:run@2",
        "method:greet@4",
        "trait:Named@8",
        "interface:Task@9",
        "enum:Level@10");
  }

  @Test
  void scala() {
    assertSymbols(
        "scala",
        "// def hidden = 1\n"
            + "case class Point(x: Int)\n"
            + "object Main {\n"
            + "  def main(args: Array[String]): Unit = {\n"
            + "    val s = \"def fake = 1\"\n"
            + "  }\n"
            + "  def +(other: Point) = other\n"
            + "}\n"
            + "trait Shape\n"
            + "type Name = String\n"
            + "enum Color { case Red }\n",
        "class:Point@1",
        "class:Main@2",
        "method:main@3",
        "method:+@6",
        "trait:Shape@8",
        "type:Name@9",
        "enum:Color@10");
  }

  @Test
  void swift() {
    assertSymbols(
        "swift",
        "// func hidden() {}\n"
            + "protocol Drawable {}\n"
            + "struct Point {\n"
            + "  init(x: Int) { self.init() }\n"
            + "}\n"
            + "final class View {\n"
            + "  class func make() -> View { View() }\n"
            + "}\n"
            + "enum Suit {}\n"
            + "extension Point: Drawable {}\n"
            + "typealias Pair = (Int, Int)\n"
            + "actor Store {}\n"
            + "func main() { print(\"func fake() {}\") }\n",
        "interface:Drawable@1",
        "struct:Point@2",
        "method:init@3",
        "class:View@5",
        "function:make@6",
        "enum:Suit@8",
        "type:Point@9",
        "type:Pair@10",
        "class:Store@11",
        "function:main@12");
  }

  @Test
  void dart() {
    assertSymbols(
        "dart",
        "// void hidden() {}\n"
            + "class Counter {\n"
            + "  int get value => _value;\n"
            + "  Future<void> load() async {\n"
            + "    if (x) {\n"
            + "    } else {\n"
            + "    }\n"
            + "  }\n"
            + "  Counter.named() {}\n"
            + "}\n"
            + "mixin Logs {}\n"
            + "enum Mode { on }\n"
            + "extension Parse on String {}\n"
            + "typedef Callback = void Function();\n"
            + "void main() {\n"
            + "  print('void fake() {}');\n"
            + "}\n",
        "class:Counter@1",
        "function:value@2",
        "function:load@3",
        "function:Counter.named@8",
        "trait:Logs@10",
        "enum:Mode@11",
        "type:Parse@12",
        "type:Callback@13",
        "function:main@14");
  }

  @Test
  void r() {
    assertSymbols(
        "r",
        "# hidden <- function(x) x\n"
            + "add <- function(a, b) a + b\n"
            + "scale.data = function(x) {\n"
            + "  s <- \"fake <- function() 1\"\n"
            + "}\n",
        "function:add@1",
        "function:scale.data@2");
  }

  @Test
  void haskell() {
    assertSymbols(
        "haskell",
        "module Data.Shop where\n"
            + "-- hidden :: Int\n"
            + "data Item = Item Int\n"
            + "newtype Price = Price Int\n"
            + "type Name = String\n"
            + "class (Eq a) => Priced a where\n"
            + "total :: [Item] -> Int\n"
            + "total = sum . map price\n"
            + "{- other :: Int -}\n",
        "module:Data.Shop@0",
        "type:Item@2",
        "type:Price@3",
        "type:Name@4",
        "class:Priced@5",
        "function:total@6");
  }

  @Test
  void objectivec() {
    assertSymbols(
        "objectivec",
        "#define MOCK 1\n"
            + "@protocol Drawing;\n"
            + "@protocol Drawable\n"
            + "@end\n"
            + "@interface Shape : NSObject\n"
            + "- (void)draw;\n"
            + "@end\n"
            + "@implementation Shape\n"
            + "+ (instancetype)shapeWithSize:(int)size { return nil; }\n"
            + "@end\n"
            + "// int hidden(void) {\n"
            + "int main(int argc, char *argv[]) {\n"
            + "}\n",
        "macro:MOCK@0",
        "interface:Drawable@2",
        "class:Shape@4",
        "method:draw@5",
        "class:Shape@7",
        "method:shapeWithSize@8",
        "function:main@11");
  }

  @Test
  void vbnet() {
    assertSymbols(
        "vbnet",
        "Namespace Mock.App\n"
            + "  ' Sub Hidden()\n"
            + "  Public Class Order\n"
            + "    Public Property Total As Integer\n"
            + "    Public Shared Function Create() As Order\n"
            + "      Dim s = \"Sub Fake()\"\n"
            + "    End Function\n"
            + "    Private Sub Save()\n"
            + "    End Sub\n"
            + "  End Class\n"
            + "  Module Tools\n"
            + "  End Module\n"
            + "  Structure Point\n"
            + "  End Structure\n"
            + "  Interface IShape\n"
            + "  End Interface\n"
            + "  Enum Mode\n"
            + "  End Enum\n"
            + "End Namespace\n",
        "namespace:Mock.App@0",
        "class:Order@2",
        "property:Total@3",
        "function:Create@4",
        "function:Save@7",
        "module:Tools@10",
        "struct:Point@12",
        "interface:IShape@14",
        "enum:Mode@16");
  }

  @Test
  void dockerfile() {
    assertSymbols(
        "dockerfile",
        "# FROM x AS hidden\n"
            + "FROM --platform=linux/amd64 golang:1.22 AS build\n"
            + "RUN echo \"FROM y AS fake\"\n"
            + "from alpine as runtime\n"
            + "FROM scratch\n",
        "target:build@1",
        "target:runtime@3");
  }

  @Test
  void makefile() {
    assertSymbols(
        "makefile",
        "# hidden: x\n"
            + "CC := gcc\n"
            + "FLAGS = -a:b\n"
            + ".PHONY: all clean\n"
            + "all: build\n"
            + "\techo \"fake: x\"\n"
            + "%.o: %.c\n"
            + "clean::\n"
            + "define RECIPE\n"
            + "endef\n",
        "target:all@4",
        "target:%.o@6",
        "target:clean@7",
        "macro:RECIPE@8");
  }

  @Test
  void cmake() {
    assertSymbols(
        "cmake",
        "# function(hidden)\n"
            + "function(add_mock name)\n"
            + "endfunction()\n"
            + "MACRO(setup)\n"
            + "endmacro()\n"
            + "add_executable(app main.c)\n"
            + "add_library(core::util STATIC util.c)\n"
            + "message(\"function(fake)\")\n",
        "function:add_mock@1",
        "macro:setup@3",
        "target:app@5",
        "target:core::util@6");
  }

  @Test
  void toml() {
    assertSymbols(
        "toml",
        "# [hidden]\n"
            + "title = \"[fake]\"\n"
            + "[server]\n"
            + "port = 1\n"
            + "[[servers.backup]]\n",
        "section:server@2",
        "section:servers.backup@4");
  }

  @Test
  void diff() {
    assertSymbols(
        "diff",
        "diff --git a/src/Main.java b/src/Main.java\n"
            + "--- a/src/Main.java\n"
            + "+++ b/src/Main.java\n"
            + "@@ -1 +1 @@\n"
            + "-old\n"
            + "+new\n"
            + "+++ /dev/null\n",
        "section:src/Main.java@2",
        "section:/dev/null@6");
  }

  @Test
  void hcl() {
    assertSymbols(
        "hcl",
        "# resource \"x\" \"hidden\" {}\n"
            + "provider \"aws\" {\n"
            + "}\n"
            + "resource \"aws_instance\" \"web\" {\n"
            + "  ami = \"resource\"\n"
            + "}\n"
            + "data \"aws_ami\" \"base\" {}\n"
            + "variable \"region\" {}\n"
            + "output \"ip\" {}\n"
            + "module \"vpc\" {}\n",
        "section:aws@1",
        "section:web@3",
        "section:base@6",
        "variable:region@7",
        "property:ip@8",
        "module:vpc@9");
  }

  @Test
  void protobuf() {
    assertSymbols(
        "protobuf",
        "syntax = \"proto3\";\n"
            + "package mock.v1;\n"
            + "// message Hidden {}\n"
            + "message Order {\n"
            + "  enum State { NEW = 0; }\n"
            + "}\n"
            + "service Shop {\n"
            + "  rpc Place(Order) returns (Order);\n"
            + "}\n",
        "namespace:mock.v1@1",
        "type:Order@3",
        "enum:State@4",
        "interface:Shop@6",
        "method:Place@7");
  }

  @Test
  void graphql() {
    assertSymbols(
        "graphql",
        "# type Hidden {}\n"
            + "type Query {\n"
            + "  order(id: ID!): Order\n"
            + "}\n"
            + "extend type Order { note: String }\n"
            + "input OrderInput { id: ID }\n"
            + "interface Node { id: ID! }\n"
            + "enum State { NEW }\n"
            + "union Result = Order | Error\n"
            + "scalar Date\n"
            + "directive @auth on FIELD\n"
            + "query Recent { orders { id } }\n"
            + "fragment Fields on Order { id }\n",
        "type:Query@1",
        "type:Order@4",
        "type:OrderInput@5",
        "interface:Node@6",
        "enum:State@7",
        "type:Result@8",
        "type:Date@9",
        "macro:auth@10",
        "function:Recent@11",
        "function:Fields@12");
  }

  @Test
  void nginx() {
    assertSymbols(
        "nginx",
        "# location /hidden {}\n"
            + "upstream backend {\n"
            + "}\n"
            + "server {\n"
            + "  location = /health {\n"
            + "  }\n"
            + "  location ~* \\.png$ {\n"
            + "  }\n"
            + "}\n",
        "section:backend@1",
        "section:/health@4",
        "section:\\.png$@6");
  }
}
