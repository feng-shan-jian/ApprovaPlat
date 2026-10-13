[CmdletBinding()]
param(
    [ValidateSet('Register', 'Load', 'StartServices', 'Cleanup', 'Validate', 'CheckBrowser')]
    [string]$Action = 'Validate',
    [string]$StateFile = $env:WORKFLOW_VERIFICATION_STATE_PATH,
    [string]$ServerUuid,
    [string]$RedisExecutable,
    [string]$BackendJar,
    [switch]$EnableDevelopmentTests,
    [switch]$UseApplicationEnvironment,
    [PSCredential]$DatabaseCredential,
    [PSCredential]$BrowserCredential
)
$ErrorActionPreference = 'Stop'
if (($UseApplicationEnvironment -or $DatabaseCredential -or $BrowserCredential) -and $Action -ne 'Load') {
    throw 'Credential inputs are supported only by Load'
}
if ($UseApplicationEnvironment -and $DatabaseCredential) {
    throw 'Choose the existing application environment or a database credential, not both'
}
. (Join-Path $PSScriptRoot 'verification-common.ps1')
$repository = (Resolve-Path (Join-Path $PSScriptRoot '..')).Path
$environmentRoot = [IO.Path]::GetFullPath((Join-Path $repository 'output/verification-environments'))

