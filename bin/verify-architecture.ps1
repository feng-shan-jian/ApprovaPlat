[CmdletBinding()]
param(
    [string]$RepositoryRoot = (Split-Path $PSScriptRoot -Parent),
    [switch]$SelfTest
)

$ErrorActionPreference = 'Stop'
Set-StrictMode -Version Latest
$RepositoryRoot = [IO.Path]::GetFullPath($RepositoryRoot)

# jdeps inspects compiled type dependencies, including nested classes. Run after
# Maven compile/test. These exact boundaries permit frozen reads and protocol types.
# Reflection and runtime DI edges are outside this deliberately bounded check.
$rules = [ordered]@{
    'com.ruoyi.flowable.service.model.WorkflowAuthorFieldCatalogService' = @('com.ruoyi.flowable.service.model.WorkflowModelService')
    'com.ruoyi.flowable.service.attachment.WorkflowAttachmentCleanupCoordinator' = @('com.ruoyi.flowable.service.attachment.WorkflowAttachmentService')
    'com.ruoyi.flowable.service.notification.WorkflowNotificationPlanner' = @(
        'org.flowable.engine.RepositoryService', 'org.flowable.engine.RuntimeService', 'org.flowable.engine.TaskService',
        'org.flowable.engine.HistoryService', 'org.flowable.engine.IdentityService', 'org.flowable.engine.ManagementService',
        'org.flowable.engine.FormService', 'org.flowable.engine.ProcessEngine',
        'com.ruoyi.flowable.identity.WorkflowIdentityResolver', 'com.ruoyi.flowable.identity.WorkflowCurrentIdentity',
        'com.ruoyi.common.utils.SecurityUtils', 'org.springframework.security.core.context.SecurityContextHolder',
        'org.flowable.common.engine.impl.identity.Authentication', 'java.lang.ThreadLocal')
    'com.ruoyi.flowable.service.integration.WorkflowHttpExecution' = @(
        'com.ruoyi.flowable.service.model.WorkflowModelService', 'com.ruoyi.flowable.service.model.WorkflowBpmnService',
        'com.ruoyi.flowable.extension.WorkflowHttpConnector', 'org.flowable.engine.delegate.DelegateExecution')
    'com.ruoyi.flowable.service.process.WorkflowProcessDetailService' = @()
    'com.ruoyi.flowable.service.task.WorkflowTaskReturnPreparation' = @()
    'com.ruoyi.flowable.service.task.WorkflowTaskRevokePreparation' = @()
    'com.ruoyi.flowable.service.task.WorkflowApplicationResubmitPreparation' = @()
}
$writeApplications = @('WorkflowTaskReturnApplicationService', 'WorkflowApplicationResubmitApplicationService', 'WorkflowTaskRevokeApplicationService') |
    ForEach-Object { "com.ruoyi.flowable.service.task.$_" }
foreach ($type in @($rules.Keys | Select-Object -Last 4)) { $rules[$type] = $writeApplications }
$javaBin = Split-Path (Get-Command java -ErrorAction Stop).Source
$jdeps = Join-Path $javaBin 'jdeps.exe'
$javac = Join-Path $javaBin 'javac.exe'

function Assert-MavenGraph([string]$Root) {
    [xml]$parent = Get-Content -Raw -LiteralPath (Join-Path $Root 'pom.xml')
    $graph = @{}
    $pomPaths = @{}
    foreach ($module in $parent.project.modules.module) {
        $path = Join-Path $Root "$module/pom.xml"
        [xml]$pom = Get-Content -Raw -LiteralPath $path
        $id = [string]$pom.project.artifactId
        $pomPaths[$id] = $path
        # dependencyManagement is not a dependency edge. Include test scopes: Maven
        # also uses them to order the reactor. Current reactor has no module profiles.
        $graph[$id] = @($pom.SelectNodes("/*[local-name()='project']/*[local-name()='dependencies']/*[local-name()='dependency']") |
            Where-Object { [string]$_.groupId -eq 'com.ruoyi' } | ForEach-Object { [string]$_.artifactId })
    }
    foreach ($id in $graph.Keys) {
        foreach ($target in $graph[$id]) {
            if (!$graph.ContainsKey($target)) { throw "$($pomPaths[$id]): unknown internal module dependency $id -> $target" }
            if (($id -eq 'ruoyi-flowable' -and $target -in @('ruoyi-framework', 'ruoyi-admin')) -or
                ($id -in @('ruoyi-system', 'ruoyi-common') -and $target -eq 'ruoyi-flowable')) {
                throw "$($pomPaths[$id]): forbidden Maven dependency $id -> $target"
            }
        }
    }
    $active = [Collections.Generic.HashSet[string]]::new()
    $done = [Collections.Generic.HashSet[string]]::new()
    function Visit-Module([string]$Id, [string[]]$Chain) {
        if ($active.Contains($Id)) { throw "Maven dependency cycle: $(($Chain + $Id) -join ' -> ')" }
        if ($done.Contains($Id)) { return }
        [void]$active.Add($Id)
        foreach ($target in $graph[$Id]) { Visit-Module $target ($Chain + $Id) }
        [void]$active.Remove($Id)
        [void]$done.Add($Id)
    }
    foreach ($id in @($graph.Keys | Sort-Object)) { Visit-Module $id @() }
    Write-Host "Maven architecture passed: $($graph.Count) modules, acyclic dependency graph."
}

