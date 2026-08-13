[CmdletBinding()]
param(
    [ValidateSet('smoke', 'full', 'fault', 'all')]
    [string]$Phase = 'all',
    [string]$RunId = '',
    [string]$BaseUrl = 'http://127.0.0.1:1024',
    [string]$BackendUrl = 'http://127.0.0.1:8080',
    [string]$Database = 'ry-vue',
    [string]$AdminUsername = 'admin',
    [string]$Grep = '',
    [switch]$RestoreOnly,
    [switch]$SkipBuild,
    [switch]$SkipProvision,
    [switch]$RestartServices
)

$ErrorActionPreference = 'Stop'

if ($PSVersionTable.PSVersion.Major -lt 7)
{
    throw '审批引擎 UI 测试总控脚本要求 PowerShell 7 或更高版本。'
}

$repositoryRoot = (Resolve-Path (Join-Path $PSScriptRoot '..\..')).Path
$uiRoot = Join-Path $repositoryRoot 'ruoyi-ui'
$backendJar = Join-Path $repositoryRoot 'ruoyi-admin\target\ruoyi-admin.jar'
$identityCatalog = Join-Path $repositoryRoot 'deployment\samples\workflow\ui-test-identities.json'
$identityScript = Join-Path $repositoryRoot 'deployment\scripts\provision-workflow-samples.mjs'
$normalizedRunId = if ([string]::IsNullOrWhiteSpace($RunId)) { Get-Date -Format 'yyyyMMddHHmmss' } else { $RunId.Trim() }
if ($normalizedRunId -notmatch '^[A-Za-z0-9_.-]{1,64}$')
{
    throw 'RunId 只能包含字母、数字、下划线、点和连字符，长度不得超过 64。'
}
# 日期目录使用本机 Asia/Shanghai 工作日口径，runId 保留为单次运行的唯一标识。
$runDate = Get-Date -Format 'yyyy-MM-dd'
$outputRoot = Join-Path $uiRoot "tests\ui\output\$runDate\$normalizedRunId"
$databaseOutput = Join-Path $outputRoot 'database'
$orchestrationPath = Join-Path $outputRoot 'orchestration.json'
$faultProfileRoot = Join-Path $outputRoot 'runtime\profile'
$faultProxyEnabled = $Phase -in @('fault', 'all')
$phaseResults = [ordered]@{}
$helperProcess = $null
$backendRestarted = $false
$buildAttempted = $false
$buildSucceeded = $false
$preBuildJarBackup = Join-Path $outputRoot 'runtime\pre-build-ruoyi-admin.jar'

<#
 * 从受控环境变量或仓库忽略文件读取 MySQL 管理密码。
 * @returns {string} 仅保存在当前 PowerShell 进程中的 MySQL 密码。
#>
function Get-MySqlPassword
{
    $password = $env:RUOYI_E2E_MYSQL_ROOT_PASSWORD
    if (-not [string]::IsNullOrWhiteSpace($password)) { return $password.Trim() }
    $credentialPath = Join-Path $repositoryRoot 'mysql-root.txt'
    if (-not (Test-Path -LiteralPath $credentialPath))
    {
        throw '缺少 RUOYI_E2E_MYSQL_ROOT_PASSWORD 或 mysql-root.txt，不能执行测试前数据库备份。'
    }
    $match = [regex]::Match(
        [IO.File]::ReadAllText($credentialPath, [Text.UTF8Encoding]::new($false)),
        '(?m)^Password:\s*(?<value>.+?)\s*$')
    if (-not $match.Success) { throw 'mysql-root.txt 未包含有效 Password 配置。' }
    return $match.Groups['value'].Value.Trim()
}

<#
 * 等待指定 HTTP 地址返回成功状态。
 * @param {string} Uri 健康检查地址。
 * @param {int} TimeoutSeconds 最大等待秒数。
 * @returns {void} 超时未就绪时抛出终止错误。
