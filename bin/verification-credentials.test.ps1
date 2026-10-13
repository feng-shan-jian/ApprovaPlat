$ErrorActionPreference = 'Stop'
$checks = 0
$names = @('WORKFLOW_VERIFICATION_STATE_PATH','WORKFLOW_DEVELOPMENT_TEST_OPT_IN','WORKFLOW_MYSQL_TEST_URL',
    'WORKFLOW_MYSQL_TEST_USERNAME','WORKFLOW_MYSQL_TEST_PASSWORD','WORKFLOW_E2E_USERNAME','WORKFLOW_E2E_PASSWORD',
    'RUOYI_DB_URL','RUOYI_DB_USERNAME','RUOYI_DB_PASSWORD')
$saved = @{}
foreach ($name in $names) { $saved[$name] = [Environment]::GetEnvironmentVariable($name, 'Process') }
$root = Join-Path ([IO.Path]::GetTempPath()) ('approvaplat-credential-test-' + [Guid]::NewGuid().ToString('N'))
function Check([bool]$Condition, [string]$Message) {
    if (-not $Condition) { throw $Message }
    $script:checks++
}
function Expect-Rejection([scriptblock]$Operation) {
    $failure = $null
    try { & $Operation | Out-Null } catch { $failure = $_ }
    Check ($null -ne $failure) 'Expected credential loading to fail closed'
    Check (-not (($failure | Out-String).Contains('synthetic-password'))) 'A credential escaped into the error'
}
try {
    $bin = New-Item -ItemType Directory -Path (Join-Path $root 'bin') -Force
    Copy-Item -LiteralPath (Join-Path $PSScriptRoot 'verification-common.ps1'), (Join-Path $PSScriptRoot 'workflow-test-environment.ps1') -Destination $bin
    # Replace only the native-client boundary in this disposable copy. No database connection is made.
    @'
function Invoke-VerificationMySql($Server, $Username, $Password, $Sql, $Database) {
    $global:ApprovaPlatCredentialProbe.calls++
    if ($Sql -cne 'SELECT DATABASE(), @@server_uuid' -or $Database -cne 'approvaplat' -or
        $Server.Host -cne '127.0.0.1' -or $Server.Port -ne 13306) { throw 'Unexpected credential validation operation' }
    if ($global:ApprovaPlatCredentialProbe.fail) { throw 'synthetic-password driver error must not escape' }
    if ($Username -cne 'synthetic-app' -or $Password -cne 'synthetic-password') { throw 'Unexpected test credentials' }
    "approvaplat`t$($global:ApprovaPlatCredentialProbe.uuid)"
}
'@ | Add-Content -LiteralPath (Join-Path $bin 'verification-common.ps1') -Encoding utf8
    $tool = Join-Path $bin 'workflow-test-environment.ps1'
    $global:ApprovaPlatCredentialProbe = @{ calls = 0; uuid = '00000000-1111-2222-3333-444444444444'; fail = $false }
    & $tool -Action Register -ServerUuid $global:ApprovaPlatCredentialProbe.uuid | Out-Null
    $statePath = (Get-ChildItem -Path "$root/output/verification-environments/*/state.json").FullName
    $stateBefore = Get-Content -LiteralPath $statePath -Raw
    $state = $stateBefore | ConvertFrom-Json
    $env:RUOYI_DB_URL = $state.databaseUrl
    $env:RUOYI_DB_USERNAME = 'synthetic-app'
    $env:RUOYI_DB_PASSWORD = 'synthetic-password'
    $env:WORKFLOW_MYSQL_TEST_USERNAME = 'old-synthetic-user'
    $env:WORKFLOW_MYSQL_TEST_PASSWORD = 'old-synthetic-password'
    $credential = [PSCredential]::new('synthetic-app', (ConvertTo-SecureString 'synthetic-password' -AsPlainText -Force))
    $browser = [PSCredential]::new('synthetic-browser', (ConvertTo-SecureString 'synthetic-browser-password' -AsPlainText -Force))

    # Positive first: the unmodified loader must fail RED because it has no supported credential input.
    $message = & $tool -Action Load -StateFile $statePath -EnableDevelopmentTests -UseApplicationEnvironment
    Check ($env:WORKFLOW_MYSQL_TEST_USERNAME -ceq 'synthetic-app' -and $env:WORKFLOW_MYSQL_TEST_PASSWORD -ceq 'synthetic-password') 'Application environment was not loaded'
    Check ($global:ApprovaPlatCredentialProbe.calls -eq 1) 'Credentials must be validated by one read-only identity query'
    Check (-not (($message | Out-String).Contains('synthetic-password'))) 'A credential escaped into normal output'
    Check ($env:WORKFLOW_DEVELOPMENT_TEST_OPT_IN -ceq "approvaplat:$($state.runId)") 'Current-run opt-in was not preserved'

    $calls = $global:ApprovaPlatCredentialProbe.calls
    Expect-Rejection { & $tool -Action Load -StateFile $statePath -UseApplicationEnvironment }
    Expect-Rejection { & $tool -Action Validate -StateFile $statePath -UseApplicationEnvironment }
    Expect-Rejection { & $tool -Action Load -StateFile $statePath -EnableDevelopmentTests -UseApplicationEnvironment -DatabaseCredential $credential }
    $env:RUOYI_DB_URL = 'jdbc:mysql://127.0.0.1:3306/approvaplat'
    Expect-Rejection { & $tool -Action Load -StateFile $statePath -EnableDevelopmentTests -UseApplicationEnvironment }
    $env:RUOYI_DB_URL = $state.databaseUrl
    Remove-Item Env:RUOYI_DB_PASSWORD
    Expect-Rejection { & $tool -Action Load -StateFile $statePath -EnableDevelopmentTests -UseApplicationEnvironment }
    $env:RUOYI_DB_PASSWORD = 'synthetic-password'
    $rootCredential = [PSCredential]::new('root', (ConvertTo-SecureString 'synthetic-password' -AsPlainText -Force))
    Expect-Rejection { & $tool -Action Load -StateFile $statePath -EnableDevelopmentTests -DatabaseCredential $rootCredential }
    Check ($global:ApprovaPlatCredentialProbe.calls -eq $calls) 'Invalid inputs must fail before a connection attempt'

    $env:WORKFLOW_MYSQL_TEST_URL = 'jdbc:mysql://127.0.0.1:3306/old-target'
    $env:WORKFLOW_VERIFICATION_STATE_PATH = 'prior-synthetic-state'
    $env:WORKFLOW_DEVELOPMENT_TEST_OPT_IN = 'prior-synthetic-opt-in'
    $original = @{}
    foreach ($name in $names) { $original[$name] = [Environment]::GetEnvironmentVariable($name, 'Process') }
    $global:ApprovaPlatCredentialProbe.uuid = 'aaaaaaaa-1111-2222-3333-444444444444'
    Expect-Rejection { & $tool -Action Load -StateFile $statePath -EnableDevelopmentTests -DatabaseCredential $credential -BrowserCredential $browser }
    foreach ($name in $names) { Check ([Environment]::GetEnvironmentVariable($name, 'Process') -ceq $original[$name]) "Failed load altered $name" }
    $global:ApprovaPlatCredentialProbe.uuid = $state.serverUuid
    $global:ApprovaPlatCredentialProbe.fail = $true
    Expect-Rejection { & $tool -Action Load -StateFile $statePath -EnableDevelopmentTests -DatabaseCredential $credential }
    $global:ApprovaPlatCredentialProbe.fail = $false

    Remove-Item Env:RUOYI_DB_USERNAME, Env:RUOYI_DB_PASSWORD
    & $tool -Action Load -StateFile $statePath -EnableDevelopmentTests -DatabaseCredential $credential -BrowserCredential $browser | Out-Null
    Check ($env:WORKFLOW_E2E_USERNAME -ceq 'synthetic-browser' -and $env:WORKFLOW_E2E_PASSWORD -ceq 'synthetic-browser-password') 'Browser credential was not loaded into the caller process'
    Check ((Get-Content -LiteralPath $statePath -Raw) -ceq $stateBefore) 'Credentials or load state were persisted to the registration'
    & (Get-Process -Id $PID).Path -NoProfile -Command 'if ($env:WORKFLOW_MYSQL_TEST_USERNAME -cne "synthetic-app" -or $env:WORKFLOW_MYSQL_TEST_PASSWORD -cne "synthetic-password") { exit 1 }'
    Check ($LASTEXITCODE -eq 0) 'A normal child process did not inherit the loaded authentication'

    $calls = $global:ApprovaPlatCredentialProbe.calls
    & $tool -Action Load -StateFile $statePath | Out-Null
    Check ($global:ApprovaPlatCredentialProbe.calls -eq $calls) 'Legacy target-only Load must not connect automatically'
    Check ([string]::IsNullOrEmpty($env:WORKFLOW_DEVELOPMENT_TEST_OPT_IN)) 'Legacy Load must still clear test opt-in'
    & $tool -Action Cleanup -StateFile $statePath | Out-Null
    Expect-Rejection { & $tool -Action Load -StateFile $statePath -EnableDevelopmentTests -DatabaseCredential $credential }
    Write-Output "PASS: $checks credential-loading checks; synthetic credentials only, zero database connections"
} finally {
    foreach ($name in $names) {
        if ($null -eq $saved[$name]) { Remove-Item -LiteralPath "Env:$name" -ErrorAction SilentlyContinue }
        else { [Environment]::SetEnvironmentVariable($name, $saved[$name], 'Process') }
    }
    Remove-Variable ApprovaPlatCredentialProbe -Scope Global -ErrorAction SilentlyContinue
    $resolved = [IO.Path]::GetFullPath($root)
    $parent = [IO.Path]::GetFullPath([IO.Path]::GetTempPath()).TrimEnd('\','/')
    if ((Split-Path $resolved) -eq $parent -and (Split-Path $resolved -Leaf) -match '^approvaplat-credential-test-[a-f0-9]{32}$') {
        Remove-Item -LiteralPath $resolved -Recurse -Force -ErrorAction SilentlyContinue
    }
}
