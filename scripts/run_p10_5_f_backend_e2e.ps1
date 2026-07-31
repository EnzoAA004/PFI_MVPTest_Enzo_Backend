param(
    [string]$BackendBaseUrl = $env:PFI_E2E_BACKEND_BASE_URL,
    [string]$AiBaseUrl = $env:PFI_E2E_AI_BASE_URL,
    [string]$StudyZip = $env:PFI_E2E_STUDY_ZIP,
    [string]$CaseId = $env:PFI_E2E_CASE_ID,
    [string]$AuthToken = $env:PFI_E2E_AUTH_TOKEN,
    [string]$AuthEmail = $env:PFI_E2E_AUTH_EMAIL,
    [string]$AuthPassword = $env:PFI_E2E_AUTH_PASSWORD,
    [string]$ComposeProjectDir = $env:PFI_E2E_COMPOSE_PROJECT_DIR,
    [string]$AiComposeService = $env:PFI_E2E_AI_COMPOSE_SERVICE,
    [string]$AiContainerName = $env:PFI_E2E_AI_CONTAINER_NAME,
    [string]$BackendComposeService = $env:PFI_E2E_BACKEND_COMPOSE_SERVICE,
    [string]$PostgresComposeService = $env:PFI_E2E_POSTGRES_COMPOSE_SERVICE,
    [string]$ComposeFiles = $env:PFI_E2E_COMPOSE_FILES,
    [string]$PythonExe = $env:PFI_E2E_PYTHON,
    [string]$OutputPath = $env:PFI_E2E_SUMMARY_PATH,
    [switch]$GenerateSyntheticDicom = ($env:PFI_E2E_GENERATE_SYNTHETIC_DICOM -eq "1"),
    [switch]$ManageServices = ($env:PFI_E2E_MANAGE_SERVICES -eq "1"),
    [switch]$RestartBackend = ($env:PFI_E2E_RESTART_BACKEND -eq "1")
)

$ErrorActionPreference = "Stop"
Add-Type -AssemblyName System.Net.Http

if ($env:RUN_PFI_E2E -ne "1") {
    Write-Error "Set RUN_PFI_E2E=1 to run this opt-in multiservice harness."
    exit 2
}
if ([string]::IsNullOrWhiteSpace($BackendBaseUrl)) { $BackendBaseUrl = "http://localhost:8080" }
if ([string]::IsNullOrWhiteSpace($AiBaseUrl)) { $AiBaseUrl = "http://localhost:8000" }
if ([string]::IsNullOrWhiteSpace($CaseId)) { $CaseId = "P10-5-F-E2E-" + (Get-Date -Format "yyyyMMddHHmmss") }
if ([string]::IsNullOrWhiteSpace($ComposeProjectDir)) { $ComposeProjectDir = (Resolve-Path -LiteralPath ".").Path }
if ([string]::IsNullOrWhiteSpace($AiComposeService)) { $AiComposeService = "ai-module" }
if ([string]::IsNullOrWhiteSpace($AiContainerName)) { $AiContainerName = "pfi-ai-module" }
if ([string]::IsNullOrWhiteSpace($BackendComposeService)) { $BackendComposeService = "backend" }
if ([string]::IsNullOrWhiteSpace($PostgresComposeService)) { $PostgresComposeService = "postgres" }
if ([string]::IsNullOrWhiteSpace($PythonExe)) { $PythonExe = "python" }
if ([string]::IsNullOrWhiteSpace($OutputPath)) {
    $OutputPath = Join-Path ([System.IO.Path]::GetTempPath()) ("p10_5_f_backend_e2e_{0}.json" -f (Get-Date -Format "yyyyMMdd_HHmmss"))
}

$startedAt = Get-Date

function Add-Step([string]$name, [hashtable]$data) {
    $entry = [ordered]@{ name = $name; at = (Get-Date).ToString("o") }
    foreach ($key in $data.Keys) { $entry[$key] = $data[$key] }
    $script:summary.steps += $entry
    Write-Host ("[{0}] {1}" -f (Get-Date -Format "HH:mm:ss"), $name)
}

function Redact-Path([string]$value) {
    if ([string]::IsNullOrWhiteSpace($value)) { return $value }
    $leaf = Split-Path -Leaf $value
    if ([string]::IsNullOrWhiteSpace($leaf)) { return "[path]" }
    return "[path]\$leaf"
}

function Assert-True([bool]$condition, [string]$message) {
    if (!$condition) { throw $message }
}

function Convert-ToJsonBody($obj) {
    return ($obj | ConvertTo-Json -Depth 40 -Compress)
}

