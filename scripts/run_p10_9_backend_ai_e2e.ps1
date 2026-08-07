param(
    [string]$BackendBaseUrl = $(if ($env:PFI_BACKEND_BASE_URL) { $env:PFI_BACKEND_BASE_URL } else { "http://localhost:8080" }),
    [string]$AiBaseUrl = $(if ($env:PFI_AI_BASE_URL) { $env:PFI_AI_BASE_URL } else { "http://localhost:8000" }),
    [string]$ResultsDir = $(if ($env:PFI_P10_9_RESULTS_DIR) { $env:PFI_P10_9_RESULTS_DIR } else { "results/P10_9_backend_ai_e2e" })
)

$ErrorActionPreference = "Stop"

$ExpectedP10_6Sha = "d41262d57b13c146a48ab15f5e183cc6a55fc92724b7d0c286cea1f2ce26e84a"
$ExpectedP10_7Sha = "16eccff327e6794b127fe372ecd03ea619a0f69d939b84ae1aa2e904191c6293"
$P10_6Path = if ($env:PFI_SUBARTICULAR_CHECKPOINT_FILE) { $env:PFI_SUBARTICULAR_CHECKPOINT_FILE } else { "../PFI_MVPTest_Enzo_AImodule/models/subarticular/frozen_subarticular_checkpoint.pt" }
$P10_7Path = if ($env:PFI_P10_7_CHECKPOINT_FILE) { $env:PFI_P10_7_CHECKPOINT_FILE } else { "../PFI_MVPTest_Enzo_AImodule/models/disc-degenerative/frozen_p10_7_spider_degenerative_multitask.pt" }
$CaseId = if ($env:PFI_E2E_CASE_ID) { $env:PFI_E2E_CASE_ID } else { "P10-9-REAL-STUDY" }

$BackendBaseUrl = $BackendBaseUrl.TrimEnd("/")
$AiBaseUrl = $AiBaseUrl.TrimEnd("/")
New-Item -ItemType Directory -Path $ResultsDir -Force | Out-Null

$script:Responses = @()
$script:Gates = [ordered]@{}
$script:Problems = New-Object System.Collections.Generic.List[string]

function Add-Gate {
    param([string]$Name, [string]$Status, [string]$Evidence, [string]$Problem = "")
    if (@("PASS", "FAIL", "BLOCKED", "NOT_APPLICABLE") -notcontains $Status) {
        throw "Invalid gate status for ${Name}: ${Status}"
    }
    $script:Gates[$Name] = [ordered]@{
        status = $Status
        evidence = $Evidence
        problem = $Problem
    }
    if ($Status -eq "FAIL") { $script:Problems.Add("${Name}: ${Problem}") | Out-Null }
}

function Safe-Request {
    param([string]$Method, [string]$Uri, [hashtable]$Headers = @{}, $Body = $null)
    try {
        $args = @{ Method = $Method; Uri = $Uri; Headers = $Headers; TimeoutSec = 180 }
        if ($null -ne $Body) {
            $args["ContentType"] = "application/json"
            $args["Body"] = ($Body | ConvertTo-Json -Depth 30)
        }
        $response = Invoke-RestMethod @args
        $script:Responses += [ordered]@{ uri = $Uri; status = "ok"; body = (Redact-Sensitive $response) }
        return $response
    } catch {
        $message = $_.Exception.Message
        $script:Responses += [ordered]@{ uri = $Uri; status = "error"; message = $message }
        throw
    }
}

function Convert-Json-Body {
    param([string]$Json)
    if ([string]::IsNullOrWhiteSpace($Json)) { return $null }
    return $Json | ConvertFrom-Json
}

