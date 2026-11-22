# Markdown

`MarkdownRenderer` and `RenderedMarkdown` in `core.markdown` of
[qcaret-core](../README.md). The preview controller and its threading are in
[qcaret-app documents](../../qcaret-app/docs/documents.md#markdown-preview).

## Parse and render

`MarkdownRenderer.render(text, folder, cancel)` parses the text once with
commonmark-java and its table, strikethrough, task list, autolink, footnote,
alert, YAML front matter and image attribute extensions, plus two QCaret block
parsers (`GitLabBlocks`): a `>>>` line pair is a block quote, and
`[[_TOC_]]` or `[TOC]` alone on a line is a table of contents. Parsing keeps
block source spans; LF, CR LF and CR breaks give the same render. The token is
checked before and after the parse and before every block; the parse itself
cannot be canceled. Rendering reads no file.

`parse(text, cancel)` returns the parse alone as a `MarkdownTree` (the
commonmark tree, its source and each heading's GitLab anchor), and
`render(tree, folder, cancel)` renders it, so another renderer walks the same
parse: the Browser preview's HTML is written from it in qcaret-app.
`MarkdownTree.MULTILINE_QUOTE` and `TOC` are the node classes of the two
GitLab blocks, for a renderer to register.

`core.markdown` uses no other core layer but `concurrent`
(`ArchitectureTest.coreLayers`), so code blocks carry their language and the
caller colors them.

## Rendered text

`RenderedMarkdown` is immutable. Its text is plain, lines separated by `\n`;
offsets are UTF-16 indexes. Sibling blocks are separated by one empty line
carrying the enclosing list and quote depth; items of a tight list are not.

| Source | Rendered |
|---|---|
| Heading | Its text under a `HEADING` run with the level |
| Paragraph | One line; a soft break is a space, a hard break or `<br>` starts a line |
| `**`, `*`, `~~`, code span, link, `<kbd>`, `<sub>`, `<sup>` | Text under the matching `InlineStyle` run; runs nest |
| Other inline HTML, HTML block | Source text; `<details>` lines dropped, `<summary>` text strong. A line break inside an inline HTML tag is a space, so the paragraph stays one line |
| List item | Text; bullet, number and task state are attributes of its first line |
| Block quote, `>>>` | Lines with `quoteDepth` raised |
| Alert (`> [!note]`) | A quote whose first line is the title, strong: the custom title or the kind |
| Rule | An empty line with `rule` |
| Fenced or indented code, front matter | One shaded line per source line; `CodeBlock.language` from the info string's first word, `yaml` for front matter |
| Table | One line per row, cells separated by a tab; tabs and breaks inside a cell become spaces |
| Image | U+FFFC for a local file with a path; else the alt text and the destination in parentheses |
| Footnote reference | Its number under a `SUP` run |
| Footnote definitions | After the last block, below a rule, as numbered items in reference order; unreferenced ones left out |
| `[[_TOC_]]` | A nested bullet list of the headings, each a link to its anchor |

## Records

| Accessor | Holds |
|---|---|
| `runs` | `StyleRun`s ordered by start |
| `listDepth`, `quoteDepth`, `marker`, `number`, `task`, `code`, `rule` | Per-line block attributes |
| `blocks` | Every leaf block's kind and line range, in order |
| `codeBlocks`, `frontMatter` | Code line ranges and languages |
| `tables` | Rows, cell ranges and column alignment; the header row first |
| `images`, `links` | Ranges and a classified `LinkTarget`; images also the alt text, width and height |
| `headings` | Level, text, anchor id, source line and rendered line of each ATX and setext heading |
| `alerts`, `footnotes`, `footnoteReferences` | Alert kind and lines; footnote numbers and lines |

A paginator walks `blocks` in order and keeps a block's lines together where
its kind needs it (a table row, a code line, a heading with the next block);
an image's line is the line holding `MarkdownImage.start`.

## Link targets

`LinkTarget.of` classifies a destination without touching the file system:
`#name` is an anchor (percent-decoded); a scheme of two or more chars or a
leading `//` is a URL, except `file:`, which is a local file with the URI's
path; anything else is a local path with the query and fragment dropped,
percent-decoded and resolved against the document's folder. A relative path in
an untitled document, and a path the file system rejects, has a null path.

## Heading anchors

`HeadingAnchors` assigns every heading's id in document order before rendering,
so a table of contents links to headings below it. The id follows GitLab: the
text without HTML, lower case; letters, digits, `_` and `-` kept; spaces to
`-`; other chars dropped; a run of `-` joined; a repeat gets `-1`, `-2`.
commonmark's `heading-anchor` extension keeps `-` runs (`a  b` gives `a--b`),
so it is not a dependency.

## Source map

`sourceLine(rendered)` returns the source line a rendered line came from and
never decreases down the render. `renderedLine(source)` binary-searches for the
first rendered line of that source line, or the last one before it for a
source line with no output, such as a blank line, a fence or a link reference
definition.
