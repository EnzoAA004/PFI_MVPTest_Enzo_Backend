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

function Get-Property {
    param($Value, [string]$Name)
    if ($null -eq $Value) { return $null }
    $prop = $Value.PSObject.Properties[$Name]
    if ($null -eq $prop) { return $null }
    return $prop.Value
}

function Get-Array {
    param($Value)
    if ($null -eq $Value) { return @() }
    if ($Value -is [System.Array]) { return @($Value) }
    if ($Value -is [System.Collections.IEnumerable] -and $Value -isnot [string]) { return @($Value) }
    return @($Value)
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
    param([string]$CheckpointName, [string]$Path, [string]$Expected)
    $resolved = Resolve-Path -LiteralPath $Path -ErrorAction SilentlyContinue
    if ($null -eq $resolved) {
        return [ordered]@{ checkpoint = $CheckpointName; source = "host"; present = $false; expected = $Expected; observed = $null }
    }
    $hash = (Get-FileHash -LiteralPath $resolved.Path -Algorithm SHA256).Hash.ToLowerInvariant()
    return [ordered]@{ checkpoint = $CheckpointName; source = "host"; present = $true; expected = $Expected; observed = $hash; match = ($hash -eq $Expected) }
}

function Hash-Container-File {
    param([string]$CheckpointName, [string]$Container, [string]$Path, [string]$Expected)
    try {
        $output = & docker exec $Container sha256sum $Path 2>$null
        if ($LASTEXITCODE -ne 0 -or [string]::IsNullOrWhiteSpace($output)) {
            return [ordered]@{ checkpoint = $CheckpointName; source = "container"; present = $false; expected = $Expected; observed = $null }
        }
        $hash = ($output -split "\s+")[0].ToLowerInvariant()
        return [ordered]@{ checkpoint = $CheckpointName; source = "container"; present = $true; expected = $Expected; observed = $hash; match = ($hash -eq $Expected) }
    } catch {
        return [ordered]@{ checkpoint = $CheckpointName; source = "container"; present = $false; expected = $Expected; observed = $null; error = "hash lookup failed" }
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

$p10_6 = Hash-File-Or-Blocked -CheckpointName "p10_6" -Path $P10_6Path -Expected $ExpectedP10_6Sha
$p10_7 = Hash-File-Or-Blocked -CheckpointName "p10_7" -Path $P10_7Path -Expected $ExpectedP10_7Sha
$p10_6Container = Hash-Container-File -CheckpointName "p10_6" -Container "pfi-ai-module" -Path "/models/subarticular/frozen_subarticular_checkpoint.pt" -Expected $ExpectedP10_6Sha
$p10_7Container = Hash-Container-File -CheckpointName "p10_7" -Container "pfi-ai-module" -Path "/models/disc-degenerative/frozen_p10_7_spider_degenerative_multitask.pt" -Expected $ExpectedP10_7Sha
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
$sagittalT1InputId = ""
$sagittalT2InputId = ""
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
        $sagittalT1InputId = First-Text @($study.sagittalT1.inputId)
        $sagittalT2InputId = First-Text @($study.sagittalT2.inputId)
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

Add-Gate "p10_6" "BLOCKED" "The axial_t2_alkafri model's classes (raw_50=disc, raw_100=posterior element, raw_150=thecal sac, raw_200=anteroposterior area, per the Al-Kafri dataset mapping already documented in MODEL_REGISTRY) do not include a vertebra class, so there is no automatic, code-verifiable way to count craniocaudal position and assign an axial slice group to a specific lumbar level (L1-L2..L5-S1) without either sagittal-to-axial registration (explicitly forbidden: automaticSagittalAxialAlignmentValidated must stay false) or dataset ground truth (explicitly forbidden). Side (left/right) could plausibly be derived from thecal-sac-relative geometry, but level cannot, so no automatic ROI is generated." "Needs one of: an anatomical label mapping that names which axial slice index corresponds to which lumbar level from DICOM geometry alone (not present in the current manifest/training docs); a dedicated ROI localizer trained for level assignment; or an independently validated axial vertebral-level detector. automaticRoiAvailable=false is exposed via GET /api/ai/health -> degenerativeFindingModels.subarticular.roiLimitation; humanReviewRequired stays true."

# Persist a reviewable PostgreSQL run before anything that needs multiplanarRunId
# (P10.7 persistence, measurements-by-plane, professional review all key off this).
# This is the same /api/ai/multiplanar/run route P10.5-C/E already validated; P10.9 only
# adds P10.7 findings and review on top of the run it creates.
$multiplanarRunId = ""
$multiplanarRun = $null
if (![string]::IsNullOrWhiteSpace($sagittalInputId) -and ![string]::IsNullOrWhiteSpace($axialInputId) -and ![string]::IsNullOrWhiteSpace($token)) {
    try {
        $runBody = @{
            caseId = $CaseId
            sagittalInputId = $sagittalInputId
            axialInputId = $axialInputId
            sagittalModelKey = "sagittal_spider"
            axialModelKey = "axial_t2_alkafri"
            allowContractFallback = $false
            metadata = @{ inferenceMode = "real_baseline" }
        }
        $multiplanarRun = Safe-Request -Method Post -Uri "$BackendBaseUrl/api/ai/multiplanar/run" -Headers (Auth-Headers -Token $token) -Body $runBody
        $result.e2e["multiplanarRun"] = $multiplanarRun
        $multiplanarRunId = First-Text @($multiplanarRun.runId, $multiplanarRun.multiplanarRunId)
    } catch {
        $result.e2e["multiplanarRunError"] = $_.Exception.Message
    }
}

if ([string]::IsNullOrWhiteSpace($sagittalT1InputId) -or [string]::IsNullOrWhiteSpace($sagittalT2InputId)) {
    Add-Gate "p10_7" "BLOCKED" "The uploaded study did not yield both an analyzable sagittal T1 and an analyzable sagittal T2 input (P10.7 requires both as independent, non-registered-to-each-other sources)." "sagittalT1InputId present=$(![string]::IsNullOrWhiteSpace($sagittalT1InputId)); sagittalT2InputId present=$(![string]::IsNullOrWhiteSpace($sagittalT2InputId))."
} elseif ([string]::IsNullOrWhiteSpace($multiplanarRunId)) {
    Add-Gate "p10_7" "BLOCKED" "P10.7 findings persist onto an existing PostgreSQL study run; no persisted multiplanarRunId was available." "POST /api/ai/multiplanar/run did not return a usable runId."
} else {
    try {
        $segT1Body = @{ caseId = $CaseId; inputId = $sagittalT1InputId; plane = "sagittal"; modelKey = "sagittal_spider" }
        $segT1 = Safe-Request -Method Post -Uri "$BackendBaseUrl/api/ai/v2/product/series-segmentation" -Headers (Auth-Headers -Token $token) -Body $segT1Body
        $segT1RunId = First-Text @($segT1.runId)
        $segT2Body = @{ caseId = $CaseId; inputId = $sagittalT2InputId; plane = "sagittal"; modelKey = "sagittal_spider" }
        $segT2 = Safe-Request -Method Post -Uri "$BackendBaseUrl/api/ai/v2/product/series-segmentation" -Headers (Auth-Headers -Token $token) -Body $segT2Body
        $segT2RunId = First-Text @($segT2.runId)
        $result.e2e["sagittalT1Segmentation"] = @{ runId = $segT1RunId; discLocalizationCount = (Get-Array (Get-Property $segT1 "discLocalizations")).Count }
        $result.e2e["sagittalT2Segmentation"] = @{ runId = $segT2RunId; discLocalizationCount = (Get-Array (Get-Property $segT2 "discLocalizations")).Count }

        $discBody = @{
            multiplanarRunId = $multiplanarRunId
            caseId = $CaseId
            sources = @(
                @{ role = "sagittal_t1"; inputId = $sagittalT1InputId; segmentationRunId = $segT1RunId },
                @{ role = "sagittal_t2"; inputId = $sagittalT2InputId; segmentationRunId = $segT2RunId }
            )
        }
        $discResponse = Safe-Request -Method Post -Uri "$BackendBaseUrl/api/ai/v2/product/disc-degenerative-findings" -Headers (Auth-Headers -Token $token) -Body $discBody
        $result.e2e["p10_7"] = $discResponse

        $findingsEnvelope = Get-Property $discResponse "discDegenerativeFindings"
        $findings = Get-Array (Get-Property $findingsEnvelope "findings")
        $allowedTypes = @("pfirrmann_grade", "modic_change", "upper_endplate_change", "lower_endplate_change", "spondylolisthesis", "disc_herniation", "disc_narrowing", "disc_bulging")
        $typesSeen = @($findings | ForEach-Object { [string](Get-Property $_ "findingType") })
        $allTypesKnown = -not ($typesSeen | Where-Object { $allowedTypes -notcontains $_ })
        $persistence = Get-Property $discResponse "persistence"
        if (
            (Get-Property $findingsEnvelope "schemaVersion") -eq "pfi.disc-degenerative-findings.v1" -and
            $findings.Count -gt 0 -and $allTypesKnown -and
            (Get-Property $discResponse "humanReviewRequired") -eq $true -and
            (Get-Property $discResponse "notClinicalDiagnosis") -eq $true -and
            (Get-Property $discResponse "autonomousDiagnosis") -eq $false -and
            (Get-Property $persistence "status") -eq "persisted_immutable"
        ) {
            Add-Gate "p10_7" "PASS" "Real sagittal T1 ($segT1RunId) and T2 ($segT2RunId) full-series segmentation, independent per modality, fed segmentation-derived disc localizations into POST /api/ai/v2/product/disc-degenerative-findings; the frozen P10.7 checkpoint returned $($findings.Count) finding(s) across the supported taxonomy and Backend persisted them immutably onto multiplanarRunId=$multiplanarRunId."
        } else {
            Add-Gate "p10_7" "FAIL" "P10.7 response did not satisfy the full product contract." "schemaVersion/findings/findingType taxonomy/safety flags/persistence status did not all validate."
        }
    } catch {
        Add-Gate "p10_7" "FAIL" "P10.7 product chain failed against the real study." $_.Exception.Message
    }
}

$p10_7Gate = $script:Gates["p10_7"]
if ($p10_7Gate.status -eq "PASS") {
    $t1Levels = if ($result.e2e.Contains("sagittalT1Segmentation")) { $result.e2e["sagittalT1Segmentation"].discLocalizationCount } else { 0 }
    $t2Levels = if ($result.e2e.Contains("sagittalT2Segmentation")) { $result.e2e["sagittalT2Segmentation"].discLocalizationCount } else { 0 }
    $result.e2e["automaticDiscLocalization"] = [ordered]@{
        automaticDiscLocalizationRealStudyValidated = $true
        automaticDiscLocalizationValidated = $false
        validationScope = "technical_real_study_provenance_not_clinical_accuracy"
        sagittalT1LevelsLocalized = $t1Levels
        sagittalT2LevelsLocalized = $t2Levels
        automaticSagittalAxialAlignmentValidated = $false
    }
    Add-Gate "automaticDiscLocalizationRealStudy" "PASS" "Real DICOM -> full-series sagittal segmentation -> connected disc components -> level assignment -> bbox -> P10.7 ran end to end with zero manual coordinates, zero dataset ground truth, and zero external ROI ($t1Levels T1 level(s), $t2Levels T2 level(s) localized). This is technical provenance on this real-study fixture only; automaticDiscLocalizationValidated (clinical/general accuracy) stays false, and automaticSagittalAxialAlignmentValidated stays false because no sagittal-to-axial pixel registration was assumed anywhere in this chain."
} else {
    Add-Gate "automaticDiscLocalizationRealStudy" "BLOCKED" "automaticDiscLocalizationRealStudyValidated remains false." "Depends on p10_7 (status=$($p10_7Gate.status)): $($p10_7Gate.problem)"
}

$measurementsFound = @()
foreach ($seg in @($sagittalSeg, $axialSeg)) {
    if ($null -eq $seg) { continue }
    foreach ($slice in (Get-Array (Get-Property $seg "slices"))) {
        $sliceMeasurements = Get-Array (Get-Property $slice "measurements")
        if ($sliceMeasurements.Count -gt 0) { $measurementsFound += ,$sliceMeasurements }
    }
}
if ($measurementsFound.Count -gt 0) {
    $sample = $measurementsFound[0][0]
    $result.e2e["measurementsSample"] = [ordered]@{
        sliceGroupsWithMeasurements = $measurementsFound.Count
        sampleKeys = @($sample.PSObject.Properties.Name)
    }
    Add-Gate "measurements" "PASS" "Full-series sagittal/axial product segmentation returned descriptive per-slice measurements (reusing the existing build_measurements engine, no new measurement model) across $($measurementsFound.Count) slice group(s); persistence and AI-vs-human correction are verified separately by the persistence and professionalReview gates."
} else {
    Add-Gate "measurements" "BLOCKED" "Measurement audit depends on a successful segmentation run with non-empty per-slice measurements." "No measurement payload was available from sagittalPipeline/axialPipeline."
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

$persistedRun = $null
if ([string]::IsNullOrWhiteSpace($multiplanarRunId)) {
    Add-Gate "persistence" "BLOCKED" "No persisted multiplanarRunId was created; there is nothing to read back from PostgreSQL." "POST /api/ai/multiplanar/run did not succeed for this real study."
} else {
    try {
        $runsResponse = Safe-Request -Method Get -Uri "$BackendBaseUrl/api/studies/$CaseId/runs" -Headers (Auth-Headers -Token $token)
        $runs = Get-Array (Get-Property $runsResponse "runs")
        $persistedRun = $runs | Where-Object { (Get-Property (Get-Property $_ "summary") "runId") -eq $multiplanarRunId } | Select-Object -First 1
        $metricsSnapshot = Get-Property $persistedRun "metricsSnapshot"
        $discPersisted = $null -ne (Get-Property $metricsSnapshot "discDegenerativeFindings")
        $measurementsByPlane = Get-Property $persistedRun "measurementsByPlane"
        $result.e2e["persistence"] = [ordered]@{
            runFoundInPostgres = $null -ne $persistedRun
            discDegenerativeFindingsPersisted = $discPersisted
            measurementsByPlaneKeys = if ($null -ne $measurementsByPlane) { @($measurementsByPlane.PSObject.Properties.Name) } else { @() }
        }
        if ($null -ne $persistedRun) {
            $p10_7Gate = $script:Gates["p10_7"]
            $expectDisc = $p10_7Gate.status -eq "PASS"
            if ((-not $expectDisc) -or $discPersisted) {
                Add-Gate "persistence" "PASS" "GET /api/studies/$CaseId/runs, a fresh HTTP request against PostgreSQL (not an in-memory Java object), found the run created by POST /api/ai/multiplanar/run and, when P10.7 ran, its P10.7 findings inside metricsSnapshot.discDegenerativeFindings -- durable across the request boundary."
            } else {
                Add-Gate "persistence" "FAIL" "Run was found in PostgreSQL but its P10.7 findings were not durably persisted." "metricsSnapshot.discDegenerativeFindings missing after a P10.7 PASS."
            }
        } else {
            Add-Gate "persistence" "FAIL" "multiplanarRunId was returned by the run endpoint but could not be re-read from PostgreSQL via the study runs endpoint." "GET /api/studies/{caseId}/runs did not list runId=$multiplanarRunId."
        }
    } catch {
        Add-Gate "persistence" "FAIL" "Reading the persisted run back from PostgreSQL failed." $_.Exception.Message
    }
}

if ([string]::IsNullOrWhiteSpace($multiplanarRunId)) {
    Add-Gate "professionalReview" "BLOCKED" "Review gate requires a persisted multiplanar/product run id." "No reviewable PostgreSQL run was created by the executed product route."
} else {
    try {
        # planes.{plane}.measurements is { values: [ { id, labelKey, value, unit, plane,
        # level, sliceIndex, source: "ai", status: "pending_review", ... } ] } on the real
        # /api/ai/multiplanar/run v2 contract -- not the older slices[].measurementIds
        # shape, which this route does not expose.
        #
        # The correction target must be a real AI measurement whose level is one of the
        # five lumbar levels P10.7 supports (L1-L2..L5-S1). The study's sagittal FOV can
        # legitimately include non-lumbar levels (e.g. T9-T10) if the acquisition covers
        # more than the lumbar spine; those are real AI measurements too, just not ones
        # that represent the lumbar product scope this E2E is closing out.
        $lumbarLevels = @("L1-L2", "L2-L3", "L3-L4", "L4-L5", "L5-S1")
        $correctionTarget = $null
        foreach ($planeName in @("sagittal", "axial")) {
            $plane = Get-Property $multiplanarRun "planes"
            $planeData = Get-Property $plane $planeName
            $measurements = Get-Property $planeData "measurements"
            $values = Get-Array (Get-Property $measurements "values")
            foreach ($measurement in $values) {
                $measurementId = [string](Get-Property $measurement "id")
                $measurementLevel = [string](Get-Property $measurement "level")
                $measurementSource = [string](Get-Property $measurement "source")
                if ((![string]::IsNullOrWhiteSpace($measurementId)) -and ($lumbarLevels -contains $measurementLevel) -and ($measurementSource -eq "ai")) {
                    $correctionTarget = [ordered]@{
                        plane = $planeName
                        sliceIndex = [int](Get-Property $measurement "sliceIndex")
                        measurementId = $measurementId
                        unit = [string](Get-Property $measurement "unit")
                        level = $measurementLevel
                        aiValue = (Get-Property $measurement "value")
                        aiLabelKey = [string](Get-Property $measurement "labelKey")
                    }
                    break
                }
            }
            if ($null -ne $correctionTarget) { break }
        }
        if ($null -eq $correctionTarget) {
            $allLevelsSeen = @()
            foreach ($planeName in @("sagittal", "axial")) {
                $plane = Get-Property $multiplanarRun "planes"
                $planeData = Get-Property $plane $planeName
                $measurements = Get-Property $planeData "measurements"
                foreach ($measurement in (Get-Array (Get-Property $measurements "values"))) {
                    $allLevelsSeen += [string](Get-Property $measurement "level")
                }
            }
            $uniqueLevelsSeen = @($allLevelsSeen | Sort-Object -Unique)
            $result.e2e["professionalReviewLumbarSearch"] = [ordered]@{ levelsSeenAcrossAllMeasurements = $uniqueLevelsSeen }
            Add-Gate "professionalReview" "BLOCKED" "No real AI measurement with a supported lumbar level (L1-L2..L5-S1) was available on the persisted run to attach a professional correction to." "Levels actually present on this run's measurements: $($uniqueLevelsSeen -join ', ')."
        } else {
            $aiValueNumeric = [double]$correctionTarget.aiValue
            $humanCorrectedValue = [math]::Round($aiValueNumeric * 1.1, 2)
            $reviewBody = @{
                reviewStatus = "observed"
                reviewer = "p10-9-e2e-reviewer"
                comments = "P10.9 E2E: correccion academica pseudonima sobre nivel lumbar $($correctionTarget.level) para validar persistencia y distincion AI vs humano."
                corrections = @(
                    @{
                        measurementId = $correctionTarget.measurementId
                        label = "P10.9 E2E lumbar measurement correction ($($correctionTarget.level))"
                        beforeValue = @{ value = $aiValueNumeric; unit = $correctionTarget.unit; plane = $correctionTarget.plane; level = $correctionTarget.level; sliceIndex = $correctionTarget.sliceIndex; source = "ai" }
                        afterValue = @{ value = $humanCorrectedValue; unit = $correctionTarget.unit; plane = $correctionTarget.plane; level = $correctionTarget.level; sliceIndex = $correctionTarget.sliceIndex; source = "human" }
                        comment = "Ajuste E2E pseudonimo sin identificadores, nivel lumbar soportado."
                    }
                )
            }
            $reviewSaved = Safe-Request -Method Post -Uri "$BackendBaseUrl/api/ai/runs/$multiplanarRunId/review" -Headers (Auth-Headers -Token $token) -Body $reviewBody
            $reviewFetched = Safe-Request -Method Get -Uri "$BackendBaseUrl/api/ai/runs/$multiplanarRunId/review" -Headers (Auth-Headers -Token $token)
            $result.e2e["professionalReview"] = [ordered]@{
                measurementId = $correctionTarget.measurementId
                level = $correctionTarget.level
                plane = $correctionTarget.plane
                aiOriginalValue = $aiValueNumeric
                aiLabelKey = $correctionTarget.aiLabelKey
                unit = $correctionTarget.unit
                humanCorrectedValue = $humanCorrectedValue
                saved = [ordered]@{ reviewStatus = Get-Property $reviewSaved "reviewStatus"; reviewer = Get-Property $reviewSaved "reviewer" }
                fetchedAfterSave = [ordered]@{ reviewStatus = Get-Property $reviewFetched "reviewStatus"; reviewer = Get-Property $reviewFetched "reviewer"; correctionCount = (Get-Array (Get-Property $reviewFetched "corrections")).Count }
            }
            $fetchedCorrections = Get-Array (Get-Property $reviewFetched "corrections")
            $correctionPersisted = $fetchedCorrections | Where-Object { (Get-Property $_ "measurementId") -eq $correctionTarget.measurementId } | Select-Object -First 1
            if (
                (Get-Property $reviewFetched "reviewStatus") -eq "observed" -and
                (Get-Property $reviewFetched "reviewer") -eq "p10-9-e2e-reviewer" -and
                $null -ne $correctionPersisted
            ) {
                Add-Gate "professionalReview" "PASS" "AI-generated run multiplanarRunId=$multiplanarRunId started review-required; the corrected measurement (measurementId=$($correctionTarget.measurementId), level=$($correctionTarget.level), a supported lumbar level) is a real product-generated AI measurement (source=ai, value=$aiValueNumeric $($correctionTarget.unit)), not fabricated. POST /api/ai/runs/{id}/review saved a human correction (value=$humanCorrectedValue $($correctionTarget.unit), source=human), and a separate GET re-fetch confirmed reviewStatus=observed, reviewer=p10-9-e2e-reviewer and the correction persisted -- AI output and human review remain distinguishable fields, nothing was overwritten silently."
            } else {
                Add-Gate "professionalReview" "FAIL" "Review round-trip did not confirm persisted human review state." "GET after POST did not reflect the saved reviewStatus/reviewer/correction."
            }
        }
    } catch {
        Add-Gate "professionalReview" "FAIL" "Professional review round-trip failed against the real persisted run." $_.Exception.Message
    }
}

# Audit the full evidence set that will actually be serialized into P10_9_E2E_RESULT.json
# (raw captured HTTP responses/errors, checkpoint hash records, and gate evidence/problem
# text), not just the redacted response mirror kept for diagnostics. This is evaluated
# before the "piiAudit" gate itself is added to $script:Gates, so the audit never inspects
# its own verdict (no self-referential PASS).
$evidenceForPiiAudit = [ordered]@{
    responses = $script:Responses
    checkpoints = $result.checkpoints
    e2e = $result.e2e
    gates = $script:Gates
}
$piiHits = Test-NoSensitiveLeak -Objects $evidenceForPiiAudit
if ($piiHits.Count -eq 0) {
    Add-Gate "piiAudit" "PASS" "No configured PII/path/token patterns were found across captured responses, checkpoint records, e2e payloads, and gate evidence destined for the public JSON."
} else {
    Add-Gate "piiAudit" "FAIL" "Sensitive-looking patterns found in evidence destined for the public JSON." ($piiHits -join ", ")
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

# Fail-closed exit codes: an automated caller must not read BLOCKED as success.
# PASS = 0, FAIL = 1, BLOCKED = 2. Evidence files are already written above for all
# three outcomes before this exit.
if ($overall -eq "FAIL") { exit 1 }
elseif ($overall -eq "BLOCKED") { exit 2 }
else { exit 0 }
