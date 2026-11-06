# QCaret User Guide

QCaret is a programmer's text editor. It never connects to another computer; a
second launch hands its files to the running QCaret over a local-only
connection. On macOS, `Ctrl` in this guide is `Cmd` unless a key is named for
macOS; Help > Keyboard Shortcuts lists every key for your platform and Edit >
Preferences, Keys changes them.

- [Opening files](#opening-files)
- [The main window](#the-main-window)
- [Editing](#editing)
- [Multiple carets and block selection](#multiple-carets-and-block-selection)
- [Search and HyperSearch](#search-and-hypersearch)
- [Replace](#replace)
- [Bookmarks](#bookmarks)
- [Folding](#folding)
- [Syntax highlighting](#syntax-highlighting)
- [External changes](#external-changes)
- [Workspace](#workspace)
- [Quick Open](#quick-open)
- [Compare](#compare)
- [Sessions and recovery](#sessions-and-recovery)
- [Windows and the running instance](#windows-and-the-running-instance)
- [Preferences](#preferences)
- [Help and logs](#help-and-logs)
- [Command line](#command-line)

## Opening files

Open files with File > Open (`Ctrl+O`), File > Recent Files (the last 20),
[Quick Open](#quick-open), the workspace browser, or the
[command line](#command-line). Each file opens in its own tab; a file already
open selects its tab, in any window. File > New (`Ctrl+N`) opens an untitled
document. File > Reopen Closed Tab (`Ctrl+Shift+T`) opens the last closed tab's
file again, up to 20 back, where the caret was.

The file's encoding comes from its byte order mark, an XML, HTML or Python
declaration, or the content; UTF-8 is tried before windows-1252. Encoding >
Reopen with Encoding reads the file again with another charset, and QCaret
remembers it for that file. Encoding > Save with Encoding changes what the next
save writes. Encoding > Line Endings converts every line break in one undo step;
a file with mixed endings keeps them until then, and Enter inserts the ending
last converted to.

A file that cannot be written back byte for byte in its encoding opens
read-only with a notice. A binary file asks first: Open Read-Only shows it as
ISO-8859-1 text, with editing and saving off.

A file needs heap to open: 1 byte per file byte for ASCII text and 2 otherwise,
plus up to 64 MiB for undo. A file that does not fit is refused, and the notice
names the `-Xmx` value to start with. No file over 2 GiB opens.

Save (`Ctrl+S`), Save As (`Ctrl+Shift+S`) and Save All (`Ctrl+Alt+S`) write on a
worker while you keep editing; `Esc` cancels a running save and leaves the file
as it was. A save writes a temporary file and moves it over the target, keeps
the file's permissions, and follows a symbolic link. Save As onto a file open
in another tab closes that tab, after asking when the tab has unsaved changes.

## The main window

![Main window with the workspace browser, a highlighted file, the find bar and search results](images/guide/main.png)

- The tab row holds one tab per document. A dirty document's tab is marked, and
  so is a file missing on disk. A middle click or the close button closes a tab;
  dragging moves it. A tab's menu offers Close, Close Others, Close All, Copy
  Path and Copy File Name.
- The editor shows line numbers, bookmark and fold marks in its gutter. View >
  Word Wrap, Show Whitespace, Show Line Endings and Overview Strip apply to the
  window's editors. Zoom is `Ctrl+=`, `Ctrl+-` and `Ctrl+0`.
- The overview strip beside the scroll bar marks find matches, HyperSearch
  matches and bookmarks across the whole document; a click or drag scrolls
  there.
- View > Split shows a document in two views, side by side or one above the
  other, each with its own carets and scroll position. `F6` moves between them.
- View > Workspace Browser, Search > Search Results and Search > Bookmarks >
  Bookmarks List show the docks around the editor.
- The status bar shows notices, the read-only marker, line and column, the
  selection, encoding, line endings and language. A click on the language
  chooses a grammar. A Warnings link appears when QCaret logs a warning and opens
  Help > Show Log.
- View > Theme chooses Light, Dark or System. On Windows native controls change
  at the next start, and on macOS they follow the system appearance.
- View > Command Palette (`Ctrl+Shift+P`) finds and runs any menu command by
  name.

## Editing

Undo (`Ctrl+Z`) and Redo (`Ctrl+Shift+Z`, and `Ctrl+Y` off macOS) restore every
caret. Typing an opening bracket or quote inserts its closer; the grammar names
the pairs, and Preferences turns this off. Enter and a typed closer indent from
the grammar's rules.

| Keys | Action |
|---|---|
| `Ctrl+D` | Duplicate the selection, or the line |
| `Ctrl+Shift+L` | Delete the line |
| `Alt+Up`, `Alt+Down` | Move the lines up or down |
| `Ctrl+J` | Join lines |
| `Ctrl+Shift+U`, `Ctrl+U` | Upper case, lower case (Edit menu only on Linux) |
| `Ctrl+]`, `Ctrl+[`, `Shift+Tab` | Indent, outdent |
| `Ctrl+/`, `Ctrl+Shift+/` | Toggle a line or block comment |
| `Ctrl+Space` (`Option+Esc` on macOS) | Complete the word from the open documents |
| `Ctrl+B`, `Ctrl+Shift+B` | Go or select to the matching bracket |
| `Ctrl+G` (`Cmd+L` on macOS) | Go to a line, as `line`, `line:column` or `line,column` |
| `Alt+Left`, `Alt+Right` (`Control+Cmd+Left` and `Right` on macOS) | Go Back and Go Forward through jumps, across documents |

Edit > Sort Lines, Remove Duplicate Lines and Trim Trailing Whitespace act on
the selected lines, or every line. Each is one undo step. Edit > Tab Width,
Indent with Spaces and Read-Only apply to the selected document. The bracket
beside the caret and the other occurrences of the word at the caret are
highlighted.

Completion opens on its key, or by itself after the number of word chars set in
Preferences. Up, Down, Enter, Tab and `Esc` work in the list; typing narrows it.

## Multiple carets and block selection

![Three carets with selected occurrences of a word](images/guide/carets.png)

| Keys | Action |
|---|---|
| `Ctrl`+click | Add a caret, or remove the one clicked |
| `Ctrl+Shift+Up`, `Ctrl+Shift+Down` (`Control+Shift` on macOS) | Add a caret above or below |
| `Ctrl+E` | Select the word, then add its next occurrence |
| Selection > Split into Lines | One selection per line of each selection |
| `Alt`+drag, `Ctrl+Alt`+drag | Select a block |
| `Alt+Shift`+arrow (`Cmd+Option+Shift` on macOS) | Extend a block by one row or column |
| `Alt+Shift`+click | Extend a block to the point clicked |
| `Esc` | Drop the extra carets or the block |

Typing, Backspace, Delete, caret moves and the Edit commands apply at every
caret. Copy joins the selections with the line ending. Paste with as many lines
as selections puts one line at each. A block can reach past the end of a short
line, and typing there pads the line.

Selection > Column Selection Mode makes a plain drag and `Shift` with an arrow
key select a block.

## Search and HyperSearch

Search > Find (`Ctrl+F`) opens the find bar, with a selection on one line as its
text. Typing finds as you type and every match in view is highlighted. `F3`,
`Shift+F3`, or Enter and `Shift+Enter` in the bar move between matches and wrap
at the ends with a notice. Match case, Whole word and Regex are toggles. `Esc`
closes the bar. Both fields keep a history.

Search > HyperSearch (`Ctrl+Shift+F`) finds every match in a scope and lists them
in the results dock while you work.

![The HyperSearch dialog](images/guide/hypersearch.png)

| Search in | Reads |
|---|---|
| Current document, Selection | The selected document, or the view's selections |
| Open documents | Every document open in the window |
| Workspace folder | The window's workspace folder and its subdirectories |
| Directory | The files of one directory |
| Directory and subdirectories | A directory's tree |

Files and Exclude take masks separated by `;`, such as `*.java;*.xml`. Open
documents are searched as edited, in every scope, so a folder search reads an
unsaved buffer and not its file.

The dock keeps ten sets, newest first; Pin keeps one past that. A set stops at
100,000 matches. Files over 64 MiB, binary and unreadable files and very long
lines are skipped, and the set's row counts them. Enter or a double click opens
a match; `F4` and `Shift+F4` step through the set. Stop ends a running search
and keeps what it found; Rerun runs it again; Clear and Clear All drop sets.

A match in text changed since the search shows as stale. Opening one goes to its
line with a notice. Rerun File searches that file again.

## Replace

In the find bar, Search > Replace (`Ctrl+H`, `Cmd+Option+F` on macOS) adds the
replace row. Replace and Replace All are one undo step each. A regex
replacement can name groups with `$1` or `${name}`.

Across files, use the dock's Replace or Search > Replace in Results
(`Ctrl+Shift+H`) on a finished set.

![The replace preview with the before and after view](images/guide/replace.png)

The preview lists every file and match with a check box and shows the selected
file before and after. Apply runs the replacement and opens a report of each
file: replaced, skipped with the reason, failed, or not attempted.

- An open document takes the replacement as one undo step and stays unsaved.
- A file not open is rewritten, with its original bytes kept under
  `replace-backups/` in the settings directory for 7 days.
- A file changed since the search is skipped.

No undo spans files. Restore Run in the report, or Search > Restore Replace Run,
writes a run's backups back, and refuses a file changed since the run.

## Bookmarks

| Keys | Action |
|---|---|
| `Ctrl+F2` | Toggle a bookmark on each caret's line |
| `F2`, `Shift+F2` | Next and previous bookmark, wrapping |
| `Ctrl+Shift+F2` | Edit the line's comment, bookmarking the line |

A bookmark shows as a bar in the gutter and a commented one as a flag, with the
comment as its tooltip. Bookmarks follow their lines through edits. Search >
Bookmarks > Bookmarks List shows the document's bookmarks with Go To, Edit
Comment and Remove. A document holds up to 1,000 comments of 1,000 chars.

## Folding

![Collapsed regions with their gutter marks](images/guide/folds.png)

A box in the gutter marks each line that starts a fold region; a click toggles
it. A collapsed region's first line ends in a box with an ellipsis. Regions come
from the grammar's brackets or markers, and from indentation otherwise.

| Keys | Action |
|---|---|
| `Ctrl+Shift+[`, `Ctrl+Shift+]` | Fold, unfold |
| `Ctrl+Alt+[`, `Ctrl+Alt+]` | Fold all, unfold all |
| View > Folding > Toggle Fold | Either, at the caret |

An edit inside a collapsed region expands it. A command that puts the caret in
hidden lines, such as find or Go to Line, expands the regions hiding it. Each
split view folds on its own.

## Syntax highlighting

QCaret highlights 35 languages and 12 file formats. The grammar follows the file
name, its extension or its first line. View > Syntax, or a click on the status
bar's language, chooses another one for the document, from the last five chosen
or a category submenu. A line over 10,000 chars shows without colors;
Preferences changes that limit.

User grammars are XML files in `grammars/` in the settings directory. One with a
built-in grammar's id replaces it. View > Syntax > Reload Grammars reads them
again, and an invalid file is skipped with a notice. The format is in the project
documentation, `qcaret-core/README.md`.

## External changes

QCaret checks each open file every 5 seconds and when a window activates.

| The file on disk | Without unsaved changes | With unsaved changes |
|---|---|---|
| Changed | Reloads | Asks: Keep Mine, Reload or Compare |
| Deleted | Stays open, unsaved, marked missing | Same |
| Back with the same bytes | The mark clears | The mark clears |

A reload is one undo step and keeps each view's caret line and column. When the
change does not fit in memory, the tab keeps its text and a notice says so. Keep
Mine makes the next save ask. A save that finds the file changed asks: Overwrite,
Reload, Compare or Cancel. Preferences, Files sets the interval and can make a
clean document ask too.

## Workspace

File > Set Workspace Folder gives each window one folder, the scope of
HyperSearch's Workspace folder and of Quick Open's file list. View > Workspace
Browser shows its tree. Enter or a double click opens a file. Refresh lists the
folder again, and Reveal Active File selects the current document's file. A
row's menu offers Open in Default Application, Set as Workspace Folder and Copy
Path. Open in Default Application refuses a file the system would run.

Hidden entries and the excluded directories (`.git`, `.svn`, `.hg`, `target`,
`node_modules` and `build` by default) stay out of the browser, Quick Open and
folder searches. Preferences, Search and Workspace changes the list.

## Quick Open

![Quick Open with a typed query](images/guide/quick-open.png)

File > Quick Open (`Ctrl+P`) matches a typed name against the open documents, in
bold, then the workspace files, up to 200,000 indexed, then Recent Files. The
query's letters match in order and need not be adjacent. A query ending in
`:line` or `:line:column` opens there. With nothing typed it lists the open
documents and Recent Files. Window > Switch Document (`Ctrl+Shift+O`) is the same
list over the open documents.

## Compare

View > Compare > Compare Documents compares the selected document with another
open one; Compare with File on Disk compares it with its file, and so does the
Compare button of a change prompt. `--diff <a> <b>` compares two files from the
command line.

![The comparison window](images/guide/compare.png)

The comparison is a read-only window with the two sides scrolling together. `F7`
and `Shift+F7` step through the differences, and `Esc` closes it. A side over
100,000 lines or 16 Mi chars gets a notice instead.

## Sessions and recovery

A start without files restores the last session: every window at its position,
with its tabs, carets, scroll, splits, folds, bookmarks, read-only state, view
options, workspace and dock contents. A file that does not open is named and
skipped. Search results return as requests, and Rerun fills them. A closed file
keeps its caret, tab settings, encoding and grammar for when it opens again.

While a document has unsaved changes, QCaret writes it to `recovery/` in the
settings directory every 30 seconds. After a crash the next start lists those
buffers with the state of each original file:

- Restore opens the buffer with its unsaved changes and leaves the original file
  alone.
- Discard deletes the recovery file.
- Later keeps it for the next start.

Recovery files left by a crash are kept for 30 days. With Preferences, Files,
Quit without asking on, Exit asks nothing and the next start restores unsaved
and untitled buffers.

## Windows and the running instance

Window > New Window opens another window. Settings, the theme, key bindings,
grammars and Recent Files are shared; each window has its own tabs, find bar,
workspace folder and search results. A document is open in one tab of one window
at a time.

Drag a tab out of every QCaret window to open it in a new window, or onto another
window's tab strip to dock it there; Window > Move to New Window does the first.
The document moves live, with its undo history and unsaved changes. A tab with a
save, reload or conversion running does not move.

File > Exit asks about unsaved documents in every window before anything closes;
Cancel leaves everything as it was, and a save that fails stops the quit. Closing
a window asks about its own documents; closing the last one quits.

A second launch on the same settings directory passes its files to the running
QCaret and exits. They open in the window activated last, or a new one with `-n`.
Launches at the same moment queue, and each file becomes a tab in the one
instance. `-m` runs an independent instance that saves no window placement or
session.

File associations: on Linux run `bin/install-desktop-entry`; on Windows run
`register-associations.cmd`; the macOS bundle declares its document types itself.

## Preferences

![Preferences on the Files tab](images/guide/preferences.png)

Edit > Preferences (`Ctrl+,`; the application menu on macOS) applies to the
running windows without a restart.

| Tab | Holds |
|---|---|
| Editor | Completion trigger, auto-close, longest line with syntax colors |
| Files | Seconds between file checks, reload without asking, recovery interval and keep time, quit without asking |
| Search and Workspace | Longest line searched, days a replace run's backups are kept, excluded directories, Clear History |
| Appearance | Theme, editor font size |
| Keys | Every command's keys: Add, Remove, Default Keys |

Add refuses a key that types text or that another command holds, and names that
command. Clear History removes the saved search texts, replacements, directories
and masks, and the searches kept in the session.

Settings are in `qcaret.conf`, in the first of these that applies:

| Mode | When | Directory |
|---|---|---|
| Explicit | `--settings <dir>` | `<dir>` |
| Portable | `qcaret.conf` exists in the installation directory | The installation directory |
| Per-user | Otherwise | Linux `~/.config/qcaret`, Windows `%APPDATA%\QCaret`, macOS `~/Library/Application Support/QCaret` |

## Help and logs

- Keyboard Shortcuts (`F1`) lists every key, with a filter and Copy.
- Command-Line Options shows the usage text.
- User Guide shows this guide in a window that loads nothing else. Under GTK 4 a
  notice names the guide's file instead; open that file in a browser. Without a
  working embedded browser the guide opens in the system's HTML application.
- Show Log shows the recent log lines and clears the Warnings link.
- Debug Log File writes the log at DEBUG to `logs/` in the settings directory
  until turned off. Open Log Folder and Open Settings Folder open those folders.
- Report an Issue copies the About details to the clipboard and opens the
  project's issue page in the system browser. Together with the project link in
  About, it is the only web address QCaret opens, and only when you pick it.
- About shows the version, runtime and settings directory; Licenses shows the
  license notices and Copy Details copies the details.

## Command line

```text
qcaret [options] [[+<line>[:<column>]] file]...
```

| Option | Effect |
|---|---|
| `-h`, `--help` | Print usage and exit |
| `-v`, `--version` | Print the version and exit |
| `-d`, `--debug <level>` | Log level on stderr: `1` info, `2` debug, `3` trace, or a level name |
| `-l`, `--log` | Also write the log to `logs/` in the settings directory, for this run |
| `-m`, `--multi` | Run as an independent instance |
| `-n`, `--new-window` | Open in a new window of the running instance |
| `-r`, `--read-only` | Open the following files read-only |
| `-e`, `--encoding <name>` | Open the following files in this encoding |
| `+<line>[:<column>]` | Place the caret in the next file |
| `--session`, `--no-session` | Restore the previous session also with files, or skip it |
| `--diff <a> <b>` | Open the comparison of two files |
| `--window-width <px>`, `--window-height <px>` | Client-area size, for this run |
| `--window-x <px>`, `--window-y <px>` | Window position, for this run |
| `--settings <dir>` | Settings directory |
| `--` | End of options |

A missing file opens an empty document that saves to that path. Options combine:
`-ml`, `--opt=value`, `-d2`. An unknown option or a bad value exits 1; an SWT
start failure, such as missing GTK libraries, exits 3.

```text
qcaret +120:8 src/Mock.java -r notes.txt
```

To give QCaret more heap for large files, run the jar with the bundle's runtime:
`runtime/bin/java --enable-native-access=ALL-UNNAMED -Xmx8g -jar lib/qcaret.jar`.
On Windows add `-Xmx8g` as a line in
`qcaret.l4j.ini` next to `qcaret.exe`.