#>
function Wait-HttpReady
{
    param(
        [Parameter(Mandatory = $true)][string]$Uri,
        [int]$TimeoutSeconds = 120
    )
    $deadline = [DateTime]::UtcNow.AddSeconds($TimeoutSeconds)
    do
    {
        try
        {
            $response = Invoke-WebRequest -UseBasicParsing -Uri $Uri -TimeoutSec 3
            if ($response.StatusCode -ge 200 -and $response.StatusCode -lt 500) { return }
        }
        catch
        {
            Start-Sleep -Milliseconds 500
        }
    } while ([DateTime]::UtcNow -lt $deadline)
    throw "服务在 $TimeoutSeconds 秒内未就绪：$Uri"
}

<#
 * 停止由当前仓库启动且监听指定端口的进程。
 * @param {int} Port 本机监听端口。
 * @param {string} ExpectedCommandFragment 命令行必须包含的仓库片段。
 * @returns {boolean} 找到并停止进程时返回 true。
#>
function Stop-RepositoryListener
{
    param(
        [Parameter(Mandatory = $true)][int]$Port,
        [Parameter(Mandatory = $true)][string]$ExpectedCommandFragment
    )
    $listener = Get-NetTCPConnection -State Listen -LocalPort $Port -ErrorAction SilentlyContinue | Select-Object -First 1
    if (-not $listener) { return $false }
    $process = Get-CimInstance Win32_Process -Filter "ProcessId=$($listener.OwningProcess)" -ErrorAction Stop
    if ($process.CommandLine -notlike "*$ExpectedCommandFragment*")
    {
        throw "端口 $Port 被非当前仓库进程占用，拒绝停止：$($process.Name)"
    }
    Stop-Process -Id $listener.OwningProcess -Force
    $deadline = [DateTime]::UtcNow.AddSeconds(20)
    do
    {
        if (-not (Get-NetTCPConnection -State Listen -LocalPort $Port -ErrorAction SilentlyContinue)) { return $true }
        Start-Sleep -Milliseconds 300
    } while ([DateTime]::UtcNow -lt $deadline)
    throw "端口 $Port 的仓库进程未能停止。"
}

<#
 * 移除当前 Windows 身份在本轮隔离附件目录上的显式拒绝写入 ACL，并执行无残留写入验证。
 * @param {string} ProfileRoot 当前 runId 的测试 profile 根目录。
 * @param {string} ExpectedOutputRoot 当前 runId 的证据输出根目录。
 * @returns {void} ACL 已恢复且目录重新可写后结束；越界、链接或恢复失败时抛出错误。
#>
function Restore-FaultAttachmentAcl
{
    param(
        [Parameter(Mandatory = $true)][string]$ProfileRoot,
        [Parameter(Mandatory = $true)][string]$ExpectedOutputRoot
    )
    $resolvedProfileRoot = [IO.Path]::GetFullPath($ProfileRoot)
    $resolvedOutputRoot = [IO.Path]::GetFullPath($ExpectedOutputRoot)
    $expectedProfileRoot = [IO.Path]::GetFullPath((Join-Path $resolvedOutputRoot 'runtime\profile'))
    if (-not [string]::Equals($resolvedProfileRoot, $expectedProfileRoot, [StringComparison]::OrdinalIgnoreCase))
    {
        throw '附件 ACL 恢复目标不属于当前 runId 隔离 profile。'
    }
    $attachmentRoot = Join-Path $resolvedProfileRoot 'workflow-attachments'
    if (-not (Test-Path -LiteralPath $attachmentRoot)) { return }
    $attachmentItem = Get-Item -LiteralPath $attachmentRoot -Force
    if (-not $attachmentItem.PSIsContainer -or ($attachmentItem.Attributes -band [IO.FileAttributes]::ReparsePoint))
    {
        throw '附件 ACL 恢复目标不是普通目录。'
    }

    # 该目录由本轮总控新建且不承载开发数据，只移除当前身份的显式 deny ACE。
    $currentSid = [Security.Principal.WindowsIdentity]::GetCurrent().User.Value
    & icacls.exe $attachmentRoot '/remove:d' ("*$currentSid") '/T' '/C' | Out-Null
    if ($LASTEXITCODE -ne 0) { throw '附件 ACL 显式拒绝项移除失败。' }

    $probePath = Join-Path $attachmentRoot ('.e2e-acl-restore-{0}.tmp' -f [guid]::NewGuid().ToString('N'))
    try
    {
        [IO.File]::WriteAllBytes($probePath, [Text.Encoding]::ASCII.GetBytes('acl-restore-probe'))
    }
    finally
    {
        if (Test-Path -LiteralPath $probePath) { Remove-Item -LiteralPath $probePath -Force }
    }
}

