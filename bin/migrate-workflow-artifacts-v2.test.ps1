#requires -Version 7.0
param([switch]$Integration, [string]$ServerUuid)
$ErrorActionPreference='Stop'
$expectedServerUuid=$ServerUuid
. (Join-Path $PSScriptRoot 'migrate-workflow-artifacts-v2.ps1')
$checks=0
function Check([bool]$Success, [string]$Message) { if (-not $Success) { throw $Message }; $script:checks++ }
function Reject([scriptblock]$Action) {
    $failed=$false
    try { & $Action | Out-Null } catch { $failed=$true }
    Check $failed 'Expected migration rejection'
}
function Hex([string]$Text) { [Convert]::ToHexString([Text.Encoding]::UTF8.GetBytes($Text)) }
$fixtureRoot=Join-Path $PSScriptRoot '../ruoyi-flowable/src/test/resources/migration'
$legacy=Hex (Get-Content (Join-Path $fixtureRoot 'conditions-v1.json') -Raw)
$expected=Read-ArtifactJson (Hex (Get-Content (Join-Path $fixtureRoot 'conditions-v2.json') -Raw))
$converted=Convert-ArtifactConditions $legacy 'legacy-parent'
Check ([Text.Json.Nodes.JsonNode]::DeepEquals((Read-ArtifactJson $converted), $expected)) 'Conversion differs from the Java runtime fixture'
Check ((Read-ArtifactJson $converted)[0]['ruleId'].GetValue[long]() -eq 9007199254740993) 'Large integer precision changed'
Check ((Read-ArtifactJson $converted)[0]['ruleJson'].GetValue[string]() -ceq (Read-ArtifactJson $legacy)[0]['ruleJson'].GetValue[string]()) 'Frozen rule text changed'
Check ((Convert-ArtifactConditions (Hex '[]') 'legacy-parent') -ceq (Hex '[]')) 'Empty conditions changed'
Reject { Convert-ArtifactConditions $legacy 'another-parent' }
Reject { Convert-ArtifactConditions (Hex '{}') 'legacy-parent' }
$damaged=Read-ArtifactJson $legacy
$damaged[0]['snapshotChecksum']=[Text.Json.Nodes.JsonValue]::Create([string]('0'*64))
Reject { Convert-ArtifactConditions (ConvertTo-ArtifactHex $damaged) 'legacy-parent' }
$rows=@()
foreach ($name in @('manifest-v1','conditions-v1','forms-v1','controlled-loops-v1','participants-v1','extensions-v1','dmn-v1','call-activities-v1','task-sla-v1')) {
    $body=if($name -eq 'manifest-v1'){Hex '{"schemaVersion":1}'}elseif($name -eq 'conditions-v1'){$legacy}else{Hex '[]'}
    $rows+=@{artifactId='artifact';parentId='legacy-parent';artifactKey='approvaplat-artifacts:legacy-parent';parentExists=1;definitionCount=1;artifactDefinitionCount=0;resourceId=$name;revision=1;name="approvaplat/$name.json";hex=$body}
}
$changes=@(Get-ArtifactMigrationPlan $rows)
Check ($changes.Count -eq 2) 'Only manifest and condition bytes may change'
$after=foreach($row in $rows){$copy=@{}+$row;$change=$changes|Where-Object {$_.before.resourceId -ceq $row.resourceId};if($change){$copy.name=$change.name;$copy.hex=$change.hex;$copy.revision=2};$copy}
Check (@(Get-ArtifactMigrationPlan $after).Count -eq 0) 'Migration is not idempotent'
Reject { Get-ArtifactMigrationPlan @($rows | Where-Object resourceId -NE 'conditions-v1') }
Reject { Get-ArtifactMigrationPlan (@($rows)+@($rows[0])) }
$unknown=foreach($row in $rows){$copy=@{}+$row;if($copy.resourceId -eq 'manifest-v1'){$copy.hex=Hex '{"schemaVersion":3}'};$copy}
Reject { Get-ArtifactMigrationPlan $unknown }

