<#
ChainPilot explicit demo-data initializer.

- Creates the complete deterministic demo chain through the public APIs:
  analysis task -> decision task -> comparison -> simulation -> plan -> selection
  -> sandbox execution -> offline evaluation run -> feedback.
- Re-running is safe: records are found by explicit [DEMO-SEED] markers and reused.
- Refuses to touch a database that already contains tasks but no demo marker.
- Never calls a model (plan explanations are deterministic; evaluation is OFFLINE).
- Never resets or deletes existing records.

Usage (backend already running):
  powershell -NoProfile -ExecutionPolicy Bypass -File .\scripts\chainpilot-demo-seed.ps1

Optional:
  -BackendUrl http://localhost:8065
  -AllowExistingData   (only creates the missing demo marker chain in a non-empty database)
#>
[CmdletBinding()]
param(
    [string]$BackendUrl = "http://localhost:8065",
    [switch]$AllowExistingData
)

$ErrorActionPreference = "Stop"
[Console]::OutputEncoding = [System.Text.Encoding]::UTF8
$BackendUrl = $BackendUrl.TrimEnd('/')

$analysisQuestion = "[DEMO-SEED] 供应商 SUP-ALPHA 出现交付延迟，SKU-MCU-001 库存不足，请分析受影响订单与风险证据并给出处置建议。"
$decisionQuestion = "[DEMO-SEED] 请比较补货 50、100、200 件三个方案，结合供应商延迟和库存缺口给出推荐。"
$planTitle = "[DEMO-SEED] 供应链风险处置方案"
$comparisonTitle = "[DEMO-SEED] 补货 50/100/200 方案对比"
$feedbackComment = "[DEMO-SEED] 演示反馈：风险证据和处置建议清晰可核对。"
$created = @()

