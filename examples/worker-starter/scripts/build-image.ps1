param(
    [Parameter(Mandatory = $false)]
    [string]$GeneratorImageName,
    [Parameter(Mandatory = $false)]
    [string]$ProcessorImageName,
    [switch]$SkipTests,
    [switch]$Help
)

Set-StrictMode -Version Latest
$ErrorActionPreference = 'Stop'

function Resolve-RepoRoot {
    param([string]$StartPath)

    try {
        $resolvedStart = (Resolve-Path $StartPath).Path
    } catch {
        return $null
    }

    $gitCmd = Get-Command git -ErrorAction SilentlyContinue
    if ($gitCmd) {
        $repoRoot = & $gitCmd.Path -C $resolvedStart rev-parse --show-toplevel 2>$null
        if ($LASTEXITCODE -eq 0 -and $repoRoot) {
            try {
                return (Resolve-Path $repoRoot).Path
            } catch {
                return $null
            }
        }
    }

    $dir = $resolvedStart
    while ($true) {
        $pomPath = Join-Path $dir 'pom.xml'
        if (Test-Path $pomPath) {
            if (Select-String -Path $pomPath -Pattern '<artifactId>pockethive-mvp</artifactId>' -SimpleMatch -Quiet) {
                return $dir
            }
        }

        $parent = Split-Path $dir -Parent
        if ([string]::IsNullOrEmpty($parent) -or $parent -eq $dir) {
            break
        }
        $dir = $parent
    }

    return $null
}

function Show-Help {
    Write-Host @"
Usage: build-image.ps1 -GeneratorImageName <generator-image> -ProcessorImageName <processor-image> [-SkipTests]

Builds the sample generator and processor worker container images.

Parameters:
  -GeneratorImageName <generator-image>   Required. Repository/tag for the generator worker image.
  -ProcessorImageName <processor-image>   Required. Repository/tag for the processor worker image.
  -SkipTests                              Optional. Skip Maven tests during the build.
  -Help                                   Show this help message.

Examples:
  ./scripts/build-image.ps1 -GeneratorImageName my-org/gen:local -ProcessorImageName my-org/proc:local
  ./scripts/build-image.ps1 -GeneratorImageName my-org/gen:local -ProcessorImageName my-org/proc:local -SkipTests
"@
}

if ($Help.IsPresent) {
    Show-Help
    exit 0
}

if (-not $PSBoundParameters.ContainsKey('GeneratorImageName') -or -not $PSBoundParameters.ContainsKey('ProcessorImageName')) {
    Show-Help
    exit 1
}

$ProjectRoot = (Resolve-Path "$PSScriptRoot/.." ).Path
$RepoRoot = Resolve-RepoRoot -StartPath $ProjectRoot
$RootPom = $null
if ($null -ne $RepoRoot) {
    $RootPom = Join-Path $RepoRoot 'pom.xml'
}

$ProjectPom = Join-Path $ProjectRoot 'pom.xml'
$PocketHiveVersion = $null
if (Test-Path $ProjectPom) {
    $match = Select-String -Path $ProjectPom -Pattern '<pockethive\.version>(.+)</pockethive\.version>' -AllMatches | Select-Object -First 1
    if ($match -and $match.Matches.Count -gt 0) {
        $PocketHiveVersion = $match.Matches[0].Groups[1].Value.Trim()
    }
}
if (-not $PocketHiveVersion) {
    Write-Warning "Unable to determine PocketHive version from $ProjectPom."
}

function Install-ParentPlaceholder {
    if (-not $MavenCmd -or -not $RootPom -or -not (Test-Path $RootPom)) {
        return
    }

    $installArgs = @(
        '-B',
        'install:install-file',
        "-Dfile=$RootPom",
        '-DgroupId=io.pockethive',
        '-DartifactId=pockethive-mvp',
        '-Dversion=${revision}',
        '-Dpackaging=pom'
    )

    Write-Host "Installing PocketHive parent placeholder with $MavenCmd $($installArgs -join ' ')"
    Push-Location $RepoRoot
    try {
        & $MavenCmd @installArgs
    } finally {
        Pop-Location
    }
}

$MavenWrapper = Join-Path $ProjectRoot 'mvnw.cmd'
$MavenCmd = $null
if (Test-Path $MavenWrapper) {
    $MavenCmd = $MavenWrapper
} elseif (Get-Command mvn -ErrorAction SilentlyContinue) {
    $MavenCmd = 'mvn'
}

$ParentInstallArgs = @()
$SdkInstallArgs = @()
$ModuleBuildArgs = @('-B', '-pl', 'generator-worker,processor-worker', '-am', 'package')

