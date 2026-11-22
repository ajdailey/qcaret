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
# the arguments it passes, and its -v must print the version. Both launchers of the universal zip,
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
      Where-Object { $_.CommandLine -like '*qcaret.jar*' } |
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

# Fails unless $Lines name $JavaHome, $Tree\lib\qcaret.jar, the JVM options of qcaret.exe and
# exactly $Expected as the arguments.
function Assert-Probe([string[]] $Lines, [string] $Tree, [string] $JavaHome, [string[]] $Expected) {
  $want = @("home=$JavaHome", "jar=$(Join-Path $Tree 'lib\qcaret.jar')", 'opt=-XX:MaxRAMPercentage=50')
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

$work = Join-Path $PWD 'bundle-run'
if (Test-Path $work) { Remove-Item -Recurse -Force $work }
$archive = Get-Item "dist\qcaret-*-$env:TARGET.zip"
$version = $archive.Name.Substring(7, $archive.Name.Length - 7 - "-$env:TARGET.zip".Length)
Expand-Archive -Path $archive.FullName -DestinationPath $work
$bundle = (Resolve-Path (Join-Path $work 'qcaret')).Path

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
& (Join-Path $jdk 'bin\javac.exe') -d $probeClasses 'qcaret-dist\src\test\java\io\github\ajdailey\qcaret\dist\LauncherProbe.java'
if ($LASTEXITCODE -ne 0) { throw "javac exited $LASTEXITCODE" }
$probeJar = Join-Path $work 'probe.jar'
& (Join-Path $jdk 'bin\jar.exe') --create --file $probeJar --main-class io.github.ajdailey.qcaret.dist.LauncherProbe -C $probeClasses .
if ($LASTEXITCODE -ne 0) { throw "jar exited $LASTEXITCODE" }
Test-CmdArguments $bundle 'with space'
Test-CmdArguments $bundle 'amp & pct 100%'
Test-CmdJavaOrder $bundle
Test-CmdVersion $bundle

$universal = Get-ChildItem dist -Filter 'qcaret-*.zip' |
  Where-Object { $_.Name -notmatch '-(linux|macos|windows)-' } |
  Select-Object -First 1
if (-not $universal) { throw 'no universal zip in dist' }
$universalDir = Join-Path $work 'universal'
Expand-Archive -Path $universal.FullName -DestinationPath $universalDir
Test-Launcher (Join-Path $universalDir 'qcaret\qcaret.exe') (Join-Path $jdk 'bin\javaw.exe') 'universal'
Test-Launcher (Join-Path $universalDir 'qcaret\qcaret.cmd') (Join-Path $jdk 'bin\javaw.exe') 'universal-cmd'
