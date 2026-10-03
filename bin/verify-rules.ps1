param(
    [ValidateSet('Quick', 'MySQL', 'BrowserControlled', 'BrowserReal')]
    [string]$Mode = 'Quick'
)
$ErrorActionPreference = 'Stop'
$repo = (Resolve-Path (Join-Path $PSScriptRoot '..')).Path
$started = Get-Date
$reportDirectory = Join-Path $repo 'output/verification'
New-Item -ItemType Directory -Path $reportDirectory -Force | Out-Null
$report = [ordered]@{ head = (& git -C $repo rev-parse HEAD); mode = $Mode; started = $started.ToString('o'); status = 'running'; tests = @(); notExecuted = @() }

function Invoke-Checked([string]$Command, [string[]]$Arguments) {
    & $Command @Arguments
    if ($LASTEXITCODE -ne 0) { throw "$Command failed (exit $LASTEXITCODE)" }
}
function Require-Environment([string[]]$Names) {
    foreach ($name in $Names) {
        if (-not [Environment]::GetEnvironmentVariable($name)) { throw "Missing environment variable: $name" }
    }
}
function Assert-Port([string]$HostName, [int]$Port) {
    $client = [System.Net.Sockets.TcpClient]::new()
    try { $client.ConnectAsync($HostName, $Port).WaitAsync([TimeSpan]::FromSeconds(5)).GetAwaiter().GetResult() }
    finally { $client.Dispose() }
}
function Assert-MySQL {
    Require-Environment @('WORKFLOW_MYSQL_TEST_URL', 'WORKFLOW_MYSQL_TEST_USERNAME', 'WORKFLOW_MYSQL_TEST_PASSWORD')
    $uri = [Uri]($env:WORKFLOW_MYSQL_TEST_URL -replace '^jdbc:', '')
    if ($uri.AbsolutePath -notmatch '^/[A-Za-z0-9_]+_it$') { throw 'MySQL verification requires a dedicated _it database' }
    $oldPassword = $env:MYSQL_PWD
    try {
        $env:MYSQL_PWD = $env:WORKFLOW_MYSQL_TEST_PASSWORD
        $null = & mysql '--protocol=TCP' '-h' $uri.Host '-P' $uri.Port '-u' $env:WORKFLOW_MYSQL_TEST_USERNAME '-D' $uri.AbsolutePath.TrimStart('/') '-N' '-B' '-e' 'SELECT COUNT(*) FROM ACT_RE_MODEL; SELECT COUNT(*) FROM wf_category'
        if ($LASTEXITCODE -ne 0) { throw 'MySQL connection or initialized schema unavailable' }
    } finally { $env:MYSQL_PWD = $oldPassword }
}
function Assert-Browser {
    Require-Environment @('WORKFLOW_E2E_BASE_URL')
    $uri = [Uri]$env:WORKFLOW_E2E_BASE_URL
    Assert-Port $uri.Host $uri.Port
}
function Read-BackendResults {
    $suites = @()
    foreach ($module in Get-ChildItem -LiteralPath $repo -Directory -Filter 'ruoyi-*') {
        foreach ($file in Get-ChildItem (Join-Path $module.FullName 'target/surefire-reports') -Filter 'TEST-*.xml' -ErrorAction SilentlyContinue) {
            if ($file.LastWriteTime -lt $started) { continue }
            [xml]$xml = Get-Content -LiteralPath $file.FullName -Raw
            $suite = $xml.testsuite
            $suites += [ordered]@{ name = $suite.name; count = [int]$suite.tests; failed = [int]$suite.failures + [int]$suite.errors; skipped = [int]$suite.skipped }
        }
    }
    $report.tests = $suites
    if (-not $suites.Count) { throw 'No new Surefire results were produced' }
    if ($Mode -eq 'MySQL' -and ($suites | Where-Object { $_.skipped -gt 0 })) { throw 'MySQL mode contains skipped tests; inspect the report' }
}

