# Unpacks the $env:TARGET bundle from dist\, checks that its runtime reports
# os.arch $env:EXPECTED_ARCH, then starts qcaret.exe and finds the javaw.exe it
# starts. Passes only when that child runs runtime\bin\javaw.exe from the
# bundle and the image's PE machine is $env:EXPECTED_MACHINE. An ARM64 image
# cannot run under x86 or x64 emulation, so ARM64 proves a native arm64 JVM.
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

$work = Join-Path $PWD 'bundle-run'
if (Test-Path $work) { Remove-Item -Recurse -Force $work }
$archive = Get-Item "dist\qcaret-*-$env:TARGET.zip"
Expand-Archive -Path $archive.FullName -DestinationPath $work
$bundle = (Resolve-Path (Join-Path $work 'qcaret')).Path
$exe = Join-Path $bundle 'qcaret.exe'
$javaw = Join-Path $bundle 'runtime\bin\javaw.exe'

$arch = & (Join-Path $bundle 'runtime\bin\java.exe') -XshowSettings:properties -version 2>&1 |
  ForEach-Object { "$_" } |
  Where-Object { $_ -match '^\s*os\.arch = ' } |
  ForEach-Object { ($_ -split '=', 2)[1].Trim() }
Write-Output "runtime os.arch=$arch, expected $env:EXPECTED_ARCH"
if ($arch -ne $env:EXPECTED_ARCH) { throw "runtime os.arch is $arch" }

$log = Join-Path $work 'notes.txt'
Set-Content -Path $log -Value 'mock line'
$settings = Join-Path $work 'settings'
Write-Output "qcaret.exe image: $(Get-PeMachine $exe)"
$launcher = Start-Process -FilePath $exe -ArgumentList @('-m', '--settings', "`"$settings`"", "`"$log`"") -PassThru

$child = $null
$deadline = (Get-Date).AddSeconds(60)
while (-not $child -and (Get-Date) -lt $deadline) {
  $child = Get-CimInstance Win32_Process -Filter "ParentProcessId = $($launcher.Id) AND Name = 'javaw.exe'" |
    Where-Object { $_.CommandLine -like '*qcaret.jar*' } |
    Select-Object -First 1
  if (-not $child) { Start-Sleep -Milliseconds 100 }
}
if (-not $child) { throw "qcaret.exe (pid $($launcher.Id)) started no javaw.exe running qcaret.jar within 60 s" }

$path = $child.ExecutablePath
$machine = Get-PeMachine $path
Write-Output "child pid $($child.ProcessId) of qcaret.exe pid $($launcher.Id)"
Write-Output "child image: $path"
Write-Output "child image machine: $machine, expected $env:EXPECTED_MACHINE"
Write-Output "child command line: $($child.CommandLine)"
Stop-Process -Id $child.ProcessId -Force -ErrorAction SilentlyContinue

if ($path -ine $javaw) { throw "child image is $path, expected $javaw" }
if ($machine -ne $env:EXPECTED_MACHINE) { throw "child image machine is $machine" }
Write-Output "PASS: qcaret.exe started the bundled $machine runtime ($path)"