function Invoke-Json([string]$Method, [string]$Url, $Body = $null, [bool]$Authenticated = $true) {
    $client = [System.Net.Http.HttpClient]::new()
    try {
        $client.Timeout = [TimeSpan]::FromSeconds(240)
        $client.DefaultRequestHeaders.Add("X-Trace-Id", "p10-5-f-e2e-backend")
        if ($Authenticated) {
            Assert-True (![string]::IsNullOrWhiteSpace($script:AuthToken)) "Authenticated request requires PFI_E2E_AUTH_TOKEN or login credentials."
            $client.DefaultRequestHeaders.Authorization = [System.Net.Http.Headers.AuthenticationHeaderValue]::new("Bearer", $script:AuthToken)
        }
        $request = [System.Net.Http.HttpRequestMessage]::new([System.Net.Http.HttpMethod]::new($Method), $Url)
        if ($Body -ne $null) {
            $request.Content = [System.Net.Http.StringContent]::new((Convert-ToJsonBody $Body), [System.Text.Encoding]::UTF8, "application/json")
        }
        $response = $client.SendAsync($request).GetAwaiter().GetResult()
        $text = $response.Content.ReadAsStringAsync().GetAwaiter().GetResult()
        $json = $null
        if (![string]::IsNullOrWhiteSpace($text)) {
            try { $json = $text | ConvertFrom-Json } catch { $json = $null }
        }
        return [pscustomobject]@{ StatusCode = [int]$response.StatusCode; Success = $response.IsSuccessStatusCode; Json = $json; Body = $text; Headers = $response.Headers; ContentHeaders = $response.Content.Headers }
    } finally {
        $client.Dispose()
    }
}

function Invoke-UploadStudy([string]$ZipPath, [bool]$Authenticated = $true) {
    $client = [System.Net.Http.HttpClient]::new()
    $stream = $null
    $content = $null
    try {
        $client.Timeout = [TimeSpan]::FromSeconds(300)
        $client.DefaultRequestHeaders.Add("X-Trace-Id", "p10-5-f-e2e-backend")
        if ($Authenticated) {
            Assert-True (![string]::IsNullOrWhiteSpace($script:AuthToken)) "Upload requires PFI_E2E_AUTH_TOKEN or login credentials."
            $client.DefaultRequestHeaders.Authorization = [System.Net.Http.Headers.AuthenticationHeaderValue]::new("Bearer", $script:AuthToken)
        }
        $content = [System.Net.Http.MultipartFormDataContent]::new()
        $content.Add([System.Net.Http.StringContent]::new($CaseId), "caseId")
        $stream = [System.IO.File]::OpenRead((Resolve-Path -LiteralPath $ZipPath).Path)
        $fileContent = [System.Net.Http.StreamContent]::new($stream)
        $fileContent.Headers.ContentType = [System.Net.Http.Headers.MediaTypeHeaderValue]::Parse("application/zip")
        $content.Add($fileContent, "file", [System.IO.Path]::GetFileName($ZipPath))
        $response = $client.PostAsync("$BackendBaseUrl/api/ai/studies", $content).GetAwaiter().GetResult()
        $body = $response.Content.ReadAsStringAsync().GetAwaiter().GetResult()
        $json = $null
        if (![string]::IsNullOrWhiteSpace($body)) { try { $json = $body | ConvertFrom-Json } catch { $json = $null } }
        return [pscustomobject]@{ StatusCode = [int]$response.StatusCode; Success = $response.IsSuccessStatusCode; Json = $json; Body = $body }
    } finally {
        if ($content -ne $null) { $content.Dispose() }
        if ($stream -ne $null) { $stream.Dispose() }
        $client.Dispose()
    }
}

function Invoke-Binary([string]$Url) {
    $client = [System.Net.Http.HttpClient]::new()
    try {
        $client.Timeout = [TimeSpan]::FromSeconds(120)
        $client.DefaultRequestHeaders.Add("X-Trace-Id", "p10-5-f-e2e-backend")
        $client.DefaultRequestHeaders.Authorization = [System.Net.Http.Headers.AuthenticationHeaderValue]::new("Bearer", $script:AuthToken)
        $response = $client.GetAsync($Url).GetAwaiter().GetResult()
        $bytes = $response.Content.ReadAsByteArrayAsync().GetAwaiter().GetResult()
        $contentType = ""
        if ($response.Content.Headers.ContentType -ne $null) { $contentType = $response.Content.Headers.ContentType.MediaType }
        return [pscustomobject]@{ StatusCode = [int]$response.StatusCode; Success = $response.IsSuccessStatusCode; Bytes = $bytes; ContentType = $contentType; Headers = $response.Headers }
    } finally {
        $client.Dispose()
    }
}

function Get-ShortHash([byte[]]$Bytes) {
    $sha = [System.Security.Cryptography.SHA256]::Create()
    try {
        $hash = $sha.ComputeHash($Bytes)
        return ([BitConverter]::ToString($hash).Replace("-", "").ToLowerInvariant()).Substring(0, 16)
    } finally {
        $sha.Dispose()
    }
}

