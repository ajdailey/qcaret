# Unpacks the $env:TARGET bundle from dist\, checks that its runtime reports
# os.arch $env:EXPECTED_ARCH and has jdk.charsets and java.xml, then starts qcaret.exe and
# finds the javaw.exe it starts. Passes only when that child runs runtime\bin\javaw.exe from the
# bundle and the image's PE machine is $env:EXPECTED_MACHINE. An ARM64 image
# cannot run under x86 or x64 emulation, so ARM64 proves a native arm64 JVM.
# Then starts the universal zip's qcaret.exe, which has no runtime, and passes
# when its child runs javaw.exe from $env:JAVA_HOME.
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

# Starts $Exe on the mock text file and checks the javaw.exe child it starts: its
# image must be $Javaw with PE machine $env:EXPECTED_MACHINE. $Name names the
# run's settings directory.
function Test-Launcher([string] $Exe, [string] $Javaw, [string] $Name) {
  $settings = Join-Path $work "settings-$Name"
  Write-Output "$Name qcaret.exe image: $(Get-PeMachine $Exe)"
  $launcher = Start-Process -FilePath $Exe -ArgumentList @('-m', '--settings', "`"$settings`"", "`"$log`"") -PassThru

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
  Stop-Process -Id $child.ProcessId -Force -ErrorAction SilentlyContinue

  if ($path -ine $Javaw) { throw "$Name child image is $path, expected $Javaw" }
  if ($machine -ne $env:EXPECTED_MACHINE) { throw "$Name child image machine is $machine" }
  Write-Output "PASS: $Name qcaret.exe started the $machine runtime $path"
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
if (-not ($modules -match '^java\.xml@')) { throw 'runtime lacks java.xml' }

$log = Join-Path $work 'notes.txt'
Set-Content -Path $log -Value 'mock line'
Test-Launcher (Join-Path $bundle 'qcaret.exe') (Join-Path $bundle 'runtime\bin\javaw.exe') 'bundle'

$universal = Get-ChildItem dist -Filter 'qcaret-*.zip' |
  Where-Object { $_.Name -notmatch '-(linux|macos|windows)-' } |
  Select-Object -First 1
if (-not $universal) { throw 'no universal zip in dist' }
$universalDir = Join-Path $work 'universal'
Expand-Archive -Path $universal.FullName -DestinationPath $universalDir
$jdk = (Resolve-Path $env:JAVA_HOME).Path
Test-Launcher (Join-Path $universalDir 'qcaret\qcaret.exe') (Join-Path $jdk 'bin\javaw.exe') 'universal'
