# Walks the $env:TARGET setup interactively and saves each page as a PNG in
# installer-screenshots\: a per-machine install that keeps the Explorer menu
# component, ticks the desktop shortcut box and leaves Run QCaret checked, its
# uninstaller, then a portable install into a temporary folder, which skips
# the components page, then the elevated instance the type page starts for
# all users (/MACHINE /ELEVATED /NOEXPLORERMENU), which must open on the
# components page with Back disabled. After each install the finish page must
# have started qcaret.exe from an explorer.exe, with QCaret's token elevation
# equal to the shell's, and the per-machine install must have written the
# common desktop shortcut and the HKLM QCaret.Edit verb, which its uninstaller
# removes; QCaret is then stopped. Each page must show a bitmap, and every
# visible bitmap control must have SS_CENTERIMAGE, which setup's scaled-art
# functions set; the walk prints each one's size and the window DPI. A shell with elevation 0
# makes this the unelevated check; on a runner whose shell is elevated it
# checks the launch path only. Pages are driven by window messages (the Next button's
# WM_COMMAND, BM_CLICK on a radio button or check box, WM_SETTEXT on the
# folder box) and captured with PrintWindow, so no window needs the focus. Run
# with Windows PowerShell from the repository root on a runner with a
# desktop, with no QCaret installed.
$ErrorActionPreference = 'Stop'

Add-Type -ReferencedAssemblies System.Drawing -TypeDefinition @'
using System;
using System.Drawing;
using System.Drawing.Imaging;
using System.Runtime.InteropServices;
using System.Text;

public static class SetupUi {
  [StructLayout(LayoutKind.Sequential)]
  public struct Rect { public int Left, Top, Right, Bottom; }
  public delegate bool EnumProc(IntPtr hwnd, IntPtr data);

  [DllImport("user32.dll")] static extern IntPtr GetDlgItem(IntPtr hwnd, int id);
  [DllImport("user32.dll")] static extern int GetDlgCtrlID(IntPtr hwnd);
  [DllImport("user32.dll")] static extern bool IsWindowEnabled(IntPtr hwnd);
  [DllImport("user32.dll")] static extern bool IsWindowVisible(IntPtr hwnd);
  [DllImport("user32.dll")] public static extern bool IsWindow(IntPtr hwnd);
  [DllImport("user32.dll")] static extern bool GetWindowRect(IntPtr hwnd, out Rect rect);
  [DllImport("user32.dll")] static extern bool PrintWindow(IntPtr hwnd, IntPtr hdc, uint flags);
  [DllImport("user32.dll")] static extern bool EnumChildWindows(IntPtr hwnd, EnumProc proc, IntPtr data);
  [DllImport("user32.dll", CharSet = CharSet.Unicode)]
  static extern int GetWindowText(IntPtr hwnd, StringBuilder text, int max);
  [DllImport("user32.dll", CharSet = CharSet.Unicode)]
  static extern IntPtr SendMessage(IntPtr hwnd, uint msg, IntPtr w, IntPtr l);
  [DllImport("user32.dll", CharSet = CharSet.Unicode)]
  static extern IntPtr SendMessage(IntPtr hwnd, uint msg, IntPtr w, string l);

  [DllImport("user32.dll", CharSet = CharSet.Unicode)]
  static extern int GetClassName(IntPtr hwnd, StringBuilder name, int max);

  [DllImport("user32.dll")] static extern IntPtr GetShellWindow();
  [DllImport("user32.dll")] static extern uint GetWindowThreadProcessId(IntPtr hwnd, out int pid);

  // The process id of the desktop shell, 0 without one.
  public static int ShellPid() {
    int pid = 0;
    IntPtr shell = GetShellWindow();
    if (shell != IntPtr.Zero) { GetWindowThreadProcessId(shell, out pid); }
    return pid;
  }

  [DllImport("kernel32.dll")] static extern IntPtr OpenProcess(uint access, bool inherit, int pid);
  [DllImport("kernel32.dll")] static extern bool CloseHandle(IntPtr handle);
  [DllImport("advapi32.dll")] static extern bool OpenProcessToken(IntPtr process, uint access, out IntPtr token);
  [DllImport("advapi32.dll")]
  static extern bool GetTokenInformation(IntPtr token, int infoClass, out int info, int length, out int returned);