function Post-MultipartFile {
    param([string]$Uri, [hashtable]$Headers = @{}, [string]$FilePath, [hashtable]$Fields = @{})
    Add-Type -AssemblyName System.Net.Http
    $client = New-Object System.Net.Http.HttpClient
    $content = New-Object System.Net.Http.MultipartFormDataContent
    $fileStream = [System.IO.File]::OpenRead($FilePath)
    try {
        foreach ($key in $Headers.Keys) {
            if ($key -ieq "Authorization") {
                $parts = ([string]$Headers[$key]) -split " ", 2
                if ($parts.Count -eq 2) {
                    $client.DefaultRequestHeaders.Authorization = New-Object System.Net.Http.Headers.AuthenticationHeaderValue($parts[0], $parts[1])
                }
            } else {
                [void]$client.DefaultRequestHeaders.TryAddWithoutValidation($key, [string]$Headers[$key])
            }
        }
        $fileContent = New-Object System.Net.Http.StreamContent($fileStream)
        $fileContent.Headers.ContentType = [System.Net.Http.Headers.MediaTypeHeaderValue]::Parse("application/zip")
        $content.Add($fileContent, "file", [System.IO.Path]::GetFileName($FilePath))
        foreach ($field in $Fields.Keys) {
            $content.Add((New-Object System.Net.Http.StringContent([string]$Fields[$field])), $field)
        }
        $httpResponse = $client.PostAsync($Uri, $content).GetAwaiter().GetResult()
        $body = $httpResponse.Content.ReadAsStringAsync().GetAwaiter().GetResult()
        if (!$httpResponse.IsSuccessStatusCode) {
            $script:Responses += [ordered]@{ uri = $Uri; status = "error"; httpStatus = [int]$httpResponse.StatusCode; message = $httpResponse.ReasonPhrase }
            throw "HTTP $([int]$httpResponse.StatusCode) $($httpResponse.ReasonPhrase) from $Uri"
        }
        $parsed = Convert-Json-Body -Json $body
        $script:Responses += [ordered]@{ uri = $Uri; status = "ok"; body = (Redact-Sensitive $parsed) }
        return $parsed
    } finally {
        $content.Dispose()
        $fileStream.Dispose()
        $client.Dispose()
    }
}

function Get-Binary {
    param([string]$Uri, [hashtable]$Headers = @{})
    Add-Type -AssemblyName System.Net.Http
    $client = New-Object System.Net.Http.HttpClient
    try {
        foreach ($key in $Headers.Keys) {
            if ($key -ieq "Authorization") {
                $parts = ([string]$Headers[$key]) -split " ", 2
                if ($parts.Count -eq 2) {
                    $client.DefaultRequestHeaders.Authorization = New-Object System.Net.Http.Headers.AuthenticationHeaderValue($parts[0], $parts[1])
                }
            } else {
                [void]$client.DefaultRequestHeaders.TryAddWithoutValidation($key, [string]$Headers[$key])
            }
        }
        $response = $client.GetAsync($Uri).GetAwaiter().GetResult()
        $bytes = $response.Content.ReadAsByteArrayAsync().GetAwaiter().GetResult()
        $contentType = ""
        if ($null -ne $response.Content.Headers.ContentType) {
            $contentType = [string]$response.Content.Headers.ContentType.MediaType
        }
        return [ordered]@{
            statusCode = [int]$response.StatusCode
            contentType = $contentType
            bytes = $bytes.Length
            success = $response.IsSuccessStatusCode
        }
    } finally {
        $client.Dispose()
    }
}

function Resolve-StudyUploadZip {
    $zip = $env:PFI_E2E_STUDY_ZIP
    if (![string]::IsNullOrWhiteSpace($zip)) {
        $resolvedZip = Resolve-Path -LiteralPath $zip -ErrorAction SilentlyContinue
        if ($null -eq $resolvedZip) { throw "PFI_E2E_STUDY_ZIP does not exist: $zip" }
        return $resolvedZip.Path
    }

    $studyPath = $env:PFI_E2E_STUDY_PATH
    if ([string]::IsNullOrWhiteSpace($studyPath)) { return $null }
    $resolvedStudy = Resolve-Path -LiteralPath $studyPath -ErrorAction SilentlyContinue
    if ($null -eq $resolvedStudy) { throw "PFI_E2E_STUDY_PATH does not exist: $studyPath" }
    if ((Get-Item -LiteralPath $resolvedStudy.Path).PSIsContainer -eq $false) {
        if ([System.IO.Path]::GetExtension($resolvedStudy.Path) -ieq ".zip") { return $resolvedStudy.Path }
        throw "PFI_E2E_STUDY_PATH must be a folder or .zip: $studyPath"
    }

    $zipPath = Join-Path $ResultsDir ("study_upload_" + (Split-Path -Leaf $resolvedStudy.Path) + ".zip")
    Compress-Archive -LiteralPath $resolvedStudy.Path -DestinationPath $zipPath -Force
    return (Resolve-Path -LiteralPath $zipPath).Path
}

