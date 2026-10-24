# Thin wrapper: builds the plugin jar via the Gradle wrapper.
# Double-click friendly.
Set-Location $PSScriptRoot
Write-Host "[1/1] Building with Gradle..." -ForegroundColor Cyan
& .\gradlew.bat build --console=plain
if ($LASTEXITCODE -ne 0) { Read-Host "`nBuild failed. Press Enter to exit"; exit 1 }
$jar = Get-ChildItem "$PSScriptRoot\build\libs\Quarry-*.jar" | Select-Object -First 1
Write-Host "`nDone!" -ForegroundColor Green
Write-Host "Built: $($jar.FullName)" -ForegroundColor Green
Read-Host "`nPress Enter to exit"
