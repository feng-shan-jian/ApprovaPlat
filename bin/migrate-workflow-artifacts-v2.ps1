#requires -Version 7.0
param(
    [string]$ServerUuid,
    [string]$Database = 'approvaplat',
    [string]$HostName = '127.0.0.1',
    [int]$Port = 13306,
    [switch]$Apply,
    [string]$BackupDirectory
)
$ErrorActionPreference = 'Stop'
. (Join-Path $PSScriptRoot 'verification-common.ps1')
$script:ArtifactCategory = 'APPROVAPLAT_WORKFLOW_ARTIFACTS'
$script:ArtifactJsonOptions = [Text.Json.JsonSerializerOptions]::new()
$script:ArtifactJsonOptions.Encoder = [Text.Encodings.Web.JavaScriptEncoder]::UnsafeRelaxedJsonEscaping

function Get-ArtifactChecksum([object[]]$Values) {
    $stream = [IO.MemoryStream]::new()
    try {
        foreach ($value in $Values) {
            $bytes = [Text.Encoding]::UTF8.GetBytes([string]$value)
            $prefix = [Text.Encoding]::ASCII.GetBytes("$($bytes.Length):")
            $stream.Write($prefix); $stream.Write($bytes)
        }
        [Convert]::ToHexString([Security.Cryptography.SHA256]::HashData($stream.ToArray())).ToLowerInvariant()
    } finally { $stream.Dispose() }
}
function Read-ArtifactJson([string]$Hex) {
    $text = [Text.UTF8Encoding]::new($false, $true).GetString([Convert]::FromHexString($Hex))
    # JsonNode keeps JSON number tokens intact (including integers beyond double precision).
    $node = [Text.Json.Nodes.JsonNode]::Parse($text)
    if ($null -eq $node) { throw 'Null artifact JSON' }
    return ,$node
}
function ConvertTo-ArtifactHex([Text.Json.Nodes.JsonNode]$Node) {
    [Convert]::ToHexString([Text.Encoding]::UTF8.GetBytes($Node.ToJsonString($script:ArtifactJsonOptions)))
}
function Convert-ArtifactConditions([string]$Hex, [string]$ParentId) {
    $rules = Read-ArtifactJson $Hex
    if ($rules -isnot [Text.Json.Nodes.JsonArray]) { throw 'Condition resource must be an array' }
    $keys = [Collections.Generic.HashSet[string]]::new([StringComparer]::Ordinal)
    foreach ($rule in $rules) {
        if ($rule -isnot [Text.Json.Nodes.JsonObject]) { throw 'Condition entry must be an object' }
        if ($rule['deployId'].GetValue[string]() -cne $ParentId) { throw 'Condition parent mismatch' }
        $values = foreach ($name in @('processKey','gatewayId','gatewayType','gatewayToken','flowId','flowName','flowToken')) {
            if ($null -eq $rule[$name]) {
                if ($name -ne 'flowName') { throw "Missing condition field: $name" }
                ''
            } else { $rule[$name].GetValue[string]() }
        }
        $key = $values[0] + [char]0 + $values[1] + [char]0 + $values[4]
        if (-not $keys.Add($key)) { throw 'Duplicate condition branch' }
        $default = $rule['defaultFlow'].GetValue[bool]()
        $ruleJson = $rule['ruleJson'].GetValue[string]()
        $parsed = [Text.Json.Nodes.JsonNode]::Parse($ruleJson)
        if ($parsed -isnot [Text.Json.Nodes.JsonObject] -or $parsed['default'].GetValue[bool]() -ne $default) { throw 'Invalid frozen condition rule' }
        if (-not $rule.ContainsKey('celConfigJson')) { throw 'Missing v1 CEL field' }
        $cel = if ($null -eq $rule['celConfigJson']) { $null } else { $rule['celConfigJson'].GetValue[string]() }
        if ($cel -and [Text.Json.Nodes.JsonNode]::Parse($cel) -isnot [Text.Json.Nodes.JsonObject]) { throw 'Invalid v1 CEL object' }
        $currentValues = @($values) + @($(if ($default) { '1' } else { '0' }), $ruleJson)
        # v1 publisher persisted canonical rule/CEL strings; refuse a damaged checksum.
        # Never evaluate CEL, reinterpret a rule, or read mutable designer/model data.
        if ((Get-ArtifactChecksum ($currentValues + @($cel))) -cne $rule['snapshotChecksum'].GetValue[string]()) { throw 'V1 condition checksum mismatch' }
        $null = $rule.Remove('celConfigJson')
        $rule['snapshotChecksum'] = [Text.Json.Nodes.JsonValue]::Create([string](Get-ArtifactChecksum $currentValues))
    }
    ConvertTo-ArtifactHex $rules
}
function Get-ArtifactMigrationPlan([object[]]$Rows) {
    $changes = [Collections.Generic.List[object]]::new()
    $parents = [Collections.Generic.HashSet[string]]::new([StringComparer]::Ordinal)
    foreach ($group in @($Rows | Group-Object artifactId)) {
        $first = $group.Group[0]
        if (-not $first.parentExists -or $first.definitionCount -lt 1 -or $first.artifactDefinitionCount -ne 0 -or $first.artifactKey -cne "approvaplat-artifacts:$($first.parentId)" -or -not $parents.Add($first.parentId)) { throw 'Invalid artifact deployment relation' }
        $resources = [Collections.Generic.Dictionary[string,object]]::new([StringComparer]::Ordinal)
        foreach ($row in $group.Group) {
            if (-not $row.resourceId -or $resources.ContainsKey($row.name)) { throw 'Missing or duplicate artifact resource' }
            if ($row.hex.Length -gt 64MB) { throw 'Artifact exceeds 32 MiB' }
            $resources.Add($row.name, $row)
        }
        if (($group.Group | Measure-Object -Property { $_.hex.Length / 2 } -Sum).Sum -gt 64MB) { throw 'Artifact deployment exceeds 64 MiB' }
        if (-not $resources.ContainsKey('approvaplat/manifest-v1.json')) { throw 'Missing artifact manifest' }
        $manifestRow = $resources['approvaplat/manifest-v1.json']
        $manifest = Read-ArtifactJson $manifestRow.hex
        if ($manifest -isnot [Text.Json.Nodes.JsonObject]) { throw 'Invalid artifact manifest' }
        $version = $manifest['schemaVersion'].GetValue[int]()
        if ($version -notin @(1,2)) { throw "Unsupported artifact version: $version" }
        $conditionName = "approvaplat/conditions-v$version.json"
        $expected = @('approvaplat/manifest-v1.json', $conditionName, 'approvaplat/forms-v1.json', 'approvaplat/controlled-loops-v1.json', 'approvaplat/participants-v1.json', 'approvaplat/extensions-v1.json', 'approvaplat/dmn-v1.json', 'approvaplat/call-activities-v1.json', 'approvaplat/task-sla-v1.json')
        if ($resources.Count -ne $expected.Count) { throw 'Unexpected artifact resource set' }
        foreach ($name in $expected) {
            if (-not $resources.ContainsKey($name)) { throw "Missing resource: $name" }
            if ($name -ne 'approvaplat/manifest-v1.json' -and (Read-ArtifactJson $resources[$name].hex) -isnot [Text.Json.Nodes.JsonArray]) { throw "Resource must be an array: $name" }
        }
        if ($version -eq 2) { continue }
        $condition = $resources[$conditionName]
        $converted = Convert-ArtifactConditions $condition.hex $first.parentId
        $changes.Add(@{ before = $condition; name = 'approvaplat/conditions-v2.json'; hex = $converted })
        $manifest['schemaVersion'] = [Text.Json.Nodes.JsonValue]::Create([int]2)
        $changes.Add(@{ before = $manifestRow; name = $manifestRow.name; hex = (ConvertTo-ArtifactHex $manifest) })
    }
    $changes.ToArray()
}
function ConvertTo-ArtifactSqlText([string]$Value) {
    "CONVERT(UNHEX('$([Convert]::ToHexString([Text.Encoding]::UTF8.GetBytes($Value)))') USING utf8mb4)"
}
function Get-ArtifactMigrationSql([object[]]$Rows, [object[]]$Changes, [string]$ExpectedUuid, [string]$ExpectedDatabase) {
    $sql = [Collections.Generic.List[string]]::new()
    # Batch mysql exits at the first error. Disconnect rolls back all InnoDB writes.
    # A temporary CHECK table supplies assertions without persistent stored routines.
    $sql.Add('CREATE TEMPORARY TABLE ap_artifact_v2_assert (ok TINYINT NOT NULL CHECK (ok = 1)) ENGINE=InnoDB;')
    $sql.Add('SET TRANSACTION ISOLATION LEVEL REPEATABLE READ; START TRANSACTION;')
    $sql.Add("INSERT INTO ap_artifact_v2_assert VALUES (BINARY @@server_uuid = $(ConvertTo-ArtifactSqlText $ExpectedUuid) AND BINARY DATABASE() = $(ConvertTo-ArtifactSqlText $ExpectedDatabase));")
    $sql.Add("SELECT ID_ FROM ACT_RE_DEPLOYMENT WHERE CATEGORY_='$script:ArtifactCategory' ORDER BY ID_ FOR UPDATE;")
    $sql.Add("SELECT b.ID_ FROM ACT_GE_BYTEARRAY b JOIN ACT_RE_DEPLOYMENT d ON d.ID_=b.DEPLOYMENT_ID_ WHERE d.CATEGORY_='$script:ArtifactCategory' ORDER BY b.ID_ FOR UPDATE;")
    $sql.Add("INSERT INTO ap_artifact_v2_assert SELECT COUNT(*)=$(@($Rows | Group-Object artifactId).Count) FROM ACT_RE_DEPLOYMENT WHERE CATEGORY_='$script:ArtifactCategory';")
    $sql.Add("INSERT INTO ap_artifact_v2_assert SELECT COUNT(*)=$($Rows.Count) FROM ACT_GE_BYTEARRAY b JOIN ACT_RE_DEPLOYMENT d ON d.ID_=b.DEPLOYMENT_ID_ WHERE d.CATEGORY_='$script:ArtifactCategory';")
    foreach ($row in $Rows) {
        $sql.Add("INSERT INTO ap_artifact_v2_assert SELECT COUNT(*)=1 FROM ACT_GE_BYTEARRAY b JOIN ACT_RE_DEPLOYMENT d ON d.ID_=b.DEPLOYMENT_ID_ JOIN ACT_RE_DEPLOYMENT p ON p.ID_=d.PARENT_DEPLOYMENT_ID_ WHERE BINARY b.ID_=$(ConvertTo-ArtifactSqlText $row.resourceId) AND BINARY b.DEPLOYMENT_ID_=$(ConvertTo-ArtifactSqlText $row.artifactId) AND d.CATEGORY_='$script:ArtifactCategory' AND BINARY d.PARENT_DEPLOYMENT_ID_=$(ConvertTo-ArtifactSqlText $row.parentId) AND BINARY d.KEY_=$(ConvertTo-ArtifactSqlText $row.artifactKey) AND (SELECT COUNT(*) FROM ACT_RE_PROCDEF pd WHERE pd.DEPLOYMENT_ID_=p.ID_)=$($row.definitionCount) AND (SELECT COUNT(*) FROM ACT_RE_PROCDEF pd WHERE pd.DEPLOYMENT_ID_=d.ID_)=0 AND BINARY b.NAME_=$(ConvertTo-ArtifactSqlText $row.name) AND b.REV_ <=> $($row.revision ?? 'NULL') AND SHA2(b.BYTES_,256)=SHA2(UNHEX('$($row.hex)'),256);")
    }
    foreach ($change in $Changes) {
        $row = $change.before
        $sql.Add("UPDATE ACT_GE_BYTEARRAY SET NAME_=$(ConvertTo-ArtifactSqlText $change.name),BYTES_=UNHEX('$($change.hex)'),REV_=COALESCE(REV_,0)+1 WHERE BINARY ID_=$(ConvertTo-ArtifactSqlText $row.resourceId);")
        $sql.Add('INSERT INTO ap_artifact_v2_assert VALUES (ROW_COUNT()=1);')
    }
    $sql.Add('COMMIT; DROP TEMPORARY TABLE ap_artifact_v2_assert;')
    $sql -join "`n"
}
function Read-ArtifactRows([Uri]$Target, [string]$Username, [string]$Password, [string]$DatabaseName) {
    $query = @'
SELECT JSON_OBJECT('artifactId',d.ID_,'parentId',d.PARENT_DEPLOYMENT_ID_,'artifactKey',d.KEY_,'parentExists',p.ID_ IS NOT NULL,'definitionCount',(SELECT COUNT(*) FROM ACT_RE_PROCDEF pd WHERE pd.DEPLOYMENT_ID_=p.ID_),'artifactDefinitionCount',(SELECT COUNT(*) FROM ACT_RE_PROCDEF pd WHERE pd.DEPLOYMENT_ID_=d.ID_),'resourceId',b.ID_,'revision',b.REV_,'name',b.NAME_,'hex',HEX(b.BYTES_))
FROM ACT_RE_DEPLOYMENT d LEFT JOIN ACT_RE_DEPLOYMENT p ON p.ID_=d.PARENT_DEPLOYMENT_ID_ LEFT JOIN ACT_GE_BYTEARRAY b ON b.DEPLOYMENT_ID_=d.ID_
WHERE d.CATEGORY_='APPROVAPLAT_WORKFLOW_ARTIFACTS' ORDER BY d.ID_,b.ID_;
'@
    $output = Invoke-VerificationMySql $Target $Username $Password $query $DatabaseName
    foreach ($line in $output -split "\r?\n") { if ($line) { ConvertFrom-Json -InputObject $line -AsHashtable } }
}

