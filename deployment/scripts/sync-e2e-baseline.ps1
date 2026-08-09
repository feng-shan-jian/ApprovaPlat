[CmdletBinding()]
param(
    [string]$SourceDatabase = 'ry-vue',
    [string]$TargetDatabase = 'ry_vue_codex_flowable_it'
)

$ErrorActionPreference = 'Stop'

# E2E 只能销毁指定隔离库，正式开发库始终只作为只读导出源。
$expectedSourceDatabase = 'ry-vue'
$expectedTargetDatabase = 'ry_vue_codex_flowable_it'
$testAccounts = @(
    @{ Username = 'codex_wf_admin_727'; RoleKey = 'workflow_admin' },
    @{ Username = 'codex_wf_design_727'; RoleKey = 'workflow_designer' },
    @{ Username = 'codex_wf_starter_727'; RoleKey = 'workflow_starter' },
    @{ Username = 'codex_wf_approve_727'; RoleKey = 'workflow_approver' },
    @{ Username = 'codex_wf_auditor_727'; RoleKey = 'workflow_auditor' }
)

<#
 * 转义 MySQL 字符串字面量，防止测试账号快照中的特殊字符改变 SQL 语义。
 * @param {string} Value 待写入 SQL 字符串字面量的值。
 * @returns {string} 已使用单引号包裹的 MySQL 安全字符串字面量。
#>
function ConvertTo-MySqlLiteral
{
    param([Parameter(Mandatory = $true)][string]$Value)

    return "'" + $Value.Replace("'", "''") + "'"
}

<#
 * 调用本机 MySQL 客户端并将失败转换为终止错误，避免同步在部分失败后继续运行。
 * @param {string[]} Arguments MySQL 客户端参数，不包含密码。
 * @returns {string[]} MySQL 标准输出文本行。
#>
function Invoke-MySql
{
    param([Parameter(Mandatory = $true)][string[]]$Arguments)

    $output = & $mysqlPath @mysqlConnectionArguments @Arguments 2>&1
    if ($LASTEXITCODE -ne 0)
    {
        throw "MySQL 命令失败：$($output -join [Environment]::NewLine)"
    }
    return @($output)
}

<#
 * 将 mysqldump 原始 SQL 通过标准输入导入指定隔离库，避免 source 交互命令和 PowerShell 重编码。
 * @param {string} Database 已通过白名单校验的目标数据库名。
 * @param {string} DumpPath mysqldump 生成的 UTF-8 SQL 文件路径。
 * @returns {void} 导入失败时抛出终止错误。
#>
function Import-MySqlDump
{
    param(
        [Parameter(Mandatory = $true)][string]$Database,
        [Parameter(Mandatory = $true)][string]$DumpPath
    )

    $processInfo = [System.Diagnostics.ProcessStartInfo]::new()
    $processInfo.FileName = $mysqlPath
    $processInfo.UseShellExecute = $false
    $processInfo.RedirectStandardInput = $true
    $processInfo.RedirectStandardOutput = $true
    $processInfo.RedirectStandardError = $true
    foreach ($argument in ($mysqlImportArguments + "--database=$Database"))
    {
        [void]$processInfo.ArgumentList.Add($argument)
    }
    $process = [System.Diagnostics.Process]::new()
    $process.StartInfo = $processInfo
    [void]$process.Start()
    if ($process.HasExited)
    {
        throw "E2E 隔离库导入进程提前退出：$($process.StandardError.ReadToEnd())"
    }
    $dumpBytes = [System.IO.File]::ReadAllBytes($DumpPath)
    $process.StandardInput.BaseStream.Write($dumpBytes, 0, $dumpBytes.Length)
    $process.StandardInput.Close()
    $process.WaitForExit()
    if ($process.ExitCode -ne 0)
    {
        throw "E2E 隔离库导入失败：$($process.StandardError.ReadToEnd())"
    }
}

<#
 * 从当前隔离库读取已登记五角色的登录必需数据，确保克隆后测试身份仍可真实登录。
 * @returns {hashtable[]} 五个测试账号的用户名、昵称和 BCrypt 密码散列。
