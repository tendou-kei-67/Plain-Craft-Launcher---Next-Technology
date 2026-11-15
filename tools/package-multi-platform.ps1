#Requires -Version 5.1
<#
.SYNOPSIS
    Builds self-contained PCL-NT jars for Windows, Linux and macOS.

.DESCRIPTION
    Runs Maven once per target platform and copies the shaded jars to dist/.
    Each jar contains the application, its resources, Gson, JNA and the JavaFX
    runtime (classes + native libraries) of that platform, so the target machine
    only needs Java 21 or newer.

    PCL-2/ and PCL-CE/ are not part of the Maven build at all (only src/main is
    packaged), so they can never end up inside the jars or the source archive.

.PARAMETER Platform
    One or more target platforms. Defaults to all of them.

.PARAMETER JdkHome
    JDK 21+ installation directory. Defaults to a JDK found through JAVA_HOME or
    the usual install locations.

.PARAMETER Maven
    mvn.cmd to use. Defaults to mvn on PATH, MAVEN_HOME, or IntelliJ IDEA's
    bundled Maven.

.PARAMETER NoSources
    Skip the source zip (PCL-NT-<version>-sources.zip, without PCL-2/PCL-CE).

.EXAMPLE
    pwsh -File tools/package-multi-platform.ps1

.EXAMPLE
    pwsh -File tools/package-multi-platform.ps1 -Platform linux-x64,macos-arm64
#>
[CmdletBinding()]
param(
    [ValidateSet('windows-x64', 'linux-x64', 'linux-aarch64', 'macos-x64', 'macos-arm64')]
    [string[]]$Platform = @('windows-x64', 'linux-x64', 'linux-aarch64', 'macos-x64', 'macos-arm64'),

    [string]$JdkHome,

    [string]$Maven,

    [switch]$NoSources
)

$ErrorActionPreference = 'Stop'
Set-StrictMode -Version Latest

$root = Split-Path -Parent $PSScriptRoot
$dist = Join-Path $root 'dist'