# Dot sourcing exposes pure conversion/planning functions to the formal regression suite.
if ($MyInvocation.InvocationName -eq '.') { return }
if ($ServerUuid -cnotmatch '^[a-f0-9]{8}(-[a-f0-9]{4}){3}-[a-f0-9]{12}$') { throw 'Pass the independently verified -ServerUuid' }
if ($Database -notmatch '^[a-zA-Z0-9_]+$') { throw 'Invalid database name' }
if (-not $env:WORKFLOW_MYSQL_TEST_USERNAME -or -not $env:WORKFLOW_MYSQL_TEST_PASSWORD) { throw 'Set WORKFLOW_MYSQL_TEST_USERNAME and WORKFLOW_MYSQL_TEST_PASSWORD in this process' }
$target = [Uri]"mysql://${HostName}:$Port"
$username = $env:WORKFLOW_MYSQL_TEST_USERNAME; $password = $env:WORKFLOW_MYSQL_TEST_PASSWORD
$identity = Invoke-VerificationMySql $target $username $password 'SELECT @@server_uuid' $Database
if ($identity -cne $ServerUuid) { throw 'Database server UUID mismatch' }
$rows = @(Read-ArtifactRows $target $username $password $Database)
$changes = @(Get-ArtifactMigrationPlan $rows)
$summary = [ordered]@{ database=$Database; serverUuid=$identity; artifacts=@($rows | Group-Object artifactId).Count; migrated=0; pending=$changes.Count/2; status='dry-run' }
if ($Apply -and $changes.Count) {
    if (-not $BackupDirectory) { $BackupDirectory = Join-Path $PSScriptRoot "../output/database-backups/artifacts-v2-$([DateTime]::Now.ToString('yyyyMMdd-HHmmss'))-$([Guid]::NewGuid().ToString('N').Substring(0,8))" }
    if (Test-Path -LiteralPath $BackupDirectory) { throw 'Use a new backup directory; existing backups are never overwritten' }
    $null = New-Item -ItemType Directory -Path $BackupDirectory
    $backupPath = Join-Path $BackupDirectory 'resources-before.json'
    $rows | ConvertTo-Json -Depth 8 | Set-Content -LiteralPath $backupPath -Encoding utf8
    $after = foreach ($row in $rows) {
        $updated = @{} + $row
        $change = $changes | Where-Object { $_.before.resourceId -ceq $row.resourceId }
        if ($change) { $updated.name=$change.name; $updated.hex=$change.hex; $updated.revision=[int]$row.revision+1 }
        $updated
    }
    $reverse = foreach ($change in $changes) { @{ before=($after | Where-Object resourceId -CEQ $change.before.resourceId); name=$change.before.name; hex=$change.before.hex } }
    Get-ArtifactMigrationSql $after @($reverse) $identity $Database | Set-Content -LiteralPath (Join-Path $BackupDirectory 'rollback.sql') -Encoding utf8
    $summary.backup=[IO.Path]::GetFullPath($backupPath)
    $summary.backupSha256=(Get-FileHash -LiteralPath $backupPath).Hash
    $summary.status='prepared'
    $receipt = Join-Path $BackupDirectory 'receipt.json'
    $summary | ConvertTo-Json | Set-Content -LiteralPath $receipt -Encoding utf8
    try {
        $null = Invoke-VerificationMySql $target $username $password (Get-ArtifactMigrationSql $rows $changes $identity $Database) $Database
        $summary.status='committed'; $summary.migrated=$changes.Count/2; $summary.pending=0
        $verified = @(Read-ArtifactRows $target $username $password $Database)
        if (@(Get-ArtifactMigrationPlan $verified).Count -ne 0 -or $verified.Count -ne $after.Count) { throw 'Post-migration verification failed' }
        foreach ($row in $after) {
            $actual = @($verified | Where-Object resourceId -CEQ $row.resourceId)
            if ($actual.Count -ne 1 -or $actual[0].hex -cne $row.hex -or $actual[0].name -cne $row.name -or $actual[0].revision -ne $row.revision) { throw 'Post-migration resource bytes differ' }
        }
        $summary.status='applied'; $summary.migrated=$changes.Count/2; $summary.pending=0
    } catch { $summary.status=if($summary.migrated){'committed-verification-failed'}else{'failed-before-commit'}; throw }
    finally { $summary | ConvertTo-Json | Set-Content -LiteralPath $receipt -Encoding utf8 }
} elseif ($Apply) { $summary.status='already-current' }
$summary | ConvertTo-Json
