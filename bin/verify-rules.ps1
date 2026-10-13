param(
    [ValidateSet('Quick', 'MySQL', 'BrowserControlled', 'BrowserReal')]
    [string]$Mode = 'Quick'
)
$ErrorActionPreference = 'Stop'
. (Join-Path $PSScriptRoot 'verification-common.ps1')
$repo = (Resolve-Path (Join-Path $PSScriptRoot '..')).Path
$started = Get-Date
$reportDirectory = Join-Path $repo 'output/verification'
New-Item -ItemType Directory -Path $reportDirectory -Force | Out-Null
$report = [ordered]@{
    head = (& git -C $repo rev-parse HEAD); dirty = @(& git -C $repo status --porcelain=v1)
    sourceHash = Get-VerificationSource $repo; mode = $Mode; started = $started.ToString('o')
    status = 'running'; tests = @(); notExecuted = @(); artifacts = @(); firstAttemptStable = $true
}
function Invoke-Checked([string]$Command, [string[]]$Arguments, [switch]$LocalOnly, [string]$CaptureFile) {
    # A child environment prevents inherited MySQL/Redis test selection without
    # modifying the caller, including when Maven or a later check fails.
    $payload = [Convert]::ToBase64String([Text.Encoding]::UTF8.GetBytes((@{ command = $Command; arguments = $Arguments } | ConvertTo-Json -Compress)))
    $script = '[Console]::OutputEncoding = [Text.UTF8Encoding]::new($false); $OutputEncoding = [Console]::OutputEncoding; $p = [Text.Encoding]::UTF8.GetString([Convert]::FromBase64String(''' + $payload + ''')) | ConvertFrom-Json; $arguments = @($p.arguments); & $p.command @arguments; exit $LASTEXITCODE'
    $info = [Diagnostics.ProcessStartInfo]::new((Get-Command pwsh -ErrorAction Stop).Source)
    $info.UseShellExecute = $false
    $info.CreateNoWindow = $true
    $info.WorkingDirectory = $repo
    if ($CaptureFile) {
        $info.RedirectStandardOutput = $true; $info.RedirectStandardError = $true
        $info.StandardOutputEncoding = [Text.UTF8Encoding]::new($false)
        $info.StandardErrorEncoding = [Text.UTF8Encoding]::new($false)
    }
    foreach ($arg in @('-NoProfile', '-EncodedCommand', [Convert]::ToBase64String([Text.Encoding]::Unicode.GetBytes($script)))) { $info.ArgumentList.Add($arg) }
    if ($LocalOnly) {
        foreach ($name in @($info.Environment.Keys) | Where-Object { $_ -match '^WORKFLOW_(MYSQL|REDIS)_TEST_' }) { $null = $info.Environment.Remove($name) }
    }
    $process = [Diagnostics.Process]::Start($info)
    if ($CaptureFile) { $stdout = $process.StandardOutput.ReadToEndAsync(); $stderr = $process.StandardError.ReadToEndAsync() }
    $process.WaitForExit()
    if ($CaptureFile) {
        $output = $stdout.GetAwaiter().GetResult() + $stderr.GetAwaiter().GetResult()
        $output | Set-Content -LiteralPath $CaptureFile -Encoding utf8
        Write-Host $output
    }
    if ($process.ExitCode -ne 0) { throw "$Command failed (exit $($process.ExitCode))" }
}
function Require-Environment([string[]]$Names) {
    foreach ($name in $Names) { if (-not [Environment]::GetEnvironmentVariable($name)) { throw "Missing environment variable: $name" } }
}
function Assert-SuiteManifest {
    $manifest = Get-Content -LiteralPath (Join-Path $PSScriptRoot 'verification-suites.json') -Raw | ConvertFrom-Json -AsHashtable
    $listed = @($manifest.BrowserControlled) + @($manifest.BrowserReal)
    $actual = @(Get-ChildItem -LiteralPath (Join-Path $repo 'ruoyi-ui/tests/e2e') -Filter '*.spec.js' -Recurse | ForEach-Object { [IO.Path]::GetRelativePath((Join-Path $repo 'ruoyi-ui/tests/e2e'), $_.FullName).Replace('\','/') })
    $duplicates = @($listed | Group-Object | Where-Object Count -GT 1 | ForEach-Object Name)
    $missing = @($actual | Where-Object { $_ -notin $listed })
    $stale = @($listed | Where-Object { $_ -notin $actual })
    if ($duplicates.Count -or $missing.Count -or $stale.Count) { throw "Browser suite manifest mismatch; duplicate=[$($duplicates -join ', ')], unassigned=[$($missing -join ', ')], absent=[$($stale -join ', ')]" }
    $report.suiteManifest = $manifest
    $report.suiteManifestHash = (Get-FileHash -LiteralPath (Join-Path $PSScriptRoot 'verification-suites.json')).Hash
    $manifest
}
function Read-BackendResults {
    $suites = @()
    foreach ($module in Get-ChildItem -LiteralPath $repo -Directory -Filter 'ruoyi-*') {
        foreach ($file in Get-ChildItem (Join-Path $module.FullName 'target/surefire-reports') -Filter 'TEST-*.xml' -ErrorAction SilentlyContinue) {
            if ($file.LastWriteTime -lt $started) { continue }
            [xml]$xml = Get-Content -LiteralPath $file.FullName -Raw
            $suite = $xml.testsuite
            $skips = @($suite.testcase | Where-Object { $_ -and $_.SelectSingleNode('skipped') -ne $null } | ForEach-Object { @{ name = $_.name; reason = $_.skipped.message } })
            $suites += [pscustomobject][ordered]@{ name = $suite.name; count = [int]$suite.tests; failed = [int]$suite.failures + [int]$suite.errors; skipped = [int]$suite.skipped; skippedCases = $skips }
            $report.artifacts += @{ path = [IO.Path]::GetRelativePath($repo, $file.FullName); sha256 = (Get-FileHash -LiteralPath $file.FullName).Hash }
        }
    }
    $report.tests = $suites
    $report.counts = @{ suites = $suites.Count; total = 0; failed = 0; skipped = 0 }
    foreach ($suite in $suites) {
        $report.counts.total += $suite.count
        $report.counts.failed += $suite.failed
        $report.counts.skipped += $suite.skipped
    }
    if (-not $suites.Count -or $report.counts.total -le $report.counts.skipped) { throw 'No backend tests actually executed' }
    if ($report.counts.failed -gt 0) { throw 'Backend tests failed; inspect this run report' }
    if ($Mode -eq 'MySQL' -and $report.counts.skipped -gt 0) { throw 'MySQL mode contains skipped tests; inspect the report' }
}
function Add-BrowserCases($Suites) {
    foreach ($suite in $Suites) {
        foreach ($spec in $suite.specs) {
            foreach ($test in $spec.tests) {
                $report.tests += @{ file = $spec.file; name = $spec.title; status = $test.status; attempts = @($test.results | ForEach-Object { @{ retry = $_.retry; status = $_.status; duration = $_.duration } }) }
            }
        }
        Add-BrowserCases $suite.suites
    }
}
function Assert-ServiceEvidence {
    Require-Environment @('WORKFLOW_VERIFICATION_STATE_PATH')
    $null = & (Join-Path $PSScriptRoot 'workflow-test-environment.ps1') -Action CheckBrowser -StateFile $env:WORKFLOW_VERIFICATION_STATE_PATH
    $state = Read-VerificationTarget $repo
    $report.serviceArtifacts = @{ backend = $state.backendArtifact; frontend = $state.frontendArtifact; runId = $state.runId; sourceHash = $state.serviceSourceHash }
}

Push-Location $repo
try {
    $manifest = Assert-SuiteManifest
    switch ($Mode) {
        'Quick' {
            $report.environment = @{ selection = 'H2 and local contracts'; excluded = @('WORKFLOW_MYSQL_TEST_*', 'WORKFLOW_REDIS_TEST_*') }
            $report.notExecuted = @('MySQL/Redis: external test variables removed only in the Maven child process', 'Browser: use BrowserControlled/BrowserReal')
            try { Invoke-Checked -Command mvn -Arguments @('-B', '-pl', 'ruoyi-admin', '-am', 'test') -LocalOnly }
            finally { Read-BackendResults }
            Invoke-Checked -Command pwsh -Arguments @('-NoProfile', '-File', 'bin/verification-target.test.ps1') -LocalOnly
            Invoke-Checked -Command pwsh -Arguments @('-NoProfile', '-File', 'bin/verification-credentials.test.ps1') -LocalOnly
            Invoke-Checked -Command pwsh -Arguments @('-NoProfile', '-File', 'bin/migrate-workflow-artifacts-v2.test.ps1') -LocalOnly
            Invoke-Checked -Command pwsh -Arguments @('-NoProfile', '-File', 'bin/verify-architecture.ps1')
            Invoke-Checked -Command npm -Arguments @('--prefix', 'ruoyi-ui', 'run', 'lint')
            $contractsFile = Join-Path $reportDirectory 'Quick-contracts.log'
            Invoke-Checked -Command npm -Arguments @('--prefix', 'ruoyi-ui', 'run', 'test:contracts') -CaptureFile $contractsFile
            $contracts = Get-Content -LiteralPath $contractsFile -Raw
            $report.frontendContracts = @{}
            foreach ($field in @('tests', 'suites', 'pass', 'fail', 'cancelled', 'skipped', 'todo')) {
                if ($contracts -match "(?m)^(?:#|ℹ)\s+$field\s+(\d+)") { $report.frontendContracts[$field] = [int]$matches[1] }
            }
            $report.artifacts += @{ path = [IO.Path]::GetRelativePath($repo, $contractsFile); sha256 = (Get-FileHash -LiteralPath $contractsFile).Hash }
            if (-not $report.frontendContracts.tests -or $report.frontendContracts.fail -gt 0 -or $report.frontendContracts.skipped -gt 0 -or $report.frontendContracts.cancelled -gt 0) { throw 'Frontend contracts require executed tests without failures or skips' }
            Invoke-Checked -Command npm -Arguments @('--prefix', 'ruoyi-ui', 'run', 'build:prod')
            $frontendFiles = @(Get-ChildItem -LiteralPath (Join-Path $repo 'ruoyi-ui/dist') -File -Recurse | Sort-Object FullName | ForEach-Object { "$([IO.Path]::GetRelativePath($repo, $_.FullName)):$((Get-FileHash -LiteralPath $_.FullName).Hash)" })
            $report.frontendBuild = @{ files = $frontendFiles.Count; sha256 = Get-VerificationHash ($frontendFiles -join "`n") }
            Invoke-Checked -Command git -Arguments @('diff', '--check')
        }
        'MySQL' {
            $report.environment = Assert-VerificationMySql $repo $env:WORKFLOW_MYSQL_TEST_URL $env:WORKFLOW_MYSQL_TEST_USERNAME $env:WORKFLOW_MYSQL_TEST_PASSWORD
            Require-Environment @('WORKFLOW_REDIS_TEST_PORT', 'WORKFLOW_REDIS_TEST_HOST')
            if (-not (Test-VerificationPort ([int]$env:WORKFLOW_REDIS_TEST_PORT) $env:WORKFLOW_REDIS_TEST_HOST)) { throw 'Isolated Redis is unavailable' }
            $report.environment.redis = @{ host = $env:WORKFLOW_REDIS_TEST_HOST; port = [int]$env:WORKFLOW_REDIS_TEST_PORT }
            try { Invoke-Checked -Command mvn -Arguments @('-B', 'clean', 'verify') }
            finally { Read-BackendResults }
            $jar = Join-Path $repo 'ruoyi-admin/target/ruoyi-admin.jar'
            if (Test-Path -LiteralPath $jar) { $report.artifacts += @{ path = [IO.Path]::GetRelativePath($repo, $jar); sha256 = (Get-FileHash -LiteralPath $jar).Hash } }
            $report.notExecuted = @('Browser acceptance')
        }
        { $_ -in 'BrowserControlled', 'BrowserReal' } {
            Require-Environment @('WORKFLOW_E2E_BASE_URL')
            $uri = [Uri]$env:WORKFLOW_E2E_BASE_URL
            if (-not (Test-VerificationPort $uri.Port $uri.Host)) { throw 'Frontend is unavailable' }
            if ($Mode -eq 'BrowserReal') {
                $report.environment = Assert-VerificationMySql $repo $env:WORKFLOW_MYSQL_TEST_URL $env:WORKFLOW_MYSQL_TEST_USERNAME $env:WORKFLOW_MYSQL_TEST_PASSWORD
                Require-Environment @('WORKFLOW_E2E_USERNAME', 'WORKFLOW_E2E_PASSWORD', 'WORKFLOW_E2E_REDIS_PORT')
                if (-not (Test-VerificationPort ([int]$env:WORKFLOW_E2E_REDIS_PORT))) { throw 'Isolated Redis is unavailable' }
                Assert-ServiceEvidence
                $probe = Invoke-RestMethod ($env:WORKFLOW_E2E_BASE_URL.TrimEnd('/') + '/dev-api/captchaImage') -TimeoutSec 10
                if ($probe.code -ne 200) { throw 'BrowserReal requires a ready backend' }
                $report.notExecuted = @('External mail vendor; target deployment endurance')
            } else {
                $report.environment = @{ frontendUrl = $env:WORKFLOW_E2E_BASE_URL; sourceHash = Get-VerificationSource $repo -ServiceOnly }
                # Controlled suites do not inherit a database/service requirement.
                $report.notExecuted = @('Real backend business behavior')
            }
            $specs = @($manifest[$Mode])
            $report.selectedSuites = $specs
            $browserResult = Join-Path $reportDirectory "$Mode-playwright.json"
            $previousJson = $env:PLAYWRIGHT_JSON_OUTPUT_FILE
            try {
                $env:PLAYWRIGHT_JSON_OUTPUT_FILE = $browserResult
                Invoke-Checked -Command npm -Arguments (@('--prefix', 'ruoyi-ui', 'run', 'test:e2e', '--') + $specs + @('--workers=1', '--reporter=line,json'))
            } finally {
                $env:PLAYWRIGHT_JSON_OUTPUT_FILE = $previousJson
                if ((Test-Path -LiteralPath $browserResult) -and (Get-Item -LiteralPath $browserResult).LastWriteTime -ge $started) {
                    $result = Get-Content -LiteralPath $browserResult -Raw | ConvertFrom-Json
                    $report.counts = $result.stats
                    Add-BrowserCases $result.suites
                    $report.artifacts += @{ path = [IO.Path]::GetRelativePath($repo, $browserResult); sha256 = (Get-FileHash -LiteralPath $browserResult).Hash }
                    $report.firstAttemptStable = $result.stats.flaky -eq 0
                }
            }
            if (-not $result -or ($result.stats.expected + $result.stats.flaky) -eq 0 -or $result.stats.skipped -gt 0 -or $result.stats.unexpected -gt 0 -or $result.errors.Count -gt 0) { throw 'Browser mode requires executed tests without failures, skips or global errors' }
        }
    }
    $report.status = if ($report.firstAttemptStable) { 'passed' } else { 'passed-with-retries' }
} catch {
    $report.status = 'failed'
    $report.failure = $_.Exception.Message
    throw
} finally {
    $report.finished = (Get-Date).ToString('o')
    $report.sourceHashAtFinish = Get-VerificationSource $repo
    if ($report.sourceHashAtFinish -ne $report.sourceHash) { $report.sourceChangedDuringRun = $true }
    $report | ConvertTo-Json -Depth 14 | Set-Content -LiteralPath (Join-Path $reportDirectory "$Mode.json") -Encoding utf8
    Pop-Location
    Write-Output "Verification $Mode : $($report.status). Report: output/verification/$Mode.json"
}
