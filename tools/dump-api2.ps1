# Second dump: protected/private members + some vanilla data files from the 26.3 jars.
# Run from the project folder:
#   powershell -ExecutionPolicy Bypass -File tools\dump-api2.ps1
# Writes api2.txt and vanilla-data.txt in the project folder. Send both back.
Add-Type -AssemblyName System.IO.Compression.FileSystem
$gradle = Join-Path $env:USERPROFILE '.gradle'
$base = Join-Path $gradle 'caches\fabric-loom\minecraftMaven\net\minecraft'
$jars = @(
  (Join-Path $base 'minecraft-common-deobf\26.3\minecraft-common-deobf-26.3.jar'),
  (Join-Path $base 'minecraft-clientonly-deobf\26.3\minecraft-clientonly-deobf-26.3.jar')
)
foreach ($j in $jars) { if (-not (Test-Path $j)) { Write-Host "Missing jar: $j"; exit 1 } }
$fab = Get-ChildItem -Path (Join-Path $gradle 'caches\modules-2') -Recurse -Filter '*.jar' -ErrorAction SilentlyContinue |
  Where-Object { $_.Name -like 'fabric-lifecycle-events*' -or $_.Name -like 'fabric-command-api*' } |
  Where-Object { $_.Name -notlike '*sources*' } | ForEach-Object { $_.FullName }
$cp = ($jars + $fab) -join ';'
$javap = $null
if ($env:JAVA_HOME -and (Test-Path (Join-Path $env:JAVA_HOME 'bin\javap.exe'))) { $javap = Join-Path $env:JAVA_HOME 'bin\javap.exe' }
elseif (Get-Command javap -ErrorAction SilentlyContinue) { $javap = 'javap' }
else { Write-Host 'javap not found. Set JAVA_HOME to your JDK folder.'; exit 1 }
$p = 'net.minecraft.'
# (class, mode) where mode is -protected or -private
$items = @(
 @('world.level.biome.BiomeSource','-protected'),
 @('world.level.biome.BiomeResolver','-protected'),
 @('world.level.biome.Climate$TargetPoint','-public'),
 @('world.level.biome.Climate$ParameterPoint','-public'),
 @('client.gui.screens.Screen','-protected'),
 @('client.gui.components.AbstractWidget','-protected'),
 @('client.gui.components.Button$Builder','-public'),
 @('client.gui.components.Button$OnPress','-public'),
 @('client.gui.GuiGraphicsExtractor','-public'),
 @('client.gui.Font','-public'),
 @('client.input.KeyEvent','-public'),
 @('client.gui.screens.worldselection.WorldCreationUiState$WorldTypeEntry','-public'),
 @('client.gui.screens.worldselection.WorldCreationContext','-public'),
 @('client.gui.screens.worldselection.WorldCreationContext$DimensionsUpdater','-public'),
 @('server.MinecraftServer','-private'),
 @('server.level.ServerLevel','-private'),
 @('world.level.storage.SavedDataStorage','-public'),
 @('util.Interval','-public'),
 @('core.registries.Registries','-public'),
 @('core.registries.BuiltInRegistries','-public'),
 @('core.HolderLookup$Provider','-public'),
 @('core.RegistryAccess','-public'),
 @('core.Holder$Reference','-public'),
 @('world.level.levelgen.presets.WorldPreset','-public'),
 @('world.level.levelgen.NoiseGeneratorSettings','-public'),
 @('world.level.levelgen.NoiseRouter','-public'),
 @('world.level.levelgen.NoiseBasedChunkGenerator','-public'),
 @('world.level.levelgen.RandomState','-public'),
 @('world.level.dimension.DimensionType','-public'),
 @('world.level.biome.Biome$Precipitation','-public')
)
$out = Join-Path (Get-Location) 'api2.txt'
"" | Set-Content $out
foreach ($it in $items) {
  $c = $p + $it[0]
  "### $c ($($it[1]))" | Add-Content $out
  & $javap $it[1] -cp $cp $c 2>&1 | Add-Content $out
}
foreach ($c in @('net.fabricmc.fabric.api.event.lifecycle.v1.ServerLevelEvents','net.fabricmc.fabric.api.event.lifecycle.v1.ServerLevelEvents$Load','net.fabricmc.fabric.api.command.v2.CommandRegistrationCallback')) {
  "### $c (-public)" | Add-Content $out
  & $javap -public -cp $cp $c 2>&1 | Add-Content $out
}
Write-Host "Wrote $out"

# Vanilla data files (searched in all 26.3 jars in the loom cache)
$data = Join-Path (Get-Location) 'vanilla-data.txt'
"" | Set-Content $data
$want = @(
 'data/minecraft/worldgen/noise_settings/overworld.json',
 'data/minecraft/dimension_type/overworld.json',
 'data/minecraft/worldgen/world_preset/normal.json',
 'data/minecraft/worldgen/biome/plains.json'
)
$all = Get-ChildItem -Path (Join-Path $gradle 'caches\fabric-loom') -Recurse -Filter '*.jar' -ErrorAction SilentlyContinue |
  Where-Object { $_.Name -notlike '*sources*' -and $_.Length -gt 5MB }
$found = @{}
foreach ($j in $all) {
  $zip = [System.IO.Compression.ZipFile]::OpenRead($j.FullName)
  try {
    foreach ($w in $want) {
      if ($found.ContainsKey($w)) { continue }
      $e = $zip.GetEntry($w)
      if ($e) {
        $found[$w] = $true
        "### FILE $w (from $($j.Name))" | Add-Content $data
        $sr = New-Object System.IO.StreamReader($e.Open())
        $sr.ReadToEnd() | Add-Content $data
        $sr.Dispose()
      }
    }
    $names = $zip.Entries | Where-Object { $_.FullName -like 'data/minecraft/worldgen/density_function/*' -or $_.FullName -like 'data/minecraft/worldgen/noise_settings/*' } | ForEach-Object { $_.FullName }
    if ($names) { "### LISTING in $($j.Name)" | Add-Content $data; $names | Select-Object -First 60 | Add-Content $data }
  } finally { $zip.Dispose() }
}
Write-Host "Wrote $data (found $($found.Count) of $($want.Count) files)"