function Assert-NoUnsafeText($obj, [string]$context) {
    $text = Convert-ToJsonBody $obj
    foreach ($needle in @("sourcePath", "relativePath", "storageKey", "hostname", "token", "stackTrace", "stack trace", "C:\", "/tmp/", "http://ai-module", ":8000/assets", ":8000/inputs")) {
        Assert-True (!$text.Contains($needle)) "$context leaked unsafe value marker: $needle"
    }
}

function Get-Property($obj, [string]$name) {
    if ($null -eq $obj) { return $null }
    $prop = $obj.PSObject.Properties[$name]
    if ($null -eq $prop) { return $null }
    return $prop.Value
}

function Get-Plane($run, [string]$plane) {
    $planes = Get-Property $run "planes"
    if ($null -eq $planes) { return $null }
    return Get-Property $planes $plane
}

function Get-PlaneInput($plane) {
    $input = Get-Property $plane "input"
    if ($null -ne $input) { return $input }
    $metadata = Get-Property $plane "metadata"
    if ($null -ne $metadata) { return $metadata }
    return $null
}

function Get-Array($value) {
    if ($null -eq $value) { return @() }
    if ($value -is [System.Array]) { return @($value) }
    return @($value)
}

function Require-Asset($asset, [string]$label) {
    Assert-True ($asset -ne $null) "$label asset missing."
    $url = [string](Get-Property $asset "url")
    if ([string]::IsNullOrWhiteSpace($url)) { $url = [string](Get-Property $asset "href") }
    Assert-True (![string]::IsNullOrWhiteSpace($url)) "$label asset URL missing."
    Assert-True ($url.StartsWith("/api/ai/assets/")) "$label asset URL is not a backend-relative URL."
    return $url
}

function Get-AssetUrlFromSlice($slice, [string]$name) {
    $asset = Get-Property $slice $name
    if ($null -eq $asset) { return $null }
    $url = Get-Property $asset "url"
    if ($null -eq $url) { $url = Get-Property $asset "href" }
    return [string]$url
}

function Assert-PngAsset([string]$RelativeUrl, [string]$Plane, [string]$AssetName, [string]$Stage) {
    $download = Invoke-Binary "$BackendBaseUrl$RelativeUrl"
    Assert-True ($download.StatusCode -eq 200) "$Stage asset $AssetName returned HTTP $($download.StatusCode)."
    Assert-True ($download.ContentType -eq "image/png") "$Stage asset $AssetName content-type was $($download.ContentType)."
    Assert-True ($download.Bytes.Length -gt 8) "$Stage asset $AssetName payload is empty."
    Assert-True ($download.Bytes[0] -eq 137 -and $download.Bytes[1] -eq 80 -and $download.Bytes[2] -eq 78 -and $download.Bytes[3] -eq 71) "$Stage asset $AssetName is not PNG."
    $hash = Get-ShortHash $download.Bytes
    $script:summary.assets += [ordered]@{ stage = $Stage; plane = $Plane; assetName = $AssetName; status = 200; size = $download.Bytes.Length; sha256_16 = $hash }
    return [pscustomobject]@{ Bytes = $download.Bytes; Hash = $hash; Size = $download.Bytes.Length }
}

function Get-GitSha([string]$Path) {
    try {
        $sha = & git -C $Path rev-parse HEAD 2>$null
        if ($LASTEXITCODE -eq 0) { return [string]$sha }
    } catch {}
    return $null
}

function Resolve-AuthToken() {
    if (![string]::IsNullOrWhiteSpace($script:AuthToken)) {
        Add-Step "auth.token.provided" @{ token = "[redacted]" }
        return
    }
    if (![string]::IsNullOrWhiteSpace($AuthEmail) -and ![string]::IsNullOrWhiteSpace($AuthPassword)) {
        $login = Invoke-Json "POST" "$BackendBaseUrl/api/auth/login" @{ email = $AuthEmail; password = $AuthPassword } $false
        Assert-True ($login.StatusCode -eq 200) "Auth login failed with HTTP $($login.StatusCode)."
        $token = Get-Property $login.Json "accessToken"
        Assert-True (![string]::IsNullOrWhiteSpace($token)) "Auth login did not return accessToken; two-step verification may be required."
        $script:AuthToken = $token
        Add-Step "auth.login.completed" @{ token = "[redacted]"; email = "[redacted]" }
        return
    }
    if ($env:PFI_E2E_ALLOW_DEMO_AUTH -eq "1") {
        $demo = Invoke-Json "POST" "$BackendBaseUrl/api/auth/demo-doctor" $null $false
        Assert-True ($demo.StatusCode -eq 200) "Demo auth failed with HTTP $($demo.StatusCode)."
        $token = Get-Property $demo.Json "accessToken"
        Assert-True (![string]::IsNullOrWhiteSpace($token)) "Demo auth did not return accessToken."
        $script:AuthToken = $token
        $script:summary.limitations += "Authentication used local demo-doctor endpoint because PFI_E2E_ALLOW_DEMO_AUTH=1."
        Add-Step "auth.demo.completed" @{ token = "[redacted]" }
        return
    }
    throw "Provide PFI_E2E_AUTH_TOKEN, or PFI_E2E_AUTH_EMAIL/PFI_E2E_AUTH_PASSWORD, or explicitly opt into PFI_E2E_ALLOW_DEMO_AUTH=1."
}

function New-SyntheticDicomZip() {
    $out = Join-Path ([System.IO.Path]::GetTempPath()) ("p10_5_f_synthetic_study_{0}.zip" -f ([Guid]::NewGuid().ToString("N")))
    $python = @'
from pathlib import Path
import io, sys, zipfile
import numpy as np
import pydicom
from pydicom.dataset import Dataset, FileMetaDataset
from pydicom.uid import ExplicitVRLittleEndian, MRImageStorage, generate_uid

out = Path(sys.argv[1])
study_uid = generate_uid()

def make_slice(series_uid, plane, instance):
    ds = Dataset()
    meta = FileMetaDataset()
    meta.TransferSyntaxUID = ExplicitVRLittleEndian
    meta.MediaStorageSOPClassUID = MRImageStorage
    meta.MediaStorageSOPInstanceUID = generate_uid()
    meta.ImplementationClassUID = generate_uid()
    ds.file_meta = meta
    ds.SOPClassUID = MRImageStorage
    ds.SOPInstanceUID = meta.MediaStorageSOPInstanceUID
    ds.PatientName = "E2E^Synthetic"
    ds.PatientID = "P10-5-F-E2E"
    ds.Modality = "MR"
    ds.StudyInstanceUID = study_uid
    ds.SeriesInstanceUID = series_uid
    ds.SeriesDescription = ("Sagittal T2" if plane == "sagittal" else "Axial T2")
    ds.ImageType = ["ORIGINAL", "PRIMARY"]
    ds.InstanceNumber = instance
    ds.Rows = 64
    ds.Columns = 64
    ds.PixelSpacing = [1.0, 1.0]
    ds.SliceThickness = 3.0
    ds.BitsAllocated = 16
    ds.BitsStored = 16
    ds.HighBit = 15
    ds.PixelRepresentation = 0
    ds.SamplesPerPixel = 1
    ds.PhotometricInterpretation = "MONOCHROME2"
    if plane == "sagittal":
        ds.ImageOrientationPatient = [0, 1, 0, 0, 0, -1]
        ds.ImagePositionPatient = [float(instance), 0, 0]
    else:
        ds.ImageOrientationPatient = [1, 0, 0, 0, 1, 0]
        ds.ImagePositionPatient = [0, 0, float(instance)]
    arr = (np.ones((64, 64), dtype=np.uint16) * (instance * 100 + (20 if plane == "sagittal" else 40)))
    ds.PixelData = arr.tobytes()
    buf = io.BytesIO()
    pydicom.dcmwrite(buf, ds, write_like_original=False)
    return buf.getvalue()

with zipfile.ZipFile(out, "w", compression=zipfile.ZIP_DEFLATED) as zf:
    for plane in ("sagittal", "axial"):
        series_uid = generate_uid()
        for idx in range(1, 4):
            zf.writestr(f"{plane}/slice-{idx:03d}.dcm", make_slice(series_uid, plane, idx))
print(out)
'@
    $tmpScript = Join-Path ([System.IO.Path]::GetTempPath()) ("p10_5_f_make_dicom_{0}.py" -f ([Guid]::NewGuid().ToString("N")))
    Set-Content -LiteralPath $tmpScript -Value $python -Encoding UTF8
    try {
        & $PythonExe $tmpScript $out | Out-Null
    } finally {
        Remove-Item -LiteralPath $tmpScript -Force -ErrorAction SilentlyContinue
    }
    Assert-True (Test-Path -LiteralPath $out) "Synthetic DICOM ZIP was not created. Ensure Python has pydicom and numpy, or set PFI_E2E_STUDY_ZIP."
    return $out
}

function Compose([string[]]$CommandArgs) {
    Push-Location $ComposeProjectDir
    try {
        $composeArgs = @()
        foreach ($file in $script:ComposeFileList) {
            $composeArgs += @("-f", $file)
        }
        $previousErrorActionPreference = $ErrorActionPreference
        $ErrorActionPreference = "Continue"
        $output = & docker compose @composeArgs @CommandArgs 2>&1
        $exit = $LASTEXITCODE
        $ErrorActionPreference = $previousErrorActionPreference
        if ($exit -ne 0) { throw "docker compose $($CommandArgs -join ' ') failed: $output" }
        return ($output -join "`n")
    } finally {
        if ($null -ne $previousErrorActionPreference) {
            $ErrorActionPreference = $previousErrorActionPreference
        }
        Pop-Location
    }
}

function Test-ContainerRunning([string]$ContainerName) {
    try {
        $state = & docker inspect $ContainerName --format "{{.State.Running}}" 2>$null
        return [string]$state -eq "true"
    } catch {
        return $false
    }
}

function Wait-Http([string]$Url, [int]$Expected = 200, [int]$Seconds = 90, [bool]$Authenticated = $false) {
    $deadline = (Get-Date).AddSeconds($Seconds)
    do {
        try {
            $resp = Invoke-Json "GET" $Url $null $Authenticated
            if ($resp.StatusCode -eq $Expected) { return $resp }
        } catch {}
        Start-Sleep -Seconds 2
    } while ((Get-Date) -lt $deadline)
    throw "Timed out waiting for $Url HTTP $Expected."
}

$script:ComposeFileList = @()
if (![string]::IsNullOrWhiteSpace($ComposeFiles)) {
    $script:ComposeFileList = @(
        $ComposeFiles -split ';' |
            Where-Object { ![string]::IsNullOrWhiteSpace($_) } |
            ForEach-Object { (Resolve-Path -LiteralPath $_).Path }
    )
}

$summary = [ordered]@{
    startedAt = $startedAt.ToString("o")
    backendBaseUrl = $BackendBaseUrl
    aiBaseUrl = $AiBaseUrl
    composeProjectDir = Redact-Path $ComposeProjectDir
    composeFiles = @($script:ComposeFileList | ForEach-Object { Redact-Path $_ })
    services = [ordered]@{
        ai = $AiComposeService
        aiContainer = $AiContainerName
        backend = $BackendComposeService
        postgres = $PostgresComposeService
    }
    manageServices = $ManageServices.IsPresent
    backendGitSha = Get-GitSha (Resolve-Path -LiteralPath ".").Path
    expectedAiGitSha = "48d6dd8b02ec26eff3180b61a3d3583eca19b542"
    caseId = $CaseId
    steps = @()
    assets = @()
    limitations = @()
}

try {
    Add-Step "preflight" @{ backendBaseUrl = $BackendBaseUrl; aiBaseUrl = $AiBaseUrl; startedAt = $summary.startedAt }
    if ($ManageServices) {
        Add-Step "compose.up.requested" @{ services = @($PostgresComposeService, $AiComposeService, $BackendComposeService); composeFiles = $summary.composeFiles }
        Compose @("up", "-d", $PostgresComposeService, $AiComposeService, $BackendComposeService) | Out-Null
        Add-Step "compose.up.completed" @{ services = "postgres,ai,backend" }
    }
    Wait-Http "$BackendBaseUrl/api/system/health" 200 90 $false | Out-Null
    Wait-Http "$AiBaseUrl/health" 200 90 $false | Out-Null
    Add-Step "health.initial" @{ backend = "up"; ai = "up" }

    Resolve-AuthToken

    if ([string]::IsNullOrWhiteSpace($StudyZip) -and $GenerateSyntheticDicom) {
        $StudyZip = New-SyntheticDicomZip
    }
    if ([string]::IsNullOrWhiteSpace($StudyZip) -or !(Test-Path -LiteralPath $StudyZip)) {
        throw "PFI_E2E_STUDY_ZIP is required unless PFI_E2E_GENERATE_SYNTHETIC_DICOM=1 succeeds."
    }

    $unauthUpload = Invoke-UploadStudy $StudyZip $false
    Assert-True ($unauthUpload.StatusCode -eq 401) "Unauthenticated upload expected 401, got HTTP $($unauthUpload.StatusCode)."
    Add-Step "auth.unauthenticated_upload_rejected" @{ status = 401 }

    $upload = Invoke-UploadStudy $StudyZip $true
    Assert-True ($upload.StatusCode -eq 200) "Study upload failed with HTTP $($upload.StatusCode)."
    Assert-NoUnsafeText $upload.Json "upload"
    Assert-True ([string](Get-Property $upload.Json "caseId") -eq $CaseId) "Upload caseId mismatch."
    Assert-True (![string]::IsNullOrWhiteSpace([string](Get-Property $upload.Json "studyId"))) "Upload studyId missing."
    $sagittal = Get-Property $upload.Json "sagittal"
    $axial = Get-Property $upload.Json "axial"
    $sagittalInputId = if ($sagittal -ne $null) { [string](Get-Property $sagittal "inputId") } else { "" }
    $axialInputId = if ($axial -ne $null) { [string](Get-Property $axial "inputId") } else { "" }
    Assert-True (![string]::IsNullOrWhiteSpace($sagittalInputId) -or ![string]::IsNullOrWhiteSpace($axialInputId)) "Upload returned no plane inputId."
    Assert-True ((Get-Array (Get-Property $upload.Json "seriesFound")).Count -gt 0) "Upload seriesFound missing."
    $warningCount = (Get-Array (Get-Property $upload.Json "warnings")).Count
    Add-Step "upload.completed" @{ status = 200; studyIdPresent = $true; sagittalInputIdPresent = (![string]::IsNullOrWhiteSpace($sagittalInputId)); axialInputIdPresent = (![string]::IsNullOrWhiteSpace($axialInputId)); seriesCount = (Get-Array (Get-Property $upload.Json "seriesFound")).Count; warningCount = $warningCount }

    $runRequest = [ordered]@{
        caseId = $CaseId
        allowContractFallback = $false
        metadata = [ordered]@{ inferenceMode = "real_baseline"; requestedBy = "p10-5-f-backend-e2e" }
    }
    if (![string]::IsNullOrWhiteSpace($sagittalInputId)) { $runRequest.sagittalInputId = $sagittalInputId }
    if (![string]::IsNullOrWhiteSpace($axialInputId)) { $runRequest.axialInputId = $axialInputId }
    $run = Invoke-Json "POST" "$BackendBaseUrl/api/ai/multiplanar/run" $runRequest $true
    if ($run.StatusCode -ne 200) {
        $summary.lastRunError = [ordered]@{
            status = $run.StatusCode
            body = if ($run.Body.Length -gt 1200) { $run.Body.Substring(0, 1200) } else { $run.Body }
        }
    }
    Assert-True ($run.StatusCode -eq 200) "Multiplanar run failed with HTTP $($run.StatusCode)."
    Assert-NoUnsafeText $run.Json "multiplanar run"
    $runId = [string](Get-Property $run.Json "runId")
    if ([string]::IsNullOrWhiteSpace($runId)) { $runId = [string](Get-Property $run.Json "multiplanarRunId") }
    Assert-True (![string]::IsNullOrWhiteSpace($runId)) "multiplanar runId missing."
    Assert-True ([string](Get-Property $run.Json "effectiveInferenceMode") -eq "real_baseline") "effectiveInferenceMode is not real_baseline."
    Assert-True ((Get-Property $run.Json "degradedMode") -ne $true) "Run degradedMode unexpectedly true."

    $verifiedPlanes = @()
    $selectedAsset = $null
    $nonResultAsset = $null
    $overlayAsset = $null
    $correctionTarget = $null
    foreach ($planeName in @("sagittal", "axial")) {
        $plane = Get-Plane $run.Json $planeName
        if ($null -eq $plane) { continue }
        $input = Get-PlaneInput $plane
        Assert-True ($input -ne $null) "$planeName input/metadata missing."
        $sliceCount = [int](Get-Property $input "sliceCount")
        $selectedSliceIndex = [int](Get-Property $input "selectedSliceIndex")
        $slices = Get-Array (Get-Property $input "slices")
        Assert-True ($sliceCount -gt 0) "$planeName sliceCount invalid."
        Assert-True ($selectedSliceIndex -ge 0 -and $selectedSliceIndex -lt $sliceCount) "$planeName selectedSliceIndex out of range."
        Assert-True ($slices.Count -gt 0) "$planeName slices missing."
        foreach ($slice in $slices) {
            $idx = [int](Get-Property $slice "index")
            $display = [int](Get-Property $slice "displayIndex")
            Assert-True ($idx -ge 0 -and $idx -lt $sliceCount) "$planeName slice index out of range."
            Assert-True ($display -eq ($idx + 1)) "$planeName displayIndex is not 1-based."
            $previewUrl = Get-AssetUrlFromSlice $slice "previewAsset"
            Assert-True (![string]::IsNullOrWhiteSpace($previewUrl)) "$planeName slice $idx previewAsset missing."
            Assert-True ($previewUrl.StartsWith("/api/ai/assets/")) "$planeName preview URL not backend-relative."
            if ($idx -eq $selectedSliceIndex) { $selectedAsset = [pscustomobject]@{ Url = $previewUrl; Plane = $planeName; Name = ([System.IO.Path]::GetFileName($previewUrl)) } }
            if ((Get-Property $slice "hasResults") -ne $true -and $null -eq $nonResultAsset) { $nonResultAsset = [pscustomobject]@{ Url = $previewUrl; Plane = $planeName; Name = ([System.IO.Path]::GetFileName($previewUrl)) } }
            if ((Get-Property $slice "hasResults") -eq $true) {
                $overlayUrl = Get-AssetUrlFromSlice $slice "overlayAsset"
                Assert-True (![string]::IsNullOrWhiteSpace($overlayUrl)) "$planeName result slice overlayAsset missing."
                Assert-True ($overlayUrl.StartsWith("/api/ai/assets/")) "$planeName overlay URL not backend-relative."
                if ($null -eq $overlayAsset) { $overlayAsset = [pscustomobject]@{ Url = $overlayUrl; Plane = $planeName; Name = ([System.IO.Path]::GetFileName($overlayUrl)) } }
                $measurementIds = Get-Array (Get-Property $slice "measurementIds")
                if ($measurementIds.Count -gt 0 -and $null -eq $correctionTarget) {
                    $correctionTarget = [pscustomobject]@{ Plane = $planeName; SliceIndex = $idx; MeasurementId = [string]$measurementIds[0] }
                }
            }
        }
        $verifiedPlanes += [ordered]@{ plane = $planeName; sliceCount = $sliceCount; selectedSliceIndex = $selectedSliceIndex; slices = $slices.Count }
    }
    Assert-True ($verifiedPlanes.Count -gt 0) "No planes were verified."
    Assert-True ($selectedAsset -ne $null) "Selected slice preview asset was not found."
    Assert-True ($correctionTarget -ne $null) "No real measurementId found for professional correction."
    Add-Step "multiplanar.completed" @{ status = 200; runId = $runId; planes = $verifiedPlanes }

    if ($nonResultAsset -ne $null) { Assert-PngAsset $nonResultAsset.Url $nonResultAsset.Plane $nonResultAsset.Name "before_ai_stop.preview_without_results" | Out-Null }
    $selectedBefore = Assert-PngAsset $selectedAsset.Url $selectedAsset.Plane $selectedAsset.Name "before_ai_stop.selected_preview"
    $overlayBefore = $null
    if ($overlayAsset -ne $null) { $overlayBefore = Assert-PngAsset $overlayAsset.Url $overlayAsset.Plane $overlayAsset.Name "before_ai_stop.overlay" }
    Add-Step "assets.before_ai_stop.completed" @{ count = $summary.assets.Count }

    $reviewPayload = [ordered]@{
        reviewStatus = "observed"
        reviewer = "p10-5-f-e2e-reviewer"
        comments = "Correccion academica pseudonima para validar persistencia durable."
        corrections = @(
            [ordered]@{
                measurementId = $correctionTarget.MeasurementId
                label = "E2E measurement correction"
                beforeValue = [ordered]@{ value = 1.0; unit = "mm"; plane = $correctionTarget.Plane; sliceIndex = $correctionTarget.SliceIndex }
                afterValue = [ordered]@{ value = 1.5; unit = "mm"; plane = $correctionTarget.Plane; sliceIndex = $correctionTarget.SliceIndex }
                comment = "Ajuste E2E pseudonimo sin identificadores."
            }
        )
    }
    $review = Invoke-Json "POST" "$BackendBaseUrl/api/ai/runs/$runId/review" $reviewPayload $true
    Assert-True ($review.StatusCode -eq 200) "Review save failed with HTTP $($review.StatusCode)."
    Assert-True ([string](Get-Property $review.Json "reviewStatus") -eq "observed") "Review status was not observed."
    $reviewCorrectionCount = (Get-Array (Get-Property $review.Json "corrections")).Count
    Add-Step "review.correction.saved" @{ status = 200; runId = $runId; plane = $correctionTarget.Plane; sliceIndex = $correctionTarget.SliceIndex; measurementIdPresent = $true; correctionEchoCount = $reviewCorrectionCount }

    $study = Invoke-Json "GET" "$BackendBaseUrl/api/studies/$CaseId" $null $true
    $runs = Invoke-Json "GET" "$BackendBaseUrl/api/studies/$CaseId/runs" $null $true
    Assert-True ($study.StatusCode -eq 200) "First reopen study failed HTTP $($study.StatusCode)."
    Assert-True ($runs.StatusCode -eq 200) "First reopen runs failed HTTP $($runs.StatusCode)."
    Assert-NoUnsafeText $study.Json "first reopen study"
    Assert-NoUnsafeText $runs.Json "first reopen runs"
    Assert-True ((Convert-ToJsonBody $runs.Json).Contains($correctionTarget.MeasurementId)) "First reopen does not include correction measurementId."
    Add-Step "reopen.first.completed" @{ studyStatus = 200; runsStatus = 200; correctionFound = $true }

    Add-Step "ai.stop.requested" @{ service = $AiComposeService }
    Compose @("stop", $AiComposeService) | Out-Null
    Start-Sleep -Seconds 5
    $aiRunningAfterStop = Test-ContainerRunning $AiContainerName
    $aiDown = $false
    try { Invoke-Json "GET" "$AiBaseUrl/health" $null $false | Out-Null } catch { $aiDown = $true }
    if (!$aiDown) {
        $health = Invoke-Json "GET" "$AiBaseUrl/health" $null $false
        $aiDown = ($health.StatusCode -ge 500 -or $health.StatusCode -eq 0)
    }
    Assert-True (!$aiRunningAfterStop) "AI service is still running after docker compose stop."
    Assert-True ($aiDown) "AI health endpoint still responds after docker compose stop."
    Wait-Http "$BackendBaseUrl/api/system/health" 200 60 $false | Out-Null
    Add-Step "ai.stopped.backend_alive" @{ aiService = $AiComposeService; aiUnavailable = $aiDown; backend = "up" }

    if ($RestartBackend) {
        Add-Step "backend.restart.requested" @{ service = $BackendComposeService; postgresKept = $true }
        Compose @("restart", $BackendComposeService) | Out-Null
        Wait-Http "$BackendBaseUrl/api/system/health" 200 120 $false | Out-Null
        Add-Step "backend.restart.completed" @{ service = $BackendComposeService }
    } else {
        $summary.limitations += "Backend restart was not requested. Set PFI_E2E_RESTART_BACKEND=1 to force process-cache eviction."
    }

    $study2 = Invoke-Json "GET" "$BackendBaseUrl/api/studies/$CaseId" $null $true
    $runs2 = Invoke-Json "GET" "$BackendBaseUrl/api/studies/$CaseId/runs" $null $true
    Assert-True ($study2.StatusCode -eq 200) "Second reopen study with AI stopped failed HTTP $($study2.StatusCode)."
    Assert-True ($runs2.StatusCode -eq 200) "Second reopen runs with AI stopped failed HTTP $($runs2.StatusCode)."
    Assert-NoUnsafeText $study2.Json "second reopen study"
    Assert-NoUnsafeText $runs2.Json "second reopen runs"
    Assert-True ((Convert-ToJsonBody $runs2.Json).Contains($correctionTarget.MeasurementId)) "Second reopen does not include persisted correction."
    $selectedAfter = Assert-PngAsset $selectedAsset.Url $selectedAsset.Plane $selectedAsset.Name "after_ai_stop.selected_preview"
    Assert-True ($selectedAfter.Hash -eq $selectedBefore.Hash) "Selected preview SHA changed after AI stopped."
    if ($overlayAsset -ne $null -and $overlayBefore -ne $null) {
        $overlayAfter = Assert-PngAsset $overlayAsset.Url $overlayAsset.Plane $overlayAsset.Name "after_ai_stop.overlay"
        Assert-True ($overlayAfter.Hash -eq $overlayBefore.Hash) "Overlay SHA changed after AI stopped."
    }
    Add-Step "reopen.second_ai_stopped.completed" @{ studyStatus = 200; runsStatus = 200; durableAssetShaMatched = $true }

    $logs = Compose @("logs", "--since", $startedAt.ToUniversalTime().ToString("yyyy-MM-ddTHH:mm:ssZ"), "--tail", "300", $BackendComposeService)
    $egressLines = @($logs -split "`n" | Where-Object { $_ -match "/assets|/multiplanar/run|/inputs|/inputs/study" } | Select-Object -First 20)
    $summary.logs = [ordered]@{ backendScanned = $true; matchingLines = $egressLines.Count; sample = @($egressLines | ForEach-Object { $_ -replace 'Bearer\s+[A-Za-z0-9._~+/=-]+', 'Bearer [redacted]' }) }
    Add-Step "logs.scanned" @{ backendLogLinesMatchingAiPaths = $egressLines.Count }

} catch {
    $summary.failedAt = (Get-Date).ToString("o")
    $summary.error = $_.Exception.Message
    throw
} finally {
    try {
        Add-Step "ai.restore.requested" @{ service = $AiComposeService }
        Compose @("start", $AiComposeService) | Out-Null
        Wait-Http "$AiBaseUrl/health" 200 120 $false | Out-Null
        Add-Step "ai.restore.completed" @{ ai = "up" }
    } catch {
        $summary.limitations += "AI Module restore failed: $($_.Exception.Message)"
    }
    $summary.finishedAt = (Get-Date).ToString("o")
    $summary.durationSeconds = [Math]::Round(((Get-Date) - $startedAt).TotalSeconds, 3)
    $summary.fixture = [ordered]@{
        source = if ($GenerateSyntheticDicom) { "synthetic-temporary" } else { "external-local" }
        zipPath = Redact-Path $StudyZip
    }
    $summary | ConvertTo-Json -Depth 40 | Set-Content -LiteralPath $OutputPath -Encoding UTF8
    Write-Host "Sanitized E2E summary: $OutputPath"
}