<#
 * 使用当前源码构建的 jar 启动独占后端，并按测试阶段切换真实异步执行器和测试环境。
 * @param {boolean} AsyncExecutor 是否启用 Flowable 异步执行器。
 * @param {string} MySqlPassword 当前本机 MySQL 管理密码。
 * @param {boolean} ConfigureTestEnvironment 是否注入 UI 测试数据库、监控和 SMTP 参数。
 * @param {boolean} UseFaultProxies 是否把 MySQL 和 Redis 接入本机透明故障代理。
 * @param {string} ProfileRoot 测试附件隔离目录；空字符串表示沿用开发配置。
 * @returns {void} 后端健康检查成功后结束。
#>
function Start-TestBackend
{
    param(
        [Parameter(Mandatory = $true)][boolean]$AsyncExecutor,
        [Parameter(Mandatory = $true)][string]$MySqlPassword,
        [boolean]$ConfigureTestEnvironment = $true,
        [boolean]$UseFaultProxies = $false,
        [string]$ProfileRoot = ''
    )
    if (-not (Test-Path -LiteralPath $backendJar)) { throw "后端构建产物不存在：$backendJar" }
    [void](Stop-RepositoryListener -Port 8080 -ExpectedCommandFragment 'ruoyi-admin')
    # 测试后端和恢复后的开发后端都必须显式连接本次已备份的开发库，避免父进程没有数据库环境变量时恢复为空配置。
    $databasePort = if ($UseFaultProxies) { 13306 } else { 3306 }
    $faultDatabaseTimeouts = if ($UseFaultProxies) { '&connectTimeout=3000&socketTimeout=3000' } else { '' }
    $env:RUOYI_DB_URL = "jdbc:mysql://127.0.0.1:${databasePort}/${Database}?useUnicode=true&characterEncoding=utf8&zeroDateTimeBehavior=convertToNull&useSSL=false&serverTimezone=Asia/Shanghai&allowPublicKeyRetrieval=true${faultDatabaseTimeouts}"
    $env:RUOYI_DB_USERNAME = 'root'
    $env:RUOYI_DB_PASSWORD = $MySqlPassword
    # 当前 Spring 版本要求 @Scheduled 字符串显式携带时间单位；测试和开发恢复都必须覆盖旧的裸数字默认值。
    $env:APPROVAPLAT_NOTIFICATION_WORKER_DELAY = if ($UseFaultProxies) { '200ms' } else { '1000ms' }
    if ($ConfigureTestEnvironment)
    {
        # 测试后端显式连接已备份的开发库，并使用本地可控 SMTP 服务验证真实通知链路。
        $env:DRUID_MONITOR_USERNAME = 'ui-test-monitor'
        $env:DRUID_MONITOR_PASSWORD = [guid]::NewGuid().ToString('N')
        $env:APPROVAPLAT_SMTP_HOST = '127.0.0.1'
        $env:APPROVAPLAT_SMTP_PORT = '2525'
        $env:APPROVAPLAT_SMTP_AUTH = 'false'
        $env:APPROVAPLAT_SMTP_STARTTLS = 'false'
        $env:APPROVAPLAT_SMTP_CONNECT_TIMEOUT = if ($UseFaultProxies) { '1500' } else { '5000' }
        $env:APPROVAPLAT_SMTP_READ_TIMEOUT = if ($UseFaultProxies) { '5000' } else { '10000' }
        $env:APPROVAPLAT_SMTP_WRITE_TIMEOUT = if ($UseFaultProxies) { '1500' } else { '10000' }
        $env:APPROVAPLAT_NOTIFICATION_LEASE_DURATION = if ($UseFaultProxies) { '10s' } else { '2m' }
        $env:APPROVAPLAT_NOTIFICATION_MAX_RETRY_DELAY = if ($UseFaultProxies) { '1s' } else { '30m' }
        $env:APPROVAPLAT_NOTIFICATION_MAIL_FROM = 'workflow-ui-test@localhost'
    }
    $arguments = @(
        '-jar', $backendJar,
        '--server.address=127.0.0.1',
        '--server.port=8080',
        '--spring.devtools.restart.enabled=false',
        "--flowable.async-executor-activate=$($AsyncExecutor.ToString().ToLowerInvariant())"
    )
    if ($UseFaultProxies)
    {
        # 数据库和 Redis 通过协议透明代理连接；代理只切断 socket，不读取或记录任何协议正文。
        $arguments += @(
            '--spring.data.redis.host=127.0.0.1',
            '--spring.data.redis.port=16379',
            '--spring.data.redis.timeout=3s',
            '--spring.datasource.druid.maxWait=3000',
            '--spring.datasource.druid.connectTimeout=3000',
            '--spring.datasource.druid.socketTimeout=3000'
        )
    }
    if (-not [string]::IsNullOrWhiteSpace($ProfileRoot))
    {
        New-Item -ItemType Directory -Path $ProfileRoot -Force | Out-Null
        $arguments += "--ruoyi.profile=$ProfileRoot"
    }
    if ($ConfigureTestEnvironment)
    {
        # UI 回归使用短周期真实调度器完成物理清理验收；恢复开发服务时不继承这些测试参数。
        $arguments += @(
            '--flowable.attachment.cleanup-initial-delay=PT1S',
            '--flowable.attachment.cleanup-fixed-delay=PT2S'
        )
    }
    # 测试后端日志必须与 finally 恢复开发后端的日志分离，启动失败时保留原始诊断证据。
    $backendLogPrefix = if ($ConfigureTestEnvironment) { 'backend' } else { 'backend.restore' }
    $stdoutPath = Join-Path $outputRoot "$backendLogPrefix.out.log"
    $stderrPath = Join-Path $outputRoot "$backendLogPrefix.err.log"
    Start-Process -FilePath 'java.exe' -ArgumentList $arguments -WorkingDirectory $repositoryRoot -WindowStyle Hidden -RedirectStandardOutput $stdoutPath -RedirectStandardError $stderrPath
    Wait-HttpReady -Uri "$BackendUrl/captchaImage" -TimeoutSeconds 240
}

