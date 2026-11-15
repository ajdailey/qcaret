# Printing

The SWT side of File > Print: `MainWindow.choosePrinter` and `PrintJob` in
`ui.swt` of [qcaret-ui-swt](../README.md). Pagination and the job lifecycle are
in [qcaret-app documents](../../qcaret-app/docs/documents.md#printing).

`MainWindow.choosePrinter` opens `PrintDialog`, or on GTK 4 a PDF save dialog
(see [GTK 4](#gtk-4)), and creates the `Printer` on the UI thread; `PrintJob` does the rest on the job's worker.
`PrintDialogs.create` opens the dialog with `PrinterData.SELECTION` while the
document has a non-empty selection: the Windows 11 dialog lists Selection only
when it opens with `PD_SELECTION` (absent without it, observed; that the flag adds
it is assumed). On macOS (`PlatformTraits.proposesPrintFileName`) it also sets
`PrinterData.fileName` to `PdfPrinter.fileName` of the document, which SWT
passes as `NSPrintSavePath`; whether the Save as PDF sheet proposes it is
unverified. win32 writes a Print to File job to that name without asking, and
GTK ignores it without Print to File, so only cocoa sets it. A `ProgressRow` shows
the progress and Cancel above the status bar. It takes no Esc, since the window
stays usable.

| Call | Thread | Reason |
|---|---|---|
| `PrintDialog.open`, `new Printer` | UI | GTK's constructor runs `gtk_enumerate_printers` with a main loop on the default context and uses static fields; macOS's creates an `NSWindow` |
| `startJob`, `GC`, fonts, drawing, `endJob`, `cancelJob` | One platform worker | SWT checks the thread on widgets, not on a `Device` or its `GC`. macOS sets the graphics context per thread in `startJob`, and Pango's font map is per thread, so a virtual thread would be wrong |
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
has closed, so the panel's PDF > Save as PDF sheet proposes "Untitled" (G2
S9-10). SWT 3.135's `PrintDialog` copies `NSPrintInfo.sharedPrintInfo` and
takes no job name, so no public SWT call names the job before the panel.

## GTK 4

SWT 3.135's `PrintDialog.open` throws `UnsatisfiedLinkError`
(`GTK3.gtk_dialog_run`) on GTK 4, so `PlatformTraits.opensPrintDialog` is false
there and `choosePrinter` uses `PdfPrinter`: a `FileDialog` titled Print to PDF
names the file, proposing the document's name with its last extension replaced
by `.pdf` (`PdfPrinter.fileName`), and `PdfPrinter.printer` returns GTK's Print to File printer
(driver `GtkPrintBackendFile`) set to write it. The job prints every page, one
copy; there is no printer, range or selection choice. A print system without a
Print to File printer shows the no-printer notice.

The save dialog is the `FileDialog` Save As uses; under Xvfb on GTK 4 it opens no
X window, so `PrintOutputTest` replaces it through `MainWindow.setPdfChooser`.