function Save-State {
    $state | ConvertTo-Json -Depth 10 | Set-Content -LiteralPath $StateFile -Encoding utf8
}
function Read-State([switch]$AllowInactive) {
    $script:state = Read-VerificationTarget $repository $StateFile -AllowInactive:$AllowInactive
    $script:StateFile = (Resolve-Path -LiteralPath $StateFile).Path
    if ([IO.Path]::GetFullPath($state.directory) -ne (Split-Path $StateFile)) { throw 'Run directory mismatch' }
}
function Set-RunEnvironment {
    # Credential input is explicit: inherited application environment or a caller-supplied
    # PSCredential (Get-Credential / an approved vault). Never discover or read secret files.
    $loadCredentials = $UseApplicationEnvironment -or $DatabaseCredential -or $BrowserCredential
    if ($loadCredentials -and -not $EnableDevelopmentTests) { throw 'Credential loading requires -EnableDevelopmentTests' }
    $variables = @{
        WORKFLOW_MYSQL_TEST_URL = $state.databaseUrl
        WORKFLOW_VERIFICATION_STATE_PATH = $StateFile
    }
    if ($state.redisPort) {
        $variables.WORKFLOW_REDIS_TEST_HOST = '127.0.0.1'
        $variables.WORKFLOW_REDIS_TEST_PORT = "$($state.redisPort)"
        $variables.WORKFLOW_REDIS_TEST_PASSWORD = ''
        $variables.WORKFLOW_E2E_REDIS_PORT = "$($state.redisPort)"
    }
    if ($state.frontendPort) { $variables.WORKFLOW_E2E_BASE_URL = "http://127.0.0.1:$($state.frontendPort)" }
    if ($EnableDevelopmentTests) { $variables.WORKFLOW_DEVELOPMENT_TEST_OPT_IN = "approvaplat:$($state.runId)" }
    else { $variables.WORKFLOW_DEVELOPMENT_TEST_OPT_IN = '' }
    $names = @($variables.Keys)
    if ($loadCredentials) { $names += @('WORKFLOW_MYSQL_TEST_USERNAME','WORKFLOW_MYSQL_TEST_PASSWORD') }
    if ($BrowserCredential) { $names += @('WORKFLOW_E2E_USERNAME','WORKFLOW_E2E_PASSWORD') }
    $previous = @{}
    foreach ($name in $names) { $previous[$name] = [Environment]::GetEnvironmentVariable($name, 'Process') }
    try {
        foreach ($name in $variables.Keys) { [Environment]::SetEnvironmentVariable($name, $variables[$name], 'Process') }
        if ($loadCredentials) {
            if ($UseApplicationEnvironment) {
                if ($env:RUOYI_DB_URL) { $null = Assert-VerificationTarget $repository $env:RUOYI_DB_URL }
                $username = $env:RUOYI_DB_USERNAME
                $password = $env:RUOYI_DB_PASSWORD
            } elseif ($DatabaseCredential) {
                $username = $DatabaseCredential.UserName
                $password = $DatabaseCredential.GetNetworkCredential().Password
            } else {
                $username = $env:WORKFLOW_MYSQL_TEST_USERNAME
                $password = $env:WORKFLOW_MYSQL_TEST_PASSWORD
            }
            if ([string]::IsNullOrWhiteSpace($username) -or [string]::IsNullOrEmpty($password)) {
                throw 'The selected credential source is incomplete; no secret-file lookup is performed'
            }
            if ($username -ieq 'root') { throw 'Use the existing non-root development application account' }
            if ($BrowserCredential) {
                $browserPassword = $BrowserCredential.GetNetworkCredential().Password
                if ([string]::IsNullOrWhiteSpace($BrowserCredential.UserName) -or [string]::IsNullOrEmpty($browserPassword)) {
                    throw 'The supplied browser credential is incomplete'
                }
            }
            try {
                $null = Assert-VerificationIdentity $repository $state.databaseUrl $username $password
            } catch {
                throw 'Database credential or target identity validation failed; prior process environment restored'
            }
            [Environment]::SetEnvironmentVariable('WORKFLOW_MYSQL_TEST_USERNAME', $username, 'Process')
            [Environment]::SetEnvironmentVariable('WORKFLOW_MYSQL_TEST_PASSWORD', $password, 'Process')
            if ($BrowserCredential) {
                [Environment]::SetEnvironmentVariable('WORKFLOW_E2E_USERNAME', $BrowserCredential.UserName, 'Process')
                [Environment]::SetEnvironmentVariable('WORKFLOW_E2E_PASSWORD', $browserPassword, 'Process')
            }
        }
    } catch {
        foreach ($name in $names) {
            if ($null -eq $previous[$name]) { Remove-Item -LiteralPath "Env:$name" -ErrorAction SilentlyContinue }
            else { [Environment]::SetEnvironmentVariable($name, $previous[$name], 'Process') }
        }
        throw
    } finally {
        $password = $null
        $browserPassword = $null
    }
}
function Start-Redis {
    if ($state.processes | Where-Object name -EQ 'redis') { return }
    if (-not $RedisExecutable) {
        $redis = Get-Command redis-server -ErrorAction SilentlyContinue
        if ($redis) { $script:RedisExecutable = $redis.Source }
        elseif (Test-Path -LiteralPath 'C:/Program Files/Memurai/memurai.exe') { $script:RedisExecutable = 'C:/Program Files/Memurai/memurai.exe' }
        else { throw 'Pass the installed -RedisExecutable; nothing is installed automatically' }
    }
    $state.redisPort = Get-VerificationFreePort
    $redisDirectory = Join-Path $state.directory 'redis'
    New-Item -ItemType Directory -Path $redisDirectory, (Join-Path $state.directory 'uploads'), (Join-Path $state.directory 'token') -Force | Out-Null
    $config = Join-Path $state.directory 'redis.conf'
    @("bind 127.0.0.1", "port $($state.redisPort)", 'protected-mode yes', 'save ""', 'appendonly no', "dir `"$($redisDirectory.Replace('\','/'))`"") | Set-Content -LiteralPath $config -Encoding utf8
    $null = Start-OwnedProcess 'redis' $RedisExecutable @("`"$config`"") @{} $state.directory
    Wait-OwnedPort 'redis' $state.redisPort
}
function Start-OwnedProcess([string]$Name, [string]$Executable, [string[]]$Arguments, [hashtable]$EnvironmentValues, [string]$WorkingDirectory) {
    $parameters = @{ FilePath = $Executable; ArgumentList = $Arguments; WorkingDirectory = $WorkingDirectory; PassThru = $true; WindowStyle = 'Hidden'; RedirectStandardOutput = (Join-Path $state.directory "$Name.stdout.log"); RedirectStandardError = (Join-Path $state.directory "$Name.stderr.log") }
    if ($EnvironmentValues.Count) { $parameters.Environment = $EnvironmentValues }
    $process = Start-Process @parameters
    $process.Refresh()
    $state.processes += @{ name = $Name; pid = $process.Id; started = $process.StartTime.ToUniversalTime().ToString('o'); executable = [IO.Path]::GetFullPath($Executable); directory = $WorkingDirectory }
    Save-State
    $process
}
function Wait-OwnedPort([string]$Name, [int]$Port, [int]$Seconds = 30) {
    $deadline = (Get-Date).AddSeconds($Seconds)
    do {
        $entry = $state.processes | Where-Object name -EQ $Name | Select-Object -Last 1
        if (-not (Assert-VerificationProcess $entry)) { throw "$Name exited; inspect this run's logs" }
        if (Test-VerificationPort $Port) { return }
        Start-Sleep -Milliseconds 250
    } while ((Get-Date) -lt $deadline)
    throw "$Name did not listen on port $Port"
}
function Start-Services {
    if ($state.servicesStarted) { throw 'Services already started for this run' }
    if (-not $BackendJar) { $script:BackendJar = Join-Path $repository 'ruoyi-admin/target/ruoyi-admin.jar' }
    if (-not (Test-Path -LiteralPath $BackendJar -PathType Leaf)) { throw 'Build the backend first: mvn -B -DskipTests package' }
    $jarTime = (Get-Item -LiteralPath $BackendJar).LastWriteTimeUtc
    $newerInputs = @(& git -C $repository ls-files --cached --others --exclude-standard | Where-Object { $_ -match '(^|/)pom\.xml$|/src/main/' } | Where-Object { (Get-Item -LiteralPath (Join-Path $repository $_) -ErrorAction SilentlyContinue).LastWriteTimeUtc -gt $jarTime })
    if ($newerInputs.Count) { throw "Backend jar predates application inputs; rebuild first: $($newerInputs -join ', ')" }
    $java = (Get-Command java -ErrorAction Stop).Source
    $node = (Get-Command node -ErrorAction Stop).Source
    $vite = Join-Path $repository 'ruoyi-ui/node_modules/vite/bin/vite.js'
    if (-not (Test-Path -LiteralPath $vite)) { throw 'Missing frontend dependencies; run npm ci in ruoyi-ui explicitly' }
    $null = Assert-VerificationMySql $repository $state.databaseUrl $env:WORKFLOW_MYSQL_TEST_USERNAME $env:WORKFLOW_MYSQL_TEST_PASSWORD
    Assert-VerificationEmptyRetentionTables ([Uri]($state.databaseUrl.Substring(5))) $env:WORKFLOW_MYSQL_TEST_USERNAME $env:WORKFLOW_MYSQL_TEST_PASSWORD $state.database
    Start-Redis
    $state.backendPort = Get-VerificationFreePort
    do { $state.frontendPort = Get-VerificationFreePort } while ($state.frontendPort -eq $state.backendPort)
    $state.serviceSourceHash = Get-VerificationSource $repository -ServiceOnly
    # Copy the exact jar so concurrent Maven packaging cannot replace a running artifact.
    $jar = Join-Path $state.directory 'backend.jar'
    Copy-Item -LiteralPath $BackendJar -Destination $jar
    $state.backendArtifact = @{ path = $jar; sha256 = (Get-FileHash -LiteralPath $jar -Algorithm SHA256).Hash; input = [IO.Path]::GetFullPath($BackendJar) }
    $state.frontendArtifact = @{ kind = 'Vite source'; root = (Join-Path $repository 'ruoyi-ui'); sourceHash = $state.serviceSourceHash; lockSha256 = (Get-FileHash -LiteralPath (Join-Path $repository 'ruoyi-ui/package-lock.json')).Hash }
    $values = @{
        RUOYI_DB_URL = $state.databaseUrl; RUOYI_DB_USERNAME = $env:WORKFLOW_MYSQL_TEST_USERNAME; RUOYI_DB_PASSWORD = $env:WORKFLOW_MYSQL_TEST_PASSWORD
        RUOYI_PROFILE = (Join-Path $state.directory 'uploads'); RUOYI_TOKEN_SECRET_FILE = (Join-Path $state.directory 'token/secret')
        RUOYI_TOKEN_SECRET = ''; SPRING_DATA_REDIS_HOST = '127.0.0.1'; SPRING_DATA_REDIS_PORT = "$($state.redisPort)"; SPRING_DATA_REDIS_PASSWORD = ''; SPRING_DATA_REDIS_DATABASE = '0'
        SERVER_PORT = "$($state.backendPort)"; SPRING_PROFILES_ACTIVE = 'druid,timers'; APPROVAPLAT_NOTIFICATION_WORKER_ENABLED = 'true'; APPROVAPLAT_NOTIFICATION_WORKER_DELAY = '3000'; APPROVAPLAT_NOTIFICATION_MAX_RETRY_DELAY = 'PT1S'
    }
    $null = Start-OwnedProcess 'backend' $java @('-jar', "`"$jar`"") $values $state.directory
    Wait-OwnedPort 'backend' $state.backendPort 150
    $deadline = (Get-Date).AddSeconds(60)
    do {
        try { $probe = Invoke-RestMethod "http://127.0.0.1:$($state.backendPort)/captchaImage" -TimeoutSec 3; if ($probe.code -eq 200) { break } } catch { }
        Start-Sleep -Milliseconds 300
    } while ((Get-Date) -lt $deadline)
    if ($probe.code -ne 200) { throw 'Backend HTTP readiness failed' }
    $null = Start-OwnedProcess 'frontend' $node @("`"$vite`"", '--host', '127.0.0.1', '--port', "$($state.frontendPort)", '--strictPort') @{ VITE_PROXY_TARGET = "http://127.0.0.1:$($state.backendPort)" } (Join-Path $repository 'ruoyi-ui')
    Wait-OwnedPort 'frontend' $state.frontendPort 30
    $state.serviceDatabaseUrl = $state.databaseUrl
    $state.serviceServerUuid = $state.serverUuid
    $state.servicesStarted = $true
    Save-State
}
function Stop-RunServices {
    Read-State -AllowInactive
    $failures = @()
    foreach ($entry in @($state.processes | Sort-Object started -Descending)) {
        try {
            if ([IO.Path]::GetFullPath($entry.directory) -ne [IO.Path]::GetFullPath($state.directory) -and [IO.Path]::GetFullPath($entry.directory) -ne (Join-Path $repository 'ruoyi-ui')) { throw 'Invalid process work directory' }
            if (Assert-VerificationProcess $entry) {
                Stop-Process -Id $entry.pid -ErrorAction Stop
                Wait-Process -Id $entry.pid -Timeout 15 -ErrorAction SilentlyContinue
                if (Assert-VerificationProcess $entry) { throw "Owned PID $($entry.pid) did not exit after stop" }
            }
        } catch { $failures += $_.Exception.Message }
    }
    # A failed stop may leave a consumer using the shared database. Keep the run
    # exclusive and retain its service state until a later Cleanup succeeds.
    $state.status = if ($failures.Count) { 'cleanup-failed' } else { 'closed' }
    if (-not $failures.Count) { $state.servicesStarted = $false }
    $state.cleanup = @{ finished = (Get-Date).ToString('o'); failures = $failures; databasePreserved = $true; accountPreserved = $true }
    Save-State
    if ($failures.Count) { throw "Service cleanup incomplete: $($failures -join '; ')" }
    Write-Output "Closed run $($state.runId); shared database, account and evidence files preserved"
}
function Assert-BrowserServices {
    Read-State
    $null = Assert-VerificationMySql $repository $env:WORKFLOW_MYSQL_TEST_URL $env:WORKFLOW_MYSQL_TEST_USERNAME $env:WORKFLOW_MYSQL_TEST_PASSWORD
    if (-not $state.servicesStarted -or $state.databaseUrl -cne $env:WORKFLOW_MYSQL_TEST_URL -or $state.serviceDatabaseUrl -cne $state.databaseUrl -or $state.serviceServerUuid -cne $state.serverUuid -or "http://127.0.0.1:$($state.frontendPort)" -cne $env:WORKFLOW_E2E_BASE_URL -or [string]$state.redisPort -cne $env:WORKFLOW_E2E_REDIS_PORT) { throw 'Browser/database/Redis target differs from the owned service run' }
    if ($state.serviceSourceHash -ne (Get-VerificationSource $repository -ServiceOnly)) { throw 'Application source changed after services started' }
    foreach ($name in @('backend','frontend','redis')) {
        $entries = @($state.processes | Where-Object name -EQ $name)
        if ($entries.Count -ne 1 -or -not (Assert-VerificationProcess $entries[0])) { throw "Owned $name process is unavailable" }
    }
    if ((Get-FileHash -LiteralPath $state.backendArtifact.path).Hash -ne $state.backendArtifact.sha256) { throw 'Running backend artifact has changed' }
}

switch ($Action) {
    'Register' {
        if ($ServerUuid -cnotmatch '^[a-f0-9]{8}(-[a-f0-9]{4}){3}-[a-f0-9]{12}$') { throw 'Supply the independently approved MySQL server UUID with -ServerUuid' }
        # A single active local registration. Do not allow parallel acceptance against shared data.
        foreach ($existing in Get-ChildItem -Path "$environmentRoot/*/state.json" -ErrorAction SilentlyContinue) {
            $registered = Get-Content -LiteralPath $existing.FullName -Raw | ConvertFrom-Json -AsHashtable
            if ($registered.mode -eq 'shared-development' -and $registered.status -ne 'closed') { throw 'Close the existing shared-development registration before registering another run' }
        }
        $runId = [Guid]::NewGuid().ToString('N').Substring(0, 24)
        $directory = Join-Path $environmentRoot $runId
        New-Item -ItemType Directory -Path $directory -Force | Out-Null
        $StateFile = Join-Path $directory 'state.json'
        $state = [ordered]@{
            project = 'ApprovaPlat'; mode = 'shared-development'; status = 'active'
            runId = $runId; repository = $repository.Replace('\','/'); directory = $directory.Replace('\','/')
            host = '127.0.0.1'; port = '13306'; database = 'approvaplat'; serverUuid = $ServerUuid
            expiresAt = [DateTimeOffset]::UtcNow.AddHours(8).ToString('o')
            databaseUrl = 'jdbc:mysql://127.0.0.1:13306/approvaplat?useUnicode=true&characterEncoding=utf8&useSSL=false&allowPublicKeyRetrieval=true&serverTimezone=Asia%2FShanghai'
            processes = @(); servicesStarted = $false; created = [DateTimeOffset]::UtcNow.ToString('o')
        }
        Set-Content -LiteralPath (Join-Path $directory 'owner') -Value $runId -Encoding utf8
        Save-State
        Write-Output "Registered target only (no connection, initialization or credentials saved): $StateFile"
    }
    'Load' { Read-State; Set-RunEnvironment; Write-Output "Loaded run $($state.runId); explicit test opt-in: $([bool]$EnableDevelopmentTests)" }
    'Validate' {
        $null = Read-VerificationTarget $repository $StateFile
        Assert-VerificationMySql $repository $env:WORKFLOW_MYSQL_TEST_URL $env:WORKFLOW_MYSQL_TEST_USERNAME $env:WORKFLOW_MYSQL_TEST_PASSWORD | ConvertTo-Json
    }
    'StartServices' {
        Read-State
        try { Start-Services } catch { $failure = $_; Stop-RunServices; throw $failure }
        Write-Output "Services ready. Load with -Action Load -EnableDevelopmentTests -StateFile '$StateFile'"
    }
    'CheckBrowser' { Assert-BrowserServices; $state | ConvertTo-Json -Depth 10 }
    'Cleanup' { Stop-RunServices }
}
