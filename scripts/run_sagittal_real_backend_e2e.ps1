param()

$ErrorActionPreference = "Stop"

if ($env:RUN_BACKEND_REAL_E2E -ne "1") {
    Write-Error "RUN_BACKEND_REAL_E2E=1 es obligatorio. El E2E real queda deshabilitado por defecto."
}

$baseUrl = if ($env:PFI_BACKEND_BASE_URL) { $env:PFI_BACKEND_BASE_URL.TrimEnd("/") } else { "http://localhost:8080" }
$caseId = if ($env:PFI_E2E_CASE_ID) { $env:PFI_E2E_CASE_ID } else { "CASE-SPIDER-E2E" }
$inputPath = $env:PFI_E2E_INPUT_PATH
$token = $env:PFI_BACKEND_BEARER_TOKEN

if ([string]::IsNullOrWhiteSpace($inputPath) -or !(Test-Path -LiteralPath $inputPath)) {
    Write-Error "PFI_E2E_INPUT_PATH debe apuntar a un archivo local existente."
}
if ([string]::IsNullOrWhiteSpace($token)) {
    Write-Error "PFI_BACKEND_BEARER_TOKEN es obligatorio. No se imprime el token."
}

$headers = @{ Authorization = "Bearer $token" }

Invoke-RestMethod -Method Get -Uri "$baseUrl/api/ai/health" -Headers $headers | Out-Null
Invoke-RestMethod -Method Get -Uri "$baseUrl/api/ai/readiness" -Headers $headers | Out-Null
Invoke-RestMethod -Method Post -Uri "$baseUrl/api/ai/models/sync?force=false" -Headers $headers | Out-Null

$upload = Invoke-RestMethod -Method Post -Uri "$baseUrl/api/ai/inputs" -Headers $headers -Form @{
    file = Get-Item -LiteralPath $inputPath
    caseId = $caseId
    plane = "sagittal"
}

$pipelineInput = if ($upload.inputPath) { $upload.inputPath } elseif ($upload.inputId) { $upload.inputId } else { throw "Upload no devolvio inputPath ni inputId." }
$body = @{
    caseId = $caseId
    plane = "sagittal"
    modelKey = "sagittal_spider"
    inputPath = $pipelineInput
    metadata = @{
        inferenceMode = "real_baseline"
        allowContractFallback = $false
        sliceIndex = 9
        deidentified = $true
    }
} | ConvertTo-Json -Depth 8

$result = Invoke-RestMethod -Method Post -Uri "$baseUrl/api/ai/pipeline/run" -Headers ($headers + @{ "Content-Type" = "application/json" }) -Body $body

if ($result.modelVersion -ne "sagittal-spider-final-v1") { throw "modelVersion inesperado." }
if ($result.artifactHash -ne "cf11dcc0ad77a7c787e64a796a2fd7398ef906add461cef4b3d61f1a5238e944") { throw "artifactHash inesperado." }
if ($result.inferenceMode -ne "real_baseline") { throw "inferenceMode inesperado." }
if ($result.allowContractFallback -ne $false) { throw "allowContractFallback no es false." }
if ($result.metadata.selectedAxis -ne 2) { throw "selectedAxis inesperado." }
if ($result.metadata.sliceCount -ne 17) { throw "sliceCount inesperado para fixture SPIDER." }
if ($result.metadata.selectedSlice -ne 9) { throw "selectedSlice inesperado." }
if (($result.metadata.inputShapeNative -join ",") -ne "17,512,512") { throw "inputShapeNative inesperado." }
if (($result.metadata.inputShapeCanonical -join ",") -ne "512,512,17") { throw "inputShapeCanonical inesperado." }
if ($result.metadata.inputOrientationTransform -ne "move_axis_0_to_last") { throw "transform inesperado." }
if ($result.humanReviewRequired -ne $true) { throw "humanReviewRequired debe ser true." }
if ($result.notClinicalDiagnosis -ne $true) { throw "notClinicalDiagnosis debe ser true." }
if ($result.degradedMode -eq $true) { throw "degradedMode no debe ser true." }

$overlayUrl = $result.assets.'overlay.png'
$inputUrl = $result.assets.'input.png'
if ([string]::IsNullOrWhiteSpace($overlayUrl) -or [string]::IsNullOrWhiteSpace($inputUrl)) {
    throw "Faltan URLs proxy de assets."
}
Invoke-WebRequest -Method Get -Uri "$baseUrl$overlayUrl" -Headers $headers | Out-Null
Invoke-WebRequest -Method Get -Uri "$baseUrl$inputUrl" -Headers $headers | Out-Null

$result | ConvertTo-Json -Depth 12