#>
function Get-TestAccountSnapshot
{
    $names = ($testAccounts | ForEach-Object { ConvertTo-MySqlLiteral $_.Username }) -join ', '
    $rows = @()
    try
    {
        $rows = Invoke-MySql @('--batch', '--skip-column-names', "--database=$TargetDatabase",
            "--execute=SELECT user_name, nick_name, password FROM sys_user WHERE user_name IN ($names) ORDER BY user_name")
    }
    catch
    {
        # 上轮导入中断时 sys_user 可能尚未创建，后续从正式库口令散列恢复隔离测试身份。
        $rows = @()
    }

    $snapshotByUsername = @{}
    foreach ($row in $rows)
    {
        $columns = $row -split "`t", 3
        if ($columns.Count -ne 3 -or [string]::IsNullOrWhiteSpace($columns[2]))
        {
            throw '隔离库测试身份快照格式无效。'
        }
        $snapshotByUsername[$columns[0]] = @{
            Username = $columns[0]
            NickName = $columns[1]
            PasswordHash = $columns[2]
        }
    }

    if ($snapshotByUsername.Count -eq $testAccounts.Count)
    {
        return @($testAccounts | ForEach-Object {
            @{
                Username = $_.Username
                RoleKey = $_.RoleKey
                NickName = $snapshotByUsername[$_.Username].NickName
                PasswordHash = $snapshotByUsername[$_.Username].PasswordHash
            }
        })
    }

    # 若前一次同步在导入阶段中断，隔离库可能不完整；使用正式开发基线的已登记本机口令散列恢复测试身份。
    $fallbackHash = Invoke-MySql @('--batch', '--skip-column-names', "--database=$SourceDatabase",
        '--execute=SELECT password FROM sys_user WHERE user_name = ''admin'' AND status = ''0'' AND del_flag = ''0''')
    if ($fallbackHash.Count -ne 1 -or [string]::IsNullOrWhiteSpace($fallbackHash[0]))
    {
        throw '隔离库测试身份不完整，且无法从正式开发基线恢复已登记口令散列。'
    }
    return @($testAccounts | ForEach-Object {
        @{
            Username = $_.Username
            RoleKey = $_.RoleKey
            NickName = $_.RoleKey
            PasswordHash = $fallbackHash[0]
        }
    })
}

<#
 * 校验克隆完成后的关键业务基线，防止菜单、角色或工作流表随测试库长期漂移。
 * @returns {void} 基线不一致时抛出终止错误。
#>
function Assert-E2eBaseline
{
    $sourceCounts = Invoke-MySql @('--batch', '--skip-column-names', "--database=$SourceDatabase",
        '--execute=SELECT COUNT(*) FROM sys_menu; SELECT COUNT(*) FROM sys_role; SELECT COUNT(*) FROM information_schema.tables WHERE table_schema = DATABASE() AND table_name = ''wf_category''')
    $targetCounts = Invoke-MySql @('--batch', '--skip-column-names', "--database=$TargetDatabase",
        '--execute=SELECT COUNT(*) FROM sys_menu; SELECT COUNT(*) FROM sys_role; SELECT COUNT(*) FROM information_schema.tables WHERE table_schema = DATABASE() AND table_name = ''wf_category''')
    if ($sourceCounts.Count -ne 3 -or $targetCounts.Count -ne 3 -or $sourceCounts[0] -ne $targetCounts[0] -or $sourceCounts[1] -ne $targetCounts[1] -or $targetCounts[2] -ne '1')
    {
        throw 'E2E 隔离库与正式开发库的菜单、角色或工作流业务表基线不一致。'
    }

    $extensionMenuCount = Invoke-MySql @('--batch', '--skip-column-names', "--database=$TargetDatabase",
        '--execute=SELECT COUNT(*) FROM sys_menu WHERE path = ''extensions'' AND menu_name = ''扩展流程管理''')
    if ($extensionMenuCount.Count -ne 1 -or $extensionMenuCount[0] -ne '1')
    {
        throw 'E2E 隔离库未包含正式开发库的“扩展流程管理”二级菜单。'
    }
}

if ($SourceDatabase -cne $expectedSourceDatabase -or $TargetDatabase -cne $expectedTargetDatabase)
{
    throw "仅允许从 $expectedSourceDatabase 同步到 $expectedTargetDatabase。"
}