  // A DWORD from process pid's token: class 20 TokenElevation (1 elevated, 0 not)
  // or 18 TokenElevationType (1 default, 2 full, 3 limited); -1 when unreadable.
  public static int TokenValue(int pid, int infoClass) {
    IntPtr process = OpenProcess(0x1000, false, pid);
    if (process == IntPtr.Zero) { return -1; }
    try {
      IntPtr token;
      if (!OpenProcessToken(process, 0x0008, out token)) { return -1; }
      try {
        int value;
        int returned;
        return GetTokenInformation(token, infoClass, out value, 4, out returned) ? value : -1;
      } finally {
        CloseHandle(token);
      }
    } finally {
      CloseHandle(process);
    }
  }

  // The visible dialog whose caption starts with prefix: a page may append
  // to the caption.
  public static IntPtr Find(string prefix) {
    IntPtr found = IntPtr.Zero;
    EnumWindows(delegate(IntPtr hwnd, IntPtr data) {
      StringBuilder cls = new StringBuilder(64);
      GetClassName(hwnd, cls, cls.Capacity);
      if (IsWindowVisible(hwnd) && cls.ToString() == "#32770" && TextOf(hwnd).StartsWith(prefix)) {
        found = hwnd;
        return false;
      }
      return true;
    }, IntPtr.Zero);
    return found;
  }

  [DllImport("user32.dll")] static extern bool EnumWindows(EnumProc proc, IntPtr data);

  // The titles of the visible top-level windows, one per line.
  public static string TopLevel() {
    StringBuilder all = new StringBuilder();
    EnumWindows(delegate(IntPtr hwnd, IntPtr data) {
      if (IsWindowVisible(hwnd)) { all.Append(TextOf(hwnd)).Append('\n'); }
      return true;
    }, IntPtr.Zero);
    return all.ToString();
  }

  public static bool NextEnabled(IntPtr hwnd) {
    IntPtr next = GetDlgItem(hwnd, 1);
    return next != IntPtr.Zero && IsWindowEnabled(next);
  }

  public static bool BackEnabled(IntPtr hwnd) {
    IntPtr back = GetDlgItem(hwnd, 3);
    return back != IntPtr.Zero && IsWindowVisible(back) && IsWindowEnabled(back);
  }

  [DllImport("user32.dll")]
  static extern bool PostMessage(IntPtr hwnd, uint msg, IntPtr w, IntPtr l);

  // Posted, so a page that opens a message box does not block the caller.
  public static void Next(IntPtr hwnd) { PostMessage(hwnd, 0x0111, (IntPtr) 1, IntPtr.Zero); }

  static string TextOf(IntPtr hwnd) {
    StringBuilder text = new StringBuilder(1024);
    GetWindowText(hwnd, text, text.Capacity);
    return text.ToString();
  }

  // The visible text of every child window, one per line: changes with the page.
  public static string Texts(IntPtr hwnd) {
    StringBuilder all = new StringBuilder();
    EnumChildWindows(hwnd, delegate(IntPtr child, IntPtr data) {
      if (IsWindowVisible(child)) { all.Append(TextOf(child)).Append('\n'); }
      return true;
    }, IntPtr.Zero);
    return all.ToString();
  }

  // Sends BM_CLICK to the visible child whose text is exactly text.
  public static bool Click(IntPtr hwnd, string text) {
    IntPtr found = IntPtr.Zero;
    EnumChildWindows(hwnd, delegate(IntPtr child, IntPtr data) {
      if (IsWindowVisible(child) && TextOf(child) == text) { found = child; return false; }
      return true;
    }, IntPtr.Zero);
    if (found == IntPtr.Zero) { return false; }
    SendMessage(found, 0x00F5, IntPtr.Zero, IntPtr.Zero);
    return true;
  }

  // Sets the text of the visible child with control id id.
  public static bool SetText(IntPtr hwnd, int id, string text) {
    IntPtr found = IntPtr.Zero;
    EnumChildWindows(hwnd, delegate(IntPtr child, IntPtr data) {
      if (IsWindowVisible(child) && GetDlgCtrlID(child) == id) { found = child; return false; }
      return true;
    }, IntPtr.Zero);
    if (found == IntPtr.Zero) { return false; }
    SendMessage(found, 0x000C, IntPtr.Zero, text);
    return true;
  }

  [DllImport("user32.dll")] static extern bool GetClientRect(IntPtr hwnd, out Rect rect);
  [DllImport("user32.dll")] static extern int GetWindowLong(IntPtr hwnd, int index);
  [DllImport("user32.dll")] public static extern uint GetDpiForWindow(IntPtr hwnd);

