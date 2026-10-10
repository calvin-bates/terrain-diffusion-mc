# Dumps the public API of net.minecraft.client.Minecraft (looking for the setScreen replacement).
# Run: powershell -ExecutionPolicy Bypass -File tools\dump-minecraft.ps1  -> writes minecraft-class.txt
$base = Join-Path $env:USERPROFILE '.gradle\caches\fabric-loom\minecraftMaven\net\minecraft'
$cp = (Join-Path $base 'minecraft-common-deobf\26.3\minecraft-common-deobf-26.3.jar') + ';' + (Join-Path $base 'minecraft-clientonly-deobf\26.3\minecraft-clientonly-deobf-26.3.jar')
$javap = if ($env:JAVA_HOME) { Join-Path $env:JAVA_HOME 'bin\javap.exe' } else { 'javap' }
$out = Join-Path (Get-Location) 'minecraft-class.txt'
& $javap -public -cp $cp net.minecraft.client.Minecraft 2>&1 | Set-Content $out
& $javap -public -cp $cp net.minecraft.client.gui.Gui 2>&1 | Add-Content $out
Write-Host "Wrote $out"
