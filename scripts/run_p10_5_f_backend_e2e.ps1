param(
    [string]$BackendBaseUrl = $env:PFI_E2E_BACKEND_BASE_URL,
    [string]$StudyZip = $env:PFI_E2E_STUDY_ZIP,
    [string]$CaseId = $env:PFI_E2E_CASE_ID,
    [string]$AuthToken = $env:PFI_E2E_AUTH_TOKEN
)

if ($env:RUN_PFI_E2E -ne "1") {
    Write-Error "Set RUN_PFI_E2E=1 to run this opt-in harness."
    exit 2
}
if ([string]::IsNullOrWhiteSpace($BackendBaseUrl)) {
    $BackendBaseUrl = "http://localhost:8080"
}
if ([string]::IsNullOrWhiteSpace($CaseId)) {
    $CaseId = "P10-5-F-E2E"
}
if ([string]::IsNullOrWhiteSpace($StudyZip) -or !(Test-Path -LiteralPath $StudyZip)) {
    Write-Error "PFI_E2E_STUDY_ZIP must point to a local pseudonymized study ZIP."
    exit 2
}
if ([string]::IsNullOrWhiteSpace($AuthToken)) {
    Write-Error "PFI_E2E_AUTH_TOKEN is required. Use an approved professional or admin JWT."
    exit 2
}

$client = [System.Net.Http.HttpClient]::new()
$stream = $null
$content = $null
try {
    $client.DefaultRequestHeaders.Authorization = [System.Net.Http.Headers.AuthenticationHeaderValue]::new("Bearer", $AuthToken)
    $client.DefaultRequestHeaders.Add("X-Trace-Id", "p10-5-f-e2e-backend")
    $content = [System.Net.Http.MultipartFormDataContent]::new()
    $content.Add([System.Net.Http.StringContent]::new($CaseId), "caseId")
    $stream = [System.IO.File]::OpenRead((Resolve-Path -LiteralPath $StudyZip).Path)
    $fileContent = [System.Net.Http.StreamContent]::new($stream)
    $fileContent.Headers.ContentType = [System.Net.Http.Headers.MediaTypeHeaderValue]::Parse("application/zip")
    $content.Add($fileContent, "file", [System.IO.Path]::GetFileName($StudyZip))

    $httpResponse = $client.PostAsync("$BackendBaseUrl/api/ai/studies", $content).GetAwaiter().GetResult()
    $body = $httpResponse.Content.ReadAsStringAsync().GetAwaiter().GetResult()
    Write-Output $body
    if (!$httpResponse.IsSuccessStatusCode) {
        Write-Error "Backend returned HTTP $([int]$httpResponse.StatusCode)."
        exit 1
    }
    $response = $body | ConvertFrom-Json
    if ([string]::IsNullOrWhiteSpace($response.sagittal.inputId) -and [string]::IsNullOrWhiteSpace($response.axial.inputId)) {
        Write-Error "Study upload completed but did not return sagittal or axial inputId."
        exit 1
    }
} finally {
    if ($content -ne $null) { $content.Dispose() }
    if ($stream -ne $null) { $stream.Dispose() }
    $client.Dispose()
}