  // Each visible bitmap static under hwnd, one per line: control id, client
  // size, and "centered" when it has SS_CENTERIMAGE.
  public static string Art(IntPtr hwnd) {
    StringBuilder all = new StringBuilder();
    EnumChildWindows(hwnd, delegate(IntPtr child, IntPtr data) {
      StringBuilder cls = new StringBuilder(64);
      GetClassName(child, cls, cls.Capacity);
      int style = GetWindowLong(child, -16);
      if (IsWindowVisible(child) && cls.ToString() == "Static" && (style & 0x1F) == 0x0E) {
        Rect rect;
        GetClientRect(child, out rect);
        all.AppendFormat("{0} {1}x{2}{3}\n", GetDlgCtrlID(child), rect.Right, rect.Bottom, (style & 0x200) != 0 ? " centered" : "");
      }
      return true;
    }, IntPtr.Zero);
    return all.ToString();
  }

  public static void Capture(IntPtr hwnd, string path) {
    Rect rect;
    GetWindowRect(hwnd, out rect);
    using (Bitmap image = new Bitmap(rect.Right - rect.Left, rect.Bottom - rect.Top)) {
      using (Graphics g = Graphics.FromImage(image)) {
        IntPtr hdc = g.GetHdc();
        PrintWindow(hwnd, hdc, 2);
        g.ReleaseHdc(hdc);
      }
      image.Save(path, ImageFormat.Png);
    }
  }
}
'@

$policy = Get-ItemProperty 'HKLM:\SOFTWARE\Microsoft\Windows\CurrentVersion\Policies\System'
$elevated = ([Security.Principal.WindowsPrincipal] [Security.Principal.WindowsIdentity]::GetCurrent()).IsInRole([Security.Principal.WindowsBuiltInRole]::Administrator)
Write-Host "UAC: EnableLUA=$($policy.EnableLUA) ConsentPromptBehaviorAdmin=$($policy.ConsentPromptBehaviorAdmin) elevated=$elevated"
$out = Join-Path $PWD 'installer-screenshots'
if (Test-Path $out) { Remove-Item -Recurse -Force $out }
New-Item -ItemType Directory -Path $out | Out-Null
$setup = (Get-Item "dist\qcaret-*-$env:TARGET-setup.exe").FullName
$script:n = 0
$shellPid = [SetupUi]::ShellPid()
if ($shellPid -eq 0) { throw 'no desktop shell window' }
$shellElevation = [SetupUi]::TokenValue($shellPid, 20)
Write-Host "shell $shellPid $((Get-Process -Id $shellPid).Name): elevation $shellElevation, type $([SetupUi]::TokenValue($shellPid, 18)); this PowerShell: elevation $([SetupUi]::TokenValue($PID, 20)), type $([SetupUi]::TokenValue($PID, 18))"

# Records each qcaret.exe and explorer.exe start under the source identifier $Name.
function Watch-Starts([string] $Name) {
  Register-CimIndicationEvent -Query "SELECT * FROM Win32_ProcessStartTrace WHERE ProcessName = 'qcaret.exe' OR ProcessName = 'explorer.exe'" -SourceIdentifier $Name | Out-Null
}

# Returns the dialog whose caption starts with $Title, waiting up to 60 s.
function Wait-Window([string] $Title) {
  $deadline = (Get-Date).AddSeconds(60)
  while ((Get-Date) -lt $deadline) {
    $hwnd = [SetupUi]::Find($Title)
    if ($hwnd -ne [IntPtr]::Zero) { return $hwnd }
    Start-Sleep -Milliseconds 200
  }
  Write-Host "visible top-level windows:`n$([SetupUi]::TopLevel())"
  throw "no window titled $Title within 60 s"
}

# Waits up to 180 s for a page other than $Previous whose text has not changed
# for 1.5 s with Next enabled, and returns its text; $null once the window
# $Hwnd is gone. Pages may change the window's caption, so the window is
# tracked by handle.
function Wait-Page([IntPtr] $Hwnd, [string] $Previous) {
  $deadline = (Get-Date).AddSeconds(180)
  $last = $null
  $since = Get-Date
  while ((Get-Date) -lt $deadline) {
    if (-not [SetupUi]::IsWindow($Hwnd)) { return $null }
    $text = [SetupUi]::Texts($Hwnd)
    if ($text -cne $last) {
      $last = $text
      $since = Get-Date
    } elseif ($text -cne $Previous -and [SetupUi]::NextEnabled($Hwnd) -and ((Get-Date) - $since).TotalMilliseconds -ge 1500) {
      return $text
    }
    Start-Sleep -Milliseconds 200
  }
  throw "the window showed no new settled page within 180 s; last text:`n$last"
}

function Save-Window([IntPtr] $Hwnd, [string] $Name) {
  $script:n++
  $file = Join-Path $out ('{0:D2}-{1}.png' -f $script:n, $Name)
  [SetupUi]::Capture($Hwnd, $file)
  Write-Host "saved $file"
}

