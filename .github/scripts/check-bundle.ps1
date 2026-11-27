# Unpacks the $env:TARGET bundle from dist\, checks that its runtime reports
# os.arch $env:EXPECTED_ARCH, has jdk.charsets, jdk.localedata, java.xml and java.management and no
# bin\keytool.exe, then starts qcaret.exe and
# finds the javaw.exe it starts. Passes only when that child runs runtime\bin\javaw.exe from the
# bundle and the image's PE machine is $env:EXPECTED_MACHINE. An ARM64 image
# cannot run under x86 or x64 emulation, so ARM64 proves a native arm64 JVM.
# javaw output is not captured, so the run uses -l and reads logs\qcaret.log: the main window
# opens, the grammars are read with 0 skipped and no unhandled failure is logged. A second launch
# then hands a file to the running instance and exits 0. qcaret.cmd then runs the same check; it
# also runs LauncherProbe in place of qcaret.jar to check the Java it picks, the JVM options and
# the arguments it passes, unquoted names as Explorer drops them and escaped ones typed at a
# prompt, none of which may run a command, and its -v must print the version. A CJK file name
# beside a decoy goes through both launchers, by ShellExecute and from a console: the decoy never
# opens. A batch file calls qcaret.cmd: its echo state holds and its pipe ends while QCaret runs.
# Both launchers of the universal zip,
# which has no runtime, then run the first check and pass when the child runs javaw.exe from
# $env:JAVA_HOME.
$ErrorActionPreference = 'Stop'

function Get-PeMachine([string] $Path) {
  $stream = [System.IO.File]::OpenRead($Path)
  try {
    $reader = New-Object System.IO.BinaryReader($stream)
    [void] $stream.Seek(0x3C, 'Begin')
    $peOffset = $reader.ReadInt32()
    [void] $stream.Seek($peOffset + 4, 'Begin')
    $machine = $reader.ReadUInt16()
  } finally {
    $stream.Dispose()
  }
  switch ($machine) {
    0x014C { 'x86' }
    0x8664 { 'x64' }
    0xAA64 { 'ARM64' }
    default { '0x{0:X4}' -f $machine }
  }
}

# Returns the log file's text, or nothing before it exists. The running JVM holds it open.
function Read-Log([string] $Path) {
  if (-not (Test-Path $Path)) { return '' }
  $stream = [System.IO.File]::Open($Path, 'Open', 'Read', 'ReadWrite,Delete')
  try {
    $reader = New-Object System.IO.StreamReader($stream)
    return $reader.ReadToEnd()
  } finally {
    $stream.Dispose()
  }
}

# Waits up to 60 s for $Text in the log file $Path.
function Wait-LogLine([string] $Path, [string] $Text) {
  $deadline = (Get-Date).AddSeconds(60)
  while ((Get-Date) -lt $deadline) {
    if ((Read-Log $Path).Contains($Text)) { return }
    Start-Sleep -Milliseconds 200
  }
}

