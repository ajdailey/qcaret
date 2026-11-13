# Syntax highlighting

Grammar loading, detection, the Syntax menu and the background highlighter:
`GrammarLibrary`, `DocumentGrammars` and `SyntaxHighlighter` in
`app.controller` of [qcaret-app](../README.md). The grammar format, tokenizer
and engine are in [qcaret-core grammars](../../qcaret-core/docs/grammars.md).

## Grammar loading

`GrammarLibrary` reads the built-in grammars and the user's
`<settings>/grammars/*.xml` on a `qcaret-io-` worker at startup
(`loadGrammars`) and on View > Syntax > Reload Grammars. A user file that does
not parse, fails the schema or compiles badly, or is over 1 MiB, is skipped
with a WARN log line and one notice per read; a user grammar with a built-in's
id replaces it.

## Grammar choice

`DocumentGrammars` gives each document a `SyntaxHighlighter` and picks its
grammar on a `qcaret-cpu-` worker with `GrammarSet.detect` (file name,
extension, then the first 1,000 chars of the first line) when it opens, when a
save gives it another name and when the grammars are read again. View > Syntax
or a click on the status bar's language field sets one for the document
(`DocumentController.setSyntax`); that choice survives a reload while the
grammar exists. Each grammar switch, the reload included, passes the grammar to
every view's `EditorController.setGrammar`.

`CommandStates.syntaxMenu` builds the `SyntaxMenu` both lists show: the last
five grammars chosen with `setSyntax`, saved in `syntax.recent`
([Settings](settings.md)), Plain Text included, then
Plain Text, then one submenu per category that holds a grammar, in `byName`
order. A grammar's `category` picks the submenu (`programming` splits into A-M
and N-Z by the name's first letter, then `markup`, `build`); a grammar without
one goes in User.

## SyntaxHighlighter

`SyntaxHighlighter` keeps the painted tokens in a `TokenCache` on the UI thread
and runs a `SyntaxEngine` on the CPU pool; the UI thread runs no regex, so
opening a large file and typing never wait for highlighting.

- Each change, grammar switch, view range and request for missing lines goes to
  a queue. One task at a time drains it, runs the engine for at most 10 ms,
  posts the output and submits itself again while work remains.
- An output carries the switch generation and the revision the engine read. Its
  tokens apply only at the current revision; the lines the frontier tokenized
  are dropped from the cache at any revision, mapped through the `PositionMap`,
  so an edit the engine has seen leaves no stale entry.
- A change shifts the cache at once, so typing does not flash plain text. An
  output repaints the views only when it changes a line one of them shows.
- The fold regions of the last complete pass are the document's
  `FoldRegionSource` (`activeFoldRegions`) for that revision.
- Lines over `editor.maxHighlightLineChars` (10,000 by default) stay plain; the
  count goes to the log at INFO when a complete pass finds a new count.
