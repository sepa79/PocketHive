param(
    [Parameter(Mandatory = $false)]
    [string]$GeneratorImageName,
    [Parameter(Mandatory = $false)]
    [string]$ProcessorImageName,
    [switch]$SkipTests,
    [switch]$Help
)

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

$ProjectRoot = Resolve-Path "$PSScriptRoot/.."
$MavenWrapper = Join-Path $ProjectRoot "mvnw.cmd"

$MavenCmd = $null
if (Test-Path $MavenWrapper) {
    $MavenCmd = $MavenWrapper
} elseif (Get-Command mvn -ErrorAction SilentlyContinue) {
    $MavenCmd = "mvn"
}

$MavenArgs = @("-B", "-pl", "generator-worker,processor-worker", "-am", "package")
$DockerMavenArgs = ""
if ($SkipTests.IsPresent) {
    $MavenArgs += "-DskipTests"
    $DockerMavenArgs = "-DskipTests"
}

if ($null -ne $MavenCmd) {
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
    $dockerArgs = @(
        "--build-arg", "MAVEN_ARGS=$DockerMavenArgs",
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
