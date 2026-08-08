# Extrahiert die von den Item-JSONs benötigten Minecraft-Texturen aus der lokalen Installation
# nach src/main/resources/game/textures (Platzhalter-Assets, analog zu den Sounds).
#
# Quelle ist ANDERS als beim Sound-Skript: Texturen liegen NICHT in assets\objects (der
# Asset-Index deckt nur Sounds/Lang/Panorama ab), sondern im Versions-Jar unter
# assets/minecraft/textures/. Deshalb wird das Jar als ZIP geöffnet. Idempotent (überschreibt).
#
# Aufruf:  powershell -ExecutionPolicy Bypass -File scripts\extract-mc-items.ps1
#          powershell -ExecutionPolicy Bypass -File scripts\extract-mc-items.ps1 -Version 1.21.11

param(
    [string]$Version = "1.21.11",
    [string]$VersionsDir = "$env:APPDATA\.minecraft\versions",
    [string]$OutDir = (Join-Path $PSScriptRoot "..\src\main\resources\game\textures")
)

$jar = Join-Path $VersionsDir "$Version\$Version.jar"
if (-not (Test-Path $jar)) {
    Write-Output "FEHLER: Kein Versions-Jar unter $jar gefunden."
    # Nur Versionen melden, die wirklich ein Jar haben — der Launcher legt auch reine
    # .json-Ordner an (heruntergeladene Metadaten ohne Client-Jar).
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

# Extraktionsliste: pattern (Jar-Eintragspfad, -like-Wildcard) -> Zielunterordner unter $OutDir.
# Zwei Zielordner nötig, weil torch.png in MC unter textures/block/ liegt, die Rohstoffe aber
# unter textures/item/ — die Trennung muss bei uns erhalten bleiben.
$wanted = @(
    # Rohstoffe/Barren für die Test-Items
    @{ pattern = 'assets/minecraft/textures/item/iron_ingot.png';   dest = 'item' },
    @{ pattern = 'assets/minecraft/textures/item/gold_ingot.png';   dest = 'item' },
    @{ pattern = 'assets/minecraft/textures/item/copper_ingot.png'; dest = 'item' },
    @{ pattern = 'assets/minecraft/textures/item/diamond.png';      dest = 'item' },
    @{ pattern = 'assets/minecraft/textures/item/emerald.png';      dest = 'item' },
    @{ pattern = 'assets/minecraft/textures/item/coal.png';         dest = 'item' },
    @{ pattern = 'assets/minecraft/textures/item/charcoal.png';     dest = 'item' },
    @{ pattern = 'assets/minecraft/textures/item/redstone.png';     dest = 'item' },
    @{ pattern = 'assets/minecraft/textures/item/lapis_lazuli.png'; dest = 'item' },
    @{ pattern = 'assets/minecraft/textures/item/stick.png';        dest = 'item' },
    @{ pattern = 'assets/minecraft/textures/item/brick.png';        dest = 'item' },
    @{ pattern = 'assets/minecraft/textures/item/clay_ball.png';    dest = 'item' },
    @{ pattern = 'assets/minecraft/textures/item/flint.png';        dest = 'item' },
    @{ pattern = 'assets/minecraft/textures/item/leather.png';      dest = 'item' },
    @{ pattern = 'assets/minecraft/textures/item/paper.png';        dest = 'item' },
    # Fackel: Blocktextur, wird vom Fackel-Modell (Phase D/E) gebraucht
    @{ pattern = 'assets/minecraft/textures/block/torch.png';       dest = 'block' },
    @{ pattern = 'assets/minecraft/textures/item/minecart.png';     dest = 'item' },
    @{ pattern = 'assets/minecraft/textures/entity/minecart.png';   dest = 'entity' }
)

$zip = [IO.Compression.ZipFile]::OpenRead((Resolve-Path $jar))
$copied = 0
$missing = 0
try {
    foreach ($entry in $wanted) {
        $hits = $zip.Entries | Where-Object { $_.FullName -like $entry.pattern }
        if (-not $hits) {
            Write-Output "WARNUNG: Kein Treffer fuer $($entry.pattern)"
            $missing++
            continue
        }
        $destDir = Join-Path $OutDir $entry.dest
        New-Item -ItemType Directory -Force -Path $destDir | Out-Null
        foreach ($h in $hits) {
            $target = Join-Path $destDir $h.Name
            [IO.Compression.ZipFileExtensions]::ExtractToFile($h, $target, $true)
            $copied++
        }
    }
} finally {
    $zip.Dispose()
}

Write-Output "Fertig: $copied Dateien extrahiert nach $([IO.Path]::GetFullPath($OutDir)) ($missing fehlend)."
