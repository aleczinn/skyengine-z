param(
    [Parameter(Mandatory = $true)] [string] $MinecraftJar,
    [string] $GameDirectory = (Join-Path $PSScriptRoot "..\src\main\resources\game")
)

$ErrorActionPreference = "Stop"
$MinecraftJar = (Resolve-Path -LiteralPath $MinecraftJar).Path
$GameDirectory = (Resolve-Path -LiteralPath $GameDirectory).Path
$output = Join-Path $GameDirectory "loot_table\blocks"
$temporary = Join-Path ([System.IO.Path]::GetTempPath()) ("skyengine-loot-" + [guid]::NewGuid())
New-Item -ItemType Directory -Force -Path $output, $temporary | Out-Null

try {
    Push-Location $temporary
    & jar xf $MinecraftJar "data/minecraft/loot_table/blocks"
    if ($LASTEXITCODE -ne 0) { throw "Minecraft-JAR konnte nicht extrahiert werden" }
    Pop-Location

    $itemTextures = @("shears", "raw_copper", "raw_gold", "raw_iron", "melon_slice", "snowball", "wheat_seeds")
    $saplingTextures = @("acacia_sapling", "birch_sapling", "dark_oak_sapling", "jungle_sapling", "oak_sapling", "pale_oak_sapling", "spruce_sapling")
    Push-Location $temporary
    foreach ($texture in $itemTextures) { & jar xf $MinecraftJar "assets/minecraft/textures/item/$texture.png" }
    foreach ($texture in $saplingTextures) { & jar xf $MinecraftJar "assets/minecraft/textures/block/$texture.png" }
    Pop-Location
    $textureOutput = Join-Path $GameDirectory "textures\item"
    foreach ($texture in $itemTextures) {
        Copy-Item -LiteralPath (Join-Path $temporary "assets\minecraft\textures\item\$texture.png") -Destination (Join-Path $textureOutput "$texture.png") -Force
    }
    foreach ($texture in $saplingTextures) {
        Copy-Item -LiteralPath (Join-Path $temporary "assets\minecraft\textures\block\$texture.png") -Destination (Join-Path $textureOutput "$texture.png") -Force
    }

    $dropFree = @("bedrock", "moving_piston", "piston_head")
    $adaptSelf = @("tnt") # Engine besitzt Vanillas internes UNSTABLE-Property nicht.
    $count = 0
    Get-ChildItem -LiteralPath (Join-Path $GameDirectory "blocks") -File -Filter "*.json" | Sort-Object Name | ForEach-Object {
        $definitionText = Get-Content -LiteralPath $_.FullName -Raw
        $idMatch = [regex]::Match($definitionText, '"id"\s*:\s*"([^"]+)"')
        if (-not $idMatch.Success) { return }
        $id = $idMatch.Groups[1].Value
        $path = $id.Substring($id.IndexOf(':') + 1)
        if ($path -in @("air", "water", "lava")) { return }

        $source = Join-Path $temporary ("data\minecraft\loot_table\blocks\" + $path + ".json")
        $target = Join-Path $output ($path + ".json")
        if ($path -eq "snow") {
            $silk = @{ condition = "skyengine:match_tool"; predicate = @{ predicates = @{ "skyengine:enchantments" = @(@{ enchantments = "skyengine:silk_touch"; levels = @{ min = 1 } }) } } }
            $first = [ordered]@{ type = "skyengine:item"; name = "skyengine:snow"; conditions = @($silk) }
            $second = [ordered]@{ type = "skyengine:item"; name = "skyengine:snowball"; conditions = @(@{ condition = "skyengine:survives_explosion" }) }
            $entry = [ordered]@{ type = "skyengine:alternatives"; children = @($first, $second) }
            $json = [ordered]@{ type = "skyengine:block"; pools = @([ordered]@{ rolls = 1.0; entries = @($entry) }); random_sequence = "skyengine:blocks/snow" }
            [System.IO.File]::WriteAllText($target, ($json | ConvertTo-Json -Depth 20), [System.Text.UTF8Encoding]::new($false))
        } elseif ((Test-Path -LiteralPath $source) -and -not ($adaptSelf -contains $path)) {
            $json = Get-Content -LiteralPath $source -Raw
            # Das Dateiformat ist strukturell kompatibel, alle Registry- und Schema-IDs gehören
            # nach dem Import aber ausschließlich der Engine.
            $json = $json.Replace('minecraft:', 'skyengine:')
            [System.IO.File]::WriteAllText($target, $json, [System.Text.UTF8Encoding]::new($false))
        } elseif ($dropFree -contains $path) {
            $json = [ordered]@{ type = "skyengine:block"; pools = @(); random_sequence = "skyengine:blocks/$path" }
            [System.IO.File]::WriteAllText($target, ($json | ConvertTo-Json -Depth 20), [System.Text.UTF8Encoding]::new($false))
        } else {
            $entry = [ordered]@{ type = "skyengine:item"; name = "skyengine:$path"; conditions = @(@{ condition = "skyengine:survives_explosion" }) }
            $pool = [ordered]@{ rolls = 1.0; entries = @($entry) }
            $json = [ordered]@{ type = "skyengine:block"; pools = @($pool); random_sequence = "skyengine:blocks/$path" }
            [System.IO.File]::WriteAllText($target, ($json | ConvertTo-Json -Depth 20), [System.Text.UTF8Encoding]::new($false))
        }
        $count++
    }
    Write-Host "$count Block-Loot-Tabellen geschrieben"
} finally {
    if ((Get-Location).Path -eq $temporary) { Pop-Location }
    Remove-Item -LiteralPath $temporary -Recurse -Force -ErrorAction SilentlyContinue
}
