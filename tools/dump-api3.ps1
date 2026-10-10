# Third dump: WorldTab internals and more vanilla data (material rules, density functions, biomes).
# Run from the project folder:
#   powershell -ExecutionPolicy Bypass -File tools\dump-api3.ps1
# Writes api3.txt and vanilla-data2.txt in the project folder. Send both back.
Add-Type -AssemblyName System.IO.Compression.FileSystem
$gradle = Join-Path $env:USERPROFILE '.gradle'
$base = Join-Path $gradle 'caches\fabric-loom\minecraftMaven\net\minecraft'
$jars = @(
  (Join-Path $base 'minecraft-common-deobf\26.3\minecraft-common-deobf-26.3.jar'),
  (Join-Path $base 'minecraft-clientonly-deobf\26.3\minecraft-clientonly-deobf-26.3.jar')
)
foreach ($j in $jars) { if (-not (Test-Path $j)) { Write-Host "Missing jar: $j"; exit 1 } }
$javap = $null
if ($env:JAVA_HOME -and (Test-Path (Join-Path $env:JAVA_HOME 'bin\javap.exe'))) { $javap = Join-Path $env:JAVA_HOME 'bin\javap.exe' }
elseif (Get-Command javap -ErrorAction SilentlyContinue) { $javap = 'javap' }
else { Write-Host 'javap not found. Set JAVA_HOME to your JDK folder.'; exit 1 }
$cp = $jars -join ';'
$p = 'net.minecraft.'
$items = @(
 @('client.gui.screens.worldselection.CreateWorldScreen$WorldTab','-private'),
 @('client.gui.screens.worldselection.CreateWorldScreen$WorldTab$1','-private'),
 @('client.gui.screens.worldselection.CreateWorldScreen$WorldTab$2','-private'),
 @('client.gui.screens.worldselection.CreateWorldScreen','-private'),
 @('world.level.levelgen.material.rule.MaterialRule','-public'),
 @('world.level.levelgen.material.rule.SequenceRule','-public'),
 @('world.level.levelgen.material.rule.ConditionRule','-public'),
 @('world.level.levelgen.material.rule.BlockRule','-public'),
 @('world.level.levelgen.material.condition.BiomeCondition','-public'),
 @('world.level.levelgen.material.condition.StoneDepthCondition','-public'),
 @('world.level.levelgen.material.condition.VerticalGradientCondition','-public'),
 @('world.level.levelgen.material.condition.WaterCondition','-public'),
 @('world.level.levelgen.material.condition.YCondition','-public'),
 @('world.level.levelgen.material.condition.AbovePreliminarySurfaceCondition','-public')
)
$out = Join-Path (Get-Location) 'api3.txt'
"" | Set-Content $out
foreach ($it in $items) {
  $c = $p + $it[0]
  "### $c ($($it[1]))" | Add-Content $out
  & $javap $it[1] -cp $cp $c 2>&1 | Add-Content $out
}
Write-Host "Wrote $out"

$data = Join-Path (Get-Location) 'vanilla-data2.txt'
"" | Set-Content $data
$wantExact = @(
 'data/minecraft/worldgen/density_function/zero.json',
 'data/minecraft/worldgen/density_function/y.json',
 'data/minecraft/worldgen/density_function/overworld/final_density.json',
 'data/minecraft/worldgen/density_function/overworld/chunk_surface_level.json',
 'data/minecraft/worldgen/density_function/overworld/preliminary_surface_level.json',
 'data/minecraft/worldgen/density_function/overworld/depth.json',
 'data/minecraft/worldgen/density_function/overworld/continents.json',
 'data/minecraft/worldgen/biome/forest.json',
 'data/minecraft/worldgen/biome/taiga.json',
 'data/minecraft/worldgen/biome/snowy_taiga.json',
 'data/minecraft/worldgen/biome/grove.json',
 'data/minecraft/tags/worldgen/world_preset/normal.json'
)
$all = Get-ChildItem -Path (Join-Path $gradle 'caches\fabric-loom') -Recurse -Filter '*.jar' -ErrorAction SilentlyContinue |
  Where-Object { $_.Name -notlike '*sources*' -and $_.Length -gt 5MB }
$found = @{}
$matListing = @()
foreach ($j in $all) {
  $zip = [System.IO.Compression.ZipFile]::OpenRead($j.FullName)
  try {
    foreach ($w in $wantExact) {
      if ($found.ContainsKey($w)) { continue }
      $e = $zip.GetEntry($w)
      if ($e) {
        $found[$w] = $true
        "### FILE $w (from $($j.Name))" | Add-Content $data
        $sr = New-Object System.IO.StreamReader($e.Open()); $sr.ReadToEnd() | Add-Content $data; $sr.Dispose()
      }
    }
    # any material rule data files (folder name unknown, so match on 'material_rule')
    foreach ($e in ($zip.Entries | Where-Object { $_.FullName -like '*material_rule*' -and $_.FullName -like '*.json' })) {
      $key = $e.FullName
      if ($found.ContainsKey($key)) { continue }
      $found[$key] = $true
      "### FILE $key (from $($j.Name))" | Add-Content $data
      $sr = New-Object System.IO.StreamReader($e.Open()); $sr.ReadToEnd() | Add-Content $data; $sr.Dispose()
    }
  } finally { $zip.Dispose() }
}
Write-Host "Wrote $data (found $($found.Count) files)"
