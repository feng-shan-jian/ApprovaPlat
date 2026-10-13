$ErrorActionPreference = 'Stop'
. (Join-Path $PSScriptRoot 'verification-common.ps1')
$checks = 0
function Expect-Failure([scriptblock]$Operation) {
    $failed = $false
    try { & $Operation | Out-Null } catch { $failed = $true }
    if (-not $failed) { throw 'Expected rejection before a database connection or write' }
    $script:checks++
}
$names = @('WORKFLOW_VERIFICATION_STATE_PATH','WORKFLOW_DEVELOPMENT_TEST_OPT_IN','WORKFLOW_MYSQL_TEST_URL')
$saved = @{}
foreach ($name in $names) { $saved[$name] = [Environment]::GetEnvironmentVariable($name, 'Process') }
$temporaryRoot = [IO.Path]::GetFullPath((Join-Path ([IO.Path]::GetTempPath()) ('approvaplat-target-guard-' + [Guid]::NewGuid().ToString('N'))))
try {
    $bin = New-Item -ItemType Directory -Path (Join-Path $temporaryRoot 'bin') -Force
    Copy-Item -LiteralPath (Join-Path $PSScriptRoot 'verification-common.ps1'), (Join-Path $PSScriptRoot 'workflow-test-environment.ps1') -Destination $bin
    $environmentTool = Join-Path $bin 'workflow-test-environment.ps1'
    & $environmentTool -Action Register -ServerUuid '00000000-1111-2222-3333-444444444444' | Out-Null
    $statePath = (Get-ChildItem -Path "$temporaryRoot/output/verification-environments/*/state.json").FullName
    $state = Get-Content -LiteralPath $statePath -Raw | ConvertFrom-Json -AsHashtable
    $env:WORKFLOW_VERIFICATION_STATE_PATH = $statePath
    $env:WORKFLOW_MYSQL_TEST_URL = $state.databaseUrl
    $env:WORKFLOW_DEVELOPMENT_TEST_OPT_IN = "approvaplat:$($state.runId)"
    $null = Assert-VerificationTarget $temporaryRoot $state.databaseUrl
    $checks++
    foreach ($url in @('jdbc:mysql://127.0.0.1:3306/approvaplat','jdbc:mysql://localhost:13306/approvaplat',
        'jdbc:mysql://127.0.0.1:13306/approvaplat_it','jdbc:mysql://127.0.0.1:13306/%61pprovaplat',
        'jdbc:mysql://user@127.0.0.1:13306/approvaplat','jdbc:mysql://127.0.0.1:13306/approvaplat#other',
        'jdbc:mysql://127.0.0.1:13306/approvaplat?sessionVariables=x','jdbc:mysql://127.0.0.1:13306/approvaplat?useSSL=false&useSSL=true')) {
        Expect-Failure { Assert-VerificationTarget $temporaryRoot $url }
    }
    foreach ($optIn in @('', 'true', 'approvaplat:other')) {
        $env:WORKFLOW_DEVELOPMENT_TEST_OPT_IN = $optIn
        Expect-Failure { Assert-VerificationTarget $temporaryRoot $state.databaseUrl }
    }
    $env:WORKFLOW_DEVELOPMENT_TEST_OPT_IN = "approvaplat:$($state.runId)"
    Expect-Failure { & $environmentTool -Action Register -ServerUuid '00000000-1111-2222-3333-444444444444' }
    # Mock the only database boundary: no client or server is started in this test.
    function Invoke-VerificationMySql($Server, $Username, $Password, $Sql, $Database) {
        if ($Sql -ne 'SELECT DATABASE(), @@server_uuid') { throw 'Unexpected SQL in identity validation' }
        "approvaplat`t$script:actualUuid"
    }
    $script:actualUuid = $state.serverUuid
    $null = Assert-VerificationIdentity $temporaryRoot $state.databaseUrl 'synthetic-user' 'synthetic-test-only'
    $checks++
    $script:actualUuid = 'aaaaaaaa-1111-2222-3333-444444444444'
    Expect-Failure { Assert-VerificationIdentity $temporaryRoot $state.databaseUrl 'synthetic-user' 'synthetic-test-only' }
    function Invoke-VerificationMySql($Server, $Username, $Password, $Sql, $Database) {
        if ($Sql -notmatch '^SELECT ') { throw 'Preflight attempted a non-read-only statement' }
        if ($script:busyTarget -and $Sql.Contains($script:busyTarget)) { '1' } else { '0' }
    }
    $script:busyTarget = ''
    $server = [Uri]'mysql://127.0.0.1:13306/approvaplat'
    Assert-VerificationQuietDatabase $server 'synthetic-user' 'synthetic-test-only' 'approvaplat'
    Assert-VerificationEmptyRetentionTables $server 'synthetic-user' 'synthetic-test-only' 'approvaplat'
    $checks += 2
    $script:busyTarget = 'wf_attachment_upload'
    Expect-Failure { Assert-VerificationQuietDatabase $server 'synthetic-user' 'synthetic-test-only' 'approvaplat' }
    Expect-Failure { Assert-VerificationEmptyRetentionTables $server 'synthetic-user' 'synthetic-test-only' 'approvaplat' }
    $script:busyTarget = 'wf_copy'
    Expect-Failure { Assert-VerificationEmptyRetentionTables $server 'synthetic-user' 'synthetic-test-only' 'approvaplat' }
    $script:busyTarget = 'ACT_RU_TIMER_JOB'
    Expect-Failure { Assert-VerificationQuietDatabase $server 'synthetic-user' 'synthetic-test-only' 'approvaplat' }
    $state.expiresAt = [DateTimeOffset]::UtcNow.AddSeconds(-1).ToString('o')
    $state | ConvertTo-Json -Depth 10 | Set-Content -LiteralPath $statePath -Encoding utf8
    Expect-Failure { Assert-VerificationTarget $temporaryRoot $state.databaseUrl }
    & $environmentTool -Action Cleanup -StateFile $statePath | Out-Null
    $closed = Get-Content -LiteralPath $statePath -Raw | ConvertFrom-Json
    if ($closed.status -ne 'closed' -or -not $closed.cleanup.databasePreserved -or -not $closed.cleanup.accountPreserved) { throw 'Shared target was not preserved during cleanup' }
    $checks++
    Expect-Failure { Assert-VerificationTarget $temporaryRoot $state.databaseUrl }
    $processStart = [DateTime]::UtcNow
    $entry = @{ pid = 123; started = $processStart.ToString('o'); executable = 'synthetic.exe' }
    function Get-Process {
        param($Id)
        $script:processReads++
        if ($script:processReplies.Count) { return $script:processReplies.Dequeue() }
        $null
    }
    try {
        $script:processReads = 0
        $script:processReplies = [Collections.Queue]::new()
        $script:processReplies.Enqueue(@{ StartTime = $processStart; Path = $null })
        $script:processReplies.Enqueue(@{ StartTime = $processStart; Path = 'synthetic.exe' })
        if (-not (Assert-VerificationProcess $entry) -or $script:processReads -ne 2) { throw 'Process startup path was not retried' }
        $checks++
        foreach ($unrelated in @(
            @{ StartTime = $processStart.AddSeconds(1); Path = 'synthetic.exe' },
            @{ StartTime = $processStart; Path = 'other.exe' }
        )) {
            $script:processReads = 0
            $script:processReplies.Enqueue($unrelated)
            Expect-Failure { Assert-VerificationProcess $entry }
            if ($script:processReads -ne 1) { throw 'Known process mismatch must fail immediately' }
        }
        if (Assert-VerificationProcess $entry) { throw 'Exited process must not be accepted' }
        $checks++
        for ($i = 0; $i -lt 20; $i++) { $script:processReplies.Enqueue(@{ StartTime = $processStart; Path = $null }) }
        Expect-Failure { Assert-VerificationProcess $entry }

        # A failed stop must keep the registration exclusive until cleanup succeeds.
        $state.status = 'active'
        $state.expiresAt = [DateTimeOffset]::UtcNow.AddHours(1).ToString('o')
        $state.servicesStarted = $true
        $entry.directory = $state.directory
        $state.processes = @($entry)
        $state | ConvertTo-Json -Depth 10 | Set-Content -LiteralPath $statePath -Encoding utf8
        $stopState = @{ alive = $true; mode = 'throws' }
        function Get-Process {
            param($Id)
            if ($stopState.alive) { @{ StartTime = $processStart; Path = 'synthetic.exe' } }
        }
        function Stop-Process {
            param($Id)
            if ($stopState.mode -eq 'throws') { throw 'Synthetic stop failure' }
            if ($stopState.mode -eq 'exits') { $stopState.alive = $false }
        }
        function Wait-Process { param($Id, $Timeout) }
        try {
            Expect-Failure { & $environmentTool -Action Cleanup -StateFile $statePath }
            $failedCleanup = Get-Content -LiteralPath $statePath -Raw | ConvertFrom-Json
            if ($failedCleanup.status -ne 'cleanup-failed' -or -not $failedCleanup.servicesStarted -or
                $failedCleanup.cleanup.failures.Count -ne 1) { throw 'Failed cleanup must retain the service registration' }
            $checks++
            Expect-Failure { & $environmentTool -Action Register -ServerUuid '00000000-1111-2222-3333-444444444444' }
            $stopState.mode = 'stays-alive'
            Expect-Failure { & $environmentTool -Action Cleanup -StateFile $statePath }
            $stopState.mode = 'exits'
            & $environmentTool -Action Cleanup -StateFile $statePath | Out-Null
            $retried = Get-Content -LiteralPath $statePath -Raw | ConvertFrom-Json
            if ($retried.status -ne 'closed' -or $retried.servicesStarted -or $retried.cleanup.failures.Count) {
                throw 'Successful cleanup retry must close the registration'
            }
            $checks++
        } finally { Remove-Item Function:\Stop-Process, Function:\Wait-Process }
    } finally { Remove-Item Function:\Get-Process }
    Write-Output "PASS: $checks target guard checks; zero database connections, no credentials saved"
} finally {
    foreach ($name in $names) { [Environment]::SetEnvironmentVariable($name, $saved[$name], 'Process') }
    $expectedParent = [IO.Path]::GetFullPath([IO.Path]::GetTempPath()).TrimEnd('\','/')
    if ((Split-Path $temporaryRoot) -eq $expectedParent -and (Split-Path $temporaryRoot -Leaf) -match '^approvaplat-target-guard-[a-f0-9]{32}$') {
        Remove-Item -LiteralPath $temporaryRoot -Recurse -Force -ErrorAction SilentlyContinue
    }
}