# Saves each page of the window $Title and presses Next until the window
# closes. $OnPage runs on each page and returns $true when it changed the
# page, which is then saved again before Next.
function Invoke-Walk([string] $Title, [string] $Prefix, [scriptblock] $OnPage) {
  $hwnd = Wait-Window $Title
  $previous = ''
  for ($page = 1; $page -le 12; $page++) {
    $text = Wait-Page $hwnd $previous
    if ($null -eq $text) { return }
    Write-Host "page $page`: $(($text -split "`n" | Where-Object { $_ }) -join ' | ')"
    Save-Window $hwnd ('{0}-{1}' -f $Prefix, $page)
    $art = @([SetupUi]::Art($hwnd) -split "`n" | Where-Object { $_ })
    Write-Host "page $page bitmaps at $([SetupUi]::GetDpiForWindow($hwnd)) DPI: $($art -join ', ')"
    if ($art.Count -eq 0) { throw "page $page shows no bitmap" }
    if (@($art | Where-Object { -not $_.EndsWith(' centered') }).Count -gt 0) { throw "page $page shows a bitmap the scaled art did not set: $($art -join ', ')" }
    if ($OnPage -and (& $OnPage $hwnd)) {
      Start-Sleep -Milliseconds 800
      Save-Window $hwnd ('{0}-{1}-set' -f $Prefix, $page)
    }
    $previous = [SetupUi]::Texts($hwnd)
    [SetupUi]::Next($hwnd)
  }
  throw "$Title still open after 12 pages"
}