function Find-Series {
    param($Study, [string]$Plane, [string]$Weighting)
    foreach ($series in @($Study.seriesFound)) {
        if ($series.plane -eq $Plane -and $series.weighting -eq $Weighting) { return $series }
    }
    return $null
}

function Count-List {
    param($Value)
    if ($null -eq $Value) { return 0 }
    if ($Value -is [System.Collections.IEnumerable] -and $Value -isnot [string]) {
        $count = 0
        foreach ($item in $Value) { $count += 1 }
        return $count
    }
    return 0
}

function First-Text {
    param($Values)
    foreach ($value in $Values) {
        if ($null -ne $value -and ![string]::IsNullOrWhiteSpace([string]$value)) { return [string]$value }
    }
    return ""
}

function Header-Text {
    param($Headers, [string]$Name)
    if ($null -eq $Headers) { return "" }
    try {
        $value = $Headers[$Name]
        if ($value -is [System.Collections.IEnumerable] -and $value -isnot [string]) {
            return First-Text @($value)
        }
        return First-Text @($value)
    } catch {
        try {
            return First-Text @($Headers.$Name)
        } catch {
            return ""
        }
    }
}

function Response-Body-Length {
    param($Response)
    if ($null -eq $Response -or $null -eq $Response.Content) { return 0 }
    try { return [int64]$Response.Content.Length } catch { return 0 }
}

function Redact-Sensitive {
    param($Value)
    if ($null -eq $Value) { return $null }
    if ($Value -is [System.Collections.IDictionary]) {
        $copy = [ordered]@{}
        foreach ($key in $Value.Keys) {
            $name = [string]$key
            if ($name -match '(?i)(token|secret|password|authorization|credential)') {
                $copy[$name] = "[REDACTED]"
            } else {
                $copy[$name] = Redact-Sensitive $Value[$key]
            }
        }
        return $copy
    }
    if ($Value -is [System.Collections.IEnumerable] -and $Value -isnot [string]) {
        $items = @()
        foreach ($item in $Value) { $items += ,(Redact-Sensitive $item) }
        return $items
    }
    if ($Value.PSObject -and $Value.PSObject.Properties.Count -gt 0 -and $Value.GetType().Namespace -ne "System") {
        $copy = [ordered]@{}
        foreach ($property in $Value.PSObject.Properties) {
            if ($property.Name -match '(?i)(token|secret|password|authorization|credential)') {
                $copy[$property.Name] = "[REDACTED]"
            } else {
                $copy[$property.Name] = Redact-Sensitive $property.Value
            }
        }
        return $copy
    }
    return $Value
}

function Get-OptionalToken {
    if (![string]::IsNullOrWhiteSpace($env:PFI_BACKEND_BEARER_TOKEN)) {
        return $env:PFI_BACKEND_BEARER_TOKEN
    }
    if ($env:PFI_E2E_ENABLE_DEMO_AUTH -eq "1") {
        $demo = Safe-Request -Method Post -Uri "$BackendBaseUrl/api/auth/demo-doctor"
        if ($demo.accessToken) { return $demo.accessToken }
        throw "Demo auth did not return accessToken."
    }
    return $null
}

function Auth-Headers {
    param([string]$Token)
    if ([string]::IsNullOrWhiteSpace($Token)) { return @{} }
    return @{ Authorization = "Bearer $Token" }
}

function Hash-File-Or-Blocked {
    param([string]$Path, [string]$Expected)
    $resolved = Resolve-Path -LiteralPath $Path -ErrorAction SilentlyContinue
    if ($null -eq $resolved) {
        return [ordered]@{ present = $false; expected = $Expected; observed = $null; path = $Path }
    }
    $hash = (Get-FileHash -LiteralPath $resolved.Path -Algorithm SHA256).Hash.ToLowerInvariant()
    return [ordered]@{ present = $true; expected = $Expected; observed = $hash; match = ($hash -eq $Expected); path = $resolved.Path }
}

function Hash-Container-File {
    param([string]$Container, [string]$Path, [string]$Expected)
    try {
        $output = & docker exec $Container sha256sum $Path 2>$null
        if ($LASTEXITCODE -ne 0 -or [string]::IsNullOrWhiteSpace($output)) {
            return [ordered]@{ present = $false; expected = $Expected; observed = $null; path = $Path; container = $Container }
        }
        $hash = ($output -split "\s+")[0].ToLowerInvariant()
        return [ordered]@{ present = $true; expected = $Expected; observed = $hash; match = ($hash -eq $Expected); path = $Path; container = $Container }
    } catch {
        return [ordered]@{ present = $false; expected = $Expected; observed = $null; path = $Path; container = $Container; error = $_.Exception.Message }
    }
}

