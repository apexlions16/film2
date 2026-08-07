[CmdletBinding()]
param(
    [Parameter(Mandatory = $true)]
    [string]$CertificateProfile,

    [Parameter(Mandatory = $false)]
    [string]$TvIp,

    [Parameter(Mandatory = $false)]
    [string]$Target,

    [Parameter(Mandatory = $false)]
    [string]$TizenStudio = "C:\tizen-studio"
)

$ErrorActionPreference = "Stop"
Set-StrictMode -Version Latest

function Resolve-Tool {
    param([string]$Name, [string[]]$Candidates)
    $cmd = Get-Command $Name -ErrorAction SilentlyContinue
    if ($cmd) { return $cmd.Source }
    foreach ($candidate in $Candidates) {
        if ($candidate -and (Test-Path $candidate)) { return $candidate }
    }
    throw "$Name bulunamadı. Tizen Studio + Web CLI kurulu olmalı veya TizenStudio parametresini doğru klasöre vermelisiniz."
}

$ProjectRoot = (Resolve-Path (Join-Path $PSScriptRoot "..")).Path
$Tizen = Resolve-Tool "tizen" @(
    (Join-Path $TizenStudio "tools\ide\bin\tizen.bat"),
    (Join-Path $TizenStudio "tools\ide\bin\tizen")
)
$Sdb = Resolve-Tool "sdb" @(
    (Join-Path $TizenStudio "tools\sdb.exe"),
    (Join-Path $TizenStudio "tools\sdb")
)

Write-Host "Film2 TV - Samsung Tizen build/install" -ForegroundColor Cyan
Write-Host "Proje: $ProjectRoot"
Write-Host "Tizen CLI: $Tizen"
Write-Host "SDB: $Sdb"

if ($TvIp) {
    Write-Host "TV'ye bağlanılıyor: $TvIp"
    & $Sdb connect $TvIp
    if ($LASTEXITCODE -ne 0) { throw "SDB bağlantısı başarısız." }
}

$DevicesOutput = & $Sdb devices 2>&1
$DevicesOutput | ForEach-Object { Write-Host $_ }
$Connected = @($DevicesOutput | Where-Object { $_ -match "\sdevice\s" })
if ($Connected.Count -eq 0) {
    throw "Bağlı Samsung TV bulunamadı. TV'de Developer Mode açık olmalı ve PC ile aynı ağda olmalı."
}

if (-not $Target) {
    # Samsung/Tizen CLI accepts the numeric device index shown by `sdb devices`.
    # With one connected device this is normally 0.
    if ($Connected.Count -gt 1) {
        throw "Birden fazla cihaz bağlı. -Target ile `sdb devices` çıktısındaki cihaz numarasını belirtin."
    }
    $Target = "0"
}

Write-Host "1/4 Web uygulaması doğrulanıp derleniyor..." -ForegroundColor Yellow
& $Tizen build-web -- $ProjectRoot
if ($LASTEXITCODE -ne 0) { throw "tizen build-web başarısız." }

$BuildDir = Join-Path $ProjectRoot ".buildResult"
if (-not (Test-Path $BuildDir)) { throw ".buildResult oluşturulmadı." }

Write-Host "2/4 Samsung sertifikasıyla paketleniyor: $CertificateProfile" -ForegroundColor Yellow
& $Tizen package -t wgt -s $CertificateProfile -- $BuildDir
if ($LASTEXITCODE -ne 0) { throw "tizen package başarısız. CertificateProfile adını ve Samsung sertifikasını kontrol edin." }

$Wgt = Get-ChildItem -Path $BuildDir -Filter *.wgt -File | Sort-Object LastWriteTime -Descending | Select-Object -First 1
if (-not $Wgt) { throw "İmzalı .wgt bulunamadı." }
Write-Host "İmzalı paket: $($Wgt.FullName)" -ForegroundColor Green

Write-Host "3/4 TV'ye kuruluyor (target: $Target)..." -ForegroundColor Yellow
& $Tizen install -n $Wgt.Name -t $Target -- $Wgt.DirectoryName
if ($LASTEXITCODE -ne 0) { throw "TV kurulumu başarısız. DUID'nin distributor sertifikasında kayıtlı olduğundan emin olun." }

Write-Host "4/4 Film2 TV başlatılıyor..." -ForegroundColor Yellow
& $Tizen run -p "F2TVPlayer.Film2TV" -t $Target
if ($LASTEXITCODE -ne 0) { throw "Uygulama kuruldu ancak başlatılamadı." }

Write-Host "Tamamlandı: Film2 TV cihazda kuruldu ve başlatıldı." -ForegroundColor Green
