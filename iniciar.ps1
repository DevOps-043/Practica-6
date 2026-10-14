param(
    [ValidateSet('run', 'test', 'build', 'setup', 'prepare')]
    [string]$Action = 'run'
)
$ErrorActionPreference = 'Stop'
$projectPath = $PSScriptRoot
$toolsPath = Join-Path $projectPath '.tools'
New-Item -ItemType Directory -Path $toolsPath -Force | Out-Null

function Get-VerifiedArchive([string]$Url, [string]$Destination, [string]$Hash, [string]$Algorithm) {
    Invoke-WebRequest -UseBasicParsing -Uri $Url -OutFile $Destination
    $actualHash = (Get-FileHash -LiteralPath $Destination -Algorithm $Algorithm).Hash
    if ($actualHash -ne $Hash.Trim()) { throw "La verificación del archivo descargado falló: $Destination" }
}

# Las herramientas portátiles permanecen en el repositorio, sin modificar el sistema.
$javaCommand = Get-Command java -ErrorAction SilentlyContinue
$compilerCommand = Get-Command javac -ErrorAction SilentlyContinue
$javaPath = if ($javaCommand -and $compilerCommand) { $javaCommand.Source } else { $null }
if ($javaPath) {
    $previousPreference = $ErrorActionPreference
    $ErrorActionPreference = 'Continue'
    $versionText = (& $javaPath -version 2>&1 | Out-String)
    $ErrorActionPreference = $previousPreference
    if ($versionText -notmatch 'version "(\d+)' -or [int]$Matches[1] -lt 17) { $javaPath = $null }
}
if (-not $javaPath) {
    $portableJdk = Get-ChildItem -LiteralPath $toolsPath -Directory | Where-Object Name -Like 'jdk-*' | Select-Object -First 1
    if (-not $portableJdk) {
        Write-Host 'Preparando Java 21 portátil (solo la primera vez)...'
        if ($env:PROCESSOR_ARCHITECTURE -ne 'AMD64') { throw 'El instalador portátil está preparado para Windows x64. Instala un JDK 17 o posterior para tu arquitectura.' }
        $assets = Invoke-RestMethod -Uri 'https://api.adoptium.net/v3/assets/latest/21/hotspot?architecture=x64&image_type=jdk&os=windows'
        $jdkPackage = $assets[0].binary.package
        $jdkArchive = Join-Path $toolsPath 'jdk.zip'
        Get-VerifiedArchive $jdkPackage.link $jdkArchive $jdkPackage.checksum 'SHA256'
        Expand-Archive -LiteralPath $jdkArchive -DestinationPath $toolsPath -Force
        $portableJdk = Get-ChildItem -LiteralPath $toolsPath -Directory | Where-Object Name -Like 'jdk-*' | Select-Object -First 1
    }
    $javaPath = Join-Path $portableJdk.FullName 'bin\java.exe'
}
$env:JAVA_HOME = if ($compilerCommand -and $javaCommand -and $javaPath -eq $javaCommand.Source) {
    Split-Path (Split-Path $compilerCommand.Source -Parent) -Parent
} else { Split-Path (Split-Path $javaPath -Parent) -Parent }
$env:PATH = "$(Join-Path $env:JAVA_HOME 'bin');$env:PATH"

$mavenCommand = Get-Command mvn -ErrorAction SilentlyContinue
if ($mavenCommand) { $mavenPath = $mavenCommand.Source }
else {
    $mavenVersion = '3.9.11'
    $mavenPath = Join-Path $toolsPath "apache-maven-$mavenVersion\bin\mvn.cmd"
    if (-not (Test-Path -LiteralPath $mavenPath)) {
        Write-Host 'Preparando Maven portátil (solo la primera vez)...'
        $mavenUrl = "https://repo.maven.apache.org/maven2/org/apache/maven/apache-maven/$mavenVersion/apache-maven-$mavenVersion-bin.zip"
        $mavenChecksum = ((Invoke-WebRequest -UseBasicParsing -Uri "$mavenUrl.sha512").Content).Trim()
        $mavenArchive = Join-Path $toolsPath 'maven.zip'
        Get-VerifiedArchive $mavenUrl $mavenArchive $mavenChecksum 'SHA512'
        Expand-Archive -LiteralPath $mavenArchive -DestinationPath $toolsPath -Force
    }
}
if ($Action -eq 'setup') { Write-Host 'Java y Maven listos.'; exit 0 }
Push-Location $projectPath
try {
    $mavenGoal = if ($Action -eq 'test') { 'test' } else { 'package' }
    & $mavenPath -B -ntp "-Dmaven.repo.local=$(Join-Path $toolsPath 'm2')" $mavenGoal
    if ($LASTEXITCODE -ne 0) { throw 'La compilación o las pruebas fallaron. Revisa el error mostrado arriba.' }
    if ($Action -eq 'prepare') {
        & $javaPath -jar (Join-Path $projectPath 'target\telemetria-web.jar') --init-db
        if ($LASTEXITCODE -ne 0) { throw 'No se pudo preparar SQLite. Revisa el error mostrado arriba.' }
        Write-Host 'Equipo preparado: Java, Maven, SQLite y base de datos listos. Ejecuta iniciar.cmd para abrir el servidor.'
    }
    if ($Action -eq 'run') { & $javaPath -jar (Join-Path $projectPath 'target\telemetria-web.jar') }
} finally { Pop-Location }