# Waits for the QCaret the finish page started from $Dir; throws unless the
# qcaret.exe started (events under $Name) has an explorer.exe parent and every
# process running from $Dir has the shell's token elevation; then stops them.
function Test-Launched([string] $Dir, [string] $Name) {
  $deadline = (Get-Date).AddSeconds(60)
  do {
    $running = @(Get-CimInstance Win32_Process | Where-Object { $_.ExecutablePath -and $_.ExecutablePath.StartsWith("$Dir\", [StringComparison]::OrdinalIgnoreCase) })
    if ($running.Count -gt 0) { break }
    Start-Sleep -Milliseconds 500
  } while ((Get-Date) -lt $deadline)
  if ($running.Count -eq 0) { throw "the finish page started no QCaret from $Dir within 60 s" }
  # qcaret.exe hands over to javaw.exe; give the child time to appear.
  Start-Sleep -Seconds 3
  $running = @(Get-CimInstance Win32_Process | Where-Object { $_.ExecutablePath -and $_.ExecutablePath.StartsWith("$Dir\", [StringComparison]::OrdinalIgnoreCase) })
  try {
    $starts = @(Get-Event -SourceIdentifier $Name -ErrorAction SilentlyContinue | ForEach-Object { $_.SourceEventArgs.NewEvent })
    $explorers = @($shellPid)
    foreach ($start in $starts) {
      Write-Host "$($start.ProcessName) $($start.ProcessID) started by $($start.ParentProcessID)"
      if ($start.ProcessName -eq 'explorer.exe') { $explorers += [int] $start.ProcessID }
    }
    $launches = @($starts | Where-Object { $_.ProcessName -eq 'qcaret.exe' })
    if ($launches.Count -eq 0) { throw "no qcaret.exe start was traced" }
    foreach ($launch in $launches) {
      if ($explorers -notcontains [int] $launch.ParentProcessID) { throw "qcaret.exe $($launch.ProcessID) was started by $($launch.ParentProcessID), not by explorer.exe" }
    }
    foreach ($process in $running) {
      $elevation = [SetupUi]::TokenValue($process.ProcessId, 20)
      $type = [SetupUi]::TokenValue($process.ProcessId, 18)
      $owner = Invoke-CimMethod -InputObject $process -MethodName GetOwner
      Write-Host "$($process.ExecutablePath) $($process.ProcessId) parent $($process.ParentProcessId) owner $($owner.Domain)\$($owner.User): elevation $elevation, type $type"
      if ($elevation -ne $shellElevation) { throw "$($process.ExecutablePath) runs with token elevation $elevation, the shell with $shellElevation" }
    }
  } finally {
    Unregister-Event -SourceIdentifier $Name
    Remove-Event -SourceIdentifier $Name -ErrorAction SilentlyContinue
    foreach ($process in $running) { Stop-Process -Id $process.ProcessId -Force -ErrorAction SilentlyContinue }
    foreach ($process in $running) { Wait-Process -Id $process.ProcessId -Timeout 30 -ErrorAction SilentlyContinue }
  }
  Write-Output "PASS: explorer.exe started QCaret from $Dir with the shell's token elevation $shellElevation"
}

$installed = Join-Path $env:ProgramFiles 'QCaret'
Write-Host "starting $setup"
Watch-Starts 'setup-start'
Start-Process -FilePath $setup | Out-Null
Invoke-Walk 'QCaret Setup' 'setup' {
  param($hwnd)
  [SetupUi]::Click($hwnd, 'Create a &desktop shortcut')
}
Test-Launched $installed 'setup-start'
$desktop = Join-Path ([Environment]::GetFolderPath('CommonDesktopDirectory')) 'QCaret.lnk'
if ((New-Object -ComObject WScript.Shell).CreateShortcut($desktop).TargetPath -ne "$installed\qcaret.exe") { throw "the desktop shortcut box wrote no $desktop for $installed\qcaret.exe" }
$verb = 'HKLM:\Software\Classes\*\shell\QCaret.Edit'
if (-not (Test-Path -LiteralPath $verb)) { throw "the components page left the Explorer menu unchecked: no $verb" }

# The uninstaller runs from a copy in %TEMP%; its window is found by title.
Start-Process -FilePath (Join-Path $env:ProgramFiles 'QCaret\uninstall.exe') | Out-Null
Invoke-Walk 'QCaret Uninstall' 'uninstall' $null
if (Test-Path $desktop) { throw "the uninstaller left $desktop" }
if (Test-Path -LiteralPath $verb) { throw "the uninstaller left $verb" }

# The portable walk picks Portable on the type page and a temporary folder on
# the folder page (control 1019).
$portable = Join-Path $env:RUNNER_TEMP 'QCaret Portable'
Write-Host "starting $setup"
Watch-Starts 'portable-start'
Start-Process -FilePath $setup | Out-Null
Invoke-Walk 'QCaret Setup' 'portable' {
  param($hwnd)
  if ([SetupUi]::Click($hwnd, '&Portable')) { return $true }
  if ([SetupUi]::SetText($hwnd, 1019, $portable)) { return $true }
  $false
}
if (-not (Test-Path (Join-Path $portable 'portable.txt'))) { throw "the portable install wrote no portable.txt in $portable" }
Test-Launched $portable 'portable-start'
foreach ($path in @($desktop, (Join-Path ([Environment]::GetFolderPath('Desktop')) 'QCaret.lnk'))) {
  if (Test-Path $path) { throw "the portable install wrote $path" }
}
# The stopped QCaret's DLLs stay locked briefly after it exits.
$deadline = (Get-Date).AddSeconds(30)
while ($true) {
  try {
    Remove-Item -Recurse -Force $portable
    break
  } catch {
    if ((Get-Date) -gt $deadline) {
      Get-CimInstance Win32_Process | Where-Object { $_.ExecutablePath } | ForEach-Object { Write-Host "$($_.ProcessId) $($_.ExecutablePath)" }
      throw
    }
    Start-Sleep -Milliseconds 500
  }
}

# The elevated instance: this shell is elevated already, so it starts setup
# with the switches the type page passes.
Write-Host "starting $setup /MACHINE /ELEVATED /NOEXPLORERMENU"
Watch-Starts 'elevated-start'
Start-Process -FilePath $setup -ArgumentList '/MACHINE', '/ELEVATED', '/NOEXPLORERMENU' | Out-Null
$script:elevatedPage = 0
Invoke-Walk 'QCaret Setup' 'elevated' {
  param($hwnd)
  $script:elevatedPage++
  if ($script:elevatedPage -eq 1) {
    $text = [SetupUi]::Texts($hwnd)
    if (-not $text.Contains('Choose Components')) { throw "the elevated setup opened on another page than the components page:`n$text" }
    if ([SetupUi]::BackEnabled($hwnd)) { throw 'the elevated setup left Back enabled on the components page' }
  }
  $false
}
Test-Launched $installed 'elevated-start'
if (Test-Path -LiteralPath $verb) { throw "the elevated setup wrote $verb despite /NOEXPLORERMENU" }
Start-Process -FilePath (Join-Path $installed 'uninstall.exe') -ArgumentList '/S' -Wait
$deadline = (Get-Date).AddSeconds(120)
while (Test-Path $installed) {
  if ((Get-Date) -gt $deadline) { throw "the silent uninstall left $installed" }
  Start-Sleep -Milliseconds 500
}
Write-Output 'PASS: the elevated instance opened on the components page with Back disabled, kept /NOEXPLORERMENU, and installed for all users'
Write-Output "PASS: saved $script:n installer pages in $out"
