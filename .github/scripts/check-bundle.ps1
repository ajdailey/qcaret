# Unpacks the $env:TARGET bundle from dist\, checks that its runtime reports
# os.arch $env:EXPECTED_ARCH and has jdk.charsets, jdk.localedata, java.xml and java.management, then starts qcaret.exe and
# finds the javaw.exe it starts. Passes only when that child runs runtime\bin\javaw.exe from the
# bundle and the image's PE machine is $env:EXPECTED_MACHINE. An ARM64 image
# cannot run under x86 or x64 emulation, so ARM64 proves a native arm64 JVM.
# javaw output is not captured, so the run uses -l and reads logs\qcaret.log: the main window
# opens, the grammars are read with 0 skipped and no unhandled failure is logged. A second launch
# then hands a file to the running instance and exits 0. The same run follows with the universal
# zip's qcaret.exe, which has no runtime, and passes when its child runs javaw.exe from
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

# Starts $Exe on the mock text file and checks the javaw.exe child it starts: its
# image must be $Javaw with PE machine $env:EXPECTED_MACHINE. Its log must show the main window
# and every grammar read. A second launch through $Javaw must hand over a file and exit 0;
# $Javaw rather than $Exe, because the launch4j wrapper does not report the child's status.
# $Name names the run's settings directory.
function Test-Launcher([string] $Exe, [string] $Javaw, [string] $Name) {
  $settings = Join-Path $work "settings-$Name"
  $logFile = Join-Path $settings 'logs\qcaret.log'
  $jar = Join-Path (Split-Path $Exe) 'lib\qcaret.jar'
  Write-Output "$Name qcaret.exe image: $(Get-PeMachine $Exe)"
  $launcher = Start-Process -FilePath $Exe -ArgumentList @('-l', '--settings', "`"$settings`"", "`"$log`"") -PassThru

  $child = $null
  $deadline = (Get-Date).AddSeconds(60)
  while (-not $child -and (Get-Date) -lt $deadline) {
    $child = Get-CimInstance Win32_Process -Filter "ParentProcessId = $($launcher.Id) AND Name = 'javaw.exe'" |
      Where-Object { $_.CommandLine -like '*qcaret.jar*' } |
      Select-Object -First 1
    if (-not $child) { Start-Sleep -Milliseconds 100 }
  }
  if (-not $child) { throw "$Name qcaret.exe (pid $($launcher.Id)) started no javaw.exe running qcaret.jar within 60 s" }

  $path = $child.ExecutablePath
  $machine = Get-PeMachine $path
  Write-Output "$Name child pid $($child.ProcessId) of qcaret.exe pid $($launcher.Id)"
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
  Write-Output "PASS: $Name qcaret.exe started the $machine runtime $path, read the grammars and took a handoff"
}

$work = Join-Path $PWD 'bundle-run'
if (Test-Path $work) { Remove-Item -Recurse -Force $work }
$archive = Get-Item "dist\qcaret-*-$env:TARGET.zip"
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

$log = Join-Path $work 'notes.txt'
Set-Content -Path $log -Value 'mock line'
$handed = Join-Path $work 'handed.txt'
Set-Content -Path $handed -Value 'mock line'
Test-Launcher (Join-Path $bundle 'qcaret.exe') (Join-Path $bundle 'runtime\bin\javaw.exe') 'bundle'

$universal = Get-ChildItem dist -Filter 'qcaret-*.zip' |
  Where-Object { $_.Name -notmatch '-(linux|macos|windows)-' } |
  Select-Object -First 1
if (-not $universal) { throw 'no universal zip in dist' }
$universalDir = Join-Path $work 'universal'
Expand-Archive -Path $universal.FullName -DestinationPath $universalDir
$jdk = (Resolve-Path $env:JAVA_HOME).Path
Test-Launcher (Join-Path $universalDir 'qcaret\qcaret.exe') (Join-Path $jdk 'bin\javaw.exe') 'universal'