<#
 * 从当前正式开发库创建单事务完整备份，备份失败时禁止任何写场景继续。
 * @param {string} MySqlPassword 当前本机 MySQL 管理密码。
 * @returns {string} 备份文件绝对路径。
#>
function Backup-Database
{
    param([Parameter(Mandatory = $true)][string]$MySqlPassword)
    New-Item -ItemType Directory -Path $databaseOutput -Force | Out-Null
    $backupPath = Join-Path $databaseOutput "$Database-before.sql"
    $previousPassword = $env:MYSQL_PWD
    try
    {
        $env:MYSQL_PWD = $MySqlPassword
        & mysqldump.exe '--host=127.0.0.1' '--user=root' '--default-character-set=utf8mb4' '--single-transaction' '--routines' '--events' '--triggers' '--set-gtid-purged=OFF' '--skip-comments' "--result-file=$backupPath" $Database
        if ($LASTEXITCODE -ne 0 -or -not (Test-Path -LiteralPath $backupPath) -or (Get-Item -LiteralPath $backupPath).Length -eq 0)
        {
            throw '正式开发数据库备份失败，已阻止 UI 写测试。'
        }
    }
    finally
    {
        $env:MYSQL_PWD = $previousPassword
    }
    return $backupPath
}

<#
 * 执行一个 UI 测试阶段并记录退出码，不因首个业务缺陷跳过后续独立阶段。
 * @param {string} Name 阶段名称。
 * @param {string} Script package.json 脚本名称。
 * @returns {void} 退出码写入阶段结果字典；Grep 非空时只执行匹配的可恢复用例。