Push-Location $repo
try {
    switch ($Mode) {
        'Quick' {
            $report.notExecuted = @('MySQL/Redis and browser acceptance require their explicit modes; optional tests may skip')
            try { Invoke-Checked -Command mvn -Arguments @('-B', '-pl', 'ruoyi-admin', '-am', 'test') }
            finally { Read-BackendResults }
            foreach ($check in @('lint', 'test:contracts', 'build:prod')) { Invoke-Checked -Command npm -Arguments @('--prefix', 'ruoyi-ui', 'run', $check) }
            Invoke-Checked -Command git -Arguments @('diff', '--check')
        }
        'MySQL' {
            Assert-MySQL
            Require-Environment @('WORKFLOW_REDIS_TEST_PORT')
            if (-not $env:WORKFLOW_REDIS_TEST_HOST) { $env:WORKFLOW_REDIS_TEST_HOST = '127.0.0.1' }
            Assert-Port $env:WORKFLOW_REDIS_TEST_HOST ([int]$env:WORKFLOW_REDIS_TEST_PORT)
            try { Invoke-Checked -Command mvn -Arguments @('-B', 'clean', 'verify') }
            finally { Read-BackendResults }
            $report.notExecuted = @('Browser acceptance')
        }
        { $_ -in 'BrowserControlled', 'BrowserReal' } {
            Assert-Browser
            if ($Mode -eq 'BrowserReal') {
                Assert-MySQL
                Require-Environment @('WORKFLOW_E2E_USERNAME', 'WORKFLOW_E2E_PASSWORD', 'WORKFLOW_E2E_REDIS_PORT')
                Assert-Port '127.0.0.1' ([int]$env:WORKFLOW_E2E_REDIS_PORT)
                $probe = Invoke-WebRequest ($env:WORKFLOW_E2E_BASE_URL.TrimEnd('/') + '/dev-api/captchaImage') -TimeoutSec 10
                if (($probe.Content | ConvertFrom-Json).code -ne 200) { throw 'Backend HTTP probe failed' }
                $specs = @('workflow-business-boundaries.spec.js', 'workflow-rule-authority.spec.js')
                $report.notExecuted = @('External mail vendor; target deployment endurance')
            } else {
                $specs = @('workflow-page-reuse.spec.js', 'workflow-observation.spec.js', 'workflow-draft-attachments.spec.js', 'request-ownership.spec.js', 'integration-credential-ownership.spec.js')
                $report.notExecuted = @('Real backend business behavior')
            }
            $browserResult = Join-Path $reportDirectory "$Mode-playwright.json"
            $previousJson = $env:PLAYWRIGHT_JSON_OUTPUT_FILE
            try {
                $env:PLAYWRIGHT_JSON_OUTPUT_FILE = $browserResult
                Invoke-Checked -Command npm -Arguments (@('--prefix', 'ruoyi-ui', 'run', 'test:e2e', '--') + $specs + @('--workers=1', '--reporter=line,json'))
            } finally {
                $env:PLAYWRIGHT_JSON_OUTPUT_FILE = $previousJson
                if ((Test-Path -LiteralPath $browserResult) -and (Get-Item -LiteralPath $browserResult).LastWriteTime -ge $started) {
                    $result = Get-Content -LiteralPath $browserResult -Raw | ConvertFrom-Json
                    $report.tests = @($result.stats)
                }
            }
            if ($result.stats.expected -eq 0 -or $result.stats.skipped -gt 0 -or $result.stats.unexpected -gt 0) { throw 'Browser mode requires executed tests without failures or skips' }
        }
    }
    $report.status = 'passed'
} catch {
    $report.status = 'failed'
    $report.failure = $_.Exception.Message
    throw
} finally {
    $report.finished = (Get-Date).ToString('o')
    $report | ConvertTo-Json -Depth 8 | Set-Content -LiteralPath (Join-Path $reportDirectory "$Mode.json") -Encoding utf8
    Pop-Location
    Write-Output "Verification $Mode : $($report.status). Report: output/verification/$Mode.json"
}