function Get-JavaMajorVersion {
    param([string]$JavaExe)
    $firstLine = (cmd /c "`"$JavaExe`" -version 2>&1" | Select-Object -First 1)
    if ($firstLine -match 'version "(?:1\.)?(\d+)') {
        return [int]$Matches[1]
    }
    return 0
}

function Resolve-Jdk {
    param([string]$Preferred)

    $candidates = New-Object System.Collections.Generic.List[string]
    if ($Preferred) { $candidates.Add($Preferred) }
    if ($env:JAVA_HOME) { $candidates.Add(($env:JAVA_HOME -replace '[\\/]bin[\\/]?$', '')) }

    $searchRoots = @(
        (Join-Path $env:ProgramFiles 'Java'),
        (Join-Path $env:ProgramFiles 'Eclipse Adoptium'),
        (Join-Path $env:ProgramFiles 'Microsoft'),
        (Join-Path $env:LOCALAPPDATA 'Programs\Eclipse Adoptium'),
        (Join-Path $env:USERPROFILE '.jdks')
    )
    if (${env:ProgramFiles(x86)}) {
        $searchRoots += (Join-Path ${env:ProgramFiles(x86)} 'Java')
    }

    foreach ($searchRoot in $searchRoots) {
        if (Test-Path $searchRoot) {
            Get-ChildItem $searchRoot -Directory -ErrorAction SilentlyContinue |
                Sort-Object Name -Descending |
                ForEach-Object { $candidates.Add($_.FullName) }
        }
    }

    # Prefer an exact JDK 21 (the release this project is built and tested with),
    # fall back to any newer JDK because maven.compiler.release still targets 21.
    $fallback = $null
    foreach ($jdkCandidate in ($candidates | Select-Object -Unique)) {
        $java = Join-Path $jdkCandidate 'bin\java.exe'
        $javac = Join-Path $jdkCandidate 'bin\javac.exe'
        if ((Test-Path $java) -and (Test-Path $javac)) {
            $major = Get-JavaMajorVersion $java
            if ($major -eq 21) {
                return (Resolve-Path $jdkCandidate).Path
            }
            if ($major -gt 21 -and -not $fallback) {
                $fallback = (Resolve-Path $jdkCandidate).Path
            }
        }
    }
    if ($fallback) { return $fallback }

    throw "No JDK 21+ found. Install one or pass -JdkHome 'C:\path\to\jdk-21'."
}

function Resolve-Maven {
    param([string]$Preferred)

    if ($Preferred) {
        if (Test-Path $Preferred) { return (Resolve-Path $Preferred).Path }
        throw "Maven executable not found: $Preferred"
    }

    $command = Get-Command mvn.cmd -ErrorAction SilentlyContinue
    if ($command) { return $command.Source }

    if ($env:MAVEN_HOME) {
        $candidate = Join-Path $env:MAVEN_HOME 'bin\mvn.cmd'
        if (Test-Path $candidate) { return (Resolve-Path $candidate).Path }
    }

    $ideaBins = @()
    foreach ($ideaRoot in @((Join-Path $env:ProgramFiles 'JetBrains'), (Join-Path $env:LOCALAPPDATA 'Programs'))) {
        if (Test-Path $ideaRoot) {
            $ideaBins += Get-ChildItem $ideaRoot -Directory -Filter 'IntelliJ IDEA*' -ErrorAction SilentlyContinue |
                Sort-Object Name -Descending |
                ForEach-Object { Join-Path $_.FullName 'plugins\maven\lib\maven3\bin\mvn.cmd' }
        }
    }
    foreach ($candidate in $ideaBins) {
        if (Test-Path $candidate) { return (Resolve-Path $candidate).Path }
    }

    throw "Maven not found. Install Maven or pass -Maven 'C:\path\to\mvn.cmd'."
}

$jdk = Resolve-Jdk $JdkHome
$mvn = Resolve-Maven $Maven
$env:JAVA_HOME = $jdk

[xml]$pom = Get-Content (Join-Path $root 'pom.xml')
$version = $pom.project.version

$javafxClassifiers = @{
    'windows-x64'   = 'win'
    'linux-x64'     = 'linux'
    'linux-aarch64' = 'linux-aarch64'
    'macos-x64'     = 'mac'
    'macos-arm64'   = 'mac-aarch64'
}

$nativeLibraries = @{
    'windows-x64'   = 'glass.dll'
    'linux-x64'     = 'libglass.so'
    'linux-aarch64' = 'libglass.so'
    'macos-x64'     = 'libglass.dylib'
    'macos-arm64'   = 'libglass.dylib'
}

Write-Host "PCL-NT $version"
Write-Host "  JDK  : $jdk"
Write-Host "  Maven: $mvn"
Write-Host "  Dest : $dist"
Write-Host ''

New-Item -ItemType Directory -Force -Path $dist | Out-Null
$built = New-Object System.Collections.Generic.List[object]

foreach ($name in $Platform) {
    $classifier = $javafxClassifiers[$name]
    Write-Host "==> Building $name (JavaFX classifier: $classifier)"

    $mavenArgs = @(
        '-B',
        '--no-transfer-progress',
        '-DskipTests',
        "-Djavafx.platform=$classifier",
        "-Dpclnt.platform=$name",
        'package'
    )
    & $mvn @mavenArgs
    if ($LASTEXITCODE -ne 0) {
        throw "Maven build failed for $name (exit code $LASTEXITCODE)."
    }

    $sourceJar = Join-Path $root "target\pcl-nt-$version-$name.jar"
    if (-not (Test-Path $sourceJar)) {
        throw "Expected jar was not produced: $sourceJar"
    }

    Add-Type -AssemblyName System.IO.Compression.FileSystem
    $zip = [System.IO.Compression.ZipFile]::OpenRead($sourceJar)
    try {
        $names = $zip.Entries | ForEach-Object { $_.FullName }
        if ($names -notcontains 'io/pclnt/Main.class') {
            throw "$sourceJar does not contain io/pclnt/Main.class."
        }
        if ($names -notcontains $nativeLibraries[$name]) {
            throw "$sourceJar does not contain the $name JavaFX native library $($nativeLibraries[$name])."
        }
    }
    finally {
        $zip.Dispose()
    }

    $destJar = Join-Path $dist "PCL-NT-$version-$name.jar"
    Copy-Item $sourceJar $destJar -Force
    $item = Get-Item $destJar
    $built.Add([pscustomobject]@{
            Platform = $name
            File     = $item.Name
            SizeMB   = [math]::Round($item.Length / 1MB, 1)
        })
    Write-Host ''
}

if (-not $NoSources) {
    Write-Host '==> Building source archive (without PCL-2/PCL-CE)'
    $sourceZip = Join-Path $dist "PCL-NT-$version-sources.zip"
    if (Test-Path $sourceZip) { Remove-Item $sourceZip -Force }

    Add-Type -AssemblyName System.IO.Compression.FileSystem
    $archive = [System.IO.Compression.ZipFile]::Open($sourceZip, [System.IO.Compression.ZipArchiveMode]::Create)
    try {
        $include = @('pom.xml', 'README.md', '.gitignore', 'src', 'tools', '.run')
        foreach ($entry in $include) {
            $path = Join-Path $root $entry
            if (-not (Test-Path $path)) { continue }
            if (Test-Path $path -PathType Leaf) {
                [System.IO.Compression.ZipFileExtensions]::CreateEntryFromFile($archive, $path, $entry) | Out-Null
                continue
            }
            Get-ChildItem $path -Recurse -File | ForEach-Object {
                $relative = $_.FullName.Substring($root.Length + 1) -replace '\\', '/'
                [System.IO.Compression.ZipFileExtensions]::CreateEntryFromFile($archive, $_.FullName, $relative) | Out-Null
            }
        }
    }
    finally {
        $archive.Dispose()
    }
    $sourceItem = Get-Item $sourceZip
    Write-Host ("    {0} ({1} MB)" -f $sourceItem.Name, [math]::Round($sourceItem.Length / 1MB, 1))
    Write-Host ''
}

Write-Host 'Built artifacts:'
$built | Format-Table -AutoSize | Out-String | Write-Host
Write-Host "Output directory: $dist"
