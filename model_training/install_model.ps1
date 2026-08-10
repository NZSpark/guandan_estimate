param(
    [string]$OutputDirectory = "$PSScriptRoot\output"
)

$model = Join-Path $OutputDirectory "card_corner_classifier.tflite"
$manifest = Join-Path $OutputDirectory "model_manifest.json"
$target = Join-Path $PSScriptRoot "..\app\src\main\assets\models"

if (-not (Test-Path -LiteralPath $model)) { throw "Missing trained model: $model" }
if (-not (Test-Path -LiteralPath $manifest)) { throw "Missing model manifest: $manifest" }

New-Item -ItemType Directory -Force -Path $target | Out-Null
Copy-Item -LiteralPath $model -Destination (Join-Path $target "card_corner_classifier.tflite") -Force
Copy-Item -LiteralPath $manifest -Destination (Join-Path $target "model_manifest.json") -Force
Write-Output "Installed model assets into $target"
