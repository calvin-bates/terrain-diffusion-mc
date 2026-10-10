# Lists class names from the Minecraft 26.3 jar and Fabric API jars in your Gradle cache.
# Run from the project folder AFTER a gradlew build has been attempted:
#   powershell -ExecutionPolicy Bypass -File tools\list-classes.ps1
# It writes classes.txt next to this script's parent folder. Send that file back.
Add-Type -AssemblyName System.IO.Compression.FileSystem
$root = Join-Path $env:USERPROFILE '.gradle'
$jars = Get-ChildItem -Path $root -Recurse -Filter '*.jar' -ErrorAction SilentlyContinue |
  Where-Object {
    $_.Name -notlike '*sources*' -and $_.Name -notlike '*javadoc*' -and (
      ($_.Name -like '*minecraft*' -and $_.Length -gt 5MB) -or
      $_.Name -like 'fabric-lifecycle-events*' -or
      $_.Name -like 'fabric-command-api*' -or
      $_.Name -like 'fabric-data-generation*' -or
      $_.Name -like 'fabric-api-base*'
    )
  }
$out = Join-Path (Split-Path $PSScriptRoot -Parent) 'classes.txt'
"" | Set-Content $out
foreach ($j in $jars) {
  "### JAR: $($j.FullName) ($([math]::Round($j.Length/1MB,1)) MB)" | Add-Content $out
  $zip = [System.IO.Compression.ZipFile]::OpenRead($j.FullName)
  try {
    $zip.Entries | Where-Object { $_.FullName -like '*.class' -and $_.FullName -notlike 'META-INF/*' } |
      ForEach-Object { $_.FullName -replace '\.class$','' } | Add-Content $out
  } finally { $zip.Dispose() }
}
Write-Host "Wrote $out with $($jars.Count) jar(s)."
