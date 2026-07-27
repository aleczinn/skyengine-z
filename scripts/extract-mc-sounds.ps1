# Extrahiert die vom Sound-System benötigten Minecraft-Sounds aus der lokalen Installation
# nach src/main/resources/game/sounds (Platzhalter-Assets, analog zu den Texturen).
#
# Quelle: %APPDATA%\.minecraft\assets — indexes\<n>.json mappt Klarnamen auf Hashes,
# die Dateien liegen unter objects\<hash[0:2]>\<hash>. Idempotent (überschreibt).
#
# Aufruf:  powershell -ExecutionPolicy Bypass -File scripts\extract-mc-sounds.ps1

param(
    [string]$AssetsDir = "$env:APPDATA\.minecraft\assets",
    [string]$OutDir = (Join-Path $PSScriptRoot "..\src\main\resources\game\sounds")
)

if (-not (Test-Path "$AssetsDir\indexes")) {
    Write-Output "FEHLER: Kein Minecraft-Asset-Verzeichnis unter $AssetsDir gefunden."
    exit 1
}

# Neuesten Index nehmen: moderne Launcher-Indexes heißen rein numerisch (z.B. 32.json) und
# sind neuer als alle versionsbenannten (1.19.json etc.) — daher numerische bevorzugen.
$allIndexes = Get-ChildItem "$AssetsDir\indexes\*.json"
$numeric = $allIndexes | Where-Object { $_.BaseName -match '^\d+$' }
if ($numeric) {
    $indexFile = $numeric | Sort-Object { [int]$_.BaseName } -Descending | Select-Object -First 1
} else {
    $indexFile = $allIndexes | Sort-Object Name -Descending | Select-Object -First 1
}
Write-Output "Verwende Asset-Index: $($indexFile.Name)"

$objects = (Get-Content $indexFile.FullName -Raw | ConvertFrom-Json).objects

# Extraktionsliste: pattern (Index-Klarname, -like-Wildcard) -> Zielunterordner
$wanted = @(
    @{ pattern = 'minecraft/sounds/step/stone*.ogg';  dest = 'step' },
    @{ pattern = 'minecraft/sounds/step/wood*.ogg';   dest = 'step' },
    @{ pattern = 'minecraft/sounds/step/gravel*.ogg'; dest = 'step' },
    @{ pattern = 'minecraft/sounds/step/grass*.ogg';  dest = 'step' },
    @{ pattern = 'minecraft/sounds/step/sand*.ogg';   dest = 'step' },
    @{ pattern = 'minecraft/sounds/step/snow*.ogg';   dest = 'step' },
    @{ pattern = 'minecraft/sounds/step/cloth*.ogg';  dest = 'step' },
    @{ pattern = 'minecraft/sounds/dig/stone*.ogg';   dest = 'dig' },
    @{ pattern = 'minecraft/sounds/dig/wood*.ogg';    dest = 'dig' },
    @{ pattern = 'minecraft/sounds/dig/gravel*.ogg';  dest = 'dig' },
    @{ pattern = 'minecraft/sounds/dig/grass*.ogg';   dest = 'dig' },
    @{ pattern = 'minecraft/sounds/dig/sand*.ogg';    dest = 'dig' },
    @{ pattern = 'minecraft/sounds/dig/snow*.ogg';    dest = 'dig' },
    @{ pattern = 'minecraft/sounds/dig/cloth*.ogg';   dest = 'dig' },
    # Glas-Bruch liegt in MC unter random/ -> bei uns einheitlich unter dig/
    @{ pattern = 'minecraft/sounds/random/glass[1-3].ogg'; dest = 'dig' },
    # UI-Button-Klick (einzelne Datei, keine Varianten)
    @{ pattern = 'minecraft/sounds/random/click.ogg'; dest = 'ui' },
    # Spieler-Schaden (Hurt) + Aufprall bei Fallschaden
    @{ pattern = 'minecraft/sounds/damage/hit[1-3].ogg';  dest = 'damage' },
    @{ pattern = 'minecraft/sounds/damage/fallsmall.ogg'; dest = 'damage' },
    @{ pattern = 'minecraft/sounds/damage/fallbig.ogg';   dest = 'damage' },
    # Essen: Kauen + Burp (in MC unter random/ -> bei uns gebuendelt unter eat/)
    @{ pattern = 'minecraft/sounds/random/eat[1-3].ogg';  dest = 'eat' },
    @{ pattern = 'minecraft/sounds/random/burp.ogg';      dest = 'eat' },
    # TNT-Explosion + Zünd-Zischen (in MC unter random/)
    @{ pattern = 'minecraft/sounds/random/explode[1-4].ogg'; dest = 'random' },
    @{ pattern = 'minecraft/sounds/random/fuse.ogg';         dest = 'random' },
    # Auf-/Zu-Sounds (Tuer, Truhe). Jeder Satz braucht einen EIGENEN Zielordner: kopiert wird mit
    # dem MC-Dateinamen, sonst wuerden wooden_door/open1.ogg und iron_door/open1.ogg kollidieren.
    # Die Truhen-Muster sind eng gefasst — im selben MC-Ordner liegen *_locked-Varianten.
    @{ pattern = 'minecraft/sounds/block/wooden_door/*.ogg';    dest = 'door/wood' },
    @{ pattern = 'minecraft/sounds/block/iron_door/*.ogg';      dest = 'door/iron' },
    @{ pattern = 'minecraft/sounds/block/chest/open.ogg';       dest = 'chest' },
    @{ pattern = 'minecraft/sounds/block/chest/close[1-3].ogg'; dest = 'chest' },
    # Klassische Musik-Tracks (im modernen Index nach Songnamen benannt)
    @{ pattern = 'minecraft/sounds/music/game/minecraft.ogg';  dest = 'music' },
    @{ pattern = 'minecraft/sounds/music/game/sweden.ogg';     dest = 'music' },
    @{ pattern = 'minecraft/sounds/music/game/haggstrom.ogg';  dest = 'music' },
    @{ pattern = 'minecraft/sounds/music/game/wet_hands.ogg';  dest = 'music' }
)

$copied = 0
$missing = 0
foreach ($entry in $wanted) {
    $matches = $objects.PSObject.Properties | Where-Object { $_.Name -like $entry.pattern }
    if (-not $matches) {
        Write-Output "WARNUNG: Kein Treffer fuer $($entry.pattern)"
        continue
    }
    $destDir = Join-Path $OutDir $entry.dest
    New-Item -ItemType Directory -Force -Path $destDir | Out-Null
    foreach ($m in $matches) {
        $hash = $m.Value.hash
        $src = Join-Path $AssetsDir "objects\$($hash.Substring(0, 2))\$hash"
        if (-not (Test-Path $src)) {
            Write-Output "WARNUNG: Objekt fehlt lokal: $($m.Name) ($hash)"
            $missing++
            continue
        }
        $fileName = Split-Path $m.Name -Leaf
        Copy-Item $src (Join-Path $destDir $fileName) -Force
        $copied++
    }
}

Write-Output "Fertig: $copied Dateien extrahiert nach $([IO.Path]::GetFullPath($OutDir)) ($missing fehlend)."
