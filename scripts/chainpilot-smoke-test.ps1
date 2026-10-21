#requires -Version 5.1
<#
ChainPilot read-only smoke test.

Default behavior:
  - checks backend/frontend availability
  - checks a small set of read-only business/evaluation/observability APIs
  - NEVER writes tasks, inventory, orders, suppliers, feedback, simulations or evaluations
  - NEVER calls /api/model-config/test/{id} and NEVER calls a real model

Model readiness is reported as an external condition, not a smoke-test failure.
#>
[CmdletBinding()]
param(
    [string]$BackendUrl = "http://localhost:8065",
    [string]$FrontendUrl = "http://localhost:3002",
    [int]$TimeoutSec = 10
)

$ErrorActionPreference = "Stop"
[Console]::OutputEncoding = [System.Text.Encoding]::UTF8
$script:failures = 0
$script:blocked = 0

function Add-Result {
    param(
        [string]$Name,
        [ValidateSet("PASS", "INFO", "BLOCKED", "FAIL")]
        [string]$Status,
        [string]$Detail
    )
    Write-Host ("[{0}] {1} - {2}" -f $Status, $Name, $Detail)
    if ($Status -eq "FAIL") { $script:failures++ }
    if ($Status -eq "BLOCKED") { $script:blocked++ }
}

function Get-Json {
    param([string]$Path)
    Invoke-RestMethod -Uri "$BackendUrl$Path" -Method Get -TimeoutSec $TimeoutSec
}

Write-Host "ChainPilot smoke test (read-only)"
Write-Host "Backend:  $BackendUrl"
Write-Host "Frontend: $FrontendUrl"
Write-Host ""

try {
    $echo = Invoke-WebRequest -Uri "$BackendUrl/echo/ok" -Method Get -TimeoutSec $TimeoutSec -UseBasicParsing
    if ($echo.StatusCode -eq 200 -and $echo.Content.Trim() -eq "ok") {
        Add-Result "backend heartbeat" "PASS" "/echo/ok returned ok"
    }
    else {
        Add-Result "backend heartbeat" "FAIL" "unexpected /echo/ok response"
    }
}
catch {
    Add-Result "backend heartbeat" "FAIL" "backend is not reachable at $BackendUrl"
    Write-Host ""
    Write-Host "Smoke test stopped: start the backend before running this script."
    exit 1
}

try {
    $orders = @(Get-Json "/api/supply-chain/orders")
    Add-Result "orders read-only API" "PASS" ("returned {0} simulated order assessment(s)" -f $orders.Count)
}
catch {
    Add-Result "orders read-only API" "FAIL" $_.Exception.Message
}

try {
    $inventory = @(Get-Json "/api/supply-chain/inventory?sku=SKU-MCU-001")
    Add-Result "inventory read-only API" "PASS" ("returned {0} simulated inventory row(s)" -f $inventory.Count)
}
catch {
    Add-Result "inventory read-only API" "FAIL" $_.Exception.Message
}

try {
    $suppliers = @(Get-Json "/api/supply-chain/suppliers")
    Add-Result "supplier read-only API" "PASS" ("returned {0} simulated supplier profile(s)" -f $suppliers.Count)
}
catch {
    Add-Result "supplier read-only API" "FAIL" $_.Exception.Message
}

try {
    $summary = Get-Json "/api/supply-chain/observability/summary"
    Add-Result "observability summary" "PASS" "summary is readable"
}
catch {
    Add-Result "observability summary" "FAIL" $_.Exception.Message
}

try {
    $capabilities = Get-Json "/api/agent-evaluation/capabilities"
    if (-not $capabilities.success) { throw "API envelope success=false" }
    $cap = $capabilities.data
    Add-Result "evaluation capabilities" "PASS" ("{0} cases, AGENT={1}, feedback cases={2}" -f $cap.totalCases, $cap.agentCases, $cap.feedbackCases)
}
catch {
    Add-Result "evaluation capabilities" "FAIL" $_.Exception.Message
}

try {
    $cases = Get-Json "/api/agent-evaluation/cases"
    if (-not $cases.success) { throw "API envelope success=false" }
    Add-Result "evaluation cases" "PASS" ("{0} enabled case definitions" -f @($cases.data).Count)
}
catch {
    Add-Result "evaluation cases" "FAIL" $_.Exception.Message
}

try {
    $runs = Get-Json "/api/agent-evaluation/runs?limit=1"
    if (-not $runs.success) { throw "API envelope success=false" }
    Add-Result "evaluation runs" "PASS" ("{0} recent run(s) readable" -f @($runs.data).Count)
}
catch {
    Add-Result "evaluation runs" "FAIL" $_.Exception.Message
}

try {
    $feedback = Get-Json "/api/agent-evaluation/feedback/stats"
    if (-not $feedback.success) { throw "API envelope success=false" }
    Add-Result "feedback stats" "PASS" ("total feedback={0}, enabled cases={1}" -f $feedback.data.total, $feedback.data.enabledCases)
}
catch {
    Add-Result "feedback stats" "FAIL" $_.Exception.Message
}

try {
    $readiness = Get-Json "/api/model-config/check-ready"
    if (-not $readiness.success) { throw "API envelope success=false" }
    $model = $readiness.data
    Add-Result "model readiness (read-only)" "INFO" ("CHAT={0}, EMBEDDING={1}, AGENT ready={2}" -f $model.chatState, $model.embeddingState, $model.agentEvaluationReady)
    if (-not $model.agentEvaluationReady) {
        Add-Result "real AGENT E2E" "BLOCKED" "Model prerequisites are not ready. Configure and manually test CHAT in /system/model-config. This smoke test did not call a model."
    }
}
catch {
    Add-Result "model readiness (read-only)" "FAIL" $_.Exception.Message
}

try {
    $frontend = Invoke-WebRequest -Uri $FrontendUrl -Method Get -TimeoutSec $TimeoutSec -UseBasicParsing
    if ($frontend.StatusCode -eq 200) {
        Add-Result "frontend" "PASS" "root page returned HTTP 200"
    }
    else {
        Add-Result "frontend" "FAIL" ("unexpected HTTP {0}" -f $frontend.StatusCode)
    }
}
catch {
    Add-Result "frontend" "FAIL" "frontend is not reachable at $FrontendUrl"
}

Write-Host ""
Write-Host ("Read-only checks complete: failures={0}, blocked external conditions={1}" -f $script:failures, $script:blocked)
Write-Host "No write APIs and no real model calls were attempted."

if ($script:failures -gt 0) { exit 1 }
exit 0