function Test-NoSensitiveLeak {
    param($Objects)
    $json = ($Objects | ConvertTo-Json -Depth 50)
    $patterns = @(
        "PatientName", "PatientID", "PatientBirthDate", "AccessionNumber",
        "[A-Za-z]:\\", "/content", "Google Drive", "/models/", "/tmp/", "Authorization",
        "password", "998688940", "2089880748", "2145914008", "4135322219",
        "eyJ[A-Za-z0-9_-]+\.[A-Za-z0-9_-]+\.[A-Za-z0-9_-]+"
    )
    $hits = @()
    foreach ($pattern in $patterns) {
        if ($json -match $pattern) { $hits += $pattern }
    }
    return $hits
}

$result = [ordered]@{
    schemaVersion = "pfi.p10-9.backend-ai-e2e-result.v1"
    generatedAt = (Get-Date).ToUniversalTime().ToString("o")
    trainingExecuted = $false
    sealedTestAccessed = $false
    humanReviewRequired = $true
    notClinicalDiagnosis = $true
    gates = $script:Gates
    checkpoints = [ordered]@{}
    e2e = [ordered]@{}
}

$p10_6 = Hash-File-Or-Blocked -Path $P10_6Path -Expected $ExpectedP10_6Sha
$p10_7 = Hash-File-Or-Blocked -Path $P10_7Path -Expected $ExpectedP10_7Sha
$p10_6Container = Hash-Container-File -Container "pfi-ai-module" -Path "/models/subarticular/frozen_subarticular_checkpoint.pt" -Expected $ExpectedP10_6Sha
$p10_7Container = Hash-Container-File -Container "pfi-ai-module" -Path "/models/disc-degenerative/frozen_p10_7_spider_degenerative_multitask.pt" -Expected $ExpectedP10_7Sha
$result.checkpoints["p10_6"] = $p10_6
$result.checkpoints["p10_7"] = $p10_7
$result.checkpoints["p10_6Container"] = $p10_6Container
$result.checkpoints["p10_7Container"] = $p10_7Container
if ($p10_6.present -and $p10_6.match -and $p10_7.present -and $p10_7.match -and $p10_6Container.present -and $p10_6Container.match -and $p10_7Container.present -and $p10_7Container.match) {
    Add-Gate "frozenCheckpoints" "PASS" "Host and pfi-ai-module container checkpoint files exist and SHA-256 matches expected P10.6/P10.7 values."
} else {
    Add-Gate "frozenCheckpoints" "BLOCKED" "One or more checkpoint files missing or mismatched." "Place the exact frozen .pt files at the configured paths."
}

try {
    $systemHealth = Safe-Request -Method Get -Uri "$BackendBaseUrl/api/system/health"
    $aiHealth = Safe-Request -Method Get -Uri "$AiBaseUrl/health"
    Add-Gate "infrastructure" "PASS" "Backend public liveness and AI Module health responded."
} catch {
    Add-Gate "infrastructure" "FAIL" "Could not verify live Backend/AI health." $_.Exception.Message
}

$token = Get-OptionalToken
if ([string]::IsNullOrWhiteSpace($token)) {
    Add-Gate "backendAiHttp" "BLOCKED" "No Backend bearer token available." "Set PFI_BACKEND_BEARER_TOKEN or run local demo auth with PFI_E2E_ENABLE_DEMO_AUTH=1 and PFI_AUTH_DEMO_ENABLED=true."
} else {
    $headers = Auth-Headers -Token $token
    try {
        $health = Safe-Request -Method Get -Uri "$BackendBaseUrl/api/ai/health" -Headers $headers
        $readiness = Safe-Request -Method Get -Uri "$BackendBaseUrl/api/ai/readiness" -Headers $headers
        $models = Safe-Request -Method Get -Uri "$BackendBaseUrl/api/ai/models" -Headers $headers
        $checkpoint = Safe-Request -Method Get -Uri "$BackendBaseUrl/api/ai/v2/product/checkpoint" -Headers $headers
        $result.e2e["backendAiHealth"] = $health
        $result.e2e["backendAiReadiness"] = $readiness
        $result.e2e["backendAiModels"] = $models
        $result.e2e["productCheckpoint"] = $checkpoint
        Add-Gate "backendAiHttp" "PASS" "Backend reached AI Module through authenticated product/diagnostic routes."

        $p10_6Runtime = $checkpoint.p10_6.subarticular
        $p10_7Runtime = $checkpoint.p10_7.checkpointRuntime
        if ($p10_6Runtime.checkpointHashExpected -eq $ExpectedP10_6Sha -and $p10_7Runtime.checkpointHashExpected -eq $ExpectedP10_7Sha) {
            Add-Gate "runtimeCheckpointContract" "PASS" "Backend product checkpoint exposes expected P10.6/P10.7 checkpoint contracts."
        } else {
            Add-Gate "runtimeCheckpointContract" "FAIL" "Unexpected runtime checkpoint contract." "Expected SHA values were not exposed by product checkpoint."
        }
    } catch {
        Add-Gate "backendAiHttp" "FAIL" "Authenticated Backend to AI route failed." $_.Exception.Message
    }
}