#>
function Invoke-TestPhase
{
    param(
        [Parameter(Mandatory = $true)][string]$Name,
        [Parameter(Mandatory = $true)][string]$Script
    )
    Push-Location $uiRoot
    try
    {
        if ([string]::IsNullOrWhiteSpace($Grep))
        {
            & npm.cmd run $Script
        }
        else
        {
            $playwrightCli = Join-Path $uiRoot 'node_modules\@playwright\test\cli.js'
            if (-not (Test-Path -LiteralPath $playwrightCli))
            {
                throw '缺少仓库本地 @playwright/test CLI，不能执行带筛选条件的 UI 测试。'
            }
            # 复杂正则必须作为 node.exe 的独立参数传递，避免 npm.cmd 经 cmd.exe 二次解释管道等元字符。
            $arguments = @($playwrightCli, 'test', '-c', 'playwright.ui.config.js')
            if ($Script -eq 'test:ui:smoke') { $arguments += @('--grep', '@smoke') }
            if ($Script -eq 'test:ui:full') { $arguments += @('--grep-invert', '@fault') }
            if ($Script -eq 'test:ui:fault') { $arguments += @('--grep', '@fault') }
            $arguments += @('--grep', $Grep.Trim())
            & node.exe @arguments
        }
        $phaseResults[$Name] = $LASTEXITCODE
    }
    finally
    {
        Pop-Location
    }
}

$mysqlPassword = Get-MySqlPassword
$previousEnvironment = @{}
$managedEnvironmentNames = @(
    'FLOWABLE_E2E_RUN_ID', 'FLOWABLE_E2E_BASE_URL', 'FLOWABLE_E2E_START_FRONTEND',
    'FLOWABLE_E2E_DB_NAME', 'FLOWABLE_E2E_OUTPUT_ROOT', 'FLOWABLE_E2E_PROFILE_ROOT',
    'FLOWABLE_E2E_FAULT_CONTROL_URL', 'FLOWABLE_E2E_FAULT_PROXY_ENABLED',
    'FLOWABLE_E2E_BACKEND_URL', 'FLOWABLE_RBAC_ACCOUNTS_REGISTERED',
    'FLOWABLE_E2E_ADMIN_USERNAME', 'FLOWABLE_E2E_ADMIN_PASSWORD',
    'FLOWABLE_RBAC_WORKFLOW_ADMIN_USERNAME', 'FLOWABLE_RBAC_WORKFLOW_DESIGNER_USERNAME',
    'FLOWABLE_RBAC_WORKFLOW_STARTER_USERNAME', 'FLOWABLE_RBAC_WORKFLOW_APPROVER_USERNAME',
    'FLOWABLE_RBAC_WORKFLOW_AUDITOR_USERNAME', 'APPROVA_SAMPLE_ADMIN_PASSWORD',
    'APPROVA_SAMPLE_IDENTITY_PASSWORD', 'RUOYI_DB_URL', 'RUOYI_DB_USERNAME', 'RUOYI_DB_PASSWORD',
    'DRUID_MONITOR_USERNAME', 'DRUID_MONITOR_PASSWORD', 'APPROVAPLAT_SMTP_HOST',
    'APPROVAPLAT_SMTP_PORT', 'APPROVAPLAT_SMTP_AUTH', 'APPROVAPLAT_SMTP_STARTTLS',
    'APPROVAPLAT_SMTP_CONNECT_TIMEOUT', 'APPROVAPLAT_SMTP_READ_TIMEOUT',
    'APPROVAPLAT_SMTP_WRITE_TIMEOUT', 'APPROVAPLAT_NOTIFICATION_LEASE_DURATION',
    'APPROVAPLAT_NOTIFICATION_MAX_RETRY_DELAY',
    'APPROVAPLAT_NOTIFICATION_WORKER_DELAY', 'APPROVAPLAT_NOTIFICATION_MAIL_FROM'
)
foreach ($name in $managedEnvironmentNames) { $previousEnvironment[$name] = [Environment]::GetEnvironmentVariable($name, 'Process') }