# Starts $Exe, qcaret.exe or qcaret.cmd, on the mock text file and checks the javaw.exe child it
# starts (for qcaret.cmd, the child of its cmd.exe): its
# image must be $Javaw with PE machine $env:EXPECTED_MACHINE. Its log must show the main window
# and every grammar read. A second launch through $Javaw must hand over a file and exit 0;
# $Javaw rather than $Exe, because neither launcher reports the child's status.
# $Name names the run's settings directory.
function Test-Launcher([string] $Exe, [string] $Javaw, [string] $Name) {
  # A fresh settings directory per run, outside the runner workspace.
  $settings = Join-Path $env:LOCALAPPDATA "qcaret-ci\settings-$Name"
  if (Test-Path $settings) { Remove-Item -Recurse -Force $settings }
  $logFile = Join-Path $settings 'logs\qcaret.log'
  $jar = Join-Path (Split-Path $Exe) 'lib\qcaret.jar'
  $exeName = Split-Path -Leaf $Exe
  if ($exeName -like '*.exe') { Write-Output "$Name $exeName image: $(Get-PeMachine $Exe)" }
  $launcher = Start-Process -FilePath $Exe -ArgumentList @('-l', '--settings', "`"$settings`"", "`"$log`"") -PassThru

  $child = $null
  $deadline = (Get-Date).AddSeconds(60)
  while (-not $child -and (Get-Date) -lt $deadline) {
    $child = Get-CimInstance Win32_Process -Filter "ParentProcessId = $($launcher.Id) AND Name = 'javaw.exe'" |
      # Under load WMI has listed the child with no ExecutablePath; poll until it has one.
      Where-Object { $_.CommandLine -like '*qcaret.jar*' -and $_.ExecutablePath } |
      Select-Object -First 1
    if (-not $child) { Start-Sleep -Milliseconds 100 }
  }
  if (-not $child) { throw "$Name $exeName (pid $($launcher.Id)) started no javaw.exe running qcaret.jar within 60 s" }

  $path = $child.ExecutablePath
  $machine = Get-PeMachine $path
  Write-Output "$Name child pid $($child.ProcessId) of $exeName pid $($launcher.Id)"
  Write-Output "$Name child image: $path"
  Write-Output "$Name child image machine: $machine, expected $env:EXPECTED_MACHINE"
  Write-Output "$Name child command line: $($child.CommandLine)"

  try {
    Wait-LogLine $logFile 'Main window open'
    Wait-LogLine $logFile 'Grammars read'

    $second = Start-Process -FilePath $Javaw -ArgumentList @('-XX:MaxRAMPercentage=50', '-jar', "`"$jar`"", '--settings', "`"$settings`"", "`"$handed`"") -PassThru
    # ExitCode reads null unless the handle was taken while the process ran.
    $null = $second.Handle
    # A failed handoff opens its own window and never exits, so the wait is bounded.
    $ended = $second.WaitForExit(60000)
    if (-not $ended) { Stop-Process -Id $second.Id -Force -ErrorAction SilentlyContinue }
    Wait-LogLine $logFile 'Handoff of 1 files'
    $text = Read-Log $logFile
  } finally {
    Stop-Process -Id $child.ProcessId -Force -ErrorAction SilentlyContinue
  }
  $logs = Join-Path $settings 'logs'
  if (Test-Path $logs) { Write-Output "$Name logs: $((Get-ChildItem $logs).Name -join ', ')" }
  Write-Output "--- $Name log"
  Write-Output $text
  Write-Output '---'

  if ($path -ine $Javaw) { throw "$Name child image is $path, expected $Javaw" }
  if ($machine -ne $env:EXPECTED_MACHINE) { throw "$Name child image machine is $machine" }
  if (-not $text.Contains('Main window open')) { throw "$Name opened no main window within 60 s" }
  if (-not $text.Contains('Grammars read: 0 skipped')) { throw "$Name did not read every grammar within 60 s" }
  if (-not $ended) { throw "$Name second launch did not exit within 60 s" }
  if ($second.ExitCode -ne 0) { throw "$Name second launch exited $($second.ExitCode)" }
  if (-not $text.Contains('Handoff of 1 files taken')) { throw "$Name primary took no handoff" }
  if ($text.Contains('Unhandled failure')) { throw "$Name logged an unhandled failure" }
  if ($text.Contains('UI task failed')) { throw "$Name logged a UI task failure" }
  Write-Output "PASS: $Name $exeName started the $machine runtime $path, read the grammars and took a handoff"
}

# Runs cmd.exe /d /s /c "$Line" with $Line passed as given, standard input closed and each
# $Environment entry set, or removed when its value is $null. Returns the exit code, stdout and
# stderr, which close only when every process holding them ends, Java's included.
function Invoke-Cmd([string] $Line, [hashtable] $Environment) {
  $info = New-Object System.Diagnostics.ProcessStartInfo $env:ComSpec
  $info.Arguments = "/d /s /c `"$Line`""
  $info.UseShellExecute = $false
  $info.RedirectStandardInput = $true
  $info.RedirectStandardOutput = $true
  $info.RedirectStandardError = $true
  foreach ($key in $Environment.Keys) {
    if ($null -eq $Environment[$key]) { [void] $info.Environment.Remove($key) }
    else { $info.Environment[$key] = $Environment[$key] }
  }
  $process = [System.Diagnostics.Process]::Start($info)
  $process.StandardInput.Close()
  $stdout = $process.StandardOutput.ReadToEndAsync()
  $stderr = $process.StandardError.ReadToEndAsync()
  if (-not $process.WaitForExit(60000)) {
    Stop-Process -Id $process.Id -Force -ErrorAction SilentlyContinue
    throw "cmd.exe /c $Line did not exit within 60 s"
  }
  # javaw.exe shows a launcher error, such as an unreadable jar, in a dialog and holds the output.
  if (-not ($stdout.Wait(60000) -and $stderr.Wait(60000))) { throw "cmd.exe /c $Line started a Java that has not ended within 60 s" }
  [pscustomobject] @{ ExitCode = $process.ExitCode; Stdout = $stdout.Result; Stderr = $stderr.Result }
}

# Copies $Bundle's qcaret.cmd, with LauncherProbe as lib\qcaret.jar, into $Folder\qcaret, and the
# bundle's runtime too unless $NoRuntime. Returns the copy's qcaret folder.
function New-ProbeTree([string] $Bundle, [string] $Folder, [switch] $NoRuntime) {
  $tree = Join-Path $Folder 'qcaret'
  New-Item -ItemType Directory -Path (Join-Path $tree 'lib') -Force | Out-Null
  Copy-Item -LiteralPath (Join-Path $Bundle 'qcaret.cmd') -Destination $tree
  Copy-Item -LiteralPath $probeJar -Destination (Join-Path $tree 'lib\qcaret.jar')
  if (-not $NoRuntime) { Copy-Item -LiteralPath (Join-Path $Bundle 'runtime') -Destination $tree -Recurse }
  $tree
}

# Runs $Tree\qcaret.cmd with the raw argument text $Arguments and returns LauncherProbe's lines.
function Invoke-Probe([string] $Tree, [string] $Arguments, [hashtable] $Environment) {
  $out = Join-Path $work "probe-$([guid]::NewGuid()).txt"
  $Environment['QCARET_PROBE_OUT'] = $out
  $result = Invoke-Cmd "`"$(Join-Path $Tree 'qcaret.cmd')`" $Arguments" $Environment
  if ($result.ExitCode -ne 0) { throw "qcaret.cmd in $Tree exited $($result.ExitCode): $($result.Stderr)" }
  # qcaret.cmd returns once javaw starts; the probe writes its file after.
  $deadline = (Get-Date).AddSeconds(60)
  while (-not (Test-Path -LiteralPath $out) -and (Get-Date) -lt $deadline) { Start-Sleep -Milliseconds 100 }
  if (-not (Test-Path -LiteralPath $out)) { throw "LauncherProbe under $Tree wrote no $out within 60 s" }
  Get-Content -LiteralPath $out -Encoding utf8
}

# Fails unless $Lines name $JavaHome, $Tree\lib\qcaret.jar, the JVM options of qcaret.exe plus
# -Dqcaret.launcher=cmd, and exactly $Expected as the arguments the bootstrap reads.
function Assert-Probe([string[]] $Lines, [string] $Tree, [string] $JavaHome, [string[]] $Expected) {
  $want = @("home=$JavaHome", "jar=$(Join-Path $Tree 'lib\qcaret.jar')", 'opt=--enable-native-access=ALL-UNNAMED', 'opt=-XX:MaxRAMPercentage=50', 'opt=-Dqcaret.launcher=cmd')
  foreach ($arg in $Expected) { $want += "arg=$arg" }
  $got = $Lines -join "`n"
  if ($got -cne ($want -join "`n")) { throw "qcaret.cmd in $Tree passed`n$got`nexpected`n$($want -join "`n")" }
}

$cmdArguments = @('-m', '+3:2', 'a b.txt', 'x&y.txt', 'c^d.txt', '100%.txt', '5%x%.txt', 'bang!.txt', '(p).txt', "it's.txt", '')
# Each argument quoted, as typed at a command prompt.
$cmdLine = ($cmdArguments | ForEach-Object { "`"$_`"" }) -join ' '

# qcaret.cmd from a folder named $FolderName passes the arguments unchanged to the bundle's
# runtime, ahead of JAVA_HOME.
function Test-CmdArguments([string] $Bundle, [string] $FolderName) {
  $tree = New-ProbeTree $Bundle (Join-Path $work "cmd\$FolderName")
  $lines = Invoke-Probe $tree $cmdLine @{ JAVA_HOME = $jdk }
  Assert-Probe $lines $tree (Join-Path $tree 'runtime') $cmdArguments
  Write-Output "PASS: qcaret.cmd in '$FolderName' passed $($cmdArguments.Count) arguments to its runtime"
}

# Without a runtime folder qcaret.cmd takes JAVA_HOME, else javaw.exe on PATH, else exits 1.
function Test-CmdJavaOrder([string] $Bundle) {
  $tree = New-ProbeTree $Bundle (Join-Path $work 'cmd\no runtime') -NoRuntime
  $system = Join-Path $env:SystemRoot 'System32'
  $empty = New-Item -ItemType Directory -Path (Join-Path $work 'cmd\empty') -Force
  $lines = Invoke-Probe $tree '"a b.txt"' @{ JAVA_HOME = $jdk; PATH = $system }
  Assert-Probe $lines $tree $jdk @('a b.txt')
  $lines = Invoke-Probe $tree '"a b.txt"' @{ JAVA_HOME = $empty.FullName; PATH = "$jdk\bin;$system" }
  Assert-Probe $lines $tree $jdk @('a b.txt')
  $result = Invoke-Cmd "`"$(Join-Path $tree 'qcaret.cmd')`"" @{ JAVA_HOME = $null; PATH = $system }
  if ($result.ExitCode -ne 1) { throw "qcaret.cmd without Java exited $($result.ExitCode)" }
  if (-not $result.Stderr.Contains('QCaret needs Java 21')) { throw "qcaret.cmd without Java wrote: $($result.Stderr)" }
  Write-Output 'PASS: qcaret.cmd took JAVA_HOME, then PATH, and exited 1 without Java'
}

# qcaret.cmd -v prints the bundle's version to this console.
function Test-CmdVersion([string] $Bundle) {
  $result = Invoke-Cmd "`"$(Join-Path $Bundle 'qcaret.cmd')`" -v" @{}
  $want = "qcaret $version"
  if ($result.Stdout.Trim() -cne $want) { throw "qcaret.cmd -v printed [$($result.Stdout)] [$($result.Stderr)], expected $want" }
  Write-Output "PASS: qcaret.cmd -v printed $want"
}

# Returns LauncherProbe's lines from $Out, written after qcaret.cmd returns.
function Wait-Probe([string] $Out) {
  $deadline = (Get-Date).AddSeconds(60)
  while (-not (Test-Path -LiteralPath $Out) -and (Get-Date) -lt $deadline) { Start-Sleep -Milliseconds 100 }
  if (-not (Test-Path -LiteralPath $Out)) { throw "LauncherProbe wrote no $Out within 60 s" }
  Get-Content -LiteralPath $Out -Encoding utf8
}

# A folder holding mark.cmd, which writes marker.txt beside itself when run. An argument
# `a&mark` runs it if any cmd.exe acts on the &.
function New-MarkFolder([string] $Folder) {
  New-Item -ItemType Directory -Path $Folder -Force | Out-Null
  Set-Content -LiteralPath (Join-Path $Folder 'mark.cmd') -Value "@>`"%~dp0marker.txt`" echo ran`r`n" -NoNewline
  $Folder
}

# Fails when $Folder holds marker.txt or l.txt, which a cmd.exe acting on & | > creates.
function Assert-NoMarker([string] $Folder, [string] $Case) {
  # A command after & runs once the script returns; give it time.
  Start-Sleep -Seconds 2
  foreach ($name in @('marker.txt', 'l.txt')) {
    if (Test-Path -LiteralPath (Join-Path $Folder $name)) { throw "$Case ran a command: $name exists" }
  }
}

# Explorer passes a dropped path without quotes unless it holds a space, through ShellExecute,
# which starts cmd.exe /c ""<qcaret.cmd>" <arguments>". qcaret.cmd must pass each name intact,
# and no command after an & may run.
function Test-CmdExplorer([string] $Bundle) {
  $folder = New-MarkFolder (Join-Path $work 'cmd\explorer')
  $tree = New-ProbeTree $Bundle $folder
  $out = Join-Path $work "probe-$([guid]::NewGuid()).txt"
  $env:QCARET_PROBE_OUT = $out
  # Defined, so a cmd.exe that expands g%h%.txt changes the name.
  $env:h = 'expanded'
  try {
    $e = [char] 0xe9
    $text = "$folder\a&mark c^d.txt e!f.txt g%h%.txt (p).txt caf$e.txt `"x y&mark.txt`""
    $launch = Start-Process -FilePath (Join-Path $tree 'qcaret.cmd') -ArgumentList $text -WorkingDirectory $folder -WindowStyle Hidden -PassThru
    if (-not $launch.WaitForExit(60000)) { throw "qcaret.cmd through ShellExecute did not exit within 60 s" }
    $lines = Wait-Probe $out
  } finally {
    Remove-Item Env:QCARET_PROBE_OUT, Env:h -ErrorAction SilentlyContinue
  }
  Assert-NoMarker $folder 'qcaret.cmd through ShellExecute'
  Assert-Probe $lines $tree (Join-Path $tree 'runtime') @("$folder\a&mark", 'c^d.txt', 'e!f.txt', 'g%h%.txt', '(p).txt', "caf$e.txt", 'x y&mark.txt')
  Write-Output 'PASS: qcaret.cmd through ShellExecute passed unquoted & ^ ! % names intact and ran no command'
}

# Runs $Text as a line typed at a command prompt in $Folder: cmd.exe without /c reads it from
# standard input. Returns LauncherProbe's lines.
function Invoke-Prompt([string] $Folder, [string] $Text) {
  $out = Join-Path $work "probe-$([guid]::NewGuid()).txt"
  $info = New-Object System.Diagnostics.ProcessStartInfo $env:ComSpec
  # Without /q: under /q, echo on has no effect and qcaret.cmd refuses to run.
  $info.Arguments = '/d'
  $info.UseShellExecute = $false
  $info.WorkingDirectory = $Folder
  $info.RedirectStandardInput = $true
  $info.RedirectStandardOutput = $true
  $info.RedirectStandardError = $true
  $info.Environment['QCARET_PROBE_OUT'] = $out
  $process = [System.Diagnostics.Process]::Start($info)
  $stdout = $process.StandardOutput.ReadToEndAsync()
  $stderr = $process.StandardError.ReadToEndAsync()
  $process.StandardInput.Write("$Text`r`nexit`r`n")
  $process.StandardInput.Close()
  if (-not $process.WaitForExit(60000)) {
    Stop-Process -Id $process.Id -Force -ErrorAction SilentlyContinue
    throw "cmd.exe reading $Text did not exit within 60 s"
  }
  if (-not ($stdout.Wait(60000) -and $stderr.Wait(60000))) { throw "cmd.exe reading $Text started a Java that has not ended within 60 s" }
  if ($stderr.Result) { Write-Output "prompt stderr: $($stderr.Result)" }
  Wait-Probe $out
}

# At a command prompt, escaped and quoted arguments reach Java as cmd.exe passed them to
# qcaret.cmd, and nothing in them runs.
function Test-CmdPrompt([string] $Bundle) {
  $folder = New-MarkFolder (Join-Path $work 'cmd\prompt & 100%')
  $tree = New-ProbeTree $Bundle $folder
  $script = "`"$(Join-Path $tree 'qcaret.cmd')`""
  $lines = Invoke-Prompt $folder "$script a^&mark c^^d.txt e!f.txt g%q9z%.txt i^|mark k^>l.txt `"x&y.txt`" (p).txt"
  Assert-NoMarker $folder 'qcaret.cmd at a prompt'
  Assert-Probe $lines $tree (Join-Path $tree 'runtime') @('a&mark', 'c^d.txt', 'e!f.txt', 'g%q9z%.txt', 'i|mark', 'k>l.txt', 'x&y.txt', '(p).txt')
  $lines = Invoke-Prompt $folder "$script $cmdLine"
  Assert-Probe $lines $tree (Join-Path $tree 'runtime') $cmdArguments
  Write-Output 'PASS: qcaret.cmd at a prompt passed escaped & ^ | > and quoted arguments intact and ran no command'
}

# Returns the javaw.exe running qcaret.jar that is not in $Before, waiting up to 60 s.
function Wait-NewJavaw([int[]] $Before) {
  $deadline = (Get-Date).AddSeconds(60)
  while ((Get-Date) -lt $deadline) {
    $new = Get-CimInstance Win32_Process -Filter "Name = 'javaw.exe'" |
      Where-Object { $_.CommandLine -like '*qcaret.jar*' -and $_.ExecutablePath -and $Before -notcontains [int] $_.ProcessId } |
      Select-Object -First 1
    if ($new) { return $new }
    Start-Sleep -Milliseconds 100
  }
  throw 'no new javaw.exe running qcaret.jar within 60 s'
}

function Get-JavawIds { @(Get-CimInstance Win32_Process -Filter "Name = 'javaw.exe'" | ForEach-Object { [int] $_.ProcessId }) }

# A name outside the ANSI code page, beside a decoy its ? form matches as a wildcard, through
# each launcher by ShellExecute and from a console (a UTF-8 batch file under code page 65001).
# qcaret.exe hands Java ??.txt: unquoted, the Java launcher expands it and QCaret opens no file;
# quoted, QCaret opens nothing and says so. qcaret.cmd passes the name intact. The decoy never
# opens, and every case shows its message or opens the file.
function Test-UnicodeNames([string] $Bundle) {
  $folder = Join-Path $work 'unicode'
  New-Item -ItemType Directory -Path $folder -Force | Out-Null
  $name = "$([char]0x65E5)$([char]0x672C).txt"
  $path = Join-Path $folder $name
  Set-Content -LiteralPath $path -Value 'mock line'
  Set-Content -LiteralPath (Join-Path $folder 'zz.txt') -Value 'mock line'
  $lossy = Join-Path $folder '??.txt'
  $cases = @(
    @{ Exe = 'qcaret.exe'; Arg = $name; Want = 'No file named at startup was opened: Java expanded ??.txt as a wildcard' },
    @{ Exe = 'qcaret.exe'; Arg = "`"$path`""; Want = "Not opened: $lossy." },
    @{ Exe = 'qcaret.cmd'; Arg = $name; Want = "Command-line file: $path" },
    @{ Exe = 'qcaret.cmd'; Arg = "`"$path`""; Want = "Command-line file: $path" }
  )
  $n = 0
  foreach ($case in $cases) {
    foreach ($via in @('ShellExecute', 'console')) {
      $n++
      $settings = Join-Path $env:LOCALAPPDATA "qcaret-ci\unicode-$n"
      if (Test-Path $settings) { Remove-Item -Recurse -Force $settings }
      $logFile = Join-Path $settings 'logs\qcaret.log'
      $exe = Join-Path $Bundle $case.Exe
      $line = "-l -d 2 --settings `"$settings`" $($case.Arg)"
      $before = Get-JavawIds
      if ($via -eq 'ShellExecute') {
        Start-Process -FilePath $exe -ArgumentList $line -WorkingDirectory $folder | Out-Null
      } else {
        $bat = Join-Path $work "unicode-$n.cmd"
        [System.IO.File]::WriteAllText($bat, "@chcp 65001 >nul`r`n@`"$exe`" $line`r`n", (New-Object System.Text.UTF8Encoding $false))
        # Not -Wait: it waits for every descendant, javaw included.
        $console = Start-Process -FilePath $env:ComSpec -ArgumentList @('/d', '/c', "`"$bat`"") -WorkingDirectory $folder -NoNewWindow -PassThru
        if (-not $console.WaitForExit(60000)) { throw "unicode-$n.cmd did not exit within 60 s" }
      }
      $child = Wait-NewJavaw $before
      try {
        Wait-LogLine $logFile 'Main window open'
        Wait-LogLine $logFile $case.Want
        $text = Read-Log $logFile
      } finally {
        Stop-Process -Id $child.ProcessId -Force -ErrorAction SilentlyContinue
      }
      $label = "$($case.Exe) $via $($case.Arg)"
      if (-not $text.Contains($case.Want)) { throw "$label`: the log lacks [$($case.Want)]:`n$text" }
      if ($text.Contains('zz.txt')) { throw "$label named the decoy zz.txt:`n$text" }
      Write-Output "PASS: $label`: $($case.Want)"
    }
  }
}

# A qcaret.exe launch whose name the Java launcher expanded, while an instance runs: the launch
# hands nothing over and opens its own window, which shows the message.
function Test-UnicodeHandoff([string] $Bundle) {
  $folder = Join-Path $work 'unicode'
  $name = "$([char]0x65E5)$([char]0x672C).txt"
  $settings = Join-Path $env:LOCALAPPDATA 'qcaret-ci\unicode-handoff'
  if (Test-Path $settings) { Remove-Item -Recurse -Force $settings }
  $logs = Join-Path $settings 'logs'
  $javaw = Join-Path $Bundle 'runtime\bin\javaw.exe'
  $jar = Join-Path $Bundle 'lib\qcaret.jar'
  $before = Get-JavawIds
  Start-Process -FilePath $javaw -ArgumentList @('-jar', "`"$jar`"", '-l', '--settings', "`"$settings`"") | Out-Null
  $primary = Wait-NewJavaw $before
  $second = $null
  try {
    Wait-LogLine (Join-Path $logs 'qcaret.log') 'Main window open'
    $before = Get-JavawIds
    Start-Process -FilePath (Join-Path $Bundle 'qcaret.exe') -ArgumentList "-l -d 2 --settings `"$settings`" $name" -WorkingDirectory $folder | Out-Null
    $second = Wait-NewJavaw $before
    $secondLog = Join-Path $logs "qcaret-$($second.ProcessId).log"
    Wait-LogLine $secondLog 'Main window open'
    Wait-LogLine $secondLog 'No file named at startup was opened'
    $text = Read-Log $secondLog
    $primaryText = Read-Log (Join-Path $logs 'qcaret.log')
  } finally {
    if ($second) { Stop-Process -Id $second.ProcessId -Force -ErrorAction SilentlyContinue }
    Stop-Process -Id $primary.ProcessId -Force -ErrorAction SilentlyContinue
  }
  if (-not $text.Contains('No file named at startup was opened')) { throw "the second launch showed no message:`n$text" }
  if (-not $text.Contains('Main window open')) { throw "the second launch opened no window:`n$text" }
  if ($primaryText.Contains('Handoff of')) { throw "the second launch handed over to the running instance:`n$primaryText" }
  if ($text.Contains('zz.txt') -or $primaryText.Contains('zz.txt')) { throw "a launch named the decoy zz.txt:`n$text" }
  Write-Output 'PASS: a qcaret.exe launch with an expanded name beside a running instance opened its own window with its message and no file'
}

# A batch file calls qcaret.cmd with its output captured through a pipe. The caller's echo state
# holds after the call, and the pipe ends once QCaret's window is open, with QCaret still running.
function Test-CmdCall([string] $Bundle) {
  $folder = Join-Path $work 'call'
  New-Item -ItemType Directory -Path $folder -Force | Out-Null
  foreach ($echo in @('on', 'off')) {
    $settings = Join-Path $env:LOCALAPPDATA "qcaret-ci\call-$echo"
    if (Test-Path $settings) { Remove-Item -Recurse -Force $settings }
    $bat = Join-Path $folder "call-$echo.bat"
    $text = ''
    if ($echo -eq 'off') { $text += "@echo off`r`n" }
    $text += "call `"$(Join-Path $Bundle 'qcaret.cmd')`" -l --settings `"$settings`" `"$handed`"`r`nrem after-marker`r`necho`r`n"
    [System.IO.File]::WriteAllText($bat, $text, (New-Object System.Text.ASCIIEncoding))
    $info = New-Object System.Diagnostics.ProcessStartInfo $env:ComSpec
    $info.Arguments = "/d /c `"$bat`""
    $info.WorkingDirectory = $folder
    $info.UseShellExecute = $false
    $info.RedirectStandardOutput = $true
    $info.RedirectStandardError = $true
    $before = Get-JavawIds
    $process = [System.Diagnostics.Process]::Start($info)
    $stdout = $process.StandardOutput.ReadToEndAsync()
    $stderr = $process.StandardError.ReadToEndAsync()
    $child = Wait-NewJavaw $before
    try {
      if (-not $process.WaitForExit(60000)) { throw "call-$echo.bat did not exit within 60 s" }
      $ended = $stdout.Wait(60000) -and $stderr.Wait(60000)
      $running = -not (Get-Process -Id $child.ProcessId -ErrorAction SilentlyContinue).HasExited
      # The handles close in the turn that opens the window; the log line follows on a later turn.
      Wait-LogLine (Join-Path $settings 'logs\qcaret.log') 'Main window open'
      $log = Read-Log (Join-Path $settings 'logs\qcaret.log')
    } finally {
      Stop-Process -Id $child.ProcessId -Force -ErrorAction SilentlyContinue
    }
    if (-not $ended) { throw "call-$echo.bat: the pipe did not end within 60 s of the call" }
    if (-not $running) { throw "call-$echo.bat: QCaret exited before the pipe ended:`n$log" }
    if (-not $log.Contains('Main window open')) { throw "call-$echo.bat: QCaret opened no window:`n$log" }
    $out = $stdout.Result
    $echoed = $out.Contains('>rem after-marker')
    if (($echo -eq 'on') -ne $echoed) { throw "call-$echo.bat: echo is not $echo after the call:`n$out" }
    Write-Output "PASS: call from a batch with echo $echo kept it $echo, and the pipe ended while QCaret ran"
  }
}

$work = Join-Path $PWD 'bundle-run'
if (Test-Path $work) { Remove-Item -Recurse -Force $work }
$archive = Get-Item "dist\qcaret-*-$env:TARGET.zip"
$version = $archive.Name.Substring(7, $archive.Name.Length - 7 - "-$env:TARGET.zip".Length)
# QCARET_HOME names an installed tree to check in place of the zip; the universal zip is then skipped.
if ($env:QCARET_HOME) {
  New-Item -ItemType Directory -Path $work | Out-Null
  $bundle = (Resolve-Path $env:QCARET_HOME).Path
} else {
  Expand-Archive -Path $archive.FullName -DestinationPath $work
  $bundle = (Resolve-Path (Join-Path $work 'qcaret')).Path
}

$arch = & (Join-Path $bundle 'runtime\bin\java.exe') -XshowSettings:properties -version 2>&1 |
  ForEach-Object { "$_" } |
  Where-Object { $_ -match '^\s*os\.arch = ' } |
  ForEach-Object { ($_ -split '=', 2)[1].Trim() }
Write-Output "runtime os.arch=$arch, expected $env:EXPECTED_ARCH"
if ($arch -ne $env:EXPECTED_ARCH) { throw "runtime os.arch is $arch" }
$modules = & (Join-Path $bundle 'runtime\bin\java.exe') --list-modules
if (-not ($modules -match '^jdk\.charsets@')) { throw 'runtime lacks jdk.charsets' }
if (-not ($modules -match '^jdk\.localedata@')) { throw 'runtime lacks jdk.localedata' }
if (-not ($modules -match '^java\.xml@')) { throw 'runtime lacks java.xml' }
if (-not ($modules -match '^java\.management@')) { throw 'runtime lacks java.management' }
if (Test-Path (Join-Path $bundle 'runtime\bin\keytool.exe')) { throw 'runtime holds keytool.exe' }

$log = Join-Path $work 'notes.txt'
Set-Content -Path $log -Value 'mock line'
$handed = Join-Path $work 'handed.txt'
Set-Content -Path $handed -Value 'mock line'
Test-Launcher (Join-Path $bundle 'qcaret.exe') (Join-Path $bundle 'runtime\bin\javaw.exe') 'bundle'
Test-Launcher (Join-Path $bundle 'qcaret.cmd') (Join-Path $bundle 'runtime\bin\javaw.exe') 'bundle-cmd'

$jdk = (Resolve-Path $env:JAVA_HOME).Path
$probeClasses = Join-Path $work 'probe-classes'
$bundleJar = Join-Path $bundle 'lib\qcaret.jar'
& (Join-Path $jdk 'bin\javac.exe') -cp $bundleJar -d $probeClasses 'qcaret-dist\src\test\java\io\github\ajdailey\qcaret\dist\LauncherProbe.java'
if ($LASTEXITCODE -ne 0) { throw "javac exited $LASTEXITCODE" }
# The probe reads the arguments through the bundle's own CmdArguments.
Push-Location $probeClasses
try { & (Join-Path $jdk 'bin\jar.exe') --extract --file $bundleJar 'io/github/ajdailey/qcaret/bootstrap/CmdArguments.class' }
finally { Pop-Location }
if ($LASTEXITCODE -ne 0) { throw "jar --extract exited $LASTEXITCODE" }
$probeJar = Join-Path $work 'probe.jar'
& (Join-Path $jdk 'bin\jar.exe') --create --file $probeJar --main-class io.github.ajdailey.qcaret.dist.LauncherProbe -C $probeClasses .
if ($LASTEXITCODE -ne 0) { throw "jar exited $LASTEXITCODE" }
Test-CmdArguments $bundle 'with space'
Test-CmdArguments $bundle 'amp & pct 100%'
Test-CmdJavaOrder $bundle
Test-CmdVersion $bundle
Test-CmdExplorer $bundle
Test-CmdPrompt $bundle
Test-UnicodeNames $bundle
Test-UnicodeHandoff $bundle
Test-CmdCall $bundle
if ($env:QCARET_HOME) { return }

$universal = Get-ChildItem dist -Filter 'qcaret-*.zip' |
  Where-Object { $_.Name -notmatch '-(linux|macos|windows)-' } |
  Select-Object -First 1
if (-not $universal) { throw 'no universal zip in dist' }
$universalDir = Join-Path $work 'universal'
Expand-Archive -Path $universal.FullName -DestinationPath $universalDir
Test-Launcher (Join-Path $universalDir 'qcaret\qcaret.exe') (Join-Path $jdk 'bin\javaw.exe') 'universal'
Test-Launcher (Join-Path $universalDir 'qcaret\qcaret.cmd') (Join-Path $jdk 'bin\javaw.exe') 'universal-cmd'
