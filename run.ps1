<#
.SYNOPSIS
  Compila, instala y abre LinguaReader en un emulador o móvil Android con un solo comando.

.DESCRIPTION
  1. Localiza un JDK 17+ de 64 bits (prioriza el JBR de Android Studio).
  2. Localiza el Android SDK y escribe local.properties.
  3. Instala lo que falte del SDK (platform-tools, emulador, imagen de sistema).
  4. Usa un móvil conectado por USB o arranca (y si hace falta crea) un emulador.
  5. Opcionalmente ejecuta los tests.
  6. Compila e instala el APK de depuración y abre la app.

.EXAMPLE
  .\run.ps1                 # lo normal: emulador + instalar + abrir
  .\run.ps1 -Tests          # además ejecuta los tests JVM (:core:test)
  .\run.ps1 -AllTests       # además los tests instrumentados en el dispositivo
  .\run.ps1 -Reset          # borra los datos de la app (progreso, vocabulario, ajustes)
  .\run.ps1 -Clean          # compilación limpia
  .\run.ps1 -Avd Otro_AVD   # usar un emulador concreto
  .\run.ps1 -Headless       # emulador sin ventana
  .\run.ps1 -Supertonic     # además instala el modelo Supertonic 3 en el dispositivo (descarga ~398 MB la 1.ª vez)
  .\run.ps1 -Serial emulator-5556   # usar un dispositivo concreto si hay varios
  .\run.ps1 -ColdBoot       # arranque en frío del emulador
#>
[CmdletBinding()]
param(
    [switch]$Tests,
    [switch]$AllTests,
    [switch]$Reset,
    [switch]$Clean,
    [switch]$Headless,
    [switch]$ColdBoot,
    [switch]$Supertonic,
    [string]$Serial = "",
    [string]$Avd = ""
)

# "Continue": en PowerShell 5.1, "Stop" convierte en excepción cualquier stderr de ejecutables nativos.
# Los fallos se comprueban explícitamente con $LASTEXITCODE.
$ErrorActionPreference = "Continue"
$ProjectDir = $PSScriptRoot
$AppId = "com.linguareader.app"
$Activity = "com.linguareader.MainActivity"
$DefaultAvdName = "LinguaReader_API_36"
$SystemImage = "system-images;android-36.1;google_apis_playstore;x86_64"
$SupertonicRevision = "aafc6e32416a594460b32413efc49d7fe4ce6d46"
$SupertonicHostDir = Join-Path $ProjectDir ".models\supertonic-3\$SupertonicRevision"

function Step($msg) { Write-Host "`n==> $msg" -ForegroundColor Cyan }
function Info($msg) { Write-Host "    $msg" -ForegroundColor Gray }
function Warn($msg) { Write-Host "    AVISO: $msg" -ForegroundColor Yellow }
function Fail($msg) { Write-Host "`nERROR: $msg" -ForegroundColor Red; exit 1 }

function Invoke-Native {
    param([string]$Exe, [string[]]$Arguments, [string]$What)
    & $Exe @Arguments
    if ($LASTEXITCODE -ne 0) { Fail "$What falló (código $LASTEXITCODE)." }
}

# ---------------------------------------------------------------------------------------------
Step "Buscando JDK (17+, 64 bits)"
$jdkCandidates = @(
    "$env:ProgramFiles\Android\Android Studio\jbr",
    "$env:LOCALAPPDATA\Programs\Android Studio\jbr",
    "${env:ProgramFiles(x86)}\Android\Android Studio\jbr",
    $env:JAVA_HOME
) | Where-Object { $_ -and (Test-Path (Join-Path $_ "bin\java.exe")) }

$jdk = $null
foreach ($candidate in $jdkCandidates) {
    $release = Join-Path $candidate "release"
    if (Test-Path $release) {
        $content = Get-Content $release -Raw
        $isX64 = $content -match 'OS_ARCH="(x86_64|amd64)"'
        $major = if ($content -match 'JAVA_VERSION="(\d+)') { [int]$Matches[1] } else { 0 }
        if ($isX64 -and $major -ge 17) { $jdk = $candidate; break }
    }
}
if (-not $jdk) {
    Fail "No encuentro un JDK 17+ de 64 bits. Instala Android Studio (incluye el JBR) o define JAVA_HOME."
}
$env:JAVA_HOME = $jdk
Info "JAVA_HOME = $jdk"

# ---------------------------------------------------------------------------------------------
Step "Buscando Android SDK"
$sdk = @($env:ANDROID_HOME, $env:ANDROID_SDK_ROOT, "$env:LOCALAPPDATA\Android\Sdk") |
    Where-Object { $_ -and (Test-Path $_) } | Select-Object -First 1