$study = $null
$sagittalInputId = $env:PFI_E2E_SAGITTAL_INPUT_ID
$axialInputId = $env:PFI_E2E_AXIAL_INPUT_ID
$studyZip = Resolve-StudyUploadZip

if ($null -eq $studyZip) {
    Add-Gate "studyIngestion" "BLOCKED" "No complete DICOM study fixture configured." "Set PFI_E2E_STUDY_PATH or PFI_E2E_STUDY_ZIP to exercise POST /api/ai/studies."
    Add-Gate "series" "BLOCKED" "Series classification requires a real uploaded study." "No DICOM fixture configured."
} elseif ([string]::IsNullOrWhiteSpace($token)) {
    Add-Gate "studyIngestion" "BLOCKED" "Study upload requires Backend auth." "Provide token or enable demo auth locally."
    Add-Gate "series" "BLOCKED" "Series gate depends on successful study ingestion." "Backend auth unavailable."
} else {
    try {
        $study = Post-MultipartFile -Uri "$BackendBaseUrl/api/ai/studies" -Headers (Auth-Headers -Token $token) -FilePath $studyZip -Fields @{ caseId = $CaseId }
        $result.e2e["studyUpload"] = $study
        if ($study.humanReviewRequired -eq $true -and $study.notClinicalDiagnosis -eq $true -and (Count-List $study.seriesFound) -ge 3 -and $study.sagittal -and $study.axial) {
            Add-Gate "studyIngestion" "PASS" "Backend /api/ai/studies accepted the complete DICOM ZIP and returned public study/input metadata with review safety flags."
        } else {
            Add-Gate "studyIngestion" "FAIL" "Study upload response did not satisfy the product upload contract." "Expected at least three listed series plus sagittal and axial usable inputs."
        }

        $sagittalInputId = First-Text @($sagittalInputId, $study.sagittal.inputId)
        $axialInputId = First-Text @($axialInputId, $study.axial.inputId)
        $sagT1 = Find-Series -Study $study -Plane "sagittal" -Weighting "t1"
        $sagT2 = Find-Series -Study $study -Plane "sagittal" -Weighting "t2"
        $axialT2 = Find-Series -Study $study -Plane "axial" -Weighting "t2"
        if ($sagT1 -and $sagT2 -and $axialT2 -and $study.sagittal -and $study.axial) {
            Add-Gate "series" "PASS" "Series classification listed Sagittal T1, Sagittal T2/STIR and Axial T2, and selected supported sagittal/axial inputs."
        } else {
            Add-Gate "series" "FAIL" "Expected RSNA study series were not all classified for the product path." "Missing Sagittal T1, Sagittal T2/STIR, Axial T2, or selected supported input."
        }
    } catch {
        Add-Gate "studyIngestion" "FAIL" "Backend /api/ai/studies failed for the real DICOM fixture." $_.Exception.Message
        Add-Gate "series" "BLOCKED" "Series gate depends on successful study ingestion." "Study upload failed."
    }
}

