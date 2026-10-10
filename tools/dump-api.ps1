# Dumps public method/field signatures of the Minecraft 26.3 classes this mod touches.
# Run from the project folder:
#   powershell -ExecutionPolicy Bypass -File tools\dump-api.ps1
# Writes api.txt in the project folder. Send that file back.
$base = Join-Path $env:USERPROFILE '.gradle\caches\fabric-loom\minecraftMaven\net\minecraft'
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
$classes = @(
 'world.level.levelgen.densityfunction.DensityFunction',
 'world.level.levelgen.densityfunction.DensityFunction$Axes',
 'world.level.levelgen.densityfunction.DensityFunction$CompileContext',
 'world.level.levelgen.densityfunction.DensitySampler',
 'world.level.levelgen.densityfunction.DensitySampler$Bound',
 'world.level.levelgen.densityfunction.SamplerContext',
 'world.level.levelgen.densityfunction.DensityVolume',
 'world.level.levelgen.densityfunction.DensityBuffer',
 'world.level.levelgen.densityfunction.TilingMode',
 'world.level.levelgen.densityfunction.generator.ConstantFunction',
 'world.level.levelgen.densityfunction.generator.ConstantFunction$Sampler',
 'world.level.biome.BiomeSource',
 'world.level.biome.Biome',
 'world.level.biome.Climate$Sampler',
 'server.MinecraftServer',
 'server.level.ServerLevel',
 'server.level.progress.LevelLoadListener',
 'world.level.saveddata.SavedData',
 'world.level.saveddata.SavedDataType',
 'world.level.storage.LevelData',
 'world.level.storage.LevelData$RespawnData',
 'world.level.storage.ServerLevelData',
 'resources.Identifier',
 'resources.ResourceKey',
 'resources.RegistryOps',
 'network.chat.Component',
 'network.chat.MutableComponent',
 'core.Holder',
 'core.HolderGetter',
 'core.Registry',
 'core.QuartPos',
 'world.level.LevelReader',
 'world.level.Level',
 'commands.Commands',
 'commands.CommandSourceStack',
 'client.gui.screens.Screen',
 'client.gui.components.Button',
 'client.gui.components.EditBox',
 'client.gui.components.StringWidget',
 'client.gui.screens.worldselection.CreateWorldScreen',
 'client.gui.screens.worldselection.WorldCreationUiState',
 'world.level.dimension.LevelStem',
 'world.level.levelgen.WorldDimensions'
) | ForEach-Object { $p + $_ }
$out = Join-Path (Get-Location) 'api.txt'
"" | Set-Content $out
foreach ($c in $classes) {
  "### $c" | Add-Content $out
  & $javap -public -cp $cp $c 2>&1 | Add-Content $out
}
Write-Host "Wrote $out ($($classes.Count) classes)."