$repositoryRoot = (Resolve-Path (Join-Path $PSScriptRoot '..\..')).Path
$mysqlPath = (Get-Command mysql -ErrorAction Stop).Source
$mysqlConnectionArguments = @('--host=localhost', '--user=root', '--default-character-set=utf8mb4')
$mysqlImportArguments = @('--host=localhost', '--user=root', '--default-character-set=utf8mb4', '--binary-mode=1')
$mysqldumpPath = (Get-Command mysqldump -ErrorAction Stop).Source
$rootCredentialPath = Join-Path $repositoryRoot 'mysql-root.txt'
$rootPassword = $env:RUOYI_E2E_MYSQL_ROOT_PASSWORD
if ([string]::IsNullOrWhiteSpace($rootPassword) -and (Test-Path -LiteralPath $rootCredentialPath))
{
    $rootPassword = ([regex]::Match(
        [System.IO.File]::ReadAllText($rootCredentialPath, [System.Text.UTF8Encoding]::new($false)),
        '(?m)^Password:\s*(?<value>.+?)\s*$')).Groups['value'].Value.Trim()
}
if ([string]::IsNullOrWhiteSpace($rootPassword))
{
    throw '缺少本机 MySQL 管理密码：请设置 RUOYI_E2E_MYSQL_ROOT_PASSWORD 或配置已忽略的 mysql-root.txt。'
}

$dumpPath = Join-Path ([System.IO.Path]::GetTempPath()) ("approvaplat-e2e-baseline-" + [guid]::NewGuid().ToString('N') + '.sql')
$previousMySqlPassword = $env:MYSQL_PWD
try
{
    $env:MYSQL_PWD = $rootPassword
    $existingSchemas = Invoke-MySql @('--batch', '--skip-column-names', '--execute=SELECT schema_name FROM information_schema.schemata WHERE schema_name IN (''ry-vue'', ''ry_vue_codex_flowable_it'') ORDER BY schema_name')
    if (@($existingSchemas) -notcontains $SourceDatabase -or @($existingSchemas) -notcontains $TargetDatabase)
    {
        throw '正式开发库或指定 E2E 隔离库不存在，拒绝执行同步。'
    }

    $testAccountSnapshot = Get-TestAccountSnapshot

    # 先成功导出正式开发库，再销毁隔离库；导出失败不会影响既有 E2E 数据。
    # 不使用 --databases 时 mysqldump 不会生成 USE 语句；直接写入文件可避免 PowerShell 改写 SQL 编码。
    & $mysqldumpPath @mysqlConnectionArguments '--single-transaction' '--routines' '--events' '--triggers' '--set-gtid-purged=OFF' '--skip-comments' "--result-file=$dumpPath" $SourceDatabase
    if ($LASTEXITCODE -ne 0 -or -not (Test-Path -LiteralPath $dumpPath) -or (Get-Item -LiteralPath $dumpPath).Length -eq 0)
    {
        throw '正式开发库导出失败，未重建 E2E 隔离库。'
    }

    Invoke-MySql @('--execute=DROP DATABASE `ry_vue_codex_flowable_it`; CREATE DATABASE `ry_vue_codex_flowable_it` CHARACTER SET utf8mb4 COLLATE utf8mb4_unicode_ci') | Out-Null
    Import-MySqlDump -Database $TargetDatabase -DumpPath $dumpPath

    foreach ($account in $testAccountSnapshot)
    {
        $insertUserSql = "INSERT INTO sys_user (dept_id, user_name, nick_name, user_type, email, phonenumber, sex, avatar, password, status, del_flag, create_by, create_time, remark) VALUES (100, $(ConvertTo-MySqlLiteral $account.Username), $(ConvertTo-MySqlLiteral $account.NickName), '00', '', '', '0', '', $(ConvertTo-MySqlLiteral $account.PasswordHash), '0', '0', 'e2e-baseline-sync', NOW(), 'E2E 隔离专用职责分离账号')"
        Invoke-MySql @("--database=$TargetDatabase", "--execute=$insertUserSql") | Out-Null
        $insertRoleSql = "INSERT INTO sys_user_role (user_id, role_id) SELECT user.user_id, role.role_id FROM sys_user user JOIN sys_role role ON role.role_key = $(ConvertTo-MySqlLiteral $account.RoleKey) WHERE user.user_name = $(ConvertTo-MySqlLiteral $account.Username)"
        Invoke-MySql @("--database=$TargetDatabase", "--execute=$insertRoleSql") | Out-Null
    }

    Assert-E2eBaseline
    Write-Output "E2E_BASELINE_SYNCED=$TargetDatabase"
}
finally
{
    if (Test-Path -LiteralPath $dumpPath)
    {
        Remove-Item -LiteralPath $dumpPath -Force
    }
    $env:MYSQL_PWD = $previousMySqlPassword
}