$sagittalSeg = $null
$sagittalRunId = ""
if ([string]::IsNullOrWhiteSpace($sagittalInputId)) {
    Add-Gate "sagittalPipeline" "BLOCKED" "No registered sagittal inputId available." "Study upload did not return a supported sagittal input."
} elseif ([string]::IsNullOrWhiteSpace($token)) {
    Add-Gate "sagittalPipeline" "BLOCKED" "Sagittal product endpoint requires Backend auth." "Provide token or enable demo auth locally."
} else {
    try {
        $body = @{
            caseId = $CaseId
            inputId = $sagittalInputId
            plane = "sagittal"
            modelKey = "sagittal_spider"
        }
        $sagittalSeg = Safe-Request -Method Post -Uri "$BackendBaseUrl/api/ai/v2/product/series-segmentation" -Headers (Auth-Headers -Token $token) -Body $body
        $sagittalRunId = First-Text @($sagittalSeg.runId, $sagittalSeg.segmentationRunId, $sagittalSeg.id)
        if ($sagittalSeg.schemaVersion -eq "pfi.full-series-segmentation.v1" -and $sagittalSeg.coverageComplete -eq $true -and $sagittalSeg.humanReviewRequired -eq $true -and $sagittalSeg.notClinicalDiagnosis -eq $true) {
            Add-Gate "sagittalPipeline" "PASS" "Full-series sagittal product route returned a complete reviewed contract for the registered study input."
            $result.e2e["sagittalPipeline"] = $sagittalSeg
        } else {
            Add-Gate "sagittalPipeline" "FAIL" "Sagittal response did not satisfy P10.9 contract." "Invalid schema, coverage, or clinical safety flags."
        }
    } catch {
        Add-Gate "sagittalPipeline" "FAIL" "Sagittal product route failed." $_.Exception.Message
    }
}

$axialSeg = $null
if ([string]::IsNullOrWhiteSpace($axialInputId)) {
    Add-Gate "axialPipeline" "BLOCKED" "No registered axial inputId available." "Study upload did not return a supported axial input."
} elseif ([string]::IsNullOrWhiteSpace($token)) {
    Add-Gate "axialPipeline" "BLOCKED" "Axial product endpoint requires Backend auth." "Provide token or enable demo auth locally."
} else {
    try {
        $body = @{
            caseId = $CaseId
            inputId = $axialInputId
            plane = "axial"
            modelKey = "axial_t2_alkafri"
        }
        $axialSeg = Safe-Request -Method Post -Uri "$BackendBaseUrl/api/ai/v2/product/series-segmentation" -Headers (Auth-Headers -Token $token) -Body $body
        if ($axialSeg.schemaVersion -eq "pfi.full-series-segmentation.v1" -and $axialSeg.coverageComplete -eq $true -and $axialSeg.humanReviewRequired -eq $true -and $axialSeg.notClinicalDiagnosis -eq $true) {
            Add-Gate "axialPipeline" "PASS" "Full-series axial product route returned a complete reviewed contract for the registered study input."
            $result.e2e["axialPipeline"] = $axialSeg
        } else {
            Add-Gate "axialPipeline" "FAIL" "Axial response did not satisfy P10.9 contract." "Invalid schema, coverage, or clinical safety flags."
        }
    } catch {
        Add-Gate "axialPipeline" "FAIL" "Axial product route failed." $_.Exception.Message
    }
}

if ($axialSeg -and $axialSeg.slices) {
    Add-Gate "p10_6" "BLOCKED" "Axial T2 segmentation ran, but this product flow did not emit a validated automatic subarticular ROI for P10.6." "Do not use dataset labels or manual ROI coordinates to force P10.6."
} else {
    Add-Gate "p10_6" "BLOCKED" "P10.6 frozen checkpoint hash is verified, but no validated automatic ROI/input was produced by the product flow." "Needs product-generated axial ROI before calling P10.6."
}

if (![string]::IsNullOrWhiteSpace($sagittalRunId) -and ![string]::IsNullOrWhiteSpace($sagittalInputId)) {
    Add-Gate "p10_7" "BLOCKED" "Sagittal segmentation produced a run id, but the Backend P10.7 persistence route requires an existing persisted multiplanar run and automatic disc localization evidence before product PASS." "No dataset labels or manual coordinates were used."
} else {
    Add-Gate "p10_7" "BLOCKED" "P10.7 checkpoint hash is verified, but no real segmentation-derived sagittal source was available for a valid product call." "Needs registered sagittal sources with segmentationRunIds from the system."
}
Add-Gate "automaticDiscLocalizationRealStudy" "BLOCKED" "automaticDiscLocalizationRealStudyValidated remains false." "No evidence yet that segmentation -> anatomical level -> slice selection -> preprocessing -> P10.7 was fully automatic for this real study."

