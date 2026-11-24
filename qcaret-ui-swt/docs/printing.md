# Printing

The SWT side of File > Print, Print Selection and Print Rendered Markdown:
`MainWindow.choosePrinter` and `PrintJob` in `ui.swt`, and `MarkdownPrinting`
in `ui.swt.markdown` of [qcaret-ui-swt](../README.md). Pagination and the job
lifecycle are in [qcaret-app documents](../../qcaret-app/docs/documents.md#printing).

`MainWindow.choosePrinter` opens `PrintDialog`, or on GTK 4 a PDF save dialog
(see [GTK 4](#gtk-4)), and creates the `Printer` on the UI thread; `PrintJob`
does the rest on the job's worker. `PrintSupport.choose` (`ui.swt.platform`)
opens the dialog with `PrinterData.SELECTION` while the document has a
non-empty selection, except on win32 (`Win32PrintSupport`). The Windows 11
dialog offers All pages and a custom range only; opened with `PD_SELECTION` it
showed All pages and the job printed only the selection (observed on Windows
11). On win32 the dialog opens on its default scope, so the job prints what the
dialog shows. File > Print Selection opens the same dialog; `PrintController` prints
the selection whatever scope comes back, so Selection is reachable on win32.
Print Rendered Markdown opens the same dialog, on Selection too while text is
selected, and prints the whole rendering whatever scope comes back.
Before the dialog opens, cocoa's `PrintSupport` names the job (see [Job
name](#job-name)). A `ProgressRow`
shows the progress and Cancel above the status bar. It takes no Esc, since the
window stays usable.

| Call | Thread | Reason |
|---|---|---|
| `PrintDialog.open`, `new Printer` | UI | GTK's constructor runs `gtk_enumerate_printers` with a main loop on the default context and uses static fields; macOS's creates an `NSWindow` |
| `startJob`, `GC`, fonts, `TextLayout` measuring and drawing, images, `endJob`, `cancelJob` | One platform worker | SWT checks the thread on widgets, not on a `Device` or its `GC`. macOS sets the graphics context per thread in `startJob`, and Pango's font map is per thread, so a virtual thread would be wrong |
| `Printer.dispose` | UI, or the worker when the Display is gone | The object was created there |

On GTK, `endJob` queues the spool file; the event loop delivers it, so a quit
straight after a job can lose it, and a delivery error reaches no notice.

## Page output

`PrintJob` draws from the `PrintTarget.Page` rows at 10 points in the editor
font's family, placing each span at its column times the measured advance, with
0.75 inch margins from the paper's edge (`computeTrim`). Colors are
`Palette.LIGHT` on white whatever the window's theme and Editor colors, a
bundled scheme or a theme file included; plain text and the header are black. A
device depth of 1 prints all black. GTK reports depth 0 and macOS the screen's
depth, so there the print system converts color for a monochrome printer.

## Rendered Markdown

`PrintJob.startMarkdown` starts the job as `start` does and returns a
`MarkdownPrintSheet` over `MarkdownPrinting`: the body is the area below the
header, and each page prints the same header. The text prints in the system
font's family at 10 points, headings at Basic's sizes, code in the editor
font's family, colored as Basic colors it with `Palette.LIGHT` on white (all
black at depth 1).

| Class | Role |
|---|---|
| `PrintLines` | Builds a rendered line's `TextLayout` on the `Printer`: styles from `MarkdownStyler.apply`, the shared span-to-style mapping; a paragraph line wraps at the body width less its quote and list indent; a table row is one `TextLayout` per cell, wrapped at its column's width and aligned in it, with 8 px of cell padding (`CELL_PADDING`). Basic's pixel constants scale by the printer's dpi over 96 |
| `PrintMeasurer` | All measuring: cell widths through `SpanWidths` with the printer's `GC`, each span in its font; a line's row heights through its `TextLayout`; a table row's height as its tallest cell's |
| `MarkdownPrinting` | Decodes images, draws placed rows clipped to the body, and paints what Basic's `MarkdownPainter` paints except block spacing and code boxes: a flat code shade, the header row's shade, quote bars, rules, heading lines, table borders, list markers, images and placeholders |

Measuring and drawing run on the job's worker. `TextLayout` measuring on a
`Printer` off the UI thread is verified on GTK 3 and GTK 4 (`PrintMeasurerTest`);
win32 and cocoa are unverified until that test runs on their CI jobs. Should
either fail, the fallback, measuring on the UI thread in bounded turns, goes in
`PrintMeasurer` alone; drawing stays on the worker.

An image decodes with `ImageLoader` from the bytes the job read, first frame
only, sized as `SwtImageDecoder.size` sizes it in 96-dpi pixels, scaled to the
printer's dpi and fitted inside the body. The decoded data is scaled down to the
printed size when larger and never up; `drawImage` scales it on the page. An
`Image` exists only while its row draws. A file that was not read or does not
decode prints as a box with its file name.

## Copies and ranges

Copies stay with the print system: the `PrinterData` passes unchanged to the
`Printer` (GTK's CUPS backend sends `cups-copies`; Windows has them in the
DEVMODE; macOS in the print info). QCaret does not loop, which would multiply
them. GTK's Print to File writes one copy; Microsoft Print to PDF writes every
copy into the file, collated. The page range is applied by `PrintController`;
GTK does not also pass it to CUPS for a `GtkPrintJob`.

## Job name

`PrintJob.start` passes the document's name to `Printer.startJob` on every
platform. On macOS that sets `NSPrintOperation.jobTitle` after `NSPrintPanel`
has closed, too late for the panel's PDF > Save as PDF sheet, which showed
"Untitled" (observed on macOS).

On cocoa, `CocoaPrintSupport.choose` calls `CocoaPrintJobNaming.name` before
`PrintDialog.open` for Print and Print Selection, which writes
`PdfPrinter.jobName` (`PdfPrinter.fileName` without `.pdf`: `d1d` for
`d1d.txt`) into `NSPrintInfo.sharedPrintInfo`'s `printSettings` under
`com_apple_print_JobInfo_PMJobName`. SWT 3.135 binds no
`PMPrintSettingsSetJobName`, so the class sends the Objective-C messages
through SWT's `internal.cocoa` bindings by reflection. `PrintDialog.open` takes
a copy of the shared print info for its panel and ignores
`PrinterData.otherData`. A reflective failure logs one WARN and the sheet
proposes "Untitled"; printing is unaffected. win32 and GTK name no job.

SWT passes `PrinterData.fileName` to the panel as `NSPrintSavePath`; the sheet
ignores it (observed on macOS), so QCaret does not set it.

The key is Core Printing's `kPMJobNameKey` as `printSettings` spells it; Save
as PDF proposing the job name is verified on macOS 27 (arm64).
`PrintJobNamingTest` on the macOS CI runner checks that the SWT members resolve
and that the name survives the copy and `updateFromPMPrintSettings` that
`PrintDialog.open` runs.

## GTK 4

SWT 3.135's `PrintDialog.open` throws `UnsatisfiedLinkError`
(`GTK3.gtk_dialog_run`) on GTK 4, so there `Gtk4PrintSupport.choose` uses
`PdfPrinter`: a `FileDialog` titled Print to PDF names the file, proposing the
document's name with its last extension replaced by `.pdf`
(`PdfPrinter.fileName`), and `PdfPrinter.printer` returns GTK's Print to File
printer (driver `GtkPrintBackendFile`) set to write it. The job prints every
page, one copy; there is no printer, range or selection choice. Print Selection
prints the selection there. A print system without a Print to File printer
shows the no-printer notice.

The save dialog is the `FileDialog` Save As uses; under Xvfb on GTK 4 it opens no
X window, so `PrintOutputTest` replaces it through `MainWindow.setPdfChooser`.
