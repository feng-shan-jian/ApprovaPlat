[CmdletBinding()]
param()

$ErrorActionPreference = 'Stop'

if ($PSVersionTable.PSVersion.Major -lt 7)
{
    throw 'UI 测试后端重启脚本要求 PowerShell 7 或更高版本。'
}

<#
 * 读取当前进程必需的 UI 故障运行参数。
 * @param {string} Name 环境变量名称。
 * @returns {string} 去除首尾空白后的环境变量值。
#>
function Get-RequiredEnvironmentValue
{
    param([Parameter(Mandatory = $true)][string]$Name)
    $value = [Environment]::GetEnvironmentVariable($Name, 'Process')
    if ([string]::IsNullOrWhiteSpace($value))
    {
        throw "缺少 UI 故障重启参数：$Name"
    }
    return $value.Trim()
}

<#
 * 等待当前测试后端健康检查恢复。
 * @param {string} Uri 本机后端健康检查地址。
 * @param {int} TimeoutSeconds 最大等待秒数。
 * @returns {void} 服务恢复后结束，超时抛出错误。
#>
function Wait-BackendReady
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
            Start-Sleep -Milliseconds 300
        }
    } while ([DateTime]::UtcNow -lt $deadline)
    throw "测试后端在 $TimeoutSeconds 秒内未恢复：$Uri"
}

$repositoryRoot = (Resolve-Path (Join-Path $PSScriptRoot '..\..')).Path
$backendJar = (Resolve-Path (Join-Path $repositoryRoot 'ruoyi-admin\target\ruoyi-admin.jar')).Path
$outputRoot = [IO.Path]::GetFullPath((Get-RequiredEnvironmentValue 'FLOWABLE_E2E_OUTPUT_ROOT'))
$expectedOutputParent = [IO.Path]::GetFullPath((Join-Path $repositoryRoot 'ruoyi-ui\output\playwright\ui'))
$relativeOutput = [IO.Path]::GetRelativePath($expectedOutputParent, $outputRoot)
if ($relativeOutput -eq '..' -or $relativeOutput.StartsWith("..$([IO.Path]::DirectorySeparatorChar)"))
{
    throw 'UI 测试输出目录不属于仓库受控证据根。'
}
$profileRoot = [IO.Path]::GetFullPath((Get-RequiredEnvironmentValue 'FLOWABLE_E2E_PROFILE_ROOT'))
$expectedProfileRoot = [IO.Path]::GetFullPath((Join-Path $outputRoot 'runtime\profile'))
if (-not [string]::Equals($profileRoot, $expectedProfileRoot, [StringComparison]::OrdinalIgnoreCase))
{
    throw 'UI 测试 profile 不属于当前 runId。'
}
$backendUrl = Get-RequiredEnvironmentValue 'FLOWABLE_E2E_BACKEND_URL'
$backendUri = [Uri]$backendUrl
if ($backendUri.Scheme -ne 'http' -or $backendUri.Host -notin @('127.0.0.1', 'localhost') `
    -or $backendUri.Port -ne 8080 -or $backendUri.AbsolutePath -ne '/')
{
    throw 'UI 测试后端地址必须是本机 8080 HTTP 根地址。'
}
if ((Get-RequiredEnvironmentValue 'FLOWABLE_E2E_FAULT_PROXY_ENABLED').ToLowerInvariant() -ne 'true')
{
    throw '仅允许在 fault 总控运行期间重启测试后端。'
}

$listener = Get-NetTCPConnection -State Listen -LocalPort 8080 -ErrorAction SilentlyContinue |
    Select-Object -First 1
if (-not $listener) { throw '8080 当前没有可重启的测试后端。' }
$currentProcess = Get-CimInstance Win32_Process -Filter "ProcessId=$($listener.OwningProcess)" -ErrorAction Stop
if ($currentProcess.Name -ne 'java.exe' -or $currentProcess.CommandLine -notlike "*$backendJar*" `
    -or $currentProcess.CommandLine -notlike '*--server.port=8080*')
{
    throw '8080 不是由当前仓库 jar 启动的测试后端，拒绝停止。'
}

# 强制终止用于模拟投递租约期间的进程崩溃；总控 finally 仍负责最终恢复开发服务。
$oldProcessId = [int]$listener.OwningProcess
Stop-Process -Id $oldProcessId -Force
$stopDeadline = [DateTime]::UtcNow.AddSeconds(20)
do
{
    if (-not (Get-NetTCPConnection -State Listen -LocalPort 8080 -ErrorAction SilentlyContinue)) { break }
    Start-Sleep -Milliseconds 200
} while ([DateTime]::UtcNow -lt $stopDeadline)
if (Get-NetTCPConnection -State Listen -LocalPort 8080 -ErrorAction SilentlyContinue)
{
    throw '旧测试后端未能释放 8080 端口。'
}

$restartDirectory = Join-Path $outputRoot 'backend-restarts'
New-Item -ItemType Directory -Path $restartDirectory -Force | Out-Null
$restartIndex = @(Get-ChildItem -LiteralPath $restartDirectory -Filter 'restart-*.json' -File -ErrorAction SilentlyContinue).Count + 1
$restartName = 'restart-{0:D2}' -f $restartIndex
$stdoutPath = Join-Path $restartDirectory "$restartName.out.log"
$stderrPath = Join-Path $restartDirectory "$restartName.err.log"
$arguments = @(
    '-jar', $backendJar,
    '--server.address=127.0.0.1',
    '--server.port=8080',
    '--spring.devtools.restart.enabled=false',
    '--flowable.async-executor-activate=true',
    '--spring.data.redis.host=127.0.0.1',
    '--spring.data.redis.port=16379',
    '--spring.data.redis.timeout=3s',
    '--spring.datasource.druid.maxWait=3000',
    '--spring.datasource.druid.connectTimeout=3000',
    '--spring.datasource.druid.socketTimeout=3000',
    '--flowable.attachment.cleanup-initial-delay=PT1S',
    '--flowable.attachment.cleanup-fixed-delay=PT2S',
    "--ruoyi.profile=$profileRoot"
)
$newProcess = Start-Process -FilePath 'java.exe' -ArgumentList $arguments -WorkingDirectory $repositoryRoot `
    -WindowStyle Hidden -PassThru -RedirectStandardOutput $stdoutPath -RedirectStandardError $stderrPath
Wait-BackendReady -Uri "$backendUrl/captchaImage" -TimeoutSeconds 120

$restartEvidence = [ordered]@{
    oldProcessId = $oldProcessId
    newProcessId = $newProcess.Id
    restartedAt = [DateTime]::UtcNow.ToString('o')
    stdout = [IO.Path]::GetRelativePath($outputRoot, $stdoutPath)
    stderr = [IO.Path]::GetRelativePath($outputRoot, $stderrPath)
}
$restartJson = $restartEvidence | ConvertTo-Json -Depth 3 -Compress
$restartJson | Set-Content -LiteralPath (Join-Path $restartDirectory "$restartName.json") -Encoding utf8NoBOM
Write-Output $restartJson
# 新后端必须继续独立运行；控制脚本完成证据输出后立即退出，避免 Node 等待长生命周期子进程。
exit 0