if ($sagittalSeg -and (Count-List $sagittalSeg.slices) -gt 0) {
    Add-Gate "measurements" "BLOCKED" "Full-series segmentation returned slice-level outputs, but no product measurement payload was emitted on this route." "Needs a measurement-producing product run."
} else {
    Add-Gate "measurements" "BLOCKED" "Measurement audit depends on a successful segmentation/measurement run." "No measurement payload available."
}

if (![string]::IsNullOrWhiteSpace($sagittalRunId) -and ![string]::IsNullOrWhiteSpace($token)) {
    try {
        $assetUri = "$BackendBaseUrl/api/ai/v2/product/series-segmentation/$sagittalRunId/sagittal/slices/0/original.png"
        $assetProbe = Get-Binary -Uri $assetUri -Headers (Auth-Headers -Token $token)
        $result.e2e["assetProbe"] = [ordered]@{ uri = $assetUri; statusCode = $assetProbe.statusCode; contentType = $assetProbe.contentType; bytes = $assetProbe.bytes }
        if ($assetProbe.statusCode -eq 200 -and $assetProbe.contentType -match "image/png" -and $assetProbe.bytes -gt 0) {
            Add-Gate "assets" "PASS" "Backend product asset proxy returned a non-empty PNG for a generated sagittal slice without exposing filesystem paths."
        } else {
            Add-Gate "assets" "FAIL" "Product asset proxy did not return a valid PNG." "Unexpected status, content type, or empty body."
        }
    } catch {
        Add-Gate "assets" "FAIL" "Product asset proxy failed for the generated sagittal run." $_.Exception.Message
    }
} else {
    Add-Gate "assets" "BLOCKED" "Asset proxy audit depends on a successful segmentation run id." "No generated run id available."
}

Add-Gate "persistence" "BLOCKED" "Study upload and product full-series routes executed through Backend, but this checkpoint does not yet persist those product segmentation runs as reviewable PostgreSQL study runs." "Needs a persisted multiplanar/product run before persistence PASS."
Add-Gate "professionalReview" "BLOCKED" "Review gate requires a persisted multiplanar/product run id." "No reviewable PostgreSQL run was created by the executed product route."

$piiHits = Test-NoSensitiveLeak -Objects $script:Responses
if ($piiHits.Count -eq 0) {
    Add-Gate "piiAudit" "PASS" "No configured PII/path/token patterns were found in captured JSON responses."
} else {
    Add-Gate "piiAudit" "FAIL" "Sensitive-looking patterns found in captured responses." ($piiHits -join ", ")
}

$overall = "PASS"
foreach ($gate in $script:Gates.Values) {
    if ($gate.status -eq "FAIL") { $overall = "FAIL"; break }
    if ($gate.status -eq "BLOCKED" -and $overall -ne "FAIL") { $overall = "BLOCKED" }
}
$result["P10_9_BACKEND_AI_REAL_STUDY_E2E"] = $overall
$result["gates"] = $script:Gates

$jsonPath = Join-Path $ResultsDir "P10_9_E2E_RESULT.json"
$mdPath = Join-Path $ResultsDir "P10_9_E2E_AUDIT.md"
$result | ConvertTo-Json -Depth 60 | Set-Content -LiteralPath $jsonPath -Encoding UTF8

$lines = New-Object System.Collections.Generic.List[string]
$lines.Add("# P10.9 Backend AI Real Product E2E Audit")
$lines.Add("")
$lines.Add("Overall: P10_9_BACKEND_AI_REAL_STUDY_E2E = $overall")
$lines.Add("")
$lines.Add("| Gate | Estado | Evidencia | Problema restante |")
$lines.Add("|---|---:|---|---|")
foreach ($name in $script:Gates.Keys) {
    $gate = $script:Gates[$name]
    $lines.Add("| $name | $($gate.status) | $($gate.evidence) | $($gate.problem) |")
}
$lines.Add("")
$lines.Add("trainingExecuted: false")
$lines.Add("sealedTestAccessed: false")
$lines.Add("humanReviewRequired: true")
$lines.Add("notClinicalDiagnosis: true")
$lines | Set-Content -LiteralPath $mdPath -Encoding UTF8

Write-Output "P10_9_BACKEND_AI_REAL_STUDY_E2E=$overall"
Write-Output "JSON=$jsonPath"
Write-Output "AUDIT=$mdPath"
if ($overall -eq "FAIL") { exit 1 }
