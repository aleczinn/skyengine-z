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
    'magma.png.mcmeta',
    'dispenser_front.png',
    'dispenser_front_vertical.png',
    'dropper_front.png',
    'dropper_front_vertical.png',
    'rail.png',
    'rail_corner.png',
    'powered_rail.png',
    'powered_rail_on.png',
    'detector_rail.png',
    'detector_rail_on.png',
    'activator_rail.png',
    'activator_rail_on.png',

    # Tueren: je Sorte eine obere und eine untere Haelfte. Die Item-Sprites liegen bereits
    # unter textures/item/ und kommen aus extract-mc-items.ps1.
    'oak_door_bottom.png', 'oak_door_top.png',
    'spruce_door_bottom.png', 'spruce_door_top.png',
    'birch_door_bottom.png', 'birch_door_top.png',
    'jungle_door_bottom.png', 'jungle_door_top.png',
    'acacia_door_bottom.png', 'acacia_door_top.png',
    'dark_oak_door_bottom.png', 'dark_oak_door_top.png',
    'mangrove_door_bottom.png', 'mangrove_door_top.png',
    'pale_oak_door_bottom.png', 'pale_oak_door_top.png',
    'iron_door_bottom.png', 'iron_door_top.png',

    # Falltueren: je Sorte EINE Textur, die alle drei Modellrumpfe teilen.
    'oak_trapdoor.png', 'spruce_trapdoor.png', 'birch_trapdoor.png', 'jungle_trapdoor.png',
    'acacia_trapdoor.png', 'dark_oak_trapdoor.png', 'mangrove_trapdoor.png',
    'pale_oak_trapdoor.png', 'iron_trapdoor.png'
)

# Farb-Achse wie in creative_tabs.json (axes.color) - fuer buntes Glas, Glasscheiben und Betten.
$colors = @(
    'white', 'light_gray', 'gray', 'black', 'brown', 'red', 'orange', 'yellow',
    'lime', 'green', 'cyan', 'light_blue', 'blue', 'purple', 'magenta', 'pink'
)

foreach ($c in $colors) {
    # Buntglas: ein Wuerfel-Textur (wie glass.png), plus die Kanten-Textur der Scheibe.
    $wanted += "${c}_stained_glass.png"
    $wanted += "${c}_stained_glass_pane_top.png"

    # Bett: 4 farbabhaengige Texturen am Kopf-, 4 am Fussteil (Nordseite des Kopfes und die
    # Unterseite sind bei allen Farben gleich, s.u.).
    $wanted += "${c}_bed_head_east.png"
    $wanted += "${c}_bed_head_up.png"
    $wanted += "${c}_bed_head_west.png"
    $wanted += "${c}_bed_foot_east.png"
    $wanted += "${c}_bed_foot_south.png"
    $wanted += "${c}_bed_foot_up.png"
    $wanted += "${c}_bed_foot_west.png"
}

# Bett: geteilte, nicht farbabhaengige Texturen (Nordseite des Kopfteils, Unterseite beider Teile).
$wanted += 'bed_head_north.png'
$wanted += 'bed_down.png'

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