if ($RestoreOnly)
{
    try
    {
        New-Item -ItemType Directory -Path $outputRoot -Force | Out-Null
        Start-TestBackend -AsyncExecutor:$false -MySqlPassword $mysqlPassword -ConfigureTestEnvironment:$false
    }
    finally
    {
        foreach ($name in $managedEnvironmentNames)
        {
            [Environment]::SetEnvironmentVariable($name, $previousEnvironment[$name], 'Process')
        }
    }
    return
}

try
{
    New-Item -ItemType Directory -Path $outputRoot -Force | Out-Null
    $backupPath = Backup-Database -MySqlPassword $mysqlPassword
    $phaseResults['backup'] = 0

    $env:FLOWABLE_E2E_RUN_ID = $normalizedRunId
    $env:FLOWABLE_E2E_BASE_URL = $BaseUrl
    $env:FLOWABLE_E2E_START_FRONTEND = 'false'
    $env:FLOWABLE_E2E_DB_NAME = $Database
    $env:FLOWABLE_E2E_OUTPUT_ROOT = $outputRoot
    $env:FLOWABLE_E2E_PROFILE_ROOT = if ($faultProxyEnabled) { $faultProfileRoot } else { 'D:\approvaplat\uploadPath' }
    $env:FLOWABLE_E2E_FAULT_CONTROL_URL = 'http://127.0.0.1:18081'
    $env:FLOWABLE_E2E_FAULT_PROXY_ENABLED = $faultProxyEnabled.ToString().ToLowerInvariant()
    $env:FLOWABLE_E2E_BACKEND_URL = $BackendUrl
    $env:FLOWABLE_E2E_ADMIN_USERNAME = $AdminUsername
    $env:FLOWABLE_RBAC_ACCOUNTS_REGISTERED = 'true'
    $env:FLOWABLE_RBAC_WORKFLOW_ADMIN_USERNAME = 'e2e_ui_wf_admin'
    $env:FLOWABLE_RBAC_WORKFLOW_DESIGNER_USERNAME = 'e2e_ui_wf_designer'
    $env:FLOWABLE_RBAC_WORKFLOW_STARTER_USERNAME = 'e2e_ui_wf_starter'
    $env:FLOWABLE_RBAC_WORKFLOW_APPROVER_USERNAME = 'e2e_ui_wf_approver'
    $env:FLOWABLE_RBAC_WORKFLOW_AUDITOR_USERNAME = 'e2e_ui_wf_auditor'

    # 系统管理 UI 场景与身份置备共用同一管理员凭据，只在当前总控进程及其 Playwright 子进程中传递。
    $adminPassword = $env:FLOWABLE_E2E_ADMIN_PASSWORD

    if (-not $SkipBuild)
    {
        $buildAttempted = $true
        # Windows 会锁定正在运行的可执行 JAR；先保留可恢复副本并停掉当前仓库后端，再执行 repackage。
        if (Test-Path -LiteralPath $backendJar)
        {
            New-Item -ItemType Directory -Path (Split-Path -Parent $preBuildJarBackup) -Force | Out-Null
            Copy-Item -LiteralPath $backendJar -Destination $preBuildJarBackup -Force
        }
        # 标记必须早于停止动作，后续构建失败时 finally 才能恢复旧包和开发服务。
        $backendRestarted = $true
        [void](Stop-RepositoryListener -Port 8080 -ExpectedCommandFragment 'ruoyi-admin')
        Push-Location $repositoryRoot
        try
        {
            & mvn.cmd -pl ruoyi-admin -am '-DskipTests' package
            if ($LASTEXITCODE -ne 0) { throw '后端当前源码构建失败。' }
        }
        finally { Pop-Location }
        Push-Location $uiRoot
        try
        {
            & npm.cmd run build:prod
            if ($LASTEXITCODE -ne 0) { throw '前端当前源码构建失败。' }
        }
        finally { Pop-Location }
        $buildSucceeded = $true
        if (Test-Path -LiteralPath $preBuildJarBackup)
        {
            Remove-Item -LiteralPath $preBuildJarBackup -Force
        }
        $phaseResults['build'] = 0
    }

    if ($Phase -in @('full', 'fault', 'all'))
    {
        $serviceScript = Join-Path $uiRoot 'tests\ui\services\test-services.mjs'
        $helperStdout = Join-Path $outputRoot 'test-services.out.log'
        $helperStderr = Join-Path $outputRoot 'test-services.err.log'
        $helperProcess = Start-Process -FilePath 'node.exe' -ArgumentList @($serviceScript) -WorkingDirectory $uiRoot -WindowStyle Hidden -PassThru -RedirectStandardOutput $helperStdout -RedirectStandardError $helperStderr
        Wait-HttpReady -Uri 'http://127.0.0.1:18081/health' -TimeoutSeconds 30
    }

    if (-not $SkipBuild -or $RestartServices -or $faultProxyEnabled)
    {
        # 标记必须在停止原服务前写入；即使测试后端启动失败，finally 仍会恢复开发后端。
        $backendRestarted = $true
        Start-TestBackend `
            -AsyncExecutor:($Phase -in @('full', 'fault', 'all')) `
            -MySqlPassword $mysqlPassword `
            -UseFaultProxies:$faultProxyEnabled `
            -ProfileRoot $(if ($faultProxyEnabled) { $faultProfileRoot } else { '' })
    }
    if ($RestartServices)
    {
        [void](Stop-RepositoryListener -Port 1024 -ExpectedCommandFragment 'vite')
        $frontendStdout = Join-Path $outputRoot 'frontend.out.log'
        $frontendStderr = Join-Path $outputRoot 'frontend.err.log'
        Start-Process -FilePath 'npm.cmd' -ArgumentList @('run', 'dev', '--', '--host', '127.0.0.1', '--port', '1024', '--strictPort') -WorkingDirectory $uiRoot -WindowStyle Hidden -RedirectStandardOutput $frontendStdout -RedirectStandardError $frontendStderr
    }

    # 无论是否由总控重启前端，都必须在置备数据和执行浏览器用例前确认真实登录页可访问，避免连接拒绝被批量记成业务失败。
    $frontendReadyTimeoutSeconds = if ($RestartServices) { 60 } else { 15 }
    Wait-HttpReady -Uri "$BaseUrl/login" -TimeoutSeconds $frontendReadyTimeoutSeconds

    if (-not $SkipProvision)
    {
        if ([string]::IsNullOrWhiteSpace($adminPassword))
        {
            throw '缺少 FLOWABLE_E2E_ADMIN_PASSWORD，无法通过正式系统 API 置备五角色账号。'
        }
        $env:APPROVA_SAMPLE_ADMIN_PASSWORD = $adminPassword
        $env:APPROVA_SAMPLE_IDENTITY_PASSWORD = 'wang'
        & node.exe $identityScript '--base-url' $BackendUrl '--username' $AdminUsername '--catalog' $identityCatalog '--identities-only'
        if ($LASTEXITCODE -ne 0) { throw '五角色 identities-only 置备失败。' }
        $phaseResults['identityProvision'] = 0
    }

    if ($Phase -in @('smoke', 'all')) { Invoke-TestPhase -Name 'smoke' -Script 'test:ui:smoke' }
    if ($Phase -in @('full', 'all')) { Invoke-TestPhase -Name 'full' -Script 'test:ui:full' }
    if ($Phase -in @('fault', 'all')) { Invoke-TestPhase -Name 'fault' -Script 'test:ui:fault' }

    [ordered]@{
        runId = $normalizedRunId
        phase = $Phase
        backup = $backupPath
        results = $phaseResults
        finishedAt = [DateTime]::UtcNow.ToString('o')
    } | ConvertTo-Json -Depth 5 | Set-Content -LiteralPath $orchestrationPath -Encoding utf8NoBOM
}
finally
{
    $recoveryErrors = [Collections.Generic.List[string]]::new()
    try
    {
        if ($helperProcess -and -not $helperProcess.HasExited)
        {
            try
            {
                # full 阶段使用开发附件目录且不会注入 ACL 故障，禁止把它交给仅接受 runId 隔离目录的附件控制器。
                $recoveryMode = [ordered]@{
                    mysqlMode = 'ok'
                    redisMode = 'ok'
                    httpMode = 'ok'
                    smtpMode = 'accept'
                }
                if ($faultProxyEnabled) { $recoveryMode['attachmentMode'] = 'writable' }
                Invoke-RestMethod -Method Post -Uri 'http://127.0.0.1:18081/mode' `
                    -ContentType 'application/json' `
                    -Body ($recoveryMode | ConvertTo-Json -Compress) | Out-Null
            }
            catch { $recoveryErrors.Add("故障服务恢复请求失败：$($_.Exception.Message)") }
        }
        if ($faultProxyEnabled)
        {
            try
            {
                # 即使辅助服务被强制终止，也由总控在恢复开发后端前直接移除本轮隔离目录 ACL。
                Restore-FaultAttachmentAcl -ProfileRoot $faultProfileRoot -ExpectedOutputRoot $outputRoot
            }
            catch { $recoveryErrors.Add("附件 ACL 兜底恢复失败：$($_.Exception.Message)") }
        }
        # 先恢复父进程环境，随后启动的开发后端才不会继承临时数据库、监控、代理或 SMTP 参数。
        foreach ($name in $managedEnvironmentNames)
        {
            [Environment]::SetEnvironmentVariable($name, $previousEnvironment[$name], 'Process')
        }
        if ($buildAttempted -and -not $buildSucceeded -and (Test-Path -LiteralPath $preBuildJarBackup))
        {
            try
            {
                # Maven repackage 失败时可能留下薄 JAR；恢复启动前必须还原已验证的旧可执行包。
                Copy-Item -LiteralPath $preBuildJarBackup -Destination $backendJar -Force
            }
            catch { $recoveryErrors.Add("构建失败后的后端 JAR 恢复失败：$($_.Exception.Message)") }
        }
        if ($backendRestarted)
        {
            try { Start-TestBackend -AsyncExecutor:$false -MySqlPassword $mysqlPassword -ConfigureTestEnvironment:$false }
            catch { $recoveryErrors.Add("开发后端恢复失败：$($_.Exception.Message)") }
        }
    }
    finally
    {
        if ($helperProcess -and -not $helperProcess.HasExited) { Stop-Process -Id $helperProcess.Id -Force }
        if (Test-Path -LiteralPath $preBuildJarBackup)
        {
            Remove-Item -LiteralPath $preBuildJarBackup -Force
        }
    }
    if ($recoveryErrors.Count -gt 0)
    {
        throw "故障环境恢复失败：$($recoveryErrors -join ' | ')"
    }
}

if (@($phaseResults.Values | Where-Object { $_ -ne 0 }).Count -gt 0)
{
    $failedPhases = @($phaseResults.GetEnumerator() | Where-Object { $_.Value -ne 0 } | ForEach-Object { $_.Key }) -join ', '
    throw "UI 自动化测试阶段失败：$failedPhases"
}