if($Integration){
    if($expectedServerUuid -cnotmatch '^[a-f0-9]{8}(-[a-f0-9]{4}){3}-[a-f0-9]{12}$'){throw 'Integration requires the verified server UUID'}
    $target=[Uri]'mysql://127.0.0.1:13306'
    $username=$env:WORKFLOW_MYSQL_TEST_USERNAME;$password=$env:WORKFLOW_MYSQL_TEST_PASSWORD
    if((Invoke-VerificationMySql $target $username $password 'SELECT @@server_uuid') -cne $expectedServerUuid){throw 'Unexpected integration server'}
    # This database is created by this invocation, never an existing business/test schema.
    $fixtureDatabase='approvaplat_migration_test_'+[Guid]::NewGuid().ToString('N')
    $null=Invoke-VerificationMySql $target $username $password "CREATE DATABASE $fixtureDatabase CHARACTER SET utf8mb4 COLLATE utf8mb4_bin;"
    try {
        $setup=@'
CREATE TABLE ACT_RE_DEPLOYMENT (ID_ varchar(64) PRIMARY KEY, PARENT_DEPLOYMENT_ID_ varchar(64), CATEGORY_ varchar(255), KEY_ varchar(255)) ENGINE=InnoDB;
CREATE TABLE ACT_RE_PROCDEF (ID_ varchar(64) PRIMARY KEY, DEPLOYMENT_ID_ varchar(64)) ENGINE=InnoDB;
CREATE TABLE ACT_GE_BYTEARRAY (ID_ varchar(64) PRIMARY KEY, REV_ int, NAME_ varchar(255), DEPLOYMENT_ID_ varchar(64), BYTES_ longblob) ENGINE=InnoDB;
INSERT INTO ACT_RE_DEPLOYMENT VALUES ('legacy-parent',NULL,NULL,NULL),('artifact','legacy-parent','APPROVAPLAT_WORKFLOW_ARTIFACTS','approvaplat-artifacts:legacy-parent');
INSERT INTO ACT_RE_PROCDEF VALUES ('definition','legacy-parent');
'@
        foreach($row in $rows){$setup+="`nINSERT INTO ACT_GE_BYTEARRAY VALUES ($(ConvertTo-ArtifactSqlText $row.resourceId),1,$(ConvertTo-ArtifactSqlText $row.name),'artifact',UNHEX('$($row.hex)'));"}
        $null=Invoke-VerificationMySql $target $username $password $setup $fixtureDatabase
        $actual=@(Read-ArtifactRows $target $username $password $fixtureDatabase)
        Check ($actual.Count -eq 9) 'Fixture discovery failed'
        $sql=Get-ArtifactMigrationSql $actual @(Get-ArtifactMigrationPlan $actual) $expectedServerUuid $fixtureDatabase
        # Force a SQL failure after both writes, proving batch failure rolls back both.
        $failureSql=$sql.Replace('COMMIT;', 'INSERT INTO ap_artifact_v2_assert VALUES (0); COMMIT;')
        Reject { Invoke-VerificationMySql $target $username $password $failureSql $fixtureDatabase }
        $rolledBack=@(Read-ArtifactRows $target $username $password $fixtureDatabase)
        Check (($rolledBack|ConvertTo-Json -Compress) -ceq ($actual|ConvertTo-Json -Compress)) 'SQL failure did not restore original bytes and revisions'
        Reject { Invoke-VerificationMySql $target $username $password (Get-ArtifactMigrationSql $actual $changes '00000000-0000-0000-0000-000000000000' $fixtureDatabase) $fixtureDatabase }
        # A concurrent/stale read must reject before making any changes.
        $null=Invoke-VerificationMySql $target $username $password "UPDATE ACT_GE_BYTEARRAY SET REV_=2 WHERE ID_='forms-v1';" $fixtureDatabase
        Reject { Invoke-VerificationMySql $target $username $password $sql $fixtureDatabase }
        $null=Invoke-VerificationMySql $target $username $password "UPDATE ACT_GE_BYTEARRAY SET REV_=1 WHERE ID_='forms-v1';" $fixtureDatabase
        $null=Invoke-VerificationMySql $target $username $password $sql $fixtureDatabase
        $migrated=@(Read-ArtifactRows $target $username $password $fixtureDatabase)
        Check (@(Get-ArtifactMigrationPlan $migrated).Count -eq 0) 'Applied migration is not current'
        foreach($row in $after){$actualRow=$migrated|Where-Object resourceId -CEQ $row.resourceId;Check ($actualRow.hex -ceq $row.hex -and $actualRow.name -ceq $row.name -and $actualRow.revision -eq $row.revision) 'Unexpected resource change'}
        $reverse=foreach($change in $changes){@{before=($migrated|Where-Object resourceId -CEQ $change.before.resourceId);name=$change.before.name;hex=$change.before.hex}}
        $null=Invoke-VerificationMySql $target $username $password (Get-ArtifactMigrationSql $migrated @($reverse) $expectedServerUuid $fixtureDatabase) $fixtureDatabase
        Check (@(Get-ArtifactMigrationPlan @(Read-ArtifactRows $target $username $password $fixtureDatabase)).Count -eq 2) 'Backup rollback did not restore v1'
    } finally {
        if($fixtureDatabase -cnotmatch '^approvaplat_migration_test_[0-9a-f]{32}$'){throw 'Invalid owned fixture database'}
        $null=Invoke-VerificationMySql $target $username $password "DROP DATABASE $fixtureDatabase;"
    }
}
Write-Output "Artifact migration regression checks passed: $checks (MySQL: $([bool]$Integration))"