if (-not $sdk) { Fail "No encuentro el Android SDK. Abre Android Studio una vez para instalarlo, o define ANDROID_HOME." }
$env:ANDROID_HOME = $sdk
Info "ANDROID_HOME = $sdk"

$localProps = Join-Path $ProjectDir "local.properties"
$sdkDirLine = "sdk.dir=" + ($sdk -replace '\\', '/')
if (-not (Test-Path $localProps) -or -not ((Get-Content $localProps -Raw) -match [regex]::Escape($sdkDirLine))) {
    Set-Content -Path $localProps -Value $sdkDirLine -Encoding ASCII
    Info "local.properties actualizado"
}

$adb = Join-Path $sdk "platform-tools\adb.exe"
$emulator = Join-Path $sdk "emulator\emulator.exe"
$sdkmanager = Join-Path $sdk "cmdline-tools\latest\bin\sdkmanager.bat"
$avdmanager = Join-Path $sdk "cmdline-tools\latest\bin\avdmanager.bat"

function Install-SdkPackages([string[]]$packages) {
    if (-not (Test-Path $sdkmanager)) {
        Fail "Faltan paquetes del SDK ($($packages -join ', ')) y no hay sdkmanager. Instala 'Android SDK Command-line Tools' desde Android Studio > SDK Manager."
    }
    Info "Instalando: $($packages -join ', ')"
    $yes = ("y`n" * 20)
    $yes | & $sdkmanager --licenses | Out-Null
    $yes | & $sdkmanager @packages
    if ($LASTEXITCODE -ne 0) { Fail "sdkmanager no pudo instalar $($packages -join ', ')." }
}

$missing = @()
if (-not (Test-Path $adb)) { $missing += "platform-tools" }
if (-not (Test-Path $emulator)) { $missing += "emulator" }
if ($missing.Count -gt 0) { Install-SdkPackages $missing }

# ---------------------------------------------------------------------------------------------
Step "Buscando dispositivo"
& $adb start-server | Out-Null

function Get-ReadyDevices {
    (& $adb devices) | Select-Object -Skip 1 | Where-Object { $_ -match "^\S+\s+device$" } | ForEach-Object { ($_ -split "\s+")[0] }
}

