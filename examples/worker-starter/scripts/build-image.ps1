param(
    [Parameter(Mandatory = $false)]
    [string]$GeneratorImageName,
    [Parameter(Mandatory = $false)]
    [string]$ProcessorImageName,
    [switch]$SkipTests,
    [switch]$Help
)

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
    $RootPom = Join-Path $RepoRoot "pom.xml"
}
$ProjectPom = Join-Path $ProjectRoot "pom.xml"
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
$LocalRepo = $env:MAVEN_REPO_LOCAL
if (-not $LocalRepo -or $LocalRepo.Trim().Length -eq 0) {
    if ($env:MAVEN_USER_HOME -and $env:MAVEN_USER_HOME.Trim().Length -gt 0) {
        $LocalRepo = Join-Path $env:MAVEN_USER_HOME "repository"
    } else {
        $LocalRepo = Join-Path $env:USERPROFILE ".m2\repository"
    }
}
$StaleParentDir = [System.IO.Path]::Combine($LocalRepo, 'io', 'pockethive', 'pockethive-mvp', '${revision}')
if (Test-Path $StaleParentDir) {
    Write-Host "Removing stale cached Maven metadata at $StaleParentDir"
    Remove-Item -Path $StaleParentDir -Recurse -Force
}
$MavenWrapper = Join-Path $ProjectRoot "mvnw.cmd"

$MavenCmd = $null
if (Test-Path $MavenWrapper) {
    $MavenCmd = $MavenWrapper
} elseif (Get-Command mvn -ErrorAction SilentlyContinue) {
    $MavenCmd = "mvn"
}

$InstallArgs = @()
if ($null -ne $RootPom) {
    $InstallArgs += "-f"
    $InstallArgs += $RootPom
}
$InstallArgs += @("-B", "-pl", "common/worker-sdk", "-am", "install")
$MavenArgs = @("-B", "-pl", "generator-worker,processor-worker", "-am", "package")
$DockerMavenArgs = @()
if ($PocketHiveVersion) {
    $InstallArgs += "-Drevision=$PocketHiveVersion"
    $MavenArgs += "-Drevision=$PocketHiveVersion"
    $DockerMavenArgs += "-Drevision=$PocketHiveVersion"
}
if ($SkipTests.IsPresent) {
    $InstallArgs += "-DskipTests"
    $MavenArgs += "-DskipTests"
    $DockerMavenArgs += "-DskipTests"
}

if ($null -ne $MavenCmd) {
    if ($null -ne $RootPom -and (Test-Path $RootPom)) {
        Write-Host "Installing parent and shared artifacts with $MavenCmd $($InstallArgs -join ' ')"
        Push-Location $RepoRoot
        try {
            & $MavenCmd @InstallArgs
        } finally {
            Pop-Location
        }
    } else {
        Write-Warning "Unable to locate PocketHive repository root. Skipping parent install. Please install io.pockethive:pockethive-mvp manually."
    }
    Write-Host "Running Maven build with $MavenCmd $($MavenArgs -join ' ')"
    Push-Location $ProjectRoot
    try {
        & $MavenCmd @MavenArgs
    } finally {
        Pop-Location
    }
} else {
    Write-Warning "Maven executable not found. Skipping host build and relying on the Docker multi-stage build."
}

if (-not (Get-Command docker -ErrorAction SilentlyContinue)) {
    Write-Error "Docker CLI is required to build the container images."
    exit 1
}

function Invoke-DockerBuild {
    param(
        [string]$ModuleDirectory,
        [string]$ImageName
    )

    $Dockerfile = Join-Path $ProjectRoot "$ModuleDirectory/docker/Dockerfile"
    $dockerArgString = $DockerMavenArgs -join ' '
    $dockerArgs = @(
        "--build-arg", "MAVEN_ARGS=$dockerArgString",
        "-t", $ImageName,
        "-f", $Dockerfile,
        $ProjectRoot
    )

    docker build @dockerArgs
}

Invoke-DockerBuild -ModuleDirectory "generator-worker" -ImageName $GeneratorImageName
Invoke-DockerBuild -ModuleDirectory "processor-worker" -ImageName $ProcessorImageName

Write-Host "`nBuilt images:" 
Write-Host "  Generator -> $GeneratorImageName"
Write-Host "  Processor -> $ProcessorImageName"