function Assert-TypeEdges([string]$Root) {
    $classes = Join-Path $Root 'ruoyi-flowable/target/classes'
    foreach ($type in $rules.Keys) {
        $relative = $type.Replace('.', '/')
        $source = Get-Item -LiteralPath (Join-Path $Root "ruoyi-flowable/src/main/java/$relative.java")
        $compiledPath = Join-Path $classes "$relative.class"
        if (!(Test-Path -LiteralPath $compiledPath)) { throw "$($source.FullName): compile the reactor before architecture verification" }
        if ((Get-Item -LiteralPath $compiledPath).LastWriteTimeUtc -lt $source.LastWriteTimeUtc) {
            throw "$($source.FullName): stale compiled class; rerun Maven compile/test before architecture verification"
        }
    }
    $filter = '^(?:' + (($rules.Keys | ForEach-Object { [regex]::Escape($_) }) -join '|') + ')(?:\$.*)?$'
    $output = & $jdeps '-verbose:class' '-filter:none' '-include' $filter $classes 2>&1
    if ($LASTEXITCODE -ne 0) { throw "jdeps failed: $($output -join [Environment]::NewLine)" }
    $seen = [Collections.Generic.HashSet[string]]::new()
    $edgeCount = 0
    foreach ($line in $output) {
        if ([string]$line -notmatch '^\s+(\S+)\s+->\s+(\S+)\s+') { continue }
        $from = $Matches[1]; $target = $Matches[2]
        $owner = ($from -split '\$')[0]
        if (!$rules.Contains($owner)) { continue }
        [void]$seen.Add($owner)
        $edgeCount++
        if (($target -split '\$')[0] -in $rules[$owner]) {
            $source = "ruoyi-flowable/src/main/java/$($owner.Replace('.', '/')).java"
            throw "${source}: forbidden type dependency $from -> $target"
        }
    }
    if ($seen.Count -ne $rules.Count) { throw "jdeps did not inspect all protected types: $($seen.Count)/$($rules.Count)" }
    Write-Host "Java architecture passed: $($rules.Count) exact types and nested classes, $edgeCount dependency edges."
}

function Assert-Frontend([string]$Root) {
    $output = & node (Join-Path $PSScriptRoot 'verify-frontend-architecture.mjs') $Root $RepositoryRoot 2>&1
    if ($LASTEXITCODE -ne 0) { throw "Frontend architecture verification failed: $($output -join [Environment]::NewLine)" }
    $output | ForEach-Object { Write-Host $_ }
}

function Assert-Rejected([scriptblock]$Action, [string]$Expected) {
    try { & $Action }
    catch {
        if ($_.Exception.Message -notmatch [regex]::Escape($Expected)) { throw }
        Write-Host "Counterexample rejected: $($_.Exception.Message)"
        return
    }
    throw "Architecture counterexample was not detected: $Expected"
}

Assert-MavenGraph $RepositoryRoot
Assert-TypeEdges $RepositoryRoot
Assert-Frontend $RepositoryRoot

