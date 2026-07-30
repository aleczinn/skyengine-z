# Extrahiert Block-Texturen aus der lokalen Minecraft-Installation nach
# src/main/resources/game/textures/block (Platzhalter-Assets, analog zu Items und Sounds).
#
# Wie beim Item-Skript liegen Texturen NICHT in assets\objects, sondern im Versions-Jar unter
# assets/minecraft/textures/block/ — das Jar wird deshalb als ZIP geoeffnet. Idempotent
# (ueberschreibt). Animierte Texturen bringen ihren .png.mcmeta-Sidecar mit; SpriteAnimations
# findet ihn ueber den Dateinamen automatisch, er MUSS also mitkopiert werden.
#
# Aufruf:  powershell -ExecutionPolicy Bypass -File scripts\extract-mc-blocks.ps1
#          powershell -ExecutionPolicy Bypass -File scripts\extract-mc-blocks.ps1 -Version 1.21.11

param(
    [string]$Version = "1.21.11",
    [string]$VersionsDir = "$env:APPDATA\.minecraft\versions",
    [string]$OutDir = (Join-Path $PSScriptRoot "..\src\main\resources\game\textures\block")
)

$jar = Join-Path $VersionsDir "$Version\$Version.jar"
if (-not (Test-Path $jar)) {
    Write-Output "FEHLER: Kein Versions-Jar unter $jar gefunden."
    if (Test-Path $VersionsDir) {
        $withJar = Get-ChildItem $VersionsDir -Directory |
                Where-Object { Test-Path (Join-Path $_.FullName "$($_.Name).jar") } |
                Select-Object -ExpandProperty Name
        Write-Output "Versionen mit Jar: $($withJar -join ', ')"
    }
    exit 1
}
Write-Output "Verwende Versions-Jar: $Version"

Add-Type -AssemblyName System.IO.Compression.FileSystem

# Gesuchte Dateinamen unter assets/minecraft/textures/block/.
# Achtung auf die MC-Namen: der Magmablock heisst als Textur nur "magma" (und ist animiert),
# der Honigblock bringt drei Seiten mit.
$wanted = @(
    'ice.png',
    'packed_ice.png',
    'blue_ice.png',
    'soul_sand.png',
    'soul_soil.png',
    'slime_block.png',
    'honey_block_top.png',
    'honey_block_side.png',
    'honey_block_bottom.png',
    'end_stone.png',
    'netherrack.png',
    'magma.png',
    'magma.png.mcmeta'
)

$zip = [IO.Compression.ZipFile]::OpenRead((Resolve-Path $jar))
$copied = 0
$missing = 0
try {
    New-Item -ItemType Directory -Force -Path $OutDir | Out-Null
    foreach ($name in $wanted) {
        $entry = $zip.GetEntry("assets/minecraft/textures/block/$name")
        if ($null -eq $entry) {
            Write-Output "WARNUNG: Kein Treffer fuer $name"
            $missing++
            continue
        }
        $target = Join-Path $OutDir $name
        [IO.Compression.ZipFileExtensions]::ExtractToFile($entry, $target, $true)
        $copied++
    }
} finally {
    $zip.Dispose()
}

Write-Output "Fertig: $copied Dateien extrahiert nach $([IO.Path]::GetFullPath($OutDir)) ($missing fehlend)."