if ($RootPom) {
    $ParentInstallArgs += @('-f', $RootPom)
    $SdkInstallArgs += @('-f', $RootPom)
}

$ParentInstallArgs += @('-B', '-N', 'install')
$SdkInstallArgs += @('-B', '-pl', 'common/worker-sdk', '-am', 'install')

if ($PocketHiveVersion) {
    $ParentInstallArgs += "-Drevision=$PocketHiveVersion"
    $SdkInstallArgs += "-Drevision=$PocketHiveVersion"
    $ModuleBuildArgs += "-Drevision=$PocketHiveVersion"
}

if ($SkipTests.IsPresent) {
    $ParentInstallArgs += '-DskipTests'
    $SdkInstallArgs += '-DskipTests'
    $ModuleBuildArgs += '-DskipTests'
}

if ($MavenCmd) {
    if ($RootPom -and (Test-Path $RootPom)) {
        Write-Host "Installing PocketHive parent POM with $MavenCmd $($ParentInstallArgs -join ' ')"
        Push-Location $RepoRoot
        try {
            & $MavenCmd @ParentInstallArgs
        } finally {
            Pop-Location
        }
        Install-ParentPlaceholder

        Write-Host "Installing Worker SDK dependencies with $MavenCmd $($SdkInstallArgs -join ' ')"
        Push-Location $RepoRoot
        try {
            & $MavenCmd @SdkInstallArgs
        } finally {
            Pop-Location
        }
    } else {
        Write-Warning "Unable to locate PocketHive repository root. Skipping parent install. Please install io.pockethive:pockethive-mvp manually."
    }

    Write-Host "Running Maven build with $MavenCmd $($ModuleBuildArgs -join ' ')"
    Push-Location $ProjectRoot
    try {
        & $MavenCmd @ModuleBuildArgs
    } finally {
        Pop-Location
    }
} else {
    Write-Error 'Maven executable not found. Install Maven or use the bundled wrapper before building images.'
    exit 1
}

if (-not (Get-Command docker -ErrorAction SilentlyContinue)) {
    Write-Error 'Docker CLI is required to build the container images.'
    exit 1
}

function Get-BuiltJar {
    param(
        [string]$ModuleDirectory
    )

    $moduleRoot = Join-Path $ProjectRoot $ModuleDirectory
    $targetDir = Join-Path $moduleRoot 'target'

    if (-not (Test-Path $targetDir)) {
        throw "Expected build output not found: $targetDir. Run the script without -SkipTests (or build with Maven manually) before packaging the image."
    }

    $candidates = Get-ChildItem -Path $targetDir -Filter '*.jar' -File | Sort-Object LastWriteTime -Descending
    if (-not $candidates -or $candidates.Count -eq 0) {
        throw "No JAR artifacts found in $targetDir. Ensure the Maven build completed successfully."
    }

    $preferred = $candidates | Where-Object { $_.Name -notlike '*-original.jar' -and $_.Name -notlike '*-plain.jar' } | Select-Object -First 1
    if (-not $preferred) {
        $preferred = $candidates | Select-Object -First 1
    }

    if (-not $preferred) {
        throw "Unable to determine which JAR to package from $targetDir."
    }

    $relative = [System.IO.Path]::GetRelativePath($moduleRoot, $preferred.FullName)
    if ([string]::IsNullOrWhiteSpace($relative) -or $relative.StartsWith('..')) {
        throw "Unable to derive a relative path for $($preferred.FullName)"
    }

    return $relative.Replace('\', '/')
}

function Invoke-DockerBuild {
    param(
        [string]$ModuleDirectory,
        [string]$ImageName
    )

    $Dockerfile = Join-Path $ProjectRoot "$ModuleDirectory/docker/Dockerfile"
    $jarRelativePath = Get-BuiltJar -ModuleDirectory $ModuleDirectory
    $dockerArgs = @(
        '--build-arg', "APP_JAR=$jarRelativePath",
        '-t', $ImageName,
        '-f', $Dockerfile,
        (Join-Path $ProjectRoot $ModuleDirectory)
    )

    docker build @dockerArgs
}

Invoke-DockerBuild -ModuleDirectory 'generator-worker' -ImageName $GeneratorImageName
Invoke-DockerBuild -ModuleDirectory 'processor-worker' -ImageName $ProcessorImageName

Write-Host "`nBuilt images:"
Write-Host "  Generator -> $GeneratorImageName"
Write-Host "  Processor -> $ProcessorImageName"
