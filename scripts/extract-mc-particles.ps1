# Extrahiert nur die von SkyEngine verwendeten Vanilla-Partikelsprites aus dem Versions-Jar.
# Aufruf: powershell -ExecutionPolicy Bypass -File scripts\extract-mc-particles.ps1

param(
    [string]$Version = "1.21.11",
    [string]$VersionsDir = "$env:APPDATA\.minecraft\versions",
    [string]$OutDir = (Join-Path $PSScriptRoot "..\src\main\resources\game\textures\particle")
)

$jar = Join-Path $VersionsDir "$Version\$Version.jar"
if (-not (Test-Path -LiteralPath $jar)) {
    Write-Output "FEHLER: Kein Versions-Jar unter $jar gefunden."
    exit 1
}

Add-Type -AssemblyName System.IO.Compression.FileSystem
$wanted = @('bubble.png', 'drip_hang.png', 'drip_fall.png', 'drip_land.png',
        'lava.png', 'flame.png')
0..7 | ForEach-Object { $wanted += "generic_$_.png" }
0..4 | ForEach-Object { $wanted += "bubble_pop_$_.png" }
0..3 | ForEach-Object { $wanted += "splash_$_.png" }
0..15 | ForEach-Object { $wanted += "explosion_$_.png" }

$zip = [IO.Compression.ZipFile]::OpenRead((Resolve-Path -LiteralPath $jar))
$copied = 0
try {
    New-Item -ItemType Directory -Force -Path $OutDir | Out-Null
    foreach ($name in $wanted) {
        $entry = $zip.GetEntry("assets/minecraft/textures/particle/$name")
        if ($null -eq $entry) {
            Write-Output "WARNUNG: Kein Treffer für $name"
            continue
        }
        [IO.Compression.ZipFileExtensions]::ExtractToFile(
                $entry, (Join-Path $OutDir $name), $true)
        $copied++
    }
} finally {
    $zip.Dispose()
}
Write-Output "Fertig: $copied Partikelsprites aus Minecraft $Version extrahiert."