if ($Serial) { $env:ANDROID_SERIAL = $Serial }
$devices = @(Get-ReadyDevices)
if ($Serial) { $devices = @($devices | Where-Object { $_ -eq $Serial }) }
if ($devices.Count -gt 0) {
    Info "Usando dispositivo conectado: $($devices -join ', ')"
} else {
    $avds = @(& $emulator -list-avds 2>$null | Where-Object { $_ -and $_ -notmatch "^(INFO|WARNING)" })
    if ($Avd) {
        if ($avds -notcontains $Avd) { Fail "No existe el AVD '$Avd'. Disponibles: $($avds -join ', ')" }
        $chosen = $Avd
    } elseif ($avds.Count -gt 0) {
        $chosen = if ($avds -contains $DefaultAvdName) { $DefaultAvdName } else { $avds[0] }
    } else {
        Info "No hay emuladores: creando '$DefaultAvdName'"
        $imageDir = Join-Path $sdk ($SystemImage -replace ';', '\')
        if (-not (Test-Path $imageDir)) { Install-SdkPackages @($SystemImage) }
        if (-not (Test-Path $avdmanager)) { Fail "Falta avdmanager (Android SDK Command-line Tools)." }
        "no" | & $avdmanager create avd --name $DefaultAvdName --package $SystemImage --device "pixel_7"
        if ($LASTEXITCODE -ne 0) { Fail "No se pudo crear el AVD." }
        $chosen = $DefaultAvdName
    }

    Info "Arrancando emulador '$chosen' (la primera vez puede tardar 1-3 minutos)"
    $emuArgs = @("-avd", $chosen, "-netdelay", "none", "-netspeed", "full")
    # Sin -no-audio: ExoPlayer necesita un dispositivo de audio para llevar el ritmo real.
    if ($Headless) { $emuArgs += @("-no-window") }
    if ($ColdBoot) { $emuArgs += "-no-snapshot-load" }
    Start-Process -FilePath $emulator -ArgumentList $emuArgs | Out-Null

    & $adb wait-for-device
    $deadline = (Get-Date).AddMinutes(6)
    do {
        Start-Sleep -Seconds 3
        $booted = (& $adb shell getprop sys.boot_completed 2>$null) -join ""
        Write-Host "." -NoNewline
    } while ($booted.Trim() -ne "1" -and (Get-Date) -lt $deadline)
    Write-Host ""
    if ($booted.Trim() -ne "1") { Fail "El emulador no terminó de arrancar en 6 minutos. Prueba: .\run.ps1 -ColdBoot" }
    & $adb shell input keyevent 82 | Out-Null  # desbloquear pantalla
    Info "Emulador listo"
}

# ---------------------------------------------------------------------------------------------
Push-Location $ProjectDir
try {
    $gradlew = Join-Path $ProjectDir "gradlew.bat"
    if ($Clean) {
        Step "Limpiando"
        Invoke-Native $gradlew @("clean", "--console=plain") "gradle clean"
    }
    if ($Tests -or $AllTests) {
        Step "Tests JVM (:core:test)"
        Invoke-Native $gradlew @(":core:test", "--console=plain") "Tests JVM"
        Info "OK. Informe: core\build\reports\tests\test\index.html"
    }
    if ($AllTests) {
        Step "Tests instrumentados (en el dispositivo)"
        Invoke-Native $gradlew @(":app:connectedDebugAndroidTest", "--console=plain") "Tests instrumentados"
        Info "OK. Informe: app\build\reports\androidTests\connected\debug\index.html"
        Info "(Los tests desinstalan la app al terminar: se reinstala a continuación.)"
    }
    if ($Supertonic -and -not (Test-Path (Join-Path $SupertonicHostDir "onnx\vector_estimator.onnx"))) {
        Step "Descargando Supertonic 3 en el PC (~398 MB, revisión fijada, verificado con sha256)"
        Invoke-Native $gradlew @(":engine:supertonic:smokeTest", "--console=plain") "Descarga/prueba de Supertonic en el PC"
    }

    Step "Compilando e instalando (la primera vez descarga dependencias)"
    Invoke-Native $gradlew @(":app:installDebug", "--console=plain") "gradle installDebug"
} finally {
    Pop-Location
}

$targets = @(Get-ReadyDevices)
if ($Serial) { $targets = @($targets | Where-Object { $_ -eq $Serial }) }

if ($Reset) {
    Step "Borrando datos de la app"
    foreach ($d in $targets) { & $adb -s $d shell pm clear $AppId | Out-Null }
}

if ($Supertonic) {
    Step "Copiando el modelo Supertonic 3 al dispositivo"
    # El modelo vive en el almacenamiento INTERNO de la app (files/). adb no puede escribir ahí
    # directamente: se sube a /data/local/tmp y se copia con run-as (solo builds de depuración).
    $tmp = "/data/local/tmp/supertonic-$SupertonicRevision"
    $rel = "files/models/supertonic-3/$SupertonicRevision"
    foreach ($d in $targets) {
        & $adb -s $d shell "rm -rf $tmp; mkdir -p $tmp" | Out-Null
        & $adb -s $d push "$SupertonicHostDir\." "$tmp/"
        if ($LASTEXITCODE -ne 0) { Fail "No se pudo subir el modelo a $d." }
        & $adb -s $d shell "run-as $AppId sh -c 'rm -rf $rel; mkdir -p $rel && cp -R $tmp/. $rel/'"
        if ($LASTEXITCODE -ne 0) { Fail "run-as no pudo copiar el modelo (¿build de release?)." }
        & $adb -s $d shell "rm -rf $tmp" | Out-Null
        # Restos de versiones anteriores que usaban Android/data (carpetas de 'shell' que la app no puede leer).
        & $adb -s $d shell "rm -rf /sdcard/Android/data/$AppId/files/models" | Out-Null
    }
    Info "Modelo instalado. En la app: Ajustes > Motor de voz > Supertonic 3."
}

Step "Abriendo LinguaReader"
foreach ($d in $targets) { & $adb -s $d shell am start -n "$AppId/$Activity" | Out-Null }

# Voces TTS: la app avisa dentro si faltan, pero lo indicamos también aquí.
$ttsEngines = (& $adb -s $targets[0] shell cmd package query-services -a android.intent.action.TTS_SERVICE 2>$null) -join "`n"
if ($ttsEngines -notmatch "packageName=") {
    Warn "No se detecta motor de voz (TTS) en el dispositivo. La app funcionará sin audio."
}

Write-Host ""
Write-Host "Listo. Prueba:" -ForegroundColor Green
Write-Host "  - Abre 'The Red Kite' (inglés) o 'Le marché du samedi' (francés) y pulsa reproducir."
Write-Host "  - Toca una palabra (p. ej. 'up' en 'wakes up') para ver la ficha; mantén pulsado para traducir la frase."
Write-Host "  - Icono de ajustes del lector: cinco modos, resaltado, dedo indicador, tamaño de letra..."
Write-Host "  - Si falta la voz de un idioma, la app muestra 'Instalar voz'."
Write-Host "  - Si no oyes nada, sube el volumen del emulador (botones laterales de su barra)."
Write-Host "  - Supertonic: Ajustes > Motor de voz. Con -Supertonic el modelo ya viene instalado; si no, pulsa Descargar."