if ($SelfTest) {
    $temporary = Join-Path ([IO.Path]::GetTempPath()) ('workflow-architecture-' + [guid]::NewGuid().ToString('N'))
    [void](New-Item -ItemType Directory -Path $temporary)
    try {
        Copy-Item -LiteralPath (Join-Path $RepositoryRoot 'pom.xml') -Destination $temporary
        [xml]$parent = Get-Content -Raw -LiteralPath (Join-Path $RepositoryRoot 'pom.xml')
        foreach ($module in $parent.project.modules.module) {
            [void](New-Item -ItemType Directory -Path (Join-Path $temporary $module))
            Copy-Item -LiteralPath (Join-Path $RepositoryRoot "$module/pom.xml") -Destination (Join-Path $temporary "$module/pom.xml")
        }
        foreach ($type in $rules.Keys) {
            $relative = $type.Replace('.', '/')
            foreach ($suffix in @('src/main/java', 'target/classes')) {
                $extension = if ($suffix -eq 'target/classes') { 'class' } else { 'java' }
                $target = Join-Path $temporary "ruoyi-flowable/$suffix/$relative.$extension"
                [void](New-Item -ItemType Directory -Path (Split-Path $target) -Force)
                Copy-Item -LiteralPath (Join-Path $RepositoryRoot "ruoyi-flowable/$suffix/$relative.$extension") -Destination $target
            }
            $classPath = Join-Path $RepositoryRoot "ruoyi-flowable/target/classes/$relative.class"
            $className = [IO.Path]::GetFileNameWithoutExtension($classPath)
            Get-ChildItem -LiteralPath (Split-Path $classPath) -File -Filter ($className + '$*.class') |
                ForEach-Object { Copy-Item -LiteralPath $_.FullName -Destination (Split-Path $target) }
        }
        $violatingType = 'com/ruoyi/flowable/service/model/WorkflowAuthorFieldCatalogService'
        $source = Join-Path $temporary "ruoyi-flowable/src/main/java/$violatingType.java"
        # A real declared field dependency in a compiled temporary replacement.
        [IO.File]::WriteAllText($source, 'package com.ruoyi.flowable.service.model; public class WorkflowAuthorFieldCatalogService { private WorkflowModelService forbidden; }')
        & $javac '-encoding' 'UTF-8' '-cp' (Join-Path $RepositoryRoot 'ruoyi-flowable/target/classes') '-d' (Join-Path $temporary 'ruoyi-flowable/target/classes') $source
        if ($LASTEXITCODE -ne 0) { throw 'Counterexample javac failed.' }
        Assert-Rejected { Assert-TypeEdges $temporary } 'WorkflowAuthorFieldCatalogService -> com.ruoyi.flowable.service.model.WorkflowModelService'
        Copy-Item -LiteralPath (Join-Path $RepositoryRoot "ruoyi-flowable/src/main/java/$violatingType.java") -Destination $source -Force
        Copy-Item -LiteralPath (Join-Path $RepositoryRoot "ruoyi-flowable/target/classes/$violatingType.class") -Destination (Join-Path $temporary "ruoyi-flowable/target/classes/$violatingType.class") -Force
        Assert-TypeEdges $temporary

        $pomPath = Join-Path $temporary 'ruoyi-framework/pom.xml'
        [xml]$pom = Get-Content -Raw -LiteralPath $pomPath
        $dependency = $pom.CreateElement('dependency', $pom.DocumentElement.NamespaceURI)
        foreach ($pair in @(@('groupId', 'com.ruoyi'), @('artifactId', 'ruoyi-admin'))) {
            $element = $pom.CreateElement($pair[0], $pom.DocumentElement.NamespaceURI)
            $element.InnerText = $pair[1]; [void]$dependency.AppendChild($element)
        }
        [void]$pom.project.dependencies.AppendChild($dependency)
        $pom.Save($pomPath)
        Assert-Rejected { Assert-MavenGraph $temporary } 'Maven dependency cycle'
        Copy-Item -LiteralPath (Join-Path $RepositoryRoot 'ruoyi-framework/pom.xml') -Destination $pomPath -Force
        Assert-MavenGraph $temporary

        foreach ($directory in @('ruoyi-ui/src/components/workflow/designer', 'ruoyi-ui/src/views/workflow/model',
                'ruoyi-ui/tests/contracts', 'ruoyi-ui/tests/e2e/fixtures')) {
            $destination = Join-Path $temporary $directory
            [void](New-Item -ItemType Directory -Path (Split-Path $destination) -Force)
            Copy-Item -LiteralPath (Join-Path $RepositoryRoot $directory) -Destination $destination -Recurse
        }
        foreach ($spec in @('component-credentials', 'component-initialization', 'component-request-ownership',
                'designer-edit-session', 'designer-identity-wiring', 'upload-settlement')) {
            $relative = "ruoyi-ui/tests/e2e/$spec.spec.js"
            Copy-Item -LiteralPath (Join-Path $RepositoryRoot $relative) -Destination (Join-Path $temporary $relative)
        }
        $domain = 'ruoyi-ui/src/components/workflow/designer/formParticipantDomain.js'
        Add-Content -LiteralPath (Join-Path $temporary $domain) -Value "`nconst duplicateContract = { assignees: { type: 'user', capability: 'approval' } }"
        Assert-Rejected { Assert-Frontend $temporary } 'one nine-pool declaration'
        Copy-Item -LiteralPath (Join-Path $RepositoryRoot $domain) -Destination (Join-Path $temporary $domain) -Force
        $testPath = 'ruoyi-ui/tests/contracts/request-sequence.test.js'
        Add-Content -LiteralPath (Join-Path $temporary $testPath) -Value "`nnew Function('return 1')"
        Assert-Rejected { Assert-Frontend $temporary } 'may not reconstruct private source via Function'
        Copy-Item -LiteralPath (Join-Path $RepositoryRoot $testPath) -Destination (Join-Path $temporary $testPath) -Force
        Assert-Frontend $temporary
        Write-Host 'Architecture self-test passed: compiled forbidden edge, Maven cycle, duplicate identity contract and source reconstruction rejected; restored copies pass.'
    }
    finally {
        $resolvedTemporary = [IO.Path]::GetFullPath($temporary)
        $tempRoot = [IO.Path]::GetFullPath([IO.Path]::GetTempPath()).TrimEnd([IO.Path]::DirectorySeparatorChar) + [IO.Path]::DirectorySeparatorChar
        if (!$resolvedTemporary.StartsWith($tempRoot, [StringComparison]::OrdinalIgnoreCase) -or
            (Split-Path $resolvedTemporary -Leaf) -notlike 'workflow-architecture-*') { throw 'Refusing cleanup outside the owned temporary directory.' }
        Remove-Item -LiteralPath $resolvedTemporary -Recurse -Force
    }
}
