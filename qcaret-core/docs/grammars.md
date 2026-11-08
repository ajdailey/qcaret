# Grammars and highlighting

The grammar format, the compiler, the tokenizer and the highlighting engine in
`core.syntax` of [qcaret-core](../README.md). Loading, detection timing and the
UI-side highlighter are in [qcaret-app
highlighting](../../qcaret-app/docs/highlighting.md).

## Grammar files

A grammar is an XML file in QCaret's own format, validated against
`grammar.xsd` (beside the classes) while the JDK parser reads it, with DOCTYPE
declarations refused and external DTD and schema access off (`GrammarReader`).
Each regex compiles at read time and each context a rule names must exist; a
failure is an `InvalidGrammarException` naming the file and, for a schema
error, the line. User grammars use the same format, in `grammars/*.xml` in the
settings directory.

| Element | Content |
|---|---|
| `grammar` | `id` (a user grammar with the same id replaces the built-in), `name`, `version`, optional `category` (`programming`, `markup` or `build`: the Syntax menu submenu; without it a user grammar goes in User) |
| `detect` | `extension`s, `fileName`s, one `firstLine` regex |
| `comments` | `line`, `blockStart`, `blockEnd` |
| `brackets`, `autoClose` | `pair open close` |
| `indent` | `increase`, `decrease` and `dedentNext` regexes |
| `folding` | `mode`: `none`, `brackets` (`open` and `close` chars), `markers` (`begin` and `end` regexes) or `indent` |
| `symbols` | Up to 64 `symbol` rules for Go to Symbol: `regex`, `kind`, optional `group` ([Symbol rules](#symbol-rules)) |
| `context` | Rules in order: `match` (regex, token, optional `push` and `pop`), `keywords` (words, `ignoreCase`, `wordChars`), `span` (`begin`, optional `end`, `escape`, inner `context`, `multiLine`, `nested`, `bodyNextLine`), `include` (a context's rules in place), `embed` (another grammar between `begin` and `end`) |

Tokens are `TokenType`: comment, string, escape, keyword, keyword2, type,
number, constant, operator, delimiter, function, preprocessor, tag, attribute,
heading, emphasis, link, invalid; text no rule covers is plain.

## Built-in grammars

`GrammarSet.BUILT_IN` lists the 47 built-in grammars (`grammars/<id>.xml`),
written from the language or format specifications each file names.

| Kind | Grammars |
|---|---|
| Languages | Plain text, Java, XML, HTML, JSON, YAML, SQL, Python, JavaScript, TypeScript, C, C++, shell, PowerShell, batch, properties, INI, CSS, Markdown, Go, Rust, C#, Kotlin, Ruby, PHP, Perl, Lua, Groovy (with `Jenkinsfile` and `*.gradle`), Scala, Swift, Dart, R, Haskell, Objective-C, VB.NET |
| Formats | Dockerfile, Makefile, CMake, TOML, diff, CSV, TSV, log files, HCL, Protobuf, GraphQL, nginx configuration |

Each extension belongs to one built-in grammar (`GrammarSetTest`): `.h` is C,
`.m` and `.mm` are Objective-C, `.kts` (so `build.gradle.kts`) is Kotlin. PHP
text outside `<?php ... ?>` is HTML.

The format grammars use the token set as follows:

| Grammar | Tokens |
|---|---|
| `csv`, `tsv` | Columns cycle through `keyword2`, `type`, `number`, `preprocessor`, `string`: a separator match pops the column context and pushes the next, and a zero-width `$` match pops it at the line's end, so a CSV quoted field keeps its column across a line break |
| `log` | Levels: FATAL and ERROR `invalid`, WARN `escape`, INFO `type`, DEBUG `keyword2`, TRACE `comment`, as upper-case words, logfmt `level=` and JSON `"level":` values, and one-letter forms (`[E]`, Android `E/Tag`, glog `E0102`). Timestamps `number`, bracketed thread names `delimiter`, `package.Class` logger names `function`, exception names and `Caused by:` `keyword`, `at` and `... N more` lines `comment`, URLs `link`, keys of `key=value` `attribute` |
| `diff` | File headers `heading`, hunk headers `keyword`, added lines `type`, removed lines `escape`, context-format changed lines `constant`; inside a unified hunk a `---` line is a removed line, and a line starting with any other char ends the hunk |

## Symbol rules

`SymbolScanner` finds a document's definitions with the grammar's `symbol`
rules. A rule's `regex` is matched against each line without its break; `kind`
is a `SymbolKind` (function, method, class, interface, struct, enum, trait,
type, module, namespace, macro, constant, variable, property, section, target,
label, selector, table, view); `group` is the name's group by number or name,
by default the group named `name`, else group 1. `GrammarReader` refuses a rule
whose group does not exist. A symbol regex compiles alone, so it may use named
groups.

- Every line is tokenized from the first, and a match counts only when its
  first non-blank char lies outside comment, string and escape tokens. The name
  may lie in a string, as HCL's quoted labels do.
- Each rule finds every match on a line. At one column the first rule wins, and
  a line's symbols are in column order.
- Each rule run reads the line through a `BoundedChars` (`8 * length + 65,536`
  reads); reaching the limit ends that rule on that line.
- The caller sets the line limit and the count: Go to Symbol skips lines over
  10,000 chars, keeping the tokenizer's state, and stops at 10,000 symbols. A
  name is cut at 200 chars.
- A rule sees one line. A definition whose name follows its keyword on a later
  line is not found, nor is a Markdown setext heading. C, C++ and Objective-C
  functions are found when defined at column 0 with the name and `(` on one
  line; Java, C#, Groovy, Dart and JavaScript methods only at a line's start.

Plain text, XML, HTML, JSON, CSV, TSV and log files have no symbol rules.

## Compilation

`Syntax.compile` turns each context into a regex alternating its rules; the
token at a position is the first rule matching there. A backreference or named
group is refused: the alternation renumbers groups.

Each rule's possible first chars come from matching its regex, without a
leading lookbehind or `\b`, against each ASCII char (a match or a hit of the
input's end means it may start); a rule whose leading negative lookbehind
matches every ASCII letter, digit and `_` cannot start after one. Each context
keeps one alternation per distinct set of rules a position may start, so the
tokenizer tries only those, none inside most identifiers, and every rule at a
non-ASCII char.

A span becomes a context of its own (escape, end, inner rules); a span without
`end`, or not `multiLine`, closes at the line's end. Inside an embedded grammar
the embed's `end` is tried first in every context, so HTML's `</script>` ends
JavaScript even inside a string. A zero-width rule that only switches contexts
may repeat 16 times at one position before a char is skipped.

### Nested spans and captured ends

In a `nested` span the `begin` opens one more level, closed by its own `end`
(Rust and Haskell block comments). A `\1` in a span's `end` or `escape` is the
text the `begin`'s first group matched: Lua's `\[(=*)\[` closes only at `]`
with as many `=` and `]`, a Ruby here document only at its own identifier. Such
a span compiles to a template, and `Syntax.derive` adds one context per
captured text on first use: at most 512 per grammar, each for a text of at most
64 chars without a backslash. Past those limits the `begin` colors itself and
opens nothing. A `nested` span cannot also be `bodyNextLine`.

### Here documents

A `bodyNextLine` span's body starts at the next line, so a here document's
opening line keeps tokenizing as code after the marker. The `begin` pushes a
pending context (`Syntax.pending`); until the line's end the rules of the
context below it apply, and a `pop` or span end removes that context and keeps
the pending one. At the line's end `Tokenizer` turns each pending context into
its span's body, the first marker's body on top, so the bodies of `cat <<A <<B`
follow in order; single-line contexts below a pending one close there too.
Ruby, Perl, PHP, shell, Dockerfile and HCL here documents use it with a `\1`
end.

## Tokenizer

`Tokenizer` tokenizes one line from a start state to an end state; a state is
an interned context stack of at most 256 contexts. A deeper push is skipped, so
unclosed nested comments do not grow the states without bound. A line over the
limit (10,000 chars by default) gets no tokens and keeps its state. Each search
reads the line through a `BoundedChars` that checks the `CancellationToken` and
ends the search after `4 * (rules + 2) * remaining chars + 65,536` reads; that,
or a `StackOverflowError`, leaves the rest of the line plain.

## SyntaxEngine

`SyntaxEngine` highlights one document on one thread at a time:

- It stores each line's start state and fold counts, 4 bytes each. `edit`
  shifts both by each edit's line range widened by one line on each side (a CR
  and an LF that join or part stay inside it) and moves the frontier back.
- It copies up to 256 lines, at most 256 Ki chars, in one read: a `LineView`
  locates its line from the start of its leaf, so line by line reads scan each
  leaf once per line.
- `run` returns the `request`ed lines first, then tokenizes from the frontier
  until a deadline, the end, or the frontier passing the last view line it had
  not reached. After the last edited line, and after a stopped frontier that an
  edit moved back, a line ending in the state its successor already holds ends
  the work: the edit has converged.
- The frontier returns the lines of each view range (`setViewport`) as it
  passes them; a requested line beyond it is tokenized from a stored state, or
  from the initial state, and returned again when the frontier passes.
- At the end it pairs the fold counts into regions: per line the closing and
  opening marks left after pairing within the line, outside comments and
  strings; one region per start line, the largest, spanning two lines or more.
  It also counts the lines over the limit.

## Grammar set and detection

`GrammarSet` holds the compiled grammars: user grammars replace built-in ones
by id, and a user grammar that does not compile is left out with a message.
`detect` takes a grammar naming the file name, then the extension (any case),
then a first-line regex over the first 1,000 chars, read through
`BoundedChars`; user grammars come first, then plain text.
