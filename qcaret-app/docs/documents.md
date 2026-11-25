# Documents

Opening, loading, saving, encodings and printing of one window's documents:
`DocumentController`, `DocumentOpener`, `SaveAndReload` and `PrintController` in
`app.controller` of [qcaret-app](../README.md).

`DocumentController` holds the window's documents, one tab each, as
package-private `OpenDocument` entries: the document, its `OpenFile` file link,
editors, tab, grammar and declared settings. `DocumentSettings` (per-file
settings), `DocumentsUnder` (workspace browser Rename and Delete),
`DocumentGrammars` (grammar detection and choice), `DocumentEncodings` (Save
with Encoding, line-ending conversion), `TabClosing` (close order and the
unsaved prompt) and `DocumentSessions` (session tabs, remembered file state,
the closed-file list) take the entries directly. An entry reaches its window only through its constructor
arguments, the window's `MainView` and selected entry. `add` gives
every document `editor.tabWidth` and `editor.insertSpaces`; a remembered file
state or a session tab then replaces them, then the file's declared settings
([Per-file settings](#per-file-settings)), and a settings change leaves open
documents alone. Loads, saves and conversions each count as one view activity
until their worker ends.

## Opening

New opens an untitled document ("Untitled N") in `editor.newEncoding` with
`editor.newLineEnding`, which `ApplicationController` passes to
`DocumentController.setNewFile`. The same pair covers the untitled document
that replaces a closed last tab, a new window's and a start's untitled
document, a missing file opened by path, a file of 0 bytes
(`LoadOptions.emptyCharset`) and the ending of a loaded text without line
breaks (`LoadOptions.empty`); a reload reads the pair current at its start.
Open goes through `DocumentOpener`:

- A missing file opens an empty document for its path; its `.editorconfig`
  settings then apply over the pair.
- A file already open selects its tab. The match is by real path, resolved on
  the opener's worker and looked up (`find`) on the UI thread before the file
  is read, so a file reached again through a link or a short name selects its
  tab unread.
- A document whose text does not encode back to the file's bytes opens
  read-only with a notice (`bytesGuarded`), and Save refuses it with a notice
  while it stays read-only. Save As writes a converted copy, and clearing
  Read-Only lets Save convert the file.
- A binary file opens only after the user accepts the prompt, as a read-only
  ISO-8859-1 view whose tab and status bar say so. Save and Save As refuse it
  in the controller, whichever command path calls them, and the editor refuses
  its edits; the view stays read-only.

### Load progress and cancel

A load still reading after `DocumentOpener.PROGRESS_DELAY_MILLIS` (300 ms)
shows through `DocumentsView.showLoadProgress`: the file's name, or the count
when several run, and the percent of their bytes read. The delay is one
`timerExec`; after it, each `LoadReservation` progress call posts one update at
a time, and only a changed percent reaches the view.

A document of `DocumentOpener.SLOW_PANE_LINES` (500,000) lines or more reaches
`Listener.opened` one UI turn later: the first turn starts an
`ActivityIndicator` operation (the busy cursor) and calls
`Progress.paneStarting`, which `DocumentController` shows as the
`LOAD_PREPARING` notice, so both paint before the editor's build holds the UI
thread (`StyledText.setContent`, 963 ms for 3,000,001 lines on a Windows
host). `paneBuilt`, which clears the notice, and the operation's end follow the
call; a close between the turns drops the document and releases its
reservation.

`cancelLoads` cancels every load past `resolved`, hides the row at once and
clears the list, so a load started next gets its own delay. A canceled load
ends in `Listener.canceled` on the UI thread, whichever it reached: the
worker's check, or a finished read still in the queue, which drops the document
and releases the reservation. A session tab counts as done and reports no
failure, so it leaves the next saved session. An open still resolving its path
is not canceled.

### One Open per load

Each open is one `DocumentController.Open`, the listener of that open alone. It
holds the open's callback, restore, forced charset, the path the user gave and
what a reopen replaces, so two loads of one file share nothing and each load
end drops what its open held.

- Every end runs `finishRestore`, because a restore whose `done` never runs
  holds back `recovery.start` and the session save. An opener closed with a
  restore running ends in no call, which happens only at quit, since
  `WindowSet.requestClose` refuses a window's close during a restore.
- The retry without a remembered encoding reuses the object.
- Two opens of one file with callbacks each run theirs at their load's end,
  except that an older open ending after a newer one reached the tab drops its
  callback (`OpenDocument.landed`), so the caret stays at the last place asked
  for.

### DocumentOpener

`DocumentOpener` opens files for one window on `qcaret-io-` workers. Each
`open` takes its own `Listener`, which gets one call ending the open, or none
once the opener is closed or the workers refuse the task; `Progress` is the
window's.

1. A worker resolves the path (`SafePaths.userFile`) and posts it to the UI
   thread, where `Listener.resolved` ends the open unread for an open document.
2. Otherwise a second worker estimates the load (`DocumentFiles.estimate`) and
   reserves it from the `MemoryBudget`. A load the budget cannot hold is
   refused unread; a load whose text outgrows the estimate past the budget is
   refused while reading. The notice names the size, the memory needed and a
   `-Xmx` value.
3. A loaded document arrives with its rows (`DisplayProjection.layout`) and its
   reservation lowered to the retained cost.
4. `UiPacer` hands loaded documents to `Listener.opened` in arrival order, at
   most `OPENED_PER_TURN` (4) per UI task, and posts the rest behind whatever
   was queued meanwhile. Each call builds a tab (about 20 ms on Linux, mostly
   `StyledText` creation); 100 handed files queued back to back held every
   other UI task 2.1-2.4 s on Linux and 1.6 s on macOS, and
   with the pacer 57-94 ms on Linux (`OpenTabsProbe`). `running` counts the
   documents still waiting, so `loading() == 0` means every tab exists. A
   document whose open was closed or canceled while waiting is dropped and its
   reservation released.

An open waiting for the UI thread is ended by `close` too, so a task the
dispatcher drops still ends its activity. `close` sets a flag and cancels the
running loads; a load that finishes after it drops its document and releases
its reservation, whether the worker checks the flag before posting or the
posted task checks it on the UI thread, so no document reaches a closed window.
The token `open` returns cancels that open alone, at any step. An unchecked
failure reports `Listener.failed` from a `finally` at the step it hit, so the
progress row and a restoring tab end; a load's `Throwable` goes on to the
worker's handler. `RecoveryController.readSnapshot` ends a tab's restore the
same way.

## Encodings and line endings

`EncodingChoices` lists `TextEncoding.MENU` first, then every other charset.
`Charset.availableCharsets` costs 130 to 229 ms cold (measured in QLogg), so
`ApplicationController.loadEncodings` computes the list once per run on a
worker.

- Reopen with Encoding loads a clean document's file again with the chosen
  charset and replaces the document in its tab once loaded. A failed or refused
  load shows a notice and leaves it open, and so does a missing file: a reopen
  adds no tab. `OpenDocument.reopen` holds the running `Open`, with the
  document, its revision and the load's token: closing the tab cancels the
  load, and an edit or a Save As during the load keeps the document and drops
  the load. A plain open of the file meanwhile selects the tab unread. One
  reopen of a document runs at a time. The load replaces its own tab, not
  another tab on the same path, and keeps the path the file was opened by, so
  a file opened through a link keeps the link.
- `reopenWithDetection` does the same with detection, past a remembered
  charset, and leaves the document's `forced` charset null, so the file state
  and the session hold none.
- Save with Encoding changes only what the next save writes and makes the
  document dirty; the byte order mark is dropped for another charset.
- A line-ending conversion builds the new text on a `qcaret-cpu-` worker
  (`LineConversion`) and applies it as one whole-text replacement and one undo
  step, then makes the ending the one Enter inserts. A result whose document
  changed or closed meanwhile is dropped. Mixed endings are otherwise kept.

## Per-file settings

`FileSettingsReader` reads what a file declares in `.editorconfig` files and
modelines ([Per-file
settings](../../qcaret-core/docs/files-and-encodings.md#per-file-settings)),
from the sources `editor.editorConfig` and `editor.modelines` turn on;
`ApplicationController` turns it on with `setFileSettings`. Each
`.editorconfig` that contributes nothing logs one WARN per read.

On open, `DocumentOpener`'s `qcaret-io-` worker resolves the EditorConfig
properties before the load, passes their charset as `LoadOptions.configured`,
and reads the modelines from the loaded text; `LoadedFile.settings` carries the
result to the UI thread.

| Setting | Order, first wins |
|---|---|
| Charset | A charset the user forced (Reopen with Encoding, `-e`, Save with Encoding, the one remembered for the file or the session tab); a BOM; a modeline or in-file declaration; EditorConfig; detection |
| Width, spaces or tabs | Modeline; EditorConfig; the remembered file state or session tab; `editor.tabWidth` and `editor.insertSpaces` |
| Ending Enter inserts | Modeline or EditorConfig, only while the text has no line break; the most frequent ending; LF |

`end_of_line` converts no existing ending, so a CR LF file in an `lf` project
stays CR LF and Enter inserts CR LF. A file that does not exist yet takes the
declared charset, ending and indentation once a worker has read them, while it
is empty and clean.

The tab's tool tip adds a line naming the `.editorconfig` files and the
modeline kind when any contributed.

| Event | Settings read again | What changes |
|---|---|---|
| Save As to another path | For the target, on the worker that resolves its real path | Width, spaces, Enter's ending, trim and final newline, tool tip, applied once the write succeeds; the encoding written stays |
| Reload, external change | For the file, on the reload worker | The same, from the reloaded text; the EditorConfig charset joins detection |
| Rename from the workspace browser | For the new path, on a `qcaret-io-` worker; dropped when the document moved again meanwhile | As Save As, applied when the result arrives |
| Reopen with Encoding | As an open | Everything an open sets; the chosen charset stays forced |

### Save cleanup

With `trim_trailing_whitespace = true` or `insert_final_newline = true`, a save
first computes `SaveCleanup.edits` from a snapshot on the bulk pool:
spaces and tabs at each line's end removed, then the document's line ending
appended to a text that does not end with a break. The UI thread applies the
edit as one undo step and the write takes the snapshot after it, so the bytes
written are the text shown. An edit made while the cleanup ran drops it and
computes it again, up to 3 times; then the write goes on without it. Esc or a
close during the cleanup ends the save unwritten. `false`, a missing property
and a read-only document leave the text alone. Blanks whose removal would join a
CR and an LF stay, and the editor's notice counts their lines.

## Saving

`DocumentController` routes Save, Save As, Save All, the quit saves and reload
to `SaveAndReload`, one per window, which hands reload to `FileReload`.
`SaveAndReload` calls `DocumentSettings.prepareSave` for the save cleanup and
`DocumentsUnder` for the rename hold.

Save writes a `DocumentSnapshot` through `DocumentFiles.save` on a `qcaret-io-`
worker while editing goes on, then calls `markSaved`.

- An untitled document asks for a file first. A save while one runs for the
  same document shows a notice instead.
- Esc in the editor and closing the tab cancel a running save. A cancel before
  the move leaves the target unchanged; an in-place write that has begun runs
  to its end and reports saved. The next save of the file waits for it
  (`OpenFile.writing`) and its result follows that write's; a conflict whose
  stamp is the one that write recorded writes again without asking.
- A file without write permission fails with `SAVE_FAILED`.
- Save, Save As, Save a Copy As, Export as HTML and the checksum list's Save
  As check the target's text with `PathFormText.check` (`SafePaths.checkForm`)
  on their worker before any disk access. A rejected path, such as a Windows
  device name, shows the save or export failure with its `PathFormText`
  reason; the log keeps the exception's message.
- Each running Save, Save As, Save a Copy As and checksum list Save As lists
  its target in `RunningWrites` until its result reaches the UI thread; a
  workspace Rename or Delete of a folder holding one refuses.
- Save writes through the path the user opened, so a symbolic link is followed
  at each save.
- A target changed on disk since the load, the last save or the last reload is
  not written; the save conflict prompt offers Overwrite, Reload, Compare and
  Cancel (see [External changes](external-changes.md)). Save As keeps that
  check when the target's real path, resolved on the save worker, is the
  document's file, so Save As through a link to it is checked too.
- Save As onto a file another tab holds, in any window (by real path, resolved
  on a `qcaret-io-` worker), closes that tab once the write succeeds. When that
  tab has unsaved changes it asks first, and a decline writes nothing. An edit
  in that tab after the question asks Save, Don't Save or Cancel for it once
  the write ends; Cancel leaves two tabs on the file, and the external-change
  check applies to the edited one.

### Save a Copy As

`SaveCopy` writes the document's snapshot through `DocumentFiles.save` on a
`qcaret-io-` worker, with no baseline. The copy takes the document's encoding,
BOM and line breaks; when the document's declared settings ask for the save
cleanup, `SaveCleanup.edits` is applied to a `RopeStorage.from` copy of the
snapshot, so the document's text, undo history and dirty state are untouched.
The document keeps its path, file check and session entry, and the copy is not
opened.

- A target whose real path is the document's file runs Save, and so does a
  hard link of it (`Files.isSameFile`, on the resolving worker): a copy would
  write the document's own inode in place, outside `OpenFile.writing` and with
  its file check running.
- A written copy is checked at once in the tab holding that file, in any
  window, which takes it as an external change, and goes to the top of Recent
  Files.
- A failed write leaves the target unchanged and shows `SAVE_FAILED` with the
  target's name. Only Esc cancels the write (`SaveCopy.cancel`); a second copy
  of the same document while one runs shows a notice.
- Closing the tab, Don't Save and closing the window leave the write running,
  so a copy followed by Don't Save keeps the edits in the copy. The result
  shows in the tab's window while it is open, otherwise in the active window.
  The tab does not move to another window while the copy runs
  (`OpenFile.inFlight`).
- Quit waits for every running copy, the snapshot quit included
  (`RunningCopies`). A failed copy stops the quit, as a failed save does.
  After `RunningCopies.BOUND` (30 s) a modal prompt in the active window
  (`MainView.askUnfinishedCopies`) names each copy still running. Quit goes on
  and logs the copies at WARN; they may be incomplete. Wait, Esc and the close
  button wait another bound. A copy ending while the prompt shows closes it and
  the quit goes on, or stops on a failure, as before.

Close asks Save, Don't Save or Cancel for a dirty document. Save closes once
the save succeeds, and an edit made during the write asks again, with the tab
and its recovery file kept. A closed document disposes its editor and releases
its heap reservation.

## Other document commands

- Save All saves every dirty document in tab order.
- Reopen Closed Tab opens the file of the last of up to 20 closed tabs again.
- Open All in Recent Files and Recently Closed (`OpenAll`) checks the listed
  files on a `qcaret-io-` worker and passes the readable regular files, in list
  order, to the open, which selects a file already open. The others are
  named in one status notice and get no tab, where a single open of a missing
  file opens an empty tab for it. Recently Closed drops each file once its tab
  shows (`open(Path, Consumer)`), so a skipped file and one whose load fails or
  is canceled stay listed.
- Open Path at Caret (`OpenPathAtCaret`) reads the primary caret's line, at
  most `REACH` (4,096) chars each side of the caret, on a `qcaret-io-` worker
  under the window's busy cursor. `PathAtCaret` finds the path and its
  `:line:column` ([rules](../../qcaret-core/docs/documents.md#path-at-the-caret));
  a relative path resolves against the document's folder, or
  `WorkspaceController.root` for an untitled document, and must name a regular
  file. A path naming no file is split by `HeadingPath.resolve` into a file
  and a Markdown heading. The file opens through `open(Path, Consumer)`, so an
  open file selects its tab and a new one goes through `DocumentOpener`; the
  caret then goes to the line and visual column, or the heading
  ([Heading jumps](#heading-jumps)). No path, a URL, no base folder or no
  regular file shows a notice instead. A path whose text
  `SafePaths.checkForm` rejects, such as a Windows device name, shows
  `OPEN_FAILED` with its `PathFormText` reason before any disk check. A newer
  run drops an older result.
- Read-Only, the tab width and indentation with spaces are per document.
- Go to Line asks for `line`, `line:column` or `line,column`, from 1, and
  passes `parsePosition` to the view as the validator that enables OK; other
  input reaching the answer shows a notice.
- A failed open shows `OPEN_FAILED` with the file name and a reason from
  `OpenFailure`: fixed text for `AccessDeniedException` and
  `NoSuchFileException`, whose messages are the path alone, and for a
  `PathFormException`, checked first through `PathFormText` (`Cannot open CON:
  the path names a Windows device`; the log keeps `Path names a Windows
  device`), the reason of another
  `FileSystemException`, else the message.
- Word wrap, Show Whitespace, Show Line Endings and the completion trigger are
  settings for the window's editors.
- Each document has one `Bookmarks`, shared by its views and disposed on close.
- `WordCompletion` keeps one `WordIndex` per open document and builds it again
  on a `qcaret-cpu-` worker when the document's revision moved since the last
  build. `documentClosed` drops the index when the document closes or moves to
  another window.

### Split views

Split shows the selected document in a second view, side by side or one above
the other; with two views, the other arrangement only rearranges them. Each
view is an `EditorController` with its own `SelectionModel`,
`DisplayProjection` (copied from the focused view's rows) and scroll position,
so view state lives per view, not per tab. The focused view is the one menu and
find commands act on. Unsplit closes every view but the focused one; closing
the tab disposes every view.

### Markdown preview

Each tab has a `MarkdownPreview`, created by `MarkdownPreviews` on first use,
holding its view mode (`MarkdownViewMode`: Source, Split, Preview) and its
renders. `DocumentController.setMarkdownMode` and `markdownMode` act on the
selected tab, and `markdownPreview` hands its pane the `MarkdownPreviewInput`.
A tab takes Split or Preview only while its grammar is `markdown`;
`DocumentGrammars` reports each grammar change, and a change away returns the
tab to Source. A tab moved to another window keeps its mode; closing or moving
disposes the preview. `MarkdownPreviews.of` attaches the tab's pane
(`TabView.markdownView`) when it creates the preview.

The session tab and the per-file state keep the mode as `markdown` (`source`,
`split`, `preview`) only once the user picks one: `setMarkdownMode`, which the
toolbar, View > Markdown View and Cycle Markdown View run, sets
`OpenDocument.markdownPicked`, and restoring a kept pick sets it again. A tab
with no pick keeps an empty value, and its next open or session restore takes
`markdown.openMode` (`MarkdownPreviews.opened`): at once when its grammar is
already `markdown`, else when detection applies `markdown`
(`DocumentGrammars.setMarkdownOpening`). `MarkdownPreviews.restore` applies a
kept pick
through `MarkdownRestore` once the grammar is known: at once for a Markdown
tab, after the detection that runs for a just-opened file, and not at all when
the grammar is another.
`MarkdownCommands` runs View > Markdown View and Cycle Markdown View (Source,
Split, Preview, then Source; nothing on another tab) and gives the status bar
the renderer's name while a preview shows.

| Trigger | Render |
|---|---|
| Source to Split or Preview | At once, on a `qcaret-cpu-` worker under the busy cursor (`BusyWork`) |
| An edit while not in Source | Cancels the running render; 300 ms after the last edit, on a worker without the busy cursor |
| `renderRequested` from the notice | At once, under the busy cursor, at any size |
| A reload of the grammars while not in Source | As an edit does, without the busy cursor |
| A renderer change (`rendererChanged`) or a Browser failure (`browserFailed`) while not in Source | At once, under the busy cursor |

A document over `LIVE_LIMIT_CHARS` (1 MiB of chars) is not rendered on a switch
or an edit: the pane shows the size notice instead. A render
(`MarkdownRenderJob`) parses once with `MarkdownRenderer.parse`, renders the
tree, then colors each code block with a `SyntaxEngine` for the grammar
`GrammarSet.forLabel` finds for its language (`MarkdownCodeTokens`), on the
same worker, and flattens the style runs and tokens into `MarkdownSpans`; the
pane receives all three as `MarkdownPreviewContent`, with the local images to
decode. While `markdown.previewRenderer` is Browser and
`MainView.previewBrowserUnavailable` gives no reason, the render also writes
the tree as HTML (`MarkdownHtmlWriter`, below), wraps it in its page
(`MarkdownPage.page`, held as `MarkdownHtml.page`) in `MarkdownPreviews`'
palette and zoom, and requests no image
decode; the pane only calls `setText`. Each render carries a generation, raised
by every edit, switch, grammar reload and new render, and the revision it read;
a result whose generation or revision is not current is dropped.

A palette change reaches `MarkdownPreviews.setPalette` through
`DocumentController.setMarkdownPalette`, from the `ThemeController` listener
`ApplicationController` registers. `MarkdownRepage` rebuilds the shown Browser
page on a `qcaret-cpu-` worker without the busy cursor; a page whose request was
overtaken by a later palette or zoom change or render, or whose content no
longer shows, is dropped. A render started in a palette or zoom a change
replaced shows only after its page is rebuilt. A change of `markdown.previewZoom` reaches
`MarkdownPreviews.setZoom` through `DocumentController.setMarkdownZoom`; each
`MarkdownPreview.zoomChanged` passes the size to its pane
(`MarkdownPreviewView.setZoom`) and rebuilds a shown Browser page the same way
([Markdown preview](../../qcaret-ui-swt/docs/markdown-preview.md#zoom)). A render over the limit
reserves 24 bytes per char from the `MemoryBudget` through `MarkdownCharge`
(assumed, not measured) until a later render replaces it or the tab closes; a
refused reservation renders nothing and posts `MARKDOWN_NO_MEMORY`.

`MarkdownImages` decodes the shown render's local images on a `qcaret-io-`
worker, one after another, each posted as it arrives; the next render, a switch
to Source and a close cancel the rest. The size comes from the image
attributes (`120`, `120px`, `50%` of the pane's width); an SVG, a file over
16 MiB and an unreadable one post a placeholder. Decoded images stay cached per
window up to 64 MiB, keyed by path, size, modification time, requested size and
pane width, so a live render decodes nothing again.

In Split, the focused editor's viewport report calls `sourceScrolled`, and
`sourceScrolled` and `previewScrolled` map the top line through the shown
render's source map and scroll the other side. The scroll a mapping causes is
not mapped back: `Tab.scrollSource` returns the editor's top line after the
scroll, which the document's end can hold above the line asked for, and the
report of that line is ignored. With `markdown.syncScroll` off
(`MarkdownPreviews.setSyncScroll`, `Tab.syncScroll`), neither call scrolls the
other side; `revealSource`, a find match and a link still scroll the preview.
`MarkdownLinks` follows links. A click
(`clicked`) on Basic text follows its link: an anchor scrolls the preview to
its heading, a local file opens in the window at the heading its fragment
names (`HeadingJumps.openLink`), an `http`, `https`, `mailto` or
`ftp` URL goes to `MainView.askOpenUrl`, whose Open runs `MainView.launchUrl`
and whose Cancel opens nothing, and any other URL, such as
`javascript:` or `smb:`, is dropped; a footnote reference scrolls to its
footnote. A Browser navigation (`linkFollowed`) is followed by its location:
`about:` with a fragment calls `scrollToAnchor`, a `file:` or `qcaret-file:`
URL opens in the window, a URL of those four schemes goes to `askOpenUrl`, and
anything else is dropped. The pane (`MarkdownPreviewView`) only
applies these requests.

Find, Replace and HyperSearch act on the tab's document and editor in every
mode. In Preview, `FindController` (Find Next, Find Previous, the incremental
find, and the match after a Replace) and `ResultsNavigation` (an opened
HyperSearch result) pass the selected match to
`DocumentController.revealMarkdownMatch`, which calls the document's
`MarkdownPreview.revealMatch` in the window holding it. `PreviewMatch` maps
the match: the preview scrolls to the rendered line of its source line, as
`revealSource` does, and Basic content also selects the rendered text
(`MarkdownPreviewView.selectRendered`) when the match's text occurs once in its
block's source and once in the block's rendered text, outside a table, with
both texts at most 100,000 chars. The render has no source offsets, so a match in
markup that does not render, or whose text repeats in its block, only
scrolls. `HeldMatch` keeps a match found in a revision the shown render does
not hold, as after a Replace, until the render of that revision shows; an edit
before then drops it. Split keeps its scroll mapping and Source does nothing.

#### Browser HTML and Export as HTML

`MarkdownHtmlWriter` writes a `MarkdownTree` with commonmark's `HtmlRenderer`
and the HTML renderers of the table, strikethrough, task list, footnote, alert
and image attribute extensions; commonmark's URL sanitizer drops
`javascript:` and similar link targets, the link then has no `href`, and raw
HTML stays as written.

| Element | HTML |
|---|---|
| Heading | `id` is the GitLab anchor from `MarkdownTree.anchor` |
| Other block, first on a later source line | `id="qcaret-line-<line>"`; `MarkdownHtml.idAt` maps a source line to the id for scrolling |
| Fenced code, front matter | `<pre><code>` with `<span class="tk-<role>">` from `MarkdownCodeTokens.ofText`; front matter as `pre.front-matter` |
| `>>>`, `[[_TOC_]]` | `blockquote`; nested `ul.section-nav` of links to the headings |
| Local image | `data:` URI when its type is known, it is at most 16 MiB and the document's embedded total stays within 64 MiB; else its alt text |
| Remote image | A link with its alt text |
| Link to a local file | Preview: the file's `file:` URL with `qcaret-file:` as the scheme and the fragment kept (`MarkdownLinks.previewFileUrl`; see [Browser pane](../../qcaret-ui-swt/docs/markdown-preview.md#browser-pane)). Export: as written |

Code colors and image bytes are read in a pass before rendering, so file reads
stay in `@WorkerThread` methods. `MarkdownPage` (app.editor) wraps a fragment
in the page both use: the content security policy meta, the title and a
stylesheet resembling GitLab's in a `Palette`'s colors. `InertMeta` renames each
`<meta` tag in the fragment to `<wbr`, so a raw-HTML meta refresh, which the
policy does not cover, does not navigate.

File > Export as HTML (`MarkdownExport`, enabled for a Markdown tab) proposes
the document's name with `.html`, then parses, writes the HTML with the Light
palette and saves it through `SafeSave` on a `qcaret-io-` worker under the busy
cursor. The worker refuses the document's own file as the target, also through
a link or another spelling of its path (`Files.isSameFile`); a failed write
shows `EXPORT_HTML_FAILED`.

### Outline

`MarkdownOutline`, one per window, is the Outline dock's `OutlineInput`: the
selected tab's headings while its grammar is `markdown`, empty otherwise. It
follows the selected tab and focused view (`addActiveListener`) and grammar
changes (`DocumentController.setGrammarListener`), and reads nothing while the
dock is hidden. While shown it calls `showOutline` with true for a Markdown tab
and false otherwise, so the dock collapses on a plain tab and returns on a
Markdown one, and posts `outlineChanged` only while the dock shows; `shown` stays
the user's choice, which the session saves.

- It renders on its own in every mode, so Source lists headings too: at once
  when it binds a Markdown tab, and 300 ms after the last edit, on a
  `qcaret-cpu-` worker without the busy cursor. Each render carries a
  generation, raised by every edit and rebind, and the revision it read; a
  result whose generation or revision is not current is dropped. A document
  over `LIVE_LIMIT_CHARS` is not rendered and lists nothing.
- `OutlineTree` turns `RenderedMarkdown.headings` into `OutlineEntry` rows: a
  heading's parent is the closest earlier heading with a lower level, so a
  skipped level nests one step. At most 10,000 entries of 160 chars each are
  kept; the summary gives the total.
- `selected` is the last heading at or above the focused editor's caret line,
  updated from its `SelectionListener` and posted through `outlineSelected`.
- `goTo` moves the caret to the heading's line (a jump for Go Back), puts that
  line at the editor's top and, in Split and Preview, calls
  `MarkdownPreview.revealSource`, which scrolls the preview to the heading's
  rendered line without mapping the scroll back. Heading lines come from the
  last finished render, so after an edit they can be off until the next one;
  the line is clamped to the document. Before a tab's first render shows,
  `revealSource` holds the line for it; an edit drops the held line.

### Heading jumps

`HeadingJumps` moves a tab's caret to the heading a `file.md#id` target names,
for the command line and its handoff (`DocumentController.open(FileRequest)`),
preview links, Open Path at Caret and Quick Open, all through
`DocumentController.jumpToHeading`, which uses the jumps of the window holding
the document.

- A tab whose grammar is not known yet waits for `DocumentGrammars` to apply
  one (`setHeadingWaiting`). A grammar other than `markdown` ignores the id
  and shows `MARKDOWN_NO_HEADING`.
- A heading pass on a `qcaret-cpu-` worker parses a text snapshot
  (`MarkdownTree.headingSourceLine`), with the ids a render gives; nothing is
  rendered. A document over `LIVE_LIMIT_CHARS` is not parsed and shows
  `MARKDOWN_HEADING_TOO_LARGE`.
- The UI thread puts the caret at the heading line's start and that line at
  the editor's top, and calls `MarkdownPreview.revealSource`. An unknown id
  shows `MARKDOWN_NO_HEADING`; it puts the caret on line 0 when the open that
  ends in the jump read the file (`fresh`: no window held the path before the
  open), and leaves a file already open with its caret and scroll.
- A tab holds one jump; a later one replaces it. A pass whose tab closed or
  whose document was edited since the snapshot is dropped.
- `openLink` checks on a `qcaret-io-` worker for a regular file named with the
  whole `name#id` (`HeadingPath.whole`); one that exists opens with no
  heading.

## Printing

`PrintController` runs File > Print, File > Print Selection and File > Print
Rendered Markdown, one job per window; a second request shows a notice. Print
prints the selection when the `PrintTarget` reports `selectionOnly`; Print Selection prints it whatever the
target reports, and without a selection shows a notice and opens no dialog. A
page range applies to the pages being printed, the selection's under Print
Selection. The command captures a `PrintSource`: a text snapshot, the active
view's selections, the tab width, the grammar and the line-number setting. Edits
made afterward do not reach the job, and word wrap and folds, which are view
state, do not affect it. `MainView.choosePrinter` returns a `PrintTarget`, or
nothing for a canceled dialog; `PrintTarget` is the toolkit seam.

The job runs on a `qcaret-print-` platform thread: `start` measures a page as
columns and rows, `PrintLayout.of` paginates, `page` prints each page in the
chosen range, `finish` hands the job over, and `close` always runs and discards
an unfinished job. Cancel, or closing the window, stops the job at the next
line or page and shows no notice. A `PrintFailure` shows a notice; so do an
empty selection and a range past the last page, which print nothing.

`PrintLayout` (`app.print`, no toolkit) walks the snapshot once and keeps each
page's start, so memory follows the page count; `page` builds rows on demand.
Rules are in its Javadoc. It tokenizes forward from the first line on its own
`Tokenizer`, because a page's first line needs the state before it, so pages
are built in ascending order. `total` in a page is the count of the whole
layout, so a range prints Page 3 of 10.

### Rendered Markdown

Print Rendered Markdown, enabled on a Markdown tab, captures a
`MarkdownPrintSource` (text snapshot, folder, grammars) and prints the whole
rendering: the selection and a Selection scope do not apply; a page range does.
`MarkdownPrintJob` runs on the job's thread. It renders as the preview does
(`MarkdownRenderer`, `MarkdownCodeTokens`, `MarkdownSpans`), calls
`PrintTarget.startMarkdown`, hands each local image's bytes to the returned
`MarkdownPrintSheet` (an SVG, a file over 16 MiB or an unreadable one goes as
empty bytes and prints as a placeholder), then lays out with
`MarkdownPageLayout`. The sheet is the toolkit seam for measuring and drawing.

`MarkdownPageLayout` (`app.print`) measures table columns through
`MarkdownTableLayout` with the sheet as `TextMeasurer`, narrows the columns of a
table wider than the body (`MarkdownTableLayout.fit`: each column keeps its
widest word, the rest is shared by how far its widest cell exceeds that word),
asks the sheet for each rendered line's row heights, and stacks them on pages of
the body's height; a table row is one row, as tall as its tallest wrapped cell:

| Line | Page break |
|---|---|
| Paragraph, list item, quote, HTML, TOC | Between wrapped rows |
| Code line, table row, heading, rule | Never inside; a line taller than the page breaks between rows |
| Heading at a page's end | Moves to the next page with the separator lines after it, unless first on its page |
| Table row continuing a table on a new page | The header row prints first again |
| Separator line at a page's top | Left out |

A row taller than the body takes a page of its own and is cut at the body's
bottom; the sheet scales images to fit the body. Cancel is checked per block,
image, line and page.
