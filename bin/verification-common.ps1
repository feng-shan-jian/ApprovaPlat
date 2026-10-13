# Small shared primitives for the four-mode entry and its owned environment.
function Get-VerificationHash([string]$Text) {
    [Convert]::ToHexString([Security.Cryptography.SHA256]::HashData([Text.Encoding]::UTF8.GetBytes($Text))).ToLowerInvariant()
}
function Get-VerificationSource([string]$Repository, [switch]$ServiceOnly) {
    $files = @(& git -C $Repository -c core.quotepath=false ls-files --cached --others --exclude-standard | Sort-Object -Unique)
    if ($ServiceOnly) { $files = @($files | Where-Object { $_ -match '(^|/)pom\.xml$|/src/main/|^ruoyi-ui/(src/|plugins/|package.*\.json$|vite\.config\.js$|\.env)' }) }
    $entries = foreach ($file in $files) {
        $path = Join-Path $Repository $file
        if (Test-Path -LiteralPath $path -PathType Leaf) { "$file`t$((Get-FileHash -LiteralPath $path -Algorithm SHA256).Hash)" }
        else { "$file`tdeleted" }
    }
    Get-VerificationHash ($entries -join "`n")
}
function Get-VerificationBaseline([string]$Repository) {
    foreach ($relative in Get-Content -LiteralPath (Join-Path $Repository 'sql/baseline-files.txt')) {
        if (-not $relative.Trim() -or $relative.StartsWith('#')) { continue }
        if ($relative -notmatch '^[a-zA-Z0-9_./-]+\.sql$' -or $relative.Contains('..')) { throw "Invalid baseline path: $relative" }
        $path = Join-Path $Repository "sql/$relative"
        if (-not (Test-Path -LiteralPath $path -PathType Leaf)) { throw "Missing baseline: $relative" }
        [pscustomobject]@{ relative = $relative; path = $path; hash = (Get-FileHash -LiteralPath $path -Algorithm SHA256).Hash }
    }
}
function Invoke-VerificationMySql([Uri]$Server, [string]$Username, [string]$Password, [string]$Sql, [string]$Database = '') {
    $client = if ($env:WORKFLOW_MYSQL_CLIENT) { $env:WORKFLOW_MYSQL_CLIENT } else { (Get-Command mysql -ErrorAction Stop).Source }
    $info = [Diagnostics.ProcessStartInfo]::new($client)
    $info.UseShellExecute = $false
    $info.CreateNoWindow = $true
    $info.RedirectStandardInput = $true
    $info.RedirectStandardOutput = $true
    $info.RedirectStandardError = $true
    $info.StandardInputEncoding = [Text.UTF8Encoding]::new($false)
    foreach ($arg in @('--protocol=TCP','--connect-timeout=5','-h',$Server.Host,'-P',$(if ($Server.Port -gt 0) { "$($Server.Port)" } else { '3306' }),'-u',$Username,'--batch','--raw','--skip-column-names','--default-character-set=utf8mb4')) { $info.ArgumentList.Add($arg) }
    if ($Database) { $info.ArgumentList.Add("--database=$Database") }
    $info.Environment['MYSQL_PWD'] = $Password
    $process = [Diagnostics.Process]::Start($info)
    $stdout = $process.StandardOutput.ReadToEndAsync()
    $stderr = $process.StandardError.ReadToEndAsync()
    $process.StandardInput.Write($Sql)
    $process.StandardInput.Close()
    $process.WaitForExit()
    $errorText = $stderr.GetAwaiter().GetResult()
    if ($process.ExitCode -ne 0) {
        # SQL may include a generated password. Do not echo SQL or command text.
        $code = if ($errorText -match 'ERROR ([0-9]+)') { $matches[1] } else { $process.ExitCode }
        throw "MySQL operation failed (code $code); host=$($Server.Host), database=$Database"
    }
    $stdout.GetAwaiter().GetResult().Trim()
}
function Assert-VerificationMySql([string]$Repository, [string]$Url, [string]$Username, [string]$Password) {
    $state = Assert-VerificationIdentity $Repository $Url $Username $Password
    $uri = [Uri]($Url.Substring(5))
    $database = $state.database
    $tables = @((Invoke-VerificationMySql $uri $Username $Password 'SHOW TABLES' $database) -split "\r?\n")
    $expected = foreach ($entry in Get-VerificationBaseline $Repository) {
        foreach ($match in [regex]::Matches((Get-Content -LiteralPath $entry.path -Raw), '(?im)^\s*create\s+table\s+(?:if\s+not\s+exists\s+)?`?([a-zA-Z0-9_]+)')) { $match.Groups[1].Value }
    }
    $missing = @($expected | Sort-Object -Unique | Where-Object { $_ -notin $tables })
    if ($missing.Count) { throw "Incomplete baseline; missing tables: $($missing -join ', ')" }
    Assert-VerificationQuietDatabase $uri $Username $Password $database
    [ordered]@{ host = $uri.Host; port = $uri.Port; database = $database; username = $Username; serverUuid = $state.serverUuid; runId = $state.runId; expectedTables = @($expected | Sort-Object -Unique).Count; baselineHash = Get-VerificationHash ((Get-VerificationBaseline $Repository | ForEach-Object { "$($_.relative):$($_.hash)" }) -join "`n") }
}
function Test-VerificationPort([int]$Port, [string]$HostName = '127.0.0.1') {
    $client = [Net.Sockets.TcpClient]::new()
    try { $client.ConnectAsync($HostName, $Port).WaitAsync([TimeSpan]::FromSeconds(1)).GetAwaiter().GetResult(); $true }
    catch { $false }
    finally { $client.Dispose() }
}
function Get-VerificationFreePort {
    $listener = [Net.Sockets.TcpListener]::new([Net.IPAddress]::Loopback, 0)
    $listener.Start()
    try { $listener.LocalEndpoint.Port } finally { $listener.Stop() }
}
function Assert-VerificationProcess($Entry) {
    # Windows can expose StartTime before the process main-module path is ready.
    # Retry only that unavailable field; a known identity mismatch fails immediately.
    for ($attempt = 0; $attempt -lt 20; $attempt++) {
        $process = Get-Process -Id $Entry.pid -ErrorAction SilentlyContinue
        if (-not $process) { return $false }
        if ($process.StartTime.ToUniversalTime() -ne ([DateTime]$Entry.started).ToUniversalTime()) { throw "Refusing unrelated PID $($Entry.pid)" }
        $executable = $process.Path
        if ($executable) {
            if ($executable -ne $Entry.executable) { throw "Refusing unrelated PID $($Entry.pid)" }
            return $true
        }
        Start-Sleep -Milliseconds 50
    }
    throw "Cannot verify executable for PID $($Entry.pid)"
}