function Invoke-Api {
    param(
        [ValidateSet("GET", "POST", "PATCH")]
        [string]$Method,
        [string]$Path,
        [object]$Body = $null
    )
    $uri = "$BackendUrl$Path"
    if ($null -eq $Body) {
        return Invoke-RestMethod -Uri $uri -Method $Method -TimeoutSec 180
    }
    $json = $Body | ConvertTo-Json -Depth 12 -Compress
    $bytes = [System.Text.Encoding]::UTF8.GetBytes($json)
    return Invoke-RestMethod -Uri $uri -Method $Method -ContentType "application/json; charset=utf-8" `
        -Body $bytes -TimeoutSec 180
}

function Get-Tasks {
    param([string]$Mode)
    $items = @(Invoke-Api -Method GET -Path "/api/supply-chain/analysis/tasks?page=1&pageSize=50&mode=$Mode")
    return $items
}

function Wait-Task {
    param([string]$TaskId, [int]$TimeoutSeconds = 180)
    $deadline = (Get-Date).AddSeconds($TimeoutSeconds)
    while ((Get-Date) -lt $deadline) {
        $task = Invoke-Api -Method GET -Path "/api/supply-chain/analysis/tasks/$TaskId"
        if ($task.status -eq "COMPLETED") { return $task }
        if ($task.status -eq "FAILED") {
            throw "任务 $TaskId 失败：$($task.error)"
        }
        Start-Sleep -Milliseconds 800
    }
    throw "任务 $TaskId 在 $TimeoutSeconds 秒内未完成"
}

function Find-ByMarker {
    param([object[]]$Items, [string]$Property, [string]$Value)
    return @($Items | Where-Object { $_.$Property -eq $Value })
}

function Submit-Task {
    param([string]$Question, [string]$Mode)
    $body = @{ question = $Question }
    if ($Mode -eq "DECISION") { $body.mode = "DECISION" }
    $task = Invoke-Api -Method POST -Path "/api/supply-chain/analysis/tasks" -Body $body
    $script:created += "task:$($task.taskId)"
    Write-Host "  已提交 $Mode 任务 $($task.taskId)"
    return Wait-Task -TaskId $task.taskId
}

Write-Host "ChainPilot demo seed"
Write-Host "Backend: $BackendUrl"
try {
    Invoke-Api -Method GET -Path "/api/supply-chain/analysis/tasks?page=1&pageSize=1" | Out-Null
}
catch {
    throw "后端不可用，请先启动 8065 或传入 -BackendUrl。$($_.Exception.Message)"
}

$analysisTasks = Get-Tasks -Mode "ANALYSIS"
$decisionTasks = Get-Tasks -Mode "DECISION"
$allTasks = @($analysisTasks) + @($decisionTasks)
$analysis = $analysisTasks | Where-Object { $_.question.StartsWith("[DEMO-SEED]") } | Select-Object -First 1

if (-not $analysis -and $allTasks.Count -gt 0 -and -not $AllowExistingData) {
    throw "当前数据库已有 $($allTasks.Count) 个任务，但没有演示标记；为避免污染用户记录，未写入任何数据。确认要追加演示链时使用 -AllowExistingData。"
}

if (-not $analysis) {
    Write-Host "创建分析任务..."
    $analysis = Submit-Task -Question $analysisQuestion -Mode "ANALYSIS"
}
else {
    $analysis = Wait-Task -TaskId $analysis.taskId
    Write-Host "复用分析任务 $($analysis.taskId)"
}

$decision = $decisionTasks | Where-Object { $_.question.StartsWith("[DEMO-SEED]") } | Select-Object -First 1
if (-not $decision) {
    Write-Host "创建决策任务..."
    $decision = Submit-Task -Question $decisionQuestion -Mode "DECISION"
}
else {
    $decision = Wait-Task -TaskId $decision.taskId
    Write-Host "复用决策任务 $($decision.taskId)"
}

$plan = $null
$plansPage = Invoke-Api -Method GET -Path "/api/supply-chain/disposal-plans?page=1&pageSize=50"
$plan = @($plansPage.items) | Where-Object { $_.title.StartsWith("[DEMO-SEED]") } | Select-Object -First 1

if (-not $plan) {
    Write-Host "创建对比、模拟与处置方案..."
    $comparison = $null
    $comparisonsPage = Invoke-Api -Method GET -Path "/api/supply-chain/disposal-scenario-comparisons?page=1&pageSize=50"
    $comparison = @($comparisonsPage.items) | Where-Object { $_.title.StartsWith("[DEMO-SEED]") } | Select-Object -First 1
    if (-not $comparison) {
        $comparison = Invoke-Api -Method POST -Path "/api/supply-chain/disposal-scenario-comparisons" -Body @{
            title = $comparisonTitle
            scenario = "INVENTORY_REPLENISHMENT"
            sku = "SKU-MCU-001"
            candidates = @(
                @{ label = "方案 A"; replenishmentQuantity = 50 },
                @{ label = "方案 B"; replenishmentQuantity = 100 },
                @{ label = "方案 C"; replenishmentQuantity = 200 }
            )
        }
        $script:created += "comparison:$($comparison.comparisonId)"
        Write-Host "  对比 $($comparison.comparisonId)"
    }

    $simulation = Invoke-Api -Method POST -Path "/api/supply-chain/disposal-simulations" -Body @{
        scenario = "INVENTORY_REPLENISHMENT"
        sku = "SKU-MCU-001"
        replenishmentQuantity = 100
    }
    $script:created += "simulation:$($simulation.simulationId)"
    Write-Host "  模拟 $($simulation.simulationId)"

    $plan = Invoke-Api -Method POST -Path "/api/supply-chain/disposal-plans" -Body @{
        title = $planTitle
        taskId = $decision.taskId
        simulationId = $simulation.simulationId
        generateExplanation = $false
    }
    $script:created += "plan:$($plan.planId)"
    Write-Host "  方案 $($plan.planId)"
}
else {
    Write-Host "复用处置方案 $($plan.planId)"
    $plan = Invoke-Api -Method GET -Path "/api/supply-chain/disposal-plans/$($plan.planId)"
}

if ($plan.status -ne "SELECTED") {
    $recommendationId = @($plan.recommendations)[0].recommendationId
    if (-not $recommendationId) { throw "方案没有可用建议，无法选中" }
    $plan = Invoke-Api -Method PATCH -Path "/api/supply-chain/disposal-plans/$($plan.planId)/status" -Body @{
        status = "SELECTED"
        selectedRecommendationId = $recommendationId
    }
    Write-Host "  已选用方案"
}

$executions = Invoke-Api -Method GET -Path "/api/supply-chain/disposal-plans/$($plan.planId)/executions?page=1&pageSize=10"
$execution = @($executions.items) | Where-Object { $_.status -eq "SUCCEEDED" } | Select-Object -First 1
if (-not $execution) {
    Write-Host "执行隔离沙箱..."
    $execution = Invoke-Api -Method POST -Path "/api/supply-chain/disposal-plans/$($plan.planId)/executions" -Body @{
        idempotencyKey = "demo-seed-$($plan.planId)"
    }
    $script:created += "execution:$($execution.executionId)"
    if ($execution.status -ne "SUCCEEDED") {
        throw "沙箱执行未成功：$($execution.status)"
    }
    Write-Host "  执行 $($execution.executionId)"
}
else {
    Write-Host "复用沙箱执行 $($execution.executionId)"
}

$feedbackPage = Invoke-Api -Method GET -Path "/api/agent-evaluation/feedback?page=1&pageSize=50"
$feedbackItems = @($feedbackPage.data.items)
$feedback = $feedbackItems | Where-Object { $_.taskId -eq $analysis.taskId } | Select-Object -First 1
if (-not $feedback) {
    $feedbackResponse = Invoke-Api -Method POST -Path "/api/agent-evaluation/feedback" -Body @{
        taskId = $analysis.taskId
        feedbackType = "HELPFUL"
        comment = $feedbackComment
    }
    $feedback = $feedbackResponse.data
    $script:created += "feedback:$($feedback.feedbackId)"
    Write-Host "  反馈 $($feedback.feedbackId)"
}
else {
    Write-Host "复用反馈 $($feedback.feedbackId)"
}

$runsResponse = Invoke-Api -Method GET -Path "/api/agent-evaluation/runs?limit=20"
$runs = @($runsResponse.data)
$offlineRun = @($runs | Where-Object {
    $_.mode -eq "OFFLINE" -and $_.status -eq "COMPLETED" -and [int]$_.totalCases -ge 14
}) | Select-Object -First 1

if (-not $offlineRun) {
    Write-Host "运行一次全量离线评测..."
    $casesResponse = Invoke-Api -Method GET -Path "/api/agent-evaluation/cases"
    $cases = @($casesResponse.data)
    $offlineCaseIds = @($cases | Where-Object { $_.mode -eq "OFFLINE" } | ForEach-Object { $_.id })
    if ($offlineCaseIds.Count -eq 0) { throw "没有可运行的离线评测案例" }
    $runResponse = Invoke-Api -Method POST -Path "/api/agent-evaluation/runs" -Body @{
        caseIds = $offlineCaseIds
        mode = "OFFLINE"
        confirmAgentRun = $false
    }
    $offlineRun = $runResponse.data
    $script:created += "evaluation:$($offlineRun.runId)"
    $deadline = (Get-Date).AddSeconds(180)
    while ((Get-Date) -lt $deadline) {
        $offlineRun = (Invoke-Api -Method GET -Path "/api/agent-evaluation/runs/$($offlineRun.runId)").data
        if ($offlineRun.status -ne "RUNNING") { break }
        Start-Sleep -Seconds 1
    }
    if ($offlineRun.status -ne "COMPLETED") {
        throw "离线评测未完成：$($offlineRun.status)"
    }
    Write-Host "  评测 $($offlineRun.runId)：$($offlineRun.passed)/$($offlineRun.totalCases) 通过"
}
else {
    Write-Host "复用离线评测 $($offlineRun.runId)"
}

$summary = [ordered]@{
    generatedAt = (Get-Date).ToString("o")
    createdCount = $created.Count
    created = $created
    analysisTaskId = $analysis.taskId
    decisionTaskId = $decision.taskId
    planId = $plan.planId
    executionId = $execution.executionId
    feedbackId = $feedback.feedbackId
    evaluationRunId = $offlineRun.runId
    evaluationPassed = $offlineRun.passed
    evaluationTotal = $offlineRun.totalCases
    demoUrl = "http://localhost:3002/supply-chain?demo=supplier-delay"
}
$summary | ConvertTo-Json -Depth 6
Write-Host ""
Write-Host "演示数据已就绪。打开 $($summary.demoUrl)"