function Read-VerificationTarget([string]$Repository, [string]$StatePath = $env:WORKFLOW_VERIFICATION_STATE_PATH, [switch]$AllowInactive) {
    if (-not $StatePath) { throw 'Register the development target and set WORKFLOW_VERIFICATION_STATE_PATH first' }
    $path = (Resolve-Path -LiteralPath $StatePath).Path
    $state = Get-Content -LiteralPath $path -Raw -Encoding utf8 | ConvertFrom-Json -AsHashtable
    if ($state.runId -notmatch '^[a-f0-9]{24}$') { throw 'Invalid run identifier' }
    $expected = [IO.Path]::GetFullPath((Join-Path $Repository "output/verification-environments/$($state.runId)/state.json"))
    if ($path -ne $expected -or [IO.Path]::GetFullPath($state.repository) -ne [IO.Path]::GetFullPath($Repository) -or $state.project -cne 'ApprovaPlat' -or $state.mode -cne 'shared-development') { throw 'Registration belongs to another repository or mode' }
    if ((Get-Content -LiteralPath (Join-Path (Split-Path $path) 'owner') -Raw).Trim() -cne $state.runId) { throw 'Run owner mismatch' }
    if ($state.host -cne '127.0.0.1' -or [string]$state.port -cne '13306' -or $state.database -cne 'approvaplat' -or $state.serverUuid -cnotmatch '^[a-f0-9]{8}(-[a-f0-9]{4}){3}-[a-f0-9]{12}$') { throw 'Unapproved development target' }
    if (-not $AllowInactive) {
        $now = [DateTimeOffset]::UtcNow
        $expiry = [DateTimeOffset]::Parse($state.expiresAt)
        if ($state.status -cne 'active' -or $expiry -le $now -or $expiry -gt $now.AddHours(8)) { throw 'Expired or inactive run registration' }
    }
    $state
}
function Assert-VerificationTarget([string]$Repository, [string]$Url) {
    $state = Read-VerificationTarget $Repository
    if ($env:WORKFLOW_DEVELOPMENT_TEST_OPT_IN -cne "approvaplat:$($state.runId)") { throw 'Explicit current-run WORKFLOW_DEVELOPMENT_TEST_OPT_IN=approvaplat:<runId> is required' }
    if ($Url -cnotmatch '^jdbc:mysql://127[.]0[.]0[.]1:13306/approvaplat(?:[?][^#]*)?$') { throw 'Connection target differs from the registered development database' }
    $uri = [Uri]($Url.Substring(5))
    $seen = @{}
    if ($uri.Query) {
        foreach ($option in $uri.Query.Substring(1).Split('&')) {
            $pair = $option.Split('=', 2)
            if ($pair.Count -ne 2 -or $pair[0] -cnotin @('useUnicode','characterEncoding','useSSL','allowPublicKeyRetrieval','serverTimezone') -or $seen.ContainsKey($pair[0])) { throw 'Unapproved JDBC connection option' }
            $seen[$pair[0]] = $true
        }
    }
    $state
}
function Assert-VerificationIdentity([string]$Repository, [string]$Url, [string]$Username, [string]$Password) {
    $state = Assert-VerificationTarget $Repository $Url
    if (-not $Username -or -not $Password) { throw 'MySQL credentials are required in the current process' }
    $identity = Invoke-VerificationMySql ([Uri]($Url.Substring(5))) $Username $Password 'SELECT DATABASE(), @@server_uuid' $state.database
    if ($identity -cne "$($state.database)`t$($state.serverUuid)") { throw 'Connected database/server UUID does not match registration' }
    $state
}
function Assert-VerificationQuietDatabase([Uri]$Server, [string]$Username, [string]$Password, [string]$Database) {
    # Shared data is not automatically disposable. Global workers/retention must start with no work.
    $queries = @{
        notifications = "SELECT COUNT(*) FROM wf_notification_outbox WHERE status IN ('PENDING','RETRYING','DELIVERING')"
        collaboration = "SELECT COUNT(*) FROM wf_collaboration_outbox WHERE status IN ('PENDING','RETRYING','DELIVERING')"
        inbound = "SELECT COUNT(*) FROM wf_collaboration_message WHERE status IN ('RECEIVED','RETRYING')"
        events = "SELECT COUNT(*) FROM wf_runtime_event_request WHERE status='RECEIVED'"
        uploads = 'SELECT COUNT(*) FROM wf_attachment_upload'
        instances = 'SELECT COUNT(*) FROM ACT_RU_EXECUTION'
        jobs = 'SELECT (SELECT COUNT(*) FROM ACT_RU_JOB) + (SELECT COUNT(*) FROM ACT_RU_TIMER_JOB) + (SELECT COUNT(*) FROM ACT_RU_SUSPENDED_JOB) + (SELECT COUNT(*) FROM ACT_RU_DEADLETTER_JOB)'
    }
    foreach ($name in $queries.Keys) {
        if ((Invoke-VerificationMySql $Server $Username $Password $queries[$name] $Database) -ne '0') { throw "Shared database has existing $name work; finish or explicitly archive it before verification" }
    }
}

function Assert-VerificationEmptyRetentionTables([Uri]$Server, [string]$Username, [string]$Password, [string]$Database) {
    # Starting timers enables global retention/attachment consumers. Never silently process existing data.
    foreach ($table in @('wf_notification_outbox','wf_runtime_event_request','wf_process_draft',
        'wf_collaboration_message','wf_collaboration_outbox','wf_attachment','wf_attachment_upload','wf_notification_inbox',
        'wf_bpmn_event_audit','wf_task_sla_execution','wf_copy','wf_controlled_loop_execution','wf_multi_instance_round')) {
        if ((Invoke-VerificationMySql $Server $Username $Password "SELECT COUNT(*) FROM $table" $Database) -ne '0') {
            throw "Shared development service preflight requires empty $table; existing data is preserved"
        }
    }
}
